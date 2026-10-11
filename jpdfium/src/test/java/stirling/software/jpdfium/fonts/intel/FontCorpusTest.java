package stirling.software.jpdfium.fonts.intel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.fonts.PinnedFonts;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.util.NativeJsonParser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Manifest-driven font corpus runner.
 *
 * <p>Each manifest case pairs a fixture with the capability the resolver is
 * required to report and whether a repair is permitted. The point is not
 * coverage for its own sake: a case fails loudly when a repair is wrong, a used
 * glyph is dropped, a CID is remapped incorrectly, an ambiguity is guessed, or
 * an unsupported font is silently "fixed". PDFBox authors the controlled
 * fixtures and independently verifies extracted text; it never produces the
 * expected mapping.
 */
class FontCorpusTest {

    private static final String SAMPLE = "Hello World";

    @Test
    void corpusManifestCases() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        Path font = locateFont();
        List<Map<String, String>> cases = loadManifest();
        assertFalse(cases.isEmpty(), "font corpus manifest is empty");

        List<String> failures = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        int executed = 0;
        for (Map<String, String> c : cases) {
            String id = c.getOrDefault("id", "?");
            try {
                byte[] pdf = buildFixture(c, font);
                if (pdf == null) {
                    skipped.add(id + " (no embeddable font located)");
                    report.append("SKIP   ").append(id).append('\n');
                    continue;
                }
                executed++;
                report.append("RUN    ").append(verifyCase(c, pdf, failures)).append('\n');
            } catch (Throwable t) {
                failures.add(id + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
                report.append("ERROR  ").append(id).append(": ").append(t.getMessage()).append('\n');
            }
        }
        if (executed > 0) {
            report.append("TALLY  ").append(tally(report)).append('\n');
        }

        String summary = "font corpus: fixtures=" + cases.size()
                + " executed=" + executed
                + " skipped=" + skipped.size()
                + " failures=" + failures.size();
        assertTrue(failures.isEmpty(), () ->
                summary + "\n" + report + "failures:\n  "
                        + String.join("\n  ", failures)
                        + (skipped.isEmpty() ? "" : "\nskipped:\n  " + String.join("\n  ", skipped)));
    }

    /** Aggregate the per-case capability outcomes reported in the run log. */
    private static String tally(StringBuilder report) {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        for (String line : report.toString().split("\n")) {
            int arrow = line.indexOf("-> ");
            if (arrow < 0) {
                continue;
            }
            String caps = line.substring(arrow + 3);
            int end = caps.indexOf(']');
            if (end >= 0) {
                caps = caps.substring(0, end + 1);
            } else {
                int space = caps.indexOf(' ');
                caps = space >= 0 ? caps.substring(0, space) : caps;
            }
            for (String cap : caps.replace("[", "").replace("]", "").split(",")) {
                String key = cap.trim();
                if (!key.isEmpty()) {
                    counts.merge(key, 1, Integer::sum);
                }
            }
        }
        return counts.toString();
    }

    // ------------------------------------------------------------- contracts

    private static String verifyCase(Map<String, String> c, byte[] pdf, List<String> failures)
            throws Exception {
        String id = c.get("id");
        Set<String> allowed = new HashSet<>(
                Arrays.asList(c.getOrDefault("expect_capability", "").split("\\|")));
        String outcome = "";

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<String> observed = observedCapabilities(doc);
            outcome = new TreeSet<>(observed).toString();
            if (observed.stream().noneMatch(allowed::contains)) {
                failures.add(id + ": expected one of " + allowed
                        + " but observed " + new TreeSet<>(observed));
                return id + " -> " + outcome + " UNEXPECTED";
            }

            String repair = c.getOrDefault("expect_repair", "any");
            if ("true".equals(repair)) {
                int pre = countCapability(doc, FontCapability.MAPPING_REPAIR_AVAILABLE.name());
                int fixed = repairAll(doc);
                int post = countCapability(doc, FontCapability.MAPPING_REPAIR_AVAILABLE.name());
                if (fixed < 1) {
                    failures.add(id + ": expected repair to change at least one font but fixed=0");
                }
                if (allowed.contains(FontCapability.MAPPING_REPAIR_AVAILABLE.name())
                        && post >= pre) {
                    failures.add(id + ": repair left every repairable font unresolved (pre="
                            + pre + " post=" + post + ")");
                }
            } else if ("false".equals(repair)) {
                int fixed = repairAll(doc);
                if (fixed != 0) {
                    failures.add(id + ": no repair is permitted for this contract but fixed=" + fixed);
                }
            }

            // Semantic invariant: a confidently reported mapping must carry a real
            // Unicode scalar, and the resolved count must match the mapping rows.
            // Ambiguity is preserved as an unresolved row, never guessed.
            for (int p = 0; p < doc.pageCount(); p++) {
                for (FontEvidence f : FontIntelligence.inspectPage(doc, p)) {
                    if (f.mappingCount() != f.resolvedMappings().size()) {
                        failures.add(id + ": mappingCount disagrees with resolved mappings on "
                                + f.resourceName());
                    }
                    for (UnicodeMapping m : f.mappings()) {
                        if (m.provenance().resolved() && m.unicode() < 0) {
                            failures.add(id + ": resolved mapping without a Unicode scalar: code "
                                    + m.code() + " (" + m.provenance() + ")");
                        }
                    }
                }
            }

            String expectExtract = c.getOrDefault("expect_extract", "");
            String expectAll = c.getOrDefault("expect_extract_all", "");
            if (!expectExtract.isEmpty() || !expectAll.isEmpty()) {
                String text = extract(doc.saveBytes());
                if (!expectExtract.isEmpty() && !text.contains(expectExtract)) {
                    failures.add(id + ": expected extracted text to contain \"" + expectExtract
                            + "\" but was \"" + text + "\"");
                }
                for (String want : expectAll.split("\\|")) {
                    if (!want.isEmpty() && !text.contains(want)) {
                        failures.add(id + ": expected extracted text to contain \"" + want
                                + "\" but was \"" + text + "\"");
                    }
                }
            }
        }
        return id + " -> " + outcome;
    }

    private static List<String> observedCapabilities(PdfDocument doc) {
        List<String> out = new ArrayList<>();
        for (int p = 0; p < doc.pageCount(); p++) {
            for (FontEvidence f : FontIntelligence.inspectPage(doc, p)) {
                out.add(FontIntelligence.capability(f).name());
            }
        }
        return out;
    }

    private static int countCapability(PdfDocument doc, String capability) {
        int n = 0;
        for (int p = 0; p < doc.pageCount(); p++) {
            for (FontEvidence f : FontIntelligence.inspectPage(doc, p)) {
                if (FontIntelligence.capability(f).name().equals(capability)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int repairAll(PdfDocument doc) {
        int fixed = 0;
        for (int p = 0; p < doc.pageCount(); p++) {
            fixed += FontIntelligence.repairPage(doc, p);
        }
        return fixed;
    }

    // ------------------------------------------------------------- fixtures

    private static byte[] buildFixture(Map<String, String> c, Path font) throws Exception {
        return switch (c.get("kind")) {
            case "full_cid" -> font == null ? null : cidPdf(font, false);
            case "broken_cid" -> font == null ? null : stripToUnicode(cidPdf(font, false));
            case "partial_map" -> font == null ? null : partialMapPdf(font);
            case "subset_no_cmap" -> font == null ? null : stripToUnicode(cidPdf(font, true));
            case "broken_cid_two_pages" ->
                    font == null ? null : stripToUnicode(cidPdfTwoPages(font));
            case "differences_simple" -> differencesPdf();
            case "same_bytes_two_encodings" -> sameBytesTwoEncodingsPdf();
            case "resource" -> fixture(c.get("source"));
            default -> throw new IllegalArgumentException("unknown corpus kind: " + c.get("kind"));
        };
    }

    /** Author a CID font PDF with PDFBox. {@code embedSubset=true} lets PDFBox
     *  drop the Unicode cmap, which is exactly the evidence-losing case we test. */
    private static byte[] cidPdf(Path font, boolean embedSubset) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType0Font f;
            try (InputStream in = Files.newInputStream(font)) {
                f = PDType0Font.load(doc, in, embedSubset);
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(f, 24);
                cs.newLineAtOffset(72, 700);
                cs.showText(SAMPLE);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** One full-embed CID font shared by two pages; stripping its {@code /ToUnicode}
     *  must still let both pages be recovered and repaired. */
    private static byte[] cidPdfTwoPages(Path font) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDType0Font f;
            try (InputStream in = Files.newInputStream(font)) {
                f = PDType0Font.load(doc, in, false);
            }
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(f, 24);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(SAMPLE);
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** A non-embedded standard font whose {@code /Differences} reassigns code 65
     *  to a glyph whose Unicode is unrelated to the code value. Proves recovery
     *  follows code -> glyph name -> AGL, not code -> code. */
    private static byte[] differencesPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType1Font f = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(f, 24);
                cs.newLineAtOffset(72, 700);
                cs.showText("A");  // Helvetica WinAnsi code 0x41
                cs.endText();
            }
            setDifferences(f.getCOSObject(), new int[] {65, 66}, new String[] {"Euro", "bullet"});
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return stripToUnicode(out.toByteArray());
        }
    }

    /** Two resources with the same display name and the same (non-embedded)
     *  program bytes but different {@code /Differences} must resolve independently. */
    private static byte[] sameBytesTwoEncodingsPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType1Font f1 = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font f2 = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(f1, 24);
                cs.newLineAtOffset(72, 700);
                cs.showText("A");
                cs.setFont(f2, 24);
                cs.newLineAtOffset(0, -40);
                cs.showText("A");
                cs.endText();
            }
            setDifferences(f1.getCOSObject(), new int[] {65}, new String[] {"Euro"});
            setDifferences(f2.getCOSObject(), new int[] {65}, new String[] {"bullet"});
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return stripToUnicode(out.toByteArray());
        }
    }

    /** Replace a simple font's {@code /Encoding} with a WinAnsi base plus the given
     *  {@code /Differences} at the COS level, after the content has been written. */
    private static void setDifferences(COSDictionary fontDict, int[] codes, String[] names) {
        COSDictionary enc = new COSDictionary();
        enc.setItem(COSName.BASE_ENCODING, COSName.WIN_ANSI_ENCODING);
        COSArray diffs = new COSArray();
        for (int i = 0; i < codes.length; i++) {
            diffs.add(COSInteger.get(codes[i]));
            diffs.add(COSName.getPDFName(names[i]));
        }
        enc.setItem(COSName.DIFFERENCES, diffs);
        fontDict.setItem(COSName.ENCODING, enc);
    }

    /** Remove every declared {@code /ToUnicode} at the COS level, independent of
     *  any text extractor's interpretation of its content. */
    private static byte[] stripToUnicode(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                PDResources resources = page.getResources();
                if (resources == null) {
                    continue;
                }
                COSDictionary fonts = resources.getCOSObject().getCOSDictionary(COSName.FONT);
                if (fonts == null) {
                    continue;
                }
                for (COSName key : new ArrayList<>(fonts.keySet())) {
                    COSBase fontDict = fonts.getDictionaryObject(key);
                    if (fontDict instanceof COSDictionary dict) {
                        dict.removeItem(COSName.TO_UNICODE);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** A full-embed CID font whose {@code /ToUnicode} is replaced by a partial one
     *  that declares only the code for 'H'. A trustworthy-but-incomplete map must
     *  be extended in place: the declared entry survives and only the missing used
     *  codes are added — never the whole cmap. */
    private static byte[] partialMapPdf(Path font) throws Exception {
        byte[] full = cidPdf(font, false);
        int code;
        try (PDDocument doc = Loader.loadPDF(full)) {
            code = findFirstShownCode(doc);
        }
        assertTrue(code >= 0, "could not locate the first shown code in the authored content");
        return injectPartialToUnicode(stripToUnicode(full), code, 0x0048);
    }

    /** The first code the page actually shows (the leading bytes of the first
     *  {@code Tj} string), so a partial map can declare a genuinely used code. */
    private static int findFirstShownCode(PDDocument doc) throws Exception {
        for (PDPage page : doc.getPages()) {
            COSBase contents = page.getCOSObject().getDictionaryObject(COSName.CONTENTS);
            List<COSStream> streams = new ArrayList<>();
            if (contents instanceof COSStream s) {
                streams.add(s);
            } else if (contents instanceof COSArray a) {
                for (COSBase b : a) {
                    if (b instanceof COSStream s) {
                        streams.add(s);
                    }
                }
            }
            for (COSStream stream : streams) {
                String data;
                try (InputStream in = stream.createInputStream()) {
                    data = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                }
                Matcher lit = Pattern.compile("\\((.*?)\\)\\s*Tj", Pattern.DOTALL).matcher(data);
                if (lit.find()) {
                    byte[] b = lit.group(1).getBytes(StandardCharsets.ISO_8859_1);
                    if (b.length >= 2) {
                        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
                    }
                }
                Matcher hex = Pattern.compile("<([0-9A-Fa-f\\s]+)>\\s*Tj").matcher(data);
                if (hex.find()) {
                    String h = hex.group(1).replaceAll("\\s", "");
                    if (h.length() >= 4) {
                        return Integer.parseInt(h.substring(0, 4), 16);
                    }
                }
            }
        }
        return -1;
    }

    /** Install a one-entry {@code /ToUnicode} (bfchar) on every font resource. */
    private static byte[] injectPartialToUnicode(byte[] pdf, int code, int unicode)
            throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                PDResources resources = page.getResources();
                if (resources == null) {
                    continue;
                }
                COSDictionary fonts = resources.getCOSObject().getCOSDictionary(COSName.FONT);
                if (fonts == null) {
                    continue;
                }
                for (COSName key : new ArrayList<>(fonts.keySet())) {
                    COSBase fd = fonts.getDictionaryObject(key);
                    if (fd instanceof COSDictionary dict) {
                        COSStream stream = doc.getDocument().createCOSStream();
                        try (OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
                            os.write(tounicodeCmap(code, unicode).getBytes(StandardCharsets.US_ASCII));
                        }
                        dict.setItem(COSName.TO_UNICODE, stream);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static String tounicodeCmap(int code, int unicode) {
        return "/CIDInit /ProcSet findresource begin\n"
                + "12 dict begin\nbegincmap\n"
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                + "/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n"
                + "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n"
                + "1 beginbfchar\n"
                + String.format("<%04X> <%04X>\n", code & 0xFFFF, unicode & 0xFFFF)
                + "endbfchar\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend";
    }

    private static String extract(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc).trim();
        }
    }

    // ------------------------------------------------------------- plumbing

    private static List<Map<String, String>> loadManifest() throws Exception {
        try (InputStream in = FontCorpusTest.class.getResourceAsStream("/font-corpus/manifest.json")) {
            assertTrue(in != null, "missing /font-corpus/manifest.json");
            return NativeJsonParser.parseArray(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static byte[] fixture(String resource) throws Exception {
        try (InputStream in = FontCorpusTest.class.getResourceAsStream(resource)) {
            assertTrue(in != null, "missing test resource " + resource);
            return in.readAllBytes();
        }
    }

    private static Path locateFont() {
        return PinnedFonts.primary();
    }
}

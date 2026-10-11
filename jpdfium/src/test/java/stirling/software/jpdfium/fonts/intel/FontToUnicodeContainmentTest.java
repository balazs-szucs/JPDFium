package stirling.software.jpdfium.fonts.intel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.fonts.PinnedFonts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Containment regressions for {@code /ToUnicode} repair. Each unsupported form
 * is exercised independently so a single passing detector cannot mask another,
 * and the document is always closed with try-with-resources so the test does not
 * itself leak ownership.
 */
class FontToUnicodeContainmentTest {

    private static final String SAMPLE = "Hello World";

    // ------------------------------------------------- unsupported forms, one per test

    @Test
    void multicharDestinationIsRefused() throws Exception {
        assertRefused(mapWithChar("<0003> <00660069>\n"));
    }

    @Test
    void malformedSurrogateDestinationIsRefused() throws Exception {
        // A lone high surrogate is not a valid scalar and cannot be reproduced.
        assertRefused(mapWithChar("<0004> <D8400061>\n"));
    }

    @Test
    void arrayBfrangeIsRefused() throws Exception {
        assertRefused("1 beginbfrange\n<0010> <0012> [<0041> <0042> <0043>]\nendbfrange\n");
    }

    @Test
    void usecmapInheritanceIsRefused() throws Exception {
        assertRefused("1 beginbfchar\n<0048> <0048>\nendbfchar\nAdobe-Japan1-UCS2 usecmap\n");
    }

    @Test
    void surrogateCrossingRangeIsRefused() throws Exception {
        // Endpoints <D7FE> .. <D801> straddle the surrogate block; both endpoints
        // are individually plausible but the interior is not.
        assertRefused("1 beginbfrange\n<0000> <0003> <D7FE>\nendbfrange\n");
    }

    @Test
    void unterminatedBlockIsRefused() throws Exception {
        // A begin without its matching end is malformed.
        assertRefused("1 beginbfchar\n<0048> <0048>\n");
    }

    // ------------------------------------------------- supported cases

    @Test
    void completeSupportedMapIsAStrictNoOp() throws Exception {
        Path font = requireFont();
        byte[] pdf = installToUnicode(cidPdf(font), completeSupportedCmap());
        byte[] before = firstToUnicodeBytes(pdf);
        assertNotNull(before, "fixture must carry a /ToUnicode");

        int fixed;
        byte[] repaired;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            fixed = repairAll(doc);
            repaired = doc.saveBytes();
        }

        assertEquals(0, fixed, "a complete supported map needs no repair");
        assertArrayEquals(before, firstToUnicodeBytes(repaired),
                "a complete supported map must be a strict no-op");
    }

    @Test
    void wellFormedSurrogatePairDestinationIsAccepted() throws Exception {
        Path font = requireFont();
        // A well-formed UTF-16BE surrogate pair is a valid supplementary scalar
        // and is reproduced as-is, so it must parse as valid, not be refused.
        byte[] pdf = installToUnicode(cidPdf(font), header()
                + mapWithChar("<0048> <D840DC00>\n") + footer());
        int fixed;
        byte[] repaired;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<FontEvidence> fonts = FontIntelligence.inspectPage(doc, 0);
            assertTrue(fonts.stream().anyMatch(FontEvidence::toUnicodeValid),
                    "a well-formed surrogate pair must parse as a valid /ToUnicode");
            // The authored map covers only one code; the other used codes are
            // legitimately added, so this is an accepted extension, not a refusal.
            fixed = repairAll(doc);
            repaired = doc.saveBytes();
        }
        assertTrue(fixed >= 1, "an accepted map should be extended with missing used codes");
        String after = new String(firstToUnicodeBytes(repaired), StandardCharsets.US_ASCII)
                .toUpperCase();
        assertTrue(after.contains("<0048> <D840DC00>"),
                "the declared surrogate pair must be preserved: " + after);
    }

    @Test
    void supportedMapWithOneUsedCodeIsExtendedAndConverges() throws Exception {
        Path font = requireFont();
        int code = firstShownCode(font, SAMPLE);
        String hex = String.format("%04X", code);
        // Identity entry for one used code; the other used codes are missing.
        String cmap = header()
                + "1 beginbfchar\n<" + hex + "> <" + hex + ">\nendbfchar\n"
                + footer();
        byte[] pdf = installToUnicode(cidPdf(font), cmap);

        int fixed;
        int secondFixed;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            fixed = repairAll(doc);
            // A repeated repair must converge: the entries it just added must not
            // be treated as missing again.
            byte[] once = doc.saveBytes();
            try (PdfDocument again = PdfDocument.open(once)) {
                secondFixed = repairAll(again);
            }
        }

        assertEquals(1, fixed,
                "exactly one font should receive the missing used codes");
        assertEquals(0, secondFixed, "repair must converge, not oscillate");
    }

    // ------------------------------------------------------------- assertions

    private static void assertRefused(String cmapBody) throws Exception {
        Path font = requireFont();
        byte[] pdf = installToUnicode(cidPdf(font), header() + cmapBody + footer());
        byte[] before = firstToUnicodeBytes(pdf);
        assertNotNull(before, "fixture must carry a /ToUnicode");

        int fixed;
        byte[] repaired;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            fixed = repairAll(doc);
            repaired = doc.saveBytes();
        }

        assertEquals(0, fixed, "an unsupported map must be refused, not repaired");
        assertArrayEquals(before, firstToUnicodeBytes(repaired),
                "a refused /ToUnicode must be preserved byte-for-byte");
    }

    // ------------------------------------------------------------- fixtures

    private static String mapWithChar(String entry) {
        return "1 beginbfchar\n" + entry + "endbfchar\n";
    }

    /** A CID font PDF with PDFBox, carrying a valid authored /ToUnicode. */
    private static byte[] cidPdf(Path font) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType0Font f;
            try (InputStream in = Files.newInputStream(font)) {
                f = PDType0Font.load(doc, in, false);
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

    /** The 16-bit code the font assigns to the first character of {@code text}. */
    private static int firstShownCode(Path font, String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            try (InputStream in = Files.newInputStream(font)) {
                PDType0Font f = PDType0Font.load(doc, in, false);
                byte[] enc = f.encode(text.substring(0, 1));
                return ((enc[0] & 0xFF) << 8) | (enc[1] & 0xFF);
            }
        }
    }

    private static String header() {
        return "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                + "/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n"
                + "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n";
    }

    private static String footer() {
        return "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend";
    }

    /**
     * A supported identity range below the surrogate block. The range deliberately
     * stops at U+D7FF so it is a valid scalar mapping, not the former
     * {@code <0000> <FFFF>} range that span surrogates.
     */
    private static String completeSupportedCmap() {
        return header()
                + "1 beginbfrange\n<0000> <D7FF> <0000>\nendbfrange\n"
                + footer();
    }

    // ------------------------------------------------------------- plumbing

    private static byte[] installToUnicode(byte[] pdf, String cmap) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                PDResources res = page.getResources();
                if (res == null) {
                    continue;
                }
                COSDictionary fonts = res.getCOSObject().getCOSDictionary(COSName.FONT);
                if (fonts == null) {
                    continue;
                }
                for (COSName key : new ArrayList<>(fonts.keySet())) {
                    COSBase fd = fonts.getDictionaryObject(key);
                    if (fd instanceof COSDictionary dict) {
                        COSStream stream = doc.getDocument().createCOSStream();
                        try (OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
                            os.write(cmap.getBytes(StandardCharsets.US_ASCII));
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

    /** Decoded bytes of the first font resource's {@code /ToUnicode}. */
    private static byte[] firstToUnicodeBytes(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                PDResources res = page.getResources();
                if (res == null) {
                    continue;
                }
                COSDictionary fonts = res.getCOSObject().getCOSDictionary(COSName.FONT);
                if (fonts == null) {
                    continue;
                }
                for (COSName key : new ArrayList<>(fonts.keySet())) {
                    COSBase fd = fonts.getDictionaryObject(key);
                    if (fd instanceof COSDictionary dict) {
                        COSBase tu = dict.getDictionaryObject(COSName.TO_UNICODE);
                        if (tu instanceof COSStream s) {
                            try (InputStream in = s.createInputStream()) {
                                return in.readAllBytes();
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static int repairAll(PdfDocument doc) {
        int fixed = 0;
        for (int p = 0; p < doc.pageCount(); p++) {
            fixed += FontIntelligence.repairPage(doc, p);
        }
        return fixed;
    }

    private static Path requireFont() {
        return PinnedFonts.primary();
    }
}

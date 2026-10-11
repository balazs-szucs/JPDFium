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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Containment regressions for {@code /ToUnicode} repair: a partial map that
 * mixes supported entries with forms the parser cannot reproduce must be left
 * untouched rather than rewritten, and a complete supported map must be a strict
 * no-op. Repairing one mapping must never corrupt another valid mapping.
 *
 * <p>The regression fixture deliberately mixes, in one map:
 * <ul>
 *   <li>a supported entry ({@code <0048> <0048>});
 *   <li>a multicharacter destination ({@code <0003> <00660069>}, "fi");
 *   <li>a surrogate-pair destination ({@code <0004> <D840DC00>});
 *   <li>an array {@code bfrange} destination.
 * </ul>
 * Any of the unsupported forms means the whole map must be preserved, not
 * partially rewritten.
 */
class FontToUnicodeContainmentTest {

    private static final String SAMPLE = "Hello World";

    @Test
    void unsupportedCmapFormsAreRefusedWithoutMutation() throws Exception {
        Path font = locateFont();
        assumeTrue(font != null, "no redistributable test font available");

        byte[] pdf = installToUnicode(cidPdf(font), unsupportedCmap());
        byte[] before = firstToUnicodeBytes(pdf);
        assertNotNull(before, "fixture must carry a /ToUnicode");

        PdfDocument doc = PdfDocument.open(pdf);
        int fixed = repairAll(doc);
        byte[] repaired = doc.saveBytes();

        assertEquals(0, fixed,
                "a map with unsupported forms must be refused, not repaired");
        assertArrayEquals(before, firstToUnicodeBytes(repaired),
                "refused /ToUnicode must be preserved byte-for-byte");
    }

    @Test
    void completeSupportedMapIsAStrictNoOp() throws Exception {
        Path font = locateFont();
        assumeTrue(font != null, "no redistributable test font available");

        byte[] pdf = installToUnicode(cidPdf(font), completeSupportedCmap());
        byte[] before = firstToUnicodeBytes(pdf);

        PdfDocument doc = PdfDocument.open(pdf);
        int fixed = repairAll(doc);
        byte[] repaired = doc.saveBytes();

        assertEquals(0, fixed, "a complete supported map needs no repair");
        assertArrayEquals(before, firstToUnicodeBytes(repaired),
                "a complete supported map must be a strict no-op");
    }

    // ------------------------------------------------------------- fixtures

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

    private static String header() {
        return "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                + "/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n"
                + "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n";
    }

    private static String footer() {
        return "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend";
    }

    /** Supported entry plus an unsupported multichar destination, surrogate pair,
     *  and array bfrange. */
    private static String unsupportedCmap() {
        return header()
                + "4 beginbfchar\n"
                + "<0048> <0048>\n"
                + "<0003> <00660069>\n"
                + "<0004> <D840DC00>\n"
                + "<0005> <00110000>\n"
                + "endbfchar\n"
                + "1 beginbfrange\n"
                + "<0010> <0012> [<0041> <0042> <0043>]\n"
                + "endbfrange\n"
                + footer();
    }

    /** A single scalar bfrange covering every 16-bit code: nothing is missing, so
     *  nothing may be rewritten. */
    private static String completeSupportedCmap() {
        return header()
                + "1 beginbfrange\n"
                + "<0000> <FFFF> <0000>\n"
                + "endbfrange\n"
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

    private static Path locateFont() {
        List<String> candidates = new ArrayList<>();
        String override = System.getenv("JPDFIUM_TEST_FONT");
        if (override != null && !override.isBlank()) {
            candidates.add(override);
        }
        candidates.add("/System/Library/Fonts/Supplemental/Arial.ttf");
        candidates.add("/System/Library/Fonts/Supplemental/Times New Roman.ttf");
        candidates.add("/Library/Fonts/Arial.ttf");
        candidates.add("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
        candidates.add("/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf");
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isReadable(path)) {
                return path;
            }
        }
        return null;
    }
}

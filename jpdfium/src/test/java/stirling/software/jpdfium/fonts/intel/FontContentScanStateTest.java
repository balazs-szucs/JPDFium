package stirling.software.jpdfium.fonts.intel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.fonts.PinnedFonts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The page content scanner must keep one font/graphics state across the whole
 * concatenated page-content sequence and must honour the font-selection routes
 * the graphics state offers.
 *
 * <p>A page's {@code /Contents} may be an array of streams that are interpreted
 * as one instruction sequence, so a {@code Tf} in the first stream governs a show
 * operator in the second. An {@code ExtGState} invoked by {@code gs} may also
 * carry a {@code /Font} entry that changes the selected font. Both routes must
 * attribute shown bytes to the correct font, or mark the page's usage unknown,
 * rather than attribute them to whichever font was selected last by {@code Tf}.
 */
class FontContentScanStateTest {

    private static final String HEADER =
            "/CIDInit /ProcSet findresource begin\n"
            + "12 dict begin\n"
            + "begincmap\n"
            + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
            + "/CMapName /test-UCS def\n"
            + "/CMapType 2 def\n"
            + "1 begincodespacerange\n"
            + "<00> <FF>\n"
            + "endcodespacerange\n";

    private static final String FOOTER =
            "endcmap\n"
            + "CMapName currentdict /CMap defineresource pop\n"
            + "end\n"
            + "end\n";

    /** Declares only the code for 'A'; the code for 'B' (0x42) is missing. */
    private static final String PARTIAL = "1 beginbfchar\n<41> <0041>\nendbfchar\n";

    @Test
    void splitContentStreamsKeepTheSelectedFont() throws Exception {
        byte[] single = pdf(
                List.of("BT /F1 12 Tf 20 700 Td (AB) Tj ET"),
                PARTIAL,
                false);
        byte[] split = pdf(
                List.of("BT /F1 12 Tf 20 700 Td", "(AB) Tj ET"),
                PARTIAL,
                false);

        String singleMap = repair(single);
        String splitMap = repair(split);

        assertNotNull(singleMap);
        assertNotNull(splitMap);
        assertTrue(singleMap.contains("<42> <0042>"),
                "the single-stream page must extend with B's code: " + singleMap);
        assertTrue(splitMap.contains("<41> <0041>"),
                "the declared mapping must survive in the split page: " + splitMap);
        assertTrue(splitMap.contains("<42> <0042>"),
                "a Tf in the first content stream must still select the font for a "
                        + "show operator in the second: " + splitMap);
        assertEquals(singleMap, splitMap,
                "split content streams must produce the same used-code result as one stream");
    }

    @Test
    void extGStateFontSelectionAttributesShownBytesToTheSelectedFont() throws Exception {
        // F1 is a valid partial map used only for 'A'; F2 (absent map) is selected
        // by /GS1's /Font entry and shows 'B'. F1 must NOT gain B's code.
        byte[] pdf = pdf(
                List.of("BT /F1 12 Tf 20 700 Td (A) Tj ET\n"
                        + "/GS1 gs\n"
                        + "BT 20 680 Td (B) Tj ET"),
                PARTIAL,
                true);

        String f1Before = toUnicodeText(pdf, 0, "F1");
        Outcome outcome = repairAll(pdf);

        assertEquals(f1Before, outcome.f1Map(),
                "an ExtGState-selected font must not make any other font look used: "
                        + outcome.f1Map());
        assertNotNull(outcome.f2Map(),
                "the ExtGState-selected font must still be repaired");
        assertTrue(outcome.f2Map().contains("begincmap"), outcome.f2Map());
    }

    // ------------------------------------------------------------- fixtures

    private record Outcome(int fixed, String f1Map, String f2Map) { }

    /** Repair every page, then read both fonts' declared maps back. */
    private static Outcome repairAll(byte[] pdf) {
        byte[] after;
        int fixed;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            fixed = FontIntelligence.repairToUnicode(doc);
            after = doc.saveBytes();
        }
        return new Outcome(fixed, toUnicodeText(after, 0, "F1"), toUnicodeText(after, 0, "F2"));
    }

    private static String repair(byte[] pdf) {
        return repairAll(pdf).f1Map();
    }

    /**
     * One page with two simple TrueType fonts (/F1, /F2). F1 carries {@code declared}
     * as its /ToUnicode; optionally an ExtGState /GS1 selects /F2. The given content
     * strings become the page's /Contents array.
     */
    private static byte[] pdf(List<String> contents, String declared, boolean withGState)
            throws Exception {
        Path font = PinnedFonts.primary();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDResources res = new PDResources();
            PDTrueTypeFont f1 = load(doc, font);
            PDTrueTypeFont f2 = load(doc, font);
            res.put(COSName.getPDFName("F1"), f1);
            res.put(COSName.getPDFName("F2"), f2);
            page.setResources(res);
            installToUnicode(doc, f1.getCOSObject(), declared);
            if (withGState) {
                COSDictionary gs = new COSDictionary();
                gs.setName(COSName.TYPE, "ExtGState");
                COSArray fa = new COSArray();
                fa.add(f2.getCOSObject());
                fa.add(new COSFloat(12));
                gs.setItem(COSName.getPDFName("Font"), fa);
                COSDictionary states = new COSDictionary();
                states.setItem(COSName.getPDFName("GS1"), gs);
                res.getCOSObject().setItem(COSName.getPDFName("ExtGState"), states);
            }
            setContents(doc, page, contents);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static PDTrueTypeFont load(PDDocument doc, Path font) throws Exception {
        try (InputStream in = Files.newInputStream(font)) {
            return PDTrueTypeFont.load(doc, in, WinAnsiEncoding.INSTANCE);
        }
    }

    private static void setContents(PDDocument doc, PDPage page, List<String> contents)
            throws Exception {
        COSArray array = new COSArray();
        for (String content : contents) {
            COSStream stream = doc.getDocument().createCOSStream();
            try (OutputStream os = stream.createOutputStream()) {
                os.write(content.getBytes(StandardCharsets.ISO_8859_1));
            }
            array.add(stream);
        }
        page.getCOSObject().setItem(COSName.CONTENTS, array);
    }

    private static void installToUnicode(PDDocument doc, COSDictionary fontDict, String cmap)
            throws Exception {
        fontDict.removeItem(COSName.TO_UNICODE);
        COSStream stream = doc.getDocument().createCOSStream();
        try (OutputStream os = stream.createOutputStream()) {
            os.write((HEADER + cmap + FOOTER).getBytes(StandardCharsets.ISO_8859_1));
        }
        fontDict.setItem(COSName.TO_UNICODE, stream);
    }

    /** The declared /ToUnicode stream text of the named resource, or null. */
    private static String toUnicodeText(byte[] pdf, int pageIndex, String resourceName) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDPage page = doc.getPage(pageIndex);
            PDResources res = page.getResources();
            if (res == null) {
                return null;
            }
            COSDictionary fonts = res.getCOSObject().getCOSDictionary(COSName.FONT);
            if (fonts == null) {
                return null;
            }
            COSBase fd = fonts.getDictionaryObject(COSName.getPDFName(resourceName));
            if (fd instanceof COSDictionary dict
                    && dict.getDictionaryObject(COSName.TO_UNICODE) instanceof COSStream stream) {
                try (InputStream in = stream.createInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                }
            }
            return null;
        } catch (Exception e) {
            throw new AssertionError("could not read the declared /ToUnicode map", e);
        }
    }
}

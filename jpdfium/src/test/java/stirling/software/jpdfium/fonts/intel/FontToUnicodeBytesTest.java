package stirling.software.jpdfium.fonts.intel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

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
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.fonts.PinnedFonts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The /ToUnicode decoder must read hex operands as bytes, not as integers.
 *
 * <p>A CMap destination is a sequence of bytes reinterpreted as UTF-16BE. An
 * odd digit count pads a trailing zero nibble, so {@code <041>} is the two bytes
 * {@code 04 10} and decodes to U+0410 — not U+0041. When an existing declaration
 * is preserved through an extension, it must survive byte-for-byte, including a
 * declared U+0000, and a map whose block count disagrees with its contents or
 * that carries invalid hex must be refused whole rather than partially rewritten.
 */
class FontToUnicodeBytesTest {

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

    @Test
    void oddDigitDestinationDecodesAsBytesNotAnInteger() throws Exception {
        // <041> is bytes 04 10 -> U+0410, not the integer 0x0041.
        byte[] pdf = pdfWithDeclaredMap("1 beginbfchar\n<41> <041>\nendbfchar\n");

        RepairOutcome outcome = repair(pdf);

        assertTrue(outcome.fixed() >= 1, "the missing used code should extend the map");
        assertTrue(outcome.cmap().contains("<41> <0410>"),
                "the declared odd-digit destination must decode as bytes 04 10: " + outcome.cmap());
        assertTrue(outcome.cmap().contains("<42> <0042>"),
                "the missing used code must be added: " + outcome.cmap());
    }

    @Test
    void whitespaceInsideAHexOperandIsIgnored() throws Exception {
        // <4 1> is the single byte 0x41; the declaration is preserved and extended.
        byte[] pdf = pdfWithDeclaredMap("1 beginbfchar\n<4 1> <0041>\nendbfchar\n");

        RepairOutcome outcome = repair(pdf);

        assertTrue(outcome.fixed() >= 1, "the missing used code should extend the map");
        assertTrue(outcome.cmap().contains("<41> <0041>"),
                "a whitespace-broken operands must still decode to code 0x41: " + outcome.cmap());
        assertTrue(outcome.cmap().contains("<42> <0042>"), outcome.cmap());
    }

    @Test
    void invalidHexCharacterRefusesTheWholeRewrite() throws Exception {
        byte[] pdf = pdfWithDeclaredMap("1 beginbfchar\n<4G> <0041>\nendbfchar\n");

        RepairOutcome outcome = repair(pdf);

        assertEquals(0, outcome.fixed(), "an invalid hex operand must leave the font untouched");
        assertEquals(outcome.before(), outcome.cmap(),
                "a refused rewrite must not alter the declared map");
    }

    @Test
    void aDeclaredDestinationOfZeroSurvivesAnExtension() throws Exception {
        // 0x41 -> U+0000 must not be mistaken for the unresolved sentinel and
        // dropped while 0x42 is added.
        byte[] pdf = pdfWithDeclaredMap("1 beginbfchar\n<41> <0000>\nendbfchar\n");

        RepairOutcome outcome = repair(pdf);

        assertTrue(outcome.fixed() >= 1, "the missing used code should extend the map");
        assertTrue(outcome.cmap().contains("<41> <0000>"),
                "a declared U+0000 must be preserved on extension: " + outcome.cmap());
        assertTrue(outcome.cmap().contains("<42> <0042>"), outcome.cmap());
    }

    @Test
    void aBlockCountThatDisagreesWithItsContentsIsRefused() throws Exception {
        // Declares two entries but supplies one: malformed, must be left alone.
        byte[] pdf = pdfWithDeclaredMap("2 beginbfchar\n<41> <0041>\nendbfchar\n");

        RepairOutcome outcome = repair(pdf);

        assertEquals(0, outcome.fixed(),
                "a block count that disagrees with its contents must refuse the rewrite");
        assertEquals(outcome.before(), outcome.cmap(),
                "a refused rewrite must not alter the declared map");
    }

    // ------------------------------------------------------------- fixtures

    private record RepairOutcome(int fixed, String cmap, String before) { }

    private static RepairOutcome repair(byte[] pdf) {
        String before = toUnicodeText(pdf);
        int fixed;
        byte[] after;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            fixed = FontIntelligence.repairToUnicode(doc);
            after = doc.saveBytes();
        }
        return new RepairOutcome(fixed, toUnicodeText(after), before);
    }

    /** One page showing "AB" with a simple TrueType font and a declared map. */
    private static byte[] pdfWithDeclaredMap(String cmap) throws Exception {
        Path font = PinnedFonts.primary();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDTrueTypeFont f;
            try (InputStream in = Files.newInputStream(font)) {
                f = PDTrueTypeFont.load(doc, in, WinAnsiEncoding.INSTANCE);
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(f, 24);
                cs.newLineAtOffset(72, 700);
                cs.showText("AB");
                cs.endText();
            }
            installToUnicode(doc, cmap);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void installToUnicode(PDDocument doc, String cmap) throws Exception {
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
                    dict.removeItem(COSName.TO_UNICODE);
                    COSStream stream = doc.getDocument().createCOSStream();
                    try (OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
                        os.write((HEADER + cmap + FOOTER).getBytes(StandardCharsets.ISO_8859_1));
                    }
                    dict.setItem(COSName.TO_UNICODE, stream);
                }
            }
        }
    }

    /** The declared /ToUnicode stream text of the first font on the first page. */
    private static String toUnicodeText(byte[] pdf) {
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
                    if (fd instanceof COSDictionary dict && dict.getDictionaryObject(COSName.TO_UNICODE)
                            instanceof COSStream stream) {
                        try (InputStream in = stream.createInputStream()) {
                            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            throw new AssertionError("could not read the declared /ToUnicode map", e);
        }
    }
}

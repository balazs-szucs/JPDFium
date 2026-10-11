package stirling.software.jpdfium.fonts.intel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import org.apache.pdfbox.cos.COSArray;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Evidence fidelity for composite fonts: what the CID mapper reports must match
 * what the embedded program actually says. Nothing may be silently dropped, and
 * ambiguity must never be promoted to a confident mapping.
 *
 * <p>The ambiguity asserted here is the one the shipped program can actually
 * produce: the pinned Ahem program reaches eight glyphs from two code points
 * each. The glyph-name ambiguity route (two glyphs sharing one name) is wired in
 * {@code resolve_glyph_name} but not reachable from a fixture, because the pinned
 * corpus contains no program with duplicate glyph names; a test that fabricated
 * that state would prove nothing about the shipped code.
 */
class FontMappingFidelityTest {

    @Test
    void anAmbiguousGlyphKeepsItsAmbiguityDistinctFromResolvedCodes() throws Exception {
        byte[] pdf = cidFontPdf(PinnedFonts.primary(), null);
        List<UnicodeMapping> mappings = mappingsOf(pdf);

        // With Identity-H the code is the gid. gid 15 is reached by both 0x2d and
        // 0x2010, so its meaning is ambiguous; gid 35 ('A') is reached by one code.
        UnicodeMapping ambiguous = byCode(mappings, 15);
        assertEquals(FontProvenance.AMBIGUOUS, ambiguous.provenance(),
                "a glyph reached by two code points must be reported ambiguous");
        assertEquals(-1, ambiguous.unicode(),
                "an ambiguous code must carry no scalar, not a guessed one");

        UnicodeMapping resolved = byCode(mappings, 35);
        assertEquals(0x41, resolved.unicode(), "gid 35 is 'A'");
        assertEquals(FontProvenance.RECOVERED, resolved.provenance());
        assertNotEquals(ambiguous.provenance(), resolved.provenance(),
                "ambiguity and recovery must remain distinct provenances");
    }

    @Test
    void twoCidsSelectingOneGlyphAreBothRepresented() throws Exception {
        int gidA = 35; // cmap('A'); both CID 1 and CID 2 select this one glyph
        byte[] pdf = cidFontPdf(PinnedFonts.primary(), (doc, descendant) -> {
            try {
                descendant.setItem(COSName.getPDFName("CIDToGIDMap"),
                        cidToGidStream(doc, new int[] {0, gidA, gidA}));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        List<UnicodeMapping> mappings = mappingsOf(pdf);
        UnicodeMapping first = byCode(mappings, 1);
        UnicodeMapping second = byCode(mappings, 2);
        assertEquals(0x41, first.unicode(), "CID 1 selects 'A'");
        assertEquals(FontProvenance.RECOVERED, first.provenance());
        assertEquals(0x41, second.unicode(),
                "CID 2 selects the same glyph and must also be represented, not dropped");
        assertEquals(FontProvenance.RECOVERED, second.provenance());
    }

    // ------------------------------------------------------------- fixtures

    private static List<UnicodeMapping> mappingsOf(byte[] pdf) throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<FontEvidence> fonts = FontIntelligence.inspectPage(doc, 0);
            assertEquals(1, fonts.size(), "the fixture carries exactly one font resource");
            return fonts.get(0).mappings();
        }
    }

    private static UnicodeMapping byCode(List<UnicodeMapping> mappings, int code) {
        return mappings.stream().filter(m -> m.code() == code).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no mapping for code " + code + " among " + mappings.size() + " rows"));
    }

    /** One page, one composite (Type0) font showing text, then {@code tweak}. */
    private static byte[] cidFontPdf(Path font, BiConsumer<PDDocument, COSDictionary> tweak)
            throws Exception {
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
                cs.showText("Hello");
                cs.endText();
            }
            if (tweak != null) {
                tweak.accept(doc, descendantFont(firstFontDict(doc)));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** A big-endian CID-to-GID stream: entry i is the glyph CID i selects. */
    private static COSStream cidToGidStream(PDDocument doc, int[] cidToGid) throws Exception {
        byte[] bytes = new byte[cidToGid.length * 2];
        for (int i = 0; i < cidToGid.length; i++) {
            bytes[2 * i] = (byte) ((cidToGid[i] >> 8) & 0xff);
            bytes[2 * i + 1] = (byte) (cidToGid[i] & 0xff);
        }
        COSStream stream = doc.getDocument().createCOSStream();
        try (OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
            os.write(bytes);
        }
        return stream;
    }

    private static COSDictionary firstFontDict(PDDocument doc) {
        PDResources res = doc.getPage(0).getResources();
        COSDictionary fonts = res.getCOSObject().getCOSDictionary(COSName.FONT);
        for (COSName key : new ArrayList<>(fonts.keySet())) {
            COSBase v = fonts.getDictionaryObject(key);
            if (v instanceof COSDictionary d) {
                return d;
            }
        }
        throw new AssertionError("page 0 has no font resource");
    }

    private static COSDictionary descendantFont(COSDictionary font) {
        COSBase d = font.getDictionaryObject(COSName.DESCENDANT_FONTS);
        if (d instanceof COSArray arr
                && arr.size() > 0
                && arr.getObject(0) instanceof COSDictionary desc) {
            return desc;
        }
        throw new AssertionError("composite font has no descendant");
    }
}

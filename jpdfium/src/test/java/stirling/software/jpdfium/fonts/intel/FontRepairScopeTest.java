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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repair plan is an authorization, not a suggestion: applying it must not
 * touch a font the plan did not cover, and a plan whose document state has moved
 * on must be refused rather than re-applied.
 */
class FontRepairScopeTest {

    @Test
    void singlePlanIsRefusedWhenThePageHasAnotherPendingRepair() throws Exception {
        List<Path> fonts = twoFonts();
        byte[] pdf = twoFontPdf(fonts.get(0), fonts.get(1));

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<FontRepairPlan> plans = FontIntelligence.repairPlans(doc);
            assertEquals(2, plans.size(),
                    "two distinct broken fonts must yield two plans");

            assertThrows(StaleRepairPlanException.class,
                    () -> FontIntelligence.applyRepair(doc, plans.get(0)),
                    "a plan that covers only one of two pending repairs must be refused");

            for (FontEvidence e : FontIntelligence.inspectPage(doc, 0)) {
                assertFalse(e.toUnicodeValid(),
                        "a refused plan must not repair anything: " + e.resourceName());
            }
        }
    }

    @Test
    void explicitPageWideAuthorisationRepairsEveryPendingFont() throws Exception {
        List<Path> fonts = twoFonts();
        byte[] pdf = twoFontPdf(fonts.get(0), fonts.get(1));

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<FontRepairPlan> plans = FontIntelligence.repairPlans(doc);
            assertEquals(2, plans.size(), "two distinct broken fonts must yield two plans");

            int fixed = FontIntelligence.applyRepairs(doc, plans);
            assertTrue(fixed >= 2, "both authorised fonts should be repaired, got " + fixed);

            for (FontEvidence e : FontIntelligence.inspectPage(doc, 0)) {
                assertTrue(e.toUnicodeValid(),
                        "every authorised font must end up with a valid /ToUnicode: "
                                + e.resourceName());
            }
        }
    }

    @Test
    void stalePlanIsRefusedAfterTheFontIsRepaired() throws Exception {
        Path font = requireFont();
        byte[] pdf = oneFontPdf(font);

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<FontRepairPlan> plans = FontIntelligence.repairPlans(doc);
            assertEquals(1, plans.size(), "one broken font must yield one plan");
            FontRepairPlan stale = plans.get(0);

            FontIntelligence.applyRepairs(doc, plans);
            assertTrue(FontIntelligence.inspectPage(doc, 0).stream()
                            .allMatch(FontEvidence::toUnicodeValid),
                    "the repair should have completed");

            assertThrows(StaleRepairPlanException.class,
                    () -> FontIntelligence.applyRepair(doc, stale),
                    "a plan whose font is already repaired must be refused, not re-applied");
        }
    }

    @Test
    void programIdentityIsPartOfThePlanIdentity() {
        List<UnicodeMapping> mappings =
                List.of(new UnicodeMapping(65, 65, FontProvenance.RECOVERED, "fixture"));

        FontRepairPlan first = FontIntelligence.repairPlans(evidence(1024L, 500, mappings)).get(0);
        FontRepairPlan second = FontIntelligence.repairPlans(evidence(2048L, 500, mappings)).get(0);
        FontRepairPlan third = FontIntelligence.repairPlans(evidence(1024L, 800, mappings)).get(0);

        assertNotEquals(first.stateFingerprint(), second.stateFingerprint(),
                "a different font program (byte length) must change the plan identity");
        assertNotEquals(first.stateFingerprint(), third.stateFingerprint(),
                "a different font program (glyph count) must change the plan identity");
    }

    @Test
    void authorisingOneFontCannotExtendAValidPartialMapOnAnother() throws Exception {
        List<Path> fonts = twoFonts();
        byte[] pdf = pdfWithAbsentAndPartialMap(fonts.get(0), fonts.get(1));

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<FontEvidence> before = FontIntelligence.inspectPage(doc, 0);
            FontEvidence absent = before.stream().filter(e -> !e.hasToUnicode())
                    .findFirst().orElseThrow();
            FontEvidence partial = before.stream().filter(FontEvidence::hasToUnicode)
                    .findFirst().orElseThrow();
            assertTrue(partial.toUnicodeValid(), "the second font must carry a valid partial map");

            List<FontRepairPlan> plans = FontIntelligence.repairPlans(doc);
            assertEquals(2, plans.size(),
                    "an absent map and a extendable partial map must both yield a plan");

            FontRepairPlan absentPlan = plans.stream()
                    .filter(p -> p.resourceName().equals(absent.resourceName()))
                    .findFirst().orElseThrow();
            assertThrows(StaleRepairPlanException.class,
                    () -> FontIntelligence.applyRepair(doc, absentPlan),
                    "authorising only the absent map must not be allowed to extend the partial one");
            assertEquals(before, FontIntelligence.inspectPage(doc, 0),
                    "a refused page-wide repair must leave every font exactly as it was");
        }
    }

    @Test
    void pageWideAuthorisationRepairsAnAbsentAndAnExtendableMap() throws Exception {
        List<Path> fonts = twoFonts();
        byte[] pdf = pdfWithAbsentAndPartialMap(fonts.get(0), fonts.get(1));

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<FontRepairPlan> plans = FontIntelligence.repairPlans(doc);
            assertEquals(2, plans.size(), "both a missing and a partial map must be planned");

            int fixed = FontIntelligence.applyRepairs(doc, plans);
            assertTrue(fixed >= 2, "both authorised fonts should be repaired, got " + fixed);
            assertTrue(FontIntelligence.inspectPage(doc, 0).stream()
                            .allMatch(FontEvidence::toUnicodeValid),
                    "after authorising the whole page every font must carry a usable map");
        }
    }

    // ------------------------------------------------------------- fixtures

    private static FontEvidence evidence(long glyphBytes, int numGlyphs,
            List<UnicodeMapping> mappings) {
        return new FontEvidence(
                0, "F1", "Base", "", "TrueType", FontKind.SIMPLE,
                true, true, false, false, false, true,
                numGlyphs, 1000, glyphBytes, false, false,
                "WinAnsiEncoding", -1, mappings, false);
    }

    private static byte[] oneFontPdf(Path font) throws Exception {
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
                cs.showText("Hello World");
                cs.endText();
            }
            stripToUnicode(doc);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] twoFontPdf(Path a, Path b) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType0Font fa;
            PDType0Font fb;
            try (InputStream in = Files.newInputStream(a)) {
                fa = PDType0Font.load(doc, in, false);
            }
            try (InputStream in = Files.newInputStream(b)) {
                fb = PDType0Font.load(doc, in, false);
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(fa, 18);
                cs.newLineAtOffset(72, 700);
                cs.showText("Alpha");
                cs.setFont(fb, 18);
                cs.newLineAtOffset(0, -30);
                cs.showText("Beta");
                cs.endText();
            }
            stripToUnicode(doc);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** F1 has no declared map; F2 declares a valid map covering only one used code. */
    private static byte[] pdfWithAbsentAndPartialMap(Path absentFont, Path partialFont)
            throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType0Font fa;
            PDType0Font fb;
            try (InputStream in = Files.newInputStream(absentFont)) {
                fa = PDType0Font.load(doc, in, false);
            }
            try (InputStream in = Files.newInputStream(partialFont)) {
                fb = PDType0Font.load(doc, in, false);
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(fa, 18);
                cs.newLineAtOffset(72, 700);
                cs.showText("Alpha");
                cs.setFont(fb, 18);
                cs.newLineAtOffset(0, -30);
                cs.showText("Beta");
                cs.endText();
            }
            // Remove both auto-generated maps, then give the second font a valid
            // but partial one: it declares the code for 'B' only, while the page
            // uses all four letters of "Beta".
            stripToUnicode(doc);
            byte[] encoded = fb.encode("B");
            int code = ((encoded[0] & 0xff) << 8) | (encoded[1] & 0xff);
            COSStream partial = doc.getDocument().createCOSStream();
            try (OutputStream os = partial.createOutputStream()) {
                os.write(partialCmap(code, 'B').getBytes(StandardCharsets.ISO_8859_1));
            }
            fb.getCOSObject().setItem(COSName.TO_UNICODE, partial);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static String partialCmap(int code, int unicode) {
        return "/CIDInit /ProcSet findresource begin\n"
                + "12 dict begin\n"
                + "begincmap\n"
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                + "/CMapName /Adobe-Identity-UCS def\n"
                + "/CMapType 2 def\n"
                + "1 begincodespacerange\n<0000> <ffff>\nendcodespacerange\n"
                + "1 beginbfchar\n"
                + "<" + hex4(code) + "> <" + hex4(unicode) + ">\n"
                + "endbfchar\n"
                + "endcmap\n"
                + "CMapName currentdict /CMap defineresource pop\n"
                + "end\n"
                + "end\n";
    }

    private static String hex4(int value) {
        return String.format("%04X", value & 0xffff);
    }

    private static void stripToUnicode(PDDocument doc) {
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
                }
            }
        }
    }

    // ------------------------------------------------------------- plumbing

    private static Path requireFont() {
        return PinnedFonts.primary();
    }

    /** Two byte-distinct pinned font programs; the corpus collapses same-identity fonts. */
    private static List<Path> twoFonts() {
        return PinnedFonts.pair();
    }
}

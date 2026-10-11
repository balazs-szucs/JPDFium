package stirling.software.jpdfium.fonts.intel;

import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.fonts.PinnedFonts;

import static stirling.software.jpdfium.fonts.intel.FontTestFixtures.installToUnicodeOnAllFonts;
import static stirling.software.jpdfium.fonts.intel.FontTestFixtures.loadFont;
import static stirling.software.jpdfium.fonts.intel.FontTestFixtures.save;
import static stirling.software.jpdfium.fonts.intel.FontTestFixtures.toUnicodeText;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A repair plan is state-bound: it authorises exactly the change set it was
 * prepared against, and it discloses every reference a shared font object is
 * reached by. A plan whose document moved, or that leaves a shared reference
 * unauthorised, must be refused before any bytes are written.
 */
class FontRepairPlanFreshnessTest {

    @Test
    void aChangedDeclaredMappingInvalidatesAPreparedPlan() throws Exception {
        byte[] original = singleFontPdf("1 beginbfchar\n<41> <0041>\nendbfchar\n");
        byte[] mutated = singleFontPdf("1 beginbfchar\n<41> <0042>\nendbfchar\n");

        FontRepairPlan plan;
        try (PdfDocument doc = PdfDocument.open(original)) {
            List<FontRepairPlan> plans = FontIntelligence.repairPlans(doc, 0);
            assertFalse(plans.isEmpty(), "the missing used code should produce a plan");
            plan = plans.get(0);
            assertFalse(plan.nativeIdentity().isEmpty(),
                    "a plan prepared from the native plan must carry its identity");
        }

        try (PdfDocument doc = PdfDocument.open(mutated)) {
            StaleRepairPlanException thrown = assertThrows(StaleRepairPlanException.class,
                    () -> FontIntelligence.applyRepair(doc, plan),
                    "a plan against a changed declared map must be rejected before mutation");
            assertTrue(thrown.getMessage() != null);
            String before = toUnicodeText(mutated, 0);
            String after = toUnicodeText(doc.saveBytes(), 0);
            assertEquals(before, after,
                    "a rejected plan must not have altered the declared map");
        }
    }

    @Test
    void aSharedFontObjectMustBeAuthorisedForEveryReference() throws Exception {
        byte[] pdf = twoPagesSharingOneFont();

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<FontRepairPlan> page0 = FontIntelligence.repairPlans(doc, 0);
            FontRepairPlan planF1 = page0.stream()
                    .filter(p -> p.resourceName().equals("/F1"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no plan for /F1: " + page0));
            assertTrue(planF1.affectedReferences().contains(FontRepairPlan.referenceKey(0, "/F1")),
                    planF1.affectedReferences().toString());
            assertTrue(planF1.affectedReferences().contains(FontRepairPlan.referenceKey(1, "/F9")),
                    "the shared reference on page 1 must be disclosed: "
                            + planF1.affectedReferences());

            assertThrows(StaleRepairPlanException.class,
                    () -> FontIntelligence.applyRepair(doc, planF1),
                    "authorising one reference of a shared font must be refused");

            int fixed = FontIntelligence.applyRepairs(doc, FontIntelligence.repairPlans(doc));
            assertTrue(fixed >= 1, "the whole-document plan, covering both references, applies");

            String cmap = toUnicodeText(doc.saveBytes(), 0);
            assertTrue(cmap != null && cmap.contains("begincmap"),
                    "the shared font object must carry a repaired /ToUnicode");
        }
    }

    // ------------------------------------------------------------- fixtures

    /** One page showing "AB" with a simple TrueType font and a declared map. */
    private static byte[] singleFontPdf(String cmap) throws Exception {
        Path font = PinnedFonts.primary();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDTrueTypeFont f = loadFont(doc, font);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(f, 24);
                cs.newLineAtOffset(72, 700);
                cs.showText("AB");
                cs.endText();
            }
            installToUnicodeOnAllFonts(doc, cmap);
            return save(doc);
        }
    }

    /**
     * Two pages that reference ONE font dictionary under different names: page 0
     * as {@code /F1}, page 1 as {@code /F9}. The font has no /ToUnicode, so both
     * pages are eligible for the same object-wide repair.
     */
    private static byte[] twoPagesSharingOneFont() throws Exception {
        Path font = PinnedFonts.primary();
        try (PDDocument doc = new PDDocument()) {
            PDTrueTypeFont f = loadFont(doc, font);
            f.getCOSObject().removeItem(COSName.TO_UNICODE);
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                PDResources res = new PDResources();
                res.put(COSName.getPDFName(i == 0 ? "F1" : "F9"), f);
                page.setResources(res);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(f, 24);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("AB");
                    cs.endText();
                }
            }
            return save(doc);
        }
    }
}

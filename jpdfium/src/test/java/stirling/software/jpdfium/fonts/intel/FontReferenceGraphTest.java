package stirling.software.jpdfium.fonts.intel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.fonts.PinnedFonts;

import static stirling.software.jpdfium.fonts.intel.FontTestFixtures.PARTIAL;
import static stirling.software.jpdfium.fonts.intel.FontTestFixtures.emptyResources;
import static stirling.software.jpdfium.fonts.intel.FontTestFixtures.formContent;
import static stirling.software.jpdfium.fonts.intel.FontTestFixtures.formStream;
import static stirling.software.jpdfium.fonts.intel.FontTestFixtures.installToUnicode;
import static stirling.software.jpdfium.fonts.intel.FontTestFixtures.loadFont;
import static stirling.software.jpdfium.fonts.intel.FontTestFixtures.save;
import static stirling.software.jpdfium.fonts.intel.FontTestFixtures.setContent;
import static stirling.software.jpdfium.fonts.intel.FontTestFixtures.toUnicodeText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The font-reference graph is bounded but complete: a shared font object is
 * reached not only through page font tables but also through form XObjects (and
 * annotations), and every reference must be disclosed with its scope path before
 * an object-wide mutation is authorised.
 *
 * <p>The consent model is separate from eligibility: a reference that needs no
 * repair of its own is consented by whichever plan discloses it, while a
 * reference that does have its own document-wide repair still has to be passed
 * as a plan. A reached reference that is neither passed nor consented refuses.
 */
class FontReferenceGraphTest {

    @Test
    void formScopedReferenceIsDisclosedWithItsScopePath() throws Exception {
        byte[] pdf = pageFormAndPageTableShareOneFont();

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<FontRepairPlan> page0 = FontIntelligence.repairPlans(doc, 0);
            FontRepairPlan planF1 = page0.stream()
                    .filter(p -> p.resourceName().equals("/F1"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no plan for /F1: " + page0));

            assertTrue(planF1.affectedReferences().contains(FontRepairPlan.referenceKey(0, "/F1")),
                    planF1.affectedReferences().toString());
            assertTrue(planF1.affectedReferences()
                            .contains(FontRepairPlan.referenceKey(1, "/Form1", "/LocalFont")),
                    "the form-scoped reference must be disclosed with its scope path: "
                            + planF1.affectedReferences());
            assertTrue(planF1.affectedReferences().contains(FontRepairPlan.referenceKey(2, "/F2")),
                    planF1.affectedReferences().toString());
        }
    }

    @Test
    void incompleteAuthorizationOfAReachedReferenceRefuses() throws Exception {
        byte[] pdf = pageFormAndPageTableShareOneFont();

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontRepairPlan planF1 = FontIntelligence.repairPlans(doc, 0).stream()
                    .filter(p -> p.resourceName().equals("/F1"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no plan for /F1"));

            assertThrows(StaleRepairPlanException.class,
                    () -> FontIntelligence.applyRepair(doc, planF1),
                    "a reached reference with its own pending repair must not be mutated"
                            + " without being authorised");

            int fixed = FontIntelligence.applyRepairs(doc, FontIntelligence.repairPlans(doc));
            assertTrue(fixed >= 1, "the whole-document plan authorises every reached reference");
            String map = toUnicodeText(doc.saveBytes(), 0);
            assertNotNull(map, "the shared font object must now carry a repaired /ToUnicode");
            assertTrue(map.contains("<41> <0041>") && map.contains("<42> <0042>"),
                    "both shown codes must be present in the repaired map: " + map);
        }
    }

    @Test
    void theRepairLeavesTheFormContentUntouched() throws Exception {
        byte[] pdf = pageFormAndPageTableShareOneFont();
        byte[] before = formContent(pdf, 1, "/Form1");

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontIntelligence.applyRepairs(doc, FontIntelligence.repairPlans(doc));
            byte[] after = formContent(doc.saveBytes(), 1, "/Form1");
            assertEquals(before.length, after.length,
                    "a /ToUnicode-only repair must not rewrite the form's content stream");
            assertEquals(new String(before, StandardCharsets.ISO_8859_1),
                    new String(after, StandardCharsets.ISO_8859_1),
                    "the form's existing text operators must be preserved verbatim");
        }
    }

    @Test
    void alreadyCompleteSharedReferenceConsentsToTheRepair() throws Exception {
        byte[] pdf = oneFontTwoPagesOneAlreadyComplete();

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            // Page 1 shows only the already-declared code, so it contributes no plan.
            assertTrue(FontIntelligence.repairPlans(doc, 1).isEmpty(),
                    "the already-complete reference must not need a repair of its own");
            List<FontRepairPlan> plans = FontIntelligence.repairPlans(doc);
            assertFalse(plans.isEmpty(), "page 0's missing code must produce a plan");

            int fixed = FontIntelligence.applyRepairs(doc, plans);
            assertTrue(fixed >= 1,
                    "the full-document plan must apply even when a shared reference"
                            + " needs no repair of its own");
            String map = toUnicodeText(doc.saveBytes(), 0);
            assertNotNull(map);
            assertTrue(map.contains("<42> <0042>"),
                    "the missing code must have been added: " + map);
        }
    }

    @Test
    void partialApprovalOfAnAlreadyCompleteSharedReferenceStillRefuses() throws Exception {
        byte[] pdf = oneFontTwoPagesOneAlreadyComplete();

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            // Passing no plan for the mutating page authorises no write at all.
            assertEquals(0, FontIntelligence.applyRepairs(doc, List.of()),
                    "an empty authorisation set must not mutate anything");
            String map = toUnicodeText(doc.saveBytes(), 0);
            assertNotNull(map);
            assertFalse(map.contains("<42> <0042>"),
                    "no code may be added by an empty authorisation: " + map);
        }
    }

    @Test
    void formWithEmptyResourcesUsesTheInheritedSelectedFont() throws Exception {
        Path font = PinnedFonts.primary();
        byte[] pdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDTrueTypeFont f = loadFont(doc, font);
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("F1"), f);
            page.setResources(res);
            installToUnicode(doc, f.getCOSObject(), PARTIAL);

            // The form declares an EMPTY /Resources dictionary and shows a code
            // without a Tf, so it inherits the caller's selected font.
            COSStream form = formStream(doc, "(B) Tj\n".getBytes(StandardCharsets.ISO_8859_1));
            form.setItem(COSName.RESOURCES, emptyResources());
            COSDictionary xobjects = new COSDictionary();
            xobjects.setItem(COSName.getPDFName("Form1"), form);
            res.getCOSObject().setItem(COSName.XOBJECT, xobjects);

            setContent(doc, page, "BT /F1 12 Tf 20 700 Td (A) Tj ET\n/Form1 Do\n");
            pdf = save(doc);
        }

        String map;
        try (PdfDocument jdoc = PdfDocument.open(pdf)) {
            assertTrue(FontIntelligence.repairToUnicode(jdoc) >= 1,
                    "the form's inherited-font code must make the partial map extendable");
            map = toUnicodeText(jdoc.saveBytes(), 0);
        }
        assertNotNull(map);
        assertTrue(map.contains("<42> <0042>"),
                "the form's inherited-font code must be attributed to the selected font: " + map);
    }

    // ------------------------------------------------------------- fixtures

    /**
     * Three pages over ONE shared indirect font object X (no {@code /ToUnicode}):
     * page 0 reaches X through its page font table as {@code /F1}, page 1 reaches
     * X through a form XObject's {@code /Resources /Font /LocalFont}, and page 2
     * reaches X through its page font table as {@code /F2}.
     *
     * <p>Pages 0 and 2 are independently plannable; page 1's form reference is not
     * reachable through any page table.
     */
    private static byte[] pageFormAndPageTableShareOneFont() throws Exception {
        Path font = PinnedFonts.primary();
        try (PDDocument doc = new PDDocument()) {
            PDTrueTypeFont f = loadFont(doc, font);
            f.getCOSObject().removeItem(COSName.TO_UNICODE);
            COSDictionary fd = f.getCOSObject();

            // Page 0: page font table /F1 -> X, showing AB.
            PDPage p0 = new PDPage(PDRectangle.LETTER);
            doc.addPage(p0);
            PDResources r0 = new PDResources();
            r0.put(COSName.getPDFName("F1"), f);
            p0.setResources(r0);
            setContent(doc, p0, "BT /F1 24 Tf 72 700 Td (AB) Tj ET\n");

            // Page 1: a form XObject whose own /Resources /Font /LocalFont -> X.
            PDPage p1 = new PDPage(PDRectangle.LETTER);
            doc.addPage(p1);
            COSStream form = formStream(doc,
                    "BT /LocalFont 24 Tf 72 700 Td (AB) Tj ET\n"
                            .getBytes(StandardCharsets.ISO_8859_1));
            COSDictionary formFonts = new COSDictionary();
            formFonts.setItem(COSName.getPDFName("LocalFont"), fd);
            COSDictionary formRes = new COSDictionary();
            formRes.setItem(COSName.FONT, formFonts);
            form.setItem(COSName.RESOURCES, formRes);
            COSDictionary xobjects = new COSDictionary();
            xobjects.setItem(COSName.getPDFName("Form1"), form);
            COSDictionary r1 = new COSDictionary();
            r1.setItem(COSName.XOBJECT, xobjects);
            p1.getCOSObject().setItem(COSName.RESOURCES, r1);
            setContent(doc, p1, "/Form1 Do\n");

            // Page 2: page font table /F2 -> the same object X, showing AB.
            PDPage p2 = new PDPage(PDRectangle.LETTER);
            doc.addPage(p2);
            PDResources r2 = new PDResources();
            r2.put(COSName.getPDFName("F2"), f);
            p2.setResources(r2);
            setContent(doc, p2, "BT /F2 24 Tf 72 700 Td (AB) Tj ET\n");

            return save(doc);
        }
    }

    /**
     * Two pages over ONE shared font object X whose {@code /ToUnicode} already
     * declares {@code A}. Page 0 shows {@code AB} (B is missing, so page 0 has a
     * plan); page 1 shows {@code A} (already complete, so page 1 has no plan).
     */
    private static byte[] oneFontTwoPagesOneAlreadyComplete() throws Exception {
        Path font = PinnedFonts.primary();
        try (PDDocument doc = new PDDocument()) {
            PDTrueTypeFont f = loadFont(doc, font);
            installToUnicode(doc, f.getCOSObject(), PARTIAL);
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                PDResources res = new PDResources();
                res.put(COSName.getPDFName(i == 0 ? "F1" : "F9"), f);
                page.setResources(res);
                setContent(doc, page,
                        "BT /" + (i == 0 ? "F1" : "F9") + " 24 Tf 72 700 Td ("
                                + (i == 0 ? "AB" : "A") + ") Tj ET\n");
            }
            return save(doc);
        }
    }

}

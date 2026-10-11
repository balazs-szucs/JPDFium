package stirling.software.jpdfium.fonts.intel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.fonts.PinnedFonts;
import stirling.software.jpdfium.panama.FontLib;
import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measures how many whole-document QPDF snapshot operations the font-intelligence
 * planning and apply paths perform, and verifies the work they do is unchanged.
 *
 * <p>The operation counter is a native diagnostic incremented once per snapshot
 * load. The contract is that neither path scales in the page count: planning uses
 * one evidence batch plus one plan batch, and applying validates against one plan
 * batch plus the single write, regardless of how many pages are touched. The
 * counts are printed so a regression is visible in the task's captured output.
 */
class FontPlanOperationCountTest {

    private static final int PAGES = 8;
    private static final int RUNS = 5;

    @Test
    void planningAndApplyUseABoundedNumberOfSnapshots() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = multiPageRepairablePdf(PAGES);

        List<Long> planOps = new ArrayList<>();
        List<Long> applyOps = new ArrayList<>();
        List<Long> planNanos = new ArrayList<>();
        List<Long> applyNanos = new ArrayList<>();

        for (int run = 0; run < RUNS; run++) {
            try (PdfDocument doc = PdfDocument.open(pdf)) {
                FontLib.resetNativeOpCount();
                long planStart = System.nanoTime();
                List<FontRepairPlan> plans = FontIntelligence.repairPlans(doc);
                long planEnd = System.nanoTime();
                long measuredPlanOps = FontLib.nativeOpCount();

                assertEquals(PAGES, plans.size(),
                        "one repairable font per page must yield one plan per page");
                for (FontRepairPlan plan : plans) {
                    assertFalse(plan.nativeIdentity().isEmpty(),
                            "a batch plan must carry its native identity: " + plan);
                    assertTrue(plan.affectedReferences()
                                    .contains(FontRepairPlan.referenceKey(
                                            plan.pageIndex(), plan.resourceName())),
                            "a plan must disclose its own reference: " + plan.affectedReferences());
                }

                FontLib.resetNativeOpCount();
                long applyStart = System.nanoTime();
                int fixed = FontIntelligence.applyRepairs(doc, plans);
                long applyEnd = System.nanoTime();
                long measuredApplyOps = FontLib.nativeOpCount();

                assertTrue(fixed >= 1, "at least one page's /ToUnicode must be repaired");
                byte[] after = doc.saveBytes();
                assertTrue(everyFontHasToUnicode(after),
                        "every page's font must declare a /ToUnicode after repair");

                planOps.add(measuredPlanOps);
                applyOps.add(measuredApplyOps);
                planNanos.add(planEnd - planStart);
                applyNanos.add(applyEnd - applyStart);
            }
        }

        System.out.printf(
                "FontIntelligence snapshot operations over %d runs (%d pages):%n"
                + "  planning ops: %s%n"
                + "  apply ops:    %s%n"
                + "  planning wall-clock ms: median=%.3f range=[%.3f, %.3f]%n"
                + "  apply wall-clock ms:    median=%.3f range=[%.3f, %.3f]%n",
                RUNS, PAGES, planOps, applyOps,
                medianMillis(planNanos), minMillis(planNanos), maxMillis(planNanos),
                medianMillis(applyNanos), minMillis(applyNanos), maxMillis(applyNanos));

        // The measured design: two snapshot operations to plan a whole document
        // (one evidence batch + one plan batch) and two to apply it (one
        // validation batch + one write), independent of the page count.
        for (int run = 0; run < RUNS; run++) {
            assertTrue(planOps.get(run) >= 1, "planning must touch the document at least once");
            assertTrue(planOps.get(run) <= 2,
                    "planning must take exactly one evidence batch and one plan batch: "
                            + planOps.get(run));
            assertTrue(applyOps.get(run) >= 1, "apply must touch the document at least once");
            assertTrue(applyOps.get(run) <= 2,
                    "apply must take one validation batch and one write: " + applyOps.get(run));
        }
    }

    /**
     * The operation counts are a property of the design, not of the document: a
     * two-page and a sixteen-page document must plan and apply in the same number
     * of snapshot operations. This is the truthful, page-count-independent claim
     * the per-page-load regression would break.
     */
    @Test
    void operationCountDoesNotScaleWithPageCount() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        long[] small = measure(2);
        long[] large = measure(16);

        System.out.printf(
                "FontIntelligence snapshot ops by page count:%n"
                + "  2 pages:  planning ops=%d apply ops=%d%n"
                + "  16 pages: planning ops=%d apply ops=%d%n",
                small[0], small[1], large[0], large[1]);

        assertEquals(small[0], large[0],
                "planning snapshot operations must be independent of page count");
        assertEquals(small[1], large[1],
                "apply snapshot operations must be independent of page count");
        assertTrue(large[0] <= 2, "planning must stay within two snapshot operations: " + large[0]);
        assertTrue(large[1] <= 2, "apply must stay within two snapshot operations: " + large[1]);
    }

    /** {@code {planning ops, apply ops}} for a freshly generated {@code pages}-page document. */
    private static long[] measure(int pages) throws Exception {
        byte[] pdf = multiPageRepairablePdf(pages);
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontLib.resetNativeOpCount();
            List<FontRepairPlan> plans = FontIntelligence.repairPlans(doc);
            long planOps = FontLib.nativeOpCount();
            assertEquals(pages, plans.size(), "one repairable font per page must yield one plan");

            FontLib.resetNativeOpCount();
            int fixed = FontIntelligence.applyRepairs(doc, plans);
            long applyOps = FontLib.nativeOpCount();
            assertTrue(fixed >= 1, "at least one page's /ToUnicode must be repaired");
            return new long[] {planOps, applyOps};
        }
    }

    // ------------------------------------------------------------- helpers

    private static double medianMillis(List<Long> nanos) {
        List<Long> sorted = new ArrayList<>(nanos);
        sorted.sort(null);
        return sorted.get(sorted.size() / 2) / 1_000_000.0;
    }

    private static double minMillis(List<Long> nanos) {
        return nanos.stream().mapToLong(Long::longValue).min().orElse(0) / 1_000_000.0;
    }

    private static double maxMillis(List<Long> nanos) {
        return nanos.stream().mapToLong(Long::longValue).max().orElse(0) / 1_000_000.0;
    }

    /**
     * One page per sheet, each with its own simple TrueType font showing "AB"
     * and no /ToUnicode, so every page is independently repairable.
     */
    private static byte[] multiPageRepairablePdf(int pages) throws Exception {
        Path font = PinnedFonts.primary();
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                PDTrueTypeFont f;
                try (InputStream in = Files.newInputStream(font)) {
                    f = PDTrueTypeFont.load(doc, in, WinAnsiEncoding.INSTANCE);
                }
                f.getCOSObject().removeItem(COSName.TO_UNICODE);
                PDResources res = new PDResources();
                res.put(COSName.getPDFName("F1"), f);
                page.setResources(res);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(f, 24);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("AB");
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** True when every font resource on every page declares a /ToUnicode stream. */
    private static boolean everyFontHasToUnicode(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            boolean sawFont = false;
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
                        sawFont = true;
                        if (!(dict.getDictionaryObject(COSName.TO_UNICODE) instanceof COSStream)) {
                            return false;
                        }
                    }
                }
            }
            return sawFont;
        } catch (Exception e) {
            throw new AssertionError("could not inspect the repaired document", e);
        }
    }
}

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

    /**
     * The reference graph is built once per operation and indexed by font-object
     * identity, so the work is linear in the number of distinct font objects, not
     * quadratic in the page count. This is the truthful claim the old
     * "rescan every page for every candidate font" design would break.
     *
     * <p>Two shapes are measured: many pages that each have their own font object
     * (work grows with the object count) and many pages that share one font object
     * (work is constant, because the reverse index deduplicates references by
     * object). The font program bytes are identical in both shapes, so the program
     * cache must decode exactly one program no matter how many objects embed it.
     */
    @Test
    void referenceWorkScalesWithDistinctObjectsNotPages() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        Work independent8 = measureWork(independentFontsPdf(8));
        Work independent32 = measureWork(independentFontsPdf(32));
        Work shared8 = measureWork(sharedFontPdf(8));
        Work shared32 = measureWork(sharedFontPdf(32));

        System.out.printf(
                "FontIntelligence reference work by document shape:%n"
                + "  independent  8 pages: nodes=%d lookups=%d programs=%d bytesHashed=%d"
                + " planJson=%dB heapGrowth=%dB elapsed=%.1fms plans=%d%n"
                + "  independent 32 pages: nodes=%d lookups=%d programs=%d bytesHashed=%d"
                + " planJson=%dB heapGrowth=%dB elapsed=%.1fms plans=%d%n"
                + "  shared       8 pages: nodes=%d lookups=%d programs=%d bytesHashed=%d"
                + " planJson=%dB heapGrowth=%dB elapsed=%.1fms plans=%d%n"
                + "  shared      32 pages: nodes=%d lookups=%d programs=%d bytesHashed=%d"
                + " planJson=%dB heapGrowth=%dB elapsed=%.1fms plans=%d%n",
                independent8.nodes(), independent8.lookups(), independent8.programs(),
                independent8.bytesHashed(), independent8.planJsonBytes(),
                independent8.heapGrowthBytes(), independent8.elapsedMillis(), independent8.plans(),
                independent32.nodes(), independent32.lookups(), independent32.programs(),
                independent32.bytesHashed(), independent32.planJsonBytes(),
                independent32.heapGrowthBytes(), independent32.elapsedMillis(),
                independent32.plans(),
                shared8.nodes(), shared8.lookups(), shared8.programs(), shared8.bytesHashed(),
                shared8.planJsonBytes(), shared8.heapGrowthBytes(), shared8.elapsedMillis(),
                shared8.plans(),
                shared32.nodes(), shared32.lookups(), shared32.programs(), shared32.bytesHashed(),
                shared32.planJsonBytes(), shared32.heapGrowthBytes(), shared32.elapsedMillis(),
                shared32.plans());

        // Distinct objects: one lookup per eligible object, linear in the object
        // count. A quadratic rescan would be ~16x from 8 to 32 pages.
        assertEquals(8, independent8.lookups(),
                "one indexed lookup per distinct eligible font object");
        assertEquals(32, independent32.lookups(),
                "one indexed lookup per distinct eligible font object");
        assertTrue(independent32.lookups() <= 6 * independent8.lookups(),
                "reference lookups must grow linearly, not quadratically: "
                        + independent8.lookups() + " -> " + independent32.lookups());
        assertTrue(independent32.nodes() <= 6 * independent8.nodes(),
                "resource dictionaries visited must grow linearly, not quadratically: "
                        + independent8.nodes() + " -> " + independent32.nodes());

        // Shared object: one distinct font object, so the reverse index returns the
        // whole reference set from a single lookup regardless of the page count.
        assertEquals(1, shared8.lookups(),
                "a shared font object must be looked up once, not once per page");
        assertEquals(1, shared32.lookups(),
                "a shared font object must be looked up once, not once per page");
        assertEquals(8, shared8.plans(), "each page's shared font must still plan once");
        assertEquals(32, shared32.plans(), "each page's shared font must still plan once");

        // The same program bytes are decoded once per operation, never once per
        // page: the decode count is independent of the page count in every shape.
        assertEquals(independent8.programs(), independent32.programs(),
                "distinct-program decodes must not grow with the page count");
        assertEquals(shared8.programs(), shared32.programs(),
                "a shared font object must not be decoded once per page");
        assertTrue(independent32.programs() <= 2,
                "at most one program decode per snapshot operation: "
                        + independent32.programs());
        assertTrue(shared32.programs() <= 2,
                "at most one program decode per snapshot operation: " + shared32.programs());

        // Memory: the planning working set stays a small multiple of the document.
        assertTrue(shared32.heapGrowthBytes() < 256L * 1024 * 1024,
                "planning 32 pages must not retain hundreds of megabytes: "
                        + shared32.heapGrowthBytes());
    }

    /**
     * A font reached only through nested form XObjects is still disclosed, once per
     * scope, from one lookup of the shared object — so the reference graph reaches
     * past the page font table without redoing work per owner.
     */
    @Test
    void nestedFormReferencesAreDiscoveredAndDeduplicated() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        int depth = 3;
        byte[] pdf = nestedFormPdf(depth);

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontLib.resetNativeMetrics();
            List<FontRepairPlan> plans = FontIntelligence.repairPlans(doc);
            FontLib.NativeMetrics metrics = FontLib.nativeMetrics();

            System.out.printf(
                    "FontIntelligence nested-form reference graph (depth=%d):"
                    + " nodes=%d lookups=%d programs=%d affected=%s%n",
                    depth, metrics.referenceNodes(), metrics.referenceLookups(),
                    metrics.programsAnalyzed(),
                    plans.isEmpty() ? "[]" : plans.get(0).affectedReferences());

            assertEquals(1, plans.size(), "the page font must yield one plan");
            assertEquals(depth + 1, plans.get(0).affectedReferences().size(),
                    "the page reference plus one reference per nested form level must be"
                            + " disclosed: " + plans.get(0).affectedReferences());
            assertTrue(plans.get(0).affectedReferences()
                            .contains(FontRepairPlan.referenceKey(0, "/F1")),
                    "the page reference must be disclosed");
            assertEquals(1, metrics.referenceLookups(),
                    "one shared object must be looked up once even when reached through"
                            + " nested forms");
        }
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

    // ------------------------------------------------- reference benchmark

    /** Planning work counters recorded for one freshly opened document. */
    private record Work(int plans, int nodes, int lookups, int programs, long bytesHashed,
                        int planJsonBytes, long heapGrowthBytes, double elapsedMillis) {}

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

    /**
     * Plan one document and time it, recording the native reference/program
     * counters and the plan JSON size, heap growth and wall clock. The plan JSON is
     * read once before the counters are reset, so the measured window is exactly
     * the {@code repairPlans} call the counters describe.
     */
    private static Work measureWork(byte[] pdf) throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            String json = FontLib.planPages(doc.nativeHandle());
            System.gc();
            long before = usedHeap();
            FontLib.resetNativeMetrics();
            long start = System.nanoTime();
            List<FontRepairPlan> plans = FontIntelligence.repairPlans(doc);
            long end = System.nanoTime();
            FontLib.NativeMetrics m = FontLib.nativeMetrics();
            long heapGrowth = Math.max(0, usedHeap() - before);
            return new Work(plans.size(), (int) m.referenceNodes(), (int) m.referenceLookups(),
                    (int) m.programsAnalyzed(), m.programBytesHashed(), json.length(), heapGrowth,
                    (end - start) / 1_000_000.0);
        }
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /**
     * Many pages that each name the SAME font object (one indirect dictionary
     * reused across pages) and show "AB" with no /ToUnicode.
     */
    private static byte[] sharedFontPdf(int pages) throws Exception {
        Path font = PinnedFonts.primary();
        try (PDDocument doc = new PDDocument()) {
            PDTrueTypeFont shared;
            try (InputStream in = Files.newInputStream(font)) {
                shared = PDTrueTypeFont.load(doc, in, WinAnsiEncoding.INSTANCE);
            }
            shared.getCOSObject().removeItem(COSName.TO_UNICODE);
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                PDResources res = new PDResources();
                res.put(COSName.getPDFName("F1"), shared);
                page.setResources(res);
                show(doc, page, shared, "AB");
            }
            return save(doc);
        }
    }

    /**
     * Many pages that each own a DISTINCT font object loaded from the same file,
     * so every object embeds identical program bytes but alternating pages declare
     * a different /ToUnicode state (none vs. only 'A'). Every page is still
     * repairable and the program cache must still decode one program.
     */
    private static byte[] independentFontsPdf(int pages) throws Exception {
        Path font = PinnedFonts.primary();
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                PDTrueTypeFont f;
                try (InputStream in = Files.newInputStream(font)) {
                    f = PDTrueTypeFont.load(doc, in, WinAnsiEncoding.INSTANCE);
                }
                if (i % 2 == 0) {
                    f.getCOSObject().removeItem(COSName.TO_UNICODE);
                } else {
                    installToUnicode(doc, f.getCOSObject(), PARTIAL);
                }
                PDResources res = new PDResources();
                res.put(COSName.getPDFName("F1"), f);
                page.setResources(res);
                show(doc, page, f, "AB");
            }
            return save(doc);
        }
    }

    /**
     * One page whose font object is reached through a chain of {@code depth} nested
     * form XObjects, each of which names the same object in its own
     * {@code /Resources /Font /LocalFont}.
     */
    private static byte[] nestedFormPdf(int depth) throws Exception {
        Path font = PinnedFonts.primary();
        try (PDDocument doc = new PDDocument()) {
            PDTrueTypeFont shared;
            try (InputStream in = Files.newInputStream(font)) {
                shared = PDTrueTypeFont.load(doc, in, WinAnsiEncoding.INSTANCE);
            }
            shared.getCOSObject().removeItem(COSName.TO_UNICODE);
            COSDictionary fd = shared.getCOSObject();

            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("F1"), shared);
            page.setResources(res);
            show(doc, page, shared, "AB");

            COSDictionary xobjects = new COSDictionary();
            res.getCOSObject().setItem(COSName.XOBJECT, xobjects);
            COSDictionary parent = xobjects;
            for (int level = 1; level <= depth; level++) {
                boolean innermost = level == depth;
                byte[] content = (innermost
                        ? "BT /LocalFont 24 Tf 72 700 Td (AB) Tj ET\n"
                        : "/Form" + (level + 1) + " Do\n")
                        .getBytes(StandardCharsets.ISO_8859_1);
                COSStream form = formStream(doc, content);
                COSDictionary formFonts = new COSDictionary();
                formFonts.setItem(COSName.getPDFName("LocalFont"), fd);
                COSDictionary formRes = new COSDictionary();
                formRes.setItem(COSName.FONT, formFonts);
                form.setItem(COSName.RESOURCES, formRes);
                parent.setItem(COSName.getPDFName("Form" + level), form);
                if (!innermost) {
                    COSDictionary nested = new COSDictionary();
                    formRes.setItem(COSName.XOBJECT, nested);
                    parent = nested;
                }
            }
            return save(doc);
        }
    }

    private static COSStream formStream(PDDocument doc, byte[] content) throws Exception {
        COSStream form = doc.getDocument().createCOSStream();
        form.setItem(COSName.TYPE, COSName.getPDFName("XObject"));
        form.setItem(COSName.SUBTYPE, COSName.getPDFName("Form"));
        form.setItem(COSName.BBOX, new PDRectangle(300, 300).getCOSArray());
        try (OutputStream os = form.createOutputStream(COSName.FLATE_DECODE)) {
            os.write(content);
        }
        return form;
    }

    private static void show(PDDocument doc, PDPage page, PDTrueTypeFont font, String text)
            throws Exception {
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(font, 24);
            cs.newLineAtOffset(72, 700);
            cs.showText(text);
            cs.endText();
        }
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

    private static byte[] save(PDDocument doc) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }
}

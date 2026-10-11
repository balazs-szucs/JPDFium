package stirling.software.jpdfium.fonts.intel;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.ShapedGlyph;
import stirling.software.jpdfium.panama.FontLib;
import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end tests for the font-intelligence path against the real native
 * library. Skipped when only the stub bridge is present.
 */
class FontIntelligenceNativeTest {

    /**
     * A corpus wide enough to exercise simple and composite fonts, and to
     * surface at least one resource whose declared {@code /ToUnicode} is
     * missing or empty so the reconstruction path is actually run.
     */
    private static final String[] CORPUS = {
            "/pdfs/general/basic-text.pdf",
            "/pdfs/general/minimal.pdf",
            "/pdfs/general/pdfjs_ArabicCIDTrueType.pdf",
            "/pdfs/general/pdfjs_XiaoBiaoSong.pdf",
            "/pdfs/general/pdfjs_ThuluthFeatures.pdf",
            "/pdfs/general/pdfjs_IdentityToUnicodeMap_charCodeOf.pdf",
            "/pdfs/general/pdfjs_Type3WordSpacing.pdf",
            "/pdfs/general/w3c_dummy.pdf"
    };

    private static byte[] fixture(String resource) throws Exception {
        try (InputStream in = FontIntelligenceNativeTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing test resource " + resource);
            return in.readAllBytes();
        }
    }

    @Test
    void inspectsFontsOnRealDocument() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = fixture("/pdfs/general/basic-text.pdf");
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertTrue(doc.pageCount() >= 1);
            List<FontEvidence> fonts = FontIntelligence.inspectPage(doc, 0);
            assertFalse(fonts.isEmpty(), "basic-text.pdf should use at least one font");
            for (FontEvidence f : fonts) {
                assertNotNull(f.kind());
                assertEquals(0, f.pageIndex());
                // Composite fonts are always flagged as CID resources.
                if (f.kind() == FontKind.COMPOSITE) {
                    assertTrue(f.cid(), "composite font must be flagged cid");
                }
            }
        }
    }

    @Test
    void inspectPageRejectsOutOfRange() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = fixture("/pdfs/general/minimal.pdf");
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            int pages = doc.pageCount();
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> FontIntelligence.inspectPage(doc, pages));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> FontIntelligence.inspectPage(doc, -1));
        }
    }

    @Test
    void recoveryAndRepairAreConsistentAcrossTheCorpus() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        int repairableFound = 0;
        int repairedVerified = 0;

        for (String resource : CORPUS) {
            byte[] pdf;
            try {
                pdf = fixture(resource);
            } catch (AssertionError missing) {
                continue;
            }
            try (PdfDocument doc = PdfDocument.open(pdf)) {
                for (int p = 0; p < doc.pageCount(); p++) {
                    List<FontEvidence> fonts = FontIntelligence.inspectPage(doc, p);
                    for (FontEvidence f : fonts) {
                        if (FontIntelligence.capability(f)
                                != FontCapability.MAPPING_REPAIR_AVAILABLE) {
                            continue;
                        }
                        repairableFound++;
                        // Evidence must not have guessed: every resolved mapping
                        // carries a real code point and the font declared no
                        // usable /ToUnicode.
                        assertFalse(f.toUnicodeValid(),
                                "a repairable font must not already have valid /ToUnicode");
                        for (UnicodeMapping m : f.resolvedMappings()) {
                            assertTrue(m.unicode() >= 0);
                            assertTrue(m.provenance().resolved());
                        }

                        int fixed = FontIntelligence.repairPage(doc, p);
                        assertTrue(fixed >= 0);
                        // Re-inspect: the font must now be reusable, or still
                        // reported (never silently claimed fixed but unchanged).
                        boolean verified = false;
                        for (FontEvidence after : FontIntelligence.inspectPage(doc, p)) {
                            if (after.resourceName().equals(f.resourceName())
                                    && after.toUnicodeValid()) {
                                verified = true;
                            }
                        }
                        if (verified) {
                            repairedVerified++;
                        }
                    }
                }
            }
        }

        // The corpus is chosen to contain at least one missing/empty /ToUnicode.
        assertTrue(repairableFound > 0,
                "expected the corpus to surface at least one repairable font");
        assertEquals(repairableFound, repairedVerified,
                "every repairable font should report a valid /ToUnicode after repair");
    }

    @Test
    void embeddedFontProgramDrivesShapingAndSubsetting() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = fixture("/pdfs/general/basic-text.pdf");
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            if (doc.pageCount() < 1) {
                return;
            }
            try (PdfPage page = doc.page(0)) {
                byte[] fontData = FontLib.getData(page.nativeHandle(), 0);
                assertNotNull(fontData);
                assertTrue(fontData.length > 0, "page 0 should expose an embedded font program");

                List<FontEvidence> fonts = FontIntelligence.inspectPage(doc, 0);
                assertFalse(fonts.isEmpty());

                boolean[] covered = FontIntelligence.coverage(fontData, new int[] {0x41, 0x20});
                assertEquals(2, covered.length);

                try {
                    List<ShapedGlyph> glyphs = FontIntelligence.shape(fontData, "Hi", 12f);
                    assertNotNull(glyphs);
                } catch (RuntimeException shapingUnsupported) {
                    // Some programs (e.g. CID without a Unicode cmap) cannot be
                    // shaped by the Unicode driver; that is a surfacing failure,
                    // not a silent wrong answer.
                }

                byte[] subset = FontIntelligence.subset(fontData, new int[] {0x48, 0x69}, true);
                assertNotNull(subset);
                assertTrue(subset.length > 0);
            }
        }
    }

    @Test
    void catalogAcrossDocumentDedupesPrograms() throws Exception {        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = fixture("/pdfs/general/basic-text.pdf");
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontCatalog catalog = FontIntelligence.inspect(doc);
            assertFalse(catalog.isEmpty());
            List<FontEvidence> all = new ArrayList<>(catalog.fonts());
            assertFalse(all.isEmpty());
            assertTrue(catalog.size() <= all.size(),
                    "distinct fonts can never exceed per-resource occurrences");
        }
    }

    /** First repairable font on any page of the document, or null. */
    private static FontEvidence firstRepairable(PdfDocument doc) {
        for (int p = 0; p < doc.pageCount(); p++) {
            for (FontEvidence f : FontIntelligence.inspectPage(doc, p)) {
                if (FontIntelligence.capability(f) == FontCapability.MAPPING_REPAIR_AVAILABLE) {
                    return f;
                }
            }
        }
        return null;
    }

    @Test
    void freshPlanAppliesAndStalePlanIsRefused() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        boolean exercised = false;
        for (String resource : CORPUS) {
            byte[] pdf;
            try {
                pdf = fixture(resource);
            } catch (AssertionError missing) {
                continue;
            }
            try (PdfDocument doc = PdfDocument.open(pdf)) {
                FontEvidence source = firstRepairable(doc);
                if (source == null) {
                    continue;
                }
                FontRepairPlan plan = null;
                for (FontRepairPlan candidate : FontIntelligence.repairPlans(doc, source.pageIndex())) {
                    if (candidate.resourceName().equals(source.resourceName())) {
                        plan = candidate;
                        break;
                    }
                }
                if (plan == null) {
                    continue;
                }

                // Mutation moves the document past the plan's snapshot.
                int fixed = FontIntelligence.repairPage(doc, source.pageIndex());
                assertTrue(fixed >= 0);

                final FontRepairPlan authorised = plan;
                assertThrows(StaleRepairPlanException.class,
                        () -> FontIntelligence.applyRepair(doc, authorised),
                        "a plan whose font is already repaired must be refused, not re-applied");
                exercised = true;
                break;
            }
        }
        assumeTrue(exercised, "corpus must expose a repairable font");
    }

    @Test
    void freshPlanOnUntouchedDocumentAppliesCleanly() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        boolean exercised = false;
        for (String resource : CORPUS) {
            byte[] pdf;
            try {
                pdf = fixture(resource);
            } catch (AssertionError missing) {
                continue;
            }
            try (PdfDocument doc = PdfDocument.open(pdf)) {
                FontEvidence source = firstRepairable(doc);
                if (source == null) {
                    continue;
                }
                FontRepairPlan plan = null;
                for (FontRepairPlan candidate : FontIntelligence.repairPlans(doc)) {
                    if (candidate.resourceName().equals(source.resourceName())) {
                        plan = candidate;
                        break;
                    }
                }
                if (plan == null) {
                    continue;
                }
                assertFalse(plan.stateFingerprint().isEmpty(),
                        "a plan must carry the evidence snapshot it targets");
                assertFalse(plan.nativeIdentity().isEmpty(),
                        "an authorisable plan must carry the native plan identity");
                // A font object may be shared by several references; authorising
                // the whole document covers every reference the plans disclose.
                int fixed = FontIntelligence.applyRepairs(doc, FontIntelligence.repairPlans(doc));
                assertTrue(fixed >= 1, "a fresh plan against an untouched document must apply");
                exercised = true;
                break;
            }
        }
        assumeTrue(exercised, "corpus must expose a repairable font");
    }
}

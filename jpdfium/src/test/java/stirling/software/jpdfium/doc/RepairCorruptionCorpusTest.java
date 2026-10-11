package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.NativeLoader;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Contract test for the paired {@link RepairCorruptionCorpus}.
 *
 * <p>Each fixture declares the outcome the pipeline must report. The test proves
 * the contract holds: the caller's input is never mutated, a failure publishes
 * no bytes, recoverable inputs stay usable with the expected page count, and a
 * second repair of a usable output is stable.
 */
@Tag("integration")
class RepairCorruptionCorpusTest {

    private static List<RepairCorruptionCorpus.Fixture> fixtures;

    @BeforeAll
    static void setUp() throws IOException {
        NativeLoader.ensureLoaded();
        fixtures = RepairCorruptionCorpus.all();
    }

    static Stream<RepairCorruptionCorpus.Fixture> fixtures() throws IOException {
        return fixtures.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void declaredOutcomeContract(RepairCorruptionCorpus.Fixture fx) {
        byte[] input = fx.bytes().clone();
        byte[] snapshot = input.clone();

        RepairResult result = assertDoesNotThrow(
                () -> PdfRepair.builder().input(input).all().build().execute(),
                fx.id() + " (" + fx.outcome() + "): repair threw");

        assertNotNull(result.status(), fx.id() + ": null status");

        // 1. The caller's input array is never mutated in place.
        assertArrayEquals(snapshot, input,
                fx.id() + ": repair mutated the caller's input array");

        // 2. A failure publishes nothing: no bytes to mistake for a repair.
        if (result.status() == RepairResult.Status.FAILED) {
            assertNull(result.repairedPdf(),
                    fx.id() + ": FAILED must not publish output bytes");
        }

        switch (fx.outcome()) {
            case REPAIRABLE -> {
                assertTrue(result.status() == RepairResult.Status.CLEAN
                                || result.status() == RepairResult.Status.FIXED,
                        fx.id() + ": expected CLEAN/FIXED but got " + result.status());
                assertNotNull(result.repairedPdf(), fx.id() + ": no repaired output");
                assertEquals(fx.expectedPages(), pageCount(result.repairedPdf()),
                        fx.id() + ": recovered page count");
            }
            case PARTIAL -> {
                assertTrue(result.isUsable(), fx.id() + ": PARTIAL outcome must still be usable");
                assertTrue(pageCount(result.repairedPdf()) >= 1,
                        fx.id() + ": PARTIAL output must carry at least one page");
            }
            case ROLLBACK, UNSUPPORTED, REQUIRES_PASSWORD, UNRECOVERABLE, RESOURCE_LIMIT -> {
                assertEquals(RepairResult.Status.FAILED, result.status(),
                        fx.id() + ": expected FAILED (" + fx.outcome() + ") but got "
                                + result.status());
                assertNull(result.repairedPdf(),
                        fx.id() + ": " + fx.outcome() + " must not publish output bytes");
            }
        }

        // 3. Repairing a usable output is stable: it converges, not oscillates.
        if (result.isUsable()) {
            int once = pageCount(result.repairedPdf());
            RepairResult second = PdfRepair.builder()
                    .input(result.repairedPdf())
                    .all()
                    .build()
                    .execute();
            assertTrue(second.status() == RepairResult.Status.CLEAN
                            || second.status() == RepairResult.Status.FIXED,
                    fx.id() + ": second repair not stable, got " + second.status());
            assertEquals(once, pageCount(second.repairedPdf()),
                    fx.id() + ": page count changed on a repeated repair");
        }
    }

    /**
     * A two-page document built by adding a page in an incremental update: the
     * effective revision has two pages. Repair must honour the newer revision
     * and must not resurrect the superseded one-page revision.
     */
    @Test
    void incrementalUpdateKeepsTheNewerRevision() throws IOException {
        byte[] pdf = RepairCorruptionCorpus.incrementalAddedPage();

        RepairResult result = PdfRepair.builder().input(pdf).all().build().execute();
        assertTrue(result.status() == RepairResult.Status.CLEAN
                        || result.status() == RepairResult.Status.FIXED,
                "incremental update should be recoverable, got " + result.status());
        assertEquals(2, pageCount(result.repairedPdf()),
                "repair must reflect the newer revision (2 pages), not the superseded one");
    }

    /**
     * The page-tree back-pointer must be observed as broken before repair and
     * clean after it: detection and repair are separate, truthful facts.
     */
    @Test
    void brokenPageParentIsDetectedThenRepaired() throws IOException {
        byte[] pdf = RepairCorruptionCorpus.brokenPageParent();

        RepairInspection before = PdfRepair.inspectStructured(pdf);
        assertTrue(before.loaded(), before.describe());
        assertTrue(before.pageTreeBrokenParents() >= 1,
                "inspection must observe the broken /Parent: " + before.describe());
        assertTrue(before.structuralDamageObserved(), before.describe());
        assertTrue(before.damageCategories().contains("page_tree"), before.describe());

        RepairResult result = PdfRepair.builder().input(pdf).all().build().execute();
        assertTrue(result.isUsable(), "broken /Parent should remain repairable");
        RepairInspection after = PdfRepair.inspectStructured(result.repairedPdf());
        assertEquals(0, after.pageTreeBrokenParents(),
                "repair must rewrite the /Parent back-pointer: " + after.describe());
    }

    /**
     * A page tree that contains itself is a cycle; repair must break it without
     * losing the pages that are genuinely reachable.
     */
    @Test
    void pageTreeCycleIsBrokenThenRepaired() throws IOException {
        byte[] pdf = RepairCorruptionCorpus.pageTreeCycle();

        RepairInspection before = PdfRepair.inspectStructured(pdf);
        assertTrue(before.loaded(), before.describe());
        assertTrue(before.pageTreeCycles() >= 1,
                "inspection must observe the cycle: " + before.describe());
        assertTrue(before.structuralDamageObserved(), before.describe());
        assertTrue(before.damageCategories().contains("page_tree"), before.describe());

        RepairResult result = PdfRepair.builder().input(pdf).all().build().execute();
        assertTrue(result.isUsable(), "a cyclic page tree should be repairable");
        RepairInspection after = PdfRepair.inspectStructured(result.repairedPdf());
        assertEquals(0, after.pageTreeCycles(),
                "repair must break the cycle: " + after.describe());
        assertEquals(1, pageCount(result.repairedPdf()),
                "the genuine page must survive cycle removal");
    }

    /**
     * The corpus itself must be well formed: unique ids, sensible declared page
     * counts, and coverage of the core outcome classes.
     */
    @Test
    void corpusIsWellFormedAndCoversCoreOutcomes() {
        assertNotNull(fixtures, "corpus not loaded");
        assertTrue(fixtures.size() >= 28,
                "expected a broad corpus, got " + fixtures.size() + " fixtures");

        java.util.Set<String> ids = new java.util.HashSet<>();
        for (RepairCorruptionCorpus.Fixture fx : fixtures) {
            assertNotNull(fx.id(), "null fixture id");
            assertTrue(ids.add(fx.id()), "duplicate fixture id: " + fx.id());
            assertNotNull(fx.note(), fx.id() + ": null note");
            assertNotNull(fx.bytes(), fx.id() + ": null bytes");
            if (fx.outcome() != RepairCorruptionCorpus.Outcome.REPAIRABLE
                    && fx.outcome() != RepairCorruptionCorpus.Outcome.PARTIAL) {
                assertEquals(0, fx.expectedPages(),
                        fx.id() + ": non-recoverable fixture must declare 0 pages");
            }
        }

        java.util.Set<RepairCorruptionCorpus.Outcome> covered = fixtures.stream()
                .map(RepairCorruptionCorpus.Fixture::outcome)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(covered.contains(RepairCorruptionCorpus.Outcome.REPAIRABLE),
                "corpus must include repairable inputs");
        assertTrue(covered.contains(RepairCorruptionCorpus.Outcome.UNRECOVERABLE),
                "corpus must include unrecoverable inputs");
        assertTrue(covered.contains(RepairCorruptionCorpus.Outcome.REQUIRES_PASSWORD),
                "corpus must include a password-protected input");
    }

    /**
     * Zero-length input is refused up front by the builder, not by the native
     * pipeline: a caller cannot mistake "no bytes" for a repairable document.
     */
    @Test
    void emptyInputIsRejectedByTheBuilder() {
        assertThrows(IllegalStateException.class,
                () -> PdfRepair.builder().input(new byte[0]).all().build(),
                "zero-length input must be rejected before any repair is attempted");
    }

    /**
     * A PDF that genuinely uses object streams must be validated without false
     * positives: the inspector finds the /ObjStm containers, validates their
     * member offsets, and reports none invalid.
     */
    @Test
    void objectStreamDocumentIsValidatedWithoutFalsePositives() throws IOException {
        byte[] pdf = RepairCorruptionCorpus.objectStreamPdf();

        RepairInspection inspection = RepairInspection.parse(PdfRepair.inspect(pdf));
        assertTrue(inspection.loaded(), "object-stream document must load");
        assertTrue(inspection.objectStreamCount() > 0,
                "expected /ObjStm containers in a PDF 1.7 document, found "
                        + inspection.objectStreamCount());
        assertEquals(0, inspection.objectStreamInvalidCount(),
                "valid object streams must not be flagged invalid");
        assertFalse(inspection.structuralDamageObserved(),
                "a valid object-stream document must not look structurally damaged");
        assertEquals(2, inspection.pageCount(), "both pages must be reachable");
    }

    private static int pageCount(byte[] pdf) {
        assertNotNull(pdf, "no PDF bytes to count pages in");
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            return doc.pageCount();
        } catch (Exception e) {
            fail("repaired output is not openable: " + e.getMessage());
            return -1;
        }
    }
}

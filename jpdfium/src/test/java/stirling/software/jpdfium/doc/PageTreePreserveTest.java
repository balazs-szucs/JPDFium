package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Preserve mode must never silently prune a page tree. A policy that is allowed
 * to be lossy may drop a repeated reference, and the report must say what it
 * reached; a preserving policy that would need to drop one applies nothing.
 */
class PageTreePreserveTest {

    private static long cyclesAfter(RepairResult result) {
        return Long.parseLong(result.report().outputValidation().get("page_tree_cycles_after"));
    }

    private static boolean warned(RepairResult result, String fragment) {
        return result.report().warnings().stream().anyMatch(w -> w.contains(fragment));
    }

    @Test
    void preserveRefusesToPruneRatherThanDropARepeatedReference() throws Exception {
        byte[] cyclic = RepairCorruptionCorpus.pageTreeCycle();

        RepairResult preserved = PdfRepair.builder().input(cyclic).preserve().build().execute();
        // Refusing to prune leaves the cycle in place, so the rewritten tree cannot
        // be traversed; the pass then fails closed and publishes nothing rather than
        // publish a page tree it silently pruned.
        assertFalse(preserved.isUsable(),
                "a preserving policy must not publish a pruned page tree");
        assertEquals(RepairReport.UNKNOWN, preserved.report().preservation().get("page_tree"),
                "with nothing published the page_tree claim is unknown, never preserved");
        assertTrue(warned(preserved, "did not reopen"),
                "the refusal must be visible as a candidate that could not be reopened: "
                        + preserved.report().warnings());

        RepairResult repaired = PdfRepair.builder().input(cyclic).all().build().execute();
        assertTrue(repaired.isUsable(), "a policy allowed to prune still repairs the document");
        assertEquals(0, cyclesAfter(repaired),
                "the pruning policy removes the cycle and reports the reachable tree");
    }

    @Test
    void aCleanPageTreeIsReportedPreserved() throws Exception {
        byte[] clean = RepairCorpus.validMultiPage();
        RepairResult result = PdfRepair.builder().input(clean).preserve().build().execute();
        assertTrue(result.isUsable(), "an intact document stays usable under preserve");
        assertEquals(RepairReport.PRESERVED, result.report().preservation().get("page_tree"),
                "an intact tree must be reported preserved, not unknown");
        assertEquals(result.report().outputValidation().get("page_tree_reachable_before"),
                result.report().outputValidation().get("page_tree_reachable_after"),
                "preserve mode must not change how many pages are reachable");
    }
}

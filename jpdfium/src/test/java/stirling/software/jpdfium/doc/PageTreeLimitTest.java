package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.jpdfium.panama.RepairLib;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A page tree deeper than the bounded analysis can walk must not be published.
 * The traversal stops before it has seen the whole tree, so any rewrite would be
 * based on incomplete knowledge; a truncated analysis therefore discards the
 * whole candidate and publishes nothing.
 */
class PageTreeLimitTest {

    private static final int DEPTH = 600; // beyond the repair pass's depth bound

    @Test
    void truncatedTraversalIsVisibleToInspectionAndRepairsToOneNothing() throws Exception {
        byte[] deep = RepairCorruptionCorpus.deepPageTree(DEPTH);
        assertTrue(PdfRepair.inspectStructured(deep).pageTreeTruncated(),
                "a tree deeper than the bound must be reported truncated");

        // preserve() has no tolerant fallbacks, so a truncated candidate cannot
        // be silently replaced by a different engine that never walked the tree.
        RepairResult result = PdfRepair.builder().input(deep).preserve().build().execute();
        assertFalse(result.isUsable(), "a truncated analysis must not publish a candidate");
        assertNull(result.repairedPdf(), "FAILED publishes no bytes");
        assertTrue(warned(result, "truncated"),
                "the refusal must be visible: " + result.report().warnings());
    }

    @Test
    void byteAndFileRoutesAgreeThatATruncatedTreePublishesNothing(@TempDir Path tmp)
            throws Exception {
        byte[] deep = RepairCorruptionCorpus.deepPageTree(DEPTH);
        Path input = tmp.resolve("deep.pdf");
        Path output = tmp.resolve("out.pdf");
        Files.write(input, deep);

        // Flags 0x7 = force v1.4 | normalize xref | fix startxref (the same bits
        // Builder.all() composes that reach the native repair entry point).
        RepairResult.Status status = RepairLib.repairToFile(input, output, 0x7);
        assertEquals(RepairResult.Status.FAILED, status,
                "the file route must also refuse a truncated page tree");
        assertFalse(Files.exists(output), "a refused file repair must not create an output");
    }

    @Test
    void aTreeWithinTheBoundStillRepairs() throws Exception {
        // Sanity: shallow nesting must not be mistaken for truncation.
        byte[] shallow = RepairCorruptionCorpus.deepPageTree(4);
        RepairResult result = PdfRepair.builder().input(shallow).preserve().build().execute();
        assertTrue(result.isUsable(), "a shallow nested tree is repairable");
    }

    private static boolean warned(RepairResult result, String fragment) {
        List<String> warnings = result.report().warnings();
        return warnings.stream().anyMatch(w -> w.contains(fragment));
    }
}

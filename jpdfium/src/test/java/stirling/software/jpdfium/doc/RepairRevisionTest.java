package stirling.software.jpdfium.doc;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Incremental revision handling: revision counting, earlier-revision recovery,
 * and the rule that recovery is attempted only when the latest revision cannot
 * be repaired (and its loss is then reported, never hidden).
 */
class RepairRevisionTest {

    private static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(a.length + b.length);
        out.writeBytes(a);
        out.writeBytes(b);
        return out.toByteArray();
    }

    @Test
    void singleRevisionDocumentReportsOneRevision() throws Exception {
        assertEquals(1, PdfRepair.countRevisions(RepairCorpus.validMultiPage()));
    }

    @Test
    void appendedDocumentReportsTwoRevisions() throws Exception {
        byte[] valid = RepairCorpus.validMultiPage();
        assertEquals(2, PdfRepair.countRevisions(concat(valid, valid)));
    }

    @Test
    void earlierRevisionCandidateIsAUsableSingleRevision() throws Exception {
        byte[] valid = RepairCorpus.validMultiPage();
        List<byte[]> candidates = PdfRepair.earlierRevisionCandidates(concat(valid, valid));
        assertFalse(candidates.isEmpty(), "an earlier revision should be recoverable");
        byte[] first = candidates.get(0);
        assertEquals(1, PdfRepair.countRevisions(first));
        RepairResult repaired = PdfRepair.builder().input(first).preserve().build().execute();
        assertTrue(repaired.isUsable(), "the earlier revision must repair to a usable PDF");
    }

    @Test
    void recoveryNotAttemptedWhenLatestRevisionIsUsable() throws Exception {
        RepairResult result = PdfRepair.builder()
                .input(RepairCorpus.validMultiPage())
                .recoverEarlierRevision(true)
                .build()
                .execute();
        assertTrue(result.isUsable());
        assertFalse(result.report().stagesAttempted().contains("revision_recovery"),
                "an intact latest revision must not trigger revision recovery");
        assertFalse(result.report().preservation().containsKey("later_revisions"),
                "no later-revision loss may be claimed when nothing was discarded");
        assertNotEquals(RepairReport.STRATEGY_REVISION_RECOVERY, result.report().strategy());
    }

    @Test
    void salvagePolicyDoesNotClaimRevisionRecoveryOnIntactDocuments() throws Exception {
        RepairResult result = PdfRepair.builder()
                .input(RepairCorpus.validMultiPage())
                .salvage()
                .build()
                .execute();
        assertTrue(result.isUsable());
        assertNotEquals(RepairReport.STRATEGY_REVISION_RECOVERY, result.report().strategy());
        assertFalse(result.report().preservation().containsKey("later_revisions"));
    }
}

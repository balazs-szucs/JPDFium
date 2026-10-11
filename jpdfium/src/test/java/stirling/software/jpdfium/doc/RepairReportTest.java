package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The structured {@link RepairReport}: strategy, stages, preservation and verification. */
class RepairReportTest {

    private static RepairResult repair(java.util.function.Consumer<PdfRepair.Builder> configure)
            throws Exception {
        PdfRepair.Builder b = PdfRepair.builder().input(RepairCorpus.validMultiPage());
        configure.accept(b);
        return b.build().execute();
    }

    @Test
    void preservePolicyReportsCoreStrategyAndVerification() throws Exception {
        RepairResult result = repair(b -> b.preserve());
        assertTrue(result.isUsable());
        RepairReport report = result.report();
        assertNotNull(report);
        assertTrue(report.outcome().equals(RepairReport.OUTCOME_CLEAN)
                        || report.outcome().equals(RepairReport.OUTCOME_REPAIRED),
                report.outcome());
        assertEquals(RepairReport.STRATEGY_QPDF_REWRITE, report.strategy());
        assertTrue(report.stagesAttempted().contains("core_qpdf"),
                report.stagesAttempted().toString());
        assertTrue(report.stagesSucceeded().contains("core_qpdf"),
                report.stagesSucceeded().toString());
        assertTrue(report.stagesAttempted().contains("candidate_reopen"));
        assertTrue(report.stagesSucceeded().contains("candidate_reopen"));
        assertEquals(RepairReport.PRESERVED, report.preservation().get("page_count"));
        assertEquals(RepairReport.UNKNOWN, report.preservation().get("page_content"),
                "page content is never verified, only the page count is");
        assertEquals(RepairReport.UNKNOWN, report.preservation().get("forms"));
        assertEquals(RepairReport.UNKNOWN, report.preservation().get("outlines"));
        assertEquals(RepairReport.UNKNOWN, report.preservation().get("attachments"));
        assertEquals(RepairReport.INVALIDATED_OR_UNKNOWN, report.preservation().get("signatures"));
        assertEquals("true", report.outputValidation().get("pdfium_loaded"));
        assertEquals("3", report.outputValidation().get("enumerated_pages"));
    }

    @Test
    void structuralPreserveNeverSucceedsSalvageStages() throws Exception {
        RepairReport report = repair(b -> b.preserve()).report();
        assertFalse(report.stagesSucceeded().contains("pdfio_page_salvage"));
        assertFalse(report.stagesSucceeded().contains("lopdf_rewrite"));
    }

    @Test
    void allPolicyNoLongerEnablesWholeDocumentBrotli() throws Exception {
        RepairReport report = repair(PdfRepair.Builder::all).report();
        assertFalse(report.stagesAttempted().contains("brotli_transcode"),
                report.stagesAttempted().toString());
        assertFalse(report.warnings().stream().anyMatch(w -> w.startsWith("brotli_transcode")),
                report.warnings().toString());
    }

    @Test
    void requestedBrotliIsReportedUnsupportedAndNotApplied() throws Exception {
        RepairReport report = repair(b -> b.compatibility()).report();
        assertTrue(report.stagesAttempted().contains("brotli_transcode"));
        assertFalse(report.stagesSucceeded().contains("brotli_transcode"));
        assertTrue(report.warnings().stream()
                        .anyMatch(w -> w.startsWith("brotli_transcode_unsupported")),
                report.warnings().toString());
    }

    @Test
    void securePolicyReportsSanitizeSucceeded() throws Exception {
        RepairReport report = repair(b -> b.secure()).report();
        assertTrue(report.stagesAttempted().contains("sanitize"));
        assertTrue(report.stagesSucceeded().contains("sanitize"));
    }

    @Test
    void failedCandidateIsReportedWithoutBytes() {
        byte[] junk = new byte[256];
        for (int i = 0; i < junk.length; i++) {
            junk[i] = (byte) (i * 37 + 11);
        }
        RepairResult result = PdfRepair.builder().input(junk).preserve().build().execute();
        if (!result.isUsable()) {
            assertNull(result.repairedPdf());
            assertEquals(RepairReport.OUTCOME_FAILED, result.report().outcome());
        }
    }
}

package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.panama.NativeLoader;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Truthful preservation and stage reporting: a stage that did not run is not
 * claimed, page preservation is only asserted when the input and output page
 * counts are both known and equal, and the final (sanitized) candidate is
 * re-verified rather than trusted from the pre-sanitize pass.
 */
class RepairPreservationTest {

    @Test
    void unexecutedIccJpxPassesAreNotReportedAsAttempted() throws IOException {
        NativeLoader.ensureLoaded();
        byte[] pdf = RepairCorpus.validMultiPage();

        RepairResult result = PdfRepair.builder().input(pdf)
                .validateIcc(true).validateJpx(true).all().build().execute();
        RepairReport report = result.report();

        assertFalse(report.stagesAttempted().contains("validate_icc"),
                "an unexecuted pass must not be reported as attempted");
        assertFalse(report.stagesAttempted().contains("validate_jpx"),
                "an unexecuted pass must not be reported as attempted");
        assertEquals("unsupported", report.outputValidation().get("validate_icc"));
        assertEquals("unsupported", report.outputValidation().get("validate_jpx"));
    }

    @Test
    void pagesPreservationIsUnknownWhenNothingIsPublished() throws IOException {
        NativeLoader.ensureLoaded();
        byte[] broken = RepairCorpus.truncate(RepairCorpus.validMultiPage(), 0.5);

        RepairResult result = PdfRepair.builder().input(broken).all().build().execute();
        assertEquals(RepairResult.Status.FAILED, result.status(),
                "a truncated document must fail, not publish a candidate");
        assertNull(result.repairedPdf(), "a failure must publish no bytes");
        assertEquals(RepairReport.UNKNOWN, result.report().preservation().get("pages"),
                "with nothing published, page preservation must be unknown");
    }

    @Test
    void pagesPreservationIsPreservedForAnIntactInput() throws IOException {
        NativeLoader.ensureLoaded();
        byte[] pdf = RepairCorpus.validMultiPage();

        RepairResult result = PdfRepair.builder().input(pdf).all().build().execute();
        assertTrue(result.isUsable(), "an undamaged document must stay usable");
        assertEquals(RepairReport.PRESERVED, result.report().preservation().get("pages"));
    }

    @Test
    void sanitizedCandidateIsReverifiedAndStillUsable() throws IOException {
        NativeLoader.ensureLoaded();
        byte[] pdf = RepairCorpus.validMultiPage();

        RepairResult result = PdfRepair.builder().input(pdf).secure().build().execute();
        assertTrue(result.isUsable(), "a sanitized candidate must remain usable");
        assertTrue(result.report().stagesAttempted().contains("final_reverify"),
                "the sanitized candidate must be re-verified");
        assertTrue(result.report().stagesSucceeded().contains("final_reverify"),
                "the re-verification of the sanitized candidate must succeed");
        assertEquals(RepairReport.PRESERVED, result.report().preservation().get("pages"));
    }
}

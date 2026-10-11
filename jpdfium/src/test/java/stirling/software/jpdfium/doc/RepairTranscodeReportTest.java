package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A requested Brotli transcode that cannot run must be reported, not silently
 * skipped. The codec is optional at build time, so the pipeline records the
 * no-op explicitly.
 */
class RepairTranscodeReportTest {

    @Test
    void requestedTranscodeThatCannotRunIsReported() throws Exception {
        byte[] pdf = RepairCorpus.validMultiPage();
        RepairResult result = PdfRepair.builder().input(pdf).transcodeBrotli(true).build().execute();

        assertTrue(result.isUsable(), "a valid PDF stays usable with a reported no-op transcode");
        assertNotNull(result.diagnosticJson());
        assertTrue(result.diagnosticJson().contains("transcode_brotli"), result.diagnosticJson());
        assertTrue(result.diagnosticJson().contains("\"executed\":false"), result.diagnosticJson());
    }

    @Test
    void transcodeNoteAbsentWhenNotRequested() throws Exception {
        byte[] pdf = RepairCorpus.validMultiPage();
        RepairResult result = PdfRepair.builder().input(pdf).build().execute();

        assertTrue(result.isUsable());
        String diagnostics = result.diagnosticJson();
        assertFalse(diagnostics != null && diagnostics.contains("transcode_brotli"));
    }
}

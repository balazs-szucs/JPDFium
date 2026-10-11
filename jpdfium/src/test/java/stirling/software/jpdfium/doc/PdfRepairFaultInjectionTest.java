package stirling.software.jpdfium.doc;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import stirling.software.jpdfium.panama.NativeLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A repair that fails at any stage must fail truthfully: the operation reports
 * {@link RepairResult.Status#FAILED}, publishes no bytes, and names the stage
 * that aborted. This is the guarantee the publication gate exists to make, so
 * it is verified by injecting a fault at each boundary rather than trusting the
 * happy path alone.
 */
class PdfRepairFaultInjectionTest {

    @BeforeAll
    static void loadNative() {
        NativeLoader.ensureLoaded();
    }

    @Test
    void withoutFaultsTheRepairSucceeds() throws Exception {
        RepairResult result = repair(cleanPdf(), stage -> { }).execute();

        assertTrue(result.isUsable(), "a clean input must repair to a usable document");
        assertNotNull(result.repairedPdf());
        assertNotEquals(RepairResult.Status.FAILED, result.status());
    }

    @ParameterizedTest
    @EnumSource(PdfRepair.Stage.class)
    void aFaultAtEachStagePublishesNothing(PdfRepair.Stage stage) throws Exception {
        RepairResult result = repair(cleanPdf(), fault -> {
            if (fault == stage) {
                throw new IllegalStateException("injected " + fault);
            }
        }).execute();

        assertEquals(RepairResult.Status.FAILED, result.status(),
                "a fault at " + stage + " must not be reported as success");
        assertFalse(result.isUsable(), "a faulted repair must not be usable");
        assertNull(result.repairedPdf(), "a faulted repair must publish no bytes");

        String marker = stage.name().toLowerCase(Locale.ROOT) + "_failed";
        assertTrue(result.report().warnings().stream().anyMatch(w -> w.startsWith(marker)),
                "the report must name the failed stage " + stage + ": " + result.report().warnings());
    }

    private static PdfRepair repair(byte[] input, PdfRepair.FaultInjector faults) {
        return PdfRepair.builder()
                .input(input)
                .normalizeXref(true)
                .fixStartxref(true)
                .faultInjector(faults)
                .build();
    }

    private static byte[] cleanPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.LETTER));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}

package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The named policies must each be a usable repair of a valid document and must
 * preserve its page count (structural repair only, no content loss).
 */
class RepairPolicyTest {

    private static void assertUsableAndIntact(RepairResult result) throws Exception {
        assertTrue(result.isUsable(), "policy produced an unusable result: " + result.status());
        int pages;
        try (PdfDocument doc = PdfDocument.open(result.repairedPdf())) {
            pages = doc.pageCount();
        }
        assertEquals(3, pages, "named policies must not lose pages from a valid document");
    }

    @Test
    void preserveKeepsStructure() throws Exception {
        assertUsableAndIntact(PdfRepair.builder()
                .input(RepairCorpus.validMultiPage()).preserve().build().execute());
    }

    @Test
    void compatibilityRepairsAndStaysReadable() throws Exception {
        assertUsableAndIntact(PdfRepair.builder()
                .input(RepairCorpus.validMultiPage()).compatibility().build().execute());
    }

    @Test
    void salvageRecoversWithoutSanitizing() throws Exception {
        assertUsableAndIntact(PdfRepair.builder()
                .input(RepairCorpus.validMultiPage()).salvage().build().execute());
    }

    @Test
    void secureRepairsThenSanitizes() throws Exception {
        assertUsableAndIntact(PdfRepair.builder()
                .input(RepairCorpus.validMultiPage()).secure().build().execute());
    }
}

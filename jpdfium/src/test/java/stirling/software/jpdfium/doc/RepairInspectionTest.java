package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The structured inspection must keep recovery observations and "unknown"
 * distinct from validity claims.
 */
class RepairInspectionTest {

    @Test
    void loadedCleanRecommendsNoRepair() {
        RepairInspection i = RepairInspection.parse(
                "{\"status\":\"loaded\",\"warning_count\":0,\"declared_page_count\":3,"
                        + "\"page_count\":3,\"xref_original_valid\":null,"
                        + "\"trailer_original_valid\":null,\"load_recovered\":false,\"issues\":[]}");
        assertTrue(i.engineAvailable());
        assertTrue(i.loaded());
        assertEquals(RepairInspection.Strategy.NO_REPAIR_OBSERVED, i.strategy());
        assertEquals(0, i.warningCount());
        assertNull(i.xrefOriginalValid(), "a successful load must not certify the original xref");
        assertNull(i.trailerOriginalValid());
        assertEquals(Boolean.FALSE, i.pageCountMismatch());
    }

    @Test
    void loadedWithRecoveryRequiresRepair() {
        RepairInspection i = RepairInspection.parse(
                "{\"status\":\"loaded\",\"warning_count\":2,\"declared_page_count\":3,"
                        + "\"page_count\":3,\"xref_original_valid\":null,"
                        + "\"trailer_original_valid\":null,\"load_recovered\":true,"
                        + "\"issues\":[{\"message\":\"xref not found\"},"
                        + "{\"message\":\"reconstructed xref\"}]}");
        assertEquals(RepairInspection.Strategy.REPAIR_REQUIRED, i.strategy());
        assertEquals(2, i.warningCount());
        assertEquals(2, i.issues().size());
        assertTrue(i.issues().contains("reconstructed xref"));
    }

    @Test
    void declaredPageCountMismatchIsReported() {
        RepairInspection i = RepairInspection.parse(
                "{\"status\":\"loaded\",\"warning_count\":1,\"declared_page_count\":5,"
                        + "\"page_count\":3,\"load_recovered\":true,\"issues\":[]}");
        assertEquals(Boolean.TRUE, i.pageCountMismatch());
    }

    @Test
    void unknownDeclaredCountYieldsNullMismatch() {
        RepairInspection i = RepairInspection.parse(
                "{\"status\":\"loaded\",\"warning_count\":0,\"page_count\":0,"
                        + "\"load_recovered\":false,\"issues\":[]}");
        assertEquals(-1, i.declaredPageCount());
        assertNull(i.pageCountMismatch());
    }

    @Test
    void fatalLoadIsUnrecoverableButTheEngineAnswered() {
        RepairInspection i = RepairInspection.parse(
                "{\"status\":\"fatal\",\"warning_count\":0,\"declared_page_count\":-1,"
                        + "\"page_count\":0,\"xref_original_valid\":false,"
                        + "\"trailer_original_valid\":false,\"load_recovered\":false,"
                        + "\"issues\":[{\"message\":\"bad\"}]}");
        assertTrue(i.engineAvailable());
        assertFalse(i.loaded());
        assertEquals(RepairInspection.Strategy.UNRECOVERABLE, i.strategy());
        assertEquals(Boolean.FALSE, i.xrefOriginalValid());
    }

    @Test
    void unavailableEngineIsUnsupported() {
        RepairInspection i = RepairInspection.parse(
                "{\"status\":\"unavailable\",\"message\":\"qpdf not linked\"}");
        assertFalse(i.engineAvailable());
        assertEquals(RepairInspection.Strategy.UNSUPPORTED, i.strategy());
    }

    @Test
    void emptyInputErrorIsReportedNotCrashing() {
        RepairInspection i = RepairInspection.parse("{\"error\":\"empty input\"}");
        assertEquals(RepairInspection.STATUS_ERROR, i.status());
        assertFalse(i.engineAvailable());
        assertEquals(RepairInspection.Strategy.UNRECOVERABLE, i.strategy());
    }

    @Test
    void structuralFieldsAreParsedAndDriveStrategyAndCategories() {
        RepairInspection i = RepairInspection.parse(
                "{\"status\":\"loaded\",\"warning_count\":0,\"declared_page_count\":2,"
                        + "\"page_count\":2,\"xref_original_valid\":null,"
                        + "\"trailer_original_valid\":null,\"load_recovered\":false,"
                        + "\"object_stream_count\":4,\"object_stream_invalid_count\":1,"
                        + "\"page_tree_cycles\":0,\"page_tree_duplicate_refs\":0,"
                        + "\"page_tree_broken_parents\":2,\"page_tree_reachable\":2,"
                        + "\"page_tree_truncated\":false,\"issues\":[]}");
        assertEquals(4, i.objectStreamCount());
        assertEquals(1, i.objectStreamInvalidCount());
        assertEquals(2, i.pageTreeBrokenParents());
        assertEquals(2, i.pageTreeReachable());
        assertFalse(i.pageTreeTruncated());
        assertTrue(i.structuralDamageObserved(),
                "structural damage must be observed even with no parser warnings");
        // Structural damage alone (no warnings, no recovery) still requires repair.
        assertEquals(RepairInspection.Strategy.REPAIR_REQUIRED, i.strategy());
        assertTrue(i.damageCategories().contains("object_stream"), i.damageCategories().toString());
        assertTrue(i.damageCategories().contains("page_tree"), i.damageCategories().toString());
        assertTrue(i.describe().contains("objstm=invalid:1/examined:4"), i.describe());
    }

    @Test
    void cleanStructuralObservationDoesNotRecommendRepair() {
        RepairInspection i = RepairInspection.parse(
                "{\"status\":\"loaded\",\"warning_count\":0,\"declared_page_count\":3,"
                        + "\"page_count\":3,\"xref_original_valid\":null,"
                        + "\"trailer_original_valid\":null,\"load_recovered\":false,"
                        + "\"object_stream_count\":0,\"object_stream_invalid_count\":0,"
                        + "\"page_tree_cycles\":0,\"page_tree_duplicate_refs\":0,"
                        + "\"page_tree_broken_parents\":0,\"page_tree_reachable\":3,"
                        + "\"page_tree_truncated\":false,\"issues\":[]}");
        assertFalse(i.structuralDamageObserved());
        assertEquals(RepairInspection.Strategy.NO_REPAIR_OBSERVED, i.strategy());
        assertTrue(i.damageCategories().isEmpty(), i.damageCategories().toString());
    }

    @Test
    void absentStructuralFieldsDefaultToZeroAndAreNotInferred() {
        // Older engines omit the structural fields entirely: that is "none
        // observed", and must never be turned into a damage claim.
        RepairInspection i = RepairInspection.parse(
                "{\"status\":\"loaded\",\"warning_count\":0,\"declared_page_count\":1,"
                        + "\"page_count\":1,\"load_recovered\":false,\"issues\":[]}");
        assertEquals(0, i.objectStreamCount());
        assertEquals(0, i.pageTreeBrokenParents());
        assertFalse(i.pageTreeTruncated());
        assertFalse(i.structuralDamageObserved());
        assertEquals(RepairInspection.Strategy.NO_REPAIR_OBSERVED, i.strategy());
    }

    @Test
    void pageTreeTruncationIsReportedAsDamage() {
        RepairInspection i = RepairInspection.parse(
                "{\"status\":\"loaded\",\"warning_count\":0,\"declared_page_count\":5000,"
                        + "\"page_count\":5000,\"load_recovered\":false,"
                        + "\"page_tree_truncated\":true,\"issues\":[]}");
        assertTrue(i.pageTreeTruncated());
        assertTrue(i.structuralDamageObserved());
        assertEquals(RepairInspection.Strategy.REPAIR_REQUIRED, i.strategy());
        assertTrue(i.damageCategories().contains("page_tree"), i.damageCategories().toString());
    }

    @Test
    void realInspectionOfAValidDocumentIsTypedAndTruthful() throws Exception {
        RepairInspection i = PdfRepair.inspectStructured(RepairCorpus.validMultiPage());
        assertTrue(i.engineAvailable(), i.describe());
        assertTrue(i.loaded(), i.describe());
        assertEquals(3, i.pageCount(), i.describe());
        assertEquals(RepairInspection.Strategy.NO_REPAIR_OBSERVED, i.strategy(), i.describe());
        assertNull(i.xrefOriginalValid(), "tolerant load must not certify original validity");
        assertFalse(i.structuralDamageObserved(), i.describe());
        assertEquals(0, i.objectStreamInvalidCount(), i.describe());
        assertEquals(0, i.pageTreeBrokenParents(), i.describe());
        assertEquals(3, i.pageTreeReachable(), i.describe());
        assertTrue(i.describe().contains("status=loaded"), i.describe());
    }
}

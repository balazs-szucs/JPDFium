package stirling.software.jpdfium.doc;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read-only damage classification: categories come from parser observations only,
 * never inferred from a successful load.
 */
class RepairDamageTest {

    private static RepairInspection inspect(String issuesJson) {
        return RepairInspection.parse(
                "{\"status\":\"loaded\",\"warning_count\":1,\"declared_page_count\":1,"
                        + "\"page_count\":1,\"xref_original_valid\":null,"
                        + "\"trailer_original_valid\":null,\"load_recovered\":true,"
                        + "\"issues\":" + issuesJson + "}");
    }

    @Test
    void startxrefAndXrefAreClassified() {
        RepairInspection r = inspect(
                "[{\"message\":\"can't find startxref\"},{\"message\":\"xref table is invalid\"}]");
        List<String> damage = r.damageCategories();
        assertTrue(damage.contains("startxref"), damage.toString());
        assertTrue(damage.contains("xref"), damage.toString());
    }

    @Test
    void objectStreamAndLengthAreClassified() {
        RepairInspection r = inspect(
                "[{\"message\":\"object stream /ObjStm member offset out of range\"},"
                        + "{\"message\":\"stream length mismatch\"}]");
        List<String> damage = r.damageCategories();
        assertTrue(damage.contains("object_stream"), damage.toString());
        assertTrue(damage.contains("stream_length"), damage.toString());
    }

    @Test
    void unknownIssueIsOtherAndDescribeExposesDamage() {
        RepairInspection r = inspect("[{\"message\":\"something odd happened\"}]");
        assertTrue(r.damageCategories().contains("other"), r.damageCategories().toString());
        assertTrue(r.describe().contains("damage=[other]"), r.describe());
    }

    @Test
    void cleanInspectionHasNoDamage() {
        RepairInspection r = inspect("[]");
        assertTrue(r.damageCategories().isEmpty(), r.damageCategories().toString());
        assertFalse(r.describe().contains("damage="), r.describe());
    }
}

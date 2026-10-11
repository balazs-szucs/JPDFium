package stirling.software.jpdfium.doc;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Additional coverage for {@link RepairInspection#damageCategories()}: the
 * classifier must be case-insensitive, sorted, de-duplicated, and must fall back
 * to {@code other} rather than guessing.
 */
class RepairDamageCategoriesTest {

    private static List<String> damage(String... messages) {
        StringBuilder issues = new StringBuilder("[");
        for (int i = 0; i < messages.length; i++) {
            if (i > 0) {
                issues.append(',');
            }
            issues.append("{\"message\":\"")
                    .append(messages[i].replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\"}");
        }
        issues.append(']');
        String json = "{\"status\":\"loaded\",\"warning_count\":1,\"declared_page_count\":1,"
                + "\"page_count\":1,\"xref_original_valid\":null,\"trailer_original_valid\":null,"
                + "\"load_recovered\":true,\"issues\":" + issues + "}";
        return RepairInspection.parse(json).damageCategories();
    }

    @Test
    void pageTreeIsClassified() {
        assertTrue(damage("page tree contains a cycle").contains("page_tree"));
    }

    @Test
    void trailerIsClassified() {
        assertTrue(damage("trailer dictionary is missing").contains("trailer"));
    }

    @Test
    void encryptionIsClassified() {
        assertTrue(damage("encryption dictionary is invalid").contains("encryption"));
    }

    @Test
    void truncationIsClassified() {
        assertTrue(damage("unexpected EOF while reading").contains("truncated"));
    }

    @Test
    void slashLengthIsClassifiedAsStreamLength() {
        assertTrue(damage("stream /Length exceeds available data").contains("stream_length"));
    }

    @Test
    void categoriesAreSortedUniqueAndCaseInsensitive() {
        List<String> d = damage("XREF is broken", "xref is broken", "Object Stream damaged");
        assertEquals(List.of("object_stream", "xref"), d);
    }

    @Test
    void multipleCategoriesAccumulate() {
        List<String> d = damage("bad startxref", "trailer invalid", "encryption broken");
        assertTrue(d.contains("startxref"));
        assertTrue(d.contains("xref"));
        assertTrue(d.contains("trailer"));
        assertTrue(d.contains("encryption"));
    }

    @Test
    void unrelatedMessagesFallBackToOther() {
        assertEquals(List.of("other"), damage("something odd happened"));
    }

    @Test
    void cleanInspectionHasNoDamageCategories() {
        assertTrue(damage().isEmpty());
        assertFalse(RepairInspection
                .parse("{\"status\":\"loaded\",\"warning_count\":0,\"declared_page_count\":1,"
                        + "\"page_count\":1,\"xref_original_valid\":null,"
                        + "\"trailer_original_valid\":null,\"load_recovered\":false,\"issues\":[]}")
                .describe()
                .contains("damage="));
    }
}

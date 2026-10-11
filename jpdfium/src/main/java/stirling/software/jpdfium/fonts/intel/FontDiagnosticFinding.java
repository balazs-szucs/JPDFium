package stirling.software.jpdfium.fonts.intel;

import java.util.Objects;

/**
 * One actionable cause, on one font resource of one page, that explains why a
 * repair was withheld.
 *
 * <p>A finding is evidence, not an outcome: it never asserts that a repair
 * succeeded, and its absence is not proof that none was needed. When the native
 * planner skips a resource without a recognised {@link FontDiagnostic}, no
 * finding is produced; the unknown cause stays unclassified rather than being
 * forced into a known one.
 *
 * @param pageIndex zero-based page the resource is used on
 * @param resourceName font resource key in the page {@code /Resources /Font} dict
 * @param diagnostic the stable cause
 */
public record FontDiagnosticFinding(int pageIndex, String resourceName, FontDiagnostic diagnostic) {

    public FontDiagnosticFinding {
        resourceName = resourceName == null ? "" : resourceName;
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    /** The stable wire code, for callers that match on strings. */
    public String code() {
        return diagnostic.code();
    }
}

package stirling.software.jpdfium.fonts.intel;

import java.util.Comparator;
import java.util.List;

/**
 * A prepared, evidence-based change to one font resource on one page.
 *
 * <p>A plan is only valid for the document state it was prepared against. The
 * {@link #stateFingerprint()} captures the evidence snapshot the plan was derived
 * from, and {@link #fingerprint()} folds that together with the exact mappings, so
 * a caller can reject a plan whose document moved underneath it.
 *
 * @param pageIndex zero-based page the plan targets
 * @param resourceName the font resource the plan targets
 * @param baseFont the {@code /BaseFont} the plan targets
 * @param capability the capability this plan was derived from
 * @param action short action identifier (e.g. {@code "fix_tounicode"})
 * @param mappings the mappings that justify the action
 * @param rationale human-readable explanation of the expected semantic change
 * @param stateFingerprint opaque identity of the evidence snapshot this plan targets
 */
public record FontRepairPlan(
        int pageIndex,
        String resourceName,
        String baseFont,
        FontCapability capability,
        String action,
        List<UnicodeMapping> mappings,
        String rationale,
        String stateFingerprint) {

    public static final String ACTION_FIX_TOUNICODE = "fix_tounicode";

    public FontRepairPlan {
        resourceName = resourceName == null ? "" : resourceName;
        baseFont = baseFont == null ? "" : baseFont;
        action = action == null ? "" : action;
        rationale = rationale == null ? "" : rationale;
        stateFingerprint = stateFingerprint == null ? "" : stateFingerprint;
        mappings = mappings == null ? List.of() : List.copyOf(mappings);
    }

    /** True when this plan changes the document when applied. */
    public boolean mutates() {
        return ACTION_FIX_TOUNICODE.equals(action);
    }

    /**
     * Stable identity of the whole plan: the targeted resource and its evidence
     * snapshot, plus every mapping that motivates the action. Two plans
     * describing the same change against the same document state compare equal
     * here; any drift produces a different value.
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder(64);
        sb.append(pageIndex).append('|').append(resourceName).append('|').append(baseFont)
                .append('|').append(capability).append('|').append(action)
                .append('|').append(stateFingerprint);
        mappings.stream()
                .sorted(Comparator.comparingInt(UnicodeMapping::code)
                        .thenComparingInt(UnicodeMapping::unicode)
                        .thenComparing(m -> String.valueOf(m.provenance())))
                .forEach(m -> sb.append('|').append(m.code()).append(':').append(m.unicode())
                        .append(':').append(m.provenance()));
        return sb.toString();
    }
}

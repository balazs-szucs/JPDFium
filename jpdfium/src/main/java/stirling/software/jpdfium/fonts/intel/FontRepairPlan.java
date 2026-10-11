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
 * @param nativeIdentity the native plan identity this plan was prepared against; empty
 *     for an advisory plan built from evidence alone that cannot authorise a write
 * @param affectedReferences every {@code page\u0000resource} reference the target font
 *     object is shared with, so a caller can refuse a write that would reach a
 *     reference it did not authorise
 */
public record FontRepairPlan(
        int pageIndex,
        String resourceName,
        String baseFont,
        FontCapability capability,
        String action,
        List<UnicodeMapping> mappings,
        String rationale,
        String stateFingerprint,
        String nativeIdentity,
        List<String> affectedReferences) {

    public static final String ACTION_FIX_TOUNICODE = "fix_tounicode";

    public FontRepairPlan {
        resourceName = resourceName == null ? "" : resourceName;
        baseFont = baseFont == null ? "" : baseFont;
        action = action == null ? "" : action;
        rationale = rationale == null ? "" : rationale;
        stateFingerprint = stateFingerprint == null ? "" : stateFingerprint;
        nativeIdentity = nativeIdentity == null ? "" : nativeIdentity;
        mappings = mappings == null ? List.of() : List.copyOf(mappings);
        affectedReferences = affectedReferences == null ? List.of() : List.copyOf(affectedReferences);
    }

    /**
     * Encode a font reference as {@code pageIndex\u0000resource}, the form used by
     * {@link #affectedReferences()}.
     */
    public static String referenceKey(int pageIndex, String resourceName) {
        return pageIndex + "\u0000" + (resourceName == null ? "" : resourceName);
    }

    /**
     * Encode a scope-qualified font reference. A page-scope reference (empty
     * {@code scopePath}) is identical to {@link #referenceKey(int, String)}; a
     * reference reached through a form XObject or annotation appearance carries
     * its scope path so that two resources with the same name in different scopes
     * do not collide.
     */
    public static String referenceKey(int pageIndex, String scopePath, String resourceName) {
        if (scopePath == null || scopePath.isEmpty()) {
            return referenceKey(pageIndex, resourceName);
        }
        return pageIndex + "\u0000" + scopePath + "\u0000"
                + (resourceName == null ? "" : resourceName);
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
                .append('|').append(stateFingerprint).append('|').append(nativeIdentity);
        affectedReferences.stream().sorted().forEach(r -> sb.append('|').append(r));
        mappings.stream()
                .sorted(Comparator.comparingInt(UnicodeMapping::code)
                        .thenComparingInt(UnicodeMapping::unicode)
                        .thenComparing(m -> String.valueOf(m.provenance())))
                .forEach(m -> sb.append('|').append(m.code()).append(':').append(m.unicode())
                        .append(':').append(m.provenance()));
        return sb.toString();
    }
}

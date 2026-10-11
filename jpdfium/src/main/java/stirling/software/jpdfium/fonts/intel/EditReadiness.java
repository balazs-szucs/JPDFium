package stirling.software.jpdfium.fonts.intel;

import java.util.Objects;

/**
 * Editor-facing preflight for one font resource on one page: can the existing
 * text be understood, can unchanged content be preserved, and can a replacement
 * run be emitted.
 *
 * <p>Three capability levels are kept separate:
 *
 * <ul>
 *   <li>{@link #engineCapable()} — the loaded native bridge implements the
 *       needed operation at all;</li>
 *   <li>{@link #fontCapable()} — this font/encoding is inside the supported
 *       recovery set;</li>
 *   <li>{@link #runEmittable()} — a replacement for this specific run can be
 *       encoded correctly from evidence.</li>
 * </ul>
 *
 * <p>This is a preflight only: it never emits a replacement font. The
 * {@link #strategy()} names the approach a caller would need.
 *
 * @param pageIndex zero-based page
 * @param resourceName font resource key in the page {@code /Resources /Font} dict
 * @param baseFont {@code /BaseFont} name as written
 * @param engineCapable native bridge implements the needed operation
 * @param fontCapable this font/encoding is within the supported recovery set
 * @param runEmittable a replacement run could be emitted from evidence
 * @param capability the derived per-font capability outcome
 * @param strategy required font strategy, or "" when none is required
 * @param unsupportedReason "" unless the operation is unsupported
 * @param recoveredCount codes with a trustworthy Unicode scalar
 * @param ambiguousCount codes where more than one Unicode reaches one glyph
 * @param unresolvedCount codes that could not be resolved at all
 */
public record EditReadiness(
        int pageIndex,
        String resourceName,
        String baseFont,
        boolean engineCapable,
        boolean fontCapable,
        boolean runEmittable,
        FontCapability capability,
        String strategy,
        String unsupportedReason,
        int recoveredCount,
        int ambiguousCount,
        int unresolvedCount) {

    /** Font strategy: reuse the original font as-is. */
    public static final String STRATEGY_ORIGINAL = "original_font";
    /** Font strategy: reconstruct a mapping for the used codes. */
    public static final String STRATEGY_REPAIR_MAPPING = "repair_mapping";
    /** Font strategy: a human must resolve an ambiguous/unresolved mapping. */
    public static final String STRATEGY_MANUAL = "manual_identification";
    /** Font strategy: the original font cannot carry the text; replace it. */
    public static final String STRATEGY_REPLACEMENT = "replacement_font_required";

    public EditReadiness {
        Objects.requireNonNull(capability, "capability");
        resourceName = resourceName == null ? "" : resourceName;
        baseFont = baseFont == null ? "" : baseFont;
        strategy = strategy == null ? "" : strategy;
        unsupportedReason = unsupportedReason == null ? "" : unsupportedReason;
    }

    /**
     * Derives readiness from one font's evidence; {@code engineCapable} (from
     * {@link FontCapabilities#unicodeRepair()}) is independent of the per-font outcome.
     */
    public static EditReadiness of(FontEvidence e, boolean engineCapable) {
        Objects.requireNonNull(e, "evidence");
        FontCapability cap = FontIntelligence.capability(e);
        boolean fontCapable = cap != FontCapability.UNSUPPORTED_OPERATION;
        boolean runEmittable = cap == FontCapability.ORIGINAL_REUSABLE
                || cap == FontCapability.MAPPING_REPAIR_AVAILABLE;
        String strategy = strategyFor(cap);
        String reason = cap == FontCapability.UNSUPPORTED_OPERATION
                ? "unsupported font subtype/encoding: "
                        + (e.subtype().isEmpty() ? e.kind().name() : e.subtype())
                : "";
        return new EditReadiness(
                e.pageIndex(),
                e.resourceName(),
                e.baseFont(),
                engineCapable,
                fontCapable,
                runEmittable,
                cap,
                strategy,
                reason,
                e.mappingCount(),
                e.ambiguousCount(),
                e.unresolvedCount());
    }

    private static String strategyFor(FontCapability cap) {
        return switch (cap) {
            case ORIGINAL_REUSABLE -> STRATEGY_ORIGINAL;
            case MAPPING_REPAIR_AVAILABLE -> STRATEGY_REPAIR_MAPPING;
            case MANUAL_CONFIRMATION_REQUIRED -> STRATEGY_MANUAL;
            case REPLACEMENT_REQUIRED -> STRATEGY_REPLACEMENT;
            case UNSUPPORTED_OPERATION -> "";
        };
    }

    /** Compact one-line summary for diagnostics and host payloads. */
    public String describe() {
        return capability.name().toLowerCase(java.util.Locale.ROOT)
                + " engine=" + engineCapable
                + " font=" + fontCapable
                + " run=" + runEmittable
                + " strategy=" + (strategy.isEmpty() ? "-" : strategy)
                + " recovered=" + recoveredCount
                + " ambiguous=" + ambiguousCount
                + " unresolved=" + unresolvedCount
                + (unsupportedReason.isEmpty() ? "" : " reason=\"" + unsupportedReason + "\"");
    }
}

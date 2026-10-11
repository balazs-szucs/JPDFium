package stirling.software.jpdfium.fonts.intel;

import java.util.Locale;

/**
 * How a Unicode value for a PDF character code was established.
 *
 * <p>The engine never collapses this to a confidence percentage: a value is
 * declared or reconstructed from evidence, otherwise unresolved or ambiguous.
 */
public enum FontProvenance {
    /** Read from a valid declared {@code /ToUnicode} CMap. */
    DECLARED,
    /** Reconstructed from the font encoding/CMap plus the embedded font program. */
    RECOVERED,
    /** More than one plausible value; a caller must disambiguate. */
    AMBIGUOUS,
    /** No available evidence produced a value. */
    UNRESOLVED,
    /** Evidence was present but structurally invalid. */
    INVALID,
    /** The construct is outside the engine's current support. */
    UNSUPPORTED;

    /** Parse the provenance token emitted by the native bridge. */
    public static FontProvenance fromName(String name) {
        if (name == null) {
            return UNRESOLVED;
        }
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "DECLARED" -> DECLARED;
            case "RECOVERED" -> RECOVERED;
            case "AMBIGUOUS" -> AMBIGUOUS;
            case "INVALID" -> INVALID;
            case "UNSUPPORTED" -> UNSUPPORTED;
            default -> UNRESOLVED;
        };
    }

    /** True when this provenance yields a trustworthy code point. */
    public boolean resolved() {
        return this == DECLARED || this == RECOVERED;
    }
}

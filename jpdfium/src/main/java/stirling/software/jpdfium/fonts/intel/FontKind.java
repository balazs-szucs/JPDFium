package stirling.software.jpdfium.fonts.intel;

import java.util.Locale;

/**
 * PDF font classification relevant to interpreting character codes.
 *
 * <p>Simple fonts map a single byte through an encoding to a glyph; composite
 * (Type0) fonts map a character code through a CMap and optionally a
 * {@code CIDToGIDMap} to a glyph.
 */
public enum FontKind {
    SIMPLE,
    COMPOSITE,
    UNKNOWN;

    /** Parse the classification token emitted by the native bridge. */
    public static FontKind fromName(String name) {
        if (name == null) {
            return UNKNOWN;
        }
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "simple" -> SIMPLE;
            case "composite" -> COMPOSITE;
            default -> UNKNOWN;
        };
    }
}

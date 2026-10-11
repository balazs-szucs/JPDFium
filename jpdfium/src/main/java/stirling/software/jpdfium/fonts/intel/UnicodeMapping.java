package stirling.software.jpdfium.fonts.intel;

/**
 * One PDF character code and the Unicode scalar recovered for it, with provenance.
 *
 * <p>A code is one of: a source byte for a simple font, a character code for a
 * composite (Type0) font, or, where the mapping is Identity, a CID. It is never a
 * glyph identifier or a Unicode scalar until a mapping says so.
 *
 * @param code source character code (always non-negative)
 * @param unicode recovered Unicode scalar, or {@code -1} when unresolved
 * @param provenance how {@code unicode} was established
 * @param note short human-readable derivation note (never authoritative)
 */
public record UnicodeMapping(int code, int unicode, FontProvenance provenance, String note) {

    public UnicodeMapping {
        if (code < 0) {
            throw new IllegalArgumentException("code must be non-negative");
        }
        if (provenance == null) {
            throw new NullPointerException("provenance");
        }
        if (unicode < 0 && provenance.resolved()) {
            throw new IllegalArgumentException("a resolved mapping must carry a code point");
        }
        note = note == null ? "" : note;
    }

    /** True when this mapping carries a concrete Unicode scalar value. */
    public boolean resolved() {
        return provenance.resolved() && unicode >= 0;
    }

    /** An unresolved mapping for a code the engine could not resolve. */
    public static UnicodeMapping unresolved(int code, String note) {
        return new UnicodeMapping(code, -1, FontProvenance.UNRESOLVED, note);
    }
}

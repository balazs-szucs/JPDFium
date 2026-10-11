package stirling.software.jpdfium.fonts.intel;

import java.util.Locale;
import java.util.Optional;

/**
 * Stable diagnostic reason codes shared by the font-repair, plan-staleness and
 * structural-salvage paths.
 *
 * <p>Each code names a distinct, actionable cause for a repair that was not
 * performed (or a document that could only be recovered lossily). Codes are
 * additive: a caller must tolerate a code it does not recognise and must never
 * read the absence of a code as evidence that a repair succeeded. A repair that
 * was skipped without a cause from this set carries no code at all.
 *
 * <p>A code is never a substitute for the repair outcome. It explains why a
 * change was withheld; whether the document is usable is a separate claim.
 */
public enum FontDiagnostic {

    /**
     * A simple font names a built-in encoding this subsystem does not implement.
     * The codes cannot be decoded and no mapping is emitted rather than guessed.
     */
    BUILTIN_ENCODING_UNSUPPORTED,

    /**
     * A symbolic font declares no explicit {@code /Encoding}; its glyph codes
     * use the program's built-in encoding, which is not decoded. StandardEncoding
     * is deliberately not assumed.
     *
     * <p>This also covers the standard-14 symbolic base fonts ({@code /Symbol},
     * {@code /ZapfDingbats}) when they carry no {@code /FontDescriptor}: the
     * descriptor's symbolic flag cannot see them, yet falling through to
     * StandardEncoding would emit an ASCII identity map (e.g. {@code 0x4B ->
     * U+004B}) for a dingbat or Greek glyph whose real code point is elsewhere
     * (e.g. {@code 0x4B -> U+272B}). Such a font is declined, never rewritten.
     */
    SYMBOLIC_FONT_UNSUPPORTED,

    /**
     * A composite/CFF font carries no code mapping this subsystem can reproduce
     * (an embedded CMap, or a missing/unusable descendant or glyph program).
     */
    CFF_CID_MAPPING_UNSUPPORTED,

    /**
     * A declared {@code /ToUnicode} uses a construct that cannot be preserved
     * byte-faithfully (inherited mappings, or a malformed body). Rewriting it
     * would drop or corrupt entries, so it is left untouched.
     */
    DECLARED_CMAP_UNSUPPORTED,

    /**
     * A bounded analysis stopped early (an oversized charmap or a mapping cap),
     * so no complete mapping decision can be justified.
     */
    ANALYSIS_LIMIT_REACHED,

    /**
     * Every code the analysis could recover was ambiguous: two glyphs share a
     * name, or several codepoints reach one glyph, so no mapping can be committed.
     */
    AMBIGUOUS_GLYPH_SEMANTICS,

    /**
     * The document changed between planning and application. The prepared plan
     * no longer matches the evidence it was derived from and is refused.
     */
    SOURCE_STATE_CHANGED,

    /**
     * The document could not be repaired in place and required rebuilding its
     * structure from salvaged pages or an earlier revision. Anything outside
     * those pages is lost.
     */
    STRUCTURAL_SALVAGE_REQUIRED;

    /** The stable wire code, identical to {@link #name()}. */
    public String code() {
        return name();
    }

    /**
     * Parse a reason code as emitted by the native bridge. Returns empty for a
     * null, blank or unrecognised code rather than inventing one, so an unknown
     * cause stays distinct from a known one.
     */
    public static Optional<FontDiagnostic> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(code.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return name();
    }
}

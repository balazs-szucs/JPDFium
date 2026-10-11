package stirling.software.jpdfium.fonts.intel;

/**
 * What a caller can do with a font resource, given the evidence available.
 *
 * <p>These states are the editor-facing contract: a caller should branch on
 * this value rather than infer capability from raw flags.
 */
public enum FontCapability {
    /** The declared semantics are trustworthy; the original font may be reused. */
    ORIGINAL_REUSABLE,
    /** No trustworthy program evidence; a replacement font is required. */
    REPLACEMENT_REQUIRED,
    /** Missing semantics can be reconstructed from evidence (e.g. {@code /ToUnicode}). */
    MAPPING_REPAIR_AVAILABLE,
    /** Partial evidence only; a human must confirm before changing the document. */
    MANUAL_CONFIRMATION_REQUIRED,
    /** The text operation is outside the engine's current support. */
    UNSUPPORTED_OPERATION
}

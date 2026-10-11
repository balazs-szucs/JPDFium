package stirling.software.jpdfium.fonts.intel;

/**
 * Embedding and subsetting rights decoded from the OpenType {@code OS/2}
 * {@code fsType} bit field.
 *
 * <p>The raw {@code fsType} word is preserved verbatim; these accessors only
 * decode it. Restrictions are never cleared to make embedding succeed. A font
 * file's own licensing may be stricter than {@code fsType}, so this is evidence,
 * not permission.
 *
 * @param fsType raw OpenType {@code OS/2} {@code fsType}, or {@code -1} when the
 *     font has no {@code OS/2} table / was not parsed
 */
public record FontEmbedding(int fsType) {

    public static final int FSTYPE_RESTRICTED = 0x0002;
    public static final int FSTYPE_PREVIEW_AND_PRINT = 0x0004;
    public static final int FSTYPE_EDITABLE = 0x0008;
    public static final int FSTYPE_NO_SUBSETTING = 0x0100;
    public static final int FSTYPE_BITMAP_ONLY = 0x0200;

    /** High-level embedding permission bit. */
    public Permission permission() {
        if (fsType < 0) {
            return Permission.UNKNOWN;
        }
        if ((fsType & FSTYPE_RESTRICTED) != 0) {
            return Permission.RESTRICTED;
        }
        if ((fsType & FSTYPE_PREVIEW_AND_PRINT) != 0) {
            return Permission.PREVIEW_AND_PRINT;
        }
        if ((fsType & FSTYPE_EDITABLE) != 0) {
            return Permission.EDITABLE;
        }
        if ((fsType & FSTYPE_BITMAP_ONLY) != 0) {
            return Permission.BITMAP_ONLY;
        }
        return Permission.INSTALLABLE;
    }

    /** True when the font declares "no subsetting". */
    public boolean noSubsetting() {
        return fsType >= 0 && (fsType & FSTYPE_NO_SUBSETTING) != 0;
    }

    /** True when the font may only be embedded as a bitmap. */
    public boolean bitmapOnly() {
        return fsType >= 0 && (fsType & FSTYPE_BITMAP_ONLY) != 0;
    }

    /** True when the font may be embedded and used to render editable text. */
    public boolean allowsEditableEmbedding() {
        Permission p = permission();
        return p == Permission.INSTALLABLE || p == Permission.EDITABLE;
    }

    /** Embedding permission values defined by OpenType {@code OS/2}. */
    public enum Permission {
        INSTALLABLE,
        RESTRICTED,
        PREVIEW_AND_PRINT,
        EDITABLE,
        BITMAP_ONLY,
        UNKNOWN
    }
}

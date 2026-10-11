package stirling.software.jpdfium.fonts.intel;

import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.panama.NativeRuntime;

/**
 * Reports which font-intelligence operations the loaded native bridge actually
 * implements, so callers can distinguish "ran and found nothing to change" from
 * "not implemented".
 *
 * <p>Success codes are not capability reports: {@code jpdfium_font_repair_widths}
 * returns {@code OK} with a zero count, but width repair is not implemented —
 * repairing {@code /W} against font-program advances can move text, so it needs
 * an explicit preservation policy.
 *
 * @param backend            human-readable backend description
 * @param evidence           per-font evidence extraction is available
 * @param unicodeRepair      {@code /ToUnicode} reconstruction is available
 * @param widthRepair        {@code /W} repair is available (currently never)
 * @param type1Conversion    Type1 → CFF conversion is available (currently never)
 * @param glyphResubset      existing-font re-subsetting is available (currently never)
 * @param shaping            HarfBuzz shaping is available
 * @param coverage           code-point coverage queries are available
 * @param subsetting         font subsetting is available
 * @param qpdf               the QPDF-backed document-rewrite path is compiled in;
 *                           independent of the font features above
 */
public record FontCapabilities(
        String backend,
        boolean evidence,
        boolean unicodeRepair,
        boolean widthRepair,
        boolean type1Conversion,
        boolean glyphResubset,
        boolean shaping,
        boolean coverage,
        boolean subsetting,
        boolean qpdf) {

    /** {@code JPDFIUM_ABI_QUERY_HAS_QPDF} from {@code jpdfium.h}. */
    private static final int ABI_QUERY_HAS_QPDF = 11;

    /**
     * Detects the capabilities of the currently loaded native bridge. The stub
     * bridge supports none of these operations.
     */
    public static FontCapabilities detect() {
        boolean full = NativeRuntime.isFull();
        return new FontCapabilities(
                full ? "freetype+harfbuzz+qpdf" : "stub",
                full,
                full,
                // Not implemented yet, even in the full bridge.
                false,
                false,
                false,
                full,
                full,
                full,
                queryHasQpdf());
    }

    /**
     * Asks the native bridge whether QPDF is compiled in, rather than inferring it
     * from the font-feature flags.
     */
    private static boolean queryHasQpdf() {
        try {
            return JpdfiumLib.abiQuery(ABI_QUERY_HAS_QPDF) != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True when any mapping-repair operation is available. */
    public boolean anyRepair() {
        return unicodeRepair || widthRepair || type1Conversion || glyphResubset;
    }
}

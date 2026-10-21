package stirling.software.jpdfium.doc;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.util.Arrays;

import stirling.software.jpdfium.exception.JPDFiumException;

/** RGB to DeviceCMYK conversion backed by a real ICC profile. */
final class IccCmykTransform {

    private static final int CACHE_BITS = 10;
    private static final int CACHE_SIZE = 1 << CACHE_BITS;
    private static final int CACHE_SHIFT = 32 - CACHE_BITS;

    private static final ThreadLocal<SourceCache> SOURCE_CACHE =
            ThreadLocal.withInitial(SourceCache::new);

    private final ColorSpace srgb = ColorSpace.getInstance(ColorSpace.CS_sRGB);
    private final ICC_ColorSpace cmyk;
    private final float[] rgb = new float[3];
    private byte[] lastSourceIcc;
    private ICC_ColorSpace lastSourceSpace;
    private final int[] cacheKeys = new int[CACHE_SIZE];
    private final int[] cacheVals = new int[CACHE_SIZE * 4];

    /** Build from a 4-component CMYK ICC profile. */
    IccCmykTransform(byte[] iccProfile) {
        ICC_Profile profile;
        try {
            profile = ICC_Profile.getInstance(iccProfile);
        } catch (IllegalArgumentException e) {
            throw new JPDFiumException("Invalid ICC profile: " + e.getMessage(), e);
        }
        int components = profile.getNumComponents();
        if (components != 4 || profile.getColorSpaceType() != ColorSpace.TYPE_CMYK) {
            throw new JPDFiumException("ICC profile is not a CMYK profile (components="
                    + components + "); expected 4");
        }
        this.cmyk = new ICC_ColorSpace(profile);
        Arrays.fill(cacheKeys, -1);
    }

    /** Convert one sRGB triple into CMYK. */
    void toCmyk(int r, int g, int b, int[] out) {
        // Direct-mapped memo for repeated fills/strokes; the ICC path returns fresh
        // float arrays, but cached values always equal a fresh conversion.
        int key = (r << 16) | (g << 8) | b;
        int index = (key * 0x9E3779B1) >>> CACHE_SHIFT;
        int base = index << 2;
        if (cacheKeys[index] == key) {
            out[0] = cacheVals[base];
            out[1] = cacheVals[base + 1];
            out[2] = cacheVals[base + 2];
            out[3] = cacheVals[base + 3];
            return;
        }
        rgb[0] = clampUnit(r);
        rgb[1] = clampUnit(g);
        rgb[2] = clampUnit(b);
        float[] xyz = srgb.toCIEXYZ(rgb);
        float[] device = cmyk.fromCIEXYZ(xyz);
        for (int i = 0; i < 4; i++) {
            out[i] = clamp255(device[i]);
            cacheVals[base + i] = out[i];
        }
        cacheKeys[index] = key;
    }

    /**
     * Convert raw source-ICC components through the source profile into the
     * target CMYK profile.
     */
    void toCmykFromIcc(byte[] sourceIcc, float[] components, int[] out) {
        ICC_ColorSpace source = sourceSpace(sourceIcc, components.length);
        float[] xyz = source.toCIEXYZ(components);
        float[] device = cmyk.fromCIEXYZ(xyz);
        for (int i = 0; i < 4; i++) {
            out[i] = clamp255(device[i]);
        }
    }

    /**
     * Convert raw source-ICC components to CMYK without a target profile:
     * source ICC -> sRGB -> profile-free GCR.
     */
    static void sourceToCmyk(byte[] sourceIcc, float[] components,
                             boolean extractBlack, int[] out) {
        ICC_ColorSpace source = staticSourceSpace(sourceIcc, components.length);
        float[] xyz = source.toCIEXYZ(components);
        ColorSpace srgb = ColorSpace.getInstance(ColorSpace.CS_sRGB);
        float[] rgb = srgb.fromCIEXYZ(xyz);
        int r = clamp255(rgb[0]);
        int g = clamp255(rgb[1]);
        int b = clamp255(rgb[2]);
        PdfColorConverter.rgbToCmyk(r, g, b, extractBlack, out);
    }

    private ICC_ColorSpace sourceSpace(byte[] sourceIcc, int expectedComponents) {
        if (lastSourceSpace != null && lastSourceIcc != null
                && lastSourceIcc.length == sourceIcc.length
                && Arrays.equals(lastSourceIcc, sourceIcc)) {
            return lastSourceSpace;
        }
        ICC_Profile profile;
        try {
            profile = ICC_Profile.getInstance(sourceIcc);
        } catch (IllegalArgumentException e) {
            throw new JPDFiumException("Invalid source ICC profile: " + e.getMessage(), e);
        }
        if (profile.getNumComponents() != expectedComponents) {
            throw new JPDFiumException("Source ICC component count mismatch: profile="
                    + profile.getNumComponents() + " components=" + expectedComponents);
        }
        ICC_ColorSpace space = new ICC_ColorSpace(profile);
        lastSourceIcc = sourceIcc.clone();
        lastSourceSpace = space;
        return space;
    }

    private static ICC_ColorSpace staticSourceSpace(byte[] sourceIcc, int expectedComponents) {
        SourceCache cache = SOURCE_CACHE.get();
        if (cache.space != null && Arrays.equals(cache.icc, sourceIcc)) {
            return cache.space;
        }
        ICC_Profile profile;
        try {
            profile = ICC_Profile.getInstance(sourceIcc);
        } catch (IllegalArgumentException e) {
            throw new JPDFiumException("Invalid source ICC profile: " + e.getMessage(), e);
        }
        if (profile.getNumComponents() != expectedComponents) {
            throw new JPDFiumException("Source ICC component count mismatch: profile="
                    + profile.getNumComponents() + " components=" + expectedComponents);
        }
        ICC_ColorSpace space = new ICC_ColorSpace(profile);
        cache.icc = sourceIcc.clone();
        cache.space = space;
        return space;
    }

    private static final class SourceCache {
        private byte[] icc;
        private ICC_ColorSpace space;
    }

    private static float clampUnit(int v) {
        if (v <= 0) return 0f;
        if (v >= 255) return 1f;
        return v / 255f;
    }

    private static int clamp255(float v) {
        float scaled = v * 255f + 0.5f;
        if (scaled <= 0f) return 0;
        if (scaled >= 255f) return 255;
        return (int) scaled;
    }
}

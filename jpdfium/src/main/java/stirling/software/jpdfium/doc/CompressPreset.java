package stirling.software.jpdfium.doc;

/**
 * Compression presets for common use cases.
 */
public enum CompressPreset {
    /**
     * Web optimization: moderate image quality, object stream compression.
     * Good balance between file size and quality.
     */
    WEB(75, 150, true, true, true, false, PreservationMode.VISUALLY_CONSTRAINED),

    /**
     * Screen/email: lower quality, maximum compression.
     */
    SCREEN(60, 96, true, true, true, false, PreservationMode.SCAN),

    /**
     * Print-ready: high quality, structural optimization only.
     */
    PRINT(90, 300, false, true, false, false, PreservationMode.VISUALLY_CONSTRAINED),

    /**
     * Lossless maximum: aggressive structural optimization and stream recompression via qpdf (object/xref streams, FlateDecode recompression), with no image recompression or lossy steps.
     *
     * <p>zopfli recompression is intentionally NOT enabled: measured across a corpus, zopfli on top of the qpdf pass inflated output (131-145% of source versus 86% for qpdf alone) at ~80x the CPU, and every ordering was worse. zopfli remains opt-in via {@link CompressOptions.Builder#useZopfliDeflate(boolean)}.
     */
    LOSSLESS(-1, -1, false, true, false, false, PreservationMode.EXACT),

    /**
     * Maximum compression: aggressive image optimization and structural compression.
     */
    MAXIMUM(50, 96, true, true, true, false, PreservationMode.DESTRUCTIVE);

    private final int imageQuality;
    private final int maxImageDpi;
    private final boolean convertPngToJpeg;
    private final boolean optimizeStreams;
    private final boolean removeMetadata;
    private final boolean useZopfliDeflate;
    private final PreservationMode preservationMode;

    CompressPreset(int imageQuality, int maxImageDpi, boolean convertPngToJpeg,
                   boolean optimizeStreams, boolean removeMetadata, boolean useZopfliDeflate,
                   PreservationMode preservationMode) {
        this.imageQuality = imageQuality;
        this.maxImageDpi = maxImageDpi;
        this.convertPngToJpeg = convertPngToJpeg;
        this.optimizeStreams = optimizeStreams;
        this.removeMetadata = removeMetadata;
        this.useZopfliDeflate = useZopfliDeflate;
        this.preservationMode = preservationMode;
    }

    public int imageQuality() { return imageQuality; }
    public int maxImageDpi() { return maxImageDpi; }
    public boolean convertPngToJpeg() { return convertPngToJpeg; }
    public boolean optimizeStreams() { return optimizeStreams; }
    public boolean removeMetadata() { return removeMetadata; }
    public boolean useZopfliDeflate() { return useZopfliDeflate; }
    public PreservationMode preservationMode() { return preservationMode; }
}

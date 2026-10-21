package stirling.software.jpdfium.vips;

import app.photofox.vipsffm.VBlob;
import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.Vips;
import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsForeignHeifCompression;
import app.photofox.vipsffm.enums.VipsForeignTiffCompression;
import app.photofox.vipsffm.enums.VipsForeignTiffPredictor;
import app.photofox.vipsffm.enums.VipsInterpretation;
import stirling.software.jpdfium.internal.RenderedPageView;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class VipsEncoder {

    private static final int DEFAULT_PNG_COMPRESSION = 6;

    private VipsEncoder() {}

    public static byte[] encodeToBytes(RenderedPageView view, VipsEncodeOptions opts) {
        VipsAvailability.require(opts.format());
        byte[][] result = new byte[1][];
        Vips.run(arena -> {
            VImage image = wrapView(arena, view);
            VBlob blob = encodeToBlob(image, opts);
            result[0] = blob.getBytes();
        });
        return result[0];
    }

    /**
     * Encode RGBA frames as consecutive pages of one TIFF. All frames must
     * have the same dimensions; the TIFF page height is derived from them.
     */
    public static byte[] encodeFramesToBytes(List<byte[]> frames, VipsEncodeOptions opts) {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("At least one frame is required");
        }
        if (opts.format() != VipsFormat.TIFF) {
            throw new IllegalArgumentException("Multi-page encoding requires TIFF");
        }
        int width = readLeInt32(frames.get(0), 0);
        int pageHeight = readLeInt32(frames.get(0), 4);
        if (width <= 0 || pageHeight <= 0) {
            throw new IllegalArgumentException("Invalid frame dimensions " + width + "x" + pageHeight);
        }
        long pagePixels = (long) width * pageHeight;
        long totalPixels = pagePixels * frames.size();
        if (pagePixels * 4L > Integer.MAX_VALUE || totalPixels * 4L > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Multi-page TIFF is too large: " + width + "x" + pageHeight
                    + " x " + frames.size() + " pages");
        }
        int pageBytes = (int) (pagePixels * 4L);
        byte[] stacked = new byte[(int) (totalPixels * 4L)];
        for (int i = 0; i < frames.size(); i++) {
            byte[] frame = frames.get(i);
            if (frame == null || frame.length < 8L + pageBytes) {
                throw new IllegalArgumentException("Frame " + i + " has an invalid payload");
            }
            if (readLeInt32(frame, 0) != width || readLeInt32(frame, 4) != pageHeight) {
                throw new IllegalArgumentException(
                        "All TIFF pages must have the same dimensions; frame " + i + " differs");
            }
            System.arraycopy(frame, 8, stacked, i * pageBytes, pageBytes);
        }
        byte[][] result = new byte[1][];
        Vips.run(arena -> {
            MemorySegment pixels = arena.allocate(stacked.length);
            pixels.copyFrom(MemorySegment.ofArray(stacked));
            VImage image = VImage.newFromMemory(
                    arena, pixels, width, pageHeight * frames.size(), 4, 0);
            image = image.copy(
                    VipsOption.Enum("interpretation", VipsInterpretation.INTERPRETATION_sRGB));
            result[0] = encodeMultiPageToBlob(image, opts, pageHeight).getBytes();
        });
        return result[0];
    }

    public static void encodeToFile(RenderedPageView view, Path output, VipsEncodeOptions opts)
            throws IOException {
        VipsAvailability.require(opts.format());
        Vips.run(arena -> {
            VImage image = wrapView(arena, view);
            writeToFile(image, output.toAbsolutePath().toString(), opts);
        });
    }

    private static VImage wrapView(Arena arena, RenderedPageView view) {
        if (!view.isTight()) {
            throw new IllegalArgumentException(
                    "RenderedPageView stride " + view.stride() + " != width*bands "
                    + (view.width() * view.bands()));
        }
        VImage image = VImage.newFromMemory(
                arena, view.pixels(), view.width(), view.height(), view.bands(), 0);
        if (view.format().name().contains("PREMUL")) {
            image = image.unpremultiply();
        }
        return image.copy(
                VipsOption.Enum("interpretation", VipsInterpretation.INTERPRETATION_sRGB));
    }

    static VBlob encodeToBlob(VImage image, VipsEncodeOptions opts) {
        VipsOption[] options = buildOptions(opts);
        return switch (opts.format()) {
            case HEIC, HEIF, AVIF -> image.heifsaveBuffer(options);
            case JXL -> image.jxlsaveBuffer(options);
            case WEBP -> image.webpsaveBuffer(options);
            case PNG -> image.pngsaveBuffer(options);
            case JPEG -> image.jpegsaveBuffer(options);
            case TIFF -> image.tiffsaveBuffer(options);
            case JPEG2000 -> image.jp2ksaveBuffer(options);
        };
    }

    private static VBlob encodeMultiPageToBlob(
            VImage image, VipsEncodeOptions opts, int pageHeight) {
        VipsOption[] base = buildOptions(opts);
        VipsOption[] options = Arrays.copyOf(base, base.length + 1);
        options[base.length] = VipsOption.Int("page-height", pageHeight);
        return image.tiffsaveBuffer(options);
    }

    private static int readLeInt32(byte[] buf, int off) {
        return (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8)
                | ((buf[off + 2] & 0xFF) << 16) | ((buf[off + 3] & 0xFF) << 24);
    }

    private static void writeToFile(VImage image, String path, VipsEncodeOptions opts) {
        VipsOption[] options = buildOptions(opts);
        switch (opts.format()) {
            case HEIC, HEIF, AVIF -> image.heifsave(path, options);
            case JXL -> image.jxlsave(path, options);
            case WEBP -> image.webpsave(path, options);
            case PNG -> image.pngsave(path, options);
            case JPEG -> image.jpegsave(path, options);
            case TIFF -> image.tiffsave(path, options);
            case JPEG2000 -> image.jp2ksave(path, options);
        }
    }

    static VipsOption[] buildOptions(VipsEncodeOptions opts) {
        return switch (opts.format()) {
            case HEIC, HEIF, AVIF -> buildHeifOptions(opts);
            case JXL -> buildJxlOptions(opts);
            case WEBP -> buildWebpOptions(opts);
            case PNG -> buildPngOptions(opts);
            case JPEG -> buildJpegOptions(opts);
            case TIFF -> buildTiffOptions(opts);
            case JPEG2000 -> buildJpeg2000Options(opts);
        };
    }

    private static VipsOption[] buildHeifOptions(VipsEncodeOptions opts) {
        List<VipsOption> list = new ArrayList<>();
        list.add(VipsOption.Int("Q", opts.quality()));
        if (opts.lossless()) list.add(VipsOption.Boolean("lossless", true));
        list.add(VipsOption.Int("bitdepth", opts.bitdepth()));
        list.add(VipsOption.Int("effort", opts.effort()));
        VipsForeignHeifCompression codec = switch (opts.format()) {
            case AVIF -> VipsForeignHeifCompression.FOREIGN_HEIF_COMPRESSION_AV1;
            case HEIC, HEIF -> VipsForeignHeifCompression.FOREIGN_HEIF_COMPRESSION_HEVC;
            default -> null;
        };
        if (codec != null) list.add(VipsOption.Enum("compression", codec));
        return list.toArray(VipsOption[]::new);
    }

    private static VipsOption[] buildJxlOptions(VipsEncodeOptions opts) {
        List<VipsOption> list = new ArrayList<>();
        list.add(VipsOption.Int("Q", opts.quality()));
        if (opts.lossless()) list.add(VipsOption.Boolean("lossless", true));
        list.add(VipsOption.Int("effort", opts.effort()));
        return list.toArray(VipsOption[]::new);
    }

    private static VipsOption[] buildWebpOptions(VipsEncodeOptions opts) {
        List<VipsOption> list = new ArrayList<>();
        list.add(VipsOption.Int("Q", opts.quality()));
        if (opts.lossless()) list.add(VipsOption.Boolean("lossless", true));
        list.add(VipsOption.Int("effort", opts.effort()));
        list.add(VipsOption.Boolean("strip", true));
        return list.toArray(VipsOption[]::new);
    }

    private static VipsOption[] buildPngOptions(VipsEncodeOptions opts) {
        List<VipsOption> list = new ArrayList<>();
        list.add(VipsOption.Int("compression", DEFAULT_PNG_COMPRESSION));
        list.add(VipsOption.Int("effort", opts.effort()));
        list.add(VipsOption.Boolean("strip", true));
        return list.toArray(VipsOption[]::new);
    }

    private static VipsOption[] buildJpegOptions(VipsEncodeOptions opts) {
        return new VipsOption[]{
            VipsOption.Int("Q", opts.quality()),
            VipsOption.Boolean("strip", true),
            VipsOption.Boolean("optimize_coding", true)
        };
    }

    private static VipsOption[] buildTiffOptions(VipsEncodeOptions opts) {
        List<VipsOption> list = new ArrayList<>();
        list.add(VipsOption.Int("Q", opts.quality()));
        if (opts.lossless()) list.add(VipsOption.Boolean("lossless", true));
        list.add(VipsOption.Enum("compression", VipsForeignTiffCompression.FOREIGN_TIFF_COMPRESSION_DEFLATE));
        list.add(VipsOption.Enum("predictor", VipsForeignTiffPredictor.FOREIGN_TIFF_PREDICTOR_HORIZONTAL));
        list.add(VipsOption.Boolean("strip", true));
        return list.toArray(VipsOption[]::new);
    }

    private static VipsOption[] buildJpeg2000Options(VipsEncodeOptions opts) {
        return new VipsOption[]{VipsOption.Int("Q", opts.quality())};
    }
}

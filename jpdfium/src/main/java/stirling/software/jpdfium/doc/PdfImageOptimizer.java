package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.ImageObjBindings;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.PageEditBindings;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * In-process image downsampling for PDF compression. Images above
 * {@code maxImageDpi} are resampled from their native pixels and re-embedded
 * via {@code FPDFImageObj_SetBitmap}; no external binary is used.
 */
public final class PdfImageOptimizer {

    private PdfImageOptimizer() {}

    private static final int FPDF_PAGEOBJ_IMAGE = 3;
    private static final int FPDF_BITMAP_GRAY = 1;
    private static final int FPDF_BITMAP_BGR = 2;
    private static final int FPDF_BITMAP_BGRx = 3;
    private static final int FPDF_BITMAP_BGRA = 4;

    // Hard cap on the decoded pixel count. The int[] pixel buffers and the
    // byte[] decode buffers are both live at once, so the int-max bound alone
    // still permits several GB of heap for an untrusted PDF. This keeps peak
    // memory per image bounded (100M px * 4 B ~= 400 MB).
    private static final long MAX_PIXELS = 100_000_000L;

    private static final int CS_DEVICE_GRAY = 1;
    private static final int CS_DEVICE_RGB = 2;
    private static final int CS_DEVICE_CMYK = 3;

    // FPDF_IMAGEOBJ_METADATA: width, height, hdpi, vdpi, bpp, colorspace, marked_content_id
    private static final int META_BYTES = 32;

    /** Source pixels decoded at the image's native resolution. */
    private record ImageSource(BufferedImage image, int width, int height) {}

    /**
     * True when the native image-edit bindings required for optimization exist.
     */
    public static boolean isSupported() {
        return PageEditBindings.FPDFImageObj_SetBitmap != null
                && PageEditBindings.FPDFBitmap_CreateEx != null
                && ImageObjBindings.FPDFImageObj_GetBitmap != null;
    }

    /**
     * Downsample images above {@code maxImageDpi}. Images at or below the
     * threshold are left byte-for-byte untouched, preserving quality and
     * avoiding unnecessary decode/encode work. Lossy re-encoding without
     * downsampling is not performed.
     *
     * @param doc         document to modify in place
     * @param maxImageDpi images above this effective DPI are downsampled;
     *                    {@code <= 0} disables the pass
     * @return number of image objects rewritten
     */
    public static int optimize(PdfDocument doc, int maxImageDpi) {
        if (!isSupported() || maxImageDpi <= 0) return 0;
        MemorySegment rawDoc = doc.rawHandle();
        int rewritten = 0;

        for (int p = 0; p < doc.pageCount(); p++) {
            try (PdfPage page = doc.page(p)) {
                MemorySegment rawPage = page.rawHandle();
                int objCount;
                try {
                    objCount = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                    continue;
                }

                boolean pageChanged = false;
                for (int i = 0; i < objCount; i++) {
                    MemorySegment obj;
                    try {
                        obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
                    } catch (Throwable t) {
                        NativeRuntime.rethrowFatal(t);
                        continue;
                    }
                    if (obj.equals(MemorySegment.NULL)) continue;

                    int type;
                    try {
                        type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj);
                    } catch (Throwable t) {
                        NativeRuntime.rethrowFatal(t);
                        continue;
                    }
                    if (type != FPDF_PAGEOBJ_IMAGE) continue;

                    if (optimizeImage(rawDoc, rawPage, obj, maxImageDpi)) {
                        pageChanged = true;
                        rewritten++;
                    }
                }

                if (pageChanged) {
                    int ok;
                    try {
                        ok = (int) PageEditBindings.FPDFPage_GenerateContent.invokeExact(rawPage);
                    } catch (Throwable t) {
                        throw new JPDFiumException("FPDFPage_GenerateContent failed", t);
                    }
                    if (ok == 0) throw new JPDFiumException("FPDFPage_GenerateContent failed");
                }
            }
        }
        return rewritten;
    }

    // Returns true when the image object was rewritten.
    private static boolean optimizeImage(MemorySegment rawDoc, MemorySegment rawPage,
                                         MemorySegment imgObj, int maxImageDpi) {
        float dpi = imageDpi(imgObj, rawPage);
        if (dpi <= 0f || dpi <= maxImageDpi) return false;

        ImageSource src = readSource(rawDoc, rawPage, imgObj);
        if (src == null || src.width() <= 0 || src.height() <= 0) return false;

        double scale = (double) maxImageDpi / dpi;
        int targetW = Math.max(1, (int) Math.round(src.width() * scale));
        int targetH = Math.max(1, (int) Math.round(src.height() * scale));
        if (targetW >= src.width() || targetH >= src.height()) return false;

        return embedBitmap(imgObj, scale(src.image(), targetW, targetH));
    }

    private static float imageDpi(MemorySegment imgObj, MemorySegment rawPage) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment meta = arena.allocate(META_BYTES);
            int ok = (int) ImageObjBindings.FPDFImageObj_GetImageMetadata.invokeExact(
                    imgObj, rawPage, meta);
            if (ok == 0) return -1f;
            float hdpi = meta.get(JAVA_FLOAT, 8);
            float vdpi = meta.get(JAVA_FLOAT, 12);
            // Use the larger axis: an image with one axis above the threshold
            // must still be downsampled even if the other axis is below it.
            float dpi = Math.max(hdpi, vdpi);
            return dpi > 0f ? dpi : -1f;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return -1f;
        }
    }

    // Native-resolution pixels, in preference order: PDFium's own bitmap, the
    // decoded colour data, then a page rasterization as a last resort.
    private static ImageSource readSource(MemorySegment rawDoc, MemorySegment rawPage,
                                          MemorySegment imgObj) {
        try {
            MemorySegment own = (MemorySegment) ImageObjBindings.FPDFImageObj_GetBitmap.invokeExact(imgObj);
            if (!own.equals(MemorySegment.NULL)) {
                ImageSource s = bitmapToSource(own);
                if (s != null) return s;
            }
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
        }

        ImageSource decoded = decodedSource(imgObj, rawPage);
        if (decoded != null) return decoded;

        try {
            MemorySegment bmp = (MemorySegment) ImageObjBindings.FPDFImageObj_GetRenderedBitmap
                    .invokeExact(rawDoc, rawPage, imgObj);
            if (bmp.equals(MemorySegment.NULL)) return null;
            try {
                return bitmapToSource(bmp);
            } finally {
                try {
                    PageEditBindings.FPDFBitmap_Destroy.invokeExact(bmp);
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                }
            }
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return null;
        }
    }

    private static ImageSource decodedSource(MemorySegment imgObj, MemorySegment rawPage) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment meta = arena.allocate(META_BYTES);
            if ((int) ImageObjBindings.FPDFImageObj_GetImageMetadata.invokeExact(
                    imgObj, rawPage, meta) == 0) {
                return null;
            }
            int width = meta.get(JAVA_INT, 0);
            int height = meta.get(JAVA_INT, 4);
            int bpp = meta.get(JAVA_INT, 16);
            int cs = meta.get(JAVA_INT, 20);
            int channels = switch (cs) {
                case CS_DEVICE_GRAY -> 1;
                case CS_DEVICE_RGB -> 3;
                case CS_DEVICE_CMYK -> 4;
                default -> 0;
            };
            // bits_per_pixel is the total per pixel; when it does not match the
            // colour space (1/4-bit gray, 16-bit components) the layout is not
            // one we can unpack unambiguously, so skip rather than mis-decode.
            if (width <= 0 || height <= 0 || channels <= 0 || (bpp > 0 && bpp != channels * 8)) {
                return null;
            }
            if ((long) width * height > MAX_PIXELS) return null;
            int pixels = width * height;

            long expected = (long) pixels * channels;
            long size = (long) ImageObjBindings.FPDFImageObj_GetImageDataDecoded
                    .invokeExact(imgObj, MemorySegment.NULL, 0L);
            if (size < expected) return null;

            MemorySegment buf = arena.allocate(size);
            long written = (long) ImageObjBindings.FPDFImageObj_GetImageDataDecoded
                    .invokeExact(imgObj, buf, size);
            if (written < expected) return null;
            byte[] data = buf.asSlice(0, (long) pixels * channels).toArray(JAVA_BYTE);

            int[] px = new int[pixels];
            int o = 0;
            for (int i = 0; i < px.length; i++) {
                int r;
                int g;
                int b;
                if (channels == 1) {
                    r = g = b = data[o] & 0xFF;
                } else if (channels == 3) {
                    r = data[o] & 0xFF;
                    g = data[o + 1] & 0xFF;
                    b = data[o + 2] & 0xFF;
                } else {
                    int c = data[o] & 0xFF;
                    int m = data[o + 1] & 0xFF;
                    int y = data[o + 2] & 0xFF;
                    int k = data[o + 3] & 0xFF;
                    r = 255 - Math.min(255, c + k);
                    g = 255 - Math.min(255, m + k);
                    b = 255 - Math.min(255, y + k);
                }
                px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                o += channels;
            }
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, width, height, px, 0, width);
            return new ImageSource(img, width, height);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return null;
        }
    }

    private static ImageSource bitmapToSource(MemorySegment bitmap) throws Throwable {
        int width = (int) PageEditBindings.FPDFBitmap_GetWidth.invokeExact(bitmap);
        int height = (int) PageEditBindings.FPDFBitmap_GetHeight.invokeExact(bitmap);
        if (width <= 0 || height <= 0) return null;

        int stride = (int) PageEditBindings.FPDFBitmap_GetStride.invokeExact(bitmap);
        int fmt = (int) PageEditBindings.FPDFBitmap_GetFormat.invokeExact(bitmap);
        MemorySegment buf = (MemorySegment) PageEditBindings.FPDFBitmap_GetBuffer.invokeExact(bitmap);
        if (buf.equals(MemorySegment.NULL)) return null;

        // Validate the layouts before any row copy: FPDFBitmap_GetBuffer can
        // return a zero-length segment, and an inconsistent stride would push
        // MemorySegment.copy past the buffer (IndexOutOfBoundsException).
        int bytesPerPixel = fmt == FPDF_BITMAP_GRAY ? 1
                : fmt == FPDF_BITMAP_BGR ? 3
                : (fmt == FPDF_BITMAP_BGRx || fmt == FPDF_BITMAP_BGRA) ? 4 : 0;
        if (bytesPerPixel == 0 || stride <= 0 || stride < (long) width * bytesPerPixel
                || (long) stride * height > Integer.MAX_VALUE
                || (long) width * height > MAX_PIXELS) {
            return null;
        }
        int pixels = width * height;
        buf = buf.reinterpret((long) stride * height);

        // 4-byte BGRx/BGRA: on little-endian the native [B,G,R,X] bytes already
        // form the ARGB int expected by TYPE_INT_ARGB, so copy rows directly
        // instead of running a per-pixel repack (no pixels are reinterpreted).
        if (fmt == FPDF_BITMAP_BGRx || fmt == FPDF_BITMAP_BGRA) {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            int rowStride = ((SinglePixelPackedSampleModel) img.getRaster().getSampleModel()).getScanlineStride();
            MemorySegment dst = MemorySegment.ofArray(px);
            for (int y = 0; y < height; y++) {
                MemorySegment.copy(buf, (long) y * stride, dst, (long) y * rowStride * 4, (long) width * 4);
            }
            return new ImageSource(img, width, height);
        }

        // Grayscale: 1 byte per pixel, copied per row into TYPE_BYTE_GRAY.
        if (fmt == FPDF_BITMAP_GRAY) {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            byte[] px = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
            int rowStride = ((ComponentSampleModel) img.getRaster().getSampleModel()).getScanlineStride();
            MemorySegment dst = MemorySegment.ofArray(px);
            for (int y = 0; y < height; y++) {
                MemorySegment.copy(buf, (long) y * stride, dst, (long) y * rowStride, width);
            }
            return new ImageSource(img, width, height);
        }

        // 3-byte packed BGR: the only format left after the BGRx/BGRA and
        // grayscale branches returned above, so unpack each pixel in place.
        byte[] raw = buf.toArray(JAVA_BYTE);
        int[] px = new int[pixels];
        int idx = 0;
        for (int y = 0; y < height; y++) {
            int o = y * stride;
            for (int x = 0; x < width; x++) {
                int b = raw[o] & 0xFF;
                int g = raw[o + 1] & 0xFF;
                int r = raw[o + 2] & 0xFF;
                px[idx] = 0xFF000000 | (r << 16) | (g << 8) | b;
                idx++;
                o += 3;
            }
        }
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] raster = img.getRaster().getDataBuffer() instanceof DataBufferInt dbi
                ? dbi.getData() : null;
        if (raster != null && raster.length >= px.length) {
            System.arraycopy(px, 0, raster, 0, px.length);
        } else {
            img.setRGB(0, 0, width, height, px, 0, width);
        }
        return new ImageSource(img, width, height);
    }

    private static BufferedImage scale(BufferedImage src, int width, int height) {
        int type = src.getType();
        if (type == BufferedImage.TYPE_BYTE_GRAY || type == BufferedImage.TYPE_CUSTOM) {
            type = BufferedImage.TYPE_INT_RGB;
        }
        BufferedImage out = new BufferedImage(width, height, type);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static boolean embedBitmap(MemorySegment imgObj,
                                       BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();

        MemorySegment bitmap = createBitmap(width, height, FPDF_BITMAP_BGRx);
        if (bitmap.equals(MemorySegment.NULL)) return false;

        try {
            int fmt = (int) PageEditBindings.FPDFBitmap_GetFormat.invokeExact(bitmap);
            int stride = (int) PageEditBindings.FPDFBitmap_GetStride.invokeExact(bitmap);
            // Mirror the bitmapToSource guard: FPDFBitmap_GetBuffer can return a
            // zero-length segment, and an inconsistent stride would push the row
            // copies past the buffer (IndexOutOfBoundsException).
            MemorySegment bufferSeg =
                    (MemorySegment) PageEditBindings.FPDFBitmap_GetBuffer.invokeExact(bitmap);
            int bpp = fmt == FPDF_BITMAP_BGR ? 3
                    : (fmt == FPDF_BITMAP_BGRx || fmt == FPDF_BITMAP_BGRA) ? 4 : 0;
            if (bufferSeg.equals(MemorySegment.NULL) || bpp == 0 || stride < (long) width * bpp
                    || (long) stride * height > Integer.MAX_VALUE
                    || (long) width * height > MAX_PIXELS) {
                return false;
            }
            int pixels = width * height;
            MemorySegment buf = bufferSeg.reinterpret((long) stride * height);

            // Fast path: an int-backed raster whose scanline stride equals the
            // width. On little-endian an ARGB int is byte-identical to a BGRx
            // pixel, so rows can be copied directly instead of unpacked per pixel.
            if (bpp == 4 && img.getType() != BufferedImage.TYPE_CUSTOM
                    && img.getRaster().getDataBuffer() instanceof DataBufferInt dbi
                    && img.getRaster().getSampleModel() instanceof SinglePixelPackedSampleModel sp
                    && sp.getScanlineStride() == width
                    && dbi.getData().length >= pixels) {
                int[] px = dbi.getData();
                int n = pixels;
                // Preserve the previous behaviour of an opaque pad byte (x = 0xFF).
                for (int i = 0; i < n; i++) px[i] |= 0xFF000000;
                MemorySegment src = MemorySegment.ofArray(px);
                for (int y = 0; y < height; y++) {
                    MemorySegment.copy(src, (long) y * width * 4, buf, (long) y * stride, (long) width * 4);
                }
                int ok = (int) PageEditBindings.FPDFImageObj_SetBitmap.invokeExact(
                        MemorySegment.NULL, 0, imgObj, bitmap);
                return ok != 0;
            }

            // Fallback: per-pixel unpack (3-byte BGR output or non-int raster).
            int[] px;
            if (img.getType() != BufferedImage.TYPE_CUSTOM
                    && img.getRaster().getDataBuffer() instanceof DataBufferInt dbi
                    && dbi.getData().length >= pixels) {
                px = dbi.getData();
            } else {
                px = img.getRGB(0, 0, width, height, null, 0, width);
            }
            byte[] raw = new byte[stride * height];
            int idx = 0;
            for (int y = 0; y < height; y++) {
                int o = y * stride;
                for (int x = 0; x < width; x++) {
                    int argb = px[idx];
                    idx++;
                    raw[o] = (byte) (argb & 0xFF);
                    raw[o + 1] = (byte) ((argb >> 8) & 0xFF);
                    raw[o + 2] = (byte) ((argb >> 16) & 0xFF);
                    if (bpp == 4) raw[o + 3] = (byte) 0xFF;
                    o += bpp;
                }
            }
            MemorySegment.copy(MemorySegment.ofArray(raw), 0, buf, 0, raw.length);

            int ok = (int) PageEditBindings.FPDFImageObj_SetBitmap.invokeExact(
                    MemorySegment.NULL, 0, imgObj, bitmap);
            return ok != 0;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return false;
        } finally {
            try {
                PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
            }
        }
    }

    private static MemorySegment createBitmap(int width, int height, int format) {
        try {
            return (MemorySegment) PageEditBindings.FPDFBitmap_CreateEx.invokeExact(
                    width, height, format, MemorySegment.NULL, 0);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return MemorySegment.NULL;
        }
    }
}

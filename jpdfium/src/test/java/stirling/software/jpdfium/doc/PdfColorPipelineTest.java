package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.ColorBindings;
import stirling.software.jpdfium.panama.ImageObjBindings;
import stirling.software.jpdfium.panama.PageEditBindings;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** End-to-end coverage for the DeviceCMYK colour pipeline:. */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfColorPipelineTest {

    private static final int PDF_COLORSPACE_DEVICECMYK = 3;
    private static final int PAGEOBJ_IMAGE = 3;
    private static final long META_COLORSPACE_OFFSET = ImageObjBindings.IMAGE_METADATA_LAYOUT
            .byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("colorspace"));

    private static Path resource(String path) throws Exception {
        URL url = PdfColorPipelineTest.class.getResource(path);
        assertNotNull(url, path + " test resource missing");
        return Path.of(url.toURI());
    }

    private static Path temp(String suffix) throws Exception {
        Path p = Files.createTempFile("jpdfium-color-", suffix);
        p.toFile().deleteOnExit();
        return p;
    }

    /** A header-only blob used to exercise the malformed-profile rejection path. */
    private static byte[] syntheticCmykProfile() {
        byte[] icc = new byte[132];
        icc[0] = 0;
        icc[1] = 0;
        icc[2] = 0;
        icc[3] = (byte) icc.length;
        System.arraycopy("CMYK".getBytes(StandardCharsets.US_ASCII), 0, icc, 16, 4);
        System.arraycopy("acsp".getBytes(StandardCharsets.US_ASCII), 0, icc, 36, 4);
        return icc;
    }

    /**
     * Minimal PDF with one form XObject containing a DeviceCMYK fill and a
     * DeviceRGB fill. Exercises form recursion, CMYK preservation and dirty
     * stream regeneration.
     */
    private static byte[] cmykFormPdf() throws Exception {
        byte[] formContent = ("0.1 0.2 0.3 0.4 k\n"
                + "10 10 80 80 re f\n"
                + "1 0 0 rg\n"
                + "100 10 80 80 re f\n").getBytes(StandardCharsets.US_ASCII);
        byte[] pageContent = "q\n/Fm0 Do\nQ\n".getBytes(StandardCharsets.US_ASCII);
        List<byte[]> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes(StandardCharsets.US_ASCII));
        objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>".getBytes(StandardCharsets.US_ASCII));
        objects.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] "
                + "/Resources << /XObject << /Fm0 4 0 R >> >> /Contents 5 0 R >>")
                .getBytes(StandardCharsets.US_ASCII));
        objects.add(("<< /Type /XObject /Subtype /Form /BBox [0 0 200 200] "
                + "/Resources << >> /Length " + formContent.length + " >>\nstream\n"
                + new String(formContent, StandardCharsets.US_ASCII) + "endstream")
                .getBytes(StandardCharsets.US_ASCII));
        objects.add(("<< /Length " + pageContent.length + " >>\nstream\n"
                + new String(pageContent, StandardCharsets.US_ASCII) + "endstream")
                .getBytes(StandardCharsets.US_ASCII));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        int[] offsets = new int[objects.size() + 1];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i + 1] = out.size();
            out.write((i + 1 + " 0 obj\n").getBytes(StandardCharsets.US_ASCII));
            out.write(objects.get(i));
            out.write("\nendobj\n".getBytes(StandardCharsets.US_ASCII));
        }
        int xref = out.size();
        out.write(("xref\n0 " + (objects.size() + 1) + "\n").getBytes(StandardCharsets.US_ASCII));
        out.write("0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII));
        for (int i = 1; i < offsets.length; i++) {
            out.write((String.format("%010d", offsets[i]) + " 00000 n \n")
                    .getBytes(StandardCharsets.US_ASCII));
        }
        out.write(("trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n"
                + xref + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    /** First real CMYK profile on this machine, if any (used for the JDK ICC path). */
    private static Optional<byte[]> findCmykProfile() {
        String explicit = System.getProperty("jpdfium.test.cmykIcc");
        String[] candidates = {
            explicit,
            "/System/Library/ColorSync/Profiles/Generic CMYK Profile.icc",
            "/usr/share/color/icc/ghostscript/default_cmyk.icc",
            "/usr/share/color/icc/ISOcoated_v2_300_eci.icc",
            "/usr/share/color/icc/colord/ISOcoated_v2_300_eci.icc",
            "/usr/share/color/icc/colord/SwappedRedAndGreen.icc",
        };
        for (String candidate : candidates) {
            if (candidate == null) continue;
            try {
                Path p = Path.of(candidate);
                if (!Files.isReadable(p)) continue;
                byte[] bytes = Files.readAllBytes(p);
                // Colord ships RGB profiles too; only accept a real 4-component
                // profile so the test cannot fail on a valid non-CMYK file.
                if (ICC_Profile.getInstance(bytes).getNumComponents() == 4) {
                    return Optional.of(bytes);
                }
            } catch (Exception _) {
                // try the next candidate
            }
        }
        return Optional.empty();
    }

    /** Metadata colourspace of the first image object on page 0, or -1 when absent. */
    private static int firstImageColorspace(PdfDocument doc) {
        try (PdfPage page = doc.page(0);
                Arena arena = Arena.ofConfined()) {
            MemorySegment rawPage = page.rawHandleForRead();
            int count = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
            MemorySegment meta = arena.allocate(ImageObjBindings.IMAGE_METADATA_LAYOUT);
            for (int i = 0; i < count; i++) {
                MemorySegment obj =
                        (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
                if (obj.equals(MemorySegment.NULL)) continue;
                int type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj);
                if (type != PAGEOBJ_IMAGE) continue;
                int ok = (int)
                        ImageObjBindings.FPDFImageObj_GetImageMetadata.invokeExact(obj, rawPage, meta);
                if (ok != 0) {
                    return meta.get(JAVA_INT, META_COLORSPACE_OFFSET);
                }
            }
        } catch (Throwable t) {
            throw new AssertionError("image metadata probe failed", t);
        }
        return -1;
    }

    @Test
    void capabilityFlagsMatchAvailabilityProbes() {
        int caps = ColorBindings.capabilities();
        assertEquals(0, caps & ~(ColorBindings.CAP_CMYK_VECTOR
                        | ColorBindings.CAP_CMYK_IMAGE
                        | ColorBindings.CAP_OUTPUT_INTENT),
                "unknown capability bits must not be set: 0x" + Integer.toHexString(caps));
        assertEquals(ColorBindings.has(ColorBindings.CAP_CMYK_VECTOR),
                ColorBindings.vectorCmykAvailable());
        assertEquals(ColorBindings.has(ColorBindings.CAP_CMYK_IMAGE),
                ColorBindings.imageConversionAvailable());
        assertEquals(ColorBindings.has(ColorBindings.CAP_OUTPUT_INTENT),
                ColorBindings.outputIntentAvailable());
        assertEquals(ColorBindings.vectorCmykAvailable()
                        && ColorBindings.colorSpaceAvailable()
                        && ColorBindings.iccConversionAvailable(),
                PdfColorConverter.supportsColorSpace(PdfColorConverter.ColorSpace.CMYK));
    }

    @Test
    void cmykJpegEncoderProducesValidJpeg() {
        assumeTrue(ColorBindings.imageConversionAvailable(),
                "CMYK JPEG encoder unavailable (Rust not built)");

        byte[] rgb = new byte[4 * 4 * 3];
        for (int i = 0; i < rgb.length; i += 3) {
            rgb[i] = (byte) 200;
            rgb[i + 1] = (byte) 40;
            rgb[i + 2] = (byte) 10;
        }
        byte[] jpeg = ColorBindings.samplesToCmykJpeg(rgb, 4, 4, 3, 90, 0);
        assertNotNull(jpeg, "CMYK JPEG encoding must succeed");
        assertTrue(jpeg.length > 4, "encoded JPEG must not be empty");
        assertEquals((byte) 0xFF, jpeg[0]);
        assertEquals((byte) 0xD8, jpeg[1]);
        assertEquals((byte) 0xFF, jpeg[jpeg.length - 2]);
        assertEquals((byte) 0xD9, jpeg[jpeg.length - 1]);
    }

    @Test
    void convertImagesToCmykRewritesEmbeddedImages() throws Exception {
        assumeTrue(PdfColorConverter.supportsImageConversion(),
                "CMYK image conversion unavailable");

        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(200, 40, 10));
            g.fillRect(0, 0, 32, 32);
        } finally {
            g.dispose();
        }

        Path out = temp(".pdf");
        int converted;
        int pages;
        try (PdfDocument doc = PdfDocument.fromImage(img)) {
            pages = doc.pageCount();
            converted = PdfColorConverter.convertImagesToCmyk(doc, 90);
            doc.save(out);
        }
        assertTrue(converted >= 1,
                "image PDF must convert at least one image to DeviceCMYK, got " + converted);

        try (PdfDocument reopened = PdfDocument.open(out)) {
            assertEquals(pages, reopened.pageCount(), "document must remain valid");
            assertEquals(PDF_COLORSPACE_DEVICECMYK, firstImageColorspace(reopened),
                    "converted image XObject must be DeviceCMYK");
        }
    }

    @Test
    void cmykFormConversionPreservesCmykAndConvertsRgb() throws Throwable {
        assumeTrue(PdfColorConverter.supportsColorSpace(PdfColorConverter.ColorSpace.CMYK),
                "vector CMYK unavailable");
        assumeTrue(PdfColorConverter.supportsFormConversion(),
                "form conversion requires the JPDFium PDFium patch");

        Path out = temp(".pdf");
        try (PdfDocument doc = PdfDocument.open(cmykFormPdf())) {
            assertEquals(1, PdfColorConverter.toCmyk(doc),
                    "the RGB child inside the form must be converted, the CMYK child skipped");
            doc.save(out);
        }

        try (PdfDocument doc = PdfDocument.open(out);
                PdfPage page = doc.page(0)) {
            MemorySegment rawPage = page.rawHandleForRead();
            MemorySegment form = (MemorySegment) PageEditBindings.FPDFPage_GetObject
                    .invokeExact(rawPage, 0);
            assertNotNull(form, "form object must survive save");
            int count = (int) PageEditBindings.FPDFFormObj_CountObjects.invokeExact(form);
            assertEquals(2, count);

            int[] cmyk = new int[5];
            MemorySegment preserved = (MemorySegment) PageEditBindings.FPDFFormObj_GetObject
                    .invokeExact(form, 0L);
            assertTrue(ColorBindings.getFillColorCmyk(preserved, cmyk),
                    "original DeviceCMYK fill must still be DeviceCMYK");
            assertEquals(26, cmyk[0]);
            assertEquals(51, cmyk[1]);
            assertEquals(77, cmyk[2]);
            assertEquals(102, cmyk[3]);

            MemorySegment converted = (MemorySegment) PageEditBindings.FPDFFormObj_GetObject
                    .invokeExact(form, 1L);
            assertTrue(ColorBindings.getFillColorCmyk(converted, cmyk),
                    "RGB fill inside the form must become DeviceCMYK");
            assertEquals(0, cmyk[0]);
            assertEquals(255, cmyk[1]);
            assertEquals(255, cmyk[2]);
            assertEquals(0, cmyk[3]);
        }
    }

    @Test
    void outputIntentInjectsCatalogEntry() throws Exception {
        assumeTrue(PdfOutputIntent.isSupported(), "OutputIntent support unavailable");
        byte[] icc = findCmykProfile().orElse(null);
        assumeTrue(icc != null, "no real CMYK ICC profile available on this machine");

        byte[] pdf = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        int expectedPages;
        try (PdfDocument source = PdfDocument.open(pdf)) {
            expectedPages = source.pageCount();
        }
        byte[] out = PdfOutputIntent.apply(pdf, icc,
                PdfOutputIntent.Spec.pdfx("JPDFium Test CMYK", "JPDFium pipeline test"));
        assertNotNull(out, "OutputIntent injection must produce bytes");

        String text = new String(out, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("/OutputIntents"), "catalog must gain /OutputIntents");
        assertTrue(text.contains("/GTS_PDFX"), "output intent must carry the PDF/X subtype");
        assertTrue(text.contains("/DestOutputProfile"), "output intent must embed the ICC stream");

        try (PdfDocument doc = PdfDocument.open(out)) {
            assertEquals(expectedPages, doc.pageCount(),
                    "document with output intent must still open with every page intact");
        }
    }

    @Test
    void recolorModesRewriteVectorColors() throws Exception {
        Path src = resource("/pdfs/general/minimal.pdf");
        int expectedPages;
        try (PdfDocument source = PdfDocument.open(src)) {
            expectedPages = source.pageCount();
        }

        try (PdfDocument doc = PdfDocument.open(src)) {
            assertTrue(PdfRecolor.invert(doc) > 0,
                    "invert must rewrite at least one fill/stroke");
        }
        try (PdfDocument doc = PdfDocument.open(src)) {
            assertTrue(PdfRecolor.tint(doc, 255, 0, 0, 100) > 0,
                    "a full-strength tint must rewrite at least one fill/stroke");
        }
        try (PdfDocument doc = PdfDocument.open(src)) {
            assertTrue(PdfRecolor.highContrast(doc, 2.0) > 0,
                    "high contrast must rewrite at least one fill/stroke");
        }
        try (PdfDocument doc = PdfDocument.open(src)) {
            // This fixture carries no colour operators, so every glyph is already black.
            assertEquals(0, PdfRecolor.binarize(doc, 128));
        }

        Path out = temp(".pdf");
        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfRecolor.tint(doc, 0, 0, 255, 60);
            doc.save(out);
        }
        try (PdfDocument reopened = PdfDocument.open(out)) {
            assertEquals(expectedPages, reopened.pageCount(),
                    "recoloured document must remain valid with every page intact");
        }
    }

    @Test
    void iccCmykTransformMapsRgbToInkLevels() {
        byte[] profile = findCmykProfile().orElse(null);
        assumeTrue(profile != null, "no CMYK ICC profile available on this machine");

        IccCmykTransform transform = new IccCmykTransform(profile);
        int[] out = new int[4];

        transform.toCmyk(255, 255, 255, out);
        assertTrue(out[3] <= 32,
                "white must carry (almost) no black ink, K=" + out[3]);

        transform.toCmyk(0, 0, 0, out);
        assertTrue(out[3] >= 200,
                "black must be dominated by black ink, K=" + out[3]);

        for (int component : out) {
            assertTrue(component >= 0 && component <= 255,
                    "ink levels must be 8-bit: " + component);
        }
    }

    @Test
    void iccSourceProfileRoundTripsThroughTargetProfile() {
        byte[] profile = findCmykProfile().orElse(null);
        assumeTrue(profile != null, "no CMYK ICC profile available on this machine");

        IccCmykTransform transform = new IccCmykTransform(profile);
        int[] out = new int[4];
        transform.toCmykFromIcc(profile, new float[] {0f, 0f, 0f, 1f}, out);
        assertTrue(out[3] >= 120, "100% K source must keep substantial black ink: " + out[3]);

        IccCmykTransform.sourceToCmyk(profile, new float[] {0f, 0f, 0f, 0f}, true, out);
        assertTrue(out[3] <= 32, "white source must carry little black: " + out[3]);
    }

    @Test
    void iccTransformRejectsNonCmykProfile() {
        byte[] srgb = java.awt.color.ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
        JPDFiumException ex = assertThrows(JPDFiumException.class,
                () -> new IccCmykTransform(srgb));
        assertTrue(ex.getMessage().contains("CMYK"), ex.getMessage());
    }

    @Test
    void outputIntentRejectsProfileWithoutAcspSignature() throws Exception {
        assumeTrue(PdfOutputIntent.isSupported(), "OutputIntent support unavailable");

        byte[] pdf = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        byte[] malformed = syntheticCmykProfile();
        malformed[36] = 0;  // destroy the mandatory "acsp" signature
        assertThrows(JPDFiumException.class,
                () -> PdfOutputIntent.apply(pdf, malformed, PdfOutputIntent.Spec.pdfx("x", "y")));
    }

    @Test
    void outputIntentRejectsProfileWithoutTagTable() throws Exception {
        assumeTrue(PdfOutputIntent.isSupported(), "OutputIntent support unavailable");

        byte[] pdf = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        byte[] headerOnly = syntheticCmykProfile();
        assertThrows(JPDFiumException.class,
                () -> PdfOutputIntent.apply(pdf, headerOnly, PdfOutputIntent.Spec.pdfx("x", "y")));
    }
}

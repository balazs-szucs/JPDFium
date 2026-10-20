package stirling.software.jpdfium.doc;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.IOException;
import java.nio.file.Path;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;

/** PDF/X bootstrap: CMYK conversion, GTS_PDFX output intent and XMP metadata. */
public final class PdfXConverter {

    private PdfXConverter() {}

    /** True when the loaded native library can run the implemented pipeline. */
    public static boolean isSupported() {
        return PdfOutputIntent.pdfxSupported()
                && PdfColorConverter.supportsColorSpace(PdfColorConverter.ColorSpace.CMYK);
    }

    /** True when {@code standard} is wired into this initial implementation. */
    public static boolean supports(PdfXStandard standard) {
        return standard != null && standard.implemented();
    }

    /** Convert colours in place and return a document with PDF/X intent metadata. */
    public static PdfDocument convert(PdfDocument doc, PdfXOptions options) {
        if (doc == null) throw new JPDFiumException("PDF/X conversion requires a document");
        if (options == null) throw new JPDFiumException("PDF/X conversion requires options");
        PdfXStandard standard = options.standard();
        if (!supports(standard)) {
            throw new JPDFiumException("PDF/X conversion is not implemented for " + standard
                    + " (supported: PDF/X-3 and PDF/X-4)");
        }
        if (!isSupported()) {
            throw new JPDFiumException("PDF/X conversion requires vector CMYK support,"
                    + " source colour-space probes and the QPDF PDF/X metadata bootstrap");
        }

        byte[] profile = options.outputIntentProfile();
        if (profile == null || profile.length == 0) {
            throw new JPDFiumException("PDF/X conversion requires an output intent ICC profile");
        }
        validateCmykProfile(profile);

        if (options.convertColors()) {
            PdfColorConverter.convert(doc, options.colorOptions());
        }
        byte[] pdf = doc.saveBytes();
        byte[] converted = PdfOutputIntent.applyPdfx(pdf, profile,
                PdfOutputIntent.Spec.pdfx(options.conditionIdentifier(), options.info()),
                standard.gtsVersion(), standard.gtsConformance());
        return PdfDocument.open(converted);
    }

    /** Convert a PDF file to another file. The input document is not modified. */
    public static void convert(Path input, Path output, PdfXOptions options) throws IOException {
        if (input == null || output == null) {
            throw new IllegalArgumentException("input and output paths are required");
        }
        try (PdfDocument source = PdfDocument.open(input);
                PdfDocument converted = convert(source, options)) {
            converted.save(output);
        }
    }

    /** Convert PDF bytes and return the new PDF bytes. */
    public static byte[] convert(byte[] input, PdfXOptions options) {
        if (input == null || input.length == 0) {
            throw new JPDFiumException("PDF/X conversion requires non-empty PDF bytes");
        }
        try (PdfDocument source = PdfDocument.open(input);
                PdfDocument converted = convert(source, options)) {
            return converted.saveBytes();
        }
    }

    /** Check the intent/ICC prerequisites; this is not a full PDF/X validator. */
    public static void validate(byte[] pdf, PdfXOptions options) {
        if (pdf == null || pdf.length == 0) {
            throw new JPDFiumException("PDF/X validation requires non-empty PDF bytes");
        }
        if (options == null || options.outputIntentProfile() == null) {
            throw new JPDFiumException("PDF/X validation requires the output intent profile");
        }
        validateCmykProfile(options.outputIntentProfile());
        String text = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!text.contains("/OutputIntents") || !text.contains("/GTS_PDFX")) {
            throw new JPDFiumException("PDF/X validation: missing GTS_PDFX output intent");
        }
        if (!text.contains("GTS_PDFXVersion")
                || !text.contains(options.standard().gtsVersion())) {
            throw new JPDFiumException("PDF/X validation: missing or mismatched"
                    + " GTS_PDFXVersion metadata");
        }
    }

    private static void validateCmykProfile(byte[] profile) {
        try {
            ICC_Profile icc = ICC_Profile.getInstance(profile);
            if (icc.getNumComponents() != 4
                    || icc.getColorSpaceType() != ColorSpace.TYPE_CMYK) {
                throw new JPDFiumException("PDF/X output intent must be a 4-component"
                        + " CMYK ICC profile");
            }
        } catch (IllegalArgumentException e) {
            throw new JPDFiumException("Invalid PDF/X output intent ICC profile: "
                    + e.getMessage(), e);
        }
    }
}

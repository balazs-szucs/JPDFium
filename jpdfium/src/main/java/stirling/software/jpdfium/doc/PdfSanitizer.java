package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.SaveOptions;
import stirling.software.jpdfium.panama.OutputTransaction;
import stirling.software.jpdfium.panama.QpdfLib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * In-process qpdf structural sanitization (FFM, no CLI).
 *
 * <p>Thin wrapper over {@link QpdfLib} for the structural scrubbing Stirling-PDF used to do via the qpdf CLI: metadata/info/structure stripping, JavaScript/action removal, embedded-file removal, AcroForm removal, and annotation flattening. This is <b>not</b> visual redaction: run the pdfium side first, then this pass to clean up structural copies redaction leaves behind.
 */
public final class PdfSanitizer {

    public static final int METADATA = 0x01;     // drop /Metadata from catalog
    public static final int INFO = 0x02;         // drop /Info trailer dict
    public static final int STRUCTURE = 0x04;     // drop /StructTreeRoot (tagged PDF)
    public static final int JAVASCRIPT = 0x08;    // drop /OpenAction, /AA, /Names/JavaScript
    public static final int ATTACHMENTS = 0x10;   // drop embedded files
    public static final int ACROFORM = 0x20;      // drop /AcroForm + widget annotations
    public static final int FLATTEN = 0x40;       // flatten annotations

    private PdfSanitizer() {}

    public static byte[] sanitize(byte[] input, int flags) {
        return QpdfLib.sanitize(input, flags);
    }

    public static byte[] sanitize(Path input, int flags) throws IOException {
        return sanitize(Files.readAllBytes(input), flags);
    }

    public static void sanitize(Path input, Path output, int flags) throws IOException {
        sanitize(input, output, flags, 0);
    }

    public static void sanitize(Path input, Path output, int flags, long maxBytes) throws IOException {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must be >= 0 (0 = unlimited), got " + maxBytes);
        }
        if (QpdfLib.sanitizeToFile(input, output, flags, maxBytes)) {
            return;
        }
        byte[] result = sanitize(input, flags);
        if (result == null) {
            throw new IOException("qpdf sanitization produced no output");
        }
        // Single transaction for staging, size validation, permissions, and atomic
        // publish; the previous createTempFile+publishStaged split kept default temp permissions and skipped the budget check here.
        try (OutputTransaction tx = OutputTransaction.begin(output)) {
            Files.write(tx.staging(), result);
            tx.publish(maxBytes <= 0 ? null : SaveOptions.maxOutputBytes(maxBytes));
        }
    }

    /** Check if in-process QPDF sanitization is available. */
    public static boolean isSupported() {
        return QpdfLib.isSanitizeSupported();
    }
}

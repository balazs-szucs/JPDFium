package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.model.FlattenMode;
import stirling.software.jpdfium.transform.PageOps;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlattenApiTest {

    private static final Path MINIMAL_PDF = Path.of("src/test/resources/pdfs/general/minimal.pdf");

    @Test
    void pageFlattenDefault() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF);
             PdfPage page = doc.page(0)) {
            assertDoesNotThrow(() -> page.flatten());
        }
    }

    @Test
    void pageFlattenWithMode() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF);
             PdfPage page = doc.page(0)) {
            assertDoesNotThrow(() -> page.flatten(FlattenMode.ANNOTATIONS));
        }
    }

    @Test
    void pageFlattenFullRasterize(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("full-flatten.pdf");
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF);
             PdfPage page = doc.page(0)) {
            assertDoesNotThrow(() -> page.flatten(FlattenMode.FULL, 100));
            assertTrue(page.isClosed());
            assertThrows(IllegalStateException.class, page::size);
            doc.save(out);
            try (PdfPage reopened = doc.page(0)) {
                assertFalse(reopened.isClosed());
                assertTrue(reopened.size().width() > 0);
            }
        }
        assertTrue(Files.exists(out));
        assertTrue(Files.size(out) > 0);
    }

    @Test
    void docFlattenNoArgs() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            assertDoesNotThrow(() -> doc.flatten());
        }
    }

    @Test
    void docFlattenPageMethods() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            assertDoesNotThrow(() -> doc.flattenPage(0));
            assertDoesNotThrow(() -> doc.flattenPage(0, FlattenMode.ANNOTATIONS));
            assertDoesNotThrow(() -> doc.flattenPage(0, FlattenMode.FULL, 100));
        }
    }

    @Test
    void docFlattenWithMode() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            assertDoesNotThrow(() -> doc.flatten(FlattenMode.ANNOTATIONS));
            assertDoesNotThrow(() -> doc.flatten(FlattenMode.FULL, 100));
        }
    }

    @Test
    void docFlattenWithDpi() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            assertDoesNotThrow(() -> doc.flatten(120));
            assertDoesNotThrow(() -> doc.flattenPage(0, 120));
        }
    }

    @Test
    void pageOpsFlattenMethods() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            assertDoesNotThrow(() -> PageOps.flatten(doc, 0));
            assertDoesNotThrow(() -> PageOps.flatten(doc, 0, 120));
            assertDoesNotThrow(() -> PageOps.flatten(doc, 0, FlattenMode.ANNOTATIONS));
            assertDoesNotThrow(() -> PageOps.flatten(doc, 0, FlattenMode.FULL, 100));
            assertDoesNotThrow(() -> PageOps.flattenAll(doc));
            assertDoesNotThrow(() -> PageOps.flattenAll(doc, 120));
            assertDoesNotThrow(() -> PageOps.flattenAll(doc, FlattenMode.ANNOTATIONS));
            assertDoesNotThrow(() -> PageOps.flattenAll(doc, FlattenMode.FULL, 100));
        }
    }

    @Test
    void fullFlattenStripsFormsOutlinesAndAttachments() {
        byte[] out72;
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            doc.flatten(FlattenMode.FULL, 72);
            out72 = doc.saveBytes();
        }
        String raw = new String(out72, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("/AcroForm"), "Flattened PDF must not contain /AcroForm");
        try (PdfDocument reopened = PdfDocument.open(out72)) {
            assertTrue(reopened.bookmarks().isEmpty(), "Flattened PDF must have no bookmarks");
            assertTrue(reopened.attachments().isEmpty(), "Flattened PDF must have no attachments");
        }
    }

    @Test
    void fullFlattenUsesJpegAndScalesWithDpi() {
        byte[] out72;
        byte[] out150;
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            doc.flatten(FlattenMode.FULL, 72);
            out72 = doc.saveBytes();
        }
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            doc.flatten(FlattenMode.FULL, 150);
            out150 = doc.saveBytes();
        }
        String raw72 = new String(out72, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(raw72.contains("/DCTDecode"), "Flattened PDF should embed JPEG (/DCTDecode)");
        assertTrue(out72.length <= out150.length,
                "72 DPI (" + out72.length + " B) should not exceed 150 DPI (" + out150.length + " B)");
    }
}

package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/** End-to-end coverage for {@link PdfColorAdjuster} against the native renderer. */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfColorAdjusterIntegrationTest {

    private static BufferedImage sampleImage() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 64, 64);
        g.setColor(new Color(0x40, 0x40, 0x40));
        g.fillRect(0, 0, 32, 64);
        g.dispose();
        return image;
    }

    @Test
    void rebuildsEveryPageAsAnImageOnlyPdf() {
        try (PdfDocument source = PdfDocument.fromImage(sampleImage())) {
            byte[] identity;
            try (PdfDocument adjusted =
                    PdfColorAdjuster.adjust(source, PdfColorAdjuster.Adjustment.identity())) {
                assertEquals(1, adjusted.pageCount());
                identity = adjusted.saveBytes();
            }
            assertTrue(identity.length > 0);

            try (PdfDocument adjusted = PdfColorAdjuster.adjust(
                    source, PdfColorAdjuster.Adjustment.fromPercent(200, 100, 100, 100, 100, 100))) {
                assertEquals(1, adjusted.pageCount());
                byte[] stronger = adjusted.saveBytes();
                assertTrue(stronger.length > 0);
                assertNotEquals(
                        Arrays.hashCode(identity),
                        Arrays.hashCode(stronger),
                        "a strong contrast change should alter the rendered output");
            }
        }
    }

    @Test
    void rejectsDocumentsWithoutPages() {
        PdfDocument empty;
        try {
            empty = PdfDocument.fromImages(List.of());
        } catch (RuntimeException e) {
            abort("fromImages does not build a page-less document: " + e);
            return;
        }
        try (PdfDocument document = empty) {
            if (document.pageCount() > 0) {
                abort("fromImages(List.of()) unexpectedly produced pages");
                return;
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PdfColorAdjuster.adjust(document, PdfColorAdjuster.Adjustment.identity()));
        }
    }
}

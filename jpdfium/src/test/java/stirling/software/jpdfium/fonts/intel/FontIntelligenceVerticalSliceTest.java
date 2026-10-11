package stirling.software.jpdfium.fonts.intel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.fonts.PinnedFonts;
import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The first vertical slice from the plan, end to end and with an independent
 * oracle:
 *
 * <pre>
 * known-clean embedded-font PDF (authored + verified with PDFBox)
 *   → deterministically break /ToUnicode
 *   → recover evidence-backed Unicode with JPDFium
 *   → apply the ToUnicode repair
 *   → reopen and verify the authored text extracts again
 * </pre>
 *
 * <p>PDFBox is used only to author the fixture and to verify the result — never
 * to produce the expected mapping, so the recovery algorithm is not grading
 * itself.
 */
class FontIntelligenceVerticalSliceTest {

    private static final String SAMPLE = "Hello World";

    private static Path locateFont() {
        return PinnedFonts.primary();
    }

    /** Author a clean document that embeds the font as an Identity-H CID font. */
    private static byte[] cleanPdf(Path font) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDFont embedded = PDType0Font.load(doc, Files.newInputStream(font), false);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(embedded, 24);
                cs.newLineAtOffset(72, 700);
                cs.showText(SAMPLE);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Remove the declared /ToUnicode so recovery must reconstruct it. */
    private static byte[] breakToUnicode(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                var resources = page.getResources();
                for (COSName name : resources.getFontNames()) {
                    PDFont font = resources.getFont(name);
                    font.getCOSObject().removeItem(COSName.TO_UNICODE);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static String extractWithPdfBox(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc).trim();
        }
    }

    /** Confirms the break at the document level, independent of any extractor. */
    private static void assertNoToUnicode(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            boolean sawFont = false;
            for (PDPage page : doc.getPages()) {
                for (COSName name : page.getResources().getFontNames()) {
                    sawFont = true;
                    assertFalse(
                            page.getResources().getFont(name).getCOSObject()
                                    .containsKey(COSName.TO_UNICODE),
                            "the break removed /ToUnicode");
                }
            }
            assertTrue(sawFont, "the broken page still declares its font resource");
        }
    }

    @Test
    void brokenToUnicodeIsRecoveredAndRepairedVertically(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        Path font = locateFont();

        byte[] clean = cleanPdf(font);
        assertEquals(SAMPLE, extractWithPdfBox(clean),
                "clean document must extract the authored text (ground truth)");

        byte[] broken = breakToUnicode(clean);
        assertNoToUnicode(broken);

        try (PdfDocument doc = PdfDocument.open(broken)) {
            List<FontEvidence> fonts = FontIntelligence.inspectPage(doc, 0);
            assertFalse(fonts.isEmpty(), "the broken page must still expose its font");
            FontEvidence target = fonts.get(0);

            FontCapability capability = FontIntelligence.capability(target);
            assertFalse(target.toUnicodeValid(), "the break is detected as invalid /ToUnicode");
            assertEquals(FontCapability.MAPPING_REPAIR_AVAILABLE, capability,
                    "an embedded CID font must be recoverable from its cmap: " + target);

            List<FontRepairPlan> plans = FontIntelligence.repairPlans(target);
            assertEquals(1, plans.size(), "one repair plan for the missing /ToUnicode");
            assertEquals(1, FontIntelligence.applyRepair(doc, plans.get(0)));

            for (FontEvidence after : FontIntelligence.inspectPage(doc, 0)) {
                assertTrue(after.toUnicodeValid(), "repaired /ToUnicode must be structurally valid");
            }

            byte[] repaired = doc.saveBytes();
            assertEquals(SAMPLE, extractWithPdfBox(repaired),
                    "authored text must extract again after repair");
        }
    }
}

package stirling.software.jpdfium.fonts.intel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.fonts.PinnedFonts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole-document font pass must produce exactly what the per-page pass
 * produces while doing less work: one document view, one analysis per distinct
 * program, one write and reload. These tests pin that equivalence so an
 * optimisation cannot silently change the result, and pin the resource identity
 * so two fonts that merely share a name and a program size are not collapsed.
 */
class FontAnalysisReuseTest {

    @Test
    void inspectPagesEqualsPerPageInspection() throws Exception {
        Path font = PinnedFonts.primary();
        byte[] pdf = multiPagePdf(font, 3);

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<FontEvidence> combined = FontIntelligence.inspectPages(doc);
            List<FontEvidence> perPage = new ArrayList<>();
            for (int p = 0; p < doc.pageCount(); p++) {
                perPage.addAll(FontIntelligence.inspectPage(doc, p));
            }
            assertEquals(perPage, combined,
                    "one pages entry must equal per-page inspection, in page order");
            assertEquals(3, combined.size(), "one font resource on each of three pages");
        }
    }

    @Test
    void repairToUnicodeEqualsPerPageRepair() throws Exception {
        Path font = PinnedFonts.primary();
        byte[] pdf = multiPagePdf(font, 3);

        int oneView;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            oneView = FontIntelligence.repairToUnicode(doc);
            assertTrue(oneView >= 3, "every page's font should be repaired, got " + oneView);
            for (FontEvidence e : FontIntelligence.inspectPages(doc)) {
                assertTrue(e.toUnicodeValid(),
                        "repair must leave a valid map: " + e.resourceName());
            }
        }

        int perPage = 0;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            for (int p = 0; p < doc.pageCount(); p++) {
                perPage += FontIntelligence.repairPage(doc, p);
            }
        }

        assertEquals(perPage, oneView,
                "one pages write must repair exactly what the per-page loop repairs");
    }

    @Test
    void applyRepairsAcrossPagesWritesEveryAuthorisedPage() throws Exception {
        Path font = PinnedFonts.primary();
        byte[] pdf = multiPagePdf(font, 3);

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<FontRepairPlan> plans = FontIntelligence.repairPlans(doc);
            assertEquals(3, plans.size(), "one plan per page");

            int fixed = FontIntelligence.applyRepairs(doc, plans);
            assertTrue(fixed >= 3, "every authorised page should be repaired, got " + fixed);
            for (FontEvidence e : FontIntelligence.inspectPages(doc)) {
                assertTrue(e.toUnicodeValid(),
                        "a plan must repair every authorised page: " + e.resourceName());
            }
        }
    }

    @Test
    void equalNameAndSizeWithDifferentMappingsAreNotMerged() {
        List<UnicodeMapping> a =
                List.of(new UnicodeMapping(65, 65, FontProvenance.RECOVERED, "first"));
        List<UnicodeMapping> b =
                List.of(new UnicodeMapping(65, 66, FontProvenance.RECOVERED, "second"));
        FontEvidence first = evidence("Base", 2048L, 500, "WinAnsiEncoding", a);
        FontEvidence second = evidence("Base", 2048L, 500, "WinAnsiEncoding", b);

        assertEquals(2, FontCatalog.of(List.of(first, second)).distinct().size(),
                "an equal name and program size must not merge when the meaning differs");
    }

    @Test
    void equalProgramWithDifferentEncodingsStayIndependent() {
        List<UnicodeMapping> m =
                List.of(new UnicodeMapping(65, 65, FontProvenance.RECOVERED, "shared"));
        FontEvidence win = evidence("Base", 2048L, 500, "WinAnsiEncoding", m);
        FontEvidence mac = evidence("Base", 2048L, 500, "MacRomanEncoding", m);

        assertEquals(2, FontCatalog.of(List.of(win, mac)).distinct().size(),
                "one program referenced with two encodings must stay two resources");
    }

    @Test
    void identicalEvidenceMergesToASingleFont() {
        List<UnicodeMapping> m =
                List.of(new UnicodeMapping(65, 65, FontProvenance.RECOVERED, "shared"));
        FontEvidence first = evidence("Base", 2048L, 500, "WinAnsiEncoding", m);
        FontEvidence second = evidence("Base", 2048L, 500, "WinAnsiEncoding", m);

        assertEquals(1, FontCatalog.of(List.of(first, second)).distinct().size(),
                "evidence that agrees on program and meaning denotes one font");
    }

    private static FontEvidence evidence(
            String base, long bytes, int glyphs, String encoding, List<UnicodeMapping> mappings) {
        return new FontEvidence(
                0, "F1", base, "", "TrueType", FontKind.SIMPLE,
                true, true, false, false, false, true,
                glyphs, 1000, bytes, false, false,
                encoding, -1, mappings, false);
    }

    /** {@code pages} single-font pages, every {@code /ToUnicode} removed. */
    private static byte[] multiPagePdf(Path font, int pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                PDType0Font f;
                try (InputStream in = Files.newInputStream(font)) {
                    f = PDType0Font.load(doc, in, false);
                }
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(f, 24);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Hello World");
                    cs.endText();
                }
            }
            stripToUnicode(doc);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void stripToUnicode(PDDocument doc) {
        for (PDPage page : doc.getPages()) {
            PDResources res = page.getResources();
            if (res == null) {
                continue;
            }
            COSDictionary fonts = res.getCOSObject().getCOSDictionary(COSName.FONT);
            if (fonts == null) {
                continue;
            }
            for (COSName key : new ArrayList<>(fonts.keySet())) {
                COSBase fd = fonts.getDictionaryObject(key);
                if (fd instanceof COSDictionary dict) {
                    dict.removeItem(COSName.TO_UNICODE);
                }
            }
        }
    }
}

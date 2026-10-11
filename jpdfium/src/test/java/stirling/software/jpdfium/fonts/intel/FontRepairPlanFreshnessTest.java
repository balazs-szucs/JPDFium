package stirling.software.jpdfium.fonts.intel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.fonts.PinnedFonts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A repair plan is state-bound: it authorises exactly the change set it was
 * prepared against, and it discloses every reference a shared font object is
 * reached by. A plan whose document moved, or that leaves a shared reference
 * unauthorised, must be refused before any bytes are written.
 */
class FontRepairPlanFreshnessTest {

    private static final String HEADER =
            "/CIDInit /ProcSet findresource begin\n"
            + "12 dict begin\n"
            + "begincmap\n"
            + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
            + "/CMapName /test-UCS def\n"
            + "/CMapType 2 def\n"
            + "1 begincodespacerange\n"
            + "<00> <FF>\n"
            + "endcodespacerange\n";

    private static final String FOOTER =
            "endcmap\n"
            + "CMapName currentdict /CMap defineresource pop\n"
            + "end\n"
            + "end\n";

    @Test
    void aChangedDeclaredMappingInvalidatesAPreparedPlan() throws Exception {
        byte[] original = singleFontPdf("1 beginbfchar\n<41> <0041>\nendbfchar\n");
        byte[] mutated = singleFontPdf("1 beginbfchar\n<41> <0042>\nendbfchar\n");

        FontRepairPlan plan;
        try (PdfDocument doc = PdfDocument.open(original)) {
            List<FontRepairPlan> plans = FontIntelligence.repairPlans(doc, 0);
            assertFalse(plans.isEmpty(), "the missing used code should produce a plan");
            plan = plans.get(0);
            assertFalse(plan.nativeIdentity().isEmpty(),
                    "a plan prepared from the native plan must carry its identity");
        }

        try (PdfDocument doc = PdfDocument.open(mutated)) {
            StaleRepairPlanException thrown = assertThrows(StaleRepairPlanException.class,
                    () -> FontIntelligence.applyRepair(doc, plan),
                    "a plan against a changed declared map must be rejected before mutation");
            assertTrue(thrown.getMessage() != null);
            String before = toUnicodeText(mutated, 0);
            String after = toUnicodeText(doc.saveBytes(), 0);
            assertEquals(before, after,
                    "a rejected plan must not have altered the declared map");
        }
    }

    @Test
    void aSharedFontObjectMustBeAuthorisedForEveryReference() throws Exception {
        byte[] pdf = twoPagesSharingOneFont();

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<FontRepairPlan> page0 = FontIntelligence.repairPlans(doc, 0);
            FontRepairPlan planF1 = page0.stream()
                    .filter(p -> p.resourceName().equals("/F1"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no plan for /F1: " + page0));
            assertTrue(planF1.affectedReferences().contains(FontRepairPlan.referenceKey(0, "/F1")),
                    planF1.affectedReferences().toString());
            assertTrue(planF1.affectedReferences().contains(FontRepairPlan.referenceKey(1, "/F9")),
                    "the shared reference on page 1 must be disclosed: "
                            + planF1.affectedReferences());

            assertThrows(StaleRepairPlanException.class,
                    () -> FontIntelligence.applyRepair(doc, planF1),
                    "authorising one reference of a shared font must be refused");

            int fixed = FontIntelligence.applyRepairs(doc, FontIntelligence.repairPlans(doc));
            assertTrue(fixed >= 1, "the whole-document plan, covering both references, applies");

            String cmap = toUnicodeText(doc.saveBytes(), 0);
            assertTrue(cmap != null && cmap.contains("begincmap"),
                    "the shared font object must carry a repaired /ToUnicode");
        }
    }

    // ------------------------------------------------------------- fixtures

    /** One page showing "AB" with a simple TrueType font and a declared map. */
    private static byte[] singleFontPdf(String cmap) throws Exception {
        Path font = PinnedFonts.primary();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDTrueTypeFont f = loadFont(doc, font);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(f, 24);
                cs.newLineAtOffset(72, 700);
                cs.showText("AB");
                cs.endText();
            }
            installToUnicode(doc, cmap);
            return save(doc);
        }
    }

    /**
     * Two pages that reference ONE font dictionary under different names: page 0
     * as {@code /F1}, page 1 as {@code /F9}. The font has no /ToUnicode, so both
     * pages are eligible for the same object-wide repair.
     */
    private static byte[] twoPagesSharingOneFont() throws Exception {
        Path font = PinnedFonts.primary();
        try (PDDocument doc = new PDDocument()) {
            PDTrueTypeFont f = loadFont(doc, font);
            f.getCOSObject().removeItem(COSName.TO_UNICODE);
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                PDResources res = new PDResources();
                res.put(COSName.getPDFName(i == 0 ? "F1" : "F9"), f);
                page.setResources(res);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(f, 24);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("AB");
                    cs.endText();
                }
            }
            return save(doc);
        }
    }

    private static PDTrueTypeFont loadFont(PDDocument doc, Path font) throws Exception {
        try (InputStream in = Files.newInputStream(font)) {
            return PDTrueTypeFont.load(doc, in, WinAnsiEncoding.INSTANCE);
        }
    }

    private static byte[] save(PDDocument doc) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    private static void installToUnicode(PDDocument doc, String cmap) throws Exception {
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
                    COSStream stream = doc.getDocument().createCOSStream();
                    try (OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
                        os.write((HEADER + cmap + FOOTER).getBytes(StandardCharsets.ISO_8859_1));
                    }
                    dict.setItem(COSName.TO_UNICODE, stream);
                }
            }
        }
    }

    /** The first font's declared /ToUnicode stream text on a given page. */
    private static String toUnicodeText(byte[] pdf, int pageIndex) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDPage page = doc.getPages().get(pageIndex);
            PDResources res = page.getResources();
            if (res == null) {
                return null;
            }
            COSDictionary fonts = res.getCOSObject().getCOSDictionary(COSName.FONT);
            if (fonts == null) {
                return null;
            }
            for (COSName key : new ArrayList<>(fonts.keySet())) {
                COSBase fd = fonts.getDictionaryObject(key);
                if (fd instanceof COSDictionary dict
                        && dict.getDictionaryObject(COSName.TO_UNICODE) instanceof COSStream stream) {
                    try (InputStream in = stream.createInputStream()) {
                        return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                    }
                }
            }
            return null;
        } catch (Exception e) {
            throw new AssertionError("could not read the declared /ToUnicode map", e);
        }
    }
}

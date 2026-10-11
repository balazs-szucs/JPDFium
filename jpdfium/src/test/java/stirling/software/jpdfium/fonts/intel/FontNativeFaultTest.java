package stirling.software.jpdfium.fonts.intel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.fonts.PinnedFonts;
import stirling.software.jpdfium.panama.FontLib;
import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Faults at the real native boundary are reported truthfully and never publish
 * bytes.
 *
 * <p>Three independent failure paths are exercised end to end:
 *
 * <ol>
 *   <li>a native plan that marks a page's shared-font scope as unenumerable makes
 *       the Java layer refuse the write and discloses the same condition, so the
 *       refusal is consistent with the document state;
 *   <li>an evidence-only advisory plan (no native identity) cannot authorise a
 *       write, so a caller that predates the native plan cannot mutate anything;
 *   <li>a native non-{@code OK} status (a page index outside the document)
 *       surfaces as a typed boundary exception rather than an empty plan.
 * </ol>
 */
class FontNativeFaultTest {

    @Test
    void unenumerableScopeIsDisclosedNativelyAndRefusesTheWrite() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = repairableButUnenumerableScopePdf();

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            // The native plan itself discloses the incomplete scope ...
            String nativePlan = FontLib.planPages(doc.nativeHandle());
            assertTrue(nativePlan.contains("\"references_complete\":false"),
                    "the native plan must disclose the unenumerable scope: " + nativePlan);

            // ... while the repairable font is still planned ...
            List<FontRepairPlan> plans = FontIntelligence.repairPlans(doc);
            assertFalse(plans.isEmpty(),
                    "an incomplete scope must not hide a font that still needs a repair");

            // ... and the write is refused on exactly that disclosed condition.
            byte[] before = doc.saveBytes();
            StaleRepairPlanException refusal = assertThrows(StaleRepairPlanException.class,
                    () -> FontIntelligence.applyRepairs(doc, plans));
            assertTrue(refusal.getMessage().contains("cannot be enumerated"),
                    "the refusal must name the unenumerable scope: " + refusal.getMessage());

            // A refused write publishes no bytes.
            assertArrayEquals(before, doc.saveBytes(),
                    "a refused write must leave the document byte-for-byte unchanged");
        }
    }

    @Test
    void advisoryEvidenceOnlyPlanCannotAuthoriseAWrite() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = repairablePdf();

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontEvidence source = null;
            for (FontEvidence e : FontIntelligence.inspectPage(doc, 0)) {
                if (FontIntelligence.capability(e) == FontCapability.MAPPING_REPAIR_AVAILABLE) {
                    source = e;
                    break;
                }
            }
            assertNotNull(source, "fixture must expose a repairable font");

            List<FontRepairPlan> advisory = FontIntelligence.repairPlans(source);
            assertFalse(advisory.isEmpty());
            assertTrue(advisory.stream().allMatch(p -> p.nativeIdentity().isEmpty()),
                    "a plan derived from evidence alone carries no native identity");

            byte[] before = doc.saveBytes();
            StaleRepairPlanException refusal = assertThrows(StaleRepairPlanException.class,
                    () -> FontIntelligence.applyRepairs(doc, advisory));
            assertTrue(refusal.getMessage().contains("cannot authorise"),
                    "an advisory plan must be refused, not applied: " + refusal.getMessage());

            assertArrayEquals(before, doc.saveBytes(),
                    "an advisory plan must not publish bytes");
        }
    }

    @Test
    void nativeNonOkStatusSurfacesAsATypedBoundaryException() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = repairablePdf();

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            JPDFiumException high = assertThrows(JPDFiumException.class,
                    () -> FontLib.planJson(doc.nativeHandle(), doc.pageCount()),
                    "a page index past the end must surface as a typed exception");
            assertTrue(high.getMessage().contains("fontPlan"),
                    "the boundary context must be preserved: " + high.getMessage());

            JPDFiumException negative = assertThrows(JPDFiumException.class,
                    () -> FontLib.planJson(doc.nativeHandle(), -1),
                    "a negative page index must surface as a typed exception");
            assertTrue(negative.getMessage().contains("fontPlan"),
                    "the boundary context must be preserved: " + negative.getMessage());
        }
    }

    // ------------------------------------------------------------- fixtures

    /** One page whose /F1 font has no /ToUnicode and shows "AB". */
    private static byte[] repairablePdf() throws Exception {
        Path font = PinnedFonts.primary();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDTrueTypeFont f = loadFont(doc, font);
            f.getCOSObject().removeItem(COSName.TO_UNICODE);
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("F1"), f);
            page.setResources(res);
            setContent(doc, page, "BT /F1 24 Tf 72 700 Td (AB) Tj ET\n");
            return save(doc);
        }
    }

    /**
     * The repairable page above, but with {@code /Resources /XObject} set to a
     * non-dictionary value. The font is still indexed (so it is planned), yet the
     * page's shared-font scope cannot be enumerated, so no object-wide write is
     * authorised.
     */
    private static byte[] repairableButUnenumerableScopePdf() throws Exception {
        Path font = PinnedFonts.primary();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDTrueTypeFont f = loadFont(doc, font);
            f.getCOSObject().removeItem(COSName.TO_UNICODE);
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("F1"), f);
            page.setResources(res);
            res.getCOSObject().setItem(COSName.XOBJECT, COSName.getPDFName("Bogus"));
            setContent(doc, page, "BT /F1 24 Tf 72 700 Td (AB) Tj ET\n");
            return save(doc);
        }
    }

    // ------------------------------------------------------------- plumbing

    private static PDTrueTypeFont loadFont(PDDocument doc, Path font) throws Exception {
        try (InputStream in = Files.newInputStream(font)) {
            return PDTrueTypeFont.load(doc, in, WinAnsiEncoding.INSTANCE);
        }
    }

    private static void setContent(PDDocument doc, PDPage page, String content) throws Exception {
        org.apache.pdfbox.cos.COSStream stream = doc.getDocument().createCOSStream();
        try (OutputStream os = stream.createOutputStream()) {
            os.write(content.getBytes(StandardCharsets.ISO_8859_1));
        }
        page.getCOSObject().setItem(COSName.CONTENTS, stream);
    }

    private static byte[] save(PDDocument doc) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }
}

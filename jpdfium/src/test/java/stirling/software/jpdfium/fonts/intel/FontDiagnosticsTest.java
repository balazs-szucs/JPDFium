package stirling.software.jpdfium.fonts.intel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

import org.apache.pdfbox.cos.COSArray;
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
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.RepairReport;
import stirling.software.jpdfium.doc.RepairResult;
import stirling.software.jpdfium.fonts.PinnedFonts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reason codes explain, in a closed vocabulary, why a document-wide font
 * pass left a resource alone. They are diagnostics, never authorisations: a
 * finding only reports that the planner decided not to touch a resource.
 *
 * <p>Some codes cannot be provoked with the pinned Ahem programs (a font with
 * more than 65535 glyphs or one whose every recovered code is ambiguous); those
 * are covered at the enum and parsing level and wired through the same native
 * path, not by a fixture. This is deliberate: the tests never fabricate a state
 * the shipped code cannot actually reach.
 */
class FontDiagnosticsTest {

    @Test
    void symbolicSimpleFontWithoutEncodingReportsSymbolicUnsupported() throws Exception {
        byte[] pdf = simpleFontPdf(PinnedFonts.primary(), (doc, font) -> {
            font.removeItem(COSName.ENCODING);
            COSDictionary desc = descriptor(font);
            desc.setInt(COSName.FLAGS, desc.getInt(COSName.FLAGS, 0) | 4);
        });
        assertHasDiagnostic(pdf, FontDiagnostic.SYMBOLIC_FONT_UNSUPPORTED);
    }

    @Test
    void unsupportedBuiltinEncodingReportsBuiltinUnsupported() throws Exception {
        byte[] pdf = simpleFontPdf(PinnedFonts.primary(), (doc, font) ->
                font.setItem(COSName.ENCODING, COSName.getPDFName("MacExpertEncoding")));
        assertHasDiagnostic(pdf, FontDiagnostic.BUILTIN_ENCODING_UNSUPPORTED);
    }

    @Test
    void cidFontWithEmbeddedCMapReportsCffCidUnsupported() throws Exception {
        byte[] pdf = cidFontPdf(PinnedFonts.primary(), (doc, font) ->
                font.setItem(COSName.ENCODING, COSName.getPDFName("UniGB-UCS2-H")));
        assertHasDiagnostic(pdf, FontDiagnostic.CFF_CID_MAPPING_UNSUPPORTED);
    }

    @Test
    void declaredMapThatInheritsAnotherCMapReportsDeclaredCmapUnsupported() throws Exception {
        byte[] pdf = simpleFontPdf(PinnedFonts.primary(), (doc, font) -> {
            try {
                font.setItem(COSName.TO_UNICODE, useCmapStream(doc));
            } catch (Exception e) {
                throw new java.io.UncheckedIOException(new java.io.IOException(e));
            }
        });
        assertHasDiagnostic(pdf, FontDiagnostic.DECLARED_CMAP_UNSUPPORTED);
    }

    @Test
    void fontDiagnosticCodesRoundTripAndRejectUnknown() {
        for (FontDiagnostic d : FontDiagnostic.values()) {
            assertEquals(d, FontDiagnostic.fromCode(d.code()).orElseThrow(),
                    "a code must parse back to its constant: " + d);
            assertEquals(d, FontDiagnostic.fromCode(d.code().toLowerCase(Locale.ROOT)).orElseThrow(),
                    "code matching is case-insensitive: " + d);
        }
        assertTrue(FontDiagnostic.fromCode(null).isEmpty(), "null is not a code");
        assertTrue(FontDiagnostic.fromCode("").isEmpty(), "blank is not a code");
        assertTrue(FontDiagnostic.fromCode("   ").isEmpty(), "blank is not a code");
        assertTrue(FontDiagnostic.fromCode("NOT_A_REAL_CODE").isEmpty(), "unknown is not a code");
    }

    @Test
    void everyDocumentedReasonCodeIsDefined() {
        List<String> expected = List.of(
                "BUILTIN_ENCODING_UNSUPPORTED",
                "SYMBOLIC_FONT_UNSUPPORTED",
                "CFF_CID_MAPPING_UNSUPPORTED",
                "DECLARED_CMAP_UNSUPPORTED",
                "ANALYSIS_LIMIT_REACHED",
                "AMBIGUOUS_GLYPH_SEMANTICS",
                "SOURCE_STATE_CHANGED",
                "STRUCTURAL_SALVAGE_REQUIRED");
        assertEquals(expected.size(), FontDiagnostic.values().length,
                "the reason-code vocabulary is exactly the documented set");
        for (String code : expected) {
            assertTrue(FontDiagnostic.fromCode(code).isPresent(), "missing reason code: " + code);
        }
    }

    @Test
    void aRepairedPlanIsStaleAndReportsSourceStateChanged() throws Exception {
        byte[] pdf = simpleFontPdf(PinnedFonts.primary(), (doc, font) ->
                font.removeItem(COSName.TO_UNICODE));

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<FontRepairPlan> plans = FontIntelligence.repairPlans(doc);
            assertFalse(plans.isEmpty(), "a font with no /ToUnicode must be planned");
            FontIntelligence.applyRepairs(doc, plans);

            StaleRepairPlanException stale = assertThrows(StaleRepairPlanException.class,
                    () -> FontIntelligence.applyRepairs(doc, plans),
                    "re-applying a plan whose source state moved on must be refused");
            assertEquals(FontDiagnostic.SOURCE_STATE_CHANGED, stale.diagnostic());
        }
    }

    @Test
    void structuralSalvageReportsTheSalvageReasonCode() {
        RepairReport salvage = RepairReport.strategy(
                RepairResult.Status.PARTIAL, RepairReport.STRATEGY_PDFIO_PAGE_SALVAGE);
        assertTrue(salvage.diagnostics().contains(FontDiagnostic.STRUCTURAL_SALVAGE_REQUIRED),
                "a page-salvage repair must be reported as structural salvage");

        RepairReport rewrite = RepairReport.strategy(
                RepairResult.Status.FIXED, RepairReport.STRATEGY_QPDF_REWRITE);
        assertTrue(rewrite.diagnostics().isEmpty(),
                "a plain rewrite is not a salvage and carries no reason code");
    }

    // ------------------------------------------------------------- fixtures

    private static void assertHasDiagnostic(byte[] pdf, FontDiagnostic expected) throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<FontDiagnosticFinding> findings = FontIntelligence.diagnostics(doc, 0);
            assertTrue(findings.stream().anyMatch(f -> f.diagnostic() == expected),
                    () -> "expected " + expected + " among " + findings);
        }
    }

    /** One page, one simple TrueType font showing text, then {@code tweak}. */
    private static byte[] simpleFontPdf(Path font, BiConsumer<PDDocument, COSDictionary> tweak)
            throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDTrueTypeFont f;
            try (InputStream in = Files.newInputStream(font)) {
                f = PDTrueTypeFont.load(doc, in, WinAnsiEncoding.INSTANCE);
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(f, 24);
                cs.newLineAtOffset(72, 700);
                cs.showText("Hello");
                cs.endText();
            }
            COSDictionary dict = firstFontDict(doc);
            if (tweak != null) {
                tweak.accept(doc, dict);
            }
            return save(doc);
        }
    }

    /** One page, one composite (Type0) font showing text, then {@code tweak}. */
    private static byte[] cidFontPdf(Path font, BiConsumer<PDDocument, COSDictionary> tweak)
            throws Exception {
        try (PDDocument doc = new PDDocument()) {
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
                cs.showText("Hello");
                cs.endText();
            }
            COSDictionary dict = firstFontDict(doc);
            tweak.accept(doc, dict);
            return save(doc);
        }
    }

    private static byte[] save(PDDocument doc) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    private static COSDictionary firstFontDict(PDDocument doc) {
        PDResources res = doc.getPage(0).getResources();
        COSDictionary fonts = res.getCOSObject().getCOSDictionary(COSName.FONT);
        for (COSName key : new ArrayList<>(fonts.keySet())) {
            COSBase v = fonts.getDictionaryObject(key);
            if (v instanceof COSDictionary d) {
                return d;
            }
        }
        throw new AssertionError("page 0 has no font resource");
    }

    private static COSDictionary descriptor(COSDictionary font) {
        COSBase direct = font.getDictionaryObject(COSName.FONT_DESC);
        if (direct instanceof COSDictionary cd) {
            return cd;
        }
        COSBase descendants = font.getDictionaryObject(COSName.DESCENDANT_FONTS);
        if (descendants instanceof COSArray arr
                && arr.size() > 0
                && arr.getObject(0) instanceof COSDictionary desc) {
            COSBase nested = desc.getDictionaryObject(COSName.FONT_DESC);
            if (nested instanceof COSDictionary cd) {
                return cd;
            }
        }
        throw new AssertionError("font has no descriptor");
    }

    /** A /ToUnicode stream whose dictionary inherits another CMap via /UseCMap. */
    private static COSStream useCmapStream(PDDocument doc) throws Exception {
        COSStream stream = doc.getDocument().createCOSStream();
        stream.setItem(COSName.USE_CMAP, COSName.getPDFName("Identity-H"));
        try (OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
            os.write(minimalCmap().getBytes(StandardCharsets.ISO_8859_1));
        }
        return stream;
    }

    private static String minimalCmap() {
        return "/CIDInit /ProcSet findresource begin\n"
                + "12 dict begin\n"
                + "begincmap\n"
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                + "/CMapName /Adobe-Identity-UCS def\n"
                + "/CMapType 2 def\n"
                + "1 begincodespacerange\n<0000> <ffff>\nendcodespacerange\n"
                + "1 beginbfchar\n<0048> <0048>\nendbfchar\n"
                + "endcmap\n"
                + "CMapName currentdict /CMap defineresource pop\n"
                + "end\n"
                + "end\n";
    }
}

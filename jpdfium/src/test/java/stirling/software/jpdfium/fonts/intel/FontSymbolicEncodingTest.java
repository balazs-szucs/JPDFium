package stirling.software.jpdfium.fonts.intel;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A standard-14 symbolic base font ({@code /ZapfDingbats}, {@code /Symbol}) with
 * no {@code /Encoding} and no {@code /FontDescriptor} must be declined, never
 * given a synthesized ASCII identity {@code /ToUnicode}.
 *
 * <p>Regression: {@code pdfjs_ZapfDingbats.pdf}'s dingbat font carries no
 * {@code /Encoding} and no {@code /FontDescriptor}, so the descriptor symbolic
 * flag cannot see it. The repair used to fall through to StandardEncoding and
 * map {@code 0x4B -> U+004B}, replacing the document's dingbat text layer with
 * Latin letters while the pixels stayed identical. The planner now reports
 * {@link FontDiagnostic#SYMBOLIC_FONT_UNSUPPORTED} and withholds that repair,
 * while still repairing the other fonts on the page.
 */
class FontSymbolicEncodingTest {

    private static byte[] fixture(String resource) throws Exception {
        try (InputStream in = FontSymbolicEncodingTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing test resource " + resource);
            return in.readAllBytes();
        }
    }

    @Test
    void standardSymbolicFontIsDeclinedNotRewritten() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = fixture("/pdfs/general/pdfjs_ZapfDingbats.pdf");
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<FontDiagnosticFinding> findings = FontIntelligence.diagnostics(doc);
            FontDiagnosticFinding symbolic = findings.stream()
                    .filter(f -> f.diagnostic() == FontDiagnostic.SYMBOLIC_FONT_UNSUPPORTED)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no SYMBOLIC_FONT_UNSUPPORTED finding; got " + findings));

            List<FontRepairPlan> plans = FontIntelligence.repairPlans(doc);
            assertTrue(plans.stream().noneMatch(p -> p.pageIndex() == symbolic.pageIndex()
                            && p.resourceName().equals(symbolic.resourceName())),
                    "a repair plan was produced for the declined symbolic font "
                            + symbolic.resourceName() + ": " + plans);
        }
    }
}

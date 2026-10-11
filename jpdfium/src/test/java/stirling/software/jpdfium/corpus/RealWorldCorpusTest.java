package stirling.software.jpdfium.corpus;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import stirling.software.jpdfium.PdfCorpus;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfRepair;
import stirling.software.jpdfium.doc.RepairInspection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-world corpora beyond the bundled resources: the Mozilla pdf.js
 * {@code test/pdfs} regression suite, the veraPDF PDF/A conformance corpus, and
 * extra permissively-licensed suites (QPDF regression files, Apache PDFBox test
 * resources, PDF Association PDF 2.0 examples).
 *
 * <p>Run with {@code ./gradlew :jpdfium:corpusTest} (online). Each source must
 * be present and a bounded sample of each must open and inspect without
 * crashing the native layer. The veraPDF corpus deliberately contains
 * malformed and non-conformant files, so opening is allowed to fail; what may
 * never happen is a native crash or a missing inspection verdict.
 */
@Tag("corpus")
class RealWorldCorpusTest {

    /** How many files from each source to open/inspect in this run. */
    private static final int SAMPLE = Integer.getInteger("jpdfium.corpus.sample", 10);

    @Test
    void pdfJsAndVeraPdfFilesArePresentAndInspectable() throws Exception {
        List<Path> all = PdfCorpus.download();

        List<Path> veraPdf = all.stream()
                .filter(p -> p.toString().contains("/verapdf/"))
                .toList();
        List<Path> other = all.stream().filter(RealWorldCorpusTest::isExtraCorpus).toList();
        List<Path> pdfJs = all.stream()
                .filter(p -> !p.toString().contains("/verapdf/"))
                .filter(p -> !isExtraCorpus(p))
                .toList();

        System.out.printf(
                "[RealWorldCorpus] sample=%d pdf.js=%d veraPDF=%d other=%d (of %d total)%n",
                SAMPLE, pdfJs.size(), veraPdf.size(), other.size(), all.size());

        assertFalse(pdfJs.isEmpty(), "expected pdf.js corpus files");
        assertFalse(veraPdf.isEmpty(), "expected veraPDF corpus files");
        assertTrue(pdfJs.size() >= 900,
                "expected the complete pdf.js test/pdfs tree (>=900 files), got "
                        + pdfJs.size() + " - the contents API caps at 1000 entries");
        assertTrue(all.size() >= 200,
                "expected a broad real-world corpus, got " + all.size() + " files");
        assertTrue(veraPdf.stream().anyMatch(p -> p.toString().contains("PDF_A-")),
                "expected veraPDF PDF/A conformance families in the corpus");
        assertFalse(other.isEmpty(),
                "expected extra GitHub corpora (qpdf/pdfbox/pdf20examples) in the corpus");

        for (Path p : sample(pdfJs)) {
            assertInspectable(p);
        }
        for (Path p : sample(veraPdf)) {
            assertInspectable(p);
        }
        for (Path p : sample(other)) {
            assertInspectable(p);
        }
    }

    /** Files from the extra GitHub corpora (QPDF regression, PDFBox, PDF 2.0). */
    private static boolean isExtraCorpus(Path p) {
        String s = p.toString();
        return s.contains("/qpdf-qtest/") || s.contains("/pdfbox-test/")
                || s.contains("/pdf20examples/");
    }

    private static List<Path> sample(List<Path> in) {
        return in.stream().sorted().limit(SAMPLE).toList();
    }

    private static void assertInspectable(Path p) throws Exception {
        // The native inspection must always return a typed verdict, never throw.
        String json = PdfRepair.inspect(Files.readAllBytes(p));
        assertNotNull(json, p + ": null inspection output");
        RepairInspection inspection = RepairInspection.parse(json);
        assertNotNull(inspection.status(), p + ": inspection returned no status");
        assertFalse(inspection.status().isBlank(), p + ": blank inspection status");

        // Opening may legitimately fail for deliberately-malformed files.
        try (PdfDocument doc = PdfDocument.open(p)) {
            assertTrue(doc.pageCount() >= 0, p + ": negative page count");
        } catch (Exception acceptable) {
            // A conformance corpus contains files that are meant to be rejected.
        }
    }
}

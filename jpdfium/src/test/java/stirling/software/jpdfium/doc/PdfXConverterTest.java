package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.color.ICC_Profile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Bootstrap contract for {@link PdfXConverter}; not a PDF/X conformance suite. */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfXConverterTest {

    @Test
    void pdfx4WritesIntentAndMetadataAndPreservesPages() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] icc = findCmykProfile().orElse(null);
        assumeTrue(icc != null, "no CMYK ICC profile available on this machine");

        byte[] input = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        int pages;
        try (PdfDocument doc = PdfDocument.open(input)) {
            pages = doc.pageCount();
        }

        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .outputIntentProfile(icc)
                .conditionIdentifier("FOGRA39")
                .info("Coated FOGRA39")
                .build();
        byte[] out = PdfXConverter.convert(input, options);
        String text = new String(out, StandardCharsets.ISO_8859_1);

        assertTrue(text.contains("/OutputIntents"), "output intent missing");
        assertTrue(text.contains("/GTS_PDFX"), "PDF/X output intent subtype missing");
        assertTrue(text.contains("GTS_PDFXVersion"), "XMP version missing");
        assertTrue(text.contains("PDF/X-4"), "XMP version is not PDF/X-4");
        assertTrue(text.contains("GTS_PDFXConformance"), "XMP conformance missing");
        assertTrue(text.contains("/Metadata"), "XMP metadata stream missing");
        PdfXConverter.validate(out, options);

        try (PDDocument pdfbox = Loader.loadPDF(out)) {
            PDMetadata metadata = pdfbox.getDocumentCatalog().getMetadata();
            assertTrue(metadata != null, "PDFBox must see a catalog metadata stream");
            String xmp = new String(metadata.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(xmp.contains("GTS_PDFXVersion"), "XMP version missing in PDFBox");
            assertTrue(xmp.contains("PDF/X-4"), "XMP version is not PDF/X-4 in PDFBox");
            assertTrue(pdfbox.getDocumentCatalog().getOutputIntents() != null
                            && !pdfbox.getDocumentCatalog().getOutputIntents().isEmpty(),
                    "PDFBox must see the output intent");
        }

        try (PdfDocument reopened = PdfDocument.open(out)) {
            assertEquals(pages, reopened.pageCount(), "page count changed");
        }
    }

    @Test
    void pdfx3UsesItsOwnGtsNames() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] icc = findCmykProfile().orElse(null);
        assumeTrue(icc != null, "no CMYK ICC profile available on this machine");

        byte[] input = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_3)
                .outputIntentProfile(icc)
                .conditionIdentifier("FOGRA39")
                .build();
        byte[] out = PdfXConverter.convert(input, options);
        String text = new String(out, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("PDF/X-3:2002"), "PDF/X-3 GTS version missing");
        assertTrue(text.contains("GTS_PDFXConformance"), "PDF/X-3 conformance missing");
        PdfXConverter.validate(out, options);
    }

    @Test
    void unsupportedStandardsFailBeforeTouchingTheDocument() throws Exception {
        assertThrows(JPDFiumException.class,
                () -> PdfXConverter.convert(new byte[] {1, 2, 3},
                        PdfXOptions.builder().standard(PdfXStandard.PDF_X_1A).build()));
        assertThrows(JPDFiumException.class,
                () -> PdfXConverter.convert(new byte[] {1, 2, 3},
                        PdfXOptions.builder().standard(PdfXStandard.PDF_X_6).build()));
    }

    @Test
    void missingProfileIsRejected() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] input = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .conditionIdentifier("FOGRA39")
                .build();
        assertThrows(JPDFiumException.class, () -> PdfXConverter.convert(input, options));
    }

    @Test
    void fileToFilePdfxBootstrapWorks() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] icc = findCmykProfile().orElse(null);
        assumeTrue(icc != null, "no CMYK ICC profile available on this machine");

        Path input = resource("/pdfs/general/minimal.pdf");
        Path output = Files.createTempFile("jpdfium-pdfx-", ".pdf");
        try {
            assertTrue(PdfOutputIntent.applyPdfx(input, output, icc,
                    PdfOutputIntent.Spec.pdfx("FOGRA39", "Coated FOGRA39"),
                    PdfXStandard.PDF_X_4.gtsVersion(),
                    PdfXStandard.PDF_X_4.gtsConformance()),
                    "file-to-file PDF/X bootstrap failed");
            assertTrue(Files.size(output) > 0, "PDF/X output is empty");
            try (PDDocument pdfbox = Loader.loadPDF(output.toFile())) {
                assertTrue(pdfbox.getDocumentCatalog().getMetadata() != null,
                        "file-to-file output has no XMP metadata");
                assertTrue(pdfbox.getDocumentCatalog().getOutputIntents() != null
                                && !pdfbox.getDocumentCatalog().getOutputIntents().isEmpty(),
                        "file-to-file output has no output intent");
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    void ghentFixturesAlsoConvertToPdfX4() throws Exception {
        String env = System.getenv("GHENT_DIR");
        assumeTrue(env != null && !env.isBlank(), "GHENT_DIR not set");
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] icc = findCmykProfile().orElse(null);
        assumeTrue(icc != null, "no CMYK ICC profile available on this machine");

        Path dir = Path.of(env);
        Path nested = dir.resolve("Ghent_PDF_Output_Suite_V50_Testpages");
        if (Files.isDirectory(nested)) {
            // GHENT_DIR may point at either the zip root or the extracted dir.
            dir = nested;
        }
        List<Path> fixtures;
        try (Stream<Path> paths = Files.list(dir)) {
            fixtures = paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".pdf"))
                    .sorted()
                    .toList();
        }
        assumeTrue(!fixtures.isEmpty(), "no Ghent PDFs found under " + dir);

        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .outputIntentProfile(icc)
                .conditionIdentifier("FOGRA39")
                .info("Coated FOGRA39")
                .build();
        for (Path input : fixtures) {
            byte[] source = Files.readAllBytes(input);
            int pages;
            try (PdfDocument doc = PdfDocument.open(source)) {
                pages = doc.pageCount();
            }
            byte[] out = PdfXConverter.convert(source, options);
            PdfXConverter.validate(out, options);
            try (PdfDocument reopened = PdfDocument.open(out)) {
                assertEquals(pages, reopened.pageCount(),
                        input.getFileName() + " page count changed");
            }
        }
    }

    private static Path resource(String name) throws Exception {
        return Path.of(PdfXConverterTest.class.getResource(name).toURI());
    }

    /** First real CMYK profile on this machine, if any. */
    private static Optional<byte[]> findCmykProfile() {
        String explicit = System.getProperty("jpdfium.test.cmykIcc");
        String[] candidates = {
            explicit,
            "/System/Library/ColorSync/Profiles/Generic CMYK Profile.icc",
            "/usr/share/color/icc/ghostscript/default_cmyk.icc",
            "/usr/share/color/icc/ISOcoated_v2_300_eci.icc",
            "/usr/share/color/icc/colord/ISOcoated_v2_300_eci.icc",
        };
        for (String candidate : candidates) {
            if (candidate == null) continue;
            try {
                Path path = Path.of(candidate);
                if (!Files.isReadable(path)) continue;
                byte[] bytes = Files.readAllBytes(path);
                if (ICC_Profile.getInstance(bytes).getNumComponents() == 4) {
                    return Optional.of(bytes);
                }
            } catch (Exception _) {
                // try the next candidate
            }
        }
        return Optional.empty();
    }
}

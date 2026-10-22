package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.redact.PdfRedactor;
import stirling.software.jpdfium.redact.RedactOptions;
import stirling.software.jpdfium.redact.RedactResult;
import stirling.software.jpdfium.redact.pii.PiiCategory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Redacts words, PII patterns, and metadata with the document-level redactor. */
public final class S05_Redact {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        Path out = SampleBase.out("redact");
        List<Path> outputs = new ArrayList<>();

        RedactOptions options = RedactOptions.builder()
                .addWord("confidential")
                .enablePiiPatterns(PiiCategory.select(PiiCategory.EMAIL, PiiCategory.SSN))
                .normalizeFonts(true)
                .redactMetadata(true)
                .build();

        for (Path input : SampleBase.inputs(args, "pdfs/redact/redact-test-helvetica.pdf")) {
            SampleBase.header("S05_Redact", input);
            Path output = out.resolve(input.getFileName());

            RedactResult result = PdfRedactor.redact(input, options);
            try (PdfDocument doc = result.document()) {
                doc.save(output);
            }
            outputs.add(output);

            System.out.printf("  %d page(s), %d match(es), %d metadata field(s), %d ms%n",
                    result.pagesProcessed(), result.totalMatches(),
                    result.metadataFieldsRedacted(), result.durationMs());
        }

        SampleBase.done("S05_Redact", outputs);
    }
}

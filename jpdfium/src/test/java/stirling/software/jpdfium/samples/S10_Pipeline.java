package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.FlattenMode;
import stirling.software.jpdfium.text.PdfTextSearcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Composes the core API: inspect, redact, flatten, render, and save. */
public final class S10_Pipeline {

    private static final int DPI = 144;
    private static final String SSN_PATTERN = "\\d{3}-\\d{2}-\\d{4}";

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        Path out = SampleBase.out("pipeline");
        List<Path> outputs = new ArrayList<>();

        for (Path input : SampleBase.inputs(args, "pdfs/general/minimal.pdf")) {
            SampleBase.header("S10_Pipeline", input);
            String stem = SampleBase.stem(input);

            Path redacted = out.resolve(stem + "-redacted.pdf");
            try (PdfDocument doc = PdfDocument.open(input)) {
                System.out.printf("  before: %d SSN match(es)%n",
                        PdfTextSearcher.search(doc, "123-45-6789").size());

                for (int i = 0; i < doc.pageCount(); i++) {
                    try (PdfPage page = doc.page(i)) {
                        page.redactPattern(SSN_PATTERN, 0xFF000000, true);
                    }
                }
                doc.flatten(FlattenMode.ANNOTATIONS);
                doc.save(redacted);
            }
            outputs.add(redacted);

            try (PdfDocument doc = PdfDocument.open(redacted)) {
                System.out.printf("  after: %d SSN match(es)%n",
                        PdfTextSearcher.search(doc, "123-45-6789").size());

                try (PdfPage page = doc.page(0)) {
                    Path png = out.resolve(stem + "-page-0.png");
                    page.renderTo(png, DPI);
                    outputs.add(png);
                }
            }

            System.out.printf("  saved %s%n", redacted.getFileName());
        }

        SampleBase.done("S10_Pipeline", outputs);
    }
}

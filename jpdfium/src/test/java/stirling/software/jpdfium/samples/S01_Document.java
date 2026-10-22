package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.PageSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Opens documents, reads page and metadata information, and writes a copy. */
public final class S01_Document {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        Path out = SampleBase.out("document");
        List<Path> outputs = new ArrayList<>();

        for (Path input : SampleBase.inputs(args, "pdfs/general/minimal.pdf")) {
            SampleBase.header("S01_Document", input);
            try (PdfDocument doc = PdfDocument.open(input)) {
                System.out.printf("  pages: %d  size: %d bytes%n", doc.pageCount(), Files.size(input));

                doc.metadata().forEach((key, value) ->
                        System.out.printf("  %-12s %s%n", key, value));

                for (int i = 0; i < doc.pageCount(); i++) {
                    try (PdfPage page = doc.page(i)) {
                        PageSize size = page.size();
                        System.out.printf("  page %d: %.1f x %.1f pt, rotation %d%n",
                                i, size.width(), size.height(), doc.getPageRotation(i));
                    }
                }

                Path copy = out.resolve(SampleBase.stem(input) + "-copy.pdf");
                doc.save(copy);
                outputs.add(copy);

                byte[] bytes = doc.saveBytes();
                try (PdfDocument reopened = PdfDocument.open(bytes)) {
                    System.out.printf("  reopened from %d bytes: %d pages%n",
                            bytes.length, reopened.pageCount());
                }
            }
        }

        SampleBase.done("S01_Document", outputs);
    }
}

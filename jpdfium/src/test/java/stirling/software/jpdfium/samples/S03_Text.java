package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.text.PageText;
import stirling.software.jpdfium.text.PdfTextExtractor;
import stirling.software.jpdfium.text.PdfTextSearcher;
import stirling.software.jpdfium.text.PdfTextSearcher.SearchMatch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Extracts structured text and searches the document. */
public final class S03_Text {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        Path out = SampleBase.out("text");
        List<Path> outputs = new ArrayList<>();

        for (Path input : SampleBase.inputs(args, "pdfs/general/basic-text.pdf")) {
            SampleBase.header("S03_Text", input);
            StringBuilder report = new StringBuilder();

            try (PdfDocument doc = PdfDocument.open(input)) {
                for (int i = 0; i < doc.pageCount(); i++) {
                    PageText text = PdfTextExtractor.extractPage(doc, i);
                    report.append(text.plainText()).append('\n');
                    System.out.printf("  page %d: %d lines, %d words, %d chars%n",
                            i, text.lineCount(), text.wordCount(), text.charCount());
                }

                List<SearchMatch> matches = PdfTextSearcher.search(doc, "PDF");
                System.out.printf("  search \"PDF\": %d match(es)%n", matches.size());
                for (SearchMatch match : matches) {
                    System.out.printf("    page %d, offset %d, length %d%n",
                            match.pageIndex(), match.startIndex(), match.length());
                }
            }

            Path reportFile = out.resolve(SampleBase.stem(input) + ".txt");
            Files.writeString(reportFile, report);
            outputs.add(reportFile);
        }

        SampleBase.done("S03_Text", outputs);
    }
}

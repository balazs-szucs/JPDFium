package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfMerge;
import stirling.software.jpdfium.PdfSplit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Merges documents, splits one into pages, and extracts a page range. */
public final class S07_MergeSplit {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputs(args,
                "pdfs/general/minimal.pdf", "pdfs/general/basic-text.pdf");
        Path out = SampleBase.out("merge-split");
        List<Path> outputs = new ArrayList<>();

        if (inputs.size() > 1) {
            Path mergedFile = out.resolve("merged.pdf");
            try (PdfDocument merged = PdfMerge.mergeFiles(inputs)) {
                merged.save(mergedFile);
            }
            outputs.add(mergedFile);
            SampleBase.header("S07_MergeSplit", inputs.getFirst());
            System.out.printf("  merged %d documents -> %s%n", inputs.size(), mergedFile.getFileName());
        }

        try (PdfDocument doc = PdfDocument.open(inputs.getFirst())) {
            String stem = SampleBase.stem(inputs.getFirst());

            List<PdfDocument> pages = PdfSplit.split(doc, PdfSplit.SplitStrategy.singlePages());
            try {
                for (int i = 0; i < pages.size(); i++) {
                    Path pageFile = out.resolve(stem + "-page-" + (i + 1) + ".pdf");
                    pages.get(i).save(pageFile);
                    outputs.add(pageFile);
                }
                System.out.printf("  split into %d page(s)%n", pages.size());
            } finally {
                pages.forEach(PdfDocument::close);
            }

            int lastPage = Math.min(1, doc.pageCount() - 1);
            Path rangeFile = out.resolve(stem + "-pages-1-" + (lastPage + 1) + ".pdf");
            try (PdfDocument range = PdfSplit.extractPageRange(doc, 0, lastPage)) {
                range.save(rangeFile);
            }
            outputs.add(rangeFile);
            System.out.printf("  extracted pages 1-%d -> %s%n", lastPage + 1, rangeFile.getFileName());
        }

        SampleBase.done("S07_MergeSplit", outputs);
    }
}

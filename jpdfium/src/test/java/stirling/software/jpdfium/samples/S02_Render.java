package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Renders every page to a PNG file. */
public final class S02_Render {

    private static final int DPI = 144;

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        Path out = SampleBase.out("render");
        List<Path> outputs = new ArrayList<>();

        for (Path input : SampleBase.inputs(args, "pdfs/general/basic-text.pdf")) {
            SampleBase.header("S02_Render", input);
            try (PdfDocument doc = PdfDocument.open(input)) {
                for (int i = 0; i < doc.pageCount(); i++) {
                    try (PdfPage page = doc.page(i)) {
                        Path png = out.resolve(SampleBase.stem(input) + "-page-" + i + ".png");
                        page.renderTo(png, DPI);
                        outputs.add(png);
                        System.out.printf("  page %d -> %s%n", i, png.getFileName());
                    }
                }
            }
        }

        SampleBase.done("S02_Render", outputs);
    }
}

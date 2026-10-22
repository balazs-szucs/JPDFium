package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.CompressOptions;
import stirling.software.jpdfium.doc.CompressPreset;
import stirling.software.jpdfium.doc.PdfCompressor;
import stirling.software.jpdfium.doc.PdfCompressor.CompressResultWithBytes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Compresses documents with the lossless and web presets. */
public final class S08_Compress {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        Path out = SampleBase.out("compress");
        List<Path> outputs = new ArrayList<>();

        for (Path input : SampleBase.inputs(args, "pdfs/general/basic-text.pdf")) {
            SampleBase.header("S08_Compress", input);
            System.out.printf("  original: %,d bytes%n", Files.size(input));
            String stem = SampleBase.stem(input);

            for (CompressPreset preset : List.of(CompressPreset.LOSSLESS, CompressPreset.WEB)) {
                try (PdfDocument doc = PdfDocument.open(input)) {
                    CompressResultWithBytes result = PdfCompressor.compress(doc,
                            CompressOptions.builder().preset(preset).build());
                    Path output = out.resolve(stem + "-" + preset.name().toLowerCase() + ".pdf");
                    Files.write(output, result.bytes());
                    outputs.add(output);
                    System.out.printf("  %-8s %,d bytes (%.1f%% smaller)%n",
                            preset, result.bytes().length, result.compressionPercent());
                }
            }
        }

        SampleBase.done("S08_Compress", outputs);
    }
}

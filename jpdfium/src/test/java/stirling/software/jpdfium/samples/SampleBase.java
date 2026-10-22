package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.panama.NativeLoader;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Shared helpers for the sample programs. */
final class SampleBase {

    static final Path OUT_ROOT = Path
            .of(System.getProperty("samples.output", "samples-output"))
            .toAbsolutePath();

    private SampleBase() {}

    static void ensureNative() {
        NativeLoader.ensureLoaded();
    }

    /**
     * Returns the PDFs to process: explicit command-line paths when present,
     * otherwise the bundled test resources named in {@code defaults}.
     */
    static List<Path> inputs(String[] args, String... defaults) throws IOException, URISyntaxException {
        if (args != null && args.length > 0) {
            return Arrays.stream(args).map(Path::of).toList();
        }
        List<Path> paths = new ArrayList<>(defaults.length);
        for (String resource : defaults) {
            URL url = SampleBase.class.getClassLoader().getResource(resource);
            if (url == null) {
                throw new IllegalStateException("Missing test resource: " + resource);
            }
            paths.add(Path.of(url.toURI()));
        }
        return paths;
    }

    static Path out(String feature) throws IOException {
        return Files.createDirectories(OUT_ROOT.resolve(feature));
    }

    static String stem(Path pdf) {
        String name = pdf.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static void header(String sample, Path input) {
        System.out.printf("%n%s | %s%n", sample, input.getFileName());
    }

    static void done(String sample, List<Path> outputs) {
        System.out.printf("%n%s done: %d file(s) in %s%n", sample, outputs.size(), OUT_ROOT);
    }
}

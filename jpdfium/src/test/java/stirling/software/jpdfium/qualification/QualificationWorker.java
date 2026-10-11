package stirling.software.jpdfium.qualification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfRepair;
import stirling.software.jpdfium.doc.RepairResult;
import stirling.software.jpdfium.fonts.intel.FontIntelligence;
import stirling.software.jpdfium.panama.NativeLoader;
import stirling.software.jpdfium.panama.NativeRuntime;

/**
 * One isolated operation for the qualification harness.
 *
 * <p>Runs a single {@code <mode>} on one immutable input and writes at most one
 * candidate output. It is deliberately a fresh process per case so a native crash
 * or hang is contained to one case rather than erasing the rest of a run.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code save} — open and re-save with no mutation (the serialization
 *       control: serialization itself can change bytes, so the other lanes are
 *       only meaningful relative to this);</li>
 *   <li>{@code font} — {@code FontIntelligence.repairToUnicode};</li>
 *   <li>{@code font-plan} — {@code FontIntelligence.applyRepairs(doc,
 *       repairPlans(doc))};</li>
 *   <li>{@code preserve} — the preserving structural repair
 *       {@code PdfRepair.builder().input(source).preserve().build().execute()}.</li>
 * </ul>
 *
 * <p>Contract:
 * <ul>
 *   <li>never overwrite the corpus input (refuses when input real path equals the
 *       normalized output);</li>
 *   <li>exit 2 for a usage error or a precondition the harness controls (missing
 *       file, output already present, empty input);</li>
 *   <li>exit 3 for an explicit refusal or a per-file failure: no candidate bytes
 *       are published, and the reason is printed to stderr;</li>
 *   <li>exit 0 only when exactly one candidate was written.</li>
 * </ul>
 */
public final class QualificationWorker {

    private QualificationWorker() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println(
                    "usage: QualificationWorker <save|font|font-plan|preserve> <input> <output>");
            System.exit(2);
        }
        String mode = args[0];
        Path input = Paths.get(args[1]);
        Path output = Paths.get(args[2]).toAbsolutePath().normalize();

        if (!Files.isRegularFile(input)) {
            System.err.println("REFUSED input is not a regular file: " + input);
            System.exit(2);
        }
        if (input.toRealPath().equals(output)) {
            System.err.println("REFUSED never overwrite the corpus input: " + output);
            System.exit(2);
        }
        if (Files.exists(output)) {
            System.err.println("REFUSED output already exists: " + output);
            System.exit(2);
        }

        NativeLoader.ensureLoaded();
        if (!NativeRuntime.isFull()) {
            throw new IllegalStateException("qualification requires real natives");
        }

        byte[] source = Files.readAllBytes(input);
        if (source.length == 0) {
            System.err.println("REFUSED empty input: " + input);
            System.exit(2);
        }

        // Per-file work: any exception (e.g. an unopenable malformed PDF) is an
        // explicit refusal, never a crash, and never publishes bytes.
        try {
            long start = System.nanoTime();
            int fixed = 0;
            byte[] candidate;
            if ("preserve".equals(mode)) {
                RepairResult result = PdfRepair.builder().input(source).preserve().build().execute();
                Path report = Paths.get(output + ".report.txt");
                Files.createDirectories(report.getParent());
                Files.writeString(report, String.valueOf(result.report()));
                if (!result.isUsable() || result.repairedPdf() == null) {
                    System.err.println("REFUSED status=" + result.status() + " report=" + report);
                    System.exit(3);
                }
                candidate = result.repairedPdf();
            } else {
                try (PdfDocument doc = PdfDocument.open(source)) {
                    switch (mode) {
                        case "save" -> { /* serialization control: re-save, no mutation */ }
                        case "font" -> fixed = FontIntelligence.repairToUnicode(doc);
                        case "font-plan" ->
                                fixed = FontIntelligence.applyRepairs(
                                        doc, FontIntelligence.repairPlans(doc));
                        default -> throw new IllegalArgumentException("unknown mode: " + mode);
                    }
                    candidate = doc.saveBytes();
                }
            }
            if (candidate == null || candidate.length == 0) {
                System.err.println("REFUSED no candidate bytes for mode=" + mode);
                System.exit(3);
            }
            Files.createDirectories(output.getParent());
            Files.write(output, candidate);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            System.out.println("mode=" + mode + " fixed=" + fixed + " inputBytes=" + source.length
                    + " outputBytes=" + candidate.length + " elapsedMs=" + elapsedMs);
        } catch (Throwable t) {
            System.err.println("REFUSED reason=" + t);
            System.exit(3);
        }
    }
}

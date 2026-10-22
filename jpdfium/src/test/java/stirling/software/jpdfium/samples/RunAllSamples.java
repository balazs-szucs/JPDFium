package stirling.software.jpdfium.samples;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs every sample with a per-sample timeout and reports a summary. */
public final class RunAllSamples {

    private static final int TIMEOUT_SECONDS = 120;

    private RunAllSamples() {}

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();

        record Sample(String name, ThrowingRunnable action) {}
        List<Sample> samples = List.of(
                new Sample("S01_Document", () -> S01_Document.main(args)),
                new Sample("S02_Render", () -> S02_Render.main(args)),
                new Sample("S03_Text", () -> S03_Text.main(args)),
                new Sample("S04_Images", () -> S04_Images.main(args)),
                new Sample("S05_Redact", () -> S05_Redact.main(args)),
                new Sample("S06_Forms", () -> S06_Forms.main(args)),
                new Sample("S07_MergeSplit", () -> S07_MergeSplit.main(args)),
                new Sample("S08_Compress", () -> S08_Compress.main(args)),
                new Sample("S09_Security", () -> S09_Security.main(args)),
                new Sample("S10_Pipeline", () -> S10_Pipeline.main(args))
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        int passed = 0;
        List<String> failures = new ArrayList<>();

        try {
            for (Sample sample : samples) {
                System.out.printf("%n[%d/%d] %s%n",
                        passed + failures.size() + 1, samples.size(), sample.name());
                Future<?> future = executor.submit(() -> {
                    try {
                        sample.action().run();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                try {
                    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    passed++;
                } catch (TimeoutException _) {
                    future.cancel(true);
                    failures.add(sample.name() + " (timeout)");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof RuntimeException runtime && runtime.getCause() != null) {
                        cause = runtime.getCause();
                    }
                    failures.add(sample.name() + ": " + cause.getMessage());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        System.out.printf("%nResults: %d/%d passed, %d failed%n",
                passed, samples.size(), failures.size());
        failures.forEach(failure -> System.out.println("  - " + failure));
        System.out.println("Output: " + SampleBase.OUT_ROOT);
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}

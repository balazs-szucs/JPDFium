package stirling.software.jpdfium.doc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import stirling.software.jpdfium.fonts.intel.FontDiagnostic;

/**
 * A structured account of what a repair run actually did.
 *
 * <p>
 * A repair {@link RepairResult.Status} alone cannot say which strategy was used,
 * which stages ran, what was preserved, or how the candidate was verified. This
 * report keeps those claims separate.
 *
 * @param outcome        one of {@code clean} / {@code repaired} / {@code partial} / {@code failed}
 * @param strategy       the strategy that produced the published candidate
 * @param stagesAttempted stage identifiers that were attempted, in order
 * @param stagesSucceeded stage identifiers that completed successfully
 * @param warnings       human-readable warnings collected while repairing
 * @param preservation   per-feature preservation claim (pages/forms/outlines/attachments/signatures)
 * @param outputValidation output verification facts (candidate reopen, enumerated pages)
 */
public record RepairReport(
        String outcome,
        String strategy,
        List<String> stagesAttempted,
        List<String> stagesSucceeded,
        List<String> warnings,
        Map<String, String> preservation,
        Map<String, String> outputValidation) {

    public static final String OUTCOME_CLEAN = "clean";
    public static final String OUTCOME_REPAIRED = "repaired";
    public static final String OUTCOME_PARTIAL = "partial";
    public static final String OUTCOME_FAILED = "failed";

    /** QPDF structural recovery/rewrite; preserves document structure. */
    public static final String STRATEGY_QPDF_REWRITE = "qpdf_rewrite";
    /** No strategy ran (unavailable native, or a non-repair path). */
    public static final String STRATEGY_UNAVAILABLE = "unavailable";
    /** PDFio page-by-page salvage: a fresh document, so only pages are guaranteed. */
    public static final String STRATEGY_PDFIO_PAGE_SALVAGE = "pdfio_page_salvage";
    /** lopdf (Rust) tolerant XRef rebuild. */
    public static final String STRATEGY_LOPDF_REWRITE = "lopdf_rewrite";
    /**
     * Recovery of an earlier explicit revision when the latest one is
     * unrepairable. The recovered document is the earlier revision as-is;
     * everything the later revisions changed is reported as {@link #LOST}.
     */
    public static final String STRATEGY_REVISION_RECOVERY = "revision_recovery";

    public static final String PRESERVED = "preserved";
    public static final String UNKNOWN = "unknown";
    public static final String LOST = "lost";
    public static final String INVALIDATED_OR_UNKNOWN = "invalidated_or_unknown";

    public RepairReport {
        outcome = outcome == null ? OUTCOME_FAILED : outcome;
        strategy = strategy == null ? "" : strategy;
        stagesAttempted = stagesAttempted == null ? List.of() : List.copyOf(stagesAttempted);
        stagesSucceeded = stagesSucceeded == null ? List.of() : List.copyOf(stagesSucceeded);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        preservation = preservation == null ? Map.of() : Map.copyOf(preservation);
        outputValidation = outputValidation == null ? Map.of() : Map.copyOf(outputValidation);
    }

    /** Map a coarse status to an outcome string. */
    public static String outcomeFor(RepairResult.Status status) {
        return switch (status) {
            case CLEAN -> OUTCOME_CLEAN;
            case FIXED -> OUTCOME_REPAIRED;
            case PARTIAL -> OUTCOME_PARTIAL;
            case FAILED -> OUTCOME_FAILED;
        };
    }

    /**
     * Structured diagnostics explaining how this report was reached.
     *
     * <p>A salvage strategy means the document could not be repaired in place and
     * only its pages were recovered, so anything held outside the page tree is
     * reported as lost. A clean in-place rewrite carries no diagnostic: nothing
     * was withheld.
     */
    public List<FontDiagnostic> diagnostics() {
        if (STRATEGY_PDFIO_PAGE_SALVAGE.equals(strategy)
                || STRATEGY_LOPDF_REWRITE.equals(strategy)
                || STRATEGY_REVISION_RECOVERY.equals(strategy)) {
            return List.of(FontDiagnostic.STRUCTURAL_SALVAGE_REQUIRED);
        }
        return List.of();
    }

    /**
     * A minimal report carrying only the outcome and a single strategy, for
     * stages that have no richer evidence to report.
     */
    public static RepairReport strategy(RepairResult.Status status, String strategy) {
        return new RepairReport(outcomeFor(status), strategy, List.of(), List.of(), List.of(),
                Map.of(), Map.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable accumulation helper for composing the final report. */
    public static final class Builder {

        private Builder() {}
        private String outcome;
        private String strategy = "";
        private final List<String> attempted = new ArrayList<>();
        private final List<String> succeeded = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final Map<String, String> preservation = new LinkedHashMap<>();
        private final Map<String, String> validation = new LinkedHashMap<>();

        public Builder outcome(String value) {
            this.outcome = value;
            return this;
        }

        public Builder strategy(String value) {
            this.strategy = value == null ? "" : value;
            return this;
        }

        public Builder attempted(String stage) {
            attempted.add(stage);
            return this;
        }

        public Builder succeeded(String stage) {
            succeeded.add(stage);
            return this;
        }

        public Builder warn(String message) {
            warnings.add(message);
            return this;
        }

        public Builder preservation(String feature, String claim) {
            preservation.put(feature, claim);
            return this;
        }

        public Builder validation(String fact, String value) {
            validation.put(fact, value);
            return this;
        }

        public RepairReport build() {
            return new RepairReport(outcome, strategy, attempted, succeeded, warnings, preservation,
                    validation);
        }
    }
}

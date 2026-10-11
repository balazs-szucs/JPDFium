package stirling.software.jpdfium.doc;

/**
 * Result of a PDF repair operation.
 *
 * @param status         outcome of the repair attempt
 * @param repairedPdf    the (possibly repaired) PDF bytes, or null on failure
 * @param diagnosticJson JSON string with repair diagnostics and warnings
 * @param report         structured account of the strategy, stages, preservation and verification
 */
public record RepairResult(
        Status status,
        byte[] repairedPdf,
        String diagnosticJson,
        RepairReport report) {

    /** Repair outcome. */
    public enum Status {
        /** The input PDF was already valid - no changes made. */
        CLEAN,
        /** The PDF was repaired successfully (with warnings). */
        FIXED,
        /** Partial repair - some content may be lost. */
        PARTIAL,
        /** All repair strategies failed. */
        FAILED
    }

    /**
     * Backwards-compatible constructor: derives a minimal report from the status
     * and, where the diagnostics name a strategy, from the diagnostics.
     */
    public RepairResult(Status status, byte[] repairedPdf, String diagnosticJson) {
        this(status, repairedPdf, diagnosticJson,
                RepairReport.strategy(status, strategyFromDiagnostics(diagnosticJson)));
    }

    public RepairResult {
        if (report == null) {
            report = RepairReport.strategy(status, RepairReport.STRATEGY_UNAVAILABLE);
        }
    }

    private static String strategyFromDiagnostics(String diagnosticJson) {
        if (diagnosticJson == null) {
            return RepairReport.STRATEGY_UNAVAILABLE;
        }
        if (diagnosticJson.contains("pdfio")) {
            return RepairReport.STRATEGY_PDFIO_PAGE_SALVAGE;
        }
        if (diagnosticJson.contains("rust-lopdf")) {
            return RepairReport.STRATEGY_LOPDF_REWRITE;
        }
        return RepairReport.STRATEGY_UNAVAILABLE;
    }

    /**
     * Returns true if the repair produced a usable output PDF.
     */
    public boolean isUsable() {
        return status != Status.FAILED && repairedPdf != null;
    }
}

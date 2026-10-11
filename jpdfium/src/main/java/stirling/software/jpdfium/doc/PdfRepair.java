package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.RepairLib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PDF repair with a builder-pattern API.
 *
 * <p>
 * Multi-stage cascade pipeline:
 * <ol>
 * <li><b>Pre-repair</b>: Brotli-Flate transcoding (PDF 2.0+ compat, optional
 * codec). A requested transcode that cannot run is reported in the diagnostics.</li>
 * <li><b>Core</b>: PDFium tolerant open - qpdf recovery - startxref fix</li>
 * <li><b>Fallback</b>: PDFio third-opinion XRef repair</li>
 * <li><b>Rust fallback</b> (opt-in): lopdf tolerant XRef rebuild - final cascade
 * stage when all C-based repair strategies fail. Enabled via
 * {@link Builder#useLopdfFallback(boolean)} or included in {@link Builder#all()}.</li>
 * <li><b>Post-repair</b>: ICC profile validation (lcms2), JPEG2000 validation
 * (OpenJPEG). Requested validation is reported in the diagnostics; this module has
 * no per-stream extractor.</li>
 * <li><b>Verification</b>: every stage that produces a candidate is independently
 * reopened and its pages are opened before the candidate can be adopted, and again
 * before publication. An unreadable candidate is reported {@code FAILED} with no
 * bytes. A declared page count is never treated as proof of a traversable tree.</li>
 * </ol>
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * RepairResult result = PdfRepair.builder()
 *         .input(pdfBytes)
 *         .all()
 *         .build()
 *         .execute();
 *
 * if (result.isUsable()) {
 *     byte[] fixed = result.repairedPdf();
 * }
 *
 * // Inspect only (non-destructive)
 * String diagnostics = PdfRepair.inspect(pdfBytes);
 * }</pre>
 *
 * <p>
 * All underlying libraries are MIT-compatible:
 * qpdf (Apache 2.0), PDFium (BSD), Brotli (MIT), PDFio (Apache 2.0),
 * lcms2 (MIT), OpenJPEG (BSD 2-Clause), lopdf (MIT, Rust).
 */
public final class PdfRepair {

    // Core repair flags - must match jpdfium.h: JPDFIUM_REPAIR_NORMALIZE_XREF 0x01,
    // JPDFIUM_REPAIR_FIX_STARTXREF 0x02, JPDFIUM_REPAIR_FORCE_V14 0x04.
    private static final int FLAG_NORMALIZE_XREF = 0x0001;
    private static final int FLAG_FIX_STARTXREF = 0x0002;
    private static final int FLAG_FORCE_V14 = 0x0004;

    private final byte[] inputBytes;
    private final int flags;
    private final boolean usePdfioFallback;
    private final boolean useLopdfFallback;
    private final boolean recoverRevision;
    private final boolean transcodeBrotli;
    private final boolean writeDiagnostics;
    private final boolean sanitize;

    private final boolean validateIcc;
    private final boolean validateJpx;

    private PdfRepair(byte[] inputBytes, int flags,
            boolean usePdfioFallback, boolean useLopdfFallback, boolean recoverRevision,
            boolean transcodeBrotli,
            boolean validateIcc, boolean validateJpx,
            boolean writeDiagnostics, boolean sanitize) {
        this.inputBytes = inputBytes;
        this.flags = flags;
        this.usePdfioFallback = usePdfioFallback;
        this.useLopdfFallback = useLopdfFallback;
        this.recoverRevision = recoverRevision;
        this.transcodeBrotli = transcodeBrotli;
        this.validateIcc = validateIcc;
        this.validateJpx = validateJpx;
        this.writeDiagnostics = writeDiagnostics;
        this.sanitize = sanitize;
    }

    public boolean validateIcc() { return validateIcc; }
    public boolean validateJpx() { return validateJpx; }

    /** Create a new repair builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Inspect a PDF for damage without modifying it. */
    public static String inspect(byte[] pdfBytes) {
        return RepairLib.inspect(pdfBytes);
    }

    /**
     * Inspects a PDF file for damage without modifying it; prefer {@link #repairFile(Path, Path, int)}
     * for large files.
     */
    public static String inspect(Path path) throws IOException {
        return inspect(Files.readAllBytes(path));
    }

    /** Inspect a PDF and return a typed, non-destructive view of the damage. */
    public static RepairInspection inspectStructured(byte[] pdfBytes) {
        return RepairInspection.parse(inspect(pdfBytes));
    }

    /** Inspect a PDF file and return a typed, non-destructive view of the damage. */
    public static RepairInspection inspectStructured(Path path) throws IOException {
        return RepairInspection.parse(inspect(path));
    }

    /**
     * Repairs a PDF straight to another file without materializing it, falling back to the
     * {@code byte[]} route on natives without the file entry point. Core stage only.
     */
    public static RepairResult.Status repairFile(Path input, Path output, int flags)
            throws IOException {
        return RepairLib.repairToFile(input, output, flags);
    }

    /**
     * Execute the full repair pipeline with configured options.
     *
     * @return result containing the status, repaired bytes, and diagnostics
     */
    public RepairResult execute() {
        RepairReport.Builder report = RepairReport.builder();
        byte[] current = inputBytes;
        boolean brotliApplied = false;
        boolean revisionRecovered = false;

        // Pre-repair: Brotli-Flate transcoding (optional capability); a request
        // that cannot run is recorded as an unsupported stage with a warning.
        if (transcodeBrotli) {
            report.attempted("brotli_transcode");
            byte[] transcoded = RepairLib.brotliToFlate(current);
            if (transcoded == null) {
                report.warn("brotli_transcode_unsupported: per-stream /BrotliDecode transcoding is "
                        + "not implemented; no whole-document transcode was applied");
            } else if (verifyStage("brotli_transcode", transcoded, report)) {
                current = transcoded;
                brotliApplied = true;
                report.succeeded("brotli_transcode");
            } else {
                report.warn("brotli_transcode_verification_failed: the transcoded document did not "
                        + "reopen; the original input is used instead");
            }
        }

        report.attempted("core_qpdf");
        RepairResult coreResult = RepairLib.repair(current, flags);
        if (coreResult.isUsable() && verifyStage("core_qpdf", coreResult.repairedPdf(), report)) {
            report.succeeded("core_qpdf");
        } else if (coreResult.isUsable()) {
            report.warn("core_qpdf_verification_failed: the repaired candidate did not reopen; "
                    + "later stages may still recover the document");
            coreResult = new RepairResult(RepairResult.Status.FAILED, null,
                    coreResult.diagnosticJson());
        }

        if (usePdfioFallback) {
            report.attempted("pdfio_page_salvage");
            if (!coreResult.isUsable()) {
                RepairResult pdfioResult = RepairLib.pdfioRepair(current);
                if (pdfioResult.isUsable()
                        && verifyStage("pdfio_page_salvage", pdfioResult.repairedPdf(), report)) {
                    coreResult = pdfioResult;
                }
            }
            if (RepairReport.STRATEGY_PDFIO_PAGE_SALVAGE.equals(coreResult.report().strategy())) {
                report.succeeded("pdfio_page_salvage");
            }
        }

        // Final fallback: Rust/lopdf tolerant XRef rebuild.
        // Only attempted when all C-based strategies have failed and the option is enabled.
        if (useLopdfFallback) {
            report.attempted("lopdf_rewrite");
            if (!coreResult.isUsable()) {
                RepairResult rustResult = RepairLib.rustRepair(current);
                if (rustResult.isUsable()
                        && verifyStage("lopdf_rewrite", rustResult.repairedPdf(), report)) {
                    coreResult = rustResult;
                }
            }
            if (RepairReport.STRATEGY_LOPDF_REWRITE.equals(coreResult.report().strategy())) {
                report.succeeded("lopdf_rewrite");
            }
        }

        // Revision recovery (opt-in via salvage): recover the newest earlier
        // revision that parses when the latest cannot be repaired; the loss of
        // later changes is reported.
        if (recoverRevision && !coreResult.isUsable()) {
            report.attempted("revision_recovery");
            RepairResult earlier = firstUsableRevision(current, flags);
            if (earlier != null
                    && verifyStage("revision_recovery", earlier.repairedPdf(), report)) {
                coreResult = earlier;
                revisionRecovered = true;
                report.succeeded("revision_recovery");
                report.warn("later_revisions_discarded: the latest revision was unrepairable; "
                        + "an earlier revision was recovered and changes after it are lost");
            }
        }

        // Verify before publication: a usable candidate must reopen as a PDF and
        // every page must be openable up to a bound; a declared page count alone
        // is not proof of a traversable page tree. Failure publishes no bytes.
        int verifiedPages = -1;
        if (coreResult.isUsable()) {
            report.attempted("candidate_reopen");
            CandidateCheck check = verifyCandidate(coreResult.repairedPdf());
            if (check.reopened()) {
                verifiedPages = check.pageCount();
                report.succeeded("candidate_reopen");
                report.validation("pdfium_loaded", "true");
                report.validation("enumerated_pages", Integer.toString(check.pageCount()));
                report.validation("page_traversal",
                        check.completeTraversal() ? "complete" : "bounded");
            } else {
                report.validation("pdfium_loaded", "false");
                report.warn("candidate_reopen_failed: repaired candidate could not be reopened; "
                        + "reported FAILED with no bytes");
                coreResult = new RepairResult(RepairResult.Status.FAILED, null,
                        coreResult.diagnosticJson());
            }
        }

        if (sanitize && coreResult.isUsable()) {
            report.attempted("sanitize");
            byte[] repairedBytes = coreResult.repairedPdf();
            if (repairedBytes != null) {
                try (PdfDocument doc = PdfDocument.open(repairedBytes)) {
                    PdfSecurity.sanitize(doc);
                    repairedBytes = doc.saveBytes();
                    coreResult = new RepairResult(coreResult.status(), repairedBytes,
                            coreResult.diagnosticJson());
                    report.succeeded("sanitize");
                } catch (Exception e) {
                    // A security policy that cannot be completed is not a successful
                    // repair and publishes nothing (no un-sanitized bytes).
                    report.warn("sanitize_failed: security sanitization could not be completed; "
                            + "nothing published");
                    coreResult = new RepairResult(RepairResult.Status.FAILED, null,
                            coreResult.diagnosticJson());
                }
            }
        }

        // Re-verify the FINAL candidate (after sanitization) rather than trusting
        // the pre-sanitize verification; failure publishes nothing.
        if (sanitize && coreResult.isUsable()) {
            report.attempted("final_reverify");
            CandidateCheck check = verifyCandidate(coreResult.repairedPdf());
            if (check.reopened()) {
                verifiedPages = check.pageCount();
                report.succeeded("final_reverify");
                report.validation("final_pdfium_loaded", "true");
                report.validation("final_enumerated_pages", Integer.toString(check.pageCount()));
                report.validation("final_page_traversal",
                        check.completeTraversal() ? "complete" : "bounded");
            } else {
                report.validation("final_pdfium_loaded", "false");
                report.warn("final_reverify_failed: sanitized candidate could not be reopened; "
                        + "reported FAILED with no bytes");
                coreResult = new RepairResult(RepairResult.Status.FAILED, null,
                        coreResult.diagnosticJson());
            }
        }

        // Post-repair validation (ICC/JPX): no document-level stream extractor
        // here, so a requested pass is recorded as unsupported, not attempted.
        if (validateIcc) {
            report.validation("validate_icc", "unsupported");
        }
        if (validateJpx) {
            report.validation("validate_jpx", "unsupported");
        }
        if (validateIcc || validateJpx) {
            report.warn("post_validation_unsupported: no document-level ICC/JPX stream extraction");
            coreResult = new RepairResult(coreResult.status(), coreResult.repairedPdf(),
                    withPostValidationNote(coreResult.diagnosticJson()));
        }

        // Brotli transcoding that did not run is reported rather than claimed.
        if (transcodeBrotli && !brotliApplied) {
            coreResult = new RepairResult(coreResult.status(), coreResult.repairedPdf(),
                    withTranscodeNote(coreResult.diagnosticJson()));
        }

        int inputPages = candidatePageCount(current);

        report.outcome(RepairReport.outcomeFor(coreResult.status()));
        String finalStrategy = revisionRecovered
                ? RepairReport.STRATEGY_REVISION_RECOVERY
                : coreResult.report().strategy();
        report.strategy(finalStrategy);
        describePreservation(report, coreResult.isUsable(), finalStrategy, inputPages,
                verifiedPages);
        RepairReport finalReport = report.build();
        coreResult = new RepairResult(coreResult.status(), coreResult.repairedPdf(),
                coreResult.diagnosticJson(), finalReport);

        if (!writeDiagnostics) {
            return new RepairResult(coreResult.status(), coreResult.repairedPdf(), null, finalReport);
        }

        return coreResult;
    }

    /**
     * Records what the chosen strategy can preserve. A preserving QPDF rewrite
     * keeps document structure; a page/raster salvage rebuilds a new document
     * from recovered pages, so anything outside those pages is an explicit loss.
     */
    private static void describePreservation(
            RepairReport.Builder report, boolean ok, String strategy,
            int inputPages, int outputPages) {
        boolean revision = RepairReport.STRATEGY_REVISION_RECOVERY.equals(strategy);
        boolean salvage = RepairReport.STRATEGY_PDFIO_PAGE_SALVAGE.equals(strategy)
                || RepairReport.STRATEGY_LOPDF_REWRITE.equals(strategy);
        // Only the page COUNT is verified here: equal counts do not prove the
        // same pages, order, contents or geometry, so page content is reported
        // as unknown rather than folded into a "preserved" claim.
        String pageCount = RepairReport.UNKNOWN;
        if (ok && inputPages >= 0 && outputPages >= 0) {
            pageCount = inputPages == outputPages
                    ? RepairReport.PRESERVED : RepairReport.LOST;
        }
        report.preservation("page_count", pageCount);
        report.preservation("page_content", RepairReport.UNKNOWN);
        if (revision) {
            // An earlier revision is intact up to its own %%EOF; everything the
            // later revisions added is gone.
            report.preservation("forms", RepairReport.UNKNOWN);
            report.preservation("outlines", RepairReport.UNKNOWN);
            report.preservation("attachments", RepairReport.UNKNOWN);
            report.preservation("signatures", RepairReport.INVALIDATED_OR_UNKNOWN);
            report.preservation("later_revisions", ok ? RepairReport.LOST : RepairReport.UNKNOWN);
        } else if (salvage) {
            report.preservation("forms", ok ? RepairReport.LOST : RepairReport.UNKNOWN);
            report.preservation("outlines", ok ? RepairReport.LOST : RepairReport.UNKNOWN);
            report.preservation("attachments", ok ? RepairReport.LOST : RepairReport.UNKNOWN);
            report.preservation("signatures", ok ? RepairReport.LOST
                    : RepairReport.INVALIDATED_OR_UNKNOWN);
        } else {
            report.preservation("forms", RepairReport.UNKNOWN);
            report.preservation("outlines", RepairReport.UNKNOWN);
            report.preservation("attachments", RepairReport.UNKNOWN);
            report.preservation("signatures", RepairReport.INVALIDATED_OR_UNKNOWN);
        }
    }

    /**
     * Appends {@code validate_icc}/{@code validate_jpx} fields to the diagnostics
     * JSON recording that the requested post-pass was not executed.
     */
    private String withPostValidationNote(String diagnostics) {
        StringBuilder note = new StringBuilder();
        if (validateIcc) {
            note.append(",\"validate_icc\":{\"requested\":true,\"executed\":false,")
                    .append("\"reason\":\"unsupported: no document-level ICC stream extraction\"}");
        }
        if (validateJpx) {
            note.append(",\"validate_jpx\":{\"requested\":true,\"executed\":false,")
                    .append("\"reason\":\"unsupported: no document-level JPX stream extraction\"}");
        }
        if (note.isEmpty()) {
            return diagnostics;
        }
        String base = diagnostics == null || diagnostics.isBlank() ? "{}" : diagnostics.trim();
        int close = base.lastIndexOf('}');
        if (close < 0) {
            return "{" + note.substring(1) + "}";
        }
        return base.substring(0, close) + note + base.substring(close);
    }

    /**
     * Appends a {@code transcode_brotli} field recording that a requested
     * Brotli-to-Flate transcode did not run.
     */
    private String withTranscodeNote(String diagnostics) {
        String note = ",\"transcode_brotli\":{\"requested\":true,\"executed\":false,"
                + "\"reason\":\"no Brotli transcode applied: codec unavailable in this build or "
                + "input is not a whole-document Brotli stream; per-stream Brotli is not implemented\"}";
        String base = diagnostics == null || diagnostics.isBlank() ? "{}" : diagnostics.trim();
        int close = base.lastIndexOf('}');
        if (close < 0) {
            return "{" + note.substring(1) + "}";
        }
        return base.substring(0, close) + note + base.substring(close);
    }

    /**
     * Upper bound on pages opened while verifying a candidate. A declared page
     * count alone does not prove the page tree can be walked, so verification
     * opens every page up to this bound; beyond it the traversal is reported as
     * bounded rather than claimed complete.
     */
    private static final int MAX_VERIFIED_PAGES = 1000;

    /** Independent verification of one candidate document. */
    private record CandidateCheck(int pageCount, boolean completeTraversal) {
        boolean reopened() {
            return pageCount >= 0;
        }
    }

    /**
     * Reopens a candidate as a PDF and opens every page up to
     * {@link #MAX_VERIFIED_PAGES}, so a traversal failure is observed rather
     * than inferred from a count. Returns the declared page count ({@code -1}
     * when the document cannot be reopened) and whether the whole tree was
     * walked. Never throws.
     */
    private static CandidateCheck verifyCandidate(byte[] candidate) {
        if (candidate == null || candidate.length == 0) {
            return new CandidateCheck(-1, false);
        }
        try (PdfDocument doc = PdfDocument.open(candidate)) {
            int pages = doc.pageCount();
            if (pages < 0) {
                return new CandidateCheck(-1, false);
            }
            int limit = Math.min(pages, MAX_VERIFIED_PAGES);
            for (int i = 0; i < limit; i++) {
                try (PdfPage page = doc.page(i)) {
                    page.size();
                }
            }
            return new CandidateCheck(pages, pages <= MAX_VERIFIED_PAGES);
        } catch (Exception | LinkageError e) {
            return new CandidateCheck(-1, false);
        }
    }

    /**
     * Reopens a repaired candidate as a PDF and returns its page count, or
     * {@code -1} when it cannot be reopened. Never throws.
     */
    private static int candidatePageCount(byte[] candidate) {
        return verifyCandidate(candidate).pageCount();
    }

    /**
     * Independently verifies a mutating stage's candidate before it can be
     * adopted, recording the outcome under {@code stage}. Returns whether the
     * candidate reopened with a walkable page tree.
     */
    private static boolean verifyStage(String stage, byte[] candidate, RepairReport.Builder report) {
        CandidateCheck check = verifyCandidate(candidate);
        if (!check.reopened()) {
            report.validation(stage + "_verified", "false");
            return false;
        }
        report.validation(stage + "_verified", "true");
        report.validation(stage + "_pages", Integer.toString(check.pageCount()));
        report.validation(stage + "_page_traversal",
                check.completeTraversal() ? "complete" : "bounded");
        return true;
    }

    /** Number of explicit revisions in a PDF: one {@code %%EOF} per revision. */
    public static int countRevisions(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i + 4 < pdfBytes.length; i++) {
            if (pdfBytes[i] == '%' && pdfBytes[i + 1] == '%'
                    && pdfBytes[i + 2] == 'E' && pdfBytes[i + 3] == 'O'
                    && pdfBytes[i + 4] == 'F') {
                count++;
                i += 4;
            }
        }
        return count;
    }

    /**
     * Candidates for revision recovery: byte prefixes ending at each {@code %%EOF}
     * except the last (the current revision), newest earlier revision first, capped
     * so a pathological input cannot clone the document unbounded times.
     */
    static List<byte[]> earlierRevisionCandidates(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return List.of();
        }
        List<Integer> ends = new ArrayList<>();
        for (int i = 0; i + 4 < pdfBytes.length; i++) {
            if (pdfBytes[i] == '%' && pdfBytes[i + 1] == '%'
                    && pdfBytes[i + 2] == 'E' && pdfBytes[i + 3] == 'O'
                    && pdfBytes[i + 4] == 'F') {
                int end = i + 5;
                // Extend to the end of the line so the truncation keeps the EOL.
                while (end < pdfBytes.length && pdfBytes[end] != '\n' && pdfBytes[end] != '\r') {
                    end++;
                }
                ends.add(end);
                i = end - 1;
            }
        }
        if (ends.size() <= 1) {
            return List.of();
        }
        List<byte[]> candidates = new ArrayList<>();
        for (int k = ends.size() - 2; k >= 0 && candidates.size() < 8; k--) {
            candidates.add(Arrays.copyOf(pdfBytes, ends.get(k)));
        }
        return candidates;
    }

    /** The newest earlier revision that repairs to a usable PDF, or {@code null}. */
    private static RepairResult firstUsableRevision(byte[] input, int flags) {
        for (byte[] candidate : earlierRevisionCandidates(input)) {
            RepairResult result = RepairLib.repair(candidate, flags);
            if (result.isUsable()) {
                return result;
            }
        }
        return null;
    }

    /**
     * Builder for configuring PDF repair pipeline options.
     */
    public static final class Builder {
        private byte[] inputBytes;
        private boolean doForceVersion14;
        private boolean doNormalizeXref;
        private boolean doFixStartxref;
        private boolean doUsePdfioFallback;
        private boolean doUseLopdfFallback;
        private boolean doRecoverRevision;
        private boolean doTranscodeBrotli;
        private boolean doValidateIcc;
        private boolean doValidateJpx;
        private boolean doWriteDiagnostics = true;
        private boolean doSanitize;

        private Builder() {
        }

        /** Set the input PDF bytes. */
        public Builder input(byte[] pdfBytes) {
            this.inputBytes = pdfBytes;
            return this;
        }

        /**
         * Set the input PDF file; read into the heap, or use {@link PdfRepair#repairFile} for a
         * flat-heap file-to-file repair.
         */
        public Builder input(Path path) throws IOException {
            this.inputBytes = Files.readAllBytes(path);
            return this;
        }

        /** Force output PDF version to 1.4. */
        public Builder forceVersion14(boolean enable) {
            this.doForceVersion14 = enable;
            return this;
        }

        /** Normalize xref format (force classic xref table). */
        public Builder normalizeXref(boolean enable) {
            this.doNormalizeXref = enable;
            return this;
        }

        /** Enable startxref offset brute-force correction. */
        public Builder fixStartxref(boolean enable) {
            this.doFixStartxref = enable;
            return this;
        }

        /** Enable PDFio third-opinion fallback (opt-in, requires libpdfio). */
        public Builder usePdfioFallback(boolean enable) {
            this.doUsePdfioFallback = enable;
            return this;
        }

        /**
         * Enable lopdf (Rust) XRef rebuild as a final fallback (opt-in).
         *
         * <p>Tried only after both the core qpdf pipeline and the PDFio fallback have
         * failed. lopdf's tolerant parser can often recover PDFs with corrupted XRef
         * tables that qpdf and PDFio cannot parse at all.
         *
         * <p>Included automatically by {@link #all()}.  If the Rust library is not
         * compiled in, this option is silently ignored (no error is thrown).
         *
         * @param enable {@code true} to enable (default {@code false})
         */
        public Builder useLopdfFallback(boolean enable) {
            this.doUseLopdfFallback = enable;
            return this;
        }

        /**
         * Recover an earlier explicit revision when the latest revision cannot be
         * repaired (opt-in). The document is truncated at the newest earlier
         * {@code %%EOF}, so changes made after that revision are lost; use only
         * when recovering an intact earlier revision is preferable to failing.
         * Included by {@link #salvage()}.
         */
        public Builder recoverEarlierRevision(boolean enable) {
            this.doRecoverRevision = enable;
            return this;
        }

        /** Enable Brotli-Flate pre-repair transcoding (opt-in, requires libbrotli). */
        public Builder transcodeBrotli(boolean enable) {
            this.doTranscodeBrotli = enable;
            return this;
        }

        /** Enable ICC profile validation post-pass (opt-in, requires liblcms2). */
        public Builder validateIcc(boolean enable) {
            this.doValidateIcc = enable;
            return this;
        }

        /**
         * Enable JPEG2000 stream validation post-pass (opt-in, requires libopenjp2).
         */
        public Builder validateJpx(boolean enable) {
            this.doValidateJpx = enable;
            return this;
        }

        /** Include diagnostic JSON in the result (default: true). Set to false to skip diagnostics. */
        public Builder writeDiagnostics(boolean enable) {
            this.doWriteDiagnostics = enable;
            return this;
        }

        /**
         * Enable post-repair security sanitization: removes JavaScript,
         * embedded files, and action annotations from the repaired PDF.
         */
        public Builder sanitize(boolean enable) {
            this.doSanitize = enable;
            return this;
        }

        /**
         * Enable all core + Phase 2 repair strategies (including Rust lopdf + sanitize).
         * Prefer a named policy ({@link #preserve()}, {@link #compatibility()},
         * {@link #salvage()}, {@link #secure()}) when the intent is known.
         *
         * <p>Whole-document Brotli transcoding is not included: per-stream
         * {@code /BrotliDecode} handling is not implemented.
         */
        public Builder all() {
            this.doForceVersion14 = true;
            this.doNormalizeXref = true;
            this.doFixStartxref = true;
            this.doUsePdfioFallback = true;
            this.doUseLopdfFallback = true;
            this.doValidateIcc = true;
            this.doValidateJpx = true;
            this.doSanitize = true;
            return this;
        }

        /**
         * <b>Preserve</b>: minimal structural repair only — normalize the xref and
         * correct a wrong startxref. No transcoding, fallbacks, validation or
         * sanitization. Use when maximum fidelity to the original structure is
         * wanted and a tolerant reader is available.
         */
        public Builder preserve() {
            this.doNormalizeXref = true;
            this.doFixStartxref = true;
            return this;
        }

        /**
         * <b>Compatibility</b>: {@link #preserve()} plus a forced PDF 1.4 version and
         * Brotli-to-Flate transcoding, so PDF 2.0 content remains readable by older
         * consumers. No lossy recovery and no sanitization.
         */
        public Builder compatibility() {
            this.doNormalizeXref = true;
            this.doFixStartxref = true;
            this.doForceVersion14 = true;
            this.doTranscodeBrotli = true;
            return this;
        }

        /**
         * <b>Salvage</b>: maximum recovery — structural fixes plus the PDFio and
         * lopdf tolerant XRef rebuilds, tried only after the core pipeline fails.
         * Not sanitized, so active content in a recovered document is preserved.
         */
        public Builder salvage() {
            this.doForceVersion14 = true;
            this.doNormalizeXref = true;
            this.doFixStartxref = true;
            this.doUsePdfioFallback = true;
            this.doUseLopdfFallback = true;
            this.doRecoverRevision = true;
            return this;
        }

        /**
         * <b>Secure</b>: repair then sanitize — structural fixes followed by removal
         * of JavaScript, embedded files and action annotations. Sanitization is a
         * hard requirement: if it cannot be completed the repair reports failure and
         * publishes nothing. Use when a repaired document will be shared untrusted.
         */
        public Builder secure() {
            this.doNormalizeXref = true;
            this.doFixStartxref = true;
            this.doSanitize = true;
            return this;
        }

        /**
         * Build the repair instance. Call {@link PdfRepair#execute()} to run.
         *
         * @throws IllegalStateException if no input was provided
         */
        public PdfRepair build() {
            if (inputBytes == null || inputBytes.length == 0) {
                throw new IllegalStateException("Input PDF bytes must be provided via .input()");
            }

            int flags = 0;
            if (doForceVersion14)
                flags |= FLAG_FORCE_V14;
            if (doNormalizeXref)
                flags |= FLAG_NORMALIZE_XREF;
            if (doFixStartxref)
                flags |= FLAG_FIX_STARTXREF;

            return new PdfRepair(inputBytes, flags,
                    doUsePdfioFallback, doUseLopdfFallback, doRecoverRevision, doTranscodeBrotli,
                    doValidateIcc, doValidateJpx, doWriteDiagnostics, doSanitize);
        }
    }
}

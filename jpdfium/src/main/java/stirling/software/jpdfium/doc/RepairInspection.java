package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.util.NativeJsonParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A structured, non-destructive inspection of a PDF's loadability and damage.
 *
 * <p>This is the typed view of the JSON emitted by {@link PdfRepair#inspect(byte[])}.
 * {@link #xrefOriginalValid()} and {@link #trailerOriginalValid()} are nullable and
 * {@code null} when the engine could not make a claim: a successful tolerant load
 * does not prove the original cross-reference table or trailer were valid.
 *
 * <p>The structural components ({@link #objectStreamCount()},
 * {@link #pageTreeCycles()}, ...) are bounded observations about the document as
 * loaded. A zero means "none observed", not "certified clean".
 *
 * @param status engine observation: {@code loaded}, {@code fatal},
 *               {@code unavailable} or {@code error}
 * @param warningCount recovery warnings raised while loading
 * @param declaredPageCount {@code /Pages /Count} as written, or {@code -1} when unknown
 * @param pageCount pages actually traversable by the engine
 * @param xrefOriginalValid original xref validity, or {@code null} when unknown
 * @param trailerOriginalValid original trailer validity, or {@code null} when unknown
 * @param loadRecovered whether the engine had to recover to load the document
 * @param issues human-readable recovery issues (may be empty)
 * @param objectStreamCount object streams ({@code /Type /ObjStm}) examined
 * @param objectStreamInvalidCount object streams whose index or offsets did not validate
 * @param pageTreeCycles nodes revisited while walking the page tree
 * @param pageTreeDuplicateRefs leaves reached more than once
 * @param pageTreeBrokenParents kids whose {@code /Parent} is not the owning node
 * @param pageTreeReachable leaf pages reachable by traversal
 * @param pageTreeTruncated whether traversal hit a bound before finishing
 */
public record RepairInspection(
        String status,
        int warningCount,
        int declaredPageCount,
        int pageCount,
        Boolean xrefOriginalValid,
        Boolean trailerOriginalValid,
        boolean loadRecovered,
        List<String> issues,
        long objectStreamCount,
        long objectStreamInvalidCount,
        long pageTreeCycles,
        long pageTreeDuplicateRefs,
        long pageTreeBrokenParents,
        long pageTreeReachable,
        boolean pageTreeTruncated) {

    public static final String STATUS_LOADED = "loaded";
    public static final String STATUS_FATAL = "fatal";
    public static final String STATUS_UNAVAILABLE = "unavailable";
    public static final String STATUS_ERROR = "error";

    /** The repair approach a caller should take, derived from what was observed. */
    public enum Strategy {
        /** No repair engine is linked; inspection cannot recommend a repair. */
        UNSUPPORTED,
        /** The engine could not parse the document at all. */
        UNRECOVERABLE,
        /** The document loaded only through recovery; publish a repaired copy. */
        REPAIR_REQUIRED,
        /** The document loaded without recovery warnings; no repair was observed to be needed. */
        NO_REPAIR_OBSERVED
    }

    public RepairInspection {
        status = status == null ? "" : status;
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** Parse the diagnostic JSON produced by the native inspection entry point. */
    public static RepairInspection parse(String json) {
        if (json == null || json.isBlank()) {
            return new RepairInspection(STATUS_ERROR, 0, -1, 0, null, null, false, List.of(),
                    0, 0, 0, 0, 0, 0, false);
        }
        String status = NativeJsonParser.stringField(json, "status");
        if (status.isEmpty() && json.contains("\"error\"")) {
            status = STATUS_ERROR;
        }
        int warningCount = NativeJsonParser.intField(json, "warning_count");
        int declaredPageCount = json.contains("\"declared_page_count\"")
                ? NativeJsonParser.intField(json, "declared_page_count")
                : -1;
        int pageCount = NativeJsonParser.intField(json, "page_count");
        Boolean xrefValid = NativeJsonParser.nullableBoolField(json, "xref_original_valid");
        Boolean trailerValid = NativeJsonParser.nullableBoolField(json, "trailer_original_valid");
        boolean loadRecovered = NativeJsonParser.boolField(json, "load_recovered");

        long objectStreamCount = NativeJsonParser.longField(json, "object_stream_count");
        long objectStreamInvalid = NativeJsonParser.longField(json, "object_stream_invalid_count");
        long pageTreeCycles = NativeJsonParser.longField(json, "page_tree_cycles");
        long pageTreeDuplicates = NativeJsonParser.longField(json, "page_tree_duplicate_refs");
        long pageTreeBrokenParents = NativeJsonParser.longField(json, "page_tree_broken_parents");
        long pageTreeReachable = NativeJsonParser.longField(json, "page_tree_reachable");
        boolean pageTreeTruncated = NativeJsonParser.boolField(json, "page_tree_truncated");

        List<String> issues = new ArrayList<>();
        int issuesKey = json.indexOf("\"issues\"");
        if (issuesKey >= 0) {
            int arrayStart = json.indexOf('[', issuesKey);
            int arrayEnd = arrayStart < 0 ? -1 : json.indexOf(']', arrayStart);
            if (arrayStart >= 0 && arrayEnd > arrayStart) {
                for (Map<String, String> issue
                        : NativeJsonParser.parseArray(json.substring(arrayStart, arrayEnd + 1))) {
                    String message = issue.get("message");
                    if (message != null && !message.isBlank()) {
                        issues.add(message);
                    }
                }
            }
        }
        return new RepairInspection(status, warningCount, declaredPageCount, pageCount,
                xrefValid, trailerValid, loadRecovered, issues,
                objectStreamCount, objectStreamInvalid, pageTreeCycles, pageTreeDuplicates,
                pageTreeBrokenParents, pageTreeReachable, pageTreeTruncated);
    }

    /** Inspect a file without modifying it. */
    public static RepairInspection parse(Path path) throws IOException {
        return parse(PdfRepair.inspect(path));
    }

    /** Whether an inspection engine actually answered (as opposed to being absent). */
    public boolean engineAvailable() {
        return !STATUS_UNAVAILABLE.equals(status) && !STATUS_ERROR.equals(status);
    }

    /** Whether the engine could load the document (with or without recovery). */
    public boolean loaded() {
        return STATUS_LOADED.equals(status);
    }

    /**
     * Whether the declared page count disagrees with what could be traversed, or
     * {@code null} when the declared count is unknown.
     */
    public Boolean pageCountMismatch() {
        return declaredPageCount < 0 ? null : declaredPageCount != pageCount;
    }

    /** Whether a structural defect was observed in the page tree or object streams. */
    public boolean structuralDamageObserved() {
        return objectStreamInvalidCount > 0
                || pageTreeCycles > 0
                || pageTreeDuplicateRefs > 0
                || pageTreeBrokenParents > 0
                || pageTreeTruncated;
    }

    /** The recommended repair approach for this observation. */
    public Strategy strategy() {
        return switch (status) {
            case STATUS_UNAVAILABLE -> Strategy.UNSUPPORTED;
            case STATUS_LOADED -> (loadRecovered || warningCount > 0 || structuralDamageObserved())
                    ? Strategy.REPAIR_REQUIRED
                    : Strategy.NO_REPAIR_OBSERVED;
            default -> Strategy.UNRECOVERABLE;
        };
    }

    /**
     * Read-only damage classification from the parser's issue messages and the
     * bounded structural observations, sorted and de-duplicated.
     */
    public List<String> damageCategories() {
        java.util.TreeSet<String> categories = new java.util.TreeSet<>();
        if (objectStreamInvalidCount > 0) {
            categories.add("object_stream");
        }
        if (pageTreeCycles > 0 || pageTreeDuplicateRefs > 0
                || pageTreeBrokenParents > 0 || pageTreeTruncated) {
            categories.add("page_tree");
        }
        for (String issue : issues) {
            if (issue == null) {
                continue;
            }
            String m = issue.toLowerCase(java.util.Locale.ROOT);
            boolean matched = false;
            if (m.contains("startxref")) {
                categories.add("startxref");
                matched = true;
            }
            if (m.contains("xref")) {
                categories.add("xref");
                matched = true;
            }
            if (m.contains("objstm") || m.contains("object stream") || m.contains("object-stream")) {
                categories.add("object_stream");
                matched = true;
            }
            if (m.contains("stream length") || m.contains("/length") || m.contains("length")) {
                categories.add("stream_length");
                matched = true;
            }
            if (m.contains("trailer")) {
                categories.add("trailer");
                matched = true;
            }
            if (m.contains("encrypt")) {
                categories.add("encryption");
                matched = true;
            }
            if (m.contains("page tree") || m.contains("page-tree") || m.contains("/pages")) {
                categories.add("page_tree");
                matched = true;
            }
            if (m.contains("eof") || m.contains("truncat")) {
                categories.add("truncated");
                matched = true;
            }
            if (!matched) {
                categories.add("other");
            }
        }
        return List.copyOf(categories);
    }

    /** Compact one-line summary for logs and diagnostics. */
    public String describe() {
        List<String> damage = damageCategories();
        return "status=" + (status.isEmpty() ? "unknown" : status)
                + " strategy=" + strategy().name().toLowerCase(java.util.Locale.ROOT)
                + " warnings=" + warningCount
                + " pages=declared:" + declaredPageCount + "/enumerated:" + pageCount
                + " xref_original=" + (xrefOriginalValid == null ? "unknown" : xrefOriginalValid)
                + " trailer_original=" + (trailerOriginalValid == null ? "unknown" : trailerOriginalValid)
                + " load_recovered=" + loadRecovered
                + " objstm=invalid:" + objectStreamInvalidCount + "/examined:" + objectStreamCount
                + " page_tree=cycles:" + pageTreeCycles + ",dupes:" + pageTreeDuplicateRefs
                + ",broken_parents:" + pageTreeBrokenParents + ",reachable:" + pageTreeReachable
                + (pageTreeTruncated ? ",truncated" : "")
                + (issues.isEmpty() ? "" : " issues=" + issues.size())
                + (damage.isEmpty() ? "" : " damage=" + damage);
    }
}

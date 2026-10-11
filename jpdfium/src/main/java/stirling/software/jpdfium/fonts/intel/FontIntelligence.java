package stirling.software.jpdfium.fonts.intel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.model.ShapedGlyph;
import stirling.software.jpdfium.panama.FontLib;

/**
 * The font-intelligence entry point.
 *
 * <p>One subsystem, reused by text editing, semantic repair, archival conversion
 * and document export: extract evidence once, decide capability, produce a plan,
 * apply it, and reuse shaping/subsetting rather than reimplementing them per
 * feature.
 *
 * <p>Evidence is collected per page and is bounded. All native work runs through
 * {@link FontLib}, which keeps PDFium access on the FFM boundary.
 */
public final class FontIntelligence {

    private FontIntelligence() {}

    /**
     * Which font operations the loaded native bridge actually implements. Callers
     * must consult this before treating a successful repair call with a zero count
     * as proof that nothing needed changing.
     */
    public static FontCapabilities capabilities() {
        return FontCapabilities.detect();
    }

    /** Collect typed, provenance-carrying evidence for every font used on a page. */
    public static List<FontEvidence> inspectPage(PdfDocument document, int pageIndex) {
        Objects.requireNonNull(document, "document");
        if (pageIndex < 0 || pageIndex >= document.pageCount()) {
            throw new IllegalArgumentException("pageIndex out of range: " + pageIndex);
        }
        String json = FontLib.evidence(document.nativeHandle(), pageIndex);
        return FontEvidence.fromJson(json, pageIndex);
    }

    /** Build a document-local font catalog across all pages. */
    public static FontCatalog inspect(PdfDocument document) {
        return FontCatalog.inspect(document);
    }

    /**
     * Editor-readiness preflight for one font resource on a page: the three
     * capability levels and the strategy a caller would need, without emitting
     * anything. Engine capability is independent of the per-font outcome.
     */
    public static EditReadiness editability(PdfDocument document, int pageIndex, String resourceName) {
        boolean engine = capabilities().unicodeRepair();
        for (FontEvidence e : inspectPage(document, pageIndex)) {
            if (e.resourceName().equals(resourceName)) {
                return EditReadiness.of(e, engine);
            }
        }
        throw new IllegalArgumentException(
                "no font resource " + resourceName + " on page " + pageIndex);
    }

    /** Editor-readiness preflight for every font resource on a page, in page order. */
    public static List<EditReadiness> editability(PdfDocument document, int pageIndex) {
        boolean engine = capabilities().unicodeRepair();
        List<EditReadiness> out = new ArrayList<>();
        for (FontEvidence e : inspectPage(document, pageIndex)) {
            out.add(EditReadiness.of(e, engine));
        }
        return out;
    }

    /**
     * Derive the editor-facing capability of a font resource from its evidence.
     *
     * <p>Conservative rules: trustworthy declared semantics permit reuse of the
     * original font; otherwise reconstruction is offered when every non-ambiguous
     * code was recovered. Ambiguous codes do not force a human; unresolved ones do.
     */
    public static FontCapability capability(FontEvidence e) {
        Objects.requireNonNull(e, "evidence");
        if (e.kind() == FontKind.UNKNOWN) {
            return FontCapability.UNSUPPORTED_OPERATION;
        }
        // Reuse requires a declared /ToUnicode entry that is also structurally
        // usable; a validity flag alone, with no entry present, is not enough.
        if (e.hasToUnicode() && e.toUnicodeValid()) {
            return FontCapability.ORIGINAL_REUSABLE;
        }
        if (e.mappingCount() > 0) {
            // A declared map we could not parse must not be overwritten, and a
            // truncated scan cannot justify a complete-map claim.
            if (isUnsupportedMap(e.toUnicodeState()) || e.mappingTruncated()) {
                return FontCapability.MANUAL_CONFIRMATION_REQUIRED;
            }
            return e.unresolvedCount() == 0
                    ? FontCapability.MAPPING_REPAIR_AVAILABLE
                    : FontCapability.MANUAL_CONFIRMATION_REQUIRED;
        }
        // No observed mapping: a declared map we could not confirm, or none.
        if (e.hasToUnicode() || e.unresolvedCount() > 0) {
            return FontCapability.MANUAL_CONFIRMATION_REQUIRED;
        }
        return FontCapability.REPLACEMENT_REQUIRED;
    }

    /** A declared /ToUnicode we recognised but cannot parse or preserve. */
    private static boolean isUnsupportedMap(String state) {
        return "unsupported".equals(state) || "malformed".equals(state);
    }

    /** Repair plans justified by one font's evidence (empty when none apply). */
    public static List<FontRepairPlan> repairPlans(FontEvidence e) {
        FontCapability cap = capability(e);
        if (cap != FontCapability.MAPPING_REPAIR_AVAILABLE) {
            return List.of();
        }
        String rationale = "Reconstruct /ToUnicode from " + e.mappingCount()
                + " evidence-backed code mappings ("
                + e.kind().name().toLowerCase(Locale.ROOT) + ")";
        return List.of(new FontRepairPlan(
                e.pageIndex(),
                e.resourceName(),
                e.baseFont(),
                cap,
                FontRepairPlan.ACTION_FIX_TOUNICODE,
                e.resolvedMappings(),
                rationale,
                stateFingerprint(e)));
    }

    /**
     * Opaque identity of the evidence a plan was derived from: declared-map state,
     * mapping counts, encoding, and font-program identity (base name, program size,
     * glyph count). Any drift in these makes a prepared plan stale.
     */
    private static String stateFingerprint(FontEvidence e) {
        return e.kind() + "|" + e.hasToUnicode() + "|" + e.toUnicodeValid() + "|"
                + e.toUnicodeState() + "|" + e.mappingCount() + "|" + e.unresolvedCount()
                + "|" + e.ambiguousCount() + "|" + e.encoding() + "|" + e.baseFont()
                + "|" + e.glyphBytes() + "|" + e.numGlyphs();
    }

    /** Repair plans for a whole document, in page order. */
    public static List<FontRepairPlan> repairPlans(PdfDocument document) {
        Objects.requireNonNull(document, "document");
        List<FontRepairPlan> plans = new ArrayList<>();
        for (FontEvidence e : FontCatalog.inspect(document)) {
            plans.addAll(repairPlans(e));
        }
        return plans;
    }

    /**
     * Apply one plan to the document it was prepared against.
     *
     * <p>The native {@code /ToUnicode} repair is page-wide: it repairs every
     * eligible font resource on the page, not only the planned one. A single plan
     * is therefore refused when its page carries any other pending repair, so a
     * repair never changes a font the caller did not authorise. Use
     * {@link #applyRepairs(PdfDocument, List)} to authorise the whole page
     * explicitly.
     *
     * @throws StaleRepairPlanException when the plan is stale or the page is not
     *     fully authorised
     */
    public static int applyRepair(PdfDocument document, FontRepairPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return applyRepairs(document, List.of(plan));
    }

    /**
     * Apply an explicit set of plans, one page at a time.
     *
     * <p>Every plan must still match the current document, and the set must list
     * every pending repair on each page it touches, so the page-wide native
     * operation is exactly as wide as the authorisation and no other resource is
     * changed. Pages are repaired in ascending order; a stale or under-specified
     * page throws before that page is written.
     *
     * @return total number of font resources repaired
     * @throws StaleRepairPlanException when a plan is stale or a page is not fully
     *     authorised
     */
    public static int applyRepairs(PdfDocument document, List<FontRepairPlan> plans) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(plans, "plans");

        Map<Integer, List<FontRepairPlan>> byPage = new LinkedHashMap<>();
        for (FontRepairPlan plan : plans) {
            if (plan.mutates()) {
                byPage.computeIfAbsent(plan.pageIndex(), k -> new ArrayList<>()).add(plan);
            }
        }

        int total = 0;
        for (Map.Entry<Integer, List<FontRepairPlan>> entry : byPage.entrySet()) {
            int pageIndex = entry.getKey();
            Set<String> authorised = new HashSet<>();
            for (FontRepairPlan plan : entry.getValue()) {
                requireFresh(document, plan);
                authorised.add(plan.resourceName());
            }
            for (FontEvidence e : inspectPage(document, pageIndex)) {
                if (!authorised.contains(e.resourceName()) && !repairPlans(e).isEmpty()) {
                    throw new StaleRepairPlanException("page " + pageIndex
                            + " has a pending repair for font resource " + e.resourceName()
                            + " that the plan set does not authorise");
                }
            }
            total += repairPage(document, pageIndex);
        }
        return total;
    }

    /** Refuse a plan whose evidence no longer matches the current document. */
    private static void requireFresh(PdfDocument document, FontRepairPlan plan) {
        if (plan.pageIndex() < 0 || plan.pageIndex() >= document.pageCount()) {
            throw new StaleRepairPlanException(
                    "plan targets page " + plan.pageIndex() + " which is not present");
        }
        String expected = plan.fingerprint();
        for (FontEvidence e : inspectPage(document, plan.pageIndex())) {
            if (!e.resourceName().equals(plan.resourceName())) {
                continue;
            }
            for (FontRepairPlan current : repairPlans(e)) {
                if (current.fingerprint().equals(expected)) {
                    return;
                }
            }
            throw new StaleRepairPlanException("font resource " + plan.resourceName()
                    + " on page " + plan.pageIndex() + " changed since the plan was prepared");
        }
        throw new StaleRepairPlanException("font resource " + plan.resourceName() + " on page "
                + plan.pageIndex() + " is no longer present");
    }

    /** Repair one page's {@code /ToUnicode} where evidence supports it. */
    public static int repairPage(PdfDocument document, int pageIndex) {
        Objects.requireNonNull(document, "document");
        if (pageIndex < 0 || pageIndex >= document.pageCount()) {
            throw new IllegalArgumentException("pageIndex out of range: " + pageIndex);
        }
        int fixed = FontLib.fixToUnicode(document.nativeHandle(), pageIndex);
        document.refreshRawHandle();
        return fixed;
    }

    /** Repair every page's {@code /ToUnicode} where evidence supports it. */
    public static int repairToUnicode(PdfDocument document) {
        Objects.requireNonNull(document, "document");
        int fixed = 0;
        for (int i = 0; i < document.pageCount(); i++) {
            fixed += FontLib.fixToUnicode(document.nativeHandle(), i);
        }
        document.refreshRawHandle();
        return fixed;
    }

    /** Per-codepoint coverage of a font program; drives rewrite-vs-replace choices. */
    public static boolean[] coverage(byte[] fontData, int[] codepoints) {
        return FontLib.coversText(fontData, codepoints);
    }

    /**
     * Shape text with a font program and return positioned glyphs.
     *
     * <p>This shapes one run: callers split mixed-direction or mixed-script text
     * before calling, using ICU-derived boundaries.
     */
    public static List<ShapedGlyph> shape(byte[] fontData, String text, float fontSize) {
        return FontLib.shapeText(fontData, text, fontSize);
    }

    /**
     * Subset a font program to the glyphs needed for {@code codepoints}.
     *
     * @param retainGids keep original glyph identifiers (larger output) so
     *     existing glyph mappings stay valid
     */
    public static byte[] subset(byte[] fontData, int[] codepoints, boolean retainGids) {
        return FontLib.subset(fontData, codepoints, retainGids);
    }
}

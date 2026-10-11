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
import stirling.software.jpdfium.util.NativeJsonParser;

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

    /**
     * Collect evidence for every page from a single document view, analysing each
     * distinct font program once rather than once per page. The result equals the
     * concatenation of {@link #inspectPage} for every page, in page order.
     */
    public static List<FontEvidence> inspectPages(PdfDocument document) {
        Objects.requireNonNull(document, "document");
        int pages = document.pageCount();
        if (pages == 0) {
            return List.of();
        }
        int[] indices = new int[pages];
        for (int i = 0; i < pages; i++) {
            indices[i] = i;
        }
        String json = FontLib.evidencePages(document.nativeHandle(), indices);
        return FontEvidence.listFromJson(json);
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
                stateFingerprint(e),
                "",
                List.of()));
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

    /**
     * Repair plans for a whole document, in page order.
     *
     * <p>Uses a single evidence batch and a single plan batch, so the whole
     * document is planned from two native snapshot operations regardless of the
     * page count rather than two per page.
     */
    public static List<FontRepairPlan> repairPlans(PdfDocument document) {
        Objects.requireNonNull(document, "document");
        if (document.pageCount() == 0) {
            return List.of();
        }
        Map<Integer, Map<String, FontEvidence>> evidenceByPage = new LinkedHashMap<>();
        for (FontEvidence e : inspectPages(document)) {
            evidenceByPage
                    .computeIfAbsent(e.pageIndex(), k -> new LinkedHashMap<>())
                    .put(e.resourceName(), e);
        }
        NativeDocumentPlan documentPlan = parseDocumentPlan(FontLib.planPages(document.nativeHandle()));
        List<FontRepairPlan> plans = new ArrayList<>();
        for (Map.Entry<Integer, NativePlan> entry : documentPlan.byPage().entrySet()) {
            plans.addAll(plansFrom(
                    evidenceByPage.getOrDefault(entry.getKey(), Map.of()),
                    entry.getValue(),
                    documentPlan.referencesByObject(),
                    entry.getKey()));
        }
        return plans;
    }

    /**
     * Repair plans for one page, derived from the native mutation plan so the
     * authorised set equals exactly what the page-wide fixer would change.
     *
     * <p>Unlike {@link #repairPlans(FontEvidence)}, which only offers
     * reconstruction of an absent map, this also yields a plan for a resource
     * whose declared map is valid but partial and would be extended with the
     * missing page-used codes.
     */
    public static List<FontRepairPlan> repairPlans(PdfDocument document, int pageIndex) {
        Objects.requireNonNull(document, "document");
        Map<String, FontEvidence> byResource = new LinkedHashMap<>();
        for (FontEvidence e : inspectPage(document, pageIndex)) {
            byResource.put(e.resourceName(), e);
        }
        NativeDocumentPlan plan = parseDocumentPlan(FontLib.planJson(document.nativeHandle(), pageIndex));
        NativePlan pagePlan = plan.byPage().get(pageIndex);
        if (pagePlan == null) {
            return List.of();
        }
        return plansFrom(byResource, pagePlan, plan.referencesByObject(), pageIndex);
    }

    /**
     * Build authorisable plans from one page's evidence and its native plan rows.
     * The plan fields are identical whether the rows came from the single-page or
     * the batched planner, because both derive from the same native decision. Each
     * repair row names the font object it mutates; the affected-reference set is
     * the document-wide reference table for that object.
     */
    private static List<FontRepairPlan> plansFrom(Map<String, FontEvidence> byResource,
            NativePlan plan, Map<String, List<String>> referencesByObject, int pageIndex) {
        List<FontRepairPlan> plans = new ArrayList<>();
        for (Map<String, String> row : plan.repairs()) {
            String resource = row.get("resource");
            FontEvidence e = byResource.get(resource);
            if (e == null) {
                continue;
            }
            String rationale = "extend".equals(row.get("mode"))
                    ? "Extend /ToUnicode with " + row.getOrDefault("additions", "?")
                            + " missing page-used code mappings"
                    : "Reconstruct /ToUnicode from " + e.mappingCount()
                            + " evidence-backed code mappings";
            plans.add(new FontRepairPlan(
                    pageIndex,
                    resource,
                    e.baseFont(),
                    capability(e),
                    FontRepairPlan.ACTION_FIX_TOUNICODE,
                    e.resolvedMappings(),
                    rationale,
                    stateFingerprint(e),
                    row.getOrDefault("identity", ""),
                    referencesByObject.getOrDefault(row.getOrDefault("object", ""), List.of())));
        }
        return plans;
    }

    /**
     * Structured reasons the native planner withheld a repair on this page, in
     * the order the resources appear. Only skips carrying a recognised
     * {@link FontDiagnostic} become findings; an unclassified skip stays out.
     */
    public static List<FontDiagnosticFinding> diagnostics(PdfDocument document, int pageIndex) {
        Objects.requireNonNull(document, "document");
        if (pageIndex < 0 || pageIndex >= document.pageCount()) {
            throw new IllegalArgumentException("pageIndex out of range: " + pageIndex);
        }
        List<FontDiagnosticFinding> out = new ArrayList<>();
        appendDiagnostics(pageIndex, nativeSkips(document, pageIndex), out);
        return out;
    }

    /** Structured reasons for every page, in page order, from one batch plan. */
    public static List<FontDiagnosticFinding> diagnostics(PdfDocument document) {
        Objects.requireNonNull(document, "document");
        NativeDocumentPlan plan = parseDocumentPlan(FontLib.planPages(document.nativeHandle()));
        List<FontDiagnosticFinding> out = new ArrayList<>();
        for (Map.Entry<Integer, NativePlan> entry : plan.byPage().entrySet()) {
            appendDiagnostics(entry.getKey(), entry.getValue().skipped(), out);
        }
        return out;
    }

    /** The native plan's withheld resources for one page. */
    private static List<Map<String, String>> nativeSkips(PdfDocument document, int pageIndex) {
        NativeDocumentPlan plan = parseDocumentPlan(FontLib.planJson(document.nativeHandle(), pageIndex));
        NativePlan pagePlan = plan.byPage().get(pageIndex);
        return pagePlan == null ? List.of() : pagePlan.skipped();
    }

    private static void appendDiagnostics(int pageIndex, List<Map<String, String>> skips,
            List<FontDiagnosticFinding> out) {
        for (Map<String, String> row : skips) {
            FontDiagnostic.fromCode(row.get("code")).ifPresent(diagnostic -> out.add(
                    new FontDiagnosticFinding(
                            pageIndex, row.getOrDefault("resource", ""), diagnostic)));
        }
    }

    /**
     * Every reference in the document that reaches each font object, keyed by the
     * native object identity. A shared font dictionary is mutated for every one of
     * them at once, so a plan must carry the full set and the caller must
     * authorise all of it. References are scope-qualified (a form XObject's
     * {@code /LocalFont} is distinct from a page's own {@code /LocalFont}).
     */
    private static Map<String, List<String>> referencesByObject(List<Map<String, String>> references) {
        Map<String, List<String>> byObject = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, String> ref : references) {
            String object = ref.get("object");
            if (object == null || object.isEmpty()) {
                continue;
            }
            String key = FontRepairPlan.referenceKey(
                    parsePageIndex(ref.get("page_index")),
                    ref.getOrDefault("scope_path", ""),
                    ref.get("resource"));
            if (seen.add(object + "\u0000" + key)) {
                byObject.computeIfAbsent(object, k -> new ArrayList<>()).add(key);
            }
        }
        return byObject;
    }

    private static int parsePageIndex(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException | NullPointerException e) {
            return -1;
        }
    }

    private static List<Map<String, String>> jsonArray(String json, String key) {
        int start = json.indexOf(key);
        if (start < 0) {
            return List.of();
        }
        int from = json.indexOf('[', start);
        int end = json.indexOf(']', from);
        if (from < 0 || end < 0) {
            return List.of();
        }
        return NativeJsonParser.parseArray(json.substring(from, end + 1));
    }

    /** The bracketed array following {@code key}, with nesting and strings tracked. */
    private static String arraySlice(String json, String key) {
        int start = json.indexOf(key);
        if (start < 0) {
            return "[]";
        }
        int from = json.indexOf('[', start + key.length());
        if (from < 0) {
            return "[]";
        }
        int depth = 0;
        boolean inString = false;
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return json.substring(from, i + 1);
                }
            }
        }
        return "[]";
    }

    private static boolean jsonBool(String json, String key, boolean fallback) {
        int at = json.indexOf(key);
        if (at < 0) {
            return fallback;
        }
        return json.startsWith("true", at + key.length());
    }

    /**
     * One page's native plan rows, parsed once so a caller reads repairs and
     * skips from the same snapshot instead of re-invoking the planner.
     * {@code referencesComplete} is false when the page's font-reference graph
     * could not be read exactly and object-wide mutation must be refused.
     */
    private record NativePlan(
            List<Map<String, String>> repairs,
            List<Map<String, String>> skipped,
            boolean referencesComplete) {

        static NativePlan parse(String pageObject) {
            return new NativePlan(
                    jsonArray(pageObject, "\"repairs\":["),
                    jsonArray(pageObject, "\"skipped\":[") ,
                    jsonBool(pageObject, "\"references_complete\":", true));
        }
    }

    /**
     * A whole-document native plan: one {@link NativePlan} per page plus the
     * document-wide font-object reference table, parsed from a single batch
     * planner call so references are serialized once per object.
     */
    private record NativeDocumentPlan(
            Map<Integer, NativePlan> byPage,
            Map<String, List<String>> referencesByObject) {}

    private static NativeDocumentPlan parseDocumentPlan(String json) {
        Map<Integer, NativePlan> byPage = new LinkedHashMap<>();
        for (String pageObject : jsonTopLevelObjects(arraySlice(json, "\"pages\":"))) {
            int pageIndex = pageIndexOf(pageObject);
            if (pageIndex >= 0) {
                byPage.put(pageIndex, NativePlan.parse(pageObject));
            }
        }
        return new NativeDocumentPlan(
                byPage, referencesByObject(jsonArray(json, "\"references\":[")));
    }

    /**
     * Split a top-level JSON array into its object elements. Only brace depth and
     * string escapes are tracked, which is enough for the flat, brace-free rows
     * the native planner emits.
     */
    private static List<String> jsonTopLevelObjects(String json) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    /** The page_index scalar of a native plan object, or -1 when absent. */
    private static int pageIndexOf(String planObject) {
        String key = "\"page_index\":";
        int at = planObject.indexOf(key);
        if (at < 0) {
            return -1;
        }
        int from = at + key.length();
        int to = from;
        while (to < planObject.length() && (Character.isDigit(planObject.charAt(to))
                || planObject.charAt(to) == '-')) {
            to++;
        }
        return parsePageIndex(planObject.substring(from, to));
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
     * Apply an explicit set of plans against one document view, in a single write.
     *
     * <p>Every plan must still match the current document, and the set must list
     * every pending repair on each page it touches, so the page-wide native
     * operation is exactly as wide as the authorisation and no other resource is
     * changed. Every page is validated before any is written; a stale or
     * under-specified page throws before the document is mutated. The pages are
     * then repaired together, analysing each distinct font program once and
     * reloading the document one time.
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
        if (byPage.isEmpty()) {
            return 0;
        }

        // One batch plan for the whole document validates every touched page from
        // a single snapshot operation, instead of re-planning each page.
        NativeDocumentPlan documentPlan = parseDocumentPlan(FontLib.planPages(document.nativeHandle()));
        Map<Integer, NativePlan> nativePlans = documentPlan.byPage();

        // Consent is separate from eligibility. A plan authorises its own
        // reference; a reference that some other page reaches through the same
        // shared font object but which needs no repair of its own has no mutating
        // plan to name it, so the plan that reaches it consents to it. A reference
        // that does have a document-wide repair but was not passed as a plan is
        // still refused, so a partially authorised object stays refused.
        Set<String> documentMutatingRefs = new HashSet<>();
        for (Map.Entry<Integer, NativePlan> page : nativePlans.entrySet()) {
            for (Map<String, String> row : page.getValue().repairs()) {
                documentMutatingRefs.add(
                        FontRepairPlan.referenceKey(page.getKey(), row.get("resource")));
            }
        }
        Set<String> authorisedRefs = new HashSet<>();
        for (FontRepairPlan plan : plans) {
            if (plan.mutates()) {
                authorisedRefs.add(
                        FontRepairPlan.referenceKey(plan.pageIndex(), "", plan.resourceName()));
            }
        }
        Set<String> consentedRefs = new HashSet<>(authorisedRefs);
        for (FontRepairPlan plan : plans) {
            if (plan.mutates()) {
                for (String ref : plan.affectedReferences()) {
                    if (!documentMutatingRefs.contains(ref)) {
                        consentedRefs.add(ref);
                    }
                }
            }
        }
        for (FontRepairPlan plan : plans) {
            if (!plan.mutates()) {
                continue;
            }
            requireFresh(document, plan, documentPlan);
            for (String ref : plan.affectedReferences()) {
                if (!consentedRefs.contains(ref)) {
                    int sep = ref.indexOf('\u0000');
                    String shared = sep < 0 ? ref : ref.substring(sep + 1);
                    throw new StaleRepairPlanException("font resource " + plan.resourceName()
                            + " on page " + plan.pageIndex()
                            + " is shared with another reference (" + shared
                            + ") that the plan set does not authorise");
                }
            }
        }

        for (Map.Entry<Integer, List<FontRepairPlan>> entry : byPage.entrySet()) {
            int pageIndex = entry.getKey();
            NativePlan pagePlan = nativePlans.get(pageIndex);
            if (pagePlan != null && !pagePlan.referencesComplete()) {
                throw new StaleRepairPlanException("page " + pageIndex
                        + " reaches a font through a scope that cannot be enumerated,"
                        + " so its shared-font scope is incomplete and no write"
                        + " is authorised");
            }
            Set<String> authorised = new HashSet<>();
            for (FontRepairPlan plan : entry.getValue()) {
                authorised.add(plan.resourceName());
            }
            List<Map<String, String>> repairs = pagePlan == null ? List.of() : pagePlan.repairs();
            for (Map<String, String> row : repairs) {
                if (!authorised.contains(row.get("resource"))) {
                    throw new StaleRepairPlanException("page " + pageIndex
                            + " has a pending repair for font resource " + row.get("resource")
                            + " that the plan set does not authorise");
                }
            }
        }

        int[] pages = byPage.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        int total = FontLib.fixToUnicodePages(document.nativeHandle(), pages);
        document.refreshRawHandle();
        return total;
    }

    /**
     * Refuse a plan whose native identity no longer matches the current document,
     * validating against the already-fetched batch plan for the plan's page.
     */
    private static void requireFresh(PdfDocument document, FontRepairPlan plan,
            NativeDocumentPlan documentPlan) {
        if (plan.pageIndex() < 0 || plan.pageIndex() >= document.pageCount()) {
            throw new StaleRepairPlanException(
                    "plan targets page " + plan.pageIndex() + " which is not present");
        }
        if (plan.nativeIdentity().isEmpty()) {
            throw new StaleRepairPlanException("plan for font resource " + plan.resourceName()
                    + " on page " + plan.pageIndex()
                    + " was not prepared against the native plan and cannot authorise a write");
        }
        NativePlan pagePlan = documentPlan.byPage().get(plan.pageIndex());
        List<Map<String, String>> repairs = pagePlan == null ? List.of() : pagePlan.repairs();
        boolean present = false;
        String object = "";
        for (Map<String, String> row : repairs) {
            if (!plan.resourceName().equals(row.get("resource"))) {
                continue;
            }
            present = true;
            if (!plan.nativeIdentity().equals(row.getOrDefault("identity", ""))) {
                throw new StaleRepairPlanException("font resource " + plan.resourceName()
                        + " on page " + plan.pageIndex()
                        + " changed since the plan was prepared");
            }
            object = row.getOrDefault("object", "");
        }
        if (!present) {
            throw new StaleRepairPlanException("font resource " + plan.resourceName() + " on page "
                    + plan.pageIndex() + " is no longer present");
        }
        List<String> currentAffected =
                documentPlan.referencesByObject().getOrDefault(object, List.of());
        if (!new HashSet<>(currentAffected).equals(new HashSet<>(plan.affectedReferences()))) {
            throw new StaleRepairPlanException("the shared-font references of " + plan.resourceName()
                    + " on page " + plan.pageIndex() + " changed since the plan was prepared");
        }
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

    /** Repair every page's {@code /ToUnicode} from one view and one write. */
    public static int repairToUnicode(PdfDocument document) {
        Objects.requireNonNull(document, "document");
        int pages = document.pageCount();
        if (pages == 0) {
            return 0;
        }
        int[] indices = new int[pages];
        for (int i = 0; i < pages; i++) {
            indices[i] = i;
        }
        int fixed = FontLib.fixToUnicodePages(document.nativeHandle(), indices);
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

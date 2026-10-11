package stirling.software.jpdfium.fonts.intel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import stirling.software.jpdfium.util.NativeJsonParser;

/**
 * A bounded, evidence-backed snapshot of one font resource as used on a page.
 *
 * <p>This is a snapshot, not a live handle into an engine: it captures the
 * identity, the embedded program facts, the declared/recovered mapping state,
 * and the per-code mappings with provenance.
 *
 * <p>Character codes, CIDs, glyph identifiers and Unicode scalars are kept as
 * separate concepts. {@link #mappings()} is the only place a code becomes a
 * Unicode scalar, and only with an explicit {@link FontProvenance}.
 *
 * @param pageIndex zero-based page the evidence was collected from
 * @param resourceName font resource key in the page {@code /Resources /Font} dict
 * @param baseFont the {@code /BaseFont} name as written in the PDF
 * @param family font family name recovered from the embedded program (may be empty)
 * @param subtype PDF {@code /Subtype} (e.g. {@code TrueType}, {@code Type0})
 * @param kind simple / composite / unknown classification
 * @param embedded true when an embedded font program was found
 * @param sfnt true when the program is an SFNT (TrueType/OpenType) container
 * @param cff true when the program carries CFF/CFF2 outlines
 * @param cid true when the resource is a composite (CID) font
 * @param subset true when the base font name carries a subset prefix
 * @param hasCmap true when the program exposes a character map
 * @param numGlyphs number of glyphs in the program (0 when unknown)
 * @param unitsPerEm design units per em (0 when unknown)
 * @param glyphBytes size in bytes of the embedded font program (0 when not embedded)
 * @param hasToUnicode true when a {@code /ToUnicode} entry is present
 * @param toUnicodeValid true when that entry is structurally usable
 * @param encoding declared encoding name (may be empty)
 * @param embeddingFsType raw {@code OS/2} {@code fsType} word, or {@code -1} if unknown
 * @param mappings per-code mappings recovered for this resource
 * @param mappingTruncated true when a bounded scan (charmap walk or code cap)
 *     stopped early, so {@link #mappings()} is incomplete
 */
public record FontEvidence(
        int pageIndex,
        String resourceName,
        String baseFont,
        String family,
        String subtype,
        FontKind kind,
        boolean embedded,
        boolean sfnt,
        boolean cff,
        boolean cid,
        boolean subset,
        boolean hasCmap,
        int numGlyphs,
        int unitsPerEm,
        long glyphBytes,
        boolean hasToUnicode,
        boolean toUnicodeValid,
        String encoding,
        int embeddingFsType,
        List<UnicodeMapping> mappings,
        boolean mappingTruncated) {

    public FontEvidence {
        resourceName = resourceName == null ? "" : resourceName;
        baseFont = baseFont == null ? "" : baseFont;
        family = family == null ? "" : family;
        subtype = subtype == null ? "" : subtype;
        kind = kind == null ? FontKind.UNKNOWN : kind;
        encoding = encoding == null ? "" : encoding;
        mappings = mappings == null ? List.of() : List.copyOf(mappings);
    }

    /** Embedding/subsetting rights decoded from the {@code fsType} word. */
    public FontEmbedding embedding() {
        return new FontEmbedding(embeddingFsType);
    }

    /** Number of codes with a trustworthy Unicode scalar. */
    public int mappingCount() {
        int n = 0;
        for (UnicodeMapping m : mappings) {
            if (m.resolved()) {
                n++;
            }
        }
        return n;
    }

    /**
     * Number of codes that could not be resolved at all. Ambiguous codes are
     * excluded: that is a known, preserved state, not an unknown one.
     */
    public int unresolvedCount() {
        int n = 0;
        for (UnicodeMapping m : mappings) {
            if (!m.resolved() && m.provenance() != FontProvenance.AMBIGUOUS) {
                n++;
            }
        }
        return n;
    }

    /**
     * Number of codes where more than one Unicode scalar reaches one glyph; the
     * ambiguity is preserved and must be confirmed out of band.
     */
    public int ambiguousCount() {
        int n = 0;
        for (UnicodeMapping m : mappings) {
            if (m.provenance() == FontProvenance.AMBIGUOUS) {
                n++;
            }
        }
        return n;
    }

    /** Only the mappings that carry a concrete Unicode scalar. */
    public List<UnicodeMapping> resolvedMappings() {
        List<UnicodeMapping> out = new ArrayList<>();
        for (UnicodeMapping m : mappings) {
            if (m.resolved()) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * Parse the flat JSON produced by {@code jpdfium_font_evidence}.
     *
     * <p>The native payload is {@code {"page_index":N,"fonts":[...],"mappings":[...]}}
     * with every object kept flat, because the shared {@link NativeJsonParser}
     * only understands brace-free object literals. Mapping rows are joined to
     * their font row by {@code resource}.
     */
    public static List<FontEvidence> fromJson(String json, int pageIndex) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<Map<String, String>> fontRows = arraySegment(json, "\"fonts\":", "\"mappings\":");
        List<Map<String, String>> mappingRows = arraySegment(json, "\"mappings\":", null);

        java.util.Map<String, List<UnicodeMapping>> byResource = new java.util.LinkedHashMap<>();
        for (Map<String, String> row : mappingRows) {
            String resource = row.getOrDefault("resource", "");
            int code = parseInt(row.get("code"), 0);
            int uni = parseInt(row.get("unicode"), -1);
            FontProvenance prov = FontProvenance.fromName(row.get("provenance"));
            if (uni < 0 && prov.resolved()) {
                prov = FontProvenance.UNRESOLVED;
            }
            byResource.computeIfAbsent(resource, k -> new ArrayList<>())
                    .add(new UnicodeMapping(code, uni, prov, row.getOrDefault("note", "")));
        }

        List<FontEvidence> out = new ArrayList<>(fontRows.size());
        for (Map<String, String> row : fontRows) {
            String resource = row.getOrDefault("resource", "");
            out.add(new FontEvidence(
                    pageIndex,
                    resource,
                    row.getOrDefault("base_font", ""),
                    row.getOrDefault("family", ""),
                    row.getOrDefault("subtype", ""),
                    FontKind.fromName(row.get("kind")),
                    asBool(row.get("embedded")),
                    asBool(row.get("sfnt")),
                    asBool(row.get("cff")),
                    asBool(row.get("cid")),
                    asBool(row.get("subset")),
                    asBool(row.get("has_cmap")),
                    parseInt(row.get("num_glyphs"), 0),
                    parseInt(row.get("units_per_em"), 0),
                    parseLong(row.get("glyph_bytes"), 0L),
                    asBool(row.get("has_tounicode")),
                    asBool(row.get("tounicode_valid")),
                    row.getOrDefault("encoding", ""),
                    parseInt(row.get("embedding_permission"), -1),
                    byResource.getOrDefault(resource, List.of()),
                    asBool(row.get("mapping_truncated"))));
        }
        return out;
    }

    private static List<Map<String, String>> arraySegment(
            String json, String key, String nextKey) {
        int k = json.indexOf(key);
        if (k < 0) {
            return List.of();
        }
        int open = json.indexOf('[', k + key.length());
        if (open < 0) {
            return List.of();
        }
        int close;
        if (nextKey != null) {
            int nk = json.indexOf(nextKey, open);
            close = nk < 0 ? json.lastIndexOf(']') : json.lastIndexOf(']', nk);
        } else {
            close = json.lastIndexOf(']');
        }
        if (close <= open) {
            return List.of();
        }
        return NativeJsonParser.parseArray(json.substring(open, close + 1));
    }

    private static boolean asBool(String v) {
        return v != null && v.trim().equalsIgnoreCase("true");
    }

    private static int parseInt(String v, int fallback) {
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String v, long fallback) {
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

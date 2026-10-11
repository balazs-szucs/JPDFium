package stirling.software.jpdfium.fonts.intel;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import stirling.software.jpdfium.PdfDocument;

/**
 * A document-local inventory of font resources and their evidence.
 *
 * <p>Fonts are cached by program identity (embedded bytes, glyph count, base
 * name) rather than display name, so two differently named resources that share
 * one embedded program are recognised as the same font.
 *
 * <p>A catalog holds bounded evidence snapshots only; it never retains engine
 * state or a full document model.
 */
public final class FontCatalog implements Iterable<FontEvidence> {

    private final List<FontEvidence> fonts;

    private FontCatalog(List<FontEvidence> fonts) {
        this.fonts = List.copyOf(fonts);
    }

    /** Build a catalog from already-collected evidence. */
    public static FontCatalog of(List<FontEvidence> all) {
        return new FontCatalog(all == null ? List.of() : all);
    }

    /** Inspect every page of a document and collect per-resource evidence. */
    public static FontCatalog inspect(PdfDocument document) {
        Objects.requireNonNull(document, "document");
        List<FontEvidence> all = new ArrayList<>();
        int pages = document.pageCount();
        for (int i = 0; i < pages; i++) {
            all.addAll(FontIntelligence.inspectPage(document, i));
        }
        return new FontCatalog(all);
    }

    /** All per-resource evidence, in page/resource order (may repeat a font). */
    public List<FontEvidence> fonts() {
        return fonts;
    }

    /**
     * Distinct fonts keyed by program identity, with per-code mappings merged.
     *
     * <p>Two resources that share an embedded program collapse into one entry;
     * mappings are unioned by code, preferring an already-resolved mapping.
     */
    public List<FontEvidence> distinct() {
        Map<String, FontEvidence> byIdentity = new LinkedHashMap<>();
        for (FontEvidence e : fonts) {
            byIdentity.merge(identity(e), e, FontCatalog::merge);
        }
        return List.copyOf(byIdentity.values());
    }

    /** Distinct fonts whose missing semantics can be reconstructed from evidence. */
    public List<FontEvidence> requiringRepair() {
        List<FontEvidence> out = new ArrayList<>();
        for (FontEvidence e : distinct()) {
            if (FontIntelligence.capability(e) == FontCapability.MAPPING_REPAIR_AVAILABLE) {
                out.add(e);
            }
        }
        return out;
    }

    /** Number of distinct font programs. */
    public int size() {
        return distinct().size();
    }

    public boolean isEmpty() {
        return fonts.isEmpty();
    }

    @Override
    public Iterator<FontEvidence> iterator() {
        return fonts.iterator();
    }

    private static String identity(FontEvidence e) {
        return e.baseFont() + '\u0000' + e.glyphBytes() + '\u0000' + e.numGlyphs();
    }

    private static FontEvidence merge(FontEvidence a, FontEvidence b) {
        Map<Integer, UnicodeMapping> byCode = new LinkedHashMap<>();
        for (UnicodeMapping m : a.mappings()) {
            byCode.put(m.code(), m);
        }
        for (UnicodeMapping m : b.mappings()) {
            byCode.merge(m.code(), m, (x, y) -> x.resolved() ? x : y);
        }
        return new FontEvidence(
                a.pageIndex(), a.resourceName(), a.baseFont(), a.family(), a.subtype(),
                a.kind(), a.embedded(), a.sfnt(), a.cff(), a.cid(), a.subset(), a.hasCmap(),
                a.numGlyphs(), a.unitsPerEm(), a.glyphBytes(), a.hasToUnicode(), a.toUnicodeValid(),
                a.encoding(), a.embeddingFsType(), new ArrayList<>(byCode.values()),
                a.mappingTruncated() || b.mappingTruncated(),
                mergeState(a.toUnicodeState(), b.toUnicodeState()));
    }

    /** Combine declared-map states; an unsupported/malformed side wins. */
    private static String mergeState(String x, String y) {
        if (x.equals("unsupported") || x.equals("malformed")) {
            return x;
        }
        if (y.equals("unsupported") || y.equals("malformed")) {
            return y;
        }
        return x.isEmpty() ? y : x;
    }
}

package stirling.software.jpdfium.fonts.intel;

import java.util.ArrayList;
import java.util.Comparator;
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
 * name) <em>and</em> by the mapping semantics that program is used with, so two
 * differently named resources that share one embedded program are recognised as
 * the same font only when they also carry the same code mappings. A shared
 * program does not imply a shared meaning: the same bytes can be referenced with
 * different /Encoding and /ToUnicode entries.
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
        return new FontCatalog(FontIntelligence.inspectPages(document));
    }

    /** All per-resource evidence, in page/resource order (may repeat a font). */
    public List<FontEvidence> fonts() {
        return fonts;
    }

    /**
     * Distinct fonts keyed by program identity and mapping semantics.
     *
     * <p>Two resources that share an embedded program <em>and</em> the same code
     * mappings collapse into one entry. Resources that share only the program are
     * kept apart: their per-code mappings are never unioned, because the same
     * bytes can be referenced with different encoding semantics.
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
        StringBuilder key = new StringBuilder();
        key.append(e.baseFont()).append('\u0000')
                .append(e.glyphBytes()).append('\u0000')
                .append(e.numGlyphs()).append('\u0000')
                .append(e.encoding()).append('\u0000')
                .append(e.hasToUnicode()).append(e.toUnicodeValid()).append('\u0000')
                .append(e.toUnicodeState());
        // A shared program is not a shared meaning: include the code mappings so
        // resources that reuse one embedded font with different /Encoding or
        // /ToUnicode semantics are not collapsed together.
        List<UnicodeMapping> sorted = new ArrayList<>(e.mappings());
        sorted.sort(Comparator.comparingInt(UnicodeMapping::code));
        for (UnicodeMapping m : sorted) {
            key.append('\u0001').append(m.code()).append(':').append(m.unicode())
                    .append(':').append(m.provenance()).append(':').append(m.resolved());
        }
        return key.toString();
    }

    private static FontEvidence merge(FontEvidence a, FontEvidence b) {
        // Keyed by identity, so the two sides agree on program and mappings; only
        // widen the truncation flag and combine declared-map states conservatively.
        if (a.mappingTruncated() == b.mappingTruncated()
                && a.toUnicodeState().equals(b.toUnicodeState())) {
            return a;
        }
        return new FontEvidence(
                a.pageIndex(), a.resourceName(), a.baseFont(), a.family(), a.subtype(),
                a.kind(), a.embedded(), a.sfnt(), a.cff(), a.cid(), a.subset(), a.hasCmap(),
                a.numGlyphs(), a.unitsPerEm(), a.glyphBytes(), a.hasToUnicode(), a.toUnicodeValid(),
                a.encoding(), a.embeddingFsType(), a.mappings(),
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

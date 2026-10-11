/**
 * Font intelligence: evidence-backed font/text semantics reused across editing,
 * repair, archival conversion and document export.
 *
 * <p>This package turns a page's font resources into bounded, typed
 * {@link stirling.software.jpdfium.fonts.intel.FontEvidence} snapshots, records
 * how each character code maps to Unicode (with
 * {@link stirling.software.jpdfium.fonts.intel.FontProvenance}), classifies the
 * editor-facing {@link stirling.software.jpdfium.fonts.intel.FontCapability},
 * and prepares {@link stirling.software.jpdfium.fonts.intel.FontRepairPlan}s that
 * can be applied to the exact document state they were built from.
 *
 * <p>Character codes, CIDs, glyph identifiers and Unicode scalars are kept as
 * distinct concepts; embedding restrictions decoded from {@code OS/2 fsType}
 * are never cleared.
 *
 * @see stirling.software.jpdfium.fonts.intel.FontIntelligence
 */
package stirling.software.jpdfium.fonts.intel;

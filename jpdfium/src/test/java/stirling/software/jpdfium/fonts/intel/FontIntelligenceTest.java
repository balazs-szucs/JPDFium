package stirling.software.jpdfium.fonts.intel;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-Java tests for the font-intelligence types: no native library required.
 *
 * <p>These pin the semantic contract that the native bridge feeds: codes are
 * only turned into Unicode with an explicit provenance, capability is derived
 * conservatively, and the catalog keys fonts by program identity rather than
 * display name.
 */
class FontIntelligenceTest {

    // --- provenance ---------------------------------------------------------

    @Test
    void provenanceResolvedOnlyForDeclaredOrRecovered() {
        assertTrue(FontProvenance.DECLARED.resolved());
        assertTrue(FontProvenance.RECOVERED.resolved());
        assertFalse(FontProvenance.AMBIGUOUS.resolved());
        assertFalse(FontProvenance.UNRESOLVED.resolved());
        assertFalse(FontProvenance.INVALID.resolved());
        assertFalse(FontProvenance.UNSUPPORTED.resolved());
    }

    @Test
    void provenanceParsesTokensAndDefaultsToUnresolved() {
        assertEquals(FontProvenance.RECOVERED, FontProvenance.fromName("recovered"));
        assertEquals(FontProvenance.DECLARED, FontProvenance.fromName("DECLARED"));
        assertEquals(FontProvenance.AMBIGUOUS, FontProvenance.fromName(" Ambiguous "));
        assertEquals(FontProvenance.UNRESOLVED, FontProvenance.fromName(null));
        assertEquals(FontProvenance.UNRESOLVED, FontProvenance.fromName("nonsense"));
    }

    // --- mappings -----------------------------------------------------------

    @Test
    void mappingIsResolvedOnlyWithProvenanceAndCodePoint() {
        assertTrue(new UnicodeMapping(65, 0x41, FontProvenance.RECOVERED, "n").resolved());
        assertTrue(new UnicodeMapping(65, 0x41, FontProvenance.DECLARED, "n").resolved());
        // Ambiguous provenance never counts as resolved even with a value.
        assertFalse(new UnicodeMapping(65, 0x41, FontProvenance.AMBIGUOUS, "n").resolved());
    }

    @Test
    void mappingRejectsResolvedWithoutCodePoint() {
        assertThrows(IllegalArgumentException.class,
                () -> new UnicodeMapping(1, -1, FontProvenance.RECOVERED, "n"));
        assertThrows(IllegalArgumentException.class,
                () -> new UnicodeMapping(-1, 65, FontProvenance.RECOVERED, "n"));
        assertThrows(NullPointerException.class,
                () -> new UnicodeMapping(1, 65, null, "n"));
    }

    @Test
    void unresolvedFactoryCarriesNoCodePoint() {
        UnicodeMapping m = UnicodeMapping.unresolved(3, "differences:notdef");
        assertFalse(m.resolved());
        assertEquals(-1, m.unicode());
        assertEquals(FontProvenance.UNRESOLVED, m.provenance());
    }

    // --- embedding rights ---------------------------------------------------

    @Test
    void embeddingPermissionDecodedFromFsType() {
        assertEquals(FontEmbedding.Permission.UNKNOWN, new FontEmbedding(-1).permission());
        assertEquals(FontEmbedding.Permission.INSTALLABLE, new FontEmbedding(0).permission());
        assertEquals(FontEmbedding.Permission.RESTRICTED,
                new FontEmbedding(FontEmbedding.FSTYPE_RESTRICTED).permission());
        assertEquals(FontEmbedding.Permission.PREVIEW_AND_PRINT,
                new FontEmbedding(FontEmbedding.FSTYPE_PREVIEW_AND_PRINT).permission());
        assertEquals(FontEmbedding.Permission.EDITABLE,
                new FontEmbedding(FontEmbedding.FSTYPE_EDITABLE).permission());
        assertEquals(FontEmbedding.Permission.BITMAP_ONLY,
                new FontEmbedding(FontEmbedding.FSTYPE_BITMAP_ONLY).permission());
    }

    @Test
    void embeddingFlagsAreIndependentBits() {
        FontEmbedding e = new FontEmbedding(FontEmbedding.FSTYPE_EDITABLE
                | FontEmbedding.FSTYPE_NO_SUBSETTING);
        assertEquals(FontEmbedding.Permission.EDITABLE, e.permission());
        assertTrue(e.noSubsetting());
        assertFalse(e.bitmapOnly());
        assertTrue(e.allowsEditableEmbedding());

        FontEmbedding restricted = new FontEmbedding(FontEmbedding.FSTYPE_RESTRICTED);
        assertFalse(restricted.allowsEditableEmbedding());
    }

    // --- parsing the native flat payload ------------------------------------

    private static final String PAYLOAD = "{\"page_index\":0,\"fonts\":["
            + "{\"resource\":\"F1\",\"base_font\":\"ABCDEF+Arial\",\"family\":\"Arial\","
            + "\"subtype\":\"TrueType\",\"kind\":\"simple\",\"embedded\":true,\"sfnt\":true,"
            + "\"cff\":false,\"cid\":false,\"subset\":true,\"has_cmap\":true,\"num_glyphs\":256,"
            + "\"units_per_em\":2048,\"glyph_bytes\":12345,\"has_tounicode\":false,"
            + "\"tounicode_valid\":false,\"encoding\":\"WinAnsiEncoding\","
            + "\"embedding_permission\":8,\"mapping_count\":2,\"unresolved_count\":0},"
            + "{\"resource\":\"F2\",\"base_font\":\"Type0\",\"family\":\"Song\","
            + "\"subtype\":\"Type0\",\"kind\":\"composite\",\"embedded\":true,\"sfnt\":true,"
            + "\"cff\":false,\"cid\":true,\"subset\":false,\"has_cmap\":true,\"num_glyphs\":900,"
            + "\"units_per_em\":1000,\"glyph_bytes\":40000,\"has_tounicode\":true,"
            + "\"tounicode_valid\":true,\"encoding\":\"Identity-H\",\"embedding_permission\":-1,"
            + "\"mapping_count\":1,\"unresolved_count\":1}"
            + "],\"mappings\":["
            + "{\"resource\":\"F1\",\"code\":65,\"unicode\":65,\"provenance\":\"RECOVERED\","
            + "\"note\":\"encoding:WinAnsiEncoding\"},"
            + "{\"resource\":\"F1\",\"code\":66,\"unicode\":66,\"provenance\":\"RECOVERED\","
            + "\"note\":\"encoding:WinAnsiEncoding\"},"
            + "{\"resource\":\"F2\",\"code\":1,\"unicode\":65,\"provenance\":\"RECOVERED\","
            + "\"note\":\"cidToGid:identity\"},"
            + "{\"resource\":\"F2\",\"code\":2,\"unicode\":-1,\"provenance\":\"UNRESOLVED\","
            + "\"note\":\"cidToGid:identity\"}"
            + "]}";

    @Test
    void fromJsonJoinsMappingsToFontsByResource() {
        List<FontEvidence> fonts = FontEvidence.fromJson(PAYLOAD, 0);
        assertEquals(2, fonts.size());

        FontEvidence simple = fonts.get(0);
        assertEquals("F1", simple.resourceName());
        assertEquals("ABCDEF+Arial", simple.baseFont());
        assertEquals("Arial", simple.family());
        assertEquals(FontKind.SIMPLE, simple.kind());
        assertTrue(simple.embedded());
        assertTrue(simple.sfnt());
        assertTrue(simple.subset());
        assertTrue(simple.hasCmap());
        assertEquals(256, simple.numGlyphs());
        assertEquals(2048, simple.unitsPerEm());
        assertEquals(12345L, simple.glyphBytes());
        assertFalse(simple.hasToUnicode());
        assertFalse(simple.toUnicodeValid());
        assertEquals("WinAnsiEncoding", simple.encoding());
        assertEquals(FontEmbedding.FSTYPE_EDITABLE, simple.embedding().fsType());
        assertEquals(2, simple.mappings().size());
        assertEquals(2, simple.mappingCount());
        assertEquals(0, simple.unresolvedCount());
        assertTrue(simple.embedding().allowsEditableEmbedding());

        FontEvidence composite = fonts.get(1);
        assertEquals(FontKind.COMPOSITE, composite.kind());
        assertTrue(composite.cid());
        assertTrue(composite.toUnicodeValid());
        assertEquals(1, composite.mappingCount());
        assertEquals(1, composite.unresolvedCount());
        // Unknown embedding fsType (-1) survives the round trip.
        assertEquals(FontEmbedding.Permission.UNKNOWN, composite.embedding().permission());
    }

    @Test
    void fromJsonToleratesMissingOrEmptyInput() {
        assertTrue(FontEvidence.fromJson(null, 0).isEmpty());
        assertTrue(FontEvidence.fromJson("   ", 0).isEmpty());
        assertTrue(FontEvidence.fromJson("{}", 0).isEmpty());
    }

    // --- capability derivation ----------------------------------------------

    private static FontEvidence evidence(
            FontKind kind, boolean toUnicodeValid, List<UnicodeMapping> mappings) {
        return new FontEvidence(0, "F1", "Base", "Fam", "TrueType", kind,
                true, true, false, false, false, true, 100, 1000, 500,
                toUnicodeValid, toUnicodeValid, "WinAnsiEncoding", 0, mappings);
    }

    @Test
    void capabilityUnknownKindIsUnsupported() {
        assertEquals(FontCapability.UNSUPPORTED_OPERATION,
                FontIntelligence.capability(evidence(FontKind.UNKNOWN, false, List.of())));
    }

    @Test
    void capabilityValidToUnicodeReusesOriginal() {
        assertEquals(FontCapability.ORIGINAL_REUSABLE,
                FontIntelligence.capability(
                        evidence(FontKind.SIMPLE, true, List.of(UnicodeMapping.unresolved(1, "x")))));
    }

    @Test
    void capabilityFullyRecoveredOffersRepair() {
        List<UnicodeMapping> m = List.of(
                new UnicodeMapping(65, 65, FontProvenance.RECOVERED, "n"),
                new UnicodeMapping(66, 66, FontProvenance.RECOVERED, "n"));
        assertEquals(FontCapability.MAPPING_REPAIR_AVAILABLE,
                FontIntelligence.capability(evidence(FontKind.SIMPLE, false, m)));
    }

    @Test
    void capabilityPartialRecoveryNeedsConfirmation() {
        List<UnicodeMapping> m = List.of(
                new UnicodeMapping(65, 65, FontProvenance.RECOVERED, "n"),
                UnicodeMapping.unresolved(66, "n"));
        assertEquals(FontCapability.MANUAL_CONFIRMATION_REQUIRED,
                FontIntelligence.capability(evidence(FontKind.SIMPLE, false, m)));
    }

    @Test
    void capabilityNoEvidenceAtAllNeedsReplacement() {
        assertEquals(FontCapability.REPLACEMENT_REQUIRED,
                FontIntelligence.capability(evidence(FontKind.SIMPLE, false, List.of())));
    }

    @Test
    void repairPlanOnlyForRepairableFonts() {
        List<UnicodeMapping> m = List.of(new UnicodeMapping(65, 65, FontProvenance.RECOVERED, "n"));
        var repairable = evidence(FontKind.SIMPLE, false, m);
        List<FontRepairPlan> plans = FontIntelligence.repairPlans(repairable);
        assertEquals(1, plans.size());
        FontRepairPlan plan = plans.get(0);
        assertEquals(FontRepairPlan.ACTION_FIX_TOUNICODE, plan.action());
        assertTrue(plan.mutates());
        assertEquals(1, plan.mappings().size());

        var alreadyOk = evidence(FontKind.SIMPLE, true, m);
        assertTrue(FontIntelligence.repairPlans(alreadyOk).isEmpty());
    }

    // --- catalog identity ---------------------------------------------------

    @Test
    void catalogKeysByProgramIdentityNotResourceName() {
        FontEvidence a = new FontEvidence(0, "F1", "Base", "Fam", "TrueType", FontKind.SIMPLE,
                true, true, false, false, false, true, 100, 1000, 500, false, false,
                "WinAnsiEncoding", 0,
                List.of(new UnicodeMapping(65, 65, FontProvenance.RECOVERED, "n")));
        // Same program identity, different resource name, different code.
        FontEvidence b = new FontEvidence(1, "F9", "Base", "Fam", "TrueType", FontKind.SIMPLE,
                true, true, false, false, false, true, 100, 1000, 500, false, false,
                "WinAnsiEncoding", 0,
                List.of(new UnicodeMapping(66, 66, FontProvenance.RECOVERED, "n")));

        FontCatalog catalog = FontCatalog.of(List.of(a, b));
        assertEquals(2, catalog.fonts().size());
        assertEquals(1, catalog.size(), "same program identity collapses to one entry");
        assertEquals(1, catalog.distinct().size());
        FontEvidence merged = catalog.distinct().get(0);
        assertEquals(2, merged.mappings().size(), "mappings are unioned by code");
        assertEquals(1, catalog.requiringRepair().size());
    }

    @Test
    void catalogDistinctKeepsDifferentProgramsSeparate() {
        FontEvidence a = new FontEvidence(0, "F1", "Base", "Fam", "TrueType", FontKind.SIMPLE,
                true, true, false, false, false, true, 100, 1000, 500, false, false,
                "WinAnsiEncoding", 0, List.of());
        FontEvidence b = new FontEvidence(0, "F2", "Other", "Fam", "TrueType", FontKind.SIMPLE,
                true, true, false, false, false, true, 100, 1000, 400, false, false,
                "WinAnsiEncoding", 0, List.of());
        FontCatalog catalog = FontCatalog.of(List.of(a, b));
        assertEquals(2, catalog.size());
        assertFalse(catalog.isEmpty());
        assertEquals(0, catalog.requiringRepair().size());
    }
}

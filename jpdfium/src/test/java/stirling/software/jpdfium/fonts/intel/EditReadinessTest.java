package stirling.software.jpdfium.fonts.intel;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Editor-readiness preflight: the three capability levels must stay distinct. */
class EditReadinessTest {

    private static FontEvidence evidence(
            FontKind kind, boolean toUnicodeValid, List<UnicodeMapping> mappings) {
        return new FontEvidence(0, "F1", "Base", "Fam", "Type0", kind,
                true, true, false, kind == FontKind.COMPOSITE, false, true, 100, 1000, 500,
                toUnicodeValid, toUnicodeValid, "Identity-H", 0, mappings);
    }

    @Test
    void originalReusableIsNotCertifiedEmittable() {
        EditReadiness r = EditReadiness.of(
                evidence(FontKind.COMPOSITE, true,
                        List.of(new UnicodeMapping(1, 0x41, FontProvenance.DECLARED, ""))),
                true);
        assertEquals(FontCapability.ORIGINAL_REUSABLE, r.capability());
        assertTrue(r.engineCapable());
        assertTrue(r.fontCapable());
        assertFalse(r.runEmittable());
        assertEquals(EditReadiness.STRATEGY_ORIGINAL, r.strategy());
        assertEquals("", r.unsupportedReason());
    }

    @Test
    void mappingRepairStrategyWhenRecovered() {
        EditReadiness r = EditReadiness.of(
                evidence(FontKind.COMPOSITE, false,
                        List.of(new UnicodeMapping(1, 0x41, FontProvenance.RECOVERED, ""))),
                true);
        assertEquals(FontCapability.MAPPING_REPAIR_AVAILABLE, r.capability());
        assertFalse(r.runEmittable());
        assertEquals(EditReadiness.STRATEGY_REPAIR_MAPPING, r.strategy());
        assertEquals(1, r.recoveredCount());
    }

    @Test
    void ambiguousOnlyDoesNotForceManual() {
        EditReadiness r = EditReadiness.of(
                evidence(FontKind.COMPOSITE, false,
                        List.of(new UnicodeMapping(3, 0, FontProvenance.AMBIGUOUS, "n"),
                                new UnicodeMapping(4, 0x41, FontProvenance.RECOVERED, "n"))),
                true);
        assertEquals(FontCapability.MAPPING_REPAIR_AVAILABLE, r.capability());
        assertEquals(1, r.ambiguousCount());
        assertEquals(0, r.unresolvedCount());
        assertFalse(r.runEmittable());
    }

    @Test
    void engineCapabilityIsIndependentOfFontOutcome() {
        EditReadiness r = EditReadiness.of(
                evidence(FontKind.COMPOSITE, false,
                        List.of(new UnicodeMapping(1, 0x41, FontProvenance.RECOVERED, ""))),
                false);
        assertFalse(r.engineCapable());
        assertEquals(FontCapability.MAPPING_REPAIR_AVAILABLE, r.capability());
        assertTrue(r.fontCapable());
        assertFalse(r.runEmittable());
    }

    @Test
    void unknownKindIsUnsupportedAndNotEmittable() {
        EditReadiness r = EditReadiness.of(evidence(FontKind.UNKNOWN, false, List.of()), true);
        assertEquals(FontCapability.UNSUPPORTED_OPERATION, r.capability());
        assertFalse(r.fontCapable());
        assertFalse(r.runEmittable());
        assertEquals("", r.strategy());
        assertFalse(r.unsupportedReason().isEmpty());
    }

    @Test
    void unresolvedForcesManualConfirmation() {
        EditReadiness r = EditReadiness.of(
                evidence(FontKind.COMPOSITE, false,
                        List.of(new UnicodeMapping(9, 0, FontProvenance.UNRESOLVED, "n"))),
                true);
        assertEquals(FontCapability.MANUAL_CONFIRMATION_REQUIRED, r.capability());
        assertFalse(r.runEmittable());
        assertEquals(EditReadiness.STRATEGY_MANUAL, r.strategy());
    }

    @Test
    void describeIsCompactAndTruthful() {
        EditReadiness r = EditReadiness.of(
                evidence(FontKind.COMPOSITE, false,
                        List.of(new UnicodeMapping(1, 0x41, FontProvenance.RECOVERED, ""))),
                true);
        String s = r.describe();
        assertTrue(s.contains("mapping_repair_available"), s);
        assertTrue(s.contains("engine=true"), s);
        assertTrue(s.contains("font=true"), s);
        assertTrue(s.contains("run=false"), s);
        assertTrue(s.contains("recovered=1"), s);
    }
}

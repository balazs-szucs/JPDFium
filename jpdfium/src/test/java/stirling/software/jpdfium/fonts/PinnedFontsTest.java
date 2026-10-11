package stirling.software.jpdfium.fonts;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pinned fonts are only useful if they fail closed: a missing resource, a
 * hash mismatch or an unknown id must abort the test run rather than let a font
 * test pass or skip against bytes nobody vouched for.
 */
class PinnedFontsTest {

    @Test
    void pinnedFontsArePresentAndByteDistinct() {
        Path primary = PinnedFonts.path("ahem");
        Path second = PinnedFonts.path("ahem-two");
        assertTrue(Files.isReadable(primary), "primary pinned font must be materialised");
        assertTrue(Files.isReadable(second), "second pinned font must be materialised");
        assertNotEquals(primary, second, "the two pinned programs must be distinct files");
    }

    @Test
    void pairIsAlwaysTwoDistinctPrograms() {
        var pair = PinnedFonts.pair();
        assertTrue(pair.size() >= 2, "pair() must yield two fonts");
        assertNotEquals(pair.get(0), pair.get(1), "pair() must yield byte-distinct programs");
    }

    @Test
    void unknownPinnedFontIsRejected() {
        assertThrows(AssertionError.class, () -> PinnedFonts.path("no-such-pinned-font"),
                "an id that is not in the manifest must abort, never silently succeed");
    }
}

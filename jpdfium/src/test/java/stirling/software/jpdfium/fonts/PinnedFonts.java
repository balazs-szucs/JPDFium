package stirling.software.jpdfium.fonts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.jpdfium.util.NativeJsonParser;

/**
 * Fonts the font tests are allowed to use. They are pinned test resources with a
 * recorded SHA-256 in {@code /font-corpus/fonts.json}; if a resource is missing
 * or its bytes no longer match the recorded hash the tests fail instead of
 * skipping. That is deliberate: a test that quietly assumes a host font is
 * present proves nothing on a machine that lacks one.
 *
 * <p>Both pinned programs are byte-distinct redistributable builds of the
 * public-domain Ahem test font. Set {@code JPDFIUM_TEST_FONT} to substitute a
 * local font for exploratory runs; the pinned default is used otherwise.
 */
public final class PinnedFonts {

    private static final String MANIFEST = "/font-corpus/fonts.json";
    private static final String RESOURCE_DIR = "/font-corpus/fonts/";

    private static final Map<String, Path> MATERIALIZED = new HashMap<>();

    private PinnedFonts() {}

    /** The pinned font that single-font tests must use. Never returns null. */
    public static Path primary() {
        String override = System.getenv("JPDFIUM_TEST_FONT");
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override);
            if (Files.isReadable(path)) {
                return path;
            }
        }
        return path("ahem");
    }

    /**
     * Two byte-distinct pinned programs for the tests that need two fonts on one
     * page. Never returns fewer than two and never returns null.
     */
    public static List<Path> pair() {
        String override = System.getenv("JPDFIUM_TEST_FONT");
        if (override != null && !override.isBlank()) {
            Path first = Path.of(override);
            if (Files.isReadable(first)) {
                Path second = path("ahem-two");
                if (!first.equals(second)) {
                    List<Path> pair = new ArrayList<>(2);
                    pair.add(first);
                    pair.add(second);
                    return List.copyOf(pair);
                }
            }
        }
        return List.of(path("ahem"), path("ahem-two"));
    }

    /** Materialises the pinned font {@code id}, verifying its recorded hash first. */
    public static synchronized Path path(String id) {
        Path cached = MATERIALIZED.get(id);
        if (cached != null) {
            return cached;
        }
        Map<String, String> entry = entry(id);
        String file = entry.get("file");
        if (file == null || file.isBlank()) {
            throw new AssertionError("pinned font '" + id + "' in " + MANIFEST + " has no file");
        }
        byte[] bytes = read(file);
        String actual = sha256(bytes);
        String expected = entry.get("sha256");
        if (expected == null || !expected.equalsIgnoreCase(actual)) {
            throw new AssertionError(
                    "pinned font '" + id + "' (" + file + ") hashes to " + actual
                            + " but " + MANIFEST + " records " + expected
                            + "; refusing to run with a font we cannot vouch for");
        }
        try {
            Path temp = Files.createTempFile("jpdfium-pinned-", "-" + file);
            temp.toFile().deleteOnExit();
            Files.write(temp, bytes);
            MATERIALIZED.put(id, temp);
            return temp;
        } catch (IOException e) {
            throw new AssertionError("could not materialise pinned font " + file, e);
        }
    }

    private static Map<String, String> entry(String id) {
        for (Map<String, String> candidate : manifest()) {
            if (id.equals(candidate.get("id"))) {
                return candidate;
            }
        }
        throw new AssertionError("no pinned font '" + id + "' in " + MANIFEST);
    }

    private static List<Map<String, String>> manifest() {
        try (InputStream in = PinnedFonts.class.getResourceAsStream(MANIFEST)) {
            if (in == null) {
                throw new AssertionError("missing pinned-font manifest " + MANIFEST);
            }
            return NativeJsonParser.parseArray(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError("could not read " + MANIFEST, e);
        }
    }

    private static byte[] read(String file) {
        try (InputStream in = PinnedFonts.class.getResourceAsStream(RESOURCE_DIR + file)) {
            if (in == null) {
                throw new AssertionError("pinned font resource missing: " + RESOURCE_DIR + file);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new AssertionError("could not read pinned font " + file, e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is unavailable", e);
        }
    }
}

package stirling.software.jpdfium.host;

import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end exercise of the {@code fonts.*} host operations over the real
 * AF_UNIX framed-JSON protocol, launched in-process. Verifies the Stirling-PDF
 * facing surface actually returns typed font evidence and reports repairing.
 */
class HostFontOpsTest {

    private static final String TOKEN = "test-token-" + UUID.randomUUID();

    @TempDir
    Path tempDir;

    private static byte[] fixture(String resource) throws Exception {
        try (InputStream in = HostFontOpsTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing test resource " + resource);
            return in.readAllBytes();
        }
    }

    @Test
    void fontsInspectAndRepairOverTheWire() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");

        Path pdfPath = tempDir.resolve("basic-text.pdf");
        Files.write(pdfPath, fixture("/pdfs/general/basic-text.pdf"));
        Path socketPath = tempDir.resolve("host.sock");

        Thread server = new Thread(() -> {
            try {
                HostMain.main(new String[] {
                        "--socket", socketPath.toString(),
                        "--token", TOKEN,
                        "--renderer", "agg"
                });
            } catch (Throwable ignored) {
                // The server thread is a daemon; failures surface as client timeouts.
            }
        }, "jpdfium-host-test");
        server.setDaemon(true);
        server.start();

        long deadline = System.currentTimeMillis() + 15_000;
        while (!Files.exists(socketPath) && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assumeTrue(Files.exists(socketPath), "host did not start in time");

        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socketPath));

            // hello (required first).
            Map<String, Object> hello = call(channel, "1", "hello",
                    Map.of("token", TOKEN));
            assertEquals("1", hello.get("id"));
            @SuppressWarnings("unchecked")
            Map<String, Object> helloResult = (Map<String, Object>) hello.get("result");
            assertNotNull(helloResult.get("host_instance"));

            // capabilities advertises the new ops and the qpdf-backed writer.
            @SuppressWarnings("unchecked")
            Map<String, Object> caps = (Map<String, Object>) call(channel, "2", "capabilities",
                    Map.of()).get("result");
            assertEquals(Boolean.TRUE, caps.get("has_qpdf"));
            @SuppressWarnings("unchecked")
            Map<String, Object> features = (Map<String, Object>) caps.get("features");
            assertEquals(Boolean.TRUE, features.get("fonts.inspect"));
            assertEquals(Boolean.TRUE, features.get("fonts.repair"));
            assertEquals(Boolean.TRUE, features.get("fonts.coverage"));
            assertEquals(Boolean.TRUE, features.get("fonts.shape_run"));
            assertEquals(Boolean.TRUE, features.get("fonts.subset"));
            assertEquals(Boolean.TRUE, features.get("fonts.readiness"));

            // The capability map is truthful about what is actually implemented.
            @SuppressWarnings("unchecked")
            Map<String, Object> fontCaps = (Map<String, Object>) caps.get("font_capabilities");
            assertNotNull(fontCaps);
            assertEquals("freetype+harfbuzz+qpdf", fontCaps.get("backend"));
            assertEquals(Boolean.TRUE, fontCaps.get("evidence"));
            assertEquals(Boolean.TRUE, fontCaps.get("unicode_repair"));
            assertEquals(Boolean.TRUE, fontCaps.get("shaping"));
            assertEquals(Boolean.TRUE, fontCaps.get("coverage"));
            assertEquals(Boolean.TRUE, fontCaps.get("subsetting"));
            // QPDF availability is reported independently of the font features.
            assertEquals(Boolean.TRUE, fontCaps.get("qpdf"));
            // Not-yet-implemented operations must not be advertised.
            assertEquals(Boolean.FALSE, fontCaps.get("width_repair"));
            assertEquals(Boolean.FALSE, fontCaps.get("type1_conversion"));
            assertEquals(Boolean.FALSE, fontCaps.get("glyph_resubset"));

            // open the document.
            @SuppressWarnings("unchecked")
            Map<String, Object> open = (Map<String, Object>) call(channel, "3", "document.open",
                    Map.of("path", pdfPath.toString())).get("result");
            String handle = (String) open.get("handle");
            assertNotNull(handle);

            // fonts.inspect on page 1.
            @SuppressWarnings("unchecked")
            Map<String, Object> inspect = (Map<String, Object>) call(channel, "4", "fonts.inspect",
                    Map.of("handle", handle, "page", 1)).get("result");
            int count = ((Number) inspect.get("count")).intValue();
            assertTrue(count >= 1, "expected at least one font on page 1");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fonts = (List<Map<String, Object>>) inspect.get("fonts");
            assertEquals(count, fonts.size());
            Map<String, Object> first = fonts.get(0);
            assertNotNull(first.get("base_font"));
            assertNotNull(first.get("capability"));
            assertNotNull(first.get("kind"));

            // fonts.inspect across all pages returns the deduplicated catalog.
            @SuppressWarnings("unchecked")
            Map<String, Object> all = (Map<String, Object>) call(channel, "5", "fonts.inspect",
                    Map.of("handle", handle)).get("result");
            assertTrue(((Number) all.get("count")).intValue() >= 1);

            // fonts.repair on page 1 is well-formed and non-throwing.
            @SuppressWarnings("unchecked")
            Map<String, Object> repair = (Map<String, Object>) call(channel, "6", "fonts.repair",
                    Map.of("handle", handle, "page", 1)).get("result");
            assertTrue(((Number) repair.get("fixed")).intValue() >= 0);

            // fonts.readiness reports the three capability levels per resource.
            @SuppressWarnings("unchecked")
            Map<String, Object> readiness = (Map<String, Object>) call(channel, "6a", "fonts.readiness",
                    Map.of("handle", handle, "page", 1)).get("result");
            int rcount = ((Number) readiness.get("count")).intValue();
            assertTrue(rcount >= 1, "expected at least one readiness row on page 1");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) readiness.get("readiness");
            assertEquals(rcount, rows.size());
            Map<String, Object> r0 = rows.get(0);
            assertNotNull(r0.get("capability"));
            assertNotNull(r0.get("strategy"));
            assertEquals(Boolean.TRUE, r0.get("engine_capable"));

            // fonts.capabilities mirrors the capability map advertised at hello.
            @SuppressWarnings("unchecked")
            Map<String, Object> standaloneCaps = (Map<String, Object>) call(channel, "6b",
                    "fonts.capabilities", Map.of()).get("result");
            assertEquals(fontCaps, standaloneCaps);

            // Unknown op is a protocol error, not a crash.
            Map<String, Object> bad = call(channel, "7", "fonts.nope", Map.of());
            assertNotNull(bad.get("error"));

            // close.
            call(channel, "8", "document.close", Map.of("handle", handle));
        }
    }

    // ------------------------------------------------------------- framing

    private static Map<String, Object> call(
            SocketChannel channel, String id, String op, Map<String, Object> params) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", id);
        request.put("op", op);
        request.put("params", params);
        writeFrame(channel, Json.write(request));
        String response = readFrame(channel);
        assertNotNull(response, "no response for op " + op);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) Json.parse(response);
        return parsed;
    }

    private static void writeFrame(SocketChannel channel, String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(4 + body.length);
        buf.putInt(body.length);
        buf.put(body);
        buf.flip();
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
    }

    private static String readFrame(SocketChannel channel) throws Exception {
        ByteBuffer len = ByteBuffer.allocate(4);
        if (!readFully(channel, len)) {
            return null;
        }
        len.flip();
        int length = len.getInt();
        ByteBuffer body = ByteBuffer.allocate(length);
        if (!readFully(channel, body)) {
            return null;
        }
        return new String(body.array(), StandardCharsets.UTF_8);
    }

    private static boolean readFully(SocketChannel channel, ByteBuffer buf) throws Exception {
        while (buf.hasRemaining()) {
            if (channel.read(buf) < 0) {
                return false;
            }
        }
        return true;
    }
}

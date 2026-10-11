package stirling.software.jpdfium.host;

import java.awt.image.BufferedImage;
import java.io.EOFException;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PageBoxes;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.exception.PdfCorruptException;
import stirling.software.jpdfium.exception.PdfPasswordException;
import stirling.software.jpdfium.fonts.intel.EditReadiness;
import stirling.software.jpdfium.fonts.intel.FontCapabilities;
import stirling.software.jpdfium.fonts.intel.FontCapability;
import stirling.software.jpdfium.fonts.intel.FontEvidence;
import stirling.software.jpdfium.fonts.intel.FontIntelligence;
import stirling.software.jpdfium.fonts.intel.FontRepairPlan;
import stirling.software.jpdfium.fonts.intel.UnicodeMapping;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.model.ShapedGlyph;
import stirling.software.jpdfium.panama.JpdfiumLib;

/**
 * Minimal, optional JPDFium host for the PyJPDFium bridge (protocol v1).
 *
 * <p>Renders pages and reports geometry over a private local (AF_UNIX) channel,
 * reusing the existing JPDFium execution domain. It implements only:
 *
 * <pre>
 *   hello            authenticate (token) + capability report
 *   health           liveness probe
 *   capabilities     capability report
 *   document.open    open a PDF by path -> opaque session-scoped handle
 *   document.close   close a handle
 *   document.save    write a handle's current state to a path
 *   page.geometry    page width/height in points + boxes
 *   page.render      rasterise one page to a file (staged, then published)
 *   fonts.inspect    typed font evidence (identity, mappings, capability)
 *   fonts.coverage   which code points a font program can render
 *   fonts.shape_run  shape a text run into positioned glyphs
 *   fonts.subset     subset a font program to a code-point set
 *   fonts.repair     reconstruct /ToUnicode from evidence where possible
 *   fonts.readiness  editor-readiness preflight for font resources
 * </pre>
 *
 * <p>OCR and native text-layer generation are reported as unavailable. Every
 * PDFium call goes through JPDFium's own API, which serialises on
 * {@code PdfiumRuntime}; this host never calls {@code jpdfium_init} itself and
 * never introduces a second execution backend.
 *
 * <p>Usage:
 * {@code java ... HostMain --socket <path> --token <token> [--renderer agg|skia]}
 */
public final class HostMain {

    private static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;
    private static final String HOST_VERSION = "1.0.0-SNAPSHOT";

    private final String token;
    private final String hostInstance = UUID.randomUUID().toString();
    private final Map<String, PdfDocument> documentsById = new ConcurrentHashMap<>();

    private HostMain(String token) {
        this.token = token;
    }

    public static void main(String[] args) throws Exception {
        String socketPath = null;
        String token = null;
        String renderer = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--socket" -> socketPath = args[++i];
                case "--token" -> token = args[++i];
                case "--renderer" -> renderer = args[++i];
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (socketPath == null || token == null) {
            System.err.println("usage: HostMain --socket <path> --token <token> [--renderer agg|skia]");
            System.exit(2);
        }
        if (renderer != null) {
            System.setProperty("jpdfium.renderer", renderer);
        }

        HostMain host = new HostMain(token);
        host.serve(Path.of(socketPath));
    }

    private void serve(Path socketPath) throws IOException {
        Files.deleteIfExists(socketPath);
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socketPath));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                closeAllDocuments();
                try {
                    Files.deleteIfExists(socketPath);
                } catch (IOException ignored) {
                    // best effort
                }
            }));
            System.out.println("READY " + hostInstance);
            System.out.flush();
            while (true) {
                SocketChannel channel = server.accept();
                Thread.ofVirtual().start(() -> handleConnection(channel));
            }
        }
    }

    private void handleConnection(SocketChannel channel) {
        Session session = new Session();
        try (channel) {
            while (true) {
                byte[] frame = readFrame(channel);
                if (frame == null) {
                    return;
                }
                Map<String, Object> response = handleFrame(frame, session);
                writeFrame(channel, response);
                if (Boolean.TRUE.equals(session.closeRequested)) {
                    return;
                }
            }
        } catch (IOException e) {
            // Peer went away; fall through to cleanup.
        } finally {
            session.closeAll();
        }
    }

    // ------------------------------------------------------------- dispatch

    private Map<String, Object> handleFrame(byte[] frame, Session session) {
        Object parsed;
        try {
            parsed = Json.parse(new String(frame, StandardCharsets.UTF_8));
        } catch (Json.JsonException e) {
            return error(null, "ProtocolError", "invalid JSON request: " + e.getMessage(), null);
        }
        if (!(parsed instanceof Map<?, ?> raw)) {
            return error(null, "ProtocolError", "request must be a JSON object", null);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) raw;
        Object id = request.get("id");
        String op = request.get("op") instanceof String s ? s : null;
        if (op == null) {
            return error(id, "ProtocolError", "request is missing a string 'op'", null);
        }
        Map<String, Object> params = request.get("params") instanceof Map<?, ?> m
                ? copyToStringKeyed(m)
                : new LinkedHashMap<>();

        if (!"hello".equals(op) && !session.authenticated) {
            session.closeRequested = true;
            return error(id, "ConnectionError", "not authenticated: send 'hello' first", null);
        }
        try {
            Map<String, Object> result = dispatch(op, params, session);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", id);
            response.put("result", result);
            return response;
        } catch (UnsupportedOperationException e) {
            return error(id, "UnsupportedOperationError", e.getMessage(), null);
        } catch (PdfPasswordException e) {
            return error(id, "DocumentError", "password required or incorrect", null);
        } catch (PdfCorruptException e) {
            return error(id, "DocumentError", "invalid or corrupt PDF: " + e.getMessage(), null);
        } catch (IllegalArgumentException e) {
            return error(id, "ProtocolError", e.getMessage(), null);
        } catch (JPDFiumException e) {
            return error(id, "DocumentError", e.getMessage(), null);
        } catch (IOException e) {
            return error(id, "RenderError", "I/O failure: " + e.getMessage(), null);
        } catch (RuntimeException e) {
            return error(id, "RenderError", e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    private Map<String, Object> dispatch(String op, Map<String, Object> params, Session session)
            throws IOException {
        return switch (op) {
            case "hello" -> hello(params, session);
            case "health" -> health();
            case "capabilities" -> capabilities();
            case "fonts.capabilities" -> fontsCapabilities();
            case "document.open" -> documentOpen(params, session);
            case "document.close" -> documentClose(params, session);
            case "document.save" -> documentSave(params, session);
            case "page.geometry" -> pageGeometry(params, session);
            case "page.render" -> pageRender(params, session);
            case "fonts.inspect" -> fontsInspect(params, session);
            case "fonts.coverage" -> fontsCoverage(params);
            case "fonts.shape_run" -> fontsShapeRun(params);
            case "fonts.subset" -> fontsSubset(params);
            case "fonts.repair" -> fontsRepair(params, session);
            case "fonts.readiness" -> fontsReadiness(params, session);
            case "recognize_image", "write_text_layer" ->
                    throw new UnsupportedOperationException(op + " is not available in this host build");
            default -> throw new IllegalArgumentException("unknown operation: " + op);
        };
    }

    private Map<String, Object> hello(Map<String, Object> params, Session session) {
        String presented = Json.optionalString(params, "token");
        if (presented == null || !constantTimeEquals(presented, token)) {
            session.closeRequested = true;
            throw new IllegalArgumentException("authentication failed");
        }
        session.authenticated = true;
        Map<String, Object> result = capabilities();
        result.put("host_instance", hostInstance);
        return result;
    }

    private Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("host_instance", hostInstance);
        result.put("live_documents", documentsById.size());
        return result;
    }

    private Map<String, Object> capabilities() {
        JpdfiumLib.ensureInitialized();
        boolean skia = JpdfiumLib.isSkiaActive();
        List<Object> renderers = new ArrayList<>();
        renderers.add(skia ? "skia" : "agg");
        if (skia) {
            renderers.add("agg");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol_version", 1);
        result.put("backend_version", HOST_VERSION);
        result.put("pdfium_version", "abi:" + JpdfiumLib.abiVersion());
        result.put("renderers", renderers);
        result.put("has_skia", skia);
        FontCapabilities caps = FontCapabilities.detect();
        result.put("has_qpdf", caps.qpdf());
        result.put("ocr_profiles", List.of());
        result.put("ocr_languages", List.of());
        result.put("font_policies", List.of(
                "original_reusable",
                "replacement_required",
                "mapping_repair_available",
                "manual_confirmation_required",
                "unsupported_operation"));
        result.put("font_capabilities", fontCapabilities());
        result.put("limits", new LinkedHashMap<String, Object>());
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("document.save", true);
        features.put("fonts.inspect", caps.evidence());
        features.put("fonts.coverage", caps.coverage());
        features.put("fonts.shape_run", caps.shaping());
        features.put("fonts.subset", caps.subsetting());
        features.put("fonts.repair", caps.unicodeRepair());
        features.put("fonts.readiness", true);
        features.put("fonts.capabilities", true);
        result.put("features", features);
        return result;
    }

    /** Per-operation font capability report. */
    private Map<String, Object> fontCapabilities() {
        FontCapabilities caps = FontCapabilities.detect();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("backend", caps.backend());
        m.put("evidence", caps.evidence());
        m.put("unicode_repair", caps.unicodeRepair());
        m.put("width_repair", caps.widthRepair());
        m.put("type1_conversion", caps.type1Conversion());
        m.put("glyph_resubset", caps.glyphResubset());
        m.put("shaping", caps.shaping());
        m.put("coverage", caps.coverage());
        m.put("subsetting", caps.subsetting());
        m.put("qpdf", caps.qpdf());
        return m;
    }

    private Map<String, Object> fontsCapabilities() {
        return fontCapabilities();
    }

    private Map<String, Object> documentOpen(Map<String, Object> params, Session session) {
        String path = Json.asString(params.get("path"));
        String password = Json.optionalString(params, "password");
        PdfDocument document = PdfDocument.open(Path.of(path), password == null ? "" : password);
        String handle = UUID.randomUUID().toString();
        session.documents.put(handle, document);
        documentsById.put(handle, document);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("handle", handle);
        result.put("path", path);
        result.put("page_count", document.pageCount());
        result.put("encrypted", password != null && !password.isEmpty());
        return result;
    }

    private Map<String, Object> documentClose(Map<String, Object> params, Session session) {
        String handle = Json.asString(params.get("handle"));
        PdfDocument document = session.documents.remove(handle);
        if (document != null) {
            documentsById.remove(handle);
            document.close();
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> documentSave(Map<String, Object> params, Session session) {
        PdfDocument document = requireDocument(params, session);
        String path = Json.asString(params.get("path"));
        document.save(Path.of(path));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path);
        return result;
    }

    private Map<String, Object> pageGeometry(Map<String, Object> params, Session session) {
        PdfDocument document = requireDocument(params, session);
        int pageNumber = Json.optionalInt(params, "page", 0);
        requirePageInRange(document, pageNumber);
        try (PdfPage page = document.page(pageNumber - 1)) {
            PageSize size = page.size();
            PageBoxes boxes = page.boxes();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("index", pageNumber);
            result.put("width_pt", size.width());
            result.put("height_pt", size.height());
            result.put("media_box", rectToList(boxes.mediaBox()));
            Optional<Rect> crop = boxes.cropBox();
            result.put("crop_box", crop.isPresent() ? rectToList(crop.get()) : null);
            result.put("rotation", 0);
            return result;
        }
    }

    private Map<String, Object> pageRender(Map<String, Object> params, Session session)
            throws IOException {
        PdfDocument document = requireDocument(params, session);
        int pageNumber = Json.optionalInt(params, "page", 0);
        requirePageInRange(document, pageNumber);
        String output = Json.asString(params.get("output"));

        double[] dpi = dpiFrom(params.get("dpi"), 300.0);
        int renderDpi = (int) Math.round(dpi[0]);
        if (renderDpi <= 0) {
            throw new IllegalArgumentException("dpi must be positive");
        }
        String format = Json.optionalString(params, "format");
        boolean jpeg = format != null && (format.equalsIgnoreCase("jpeg") || format.equalsIgnoreCase("jpg"));
        String writerName = jpeg ? "jpeg" : "png";

        Path destination = Path.of(output);
        Path staging = destination.resolveSibling(destination.getFileName() + ".tmp-" + UUID.randomUUID());

        int widthPx;
        int heightPx;
        try (PdfPage page = document.page(pageNumber - 1)) {
            BufferedImage image = page.renderImage(renderDpi, false);
            widthPx = image.getWidth();
            heightPx = image.getHeight();
            if (!ImageIO.write(image, writerName, staging.toFile())) {
                throw new IOException("no image writer for " + writerName);
            }
        } catch (RuntimeException e) {
            Files.deleteIfExists(staging);
            throw e;
        }
        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", output);
        result.put("width_px", widthPx);
        result.put("height_px", heightPx);
        List<Object> dpiList = new ArrayList<>();
        dpiList.add(dpi[0]);
        dpiList.add(dpi[1]);
        result.put("dpi", dpiList);
        result.put("image_format", jpeg ? "JPEG" : "PNG");
        result.put("box", "media");
        return result;
    }

    // --------------------------------------------------------------- fonts

    private Map<String, Object> fontsInspect(Map<String, Object> params, Session session) {
        PdfDocument document = requireDocument(params, session);
        int pageNumber = Json.optionalInt(params, "page", 0);
        List<Object> fonts = new ArrayList<>();
        if (pageNumber > 0) {
            requirePageInRange(document, pageNumber);
            for (FontEvidence e : FontIntelligence.inspectPage(document, pageNumber - 1)) {
                fonts.add(fontToMap(e, true));
            }
        } else {
            for (FontEvidence e : FontIntelligence.inspect(document).distinct()) {
                fonts.add(fontToMap(e, false));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page", pageNumber > 0 ? pageNumber : null);
        result.put("count", fonts.size());
        result.put("fonts", fonts);
        return result;
    }

    private Map<String, Object> fontsCoverage(Map<String, Object> params) {
        byte[] fontData = decodeFontData(params);
        int[] codepoints = codepoints(params);
        boolean[] covered = FontIntelligence.coverage(fontData, codepoints);
        List<Object> out = new ArrayList<>(covered.length);
        for (boolean c : covered) {
            out.add(c);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", covered.length);
        result.put("covered", out);
        return result;
    }

    private Map<String, Object> fontsShapeRun(Map<String, Object> params) {
        byte[] fontData = decodeFontData(params);
        String text = Json.asString(params.get("text"));
        double size = params.get("font_size") instanceof Number n ? n.doubleValue() : 12.0;
        List<ShapedGlyph> glyphs = FontIntelligence.shape(fontData, text, (float) size);
        List<Object> rows = new ArrayList<>(glyphs.size());
        for (ShapedGlyph g : glyphs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("g", g.glyphId());
            row.put("ax", g.advanceX());
            row.put("ay", g.advanceY());
            row.put("dx", g.offsetX());
            row.put("dy", g.offsetY());
            row.put("cluster", g.cluster());
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", rows.size());
        result.put("glyphs", rows);
        return result;
    }

    private Map<String, Object> fontsSubset(Map<String, Object> params) {
        byte[] fontData = decodeFontData(params);
        int[] codepoints = codepoints(params);
        boolean retainGids = Boolean.TRUE.equals(params.get("retain_gids"));
        byte[] subset = FontIntelligence.subset(fontData, codepoints, retainGids);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("font_data", Base64.getEncoder().encodeToString(subset));
        result.put("size", subset.length);
        result.put("retain_gids", retainGids);
        return result;
    }

    private Map<String, Object> fontsRepair(Map<String, Object> params, Session session) {
        PdfDocument document = requireDocument(params, session);
        int pageNumber = Json.optionalInt(params, "page", 0);
        int fixed;
        if (pageNumber > 0) {
            requirePageInRange(document, pageNumber);
            fixed = FontIntelligence.repairPage(document, pageNumber - 1);
        } else {
            fixed = FontIntelligence.repairToUnicode(document);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fixed", fixed);
        result.put("page", pageNumber > 0 ? pageNumber : null);
        return result;
    }

    private Map<String, Object> fontsReadiness(Map<String, Object> params, Session session) {
        PdfDocument document = requireDocument(params, session);
        int pageNumber = Json.optionalInt(params, "page", 0);
        List<Object> rows = new ArrayList<>();
        if (pageNumber > 0) {
            requirePageInRange(document, pageNumber);
            for (EditReadiness r : FontIntelligence.editability(document, pageNumber - 1)) {
                rows.add(readinessToMap(r));
            }
        } else {
            for (int p = 0; p < document.pageCount(); p++) {
                for (EditReadiness r : FontIntelligence.editability(document, p)) {
                    rows.add(readinessToMap(r));
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page", pageNumber > 0 ? pageNumber : null);
        result.put("count", rows.size());
        result.put("readiness", rows);
        return result;
    }

    private static Map<String, Object> readinessToMap(EditReadiness r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("page", r.pageIndex() + 1);
        m.put("resource", r.resourceName());
        m.put("base_font", r.baseFont());
        m.put("engine_capable", r.engineCapable());
        m.put("font_capable", r.fontCapable());
        m.put("run_emittable", r.runEmittable());
        m.put("capability", r.capability().name().toLowerCase(Locale.ROOT));
        m.put("strategy", r.strategy());
        m.put("unsupported_reason", r.unsupportedReason());
        m.put("recovered_count", r.recoveredCount());
        m.put("ambiguous_count", r.ambiguousCount());
        m.put("unresolved_count", r.unresolvedCount());
        return m;
    }

    private static Map<String, Object> fontToMap(FontEvidence e, boolean includeMappings) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resource", e.resourceName());
        m.put("page", e.pageIndex() + 1);
        m.put("base_font", e.baseFont());
        m.put("family", e.family());
        m.put("subtype", e.subtype());
        m.put("kind", e.kind().name().toLowerCase(Locale.ROOT));
        m.put("embedded", e.embedded());
        m.put("sfnt", e.sfnt());
        m.put("cff", e.cff());
        m.put("cid", e.cid());
        m.put("subset", e.subset());
        m.put("has_cmap", e.hasCmap());
        m.put("num_glyphs", e.numGlyphs());
        m.put("units_per_em", e.unitsPerEm());
        m.put("glyph_bytes", e.glyphBytes());
        m.put("has_tounicode", e.hasToUnicode());
        m.put("tounicode_valid", e.toUnicodeValid());
        m.put("encoding", e.encoding());
        m.put("embedding_fs_type", e.embeddingFsType());
        m.put("embedding_permission", e.embedding().permission().name().toLowerCase(Locale.ROOT));
        m.put("no_subsetting", e.embedding().noSubsetting());
        m.put("capability", FontIntelligence.capability(e).name().toLowerCase(Locale.ROOT));
        m.put("mapping_count", e.mappingCount());
        m.put("unresolved_count", e.unresolvedCount());
        m.put("ambiguous_count", e.ambiguousCount());
        if (includeMappings) {
            List<Object> rows = new ArrayList<>();
            for (UnicodeMapping um : e.mappings()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("code", um.code());
                row.put("unicode", um.resolved() ? um.unicode() : -1);
                row.put("provenance", um.provenance().name().toLowerCase(Locale.ROOT));
                if (!um.note().isEmpty()) {
                    row.put("note", um.note());
                }
                rows.add(row);
            }
            m.put("mappings", rows);
        }
        return m;
    }

    private static byte[] decodeFontData(Map<String, Object> params) {
        String b64 = Json.optionalString(params, "font_data");
        if (b64 == null || b64.isBlank()) {
            throw new IllegalArgumentException("font_data (base64) is required");
        }
        try {
            return Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("font_data is not valid base64");
        }
    }

    private static int[] codepoints(Map<String, Object> params) {
        List<Integer> codes = new ArrayList<>();
        if (params.get("codepoints") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) {
                    codes.add(n.intValue());
                } else if (o instanceof String s && !s.isBlank()) {
                    codes.add(Integer.parseInt(s.trim()));
                }
            }
        }
        if (codes.isEmpty()) {
            throw new IllegalArgumentException("codepoints must not be empty");
        }
        int[] out = new int[codes.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = codes.get(i);
        }
        return out;
    }

    // ------------------------------------------------------------- helpers

    private PdfDocument requireDocument(Map<String, Object> params, Session session) {
        String handle = Json.asString(params.get("handle"));
        PdfDocument document = session.documents.get(handle);
        if (document == null) {
            throw new IllegalArgumentException("unknown or expired document handle");
        }
        return document;
    }

    private static void requirePageInRange(PdfDocument document, int pageNumber) {
        if (pageNumber < 1 || pageNumber > document.pageCount()) {
            throw new IllegalArgumentException("page out of range: " + pageNumber);
        }
    }

    private static List<Object> rectToList(Rect rect) {
        List<Object> list = new ArrayList<>(4);
        list.add(rect.x());
        list.add(rect.y());
        list.add(rect.width());
        list.add(rect.height());
        return list;
    }

    private static double[] dpiFrom(Object value, double fallback) {
        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Number n) {
            double x = n.doubleValue();
            double y = list.size() > 1 && list.get(1) instanceof Number m ? m.doubleValue() : x;
            return new double[] {x, y};
        }
        return new double[] {fallback, fallback};
    }

    private static Map<String, Object> copyToStringKeyed(Map<?, ?> raw) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            copy.put(String.valueOf(e.getKey()), e.getValue());
        }
        return copy;
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < x.length; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }

    private static Map<String, Object> error(Object id, String type, String message, Integer code) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", type);
        error.put("message", message == null ? type : message);
        if (code != null) {
            error.put("code", code);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("error", error);
        return response;
    }

    // --------------------------------------------------------------- framing

    private static byte[] readFrame(SocketChannel channel) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(4);
        while (header.hasRemaining()) {
            int read = channel.read(header);
            if (read < 0) {
                if (header.position() == 0) {
                    return null; // clean EOF exactly at a frame boundary
                }
                throw new EOFException("connection closed mid-header");
            }
        }
        header.flip();
        int length = header.getInt();
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("frame length out of bounds: " + length);
        }
        ByteBuffer body = ByteBuffer.allocate(length);
        while (body.hasRemaining()) {
            int read = channel.read(body);
            if (read < 0) {
                throw new EOFException("connection closed mid-frame");
            }
        }
        return body.array();
    }

    private static void writeFrame(SocketChannel channel, Map<String, Object> message) throws IOException {
        byte[] body = Json.write(message).getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_FRAME_BYTES) {
            throw new IOException("response frame too large");
        }
        ByteBuffer buffer = ByteBuffer.allocate(4 + body.length);
        buffer.putInt(body.length).put(body).flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private void closeAllDocuments() {
        for (PdfDocument document : documentsById.values()) {
            try {
                document.close();
            } catch (RuntimeException ignored) {
                // best effort during shutdown
            }
        }
        documentsById.clear();
    }

    /** Per-connection state: authentication plus that session's documents. */
    private static final class Session {
        final Map<String, PdfDocument> documents = new LinkedHashMap<>();
        boolean authenticated;
        boolean closeRequested;

        void closeAll() {
            for (PdfDocument document : documents.values()) {
                try {
                    document.close();
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
            documents.clear();
        }
    }
}

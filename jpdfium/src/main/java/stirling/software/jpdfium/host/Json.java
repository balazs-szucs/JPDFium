package stirling.software.jpdfium.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer for the host protocol.
 *
 * <p>The {@code jpdfium} module has no JSON dependency on its classpath, so this
 * host uses a tiny self-contained codec covering exactly what the framed-JSON
 * protocol needs: objects, arrays, strings (with U+XXXX escapes), numbers,
 * booleans and null.
 */
final class Json {

    /** Raised on malformed JSON. Mapped to a protocol error by the host. */
    static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        JsonException(String message) {
            super(message);
        }
    }

    private Json() {}

    // ---------------------------------------------------------------- writer

    static String write(Object value) {
        StringBuilder sb = new StringBuilder(128);
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new JsonException("cannot serialise non-finite number");
            }
            sb.append(d);
        } else if (value instanceof Number n) {
            sb.append(n.longValue());
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object element : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, element);
            }
            sb.append(']');
        } else if (value instanceof Object[] array) {
            writeValue(sb, List.of(array));
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- parser

    static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonException("trailing content after JSON value at " + parser.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new JsonException("expected a JSON object, got " + typeName(value));
        }
        return (Map<String, Object>) map;
    }

    static String asString(Object value) {
        if (value instanceof String s) {
            return s;
        }
        throw new JsonException("expected a JSON string, got " + typeName(value));
    }

    static String optionalString(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value == null ? null : asString(value);
    }

    static int optionalInt(Map<String, Object> object, String key, int fallback) {
        Object value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        throw new JsonException("expected a number for '" + key + "', got " + typeName(value));
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f', 'n' -> parseLiteral();
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or '}' at " + (pos - 1));
                }
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or ']' at " + (pos - 1));
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char esc = next();
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw new JsonException("truncated \\u escape");
                            }
                            String hex = text.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw new JsonException("invalid \\u escape: " + hex);
                            }
                        }
                        default -> throw new JsonException("invalid escape \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object parseLiteral() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new JsonException("invalid literal at " + pos);
        }

        private Object parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            String token = text.substring(start, pos);
            if (token.isEmpty()) {
                throw new JsonException("invalid number at " + start);
            }
            try {
                if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
                    return Long.parseLong(token);
                }
                return Double.parseDouble(token);
            } catch (NumberFormatException e) {
                throw new JsonException("invalid number: " + token);
            }
        }

        private char peek() {
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            return text.charAt(pos);
        }

        private char next() {
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            return text.charAt(pos++);
        }

        private void expect(char expected) {
            char c = next();
            if (c != expected) {
                throw new JsonException("expected '" + expected + "' at " + (pos - 1) + " but found '" + c + "'");
            }
        }
    }
}

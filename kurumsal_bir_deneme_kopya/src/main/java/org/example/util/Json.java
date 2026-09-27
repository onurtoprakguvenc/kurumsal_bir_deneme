package org.example.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free JSON reader/writer sufficient for the Gemini REST protocol.
 *
 * <p>Parsing yields {@link Map} (insertion ordered), {@link List}, {@link String}, {@link Double}/{@link Long},
 * {@link Boolean} or {@code null}. Nesting depth is bounded to resist hostile payloads.</p>
 */
public final class Json {

    private static final int MAX_DEPTH = 128;

    private Json() {
    }

    /** Thrown for syntactically invalid JSON. */
    public static final class JsonException extends Exception {
        private static final long serialVersionUID = 1L;

        JsonException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object value) {
        StringBuilder out = new StringBuilder(256);
        write(value, out, 0);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nesting too deep");
        }
        switch (value) {
            case null -> out.append("null");
            case CharSequence s -> quote(s, out);
            case Boolean b -> out.append(b.booleanValue());
            case Double d -> out.append(finite(d));
            case Float f -> out.append(finite(f.doubleValue()));
            case Number n -> out.append(n.longValue());
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    quote(String.valueOf(e.getKey()), out);
                    out.append(':');
                    write(e.getValue(), out, depth + 1);
                }
                out.append('}');
            }
            case Collection<?> list -> {
                out.append('[');
                boolean first = true;
                for (Object item : list) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(item, out, depth + 1);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("Unsupported JSON type: " + value.getClass().getName());
        }
    }

    private static String finite(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("JSON cannot encode " + d);
        }
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }

    public static void quote(CharSequence s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == '\u2028' || c == '\u2029'
                            || (Character.isSurrogate(c) && !validSurrogate(s, i))) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static boolean validSurrogate(CharSequence s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        }
        return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
    }

    // ------------------------------------------------------------------ reading

    public static Object parse(String text) throws JsonException {
        Parser p = new Parser(text);
        p.skipWs();
        Object value = p.value(0);
        p.skipWs();
        if (p.pos != text.length()) {
            throw new JsonException("Trailing data at offset " + p.pos);
        }
        return value;
    }

    /** Navigates nested maps/lists; returns {@code null} when any step is missing. */
    public static Object at(Object root, Object... path) {
        Object current = root;
        for (Object step : path) {
            if (step instanceof String key && current instanceof Map<?, ?> map) {
                current = map.get(key);
            } else if (step instanceof Integer index && current instanceof List<?> list) {
                current = index >= 0 && index < list.size() ? list.get(index) : null;
            } else {
                return null;
            }
        }
        return current;
    }

    public static String string(Object root, Object... path) {
        return at(root, path) instanceof String s ? s : null;
    }

    public static long number(Object root, long fallback, Object... path) {
        return at(root, path) instanceof Number n ? n.longValue() : fallback;
    }

    public static List<?> list(Object root, Object... path) {
        return at(root, path) instanceof List<?> l ? l : List.of();
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value(int depth) throws JsonException {
            if (depth > MAX_DEPTH) {
                throw new JsonException("Nesting too deep");
            }
            if (pos >= s.length()) {
                throw new JsonException("Unexpected end of input");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw new JsonException("Unexpected character '" + c + "' at offset " + pos);
                }
            };
        }

        private Object literal(String word, Object result) throws JsonException {
            if (!s.startsWith(word, pos)) {
                throw new JsonException("Invalid literal at offset " + pos);
            }
            pos += word.length();
            return result;
        }

        private Map<String, Object> object(int depth) throws JsonException {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw new JsonException("Expected object key at offset " + pos);
                }
                String key = string();
                skipWs();
                expect(':');
                skipWs();
                map.put(key, value(depth + 1));
                skipWs();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new JsonException("Expected ',' or '}' at offset " + (pos - 1));
                }
            }
        }

        private List<Object> array(int depth) throws JsonException {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(value(depth + 1));
                skipWs();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new JsonException("Expected ',' or ']' at offset " + (pos - 1));
                }
            }
        }

        private String string() throws JsonException {
            expect('"');
            StringBuilder sb = null;
            int start = pos;
            while (true) {
                if (pos >= s.length()) {
                    throw new JsonException("Unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb == null ? s.substring(start, pos - 1) : sb.toString();
                }
                if (c == '\\') {
                    if (sb == null) {
                        sb = new StringBuilder(s.substring(start, pos - 1));
                    }
                    char e = next();
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw new JsonException("Truncated unicode escape");
                            }
                            try {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw new JsonException("Invalid unicode escape at offset " + pos);
                            }
                            pos += 4;
                        }
                        default -> throw new JsonException("Invalid escape '\\" + e + "'");
                    }
                } else if (c < 0x20) {
                    throw new JsonException("Control character in string at offset " + (pos - 1));
                } else if (sb != null) {
                    sb.append(c);
                }
            }
        }

        private Object number() throws JsonException {
            int start = pos;
            boolean floating = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E') {
                    floating = true;
                    pos++;
                } else {
                    break;
                }
            }
            String token = s.substring(start, pos);
            try {
                if (!floating) {
                    return Long.parseLong(token);
                }
                return Double.parseDouble(token);
            } catch (NumberFormatException e) {
                try {
                    return Double.parseDouble(token);
                } catch (NumberFormatException e2) {
                    throw new JsonException("Invalid number '" + token + "'");
                }
            }
        }

        private char peek() throws JsonException {
            if (pos >= s.length()) {
                throw new JsonException("Unexpected end of input");
            }
            return s.charAt(pos);
        }

        private char next() throws JsonException {
            char c = peek();
            pos++;
            return c;
        }

        private void expect(char expected) throws JsonException {
            char c = next();
            if (c != expected) {
                throw new JsonException("Expected '" + expected + "' at offset " + (pos - 1));
            }
        }
    }
}

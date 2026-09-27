package org.yazi.motion.json;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Minimal dependency-free JSON writer (objects = Map, arrays = List). */
public final class Json {
    private Json() {}

    public static String write(Object value, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, pretty, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, boolean pretty, int depth) {
        switch (v) {
            case null -> sb.append("null");
            case String s -> string(sb, s);
            case Boolean b -> sb.append(b);
            case Integer i -> sb.append(i);
            case Long l -> sb.append(l);
            case Double d -> sb.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf(d.longValue()) : d.toString());
            case Number n -> sb.append(n);
            case Enum<?> e -> string(sb, e.name());
            case Map<?, ?> m -> {
                sb.append('{');
                Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
                while (it.hasNext()) {
                    var e = it.next();
                    newline(sb, pretty, depth + 1);
                    string(sb, String.valueOf(e.getKey()));
                    sb.append(pretty ? ": " : ":");
                    write(sb, e.getValue(), pretty, depth + 1);
                    if (it.hasNext()) sb.append(',');
                }
                if (!m.isEmpty()) newline(sb, pretty, depth);
                sb.append('}');
            }
            case List<?> list -> {
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    newline(sb, pretty, depth + 1);
                    write(sb, list.get(i), pretty, depth + 1);
                    if (i < list.size() - 1) sb.append(',');
                }
                if (!list.isEmpty()) newline(sb, pretty, depth);
                sb.append(']');
            }
            default -> string(sb, v.toString());
        }
    }

    @SuppressWarnings("unused")
    private static void newline(StringBuilder sb, boolean pretty, int depth) {
        if (pretty) sb.append('\n').append("  ".repeat(depth));
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}

package org.opentoshi.openbarnyard.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader/writer covering exactly what the
 * launcher persists (engine config + touch profiles). Avoids org.json so the
 * core stays testable on a plain JVM and behaves identically everywhere.
 * Numbers parse as {@link Double}; objects as {@code Map<String,Object>}.
 */
public final class Json {
    private Json() {}

    // -------------------------------------------------------------- writing

    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    /** Formats a float compactly and locale-safely ("0.85", "1"). */
    public static String num(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        String s = String.format(Locale.US, "%.4f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // -------------------------------------------------------------- reading

    public static final class ParseException extends Exception {
        ParseException(String msg) { super(msg); }
    }

    public static Object parse(String text) throws ParseException {
        if (text == null) throw new ParseException("null input");
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (!p.atEnd()) throw new ParseException("trailing characters at " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws ParseException {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new ParseException("top-level value is not an object");
        return (Map<String, Object>) v;
    }

    // -------------------------------------------------------- typed getters

    public static double getDouble(Map<String, Object> m, String key, double def) {
        Object v = m != null ? m.get(key) : null;
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }

    public static int getInt(Map<String, Object> m, String key, int def) {
        Object v = m != null ? m.get(key) : null;
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    public static boolean getBool(Map<String, Object> m, String key, boolean def) {
        Object v = m != null ? m.get(key) : null;
        return v instanceof Boolean ? (Boolean) v : def;
    }

    public static String getString(Map<String, Object> m, String key, String def) {
        Object v = m != null ? m.get(key) : null;
        return v instanceof String ? (String) v : def;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getObject(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> getArray(Map<String, Object> m, String key) {
        Object v = m != null ? m.get(key) : null;
        return v instanceof List ? (List<Object>) v : null;
    }

    // --------------------------------------------------------------- parser

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        boolean atEnd() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        private char peek() throws ParseException {
            if (atEnd()) throw new ParseException("unexpected end of input");
            return s.charAt(pos);
        }

        private void expect(char c) throws ParseException {
            if (atEnd() || s.charAt(pos) != c) {
                throw new ParseException("expected '" + c + "' at " + pos);
            }
            pos++;
        }

        Object value() throws ParseException {
            skipWs();
            char c = peek();
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': literal("true");  return Boolean.TRUE;
                case 'f': literal("false"); return Boolean.FALSE;
                case 'n': literal("null");  return null;
                default:  return number();
            }
        }

        private void literal(String lit) throws ParseException {
            if (!s.regionMatches(pos, lit, 0, lit.length())) {
                throw new ParseException("bad literal at " + pos);
            }
            pos += lit.length();
        }

        private Map<String, Object> object() throws ParseException {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            skipWs();
            if (!atEnd() && s.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                expect(':');
                map.put(key, value());
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return map; }
                throw new ParseException("expected ',' or '}' at " + pos);
            }
        }

        private List<Object> array() throws ParseException {
            expect('[');
            List<Object> list = new ArrayList<Object>();
            skipWs();
            if (!atEnd() && s.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(value());
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return list; }
                throw new ParseException("expected ',' or ']' at " + pos);
            }
        }

        private String string() throws ParseException {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                if (atEnd()) throw new ParseException("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (atEnd()) throw new ParseException("unterminated escape");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"':  b.append('"');  break;
                        case '\\': b.append('\\'); break;
                        case '/':  b.append('/');  break;
                        case 'b':  b.append('\b'); break;
                        case 'f':  b.append('\f'); break;
                        case 'n':  b.append('\n'); break;
                        case 'r':  b.append('\r'); break;
                        case 't':  b.append('\t'); break;
                        case 'u':
                            if (pos + 4 > s.length()) throw new ParseException("bad \\u escape");
                            try {
                                b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException nfe) {
                                throw new ParseException("bad \\u escape");
                            }
                            pos += 4;
                            break;
                        default: throw new ParseException("bad escape \\" + e);
                    }
                } else {
                    b.append(c);
                }
            }
        }

        private Double number() throws ParseException {
            int start = pos;
            if (!atEnd() && s.charAt(pos) == '-') pos++;
            while (!atEnd()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++;
                else break;
            }
            if (pos == start) throw new ParseException("bad value at " + start);
            try {
                return Double.valueOf(s.substring(start, pos));
            } catch (NumberFormatException nfe) {
                throw new ParseException("bad number at " + start);
            }
        }
    }
}

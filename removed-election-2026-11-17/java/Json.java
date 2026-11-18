package com.rightnews.live;
import java.util.*;
// Minimal JSON parser (objects, arrays, strings, numbers, booleans,
// null) — no external library in this manual build, and the
// civicAPI region payloads are nested, so regex extraction is not
// enough for them. Objects keep insertion order (LinkedHashMap).
public class Json {
    public static Object parse(String s) throws Exception {
        Parser p = new Parser(s);
        p.skip();
        Object v = p.value();
        return v;
    }
    public static Map<String,Object> obj(Object v) {
        if (v instanceof Map) return (Map<String,Object>) v;
        return new HashMap<>();
    }
    public static List<Object> arr(Object v) {
        if (v instanceof List) return (List<Object>) v;
        return new ArrayList<>();
    }
    public static String str(Object v, String def) {
        return v instanceof String ? (String) v : def;
    }
    public static double num(Object v, double def) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        return def;
    }
    public static boolean bool(Object v, boolean def) {
        if (v instanceof Boolean) return (Boolean) v;
        return def;
    }
    private static class Parser {
        private final String s; private int i;
        Parser(String s) { this.s = s; }
        void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() throws Exception {
            skip();
            if (i >= s.length()) throw new Exception("eof");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't') { expect("true"); return Boolean.TRUE; }
            if (c == 'f') { expect("false"); return Boolean.FALSE; }
            if (c == 'n') { expect("null"); return null; }
            return number();
        }
        void expect(String lit) throws Exception {
            if (!s.startsWith(lit, i)) throw new Exception("bad literal");
            i += lit.length();
        }
        Map<String,Object> object() throws Exception {
            Map<String,Object> m = new LinkedHashMap<>();
            i++; skip();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                skip();
                String k = string();
                skip();
                if (i >= s.length() || s.charAt(i) != ':') throw new Exception("expected :");
                i++;
                m.put(k, value());
                skip();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; break; }
                throw new Exception("expected , or }");
            }
            return m;
        }
        List<Object> array() throws Exception {
            List<Object> l = new ArrayList<>();
            i++; skip();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                skip();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; break; }
                throw new Exception("expected , or ]");
            }
            return l;
        }
        String string() throws Exception {
            if (s.charAt(i) != '"') throw new Exception("expected string");
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    if (e == 'n') sb.append('\n');
                    else if (e == 't') sb.append('\t');
                    else if (e == 'r') sb.append('\r');
                    else if (e == 'u' && i + 4 <= s.length()) {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    } else sb.append(e);
                } else sb.append(c);
            }
            throw new Exception("unterminated string");
        }
        Number number() throws Exception {
            int j = i;
            while (j < s.length() && "-+0123456789.eE".indexOf(s.charAt(j)) >= 0) j++;
            String t = s.substring(i, j);
            i = j;
            try {
                if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.parseDouble(t);
                return Long.parseLong(t);
            } catch (Exception e) { throw new Exception("bad number " + t); }
        }
    }
}

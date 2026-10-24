package studio.manafield.remotedatapack.file;

import java.util.*;

/** Strict, bounded JSON for pack.mcmeta only. No lenient parsing, duplicate keys or unbounded nesting. */
final class MetadataJson {
    private final String text; private int offset;
    private MetadataJson(String text) { this.text = text; }
    static Object parse(String text) {
        var parser = new MetadataJson(text); Object value = parser.value(0); parser.space();
        if (parser.offset != text.length()) throw new IllegalArgumentException();
        return value;
    }
    private void space() { while (offset < text.length() && " \n\r\t".indexOf(text.charAt(offset)) >= 0) offset++; }
    private char take() { if (offset >= text.length()) throw new IllegalArgumentException(); return text.charAt(offset++); }
    private Object value(int depth) {
        if (depth > 32) throw new IllegalArgumentException(); space(); char c = take();
        if (c == '"') return string();
        if (c == '{') {
            Map<String,Object> map = new HashMap<>(); space();
            if (offset < text.length() && text.charAt(offset) == '}') { offset++; return map; }
            do { space(); if (take() != '"') throw new IllegalArgumentException(); String key = string(); space();
                if (take() != ':' || map.containsKey(key)) throw new IllegalArgumentException(); map.put(key, value(depth+1));
                space(); c = take(); if (c == '}') return map;
            } while (c == ','); throw new IllegalArgumentException();
        }
        if (c == '[') {
            List<Object> list = new ArrayList<>(); space();
            if (offset < text.length() && text.charAt(offset) == ']') { offset++; return list; }
            do { list.add(value(depth+1)); space(); c = take(); if (c == ']') return list; } while (c == ',');
            throw new IllegalArgumentException();
        }
        if (c == 't' || c == 'f' || c == 'n') {
            String tail = c == 't' ? "rue" : c == 'f' ? "alse" : "ull";
            for (char expected : tail.toCharArray()) if (take() != expected) throw new IllegalArgumentException();
            return c == 'n' ? null : c == 't';
        }
        int start = offset - 1;
        while (offset < text.length() && "0123456789.eE+-".indexOf(text.charAt(offset)) >= 0) offset++;
        String number = text.substring(start, offset);
        if (!number.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) throw new IllegalArgumentException();
        return new java.math.BigDecimal(number);
    }
    private String string() {
        StringBuilder value = new StringBuilder();
        for (;;) {
            char c = take(); if (c == '"') return value.toString(); if (c < 32) throw new IllegalArgumentException();
            if (c == '\\') { c = take(); c = switch(c) {
                case '"', '\\', '/' -> c; case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                case 'u' -> { int code = 0; for (int i=0;i<4;i++) { int d=Character.digit(take(),16); if(d<0) throw new IllegalArgumentException(); code=code*16+d; } yield (char)code; }
                default -> throw new IllegalArgumentException();
            }; }
            value.append(c);
        }
    }
}

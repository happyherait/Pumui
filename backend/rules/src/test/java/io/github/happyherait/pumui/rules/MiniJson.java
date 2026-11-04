package io.github.happyherait.pumui.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** rules 모듈을 무의존성으로 유지하기 위한 테스트 전용 JSON 파서. */
final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        return new MiniJson(text).value();
    }

    private Object value() {
        ws();
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return true; }
        if (s.startsWith("false", i)) { i += 5; return false; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int start = i;
        while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
        return Double.parseDouble(s.substring(start, i));
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') { i++; return out; }
        while (true) {
            ws();
            String key = string();
            ws();
            i++;
            out.put(key, value());
            ws();
            if (s.charAt(i++) == '}') return out;
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') { i++; return out; }
        while (true) {
            out.add(value());
            ws();
            if (s.charAt(i++) == ']') return out;
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        i++;
        while (s.charAt(i) != '"') {
            if (s.charAt(i) == '\\') i++;
            sb.append(s.charAt(i++));
        }
        i++;
        return sb.toString();
    }

    private void ws() {
        while (Character.isWhitespace(s.charAt(i))) i++;
    }
}

package io.github.happyherait.pumui.rules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 키 순서와 무관한 내용 해시. 승인을 특정 사양에 묶는 데 쓴다. */
public final class RevisionHash {

    private RevisionHash() {}

    public static String of(Map<String, ?> content) {
        StringBuilder sb = new StringBuilder();
        write(content, sb);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(Object value, StringBuilder sb) {
        if (value instanceof Map<?, ?> map) {
            sb.append('{');
            new TreeMap<>(map).forEach((k, v) -> {
                sb.append(quote(k)).append(':');
                write(v, sb);
                sb.append(',');
            });
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            list.forEach(v -> {
                write(v, sb);
                sb.append(',');
            });
            sb.append(']');
        } else {
            sb.append(quote(value));
        }
    }

    private static String quote(Object v) {
        String text = String.valueOf(v);
        return text.length() + ":" + text;
    }
}

package com.example.milestone.util;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;

/**
 * 确定性 JSON 序列化（对象键排序、最小白空格），用于证据负载的内容哈希。
 * 数字按 jsonb 规范化后的字面量输出，配合
 * {@code DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS} 保证重算一致。
 */
public final class CanonicalJson {
    private CanonicalJson() {}

    public static String canonical(JsonNode node) {
        var sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
    }

    private static void write(JsonNode node, StringBuilder sb) {
        switch (node.getNodeType()) {
            case OBJECT -> {
                sb.append('{');
                var names = new ArrayList<String>();
                node.fieldNames().forEachRemaining(names::add);
                Collections.sort(names);
                for (int i = 0; i < names.size(); i++) {
                    if (i > 0) sb.append(',');
                    writeString(names.get(i), sb);
                    sb.append(':');
                    write(node.get(names.get(i)), sb);
                }
                sb.append('}');
            }
            case ARRAY -> {
                sb.append('[');
                for (int i = 0; i < node.size(); i++) {
                    if (i > 0) sb.append(',');
                    write(node.get(i), sb);
                }
                sb.append(']');
            }
            case STRING -> writeString(node.textValue(), sb);
            default -> sb.append(node.toString());
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
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

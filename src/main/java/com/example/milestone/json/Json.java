package com.example.milestone.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

/** JSON 工具：解析、规范化（键排序、无空白）与哈希。规范化串是签名与 payload_hash 的输入。 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN, true);

    private Json() {}

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getOriginalMessage(), e);
        }
    }

    public static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    /** 规范化 JSON：对象键按字典序递归排序、无空白；数组保持顺序。 */
    public static String canonical(JsonNode node) {
        var sb = new StringBuilder();
        writeCanonical(node, sb);
        return sb.toString();
    }

    private static void writeCanonical(JsonNode n, StringBuilder sb) {
        switch (n.getNodeType()) {
            case OBJECT -> {
                sb.append('{');
                var names = new TreeSet<String>();
                n.fieldNames().forEachRemaining(names::add);
                boolean first = true;
                for (String name : names) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(write(MAPPER.getNodeFactory().textNode(name)));
                    sb.append(':');
                    writeCanonical(n.get(name), sb);
                }
                sb.append('}');
            }
            case ARRAY -> {
                sb.append('[');
                for (int i = 0; i < n.size(); i++) {
                    if (i > 0) sb.append(',');
                    writeCanonical(n.get(i), sb);
                }
                sb.append(']');
            }
            case STRING -> sb.append(write(n));
            case NUMBER -> sb.append(n.numberValue().toString());
            case BOOLEAN -> sb.append(n.booleanValue());
            case NULL -> sb.append("null");
            default -> throw new IllegalArgumentException("不支持的 JSON 节点类型: " + n.getNodeType());
        }
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

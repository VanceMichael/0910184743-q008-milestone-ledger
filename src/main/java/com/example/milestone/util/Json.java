package com.example.milestone.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.milestone.http.ApiException;

public final class Json {
    /** 通用映射器。 */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    /** 规范化映射器：浮点/整数按字面精度解析，配合 CanonicalJson 与 jsonb 输出对齐。 */
    public static final ObjectMapper CANON_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            // Jackson 2.16+ 默认去除尾随零，会与 jsonb 的规范化输出不一致
            .disable(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private Json() {}

    public static JsonNode parse(byte[] body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new ApiException(400, "INVALID_JSON", "请求体不是合法 JSON");
        }
    }

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("非法 JSON 文本", e);
        }
    }

    public static JsonNode parseCanonical(String text) {
        try {
            return CANON_MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("非法 JSON 文本", e);
        }
    }

    public static byte[] bytes(JsonNode node) {
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }
}

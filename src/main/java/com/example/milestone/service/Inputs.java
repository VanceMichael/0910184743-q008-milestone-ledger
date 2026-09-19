package com.example.milestone.service;

import com.example.milestone.domain.ApiException;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/** 请求入参校验小工具。 */
public final class Inputs {

    private Inputs() {}

    public static UUID uuid(JsonNode body, String field) {
        String raw = text(body, field);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("字段 " + field + " 不是合法 UUID: " + raw);
        }
    }

    public static String text(JsonNode body, String field) {
        JsonNode n = body.get(field);
        if (n == null || !n.isTextual() || n.asText().isBlank()) {
            throw ApiException.badRequest("缺少或非法的文本字段: " + field);
        }
        return n.asText();
    }

    public static String optionalText(JsonNode body, String field, String fallback) {
        JsonNode n = body.get(field);
        return (n == null || n.isNull()) ? fallback : n.asText();
    }

    public static JsonNode object(JsonNode body, String field) {
        JsonNode n = body.get(field);
        if (n == null || !n.isObject()) throw ApiException.badRequest("字段 " + field + " 必须是 JSON 对象");
        return n;
    }

    public static JsonNode array(JsonNode body, String field) {
        JsonNode n = body.get(field);
        if (n == null || !n.isArray()) throw ApiException.badRequest("字段 " + field + " 必须是 JSON 数组");
        return n;
    }

    /** 解析客户端时间；原文需保留用于验签，因此调用方应同时使用原始字符串。 */
    public static OffsetDateTime timestamp(JsonNode body, String field) {
        return parseTimestamp(text(body, field), field);
    }

    public static OffsetDateTime parseTimestamp(String raw, String field) {
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException e1) {
            try {
                return java.time.Instant.parse(raw).atOffset(java.time.ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                throw ApiException.badRequest("字段 " + field + " 不是合法 ISO-8601 时间: " + raw);
            }
        }
    }

    public static UUID uuidPath(String raw, String field) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("路径参数 " + field + " 不是合法 UUID: " + raw);
        }
    }
}

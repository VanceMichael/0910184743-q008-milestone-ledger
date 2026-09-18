package com.example.milestone.service;

import com.example.milestone.auth.Role;
import com.example.milestone.http.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public final class Validate {
    private Validate() {}

    public static String requireText(JsonNode body, String field) {
        JsonNode n = body.get(field);
        if (n == null || !n.isTextual() || n.asText().isBlank()) {
            throw ApiException.unprocessable("MISSING_FIELD", "缺少必填字段: " + field);
        }
        return n.asText();
    }

    public static String optionalText(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n != null && n.isTextual() && !n.asText().isBlank() ? n.asText() : null;
    }

    public static UUID requireUuid(JsonNode body, String field) {
        String text = requireText(body, field);
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("BAD_UUID", "字段不是合法 UUID: " + field);
        }
    }

    public static UUID pathUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("BAD_UUID", "路径参数不是合法 UUID: " + field);
        }
    }

    public static String optionalDecimal(JsonNode body, String field) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) return null;
        if (!n.isNumber() && !n.isTextual()) {
            throw ApiException.unprocessable("BAD_DECIMAL", "字段不是数值: " + field);
        }
        try {
            return new java.math.BigDecimal(n.asText()).toPlainString();
        } catch (NumberFormatException e) {
            throw ApiException.unprocessable("BAD_DECIMAL", "字段不是数值: " + field);
        }
    }

    public static Role requireRole(JsonNode body, String field) {
        String text = requireText(body, field);
        try {
            return Role.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("BAD_ROLE", "未知角色: " + text);
        }
    }
}

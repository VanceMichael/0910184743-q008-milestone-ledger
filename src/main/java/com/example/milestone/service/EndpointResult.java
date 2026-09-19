package com.example.milestone.service;

import com.fasterxml.jackson.databind.JsonNode;

/** 端点结果：状态码 + 响应体；replay=true 表示这是幂等重放的原决定。 */
public record EndpointResult(int status, JsonNode body, boolean replay) {

    public static EndpointResult fresh(int status, JsonNode body) {
        return new EndpointResult(status, body, false);
    }

    public static EndpointResult replay(int status, JsonNode body) {
        return new EndpointResult(status, body, true);
    }
}

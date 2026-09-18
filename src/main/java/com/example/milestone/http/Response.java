package com.example.milestone.http;

import com.example.milestone.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Response {
    public final int status;
    public final byte[] body;
    public final Map<String, String> headers;

    public Response(int status, byte[] body, Map<String, String> headers) {
        this.status = status;
        this.body = body;
        this.headers = headers;
    }

    public static Response json(int status, JsonNode node) {
        return json(status, node, Map.of());
    }

    public static Response json(int status, JsonNode node, Map<String, String> extraHeaders) {
        var headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.putAll(extraHeaders);
        return new Response(status, Json.bytes(node), headers);
    }

    public static Response error(int status, String code, String message) {
        var node = Json.obj();
        var err = node.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return json(status, node);
    }
}

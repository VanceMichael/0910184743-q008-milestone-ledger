package com.example.milestone.http;

import com.example.milestone.auth.AuthContext;
import com.example.milestone.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.Headers;
import java.util.List;
import java.util.Map;

public final class Request {
    public final String method;
    public final String path;
    public final Map<String, String> pathParams;
    public final Map<String, String> query;
    public final Headers headers;
    public final byte[] body;
    public AuthContext auth;

    public Request(String method, String path, Map<String, String> pathParams,
                   Map<String, String> query, Headers headers, byte[] body) {
        this.method = method;
        this.path = path;
        this.pathParams = pathParams;
        this.query = query;
        this.headers = headers;
        this.body = body;
    }

    public String header(String name) {
        List<String> all = headers.get(name);
        return all == null || all.isEmpty() ? null : all.get(0);
    }

    public String param(String name) {
        String v = pathParams.get(name);
        if (v == null) throw new IllegalStateException("缺少路径参数 " + name);
        return v;
    }

    public String query(String name, String fallback) {
        return query.getOrDefault(name, fallback);
    }

    public JsonNode json() {
        return Json.parse(body);
    }
}

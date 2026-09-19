package com.example.milestone.http;

import com.example.milestone.domain.ApiException;
import com.example.milestone.fault.FaultInjector;
import com.example.milestone.json.Json;
import com.example.milestone.service.EndpointResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 极简路由器：支持 {param} 路径参数，统一异常 -> 错误 JSON 映射。 */
public final class Router {

    private static final Logger log = LoggerFactory.getLogger(Router.class);

    @FunctionalInterface
    public interface Handler {
        EndpointResult handle(Request request) throws Exception;
    }

    public record Request(Map<String, String> path, Map<String, String> query,
                          Map<String, String> headers, JsonNode body) {

        public String pathParam(String name) {
            return path.get(name);
        }

        public String queryParam(String name) {
            return query.get(name);
        }

        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private record Route(String method, Pattern pattern, List<String> params, Handler handler) {}

    private final List<Route> routes = new ArrayList<>();

    public Router add(String method, String pathTemplate, Handler handler) {
        var params = new ArrayList<String>();
        var regex = new StringBuilder();
        for (String segment : pathTemplate.split("/")) {
            if (segment.isEmpty()) continue;
            regex.append("/");
            if (segment.startsWith("{") && segment.endsWith("}")) {
                params.add(segment.substring(1, segment.length() - 1));
                regex.append("([^/]+)");
            } else {
                regex.append(Pattern.quote(segment));
            }
        }
        routes.add(new Route(method, Pattern.compile(regex.toString()), params, handler));
        return this;
    }

    public void handle(HttpExchange exchange) throws IOException {
        try {
            dispatch(exchange);
        } catch (ApiException e) {
            writeJson(exchange, e.status(), errorBody(e.code(), e.getMessage()), false);
        } catch (FaultInjector.FaultInjected e) {
            writeJson(exchange, 500, errorBody("FAULT_INJECTED", e.getMessage()), false);
        } catch (Exception e) {
            log.error("未处理异常 {} {}", exchange.getRequestMethod(), exchange.getRequestURI(), e);
            writeJson(exchange, 500, errorBody("INTERNAL", "内部错误"), false);
        } finally {
            exchange.close();
        }
    }

    private void dispatch(HttpExchange exchange) throws Exception {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getRawPath();
        for (Route route : routes) {
            if (!route.method().equals(method)) continue;
            var matcher = route.pattern().matcher(path);
            if (!matcher.matches()) continue;
            var pathParams = new HashMap<String, String>();
            for (int i = 0; i < route.params().size(); i++) {
                pathParams.put(route.params().get(i),
                        URLDecoder.decode(matcher.group(i + 1), StandardCharsets.UTF_8));
            }
            var headers = new HashMap<String, String>();
            exchange.getRequestHeaders().forEach((k, v) -> {
                if (!v.isEmpty()) headers.put(k.toLowerCase(), v.get(0));
            });
            JsonNode body = null;
            byte[] raw = exchange.getRequestBody().readAllBytes();
            if (raw.length > 0) {
                try {
                    body = Json.parse(new String(raw, StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("请求体不是合法 JSON");
                }
            }
            var result = route.handler().handle(
                    new Request(pathParams, parseQuery(exchange.getRequestURI().getRawQuery()), headers, body));
            writeJson(exchange, result.status(), result.body(), result.replay());
            return;
        }
        writeJson(exchange, 404, errorBody("NOT_FOUND", "路由不存在: " + method + " " + path), false);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        var out = new LinkedHashMap<String, String>();
        if (rawQuery == null || rawQuery.isEmpty()) return out;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static JsonNode errorBody(String code, String message) {
        return Json.obj().set("error", Json.obj().put("code", code).put("message", message));
    }

    private static void writeJson(HttpExchange exchange, int status, JsonNode body, boolean replay)
            throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (replay) exchange.getResponseHeaders().set("Idempotent-Replay", "true");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}

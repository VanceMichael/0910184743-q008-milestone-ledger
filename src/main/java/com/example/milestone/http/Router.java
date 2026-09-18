package com.example.milestone.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 极简路由：按段匹配，{name} 提取路径参数。 */
public final class Router {
    @FunctionalInterface
    public interface Handler {
        Response handle(Request req) throws Exception;
    }

    private record Route(String method, String[] segments, Handler handler) {}

    private final List<Route> routes = new ArrayList<>();

    public Router add(String method, String pattern, Handler handler) {
        routes.add(new Route(method, split(pattern), handler));
        return this;
    }

    public Response dispatch(Request req) throws Exception {
        String[] actual = split(req.path);
        boolean pathMatched = false;
        for (Route r : routes) {
            if (r.segments().length != actual.length) continue;
            var params = new LinkedHashMap<String, String>();
            boolean match = true;
            for (int i = 0; i < actual.length; i++) {
                String seg = r.segments()[i];
                if (seg.startsWith("{") && seg.endsWith("}")) {
                    params.put(seg.substring(1, seg.length() - 1), actual[i]);
                } else if (!seg.equals(actual[i])) {
                    match = false;
                    break;
                }
            }
            if (!match) continue;
            if (!r.method().equals(req.method)) {
                pathMatched = true;
                continue;
            }
            var withParams = new Request(req.method, req.path, params, req.query, req.headers, req.body);
            withParams.auth = req.auth;
            return r.handler().handle(withParams);
        }
        if (pathMatched) throw new ApiException(405, "METHOD_NOT_ALLOWED", "方法不允许");
        throw ApiException.notFound("NOT_FOUND", "路由不存在: " + req.method + " " + req.path);
    }

    private static String[] split(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (p.isEmpty()) return new String[0];
        return p.split("/");
    }
}

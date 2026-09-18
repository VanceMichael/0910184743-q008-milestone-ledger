package com.example.milestone.http;

import com.example.milestone.auth.AuthService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/** JDK 内置 HTTP 服务器适配层；虚拟线程执行，阻塞式 JDBC 无需额外配置。 */
public final class Server {
    private static final Logger LOG = Logger.getLogger(Server.class.getName());

    private Server() {}

    public static HttpServer create(int port, Router router, AuthService auth) throws IOException {
        var server = HttpServer.create(new java.net.InetSocketAddress(port), 0);
        server.createContext("/", exchange -> handle(exchange, router, auth));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        return server;
    }

    private static void handle(HttpExchange exchange, Router router, AuthService auth) {
        try (exchange) {
            Response resp;
            try {
                Request req = toRequest(exchange);
                if (req.path.startsWith("/api/")) {
                    req.auth = auth.authenticate(req);
                }
                resp = router.dispatch(req);
            } catch (ApiException e) {
                resp = Response.error(e.status, e.code, e.getMessage());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "未处理异常: " + exchange.getRequestMethod() + " " + exchange.getRequestURI(), e);
                resp = Response.error(500, "INTERNAL", "内部错误");
            }
            write(exchange, resp);
        } catch (IOException e) {
            LOG.log(Level.FINE, "连接已断开", e);
        }
    }

    private static Request toRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        Map<String, String> query = new LinkedHashMap<>();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    query.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        byte[] body = exchange.getRequestBody().readAllBytes();
        return new Request(exchange.getRequestMethod(), path, Map.of(), query, exchange.getRequestHeaders(), body);
    }

    private static void write(HttpExchange exchange, Response resp) throws IOException {
        resp.headers.forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
        exchange.sendResponseHeaders(resp.status, resp.body.length == 0 ? -1 : resp.body.length);
        if (resp.body.length > 0) {
            exchange.getResponseBody().write(resp.body);
        }
    }
}

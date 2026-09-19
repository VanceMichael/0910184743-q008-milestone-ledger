package com.example.milestone.support;

import com.example.milestone.AppFactory;
import com.example.milestone.db.Migrator;
import com.example.milestone.fault.FaultInjector;
import com.example.milestone.http.HttpApp;
import com.example.milestone.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

/**
 * 集成测试环境：进程内真实 PostgreSQL 16 + 完整应用（迁移 + HTTP 服务）。
 * restartApp() 在同一数据库上重建应用层，模拟 Compose 重启应用容器。
 */
public final class TestEnv implements AutoCloseable {

    public record Resp(int status, JsonNode json, boolean replay) {
        public String errorCode() {
            return json != null && json.has("error") ? json.get("error").get("code").asText() : null;
        }
    }

    public final EmbeddedPostgres pg;
    public HikariDataSource ds;
    public FaultInjector faults;
    public HttpApp app;
    public final HttpClient client = HttpClient.newHttpClient();

    private TestEnv(EmbeddedPostgres pg) {
        this.pg = pg;
    }

    public static TestEnv start() throws Exception {
        var env = new TestEnv(EmbeddedPostgres.start());
        env.startApp();
        return env;
    }

    /** 在同一数据库上（重新）启动应用层：重跑迁移（幂等）并拉起 HTTP 服务。 */
    public void startApp() throws Exception {
        var hc = new HikariConfig();
        hc.setJdbcUrl(pg.getJdbcUrl("postgres", "postgres"));
        hc.setUsername("postgres");
        hc.setMaximumPoolSize(8);
        ds = new HikariDataSource(hc);
        new Migrator(ds).migrate();
        faults = new FaultInjector();
        app = AppFactory.create(ds, faults, 0);
        app.start();
    }

    /** 模拟容器重启：关闭应用层（连接池 + HTTP），数据库保持运行。 */
    public void stopApp() {
        if (app != null) app.close();
        if (ds != null) ds.close();
    }

    public String url(String path) {
        return "http://127.0.0.1:" + app.port() + path;
    }

    public Resp post(String path, JsonNode body, UUID caller, String idempotencyKey) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url(path)))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .header("Content-Type", "application/json");
        if (caller != null) builder.header("X-Party-Id", caller.toString());
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        return send(builder.build());
    }

    public Resp get(String path, UUID caller) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url(path))).GET();
        if (caller != null) builder.header("X-Party-Id", caller.toString());
        return send(builder.build());
    }

    private Resp send(HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = response.body().isEmpty() ? null : Json.parse(response.body());
        boolean replay = "true".equalsIgnoreCase(
                response.headers().firstValue("Idempotent-Replay").orElse("false"));
        return new Resp(response.statusCode(), json, replay);
    }

    /** 直接 SQL 断言辅助。 */
    public long count(String sql) throws Exception {
        try (var c = pg.getPostgresDatabase().getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Override
    public void close() {
        stopApp();
        try {
            pg.close();
        } catch (Exception e) {
            // 关闭失败不影响测试结果
        }
    }
}

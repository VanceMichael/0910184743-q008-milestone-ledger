package com.example.milestone.service;

import com.example.milestone.http.Response;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;

/**
 * 幂等决定表：同一 (project, action, key) 只执行一次，重试按字节回放原决定。
 * 决定与业务写入在同一事务提交；事务回滚则决定一并消失，重试可安全重放。
 * 并发重复键通过咨询锁串行化，后到者看到已提交的决定并回放。
 */
public final class Idempotency {
    private Idempotency() {}

    public record Decision(int status, String body) {
        /** 原决定原样回放：状态码、响应体字节一致，仅追加回放标记头。 */
        public Response toResponse() {
            var headers = new LinkedHashMap<String, String>();
            headers.put("Content-Type", "application/json; charset=utf-8");
            headers.put("X-Idempotent-Replay", "true");
            return new Response(status, body.getBytes(StandardCharsets.UTF_8), headers);
        }
    }

    /** 在事务内调用；返回已存储的决定（应直接回放），空表示首次执行。 */
    public static Optional<Decision> begin(Connection c, UUID projectId, String action, String key, String subject)
            throws SQLException {
        try (var ps = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            ps.setString(1, projectId + "|" + action + "|" + key);
            ps.execute();
        }
        try (var ps = c.prepareStatement(
                "SELECT http_status, response FROM idempotency_keys WHERE project_id = ? AND action = ? AND idem_key = ?")) {
            ps.setObject(1, projectId);
            ps.setString(2, action);
            ps.setString(3, key);
            var rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(new Decision(rs.getInt("http_status"), rs.getString("response")));
        }
    }

    public static void record(Connection c, UUID projectId, String action, String key, String subject, Response resp)
            throws SQLException {
        try (var ps = c.prepareStatement(
                "INSERT INTO idempotency_keys (project_id, action, idem_key, subject, http_status, response) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, projectId);
            ps.setString(2, action);
            ps.setString(3, key);
            ps.setString(4, subject);
            ps.setInt(5, resp.status);
            ps.setString(6, new String(resp.body, StandardCharsets.UTF_8));
            ps.executeUpdate();
        }
    }
}

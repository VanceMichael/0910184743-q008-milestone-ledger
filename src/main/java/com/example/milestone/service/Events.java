package com.example.milestone.service;

import com.example.milestone.auth.AuthContext;
import com.example.milestone.db.Db;
import com.example.milestone.util.CanonicalJson;
import com.example.milestone.util.Hashes;
import com.example.milestone.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

/** 证据时间线的写入原语：事件追加与里程碑投影，始终由外层事务包裹。 */
public final class Events {
    private Events() {}

    public record Inserted(long seq, UUID id) {}

    /**
     * 追加一条不可变事件。负载先经 jsonb 规范化再算内容哈希，
     * 保证事后用库存 payload 重算的哈希与 payload_hash 一致。
     */
    public static Inserted insert(Connection c, AuthContext auth, UUID projectId, UUID versionId,
                                  UUID milestoneId, String eventType, JsonNode payload) throws SQLException {
        String normalized;
        try (var ps = c.prepareStatement("SELECT ?::jsonb::text")) {
            ps.setString(1, payload.toString());
            var rs = ps.executeQuery();
            rs.next();
            normalized = rs.getString(1);
        }
        String hash = Hashes.sha256Hex(CanonicalJson.canonical(Json.parseCanonical(normalized)));
        UUID id = UUID.randomUUID();
        try (var ps = c.prepareStatement(
                "INSERT INTO evidence_events (id, project_id, version_id, milestone_id, event_type, subject, cert_id,"
                        + " payload, payload_hash, signature, signed_document)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) RETURNING seq")) {
            ps.setObject(1, id);
            ps.setObject(2, projectId);
            ps.setObject(3, versionId);
            ps.setObject(4, milestoneId);
            ps.setString(5, eventType);
            ps.setString(6, auth.subject());
            ps.setObject(7, auth.certId());
            ps.setString(8, normalized);
            ps.setString(9, hash);
            ps.setBytes(10, auth.signature());
            ps.setString(11, auth.signedDocument());
            var rs = ps.executeQuery();
            rs.next();
            return new Inserted(rs.getLong(1), id);
        }
    }

    /** 在指定事件序号处落一行里程碑投影（时点快照的数据源）。 */
    public static void insertProjection(Connection c, UUID milestoneId, long seq) throws SQLException {
        UUID projectId;
        UUID versionId;
        String status;
        try (var ps = c.prepareStatement("SELECT project_id, version_id, status FROM milestones WHERE id = ?")) {
            ps.setObject(1, milestoneId);
            var rs = ps.executeQuery();
            if (!rs.next()) throw new IllegalStateException("里程碑不存在: " + milestoneId);
            projectId = rs.getObject(1, UUID.class);
            versionId = rs.getObject(2, UUID.class);
            status = rs.getString(3);
        }
        String evidenceSet;
        try (var ps = c.prepareStatement(
                "SELECT COALESCE(jsonb_agg(DISTINCT payload->>'evidenceType'), '[]'::jsonb)::text "
                        + "FROM evidence_events WHERE milestone_id = ? AND event_type = 'EVIDENCE_SUBMITTED'")) {
            ps.setObject(1, milestoneId);
            var rs = ps.executeQuery();
            rs.next();
            evidenceSet = rs.getString(1);
        }
        String approvals;
        try (var ps = c.prepareStatement(
                "SELECT COALESCE(jsonb_agg(jsonb_build_object("
                        + "'role', role, 'subject', subject, 'decidedAt', decided_at) ORDER BY role), '[]'::jsonb)::text "
                        + "FROM approvals WHERE milestone_id = ?")) {
            ps.setObject(1, milestoneId);
            var rs = ps.executeQuery();
            rs.next();
            approvals = rs.getString(1);
        }
        UUID disbursementId = null;
        try (var ps = c.prepareStatement("SELECT id FROM disbursements WHERE milestone_id = ?")) {
            ps.setObject(1, milestoneId);
            var rs = ps.executeQuery();
            if (rs.next()) disbursementId = rs.getObject(1, UUID.class);
        }
        Timestamp eventTime;
        try (var ps = c.prepareStatement("SELECT created_at FROM evidence_events WHERE seq = ?")) {
            ps.setLong(1, seq);
            var rs = ps.executeQuery();
            rs.next();
            eventTime = rs.getTimestamp(1);
        }
        try (var ps = c.prepareStatement(
                "INSERT INTO milestone_projections (milestone_id, seq, project_id, version_id, status,"
                        + " evidence_set, approvals, disbursement_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)")) {
            ps.setObject(1, milestoneId);
            ps.setLong(2, seq);
            ps.setObject(3, projectId);
            ps.setObject(4, versionId);
            ps.setString(5, status);
            ps.setObject(6, Db.jsonb(evidenceSet));
            ps.setObject(7, Db.jsonb(approvals));
            ps.setObject(8, disbursementId);
            ps.setTimestamp(9, eventTime);
            ps.executeUpdate();
        }
    }
}

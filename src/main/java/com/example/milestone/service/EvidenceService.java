package com.example.milestone.service;

import com.example.milestone.auth.AuthContext;
import com.example.milestone.auth.Role;
import com.example.milestone.db.Db;
import com.example.milestone.http.ApiException;
import com.example.milestone.http.Response;
import com.example.milestone.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** 证据事件提交：写入当前 ACTIVE 版本的不可变时间线；过期版本只能归档。 */
public final class EvidenceService {
    private static final String ACTION = "event";

    private final Db db;

    public EvidenceService(Db db) {
        this.db = db;
    }

    public Response submit(AuthContext auth, UUID projectId, String idemKey, JsonNode body) {
        UUID versionId = Validate.requireUuid(body, "versionId");
        UUID milestoneId = body.hasNonNull("milestoneId") ? Validate.requireUuid(body, "milestoneId") : null;
        String eventType = Validate.requireText(body, "eventType");
        if (!"EVIDENCE_SUBMITTED".equals(eventType)) {
            throw ApiException.unprocessable("BAD_EVENT_TYPE", "该端点只接受 EVIDENCE_SUBMITTED");
        }
        JsonNode payload = body.get("payload");
        if (payload == null || !payload.isObject()) {
            throw ApiException.unprocessable("MISSING_FIELD", "payload 必须是 JSON 对象");
        }
        JsonNode evidenceType = payload.get("evidenceType");
        if (evidenceType == null || !evidenceType.isTextual() || evidenceType.asText().isBlank()) {
            throw ApiException.unprocessable("EVIDENCE_TYPE_REQUIRED", "payload.evidenceType 不能为空");
        }
        final UUID mid = milestoneId;
        return db.tx(conn -> {
            Optional<Idempotency.Decision> replay =
                    Idempotency.begin(conn, projectId, ACTION, idemKey, auth.subject());
            if (replay.isPresent()) return replay.get().toResponse();

            Permissions.requireWriteRole(conn, projectId, auth,
                    Role.PROJECT_PARTY, Role.RIGHTS_HOLDER, Role.VERIFIER);

            String versionStatus;
            int versionNo;
            try (var ps = conn.prepareStatement(
                    "SELECT status, version_no FROM project_versions WHERE id = ? AND project_id = ? FOR UPDATE")) {
                ps.setObject(1, versionId);
                ps.setObject(2, projectId);
                var rs = ps.executeQuery();
                if (!rs.next()) throw ApiException.notFound("VERSION_NOT_FOUND", "版本不存在");
                versionStatus = rs.getString("status");
                versionNo = rs.getInt("version_no");
            }
            switch (versionStatus) {
                case "EXPIRED" -> {
                    return decision(conn, auth, projectId, ACTION, idemKey, 409, "VERSION_EXPIRED",
                            "版本 v" + versionNo + " 已过期：只能归档，不能推进当前项目");
                }
                case "ARCHIVED" -> {
                    return decision(conn, auth, projectId, ACTION, idemKey, 409, "VERSION_ARCHIVED",
                            "版本 v" + versionNo + " 已归档，时间线封存");
                }
                default -> { /* ACTIVE → 继续 */ }
            }
            String milestoneCode = null;
            if (mid != null) {
                // 与审批共用里程碑行锁：证据终结放款时与并发审批串行化
                try (var ps = conn.prepareStatement(
                        "SELECT code FROM milestones WHERE id = ? AND version_id = ? FOR UPDATE")) {
                    ps.setObject(1, mid);
                    ps.setObject(2, versionId);
                    var rs = ps.executeQuery();
                    if (!rs.next()) {
                        throw ApiException.unprocessable("MILESTONE_VERSION_MISMATCH", "里程碑不属于该版本");
                    }
                    milestoneCode = rs.getString(1);
                }
            }

            Events.Inserted ins = Events.insert(conn, auth, projectId, versionId, mid, eventType, payload);
            if (mid != null) {
                try (var ps = conn.prepareStatement(
                        "UPDATE milestones SET status = 'IN_REVIEW' WHERE id = ? AND status = 'PENDING'")) {
                    ps.setObject(1, mid);
                    ps.executeUpdate();
                }
                // 证据也可能是放款条件的最后一块拼图
                Optional<Finalizer.Disbursed> disbursed =
                        Finalizer.tryFinalize(conn, auth, projectId, versionId, mid, milestoneCode);
                Events.insertProjection(conn, mid, ins.seq());
                var resp = Json.obj();
                resp.put("eventId", ins.id().toString());
                resp.put("seq", ins.seq());
                resp.put("projectId", projectId.toString());
                resp.put("versionId", versionId.toString());
                resp.put("milestoneId", mid.toString());
                if (disbursed.isPresent()) {
                    Events.insertProjection(conn, mid, disbursed.get().eventSeq());
                    resp.put("milestoneStatus", "DISBURSED");
                    resp.put("disbursementId", disbursed.get().disbursementId().toString());
                }
                Response response = Response.json(201, resp);
                Idempotency.record(conn, projectId, ACTION, idemKey, auth.subject(), response);
                return response;
            }
            var resp = Json.obj();
            resp.put("eventId", ins.id().toString());
            resp.put("seq", ins.seq());
            resp.put("projectId", projectId.toString());
            resp.put("versionId", versionId.toString());
            Response response = Response.json(201, resp);
            Idempotency.record(conn, projectId, ACTION, idemKey, auth.subject(), response);
            return response;
        });
    }

    /** 记录一条领域决定（含拒绝），与已完成的读取同事务提交；重试同键回放原决定。 */
    static Response decision(Connection conn, AuthContext auth, UUID projectId, String action, String key,
                             int status, String code, String message) throws SQLException {
        var body = Json.obj();
        var err = body.putObject("error");
        err.put("code", code);
        err.put("message", message);
        Response resp = Response.json(status, body);
        Idempotency.record(conn, projectId, action, key, auth.subject(), resp);
        return resp;
    }
}

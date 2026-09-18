package com.example.milestone.service;

import com.example.milestone.auth.AuthContext;
import com.example.milestone.auth.Role;
import com.example.milestone.db.Db;
import com.example.milestone.http.ApiException;
import com.example.milestone.http.Response;
import com.example.milestone.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 审批：两个审批方并发确认时，以里程碑行锁串行化；
 * 证据集合、里程碑投影与放款指令在同一事务提交，全库至多一笔放款。
 */
public final class ApprovalService {
    private static final String ACTION = "approval";

    private final Db db;

    public ApprovalService(Db db) {
        this.db = db;
    }

    public Response approve(AuthContext auth, UUID projectId, UUID milestoneId, String idemKey, JsonNode body) {
        String comment = body != null && body.hasNonNull("comment") ? body.get("comment").asText() : null;
        return db.tx(conn -> {
            Optional<Idempotency.Decision> replay =
                    Idempotency.begin(conn, projectId, ACTION, idemKey, auth.subject());
            if (replay.isPresent()) return replay.get().toResponse();

            // 行锁：并发审批在此串行化，后到者读到先到者已提交的审批
            MilestoneRow m = lockMilestone(conn, projectId, milestoneId);
            // 先权限、后状态：无审批资格一律 403（不记录决定），避免向越权者泄露里程碑状态
            Role role = Permissions.roleOf(conn, projectId, auth.subject())
                    .orElseThrow(() -> ApiException.forbidden("主体在该项目上没有角色"));
            Set<String> requiredApprovers = Finalizer.requiredApprovers(conn, milestoneId);
            if (!requiredApprovers.contains(role.name())) {
                throw ApiException.forbidden("角色 " + role + " 不是该里程碑的审批方");
            }
            if (!"ACTIVE".equals(m.versionStatus)) {
                return EvidenceService.decision(conn, auth, projectId, ACTION, idemKey, 409, "VERSION_NOT_ACTIVE",
                        "里程碑所属版本状态为 " + m.versionStatus + "，不能审批");
            }
            if ("DISBURSED".equals(m.status)) {
                return EvidenceService.decision(conn, auth, projectId, ACTION, idemKey, 409, "ALREADY_DISBURSED",
                        "里程碑已放款，审批已终结");
            }
            if (Finalizer.approvedRoles(conn, milestoneId).contains(role.name())) {
                return EvidenceService.decision(conn, auth, projectId, ACTION, idemKey, 409, "ROLE_ALREADY_APPROVED",
                        "角色 " + role + " 已批准过该里程碑");
            }

            var payload = Json.obj();
            payload.put("decision", "APPROVE");
            payload.put("role", role.name());
            if (comment != null) payload.put("comment", comment);
            Events.Inserted approvalEvent =
                    Events.insert(conn, auth, projectId, m.versionId, milestoneId, "APPROVAL_GRANTED", payload);
            try (var ps = conn.prepareStatement(
                    "INSERT INTO approvals (milestone_id, role, subject, event_seq) VALUES (?, ?, ?, ?)")) {
                ps.setObject(1, milestoneId);
                ps.setString(2, role.name());
                ps.setString(3, auth.subject());
                ps.setLong(4, approvalEvent.seq());
                ps.executeUpdate();
            }
            TestHooks.checkpoint(TestHooks.Point.AFTER_APPROVAL_INSERT);

            try (var ps = conn.prepareStatement(
                    "UPDATE milestones SET status = 'IN_REVIEW' WHERE id = ? AND status = 'PENDING'")) {
                ps.setObject(1, milestoneId);
                ps.executeUpdate();
            }
            Optional<Finalizer.Disbursed> disbursed =
                    Finalizer.tryFinalize(conn, auth, projectId, m.versionId, milestoneId, m.code);
            Events.insertProjection(conn, milestoneId, approvalEvent.seq());

            Response response;
            if (disbursed.isPresent()) {
                Events.insertProjection(conn, milestoneId, disbursed.get().eventSeq());
                var resp = Json.obj();
                resp.put("milestoneId", milestoneId.toString());
                resp.put("status", "DISBURSED");
                resp.put("disbursementId", disbursed.get().disbursementId().toString());
                resp.put("eventSeq", disbursed.get().eventSeq());
                response = Response.json(201, resp);
            } else {
                var resp = Json.obj();
                resp.put("milestoneId", milestoneId.toString());
                resp.put("status", "IN_REVIEW");
                resp.put("eventSeq", approvalEvent.seq());
                resp.set("approvedRoles", toArray(Finalizer.approvedRoles(conn, milestoneId)));
                Set<String> pendingRoles = Finalizer.requiredApprovers(conn, milestoneId);
                pendingRoles.removeAll(Finalizer.approvedRoles(conn, milestoneId));
                resp.set("pendingRoles", toArray(pendingRoles));
                Set<String> pendingEvidence = Finalizer.requiredEvidence(conn, milestoneId);
                pendingEvidence.removeAll(Finalizer.submittedEvidence(conn, milestoneId));
                resp.set("pendingEvidence", toArray(pendingEvidence));
                response = Response.json(202, resp);
            }
            Idempotency.record(conn, projectId, ACTION, idemKey, auth.subject(), response);
            return response;
        });
    }

    private record MilestoneRow(String status, String code, UUID versionId, String versionStatus) {}

    private static MilestoneRow lockMilestone(Connection conn, UUID projectId, UUID milestoneId)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT m.status, m.code, m.version_id, v.status AS version_status "
                        + "FROM milestones m JOIN project_versions v ON v.id = m.version_id "
                        + "WHERE m.id = ? AND m.project_id = ? FOR UPDATE OF m")) {
            ps.setObject(1, milestoneId);
            ps.setObject(2, projectId);
            var rs = ps.executeQuery();
            if (!rs.next()) throw ApiException.notFound("MILESTONE_NOT_FOUND", "里程碑不存在");
            return new MilestoneRow(
                    rs.getString("status"),
                    rs.getString("code"),
                    rs.getObject("version_id", UUID.class),
                    rs.getString("version_status"));
        }
    }

    private static ArrayNode toArray(Set<String> values) {
        ArrayNode arr = Json.MAPPER.createArrayNode();
        values.stream().sorted().forEach(arr::add);
        return arr;
    }
}

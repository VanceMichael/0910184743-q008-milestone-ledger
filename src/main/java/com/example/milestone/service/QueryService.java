package com.example.milestone.service;

import com.example.milestone.auth.AuthContext;
import com.example.milestone.auth.Role;
import com.example.milestone.crypto.SignatureService;
import com.example.milestone.db.Db;
import com.example.milestone.http.ApiException;
import com.example.milestone.http.Response;
import com.example.milestone.util.CanonicalJson;
import com.example.milestone.util.Hashes;
import com.example.milestone.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 只读查询：里程碑视图、时点快照、待补证据、版本差异、审计时间线、历史验签。 */
public final class QueryService {
    private final Db db;

    public QueryService(Db db) {
        this.db = db;
    }

    public Response milestoneView(AuthContext auth, UUID projectId, UUID milestoneId) {
        return db.read(conn -> {
            Role role = Permissions.requireReadRole(conn, projectId, auth);
            ObjectNode view = buildMilestoneView(conn, projectId, milestoneId);
            FieldTrimmer.trimView(view, role);
            return Response.json(200, view);
        });
    }

    /** 指定时点快照：取 created_at <= at 的最后一行投影。 */
    public Response snapshot(AuthContext auth, UUID projectId, UUID milestoneId, Instant at) {
        return db.read(conn -> {
            Role role = Permissions.requireReadRole(conn, projectId, auth);
            try (var ps = conn.prepareStatement(
                    "SELECT seq, status, evidence_set::text, approvals::text, disbursement_id, created_at "
                            + "FROM milestone_projections WHERE milestone_id = ? AND project_id = ? AND created_at <= ? "
                            + "ORDER BY seq DESC LIMIT 1")) {
                ps.setObject(1, milestoneId);
                ps.setObject(2, projectId);
                ps.setTimestamp(3, Timestamp.from(at));
                var rs = ps.executeQuery();
                if (!rs.next()) throw ApiException.notFound("SNAPSHOT_NOT_FOUND", "该时点之前没有投影记录");
                var view = Json.obj();
                view.put("milestoneId", milestoneId.toString());
                view.put("asOf", at.toString());
                view.put("seq", rs.getLong("seq"));
                view.put("status", rs.getString("status"));
                view.set("evidenceSet", Json.parse(rs.getString("evidence_set")));
                view.set("approvals", Json.parse(rs.getString("approvals")));
                var commercial = view.putObject("commercial");
                UUID disbId = rs.getObject("disbursement_id", UUID.class);
                if (disbId != null) commercial.put("disbursementId", disbId.toString());
                view.put("projectedAt", rs.getTimestamp("created_at").toInstant().toString());
                FieldTrimmer.trimView(view, role);
                return Response.json(200, view);
            }
        });
    }

    public Response pendingEvidence(AuthContext auth, UUID projectId, UUID milestoneId) {
        return db.read(conn -> {
            Permissions.requireReadRole(conn, projectId, auth);
            ensureMilestone(conn, projectId, milestoneId);
            Set<String> required = Finalizer.requiredEvidence(conn, milestoneId);
            Set<String> submitted = Finalizer.submittedEvidence(conn, milestoneId);
            Set<String> pending = new LinkedHashSet<>(required);
            pending.removeAll(submitted);
            var resp = Json.obj();
            resp.put("milestoneId", milestoneId.toString());
            resp.set("required", sortedArray(required));
            resp.set("submitted", sortedArray(submitted));
            resp.set("pending", sortedArray(pending));
            return Response.json(200, resp);
        });
    }

    /** 审计时间线：seq 为单调递增游标，重启后继续递增，客户端用 afterSeq 续读。 */
    public Response timeline(AuthContext auth, UUID projectId, long afterSeq, int limit) {
        return db.read(conn -> {
            Role role = Permissions.requireReadRole(conn, projectId, auth);
            ArrayNode events = Json.MAPPER.createArrayNode();
            long next = afterSeq;
            try (var ps = conn.prepareStatement(
                    "SELECT seq, id, version_id, milestone_id, event_type, subject, cert_id, payload::text,"
                            + " payload_hash, created_at FROM evidence_events "
                            + "WHERE project_id = ? AND seq > ? ORDER BY seq LIMIT ?")) {
                ps.setObject(1, projectId);
                ps.setLong(2, afterSeq);
                ps.setInt(3, limit);
                var rs = ps.executeQuery();
                while (rs.next()) {
                    var e = Json.obj();
                    e.put("seq", rs.getLong("seq"));
                    e.put("id", rs.getObject("id", UUID.class).toString());
                    e.put("versionId", rs.getObject("version_id", UUID.class).toString());
                    UUID mid = rs.getObject("milestone_id", UUID.class);
                    if (mid != null) e.put("milestoneId", mid.toString());
                    e.put("eventType", rs.getString("event_type"));
                    e.put("subject", rs.getString("subject"));
                    e.put("certId", rs.getObject("cert_id", UUID.class).toString());
                    e.set("payload", Json.parse(rs.getString("payload")));
                    e.put("payloadHash", rs.getString("payload_hash"));
                    e.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    FieldTrimmer.trimTimelineEvent(e, role);
                    events.add(e);
                    next = rs.getLong("seq");
                }
            }
            var resp = Json.obj();
            resp.put("projectId", projectId.toString());
            resp.set("events", events);
            resp.put("nextAfterSeq", next);
            return Response.json(200, resp);
        });
    }

    /** 两个版本间的证据集合与里程碑状态差异。 */
    public Response versionDiff(AuthContext auth, UUID projectId, int fromNo, int toNo) {
        return db.read(conn -> {
            Permissions.requireReadRole(conn, projectId, auth);
            Map<Integer, UUID> versions = new LinkedHashMap<>();
            try (var ps = conn.prepareStatement(
                    "SELECT id, version_no FROM project_versions WHERE project_id = ? AND version_no IN (?, ?)")) {
                ps.setObject(1, projectId);
                ps.setInt(2, fromNo);
                ps.setInt(3, toNo);
                var rs = ps.executeQuery();
                while (rs.next()) versions.put(rs.getInt("version_no"), rs.getObject("id", UUID.class));
            }
            if (!versions.containsKey(fromNo) || !versions.containsKey(toNo)) {
                throw ApiException.notFound("VERSION_NOT_FOUND", "版本号不存在: " + fromNo + " 或 " + toNo);
            }
            Map<String, long[]> evidenceCounts = new LinkedHashMap<>(); // type -> [from, to]
            try (var ps = conn.prepareStatement(
                    "SELECT v.version_no, e.payload->>'evidenceType' AS t, COUNT(*) "
                            + "FROM evidence_events e JOIN project_versions v ON v.id = e.version_id "
                            + "WHERE e.project_id = ? AND e.event_type = 'EVIDENCE_SUBMITTED' AND v.version_no IN (?, ?) "
                            + "GROUP BY 1, 2")) {
                ps.setObject(1, projectId);
                ps.setInt(2, fromNo);
                ps.setInt(3, toNo);
                var rs = ps.executeQuery();
                while (rs.next()) {
                    long[] pair = evidenceCounts.computeIfAbsent(rs.getString("t"), k -> new long[2]);
                    pair[rs.getInt("version_no") == fromNo ? 0 : 1] = rs.getLong(3);
                }
            }
            Map<String, String[]> milestoneStatus = new LinkedHashMap<>(); // code -> [fromStatus, toStatus]
            try (var ps = conn.prepareStatement(
                    "SELECT v.version_no, m.code, m.status FROM milestones m JOIN project_versions v ON v.id = m.version_id "
                            + "WHERE m.project_id = ? AND v.version_no IN (?, ?)")) {
                ps.setObject(1, projectId);
                ps.setInt(2, fromNo);
                ps.setInt(3, toNo);
                var rs = ps.executeQuery();
                while (rs.next()) {
                    String[] pair = milestoneStatus.computeIfAbsent(rs.getString("code"), k -> new String[2]);
                    pair[rs.getInt("version_no") == fromNo ? 0 : 1] = rs.getString("status");
                }
            }

            var resp = Json.obj();
            resp.put("projectId", projectId.toString());
            resp.put("fromVersion", fromNo);
            resp.put("toVersion", toNo);
            ArrayNode added = Json.MAPPER.createArrayNode();
            ArrayNode removed = Json.MAPPER.createArrayNode();
            ArrayNode countChanges = Json.MAPPER.createArrayNode();
            evidenceCounts.forEach((type, pair) -> {
                if (pair[0] == 0 && pair[1] > 0) added.add(type);
                else if (pair[0] > 0 && pair[1] == 0) removed.add(type);
                else if (pair[0] != pair[1]) {
                    var c = Json.obj();
                    c.put("evidenceType", type);
                    c.put("from", pair[0]);
                    c.put("to", pair[1]);
                    countChanges.add(c);
                }
            });
            var evidence = resp.putObject("evidence");
            evidence.set("addedInTo", added);
            evidence.set("removedInTo", removed);
            evidence.set("countChanges", countChanges);
            ArrayNode milestones = Json.MAPPER.createArrayNode();
            milestoneStatus.forEach((code, pair) -> {
                var m = Json.obj();
                m.put("code", code);
                if (pair[0] != null) m.put("fromStatus", pair[0]);
                if (pair[1] != null) m.put("toStatus", pair[1]);
                m.put("changed", !java.util.Objects.equals(pair[0], pair[1]));
                milestones.add(m);
            });
            resp.set("milestones", milestones);
            return Response.json(200, resp);
        });
    }

    /** 历史事件验签：用事件钉住的证书（可能已轮换）验证签名与内容哈希。 */
    public Response verifyEvent(AuthContext auth, UUID projectId, long seq) {
        return db.read(conn -> {
            Permissions.requireReadRole(conn, projectId, auth);
            try (var ps = conn.prepareStatement(
                    "SELECT e.event_type, e.subject, e.payload::text, e.payload_hash, e.signature, e.signed_document,"
                            + " e.created_at, c.id AS cert_id, c.subject AS cert_subject, c.public_key,"
                            + " c.valid_from, c.valid_to "
                            + "FROM evidence_events e JOIN certificates c ON c.id = e.cert_id "
                            + "WHERE e.project_id = ? AND e.seq = ?")) {
                ps.setObject(1, projectId);
                ps.setLong(2, seq);
                var rs = ps.executeQuery();
                if (!rs.next()) throw ApiException.notFound("EVENT_NOT_FOUND", "事件不存在");
                String payloadText = rs.getString("payload");
                String recomputed = Hashes.sha256Hex(
                        CanonicalJson.canonical(Json.parseCanonical(payloadText)));
                boolean hashMatch = recomputed.equals(rs.getString("payload_hash"));
                boolean signatureValid = SignatureService.verify(
                        rs.getBytes("public_key"),
                        rs.getString("signed_document").getBytes(StandardCharsets.UTF_8),
                        rs.getBytes("signature"));

                var resp = Json.obj();
                resp.put("seq", seq);
                resp.put("projectId", projectId.toString());
                resp.put("eventType", rs.getString("event_type"));
                resp.put("subject", rs.getString("subject"));
                resp.put("signatureValid", signatureValid);
                resp.put("payloadHashMatch", hashMatch);
                resp.put("payloadHash", rs.getString("payload_hash"));
                resp.put("signedDocument", rs.getString("signed_document"));
                var cert = resp.putObject("cert");
                cert.put("id", rs.getObject("cert_id", UUID.class).toString());
                cert.put("subject", rs.getString("cert_subject"));
                cert.put("validFrom", rs.getTimestamp("valid_from").toInstant().toString());
                Timestamp validTo = rs.getTimestamp("valid_to");
                if (validTo != null) cert.put("validTo", validTo.toInstant().toString());
                cert.put("rotatedOut", validTo != null);
                resp.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                return Response.json(200, resp);
            }
        });
    }

    private ObjectNode buildMilestoneView(Connection conn, UUID projectId, UUID milestoneId)
            throws SQLException {
        String code;
        String title;
        String status;
        String requiredEvidence;
        String requiredApprovers;
        String amount;
        int versionNo;
        UUID versionId;
        String versionStatus;
        try (var ps = conn.prepareStatement(
                "SELECT m.code, m.title, m.status, m.required_evidence::text, m.required_approvers::text,"
                        + " m.amount::text, m.version_id, v.version_no, v.status AS version_status "
                        + "FROM milestones m JOIN project_versions v ON v.id = m.version_id "
                        + "WHERE m.id = ? AND m.project_id = ?")) {
            ps.setObject(1, milestoneId);
            ps.setObject(2, projectId);
            var rs = ps.executeQuery();
            if (!rs.next()) throw ApiException.notFound("MILESTONE_NOT_FOUND", "里程碑不存在");
            code = rs.getString("code");
            title = rs.getString("title");
            status = rs.getString("status");
            requiredEvidence = rs.getString("required_evidence");
            requiredApprovers = rs.getString("required_approvers");
            amount = rs.getString("amount");
            versionId = rs.getObject("version_id", UUID.class);
            versionNo = rs.getInt("version_no");
            versionStatus = rs.getString("version_status");
        }

        var view = Json.obj();
        view.put("milestoneId", milestoneId.toString());
        view.put("projectId", projectId.toString());
        view.put("code", code);
        view.put("title", title);
        view.put("status", status);
        var version = view.putObject("version");
        version.put("id", versionId.toString());
        version.put("versionNo", versionNo);
        version.put("status", versionStatus);
        view.set("requiredEvidence", Json.parse(requiredEvidence));
        view.set("requiredApprovers", Json.parse(requiredApprovers));

        Set<String> submitted = Finalizer.submittedEvidence(conn, milestoneId);
        view.set("submittedEvidence", sortedArray(submitted));
        Set<String> pending = new LinkedHashSet<>(Finalizer.requiredEvidence(conn, milestoneId));
        pending.removeAll(submitted);
        view.set("pendingEvidence", sortedArray(pending));

        ArrayNode approvals = Json.MAPPER.createArrayNode();
        try (var ps = conn.prepareStatement(
                "SELECT role, subject, decided_at FROM approvals WHERE milestone_id = ? ORDER BY role")) {
            ps.setObject(1, milestoneId);
            var rs = ps.executeQuery();
            while (rs.next()) {
                var a = Json.obj();
                a.put("role", rs.getString("role"));
                a.put("subject", rs.getString("subject"));
                a.put("decidedAt", rs.getTimestamp("decided_at").toInstant().toString());
                approvals.add(a);
            }
        }
        view.set("approvals", approvals);

        // 商业块：金额、账户与放款指令，按角色裁剪
        var commercial = view.putObject("commercial");
        if (amount != null) commercial.put("amount", amount);
        try (var ps = conn.prepareStatement(
                "SELECT contract_amount::text, beneficiary_account FROM projects WHERE id = ?")) {
            ps.setObject(1, projectId);
            var rs = ps.executeQuery();
            if (rs.next()) {
                if (rs.getString(1) != null) commercial.put("contractAmount", rs.getString(1));
                if (rs.getString(2) != null) commercial.put("beneficiaryAccount", rs.getString(2));
            }
        }
        try (var ps = conn.prepareStatement(
                "SELECT id, amount::text, account, instruction::text, created_at FROM disbursements WHERE milestone_id = ?")) {
            ps.setObject(1, milestoneId);
            var rs = ps.executeQuery();
            if (rs.next()) {
                var d = commercial.putObject("disbursement");
                d.put("id", rs.getObject("id", UUID.class).toString());
                d.put("amount", rs.getString("amount"));
                d.put("account", rs.getString("account"));
                d.set("instruction", Json.parse(rs.getString("instruction")));
                d.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            }
        }
        return view;
    }

    private static void ensureMilestone(Connection conn, UUID projectId, UUID milestoneId) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT 1 FROM milestones WHERE id = ? AND project_id = ?")) {
            ps.setObject(1, milestoneId);
            ps.setObject(2, projectId);
            if (!ps.executeQuery().next()) throw ApiException.notFound("MILESTONE_NOT_FOUND", "里程碑不存在");
        }
    }

    private static ArrayNode sortedArray(Set<String> values) {
        ArrayNode arr = Json.MAPPER.createArrayNode();
        values.stream().sorted().forEach(arr::add);
        return arr;
    }
}

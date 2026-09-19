package com.example.milestone.store;

import com.example.milestone.domain.Rows.*;
import com.example.milestone.json.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 全部 SQL 的存放处。方法接收调用方事务内的 Connection，不自行提交。
 * 查询类方法以 find/list 开头，写类方法以 insert/update 开头。
 */
public final class LedgerStore {

    // ---------- 参与方与证书 ----------

    public void insertParty(Connection c, UUID id, String name, String role) throws SQLException {
        try (var ps = c.prepareStatement("INSERT INTO party(id, name, role) VALUES (?,?,?)")) {
            ps.setObject(1, id);
            ps.setString(2, name);
            ps.setString(3, role);
            ps.executeUpdate();
        }
    }

    public PartyRow findParty(Connection c, UUID id) throws SQLException {
        try (var ps = c.prepareStatement("SELECT id, name, role FROM party WHERE id = ?")) {
            ps.setObject(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? new PartyRow(id, rs.getString("name"), rs.getString("role")) : null;
            }
        }
    }

    public void insertCertificate(Connection c, UUID id, UUID partyId, String subjectCn, String pem,
                                  OffsetDateTime notBefore, OffsetDateTime notAfter) throws SQLException {
        try (var ps = c.prepareStatement(
                "INSERT INTO signing_certificate(id, party_id, subject_cn, public_key_pem, not_before, not_after)"
                        + " VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, partyId);
            ps.setString(3, subjectCn);
            ps.setString(4, pem);
            ps.setObject(5, notBefore);
            ps.setObject(6, notAfter);
            ps.executeUpdate();
        }
    }

    public CertificateRow findCertificate(Connection c, UUID id) throws SQLException {
        try (var ps = c.prepareStatement(
                "SELECT id, party_id, subject_cn, public_key_pem, not_before, not_after, revoked_at"
                        + " FROM signing_certificate WHERE id = ?")) {
            ps.setObject(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? mapCertificate(rs) : null;
            }
        }
    }

    public void revokeCertificate(Connection c, UUID id) throws SQLException {
        try (var ps = c.prepareStatement(
                "UPDATE signing_certificate SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL")) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
    }

    private CertificateRow mapCertificate(ResultSet rs) throws SQLException {
        return new CertificateRow(
                rs.getObject("id", UUID.class),
                rs.getObject("party_id", UUID.class),
                rs.getString("subject_cn"),
                rs.getString("public_key_pem"),
                rs.getObject("not_before", OffsetDateTime.class),
                rs.getObject("not_after", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class));
    }

    // ---------- 项目、权限、版本 ----------

    public void insertProject(Connection c, UUID id, String name) throws SQLException {
        try (var ps = c.prepareStatement("INSERT INTO project(id, name) VALUES (?,?)")) {
            ps.setObject(1, id);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    public boolean projectExists(Connection c, UUID id) throws SQLException {
        try (var ps = c.prepareStatement("SELECT 1 FROM project WHERE id = ?")) {
            ps.setObject(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void grantPermission(Connection c, UUID projectId, UUID partyId, String permission) throws SQLException {
        try (var ps = c.prepareStatement(
                "INSERT INTO project_permission(project_id, party_id, permission) VALUES (?,?,?)"
                        + " ON CONFLICT DO NOTHING")) {
            ps.setObject(1, projectId);
            ps.setObject(2, partyId);
            ps.setString(3, permission);
            ps.executeUpdate();
        }
    }

    public boolean hasPermission(Connection c, UUID projectId, UUID partyId, String permission) throws SQLException {
        try (var ps = c.prepareStatement(
                "SELECT 1 FROM project_permission WHERE project_id = ? AND party_id = ? AND permission = ?")) {
            ps.setObject(1, projectId);
            ps.setObject(2, partyId);
            ps.setString(3, permission);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void insertVersion(Connection c, UUID id, UUID projectId, int versionNo, String status) throws SQLException {
        try (var ps = c.prepareStatement(
                "INSERT INTO project_version(id, project_id, version_no, status) VALUES (?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, projectId);
            ps.setInt(3, versionNo);
            ps.setString(4, status);
            ps.executeUpdate();
        }
    }

    public VersionRow findVersion(Connection c, UUID id) throws SQLException {
        try (var ps = c.prepareStatement(
                "SELECT id, project_id, version_no, status FROM project_version WHERE id = ?")) {
            ps.setObject(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? mapVersion(rs) : null;
            }
        }
    }

    public VersionRow findCurrentVersion(Connection c, UUID projectId) throws SQLException {
        try (var ps = c.prepareStatement(
                "SELECT id, project_id, version_no, status FROM project_version"
                        + " WHERE project_id = ? AND status = 'CURRENT'")) {
            ps.setObject(1, projectId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? mapVersion(rs) : null;
            }
        }
    }

    public List<VersionRow> versionsOfProject(Connection c, UUID projectId) throws SQLException {
        try (var ps = c.prepareStatement(
                "SELECT id, project_id, version_no, status FROM project_version"
                        + " WHERE project_id = ? ORDER BY version_no")) {
            ps.setObject(1, projectId);
            try (var rs = ps.executeQuery()) {
                var out = new ArrayList<VersionRow>();
                while (rs.next()) out.add(mapVersion(rs));
                return out;
            }
        }
    }

    /** 将项目的 CURRENT 版本置为 EXPIRED，返回被置旧的版本；无 CURRENT 时返回 null。 */
    public VersionRow expireCurrentVersion(Connection c, UUID projectId) throws SQLException {
        try (var ps = c.prepareStatement(
                "UPDATE project_version SET status = 'EXPIRED', closed_at = now()"
                        + " WHERE project_id = ? AND status = 'CURRENT'"
                        + " RETURNING id, project_id, version_no, status")) {
            ps.setObject(1, projectId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? mapVersion(rs) : null;
            }
        }
    }

    /** 仅允许 EXPIRED -> ARCHIVED；返回受影响行数。 */
    public int archiveVersion(Connection c, UUID versionId) throws SQLException {
        try (var ps = c.prepareStatement(
                "UPDATE project_version SET status = 'ARCHIVED', closed_at = now()"
                        + " WHERE id = ? AND status = 'EXPIRED'")) {
            ps.setObject(1, versionId);
            return ps.executeUpdate();
        }
    }

    private VersionRow mapVersion(ResultSet rs) throws SQLException {
        return new VersionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getInt("version_no"),
                rs.getString("status"));
    }

    // ---------- 里程碑 ----------

    public void insertMilestone(Connection c, UUID id, UUID versionId, String code, String title,
                                BigDecimal amount, String currency,
                                JsonNode requiredEvidence, JsonNode requiredApproverRoles) throws SQLException {
        try (var ps = c.prepareStatement(
                "INSERT INTO milestone(id, project_version_id, code, title, amount, currency,"
                        + " required_evidence, required_approver_roles) VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb)")) {
            ps.setObject(1, id);
            ps.setObject(2, versionId);
            ps.setString(3, code);
            ps.setString(4, title);
            ps.setBigDecimal(5, amount);
            ps.setString(6, currency);
            ps.setString(7, Json.write(requiredEvidence));
            ps.setString(8, Json.write(requiredApproverRoles));
            ps.executeUpdate();
        }
    }

    private static final String MILESTONE_SELECT =
            "SELECT m.id, m.project_version_id, m.code, m.title, m.amount, m.currency,"
                    + " m.required_evidence, m.required_approver_roles, m.status,"
                    + " v.project_id, v.version_no, v.status AS version_status"
                    + " FROM milestone m JOIN project_version v ON v.id = m.project_version_id";

    public MilestoneRow findMilestone(Connection c, UUID id) throws SQLException {
        try (var ps = c.prepareStatement(MILESTONE_SELECT + " WHERE m.id = ?")) {
            ps.setObject(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? mapMilestone(rs) : null;
            }
        }
    }

    /** 行锁：并发审批在此串行化，保证“审批+投影+放款”原子推进。 */
    public MilestoneRow lockMilestone(Connection c, UUID id) throws SQLException {
        try (var ps = c.prepareStatement(MILESTONE_SELECT + " WHERE m.id = ? FOR UPDATE OF m")) {
            ps.setObject(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? mapMilestone(rs) : null;
            }
        }
    }

    public List<MilestoneRow> milestonesOfVersion(Connection c, UUID versionId) throws SQLException {
        try (var ps = c.prepareStatement(MILESTONE_SELECT + " WHERE m.project_version_id = ? ORDER BY m.code")) {
            ps.setObject(1, versionId);
            try (var rs = ps.executeQuery()) {
                var out = new ArrayList<MilestoneRow>();
                while (rs.next()) out.add(mapMilestone(rs));
                return out;
            }
        }
    }

    public void updateMilestoneStatus(Connection c, UUID id, String status) throws SQLException {
        try (var ps = c.prepareStatement("UPDATE milestone SET status = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setObject(2, id);
            ps.executeUpdate();
        }
    }

    private MilestoneRow mapMilestone(ResultSet rs) throws SQLException {
        var roles = new ArrayList<String>();
        for (JsonNode n : Json.parse(rs.getString("required_approver_roles"))) roles.add(n.asText());
        return new MilestoneRow(
                rs.getObject("id", UUID.class),
                rs.getObject("project_version_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getInt("version_no"),
                rs.getString("version_status"),
                rs.getString("code"),
                rs.getString("title"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                Json.parse(rs.getString("required_evidence")),
                roles,
                rs.getString("status"));
    }

    // ---------- 证据事件（不可变时间线） ----------

    /** 插入证据事件，返回 [seq, recorded_at]。 */
    public Object[] insertEvent(Connection c, UUID id, UUID versionId, UUID milestoneId, UUID partyId,
                                UUID certificateId, String eventType, JsonNode payload, String payloadHash,
                                String signature, OffsetDateTime occurredAt, String occurredAtText) throws SQLException {
        try (var ps = c.prepareStatement(
                "INSERT INTO evidence_event(id, project_version_id, milestone_id, party_id, certificate_id,"
                        + " event_type, payload, payload_hash, signature, occurred_at, occurred_at_text)"
                        + " VALUES (?,?,?,?,?,?,?::jsonb,?,?,?,?) RETURNING seq, recorded_at")) {
            ps.setObject(1, id);
            ps.setObject(2, versionId);
            ps.setObject(3, milestoneId);
            ps.setObject(4, partyId);
            ps.setObject(5, certificateId);
            ps.setString(6, eventType);
            ps.setString(7, Json.write(payload));
            ps.setString(8, payloadHash);
            ps.setString(9, signature);
            ps.setObject(10, occurredAt);
            ps.setString(11, occurredAtText);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return new Object[]{rs.getLong("seq"), rs.getObject("recorded_at", OffsetDateTime.class)};
            }
        }
    }

    private static final String EVENT_SELECT =
            "SELECT e.seq, e.id, e.project_version_id, e.milestone_id, e.party_id, p.role AS party_role,"
                    + " e.certificate_id, e.event_type, e.payload, e.payload_hash, e.signature,"
                    + " e.occurred_at, e.occurred_at_text, e.recorded_at"
                    + " FROM evidence_event e JOIN party p ON p.id = e.party_id";

    public EventRow findEvent(Connection c, UUID id) throws SQLException {
        try (var ps = c.prepareStatement(EVENT_SELECT + " WHERE e.id = ?")) {
            ps.setObject(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? mapEvent(rs) : null;
            }
        }
    }

    public List<EventRow> eventsOfMilestone(Connection c, UUID milestoneId) throws SQLException {
        try (var ps = c.prepareStatement(EVENT_SELECT + " WHERE e.milestone_id = ? ORDER BY e.seq")) {
            ps.setObject(1, milestoneId);
            try (var rs = ps.executeQuery()) {
                var out = new ArrayList<EventRow>();
                while (rs.next()) out.add(mapEvent(rs));
                return out;
            }
        }
    }

    /** 时点快照用：recorded_at <= at 的事件。 */
    public List<EventRow> eventsOfMilestoneAt(Connection c, UUID milestoneId, OffsetDateTime at) throws SQLException {
        try (var ps = c.prepareStatement(
                EVENT_SELECT + " WHERE e.milestone_id = ? AND e.recorded_at <= ? ORDER BY e.seq")) {
            ps.setObject(1, milestoneId);
            ps.setObject(2, at);
            try (var rs = ps.executeQuery()) {
                var out = new ArrayList<EventRow>();
                while (rs.next()) out.add(mapEvent(rs));
                return out;
            }
        }
    }

    public long countEventsOfVersion(Connection c, UUID versionId) throws SQLException {
        try (var ps = c.prepareStatement(
                "SELECT count(*) FROM evidence_event WHERE project_version_id = ?")) {
            ps.setObject(1, versionId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private EventRow mapEvent(ResultSet rs) throws SQLException {
        return new EventRow(
                rs.getLong("seq"),
                rs.getObject("id", UUID.class),
                rs.getObject("project_version_id", UUID.class),
                rs.getObject("milestone_id", UUID.class),
                rs.getObject("party_id", UUID.class),
                rs.getString("party_role"),
                rs.getObject("certificate_id", UUID.class),
                rs.getString("event_type"),
                Json.parse(rs.getString("payload")),
                rs.getString("payload_hash"),
                rs.getString("signature"),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getString("occurred_at_text"),
                rs.getObject("recorded_at", OffsetDateTime.class));
    }

    // ---------- 审批 ----------

    public void insertApproval(Connection c, UUID milestoneId, UUID partyId, UUID certificateId,
                               String signature, OffsetDateTime approvedAt, String approvedAtText) throws SQLException {
        try (var ps = c.prepareStatement(
                "INSERT INTO milestone_approval(milestone_id, party_id, certificate_id, signature,"
                        + " approved_at, approved_at_text) VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, milestoneId);
            ps.setObject(2, partyId);
            ps.setObject(3, certificateId);
            ps.setString(4, signature);
            ps.setObject(5, approvedAt);
            ps.setString(6, approvedAtText);
            ps.executeUpdate();
        }
    }

    private static final String APPROVAL_SELECT =
            "SELECT a.milestone_id, a.party_id, p.role AS party_role, a.certificate_id, a.signature,"
                    + " a.approved_at, a.approved_at_text, a.recorded_at"
                    + " FROM milestone_approval a JOIN party p ON p.id = a.party_id";

    public List<ApprovalRow> approvalsOfMilestone(Connection c, UUID milestoneId) throws SQLException {
        try (var ps = c.prepareStatement(APPROVAL_SELECT + " WHERE a.milestone_id = ? ORDER BY a.recorded_at")) {
            ps.setObject(1, milestoneId);
            try (var rs = ps.executeQuery()) {
                var out = new ArrayList<ApprovalRow>();
                while (rs.next()) out.add(mapApproval(rs));
                return out;
            }
        }
    }

    public List<ApprovalRow> approvalsOfMilestoneAt(Connection c, UUID milestoneId, OffsetDateTime at) throws SQLException {
        try (var ps = c.prepareStatement(
                APPROVAL_SELECT + " WHERE a.milestone_id = ? AND a.recorded_at <= ? ORDER BY a.recorded_at")) {
            ps.setObject(1, milestoneId);
            ps.setObject(2, at);
            try (var rs = ps.executeQuery()) {
                var out = new ArrayList<ApprovalRow>();
                while (rs.next()) out.add(mapApproval(rs));
                return out;
            }
        }
    }

    private ApprovalRow mapApproval(ResultSet rs) throws SQLException {
        return new ApprovalRow(
                rs.getObject("milestone_id", UUID.class),
                rs.getObject("party_id", UUID.class),
                rs.getString("party_role"),
                rs.getObject("certificate_id", UUID.class),
                rs.getString("signature"),
                rs.getObject("approved_at", OffsetDateTime.class),
                rs.getString("approved_at_text"),
                rs.getObject("recorded_at", OffsetDateTime.class));
    }

    // ---------- 放款指令 ----------

    public void insertPayment(Connection c, UUID id, UUID milestoneId, UUID versionId,
                              BigDecimal amount, String currency, String evidenceSetHash) throws SQLException {
        try (var ps = c.prepareStatement(
                "INSERT INTO payment_instruction(id, milestone_id, project_version_id, amount, currency,"
                        + " evidence_set_hash) VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, milestoneId);
            ps.setObject(3, versionId);
            ps.setBigDecimal(4, amount);
            ps.setString(5, currency);
            ps.setString(6, evidenceSetHash);
            ps.executeUpdate();
        }
    }

    public PaymentRow findPayment(Connection c, UUID id) throws SQLException {
        try (var ps = c.prepareStatement(
                "SELECT id, milestone_id, project_version_id, amount, currency, evidence_set_hash, status, created_at"
                        + " FROM payment_instruction WHERE id = ?")) {
            ps.setObject(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? mapPayment(rs) : null;
            }
        }
    }

    public PaymentRow paymentOfMilestone(Connection c, UUID milestoneId) throws SQLException {
        try (var ps = c.prepareStatement(
                "SELECT id, milestone_id, project_version_id, amount, currency, evidence_set_hash, status, created_at"
                        + " FROM payment_instruction WHERE milestone_id = ?")) {
            ps.setObject(1, milestoneId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? mapPayment(rs) : null;
            }
        }
    }

    public PaymentRow paymentOfMilestoneAt(Connection c, UUID milestoneId, OffsetDateTime at) throws SQLException {
        try (var ps = c.prepareStatement(
                "SELECT id, milestone_id, project_version_id, amount, currency, evidence_set_hash, status, created_at"
                        + " FROM payment_instruction WHERE milestone_id = ? AND created_at <= ?")) {
            ps.setObject(1, milestoneId);
            ps.setObject(2, at);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? mapPayment(rs) : null;
            }
        }
    }

    public void insertPaymentEvidence(Connection c, UUID paymentId, long evidenceSeq) throws SQLException {
        try (var ps = c.prepareStatement(
                "INSERT INTO payment_evidence(payment_id, evidence_seq) VALUES (?,?)")) {
            ps.setObject(1, paymentId);
            ps.setLong(2, evidenceSeq);
            ps.executeUpdate();
        }
    }

    /** 放款对应的证据集合（按时间线顺序）。 */
    public List<EventRow> paymentEvidence(Connection c, UUID paymentId) throws SQLException {
        try (var ps = c.prepareStatement(
                EVENT_SELECT + " JOIN payment_evidence pe ON pe.evidence_seq = e.seq"
                        + " WHERE pe.payment_id = ? ORDER BY e.seq")) {
            ps.setObject(1, paymentId);
            try (var rs = ps.executeQuery()) {
                var out = new ArrayList<EventRow>();
                while (rs.next()) out.add(mapEvent(rs));
                return out;
            }
        }
    }

    private PaymentRow mapPayment(ResultSet rs) throws SQLException {
        return new PaymentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("milestone_id", UUID.class),
                rs.getObject("project_version_id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("evidence_set_hash"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    // ---------- 幂等 ----------

    /** 以 (key, party) 为粒度的事务级咨询锁：并发同键请求在此串行。 */
    public void lockIdempotencyKey(Connection c, String key, UUID partyId) throws SQLException {
        try (var ps = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            ps.setString(1, key + "|" + partyId);
            ps.execute();
        }
    }

    public IdempotencyRow findIdempotency(Connection c, String key, UUID partyId) throws SQLException {
        try (var ps = c.prepareStatement(
                "SELECT idempotency_key, party_id, request_hash, response_status, response_body"
                        + " FROM idempotency_record WHERE idempotency_key = ? AND party_id = ?")) {
            ps.setString(1, key);
            ps.setObject(2, partyId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new IdempotencyRow(
                        rs.getString("idempotency_key"),
                        rs.getObject("party_id", UUID.class),
                        rs.getString("request_hash"),
                        rs.getInt("response_status"),
                        Json.parse(rs.getString("response_body")));
            }
        }
    }

    public void insertIdempotency(Connection c, String key, UUID partyId, String requestHash,
                                  int status, JsonNode body) throws SQLException {
        try (var ps = c.prepareStatement(
                "INSERT INTO idempotency_record(idempotency_key, party_id, request_hash, response_status,"
                        + " response_body) VALUES (?,?,?,?,?::jsonb)")) {
            ps.setString(1, key);
            ps.setObject(2, partyId);
            ps.setString(3, requestHash);
            ps.setInt(4, status);
            ps.setString(5, Json.write(body));
            ps.executeUpdate();
        }
    }

    // ---------- 审计 ----------

    public void insertAudit(Connection c, UUID projectId, String action, UUID actorPartyId,
                            JsonNode detail) throws SQLException {
        try (var ps = c.prepareStatement(
                "INSERT INTO audit_log(project_id, action, actor_party_id, detail) VALUES (?,?,?,?::jsonb)")) {
            ps.setObject(1, projectId);
            ps.setString(2, action);
            ps.setObject(3, actorPartyId);
            ps.setString(4, Json.write(detail));
            ps.executeUpdate();
        }
    }

    public List<AuditRow> auditOfProject(Connection c, UUID projectId, long afterSeq, int limit) throws SQLException {
        try (var ps = c.prepareStatement(
                "SELECT seq, project_id, action, actor_party_id, detail, created_at FROM audit_log"
                        + " WHERE project_id = ? AND seq > ? ORDER BY seq LIMIT ?")) {
            ps.setObject(1, projectId);
            ps.setLong(2, afterSeq);
            ps.setInt(3, limit);
            try (var rs = ps.executeQuery()) {
                var out = new ArrayList<AuditRow>();
                while (rs.next()) {
                    out.add(new AuditRow(
                            rs.getLong("seq"),
                            rs.getObject("project_id", UUID.class),
                            rs.getString("action"),
                            rs.getObject("actor_party_id", UUID.class),
                            Json.parse(rs.getString("detail")),
                            rs.getObject("created_at", OffsetDateTime.class)));
                }
                return out;
            }
        }
    }

    public long maxAuditSeq(Connection c) throws SQLException {
        try (var st = c.createStatement();
             var rs = st.executeQuery("SELECT coalesce(max(seq), 0) FROM audit_log")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}

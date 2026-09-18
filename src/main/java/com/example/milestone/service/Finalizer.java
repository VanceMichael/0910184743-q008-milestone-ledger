package com.example.milestone.service;

import com.example.milestone.auth.AuthContext;
import com.example.milestone.db.Db;
import com.example.milestone.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 里程碑终结：当且仅当「所需审批角色全部签收」且「所需证据类型全部提交」时，
 * 在同一事务内更新里程碑状态、追加放款事件、生成唯一放款指令并落投影。
 * 审批与证据谁先齐都可以触发；disbursements.milestone_id 的唯一约束是最终兜底。
 */
public final class Finalizer {
    private Finalizer() {}

    public record Disbursed(UUID disbursementId, long eventSeq) {}

    public static Optional<Disbursed> tryFinalize(Connection c, AuthContext auth, UUID projectId, UUID versionId,
                                                  UUID milestoneId, String milestoneCode) throws SQLException {
        Set<String> requiredApprovers = requiredApprovers(c, milestoneId);
        Set<String> approved = approvedRoles(c, milestoneId);
        if (!approved.containsAll(requiredApprovers)) return Optional.empty();

        Set<String> requiredEvidence = requiredEvidence(c, milestoneId);
        Set<String> submitted = submittedEvidence(c, milestoneId);
        if (!submitted.containsAll(requiredEvidence)) return Optional.empty();

        String amount;
        try (var ps = c.prepareStatement("SELECT amount::text, status FROM milestones WHERE id = ?")) {
            ps.setObject(1, milestoneId);
            var rs = ps.executeQuery();
            rs.next();
            amount = rs.getString(1);
            if ("DISBURSED".equals(rs.getString(2))) return Optional.empty();
        }
        try (var ps = c.prepareStatement("UPDATE milestones SET status = 'DISBURSED' WHERE id = ?")) {
            ps.setObject(1, milestoneId);
            ps.executeUpdate();
        }

        var payload = Json.obj();
        payload.put("milestoneCode", milestoneCode);
        if (amount != null) payload.put("amount", amount);
        payload.put("currency", "CNY");
        Events.Inserted disbEvent = Events.insert(c, auth, projectId, versionId, milestoneId,
                "MILESTONE_DISBURSED", payload);

        String account;
        try (var ps = c.prepareStatement("SELECT beneficiary_account FROM projects WHERE id = ?")) {
            ps.setObject(1, projectId);
            var rs = ps.executeQuery();
            rs.next();
            account = rs.getString(1);
        }
        UUID disbursementId = UUID.randomUUID();
        var instruction = Json.obj();
        instruction.put("type", "BANK_TRANSFER");
        instruction.put("reference", "MS-" + milestoneCode + "-" + disbursementId.toString().substring(0, 8));
        if (amount != null) instruction.put("amount", amount);
        instruction.put("currency", "CNY");
        if (account != null) instruction.put("account", account);
        try (var ps = c.prepareStatement(
                "INSERT INTO disbursements (id, milestone_id, project_id, amount, account, instruction, event_seq)"
                        + " VALUES (?, ?, ?, ?::numeric, ?, ?, ?)")) {
            ps.setObject(1, disbursementId);
            ps.setObject(2, milestoneId);
            ps.setObject(3, projectId);
            ps.setString(4, amount);
            ps.setString(5, account);
            ps.setObject(6, Db.jsonb(instruction.toString()));
            ps.setLong(7, disbEvent.seq());
            ps.executeUpdate();
        }
        TestHooks.checkpoint(TestHooks.Point.AFTER_DISBURSEMENT_INSERT);
        return Optional.of(new Disbursed(disbursementId, disbEvent.seq()));
    }

    static Set<String> requiredApprovers(Connection c, UUID milestoneId) throws SQLException {
        return jsonbStringSet(c, "SELECT required_approvers::text FROM milestones WHERE id = ?", milestoneId);
    }

    static Set<String> requiredEvidence(Connection c, UUID milestoneId) throws SQLException {
        return jsonbStringSet(c, "SELECT required_evidence::text FROM milestones WHERE id = ?", milestoneId);
    }

    static Set<String> approvedRoles(Connection c, UUID milestoneId) throws SQLException {
        var out = new LinkedHashSet<String>();
        try (var ps = c.prepareStatement("SELECT role FROM approvals WHERE milestone_id = ?")) {
            ps.setObject(1, milestoneId);
            var rs = ps.executeQuery();
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    static Set<String> submittedEvidence(Connection c, UUID milestoneId) throws SQLException {
        var out = new LinkedHashSet<String>();
        try (var ps = c.prepareStatement(
                "SELECT DISTINCT payload->>'evidenceType' FROM evidence_events "
                        + "WHERE milestone_id = ? AND event_type = 'EVIDENCE_SUBMITTED'")) {
            ps.setObject(1, milestoneId);
            var rs = ps.executeQuery();
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static Set<String> jsonbStringSet(Connection c, String sql, UUID milestoneId) throws SQLException {
        var out = new LinkedHashSet<String>();
        try (var ps = c.prepareStatement(sql)) {
            ps.setObject(1, milestoneId);
            var rs = ps.executeQuery();
            if (rs.next()) {
                ArrayNode arr = (ArrayNode) Json.parse(rs.getString(1));
                arr.forEach(n -> out.add(n.asText()));
            }
        }
        return out;
    }
}

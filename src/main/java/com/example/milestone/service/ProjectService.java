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
import java.util.UUID;

/** 项目创建与权限授予（仅 ADMIN）。 */
public final class ProjectService {
    private final Db db;

    public ProjectService(Db db) {
        this.db = db;
    }

    public Response createProject(AuthContext auth, JsonNode body) {
        requireAdmin(auth);
        String name = Validate.requireText(body, "name");
        String contractAmount = Validate.optionalDecimal(body, "contractAmount");
        String account = Validate.optionalText(body, "beneficiaryAccount");
        JsonNode milestones = body.get("milestones");
        if (milestones == null || !milestones.isArray() || milestones.isEmpty()) {
            throw ApiException.unprocessable("MISSING_FIELD", "milestones 必须是非空数组");
        }
        return db.tx(conn -> {
            UUID projectId = UUID.randomUUID();
            try (var ps = conn.prepareStatement(
                    "INSERT INTO projects (id, name, contract_amount, beneficiary_account) VALUES (?, ?, ?::numeric, ?)")) {
                ps.setObject(1, projectId);
                ps.setString(2, name);
                ps.setString(3, contractAmount);
                ps.setString(4, account);
                ps.executeUpdate();
            }
            UUID versionId = UUID.randomUUID();
            try (var ps = conn.prepareStatement(
                    "INSERT INTO project_versions (id, project_id, version_no, status) VALUES (?, ?, 1, 'ACTIVE')")) {
                ps.setObject(1, versionId);
                ps.setObject(2, projectId);
                ps.executeUpdate();
            }
            ArrayNode milestoneList = insertMilestones(conn, projectId, versionId, milestones);
            if (body.get("permissions") instanceof ArrayNode perms) {
                for (JsonNode p : perms) {
                    grantPermission(conn, projectId, Validate.requireText(p, "subject"), Validate.requireRole(p, "role"));
                }
            }
            var eventPayload = Json.obj();
            eventPayload.put("versionNo", 1);
            eventPayload.put("reason", "项目创建");
            Events.insert(conn, auth, projectId, versionId, null, "VERSION_ACTIVATED", eventPayload);

            var resp = Json.obj();
            resp.put("projectId", projectId.toString());
            resp.put("versionId", versionId.toString());
            resp.put("versionNo", 1);
            resp.set("milestones", milestoneList);
            return Response.json(201, resp);
        });
    }

    public Response grantPermission(AuthContext auth, UUID projectId, JsonNode body) {
        requireAdmin(auth);
        String subject = Validate.requireText(body, "subject");
        Role role = Validate.requireRole(body, "role");
        return db.tx(conn -> {
            if (!projectExists(conn, projectId)) {
                throw ApiException.notFound("PROJECT_NOT_FOUND", "项目不存在");
            }
            grantPermission(conn, projectId, subject, role);
            var resp = Json.obj();
            resp.put("projectId", projectId.toString());
            resp.put("subject", subject);
            resp.put("role", role.name());
            return Response.json(201, resp);
        });
    }

    static void grantPermission(Connection conn, UUID projectId, String subject, Role role) throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO project_permissions (project_id, subject, role) VALUES (?, ?, ?) "
                        + "ON CONFLICT (project_id, subject) DO UPDATE SET role = EXCLUDED.role")) {
            ps.setObject(1, projectId);
            ps.setString(2, subject);
            ps.setString(3, role.name());
            ps.executeUpdate();
        }
    }

    static boolean projectExists(Connection conn, UUID projectId) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT 1 FROM projects WHERE id = ?")) {
            ps.setObject(1, projectId);
            return ps.executeQuery().next();
        }
    }

    /** 插入里程碑定义，返回 [{id, code}] 列表。 */
    static ArrayNode insertMilestones(Connection conn, UUID projectId, UUID versionId, JsonNode milestones)
            throws SQLException {
        ArrayNode out = Json.MAPPER.createArrayNode();
        for (JsonNode m : milestones) {
            String code = Validate.requireText(m, "code");
            String title = Validate.requireText(m, "title");
            String requiredEvidence = m.get("requiredEvidence") != null ? m.get("requiredEvidence").toString() : "[]";
            String requiredApprovers = m.get("requiredApprovers") != null ? m.get("requiredApprovers").toString() : "[]";
            validateStringArray(m.get("requiredEvidence"), "requiredEvidence");
            validateApproverArray(m.get("requiredApprovers"));
            String amount = Validate.optionalDecimal(m, "amount");
            UUID id = UUID.randomUUID();
            try (var ps = conn.prepareStatement(
                    "INSERT INTO milestones (id, project_id, version_id, code, title, required_evidence,"
                            + " required_approvers, amount) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::numeric)")) {
                ps.setObject(1, id);
                ps.setObject(2, projectId);
                ps.setObject(3, versionId);
                ps.setString(4, code);
                ps.setString(5, title);
                ps.setObject(6, Db.jsonb(requiredEvidence));
                ps.setObject(7, Db.jsonb(requiredApprovers));
                ps.setString(8, amount);
                ps.executeUpdate();
            }
            var item = Json.obj();
            item.put("id", id.toString());
            item.put("code", code);
            out.add(item);
        }
        return out;
    }

    private static void validateStringArray(JsonNode node, String field) {
        if (node == null) return;
        if (!node.isArray()) {
            throw ApiException.unprocessable("BAD_FIELD", field + " 必须是字符串数组");
        }
        for (JsonNode n : node) {
            if (!n.isTextual()) {
                throw ApiException.unprocessable("BAD_FIELD", field + " 必须是字符串数组");
            }
        }
    }

    private static void validateApproverArray(JsonNode node) {
        validateStringArray(node, "requiredApprovers");
        if (node == null) return;
        for (JsonNode n : node) {
            try {
                Role.valueOf(n.asText());
            } catch (IllegalArgumentException e) {
                throw ApiException.unprocessable("BAD_ROLE", "requiredApprovers 含未知角色: " + n.asText());
            }
        }
    }

    static void requireAdmin(AuthContext auth) {
        if (!auth.admin()) {
            throw ApiException.forbidden("该操作需要 ADMIN 主体");
        }
    }
}

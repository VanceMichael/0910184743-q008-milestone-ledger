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

/**
 * 版本生命周期：ACTIVE → EXPIRED → ARCHIVED。
 * 过期版本只能归档，不能再接受证据/审批来推进当前项目。
 */
public final class VersionService {
    private final Db db;

    public VersionService(Db db) {
        this.db = db;
    }

    /** 激活新版本：当前 ACTIVE 版本自动过期，里程碑定义随请求提供。 */
    public Response createVersion(AuthContext auth, UUID projectId, JsonNode body) {
        JsonNode milestones = body.get("milestones");
        if (milestones == null || !milestones.isArray() || milestones.isEmpty()) {
            throw ApiException.unprocessable("MISSING_FIELD", "milestones 必须是非空数组");
        }
        return db.tx(conn -> {
            Permissions.requireWriteRole(conn, projectId, auth, Role.PROJECT_PARTY);
            lockProject(conn, projectId);

            UUID expiredId = null;
            int expiredNo = 0;
            try (var ps = conn.prepareStatement(
                    "SELECT id, version_no FROM project_versions WHERE project_id = ? AND status = 'ACTIVE'")) {
                ps.setObject(1, projectId);
                var rs = ps.executeQuery();
                if (rs.next()) {
                    expiredId = rs.getObject("id", UUID.class);
                    expiredNo = rs.getInt("version_no");
                }
            }
            if (expiredId != null) {
                try (var ps = conn.prepareStatement(
                        "UPDATE project_versions SET status = 'EXPIRED', expired_at = now() WHERE id = ?")) {
                    ps.setObject(1, expiredId);
                    ps.executeUpdate();
                }
                var expiredPayload = Json.obj();
                expiredPayload.put("versionNo", expiredNo);
                expiredPayload.put("reason", "新版本激活");
                Events.insert(conn, auth, projectId, expiredId, null, "VERSION_EXPIRED", expiredPayload);
            }

            int newNo;
            try (var ps = conn.prepareStatement(
                    "SELECT COALESCE(MAX(version_no), 0) + 1 FROM project_versions WHERE project_id = ?")) {
                ps.setObject(1, projectId);
                var rs = ps.executeQuery();
                rs.next();
                newNo = rs.getInt(1);
            }
            UUID versionId = UUID.randomUUID();
            try (var ps = conn.prepareStatement(
                    "INSERT INTO project_versions (id, project_id, version_no, status) VALUES (?, ?, ?, 'ACTIVE')")) {
                ps.setObject(1, versionId);
                ps.setObject(2, projectId);
                ps.setInt(3, newNo);
                ps.executeUpdate();
            }
            ArrayNode milestoneList = ProjectService.insertMilestones(conn, projectId, versionId, milestones);
            var activatedPayload = Json.obj();
            activatedPayload.put("versionNo", newNo);
            activatedPayload.put("reason", "版本激活");
            Events.insert(conn, auth, projectId, versionId, null, "VERSION_ACTIVATED", activatedPayload);

            var resp = Json.obj();
            resp.put("projectId", projectId.toString());
            resp.put("versionId", versionId.toString());
            resp.put("versionNo", newNo);
            if (expiredId != null) resp.put("expiredVersionId", expiredId.toString());
            resp.set("milestones", milestoneList);
            return Response.json(201, resp);
        });
    }

    public Response expireVersion(AuthContext auth, UUID projectId, UUID versionId) {
        return db.tx(conn -> {
            Permissions.requireWriteRole(conn, projectId, auth, Role.PROJECT_PARTY);
            var v = lockVersion(conn, projectId, versionId);
            if (!"ACTIVE".equals(v.status)) {
                throw new ApiException(409, "VERSION_NOT_ACTIVE", "只有 ACTIVE 版本可以过期，当前状态: " + v.status);
            }
            try (var ps = conn.prepareStatement(
                    "UPDATE project_versions SET status = 'EXPIRED', expired_at = now() WHERE id = ?")) {
                ps.setObject(1, versionId);
                ps.executeUpdate();
            }
            var payload = Json.obj();
            payload.put("versionNo", v.versionNo);
            payload.put("reason", "手动过期");
            Events.insert(conn, auth, projectId, versionId, null, "VERSION_EXPIRED", payload);
            return versionResponse(projectId, versionId, v.versionNo, "EXPIRED");
        });
    }

    /** 归档是过期版本唯一允许的前向操作。 */
    public Response archiveVersion(AuthContext auth, UUID projectId, UUID versionId) {
        return db.tx(conn -> {
            Permissions.requireWriteRole(conn, projectId, auth, Role.PROJECT_PARTY, Role.RIGHTS_HOLDER);
            var v = lockVersion(conn, projectId, versionId);
            switch (v.status) {
                case "ACTIVE" -> throw new ApiException(409, "VERSION_NOT_EXPIRED", "只有过期版本可以归档");
                case "ARCHIVED" -> throw new ApiException(409, "ALREADY_ARCHIVED", "版本已归档");
                default -> { /* EXPIRED → 允许归档 */ }
            }
            try (var ps = conn.prepareStatement(
                    "UPDATE project_versions SET status = 'ARCHIVED', archived_at = now() WHERE id = ?")) {
                ps.setObject(1, versionId);
                ps.executeUpdate();
            }
            var payload = Json.obj();
            payload.put("versionNo", v.versionNo);
            payload.put("reason", "版本归档");
            Events.insert(conn, auth, projectId, versionId, null, "VERSION_ARCHIVED", payload);
            return versionResponse(projectId, versionId, v.versionNo, "ARCHIVED");
        });
    }

    private record LockedVersion(String status, int versionNo) {}

    private static LockedVersion lockVersion(Connection conn, UUID projectId, UUID versionId) throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT status, version_no FROM project_versions WHERE id = ? AND project_id = ? FOR UPDATE")) {
            ps.setObject(1, versionId);
            ps.setObject(2, projectId);
            var rs = ps.executeQuery();
            if (!rs.next()) throw ApiException.notFound("VERSION_NOT_FOUND", "版本不存在");
            return new LockedVersion(rs.getString("status"), rs.getInt("version_no"));
        }
    }

    private static void lockProject(Connection conn, UUID projectId) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT 1 FROM projects WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, projectId);
            if (!ps.executeQuery().next()) throw ApiException.notFound("PROJECT_NOT_FOUND", "项目不存在");
        }
    }

    private static Response versionResponse(UUID projectId, UUID versionId, int versionNo, String status) {
        var resp = Json.obj();
        resp.put("projectId", projectId.toString());
        resp.put("versionId", versionId.toString());
        resp.put("versionNo", versionNo);
        resp.put("status", status);
        return Response.json(200, resp);
    }
}

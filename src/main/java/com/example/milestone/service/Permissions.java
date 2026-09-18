package com.example.milestone.service;

import com.example.milestone.auth.AuthContext;
import com.example.milestone.auth.Role;
import com.example.milestone.http.ApiException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** 项目级权限：写操作必须显式授权；读操作额外允许 ADMIN 以审计视角访问。 */
public final class Permissions {
    private Permissions() {}

    public static Optional<Role> roleOf(Connection c, UUID projectId, String subject) throws SQLException {
        try (var ps = c.prepareStatement("SELECT role FROM project_permissions WHERE project_id = ? AND subject = ?")) {
            ps.setObject(1, projectId);
            ps.setString(2, subject);
            var rs = ps.executeQuery();
            return rs.next() ? Optional.of(Role.valueOf(rs.getString(1))) : Optional.empty();
        }
    }

    /** 写路径：必须持有列出的项目角色之一。 */
    public static Role requireWriteRole(Connection c, UUID projectId, AuthContext auth, Role... allowed)
            throws SQLException {
        Optional<Role> role = roleOf(c, projectId, auth.subject());
        for (Role r : allowed) {
            if (role.orElse(null) == r) return r;
        }
        throw ApiException.forbidden("主体 " + auth.subject() + " 在该项目上没有所需角色");
    }

    /** 读路径：项目角色或 ADMIN（按审计视角返回完整字段）。 */
    public static Role requireReadRole(Connection c, UUID projectId, AuthContext auth) throws SQLException {
        Optional<Role> role = roleOf(c, projectId, auth.subject());
        if (role.isPresent()) return role.get();
        if (auth.admin()) return Role.AUDITOR;
        throw ApiException.forbidden("主体 " + auth.subject() + " 在该项目上没有读取权限");
    }
}

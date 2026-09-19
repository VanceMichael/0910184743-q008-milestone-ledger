package com.example.milestone.http;

import com.example.milestone.db.Database;
import com.example.milestone.domain.ApiException;
import com.example.milestone.json.Json;
import com.example.milestone.service.ApprovalService;
import com.example.milestone.service.EndpointResult;
import com.example.milestone.service.EvidenceService;
import com.example.milestone.service.Inputs;
import com.example.milestone.service.ProjectService;
import com.example.milestone.service.QueryService;
import com.fasterxml.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 路由注册与请求装配。 */
public final class ApiHandlers {

    private ApiHandlers() {}

    public static Router build(DataSource ds, ProjectService projects, EvidenceService evidence,
                               ApprovalService approvals, QueryService queries) {
        var router = new Router();

        router.add("GET", "/health", req -> {
            boolean ok = Database.healthy(ds);
            return EndpointResult.fresh(ok ? 200 : 503,
                    Json.obj().put("status", ok ? "ok" : "error").put("db", ok ? "up" : "down"));
        });

        // ---- 管理：参与方 / 证书 / 项目 / 版本 / 里程碑 ----
        router.add("POST", "/api/parties", req -> projects.createParty(body(req)));
        router.add("POST", "/api/parties/{id}/certificates",
                req -> projects.registerCertificate(uuid(req, "id"), body(req)));
        router.add("POST", "/api/certificates/{id}/revoke",
                req -> projects.revokeCertificate(uuid(req, "id"), caller(req)));
        router.add("POST", "/api/projects", req -> projects.createProject(body(req), caller(req)));
        router.add("POST", "/api/projects/{id}/permissions",
                req -> projects.grantPermission(uuid(req, "id"), body(req), caller(req)));
        router.add("POST", "/api/projects/{id}/versions",
                req -> projects.openNewVersion(uuid(req, "id"), caller(req)));
        router.add("POST", "/api/projects/{id}/versions/{vid}/archive",
                req -> projects.archiveVersion(uuid(req, "id"), uuid(req, "vid"), caller(req)));
        router.add("POST", "/api/projects/{id}/milestones",
                req -> projects.createMilestone(uuid(req, "id"), body(req), caller(req)));

        // ---- 账本写入：证据与审批（幂等） ----
        router.add("POST", "/api/evidence-events",
                req -> evidence.submit(requireCaller(req), idempotencyKey(req), body(req)));
        router.add("POST", "/api/milestones/{id}/approvals",
                req -> approvals.approve(requireCaller(req), idempotencyKey(req), uuid(req, "id"), body(req)));

        // ---- 查询 ----
        router.add("GET", "/api/milestones/{id}",
                req -> queries.milestoneView(caller(req), uuid(req, "id")));
        router.add("GET", "/api/milestones/{id}/events",
                req -> queries.milestoneEvents(caller(req), uuid(req, "id")));
        router.add("GET", "/api/milestones/{id}/snapshot", req -> {
            String at = req.queryParam("at");
            if (at == null) throw ApiException.badRequest("缺少查询参数 at");
            return queries.snapshot(caller(req), uuid(req, "id"), Inputs.parseTimestamp(at, "at"));
        });
        router.add("GET", "/api/milestones/{id}/pending-evidence",
                req -> queries.pendingEvidence(caller(req), uuid(req, "id")));
        router.add("GET", "/api/projects/{id}/versions/diff", req -> {
            String from = req.queryParam("from");
            String to = req.queryParam("to");
            if (from == null || to == null) throw ApiException.badRequest("缺少查询参数 from/to");
            return queries.versionDiff(caller(req), uuid(req, "id"),
                    Inputs.uuidPath(from, "from"), Inputs.uuidPath(to, "to"));
        });
        router.add("GET", "/api/projects/{id}/audit", req -> {
            long afterSeq = parseLong(req.queryParam("afterSeq"), 0);
            int limit = (int) parseLong(req.queryParam("limit"), 100);
            return queries.audit(uuid(req, "id"), afterSeq, limit);
        });
        router.add("GET", "/api/evidence-events/{id}/verify",
                req -> queries.verifyEvent(uuid(req, "id")));
        router.add("GET", "/api/payments/{id}",
                req -> queries.paymentView(caller(req), uuid(req, "id")));
        router.add("GET", "/api/payments/{id}/evidence-set",
                req -> queries.paymentEvidenceSet(caller(req), uuid(req, "id")));
        router.add("GET", "/api/meta/audit-cursor", req -> queries.auditCursor());

        return router;
    }

    private static JsonNode body(Router.Request req) {
        if (req.body() == null) throw ApiException.badRequest("缺少 JSON 请求体");
        return req.body();
    }

    private static UUID uuid(Router.Request req, String param) {
        return Inputs.uuidPath(req.pathParam(param), param);
    }

    /** 调用者身份（X-Party-Id 头）；匿名返回 null，查询按无权限裁剪。 */
    private static UUID caller(Router.Request req) {
        String raw = req.header("x-party-id");
        return raw == null ? null : Inputs.uuidPath(raw, "X-Party-Id");
    }

    private static UUID requireCaller(Router.Request req) {
        UUID caller = caller(req);
        if (caller == null) throw ApiException.unauthorized("CALLER_REQUIRED", "缺少 X-Party-Id 请求头");
        return caller;
    }

    private static String idempotencyKey(Router.Request req) {
        String key = req.header("idempotency-key");
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("缺少 Idempotency-Key 请求头");
        }
        return key;
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null) return fallback;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("数值参数非法: " + raw);
        }
    }
}

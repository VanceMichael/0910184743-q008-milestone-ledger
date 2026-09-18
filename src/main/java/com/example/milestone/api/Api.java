package com.example.milestone.api;

import com.example.milestone.auth.AuthService;
import com.example.milestone.config.AppConfig;
import com.example.milestone.db.Db;
import com.example.milestone.http.ApiException;
import com.example.milestone.http.Request;
import com.example.milestone.http.Response;
import com.example.milestone.http.Router;
import com.example.milestone.service.ApprovalService;
import com.example.milestone.service.CertificateService;
import com.example.milestone.service.EvidenceService;
import com.example.milestone.service.ProjectService;
import com.example.milestone.service.QueryService;
import com.example.milestone.service.Validate;
import com.example.milestone.service.VersionService;
import com.example.milestone.util.Json;
import java.time.Instant;
import java.util.UUID;

/** 路由装配：每个端点一行，业务全部在服务层。 */
public final class Api {
    private Api() {}

    public static Router router(Db db, AppConfig cfg) {
        var projects = new ProjectService(db);
        var versions = new VersionService(db);
        var certs = new CertificateService(db);
        var evidence = new EvidenceService(db);
        var approvals = new ApprovalService(db);
        var queries = new QueryService(db);

        Router r = new Router();
        r.add("GET", "/health", req -> health(db));
        r.add("POST", "/api/projects", req -> projects.createProject(req.auth, req.json()));
        r.add("POST", "/api/projects/{pid}/permissions",
                req -> projects.grantPermission(req.auth, uuid(req, "pid"), req.json()));
        r.add("POST", "/api/projects/{pid}/versions",
                req -> versions.createVersion(req.auth, uuid(req, "pid"), req.json()));
        r.add("POST", "/api/projects/{pid}/versions/{vid}/expire",
                req -> versions.expireVersion(req.auth, uuid(req, "pid"), uuid(req, "vid")));
        r.add("POST", "/api/projects/{pid}/versions/{vid}/archive",
                req -> versions.archiveVersion(req.auth, uuid(req, "pid"), uuid(req, "vid")));
        r.add("POST", "/api/certificates", req -> certs.register(req.auth, req.json()));
        r.add("POST", "/api/projects/{pid}/events",
                req -> evidence.submit(req.auth, uuid(req, "pid"), idemKey(req), req.json()));
        r.add("POST", "/api/projects/{pid}/milestones/{mid}/approvals",
                req -> approvals.approve(req.auth, uuid(req, "pid"), uuid(req, "mid"), idemKey(req),
                        req.body.length == 0 ? Json.obj() : req.json()));
        r.add("GET", "/api/projects/{pid}/milestones/{mid}",
                req -> queries.milestoneView(req.auth, uuid(req, "pid"), uuid(req, "mid")));
        r.add("GET", "/api/projects/{pid}/milestones/{mid}/snapshot",
                req -> queries.snapshot(req.auth, uuid(req, "pid"), uuid(req, "mid"), at(req)));
        r.add("GET", "/api/projects/{pid}/milestones/{mid}/pending-evidence",
                req -> queries.pendingEvidence(req.auth, uuid(req, "pid"), uuid(req, "mid")));
        r.add("GET", "/api/projects/{pid}/versions/diff",
                req -> queries.versionDiff(req.auth, uuid(req, "pid"),
                        intQuery(req, "from"), intQuery(req, "to")));
        r.add("GET", "/api/projects/{pid}/timeline",
                req -> queries.timeline(req.auth, uuid(req, "pid"),
                        longQuery(req, "afterSeq", 0), boundedLimit(req)));
        r.add("GET", "/api/projects/{pid}/events/{seq}/verify",
                req -> queries.verifyEvent(req.auth, uuid(req, "pid"), longParam(req, "seq")));
        return r;
    }

    private static Response health(Db db) {
        try {
            db.read(conn -> {
                try (var ps = conn.prepareStatement("SELECT 1")) {
                    ps.execute();
                }
                return null;
            });
            var ok = Json.obj();
            ok.put("status", "ok");
            ok.put("db", "up");
            return Response.json(200, ok);
        } catch (Exception e) {
            var down = Json.obj();
            down.put("status", "down");
            down.put("db", "down");
            return Response.json(503, down);
        }
    }

    private static UUID uuid(Request req, String name) {
        return Validate.pathUuid(req.param(name), name);
    }

    private static String idemKey(Request req) {
        String key = req.header("Idempotency-Key");
        if (key == null || key.isBlank()) {
            throw new ApiException(400, "MISSING_IDEMPOTENCY_KEY", "缺少 Idempotency-Key 请求头");
        }
        return key;
    }

    private static Instant at(Request req) {
        String raw = req.query.get("at");
        if (raw == null) throw ApiException.unprocessable("MISSING_PARAM", "缺少查询参数 at");
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            throw ApiException.unprocessable("BAD_TIMESTAMP", "at 不是 ISO-8601 时间");
        }
    }

    private static int intQuery(Request req, String name) {
        String raw = req.query.get(name);
        if (raw == null) throw ApiException.unprocessable("MISSING_PARAM", "缺少查询参数 " + name);
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw ApiException.unprocessable("BAD_PARAM", "查询参数不是整数: " + name);
        }
    }

    private static long longQuery(Request req, String name, long fallback) {
        String raw = req.query.get(name);
        if (raw == null) return fallback;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw ApiException.unprocessable("BAD_PARAM", "查询参数不是整数: " + name);
        }
    }

    private static long longParam(Request req, String name) {
        try {
            return Long.parseLong(req.param(name));
        } catch (NumberFormatException e) {
            throw ApiException.unprocessable("BAD_PARAM", "路径参数不是整数: " + name);
        }
    }

    private static int boundedLimit(Request req) {
        long limit = longQuery(req, "limit", 100);
        if (limit < 1 || limit > 500) {
            throw ApiException.unprocessable("BAD_PARAM", "limit 必须在 1..500 之间");
        }
        return (int) limit;
    }
}

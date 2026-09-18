package com.example.milestone.it;

import com.example.milestone.crypto.SignatureService;
import com.example.milestone.util.Hashes;
import com.example.milestone.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** 带 Ed25519 请求签名的测试客户端，与服务端验签方案镜像。 */
final class LedgerClient {
    final String subject;
    final KeyPair keys;
    private final HttpClient http = HttpClient.newHttpClient();
    private String baseUrl;

    private LedgerClient(String subject, String baseUrl, KeyPair keys) {
        this.subject = subject;
        this.baseUrl = baseUrl;
        this.keys = keys;
    }

    static LedgerClient fresh(String subject, String baseUrl) {
        return new LedgerClient(subject, baseUrl, SignatureService.generateKeyPair());
    }

    void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    String publicKeyB64() {
        return Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
    }

    record Result(int status, JsonNode body, boolean replay) {
        boolean is2xx() {
            return status >= 200 && status < 300;
        }

        String errorCode() {
            return body != null && body.has("error") ? body.get("error").get("code").asText() : null;
        }
    }

    Result call(String method, String path, JsonNode body, String idemKey) {
        try {
            byte[] bodyBytes = body == null ? new byte[0] : Json.bytes(body);
            String timestamp = Instant.now().toString();
            // 签名只覆盖路径，不含查询串（与服务端 getRawPath 一致）
            String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            String doc = method + "\n" + pathOnly + "\n" + subject + "\n" + timestamp + "\n"
                    + Hashes.sha256Hex(bodyBytes);
            String signature = Base64.getEncoder()
                    .encodeToString(SignatureService.sign(keys.getPrivate(), doc.getBytes(StandardCharsets.UTF_8)));
            var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("X-Ledger-Subject", subject)
                    .header("X-Ledger-Timestamp", timestamp)
                    .header("X-Ledger-Signature", signature)
                    .header("Content-Type", "application/json");
            if (idemKey != null) builder.header("Idempotency-Key", idemKey);
            builder.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
            var resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            JsonNode parsed = resp.body().length == 0 ? null : Json.parse(resp.body());
            return new Result(resp.statusCode(), parsed,
                    "true".equals(resp.headers().firstValue("X-Idempotent-Replay").orElse(null)));
        } catch (Exception e) {
            throw new IllegalStateException("请求失败: " + method + " " + path, e);
        }
    }

    Result get(String path) {
        return call("GET", path, null, null);
    }

    Result post(String path, JsonNode body) {
        return call("POST", path, body, null);
    }

    Result post(String path, JsonNode body, String idemKey) {
        return call("POST", path, body, idemKey);
    }

    // ---- 业务便捷方法 ----

    Result bootstrapCert() {
        var body = Json.obj();
        body.put("subject", subject);
        body.put("publicKey", publicKeyB64());
        return post("/api/certificates", body);
    }

    Result registerCert(String subject, String publicKeyB64) {
        var body = Json.obj();
        body.put("subject", subject);
        body.put("publicKey", publicKeyB64); // 注意：必须用参数，而非 this.publicKeyB64()
        return post("/api/certificates", body);
    }

    Result createProject(String name, ObjectNode milestoneDef, ObjectNode... permissions) {
        var body = Json.obj();
        body.put("name", name);
        body.put("contractAmount", "1200000.00");
        body.put("beneficiaryAccount", "CN-ACC-9001");
        var milestones = body.putArray("milestones");
        milestones.add(milestoneDef);
        var perms = body.putArray("permissions");
        for (ObjectNode p : permissions) perms.add(p);
        return post("/api/projects", body);
    }

    static ObjectNode milestoneDef(String code, String[] requiredEvidence, String[] requiredApprovers, String amount) {
        var m = Json.obj();
        m.put("code", code);
        m.put("title", "里程碑 " + code);
        var ev = m.putArray("requiredEvidence");
        for (String e : requiredEvidence) ev.add(e);
        var ap = m.putArray("requiredApprovers");
        for (String a : requiredApprovers) ap.add(a);
        if (amount != null) m.put("amount", amount);
        return m;
    }

    static ObjectNode permission(String subject, String role) {
        var p = Json.obj();
        p.put("subject", subject);
        p.put("role", role);
        return p;
    }

    Result submitEvidence(String projectId, String versionId, String milestoneId,
                          String evidenceType, String idemKey) {
        var body = Json.obj();
        body.put("versionId", versionId);
        if (milestoneId != null) body.put("milestoneId", milestoneId);
        body.put("eventType", "EVIDENCE_SUBMITTED");
        var payload = body.putObject("payload");
        payload.put("evidenceType", evidenceType);
        payload.put("summary", "证据 " + evidenceType + " @" + UUID.randomUUID());
        payload.put("uri", "oss://evidence/" + UUID.randomUUID());
        var commercial = payload.putObject("commercial");
        commercial.put("invoiceRef", "INV-" + UUID.randomUUID().toString().substring(0, 8));
        return post("/api/projects/" + projectId + "/events", body, idemKey);
    }

    Result approve(String projectId, String milestoneId, String idemKey) {
        var body = Json.obj();
        body.put("comment", "同意");
        return post("/api/projects/" + projectId + "/milestones/" + milestoneId + "/approvals", body, idemKey);
    }

    Result createVersion(String projectId, ObjectNode milestoneDef) {
        var body = Json.obj();
        body.putArray("milestones").add(milestoneDef);
        return post("/api/projects/" + projectId + "/versions", body);
    }

    Result expireVersion(String projectId, String versionId) {
        return post("/api/projects/" + projectId + "/versions/" + versionId + "/expire", Json.obj());
    }

    Result archiveVersion(String projectId, String versionId) {
        return post("/api/projects/" + projectId + "/versions/" + versionId + "/archive", Json.obj());
    }

    Result milestone(String projectId, String milestoneId) {
        return get("/api/projects/" + projectId + "/milestones/" + milestoneId);
    }

    Result snapshot(String projectId, String milestoneId, Instant at) {
        return get("/api/projects/" + projectId + "/milestones/" + milestoneId + "/snapshot?at=" + at);
    }

    Result pendingEvidence(String projectId, String milestoneId) {
        return get("/api/projects/" + projectId + "/milestones/" + milestoneId + "/pending-evidence");
    }

    Result timeline(String projectId, long afterSeq) {
        return get("/api/projects/" + projectId + "/timeline?afterSeq=" + afterSeq + "&limit=500");
    }

    Result diff(String projectId, int from, int to) {
        return get("/api/projects/" + projectId + "/versions/diff?from=" + from + "&to=" + to);
    }

    Result verify(String projectId, long seq) {
        return get("/api/projects/" + projectId + "/events/" + seq + "/verify");
    }
}

package com.example.milestone.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 幂等键：同键重试返回原决定（含拒绝），换键才是新请求。 */
class IdempotencyIT extends ITBase {

    @Test
    @DisplayName("同一 idempotency_key 重试返回原决定")
    void sameKeyReplaysOriginalDecision() {
        var f = newStandardProject();
        String key = "ev-" + System.nanoTime();

        var first = alice.submitEvidence(f.projectId(), f.versionId(), f.milestoneId(), "IP_ASSIGNMENT", key);
        assertEquals(201, first.status());
        long seq = first.body().get("seq").asLong();

        var retry = alice.submitEvidence(f.projectId(), f.versionId(), f.milestoneId(), "IP_ASSIGNMENT", key);
        assertTrue(retry.replay(), "同键重试应标记回放");
        assertEquals(201, retry.status());
        assertEquals(first.body().toString(), retry.body().toString(), "重试必须返回原决定原文");
        assertEquals(seq, retry.body().get("seq").asLong(), "回放不得产生新事件序号");

        // 换键同负载：新事件
        var fresh = alice.submitEvidence(f.projectId(), f.versionId(), f.milestoneId(),
                "IP_ASSIGNMENT", "ev-" + System.nanoTime());
        assertEquals(201, fresh.status());
        assertNotEquals(seq, fresh.body().get("seq").asLong());

        assertEquals(2, sqlLong(
                "SELECT COUNT(*) FROM evidence_events WHERE project_id = ?::uuid AND event_type = 'EVIDENCE_SUBMITTED'",
                f.projectId()));

        // 缺幂等键：400
        var missing = alice.post("/api/projects/" + f.projectId() + "/events",
                com.example.milestone.util.Json.obj());
        assertEquals(400, missing.status());
        assertEquals("MISSING_IDEMPOTENCY_KEY", missing.errorCode());
    }

    @Test
    @DisplayName("伪造与越权请求被鉴权拦截")
    void forgedAndUnauthorizedRequestsRejected() {
        var f = newStandardProject();

        // 无签名头
        try {
            var raw = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("http://localhost:" + app.port()
                                    + "/api/projects/" + f.projectId() + "/timeline"))
                            .GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(401, raw.statusCode());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        // 有效证书但无项目角色：403
        var outsider = LedgerClient.fresh("eve", "http://localhost:" + app.port());
        assertEquals(201, admin.registerCert("eve", outsider.publicKeyB64()).status());
        var denied = outsider.submitEvidence(f.projectId(), f.versionId(), f.milestoneId(),
                "IP_ASSIGNMENT", "ev-eve-" + System.nanoTime());
        assertEquals(403, denied.status());

        // 未注册证书的主体：401
        var unknown = LedgerClient.fresh("ghost", "http://localhost:" + app.port());
        var rejected = unknown.submitEvidence(f.projectId(), f.versionId(), f.milestoneId(),
                "IP_ASSIGNMENT", "ev-ghost-" + System.nanoTime());
        assertEquals(401, rejected.status());

        // 项目方不能冒充审批角色
        var wrongRole = alice.approve(f.projectId(), f.milestoneId(), "ap-alice-" + System.nanoTime());
        assertEquals(403, wrongRole.status());
    }
}

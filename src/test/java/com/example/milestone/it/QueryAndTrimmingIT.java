package com.example.milestone.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 时点快照、待补证据、按角色裁剪商业字段。 */
class QueryAndTrimmingIT extends ITBase {

    @Test
    @DisplayName("快照随时间推进，待补证据收敛，验证机构看不到商业字段")
    void snapshotsPendingEvidenceAndTrimming() throws Exception {
        var f = newStandardProject();

        Instant t0 = Instant.now();
        Thread.sleep(50);

        // 待补证据：初始两类都缺
        var pending0 = alice.pendingEvidence(f.projectId(), f.milestoneId());
        assertEquals(200, pending0.status());
        assertEquals(2, pending0.body().get("pending").size());

        alice.submitEvidence(f.projectId(), f.versionId(), f.milestoneId(),
                "IP_ASSIGNMENT", "ev-ip-" + System.nanoTime());
        Thread.sleep(50);
        Instant t1 = Instant.now();
        Thread.sleep(50);

        var pending1 = alice.pendingEvidence(f.projectId(), f.milestoneId());
        assertEquals(1, pending1.body().get("pending").size());
        assertEquals("PROTOTYPE_DEMO", pending1.body().get("pending").get(0).asText());

        carol.submitEvidence(f.projectId(), f.versionId(), f.milestoneId(),
                "PROTOTYPE_DEMO", "ev-demo-" + System.nanoTime());
        var pending2 = alice.pendingEvidence(f.projectId(), f.milestoneId());
        assertEquals(0, pending2.body().get("pending").size());

        var first = bob.approve(f.projectId(), f.milestoneId(), "ap-bob-" + System.nanoTime());
        assertEquals(202, first.status());
        Thread.sleep(50);
        Instant t2 = Instant.now();
        Thread.sleep(50);

        var done = carol.approve(f.projectId(), f.milestoneId(), "ap-carol-" + System.nanoTime());
        assertEquals(201, done.status());
        Thread.sleep(50);
        Instant t3 = Instant.now();

        // 时点快照：三个时点三种状态
        var snap0 = dave.snapshot(f.projectId(), f.milestoneId(), t0);
        assertEquals(404, snap0.status(), "首个事件前的时点应无投影");

        var snap1 = dave.snapshot(f.projectId(), f.milestoneId(), t1);
        assertEquals(200, snap1.status());
        assertEquals("IN_REVIEW", snap1.body().get("status").asText());
        assertEquals(1, snap1.body().get("evidenceSet").size());
        assertEquals(0, snap1.body().get("approvals").size());
        assertFalse(snap1.body().get("commercial").has("disbursementId"));

        var snap2 = dave.snapshot(f.projectId(), f.milestoneId(), t2);
        assertEquals("IN_REVIEW", snap2.body().get("status").asText());
        assertEquals(2, snap2.body().get("evidenceSet").size());
        assertEquals(1, snap2.body().get("approvals").size());

        var snap3 = dave.snapshot(f.projectId(), f.milestoneId(), t3);
        assertEquals("DISBURSED", snap3.body().get("status").asText());
        assertTrue(snap3.body().get("commercial").has("disbursementId"));

        // 商业字段裁剪：验证机构看不到金额与账户
        var verifierView = carol.milestone(f.projectId(), f.milestoneId());
        assertEquals(200, verifierView.status());
        assertFalse(verifierView.body().has("commercial"), "VERIFIER 视图不得包含商业字段");
        assertEquals("DISBURSED", verifierView.body().get("status").asText());

        var holderView = bob.milestone(f.projectId(), f.milestoneId());
        assertTrue(holderView.body().has("commercial"), "RIGHTS_HOLDER 应看到商业字段");
        assertEquals("300000.00", holderView.body().at("/commercial/amount").asText());
        assertTrue(holderView.body().at("/commercial/disbursement").has("instruction"));

        var auditorView = dave.milestone(f.projectId(), f.milestoneId());
        assertTrue(auditorView.body().has("commercial"));

        // 时间线裁剪：VERIFIER 看不到 payload.commercial，AUDITOR 可以
        var verifierTimeline = carol.timeline(f.projectId(), 0);
        for (var e : verifierTimeline.body().get("events")) {
            assertFalse(e.get("payload").has("commercial"), "VERIFIER 时间线不得含商业子字段");
        }
        var auditorTimeline = dave.timeline(f.projectId(), 0);
        boolean sawCommercial = false;
        for (var e : auditorTimeline.body().get("events")) {
            if (e.get("payload").has("commercial")) sawCommercial = true;
        }
        assertTrue(sawCommercial, "AUDITOR 时间线应保留商业子字段");

        // 未授权主体：403
        var outsider = LedgerClient.fresh("mallory", "http://localhost:" + app.port());
        assertEquals(201, admin.registerCert("mallory", outsider.publicKeyB64()).status());
        assertEquals(403, outsider.milestone(f.projectId(), f.milestoneId()).status());
        assertEquals(403, outsider.timeline(f.projectId(), 0).status());
        assertEquals(403, outsider.snapshot(f.projectId(), f.milestoneId(), t3).status());
    }
}

package com.example.milestone.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 重启恢复：数据库与应用重启后，审计游标继续递增；
 * 旧签名证书对应的历史记录仍可验签，旧证书不能再用。
 */
class RecoveryIT extends ITBase {

    @Test
    @DisplayName("重启后审计游标递增，旧证书历史可验、不可再用")
    void auditCursorAndOldCertsSurviveRestart() {
        assumeTrue(pg.canRestart(), "外部数据库模式跳过重启测试");
        var f = newStandardProject();

        // bob 用第一代证书提交证据
        var e1 = bob.submitEvidence(f.projectId(), f.versionId(), f.milestoneId(),
                "IP_ASSIGNMENT", "ev-before-rotate-" + System.nanoTime());
        assertEquals(201, e1.status());
        long seqBeforeRotate = e1.body().get("seq").asLong();

        // 证书轮换：bob 换新密钥，旧证书置 valid_to 但保留
        var bobV2 = LedgerClient.fresh("bob", "http://localhost:" + app.port());
        var rotated = admin.registerCert("bob", bobV2.publicKeyB64());
        assertEquals(201, rotated.status());
        assertTrue(rotated.body().has("rotatedFrom"), "轮换应返回被替换的证书");

        // 旧密钥不能再签新请求
        var rejected = bob.submitEvidence(f.projectId(), f.versionId(), f.milestoneId(),
                "PROTOTYPE_DEMO", "ev-old-key-" + System.nanoTime());
        assertEquals(401, rejected.status(), "轮换后旧证书不得再签发新事件");
        // 新密钥可以
        var e2 = bobV2.submitEvidence(f.projectId(), f.versionId(), f.milestoneId(),
                "PROTOTYPE_DEMO", "ev-new-key-" + System.nanoTime());
        assertEquals(201, e2.status());

        long maxSeqBeforeRestart = maxSeq(f.projectId());
        assertTrue(maxSeqBeforeRestart >= seqBeforeRotate);

        // 重启数据库（同一数据目录）与应用
        pg.restart();
        startApp();

        // 审计游标继续递增（BIGSERIAL 序列随库持久化）
        var e3 = alice.submitEvidence(f.projectId(), f.versionId(), f.milestoneId(),
                "ACCEPTANCE_REPORT", "ev-after-restart-" + System.nanoTime());
        assertEquals(201, e3.status(), "重启后应可继续写入: " + e3.body());
        long seqAfterRestart = e3.body().get("seq").asLong();
        assertTrue(seqAfterRestart > maxSeqBeforeRestart,
                "审计游标应继续递增: " + seqAfterRestart + " > " + maxSeqBeforeRestart);

        // 旧证书签的历史事件仍可验签（事件钉住 cert_id）
        var verifyOld = dave.verify(f.projectId(), seqBeforeRotate);
        assertEquals(200, verifyOld.status());
        assertTrue(verifyOld.body().get("signatureValid").asBoolean(), "旧证书历史记录必须仍可验签");
        assertTrue(verifyOld.body().get("payloadHashMatch").asBoolean(), "历史负载哈希必须一致");
        assertTrue(verifyOld.body().get("cert").get("rotatedOut").asBoolean(),
                "验签响应应标明证书已轮换");

        // 新证书签的事件同样可验
        var verifyNew = dave.verify(f.projectId(), e2.body().get("seq").asLong());
        assertTrue(verifyNew.body().get("signatureValid").asBoolean());

        // 时间线从头重放完整（游标 0 起读）
        var timeline = dave.timeline(f.projectId(), 0);
        assertEquals(200, timeline.status());
        long total = sqlLong("SELECT COUNT(*) FROM evidence_events WHERE project_id = ?::uuid", f.projectId());
        assertEquals(total, timeline.body().get("events").size(), "时间线应包含全部历史事件");
        assertEquals(sqlLong("SELECT MAX(seq) FROM evidence_events WHERE project_id = ?::uuid", f.projectId()),
                timeline.body().get("nextAfterSeq").asLong(), "续读游标应指向最新事件");
    }
}

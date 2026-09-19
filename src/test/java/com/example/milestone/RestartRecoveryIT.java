package com.example.milestone;

import com.example.milestone.json.Json;
import com.example.milestone.support.Fixture;
import com.example.milestone.support.TestEnv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重启恢复：应用在同一数据库上重启后，
 * 审计游标继续递增、旧签名证书对应的历史记录仍可验证、幂等记录仍然有效。
 */
class RestartRecoveryIT {

    private TestEnv env;

    @AfterEach
    void down() {
        if (env != null) env.close();
    }

    @Test
    void auditCursorContinuesAndOldCertificatesStillVerifyAfterRestart() throws Exception {
        env = TestEnv.start();
        var f = Fixture.create(env);

        // 证书 A 签署事件 1
        String occurredAt1 = OffsetDateTime.now(ZoneOffset.UTC).toString();
        var body1 = f.evidenceBody(f.rightsId, f.rightsKeys, f.rightsCertId,
                "OWNERSHIP_CONFIRMATION", Json.obj().put("patentNo", "ZL-OLD"), occurredAt1);
        var event1 = env.post("/api/evidence-events", body1, f.rightsId, "restart-ev-1");
        assertEquals(201, event1.status(), () -> String.valueOf(event1.json()));
        String event1Id = event1.json().get("id").asText();

        // 证书轮换：B 上岗，A 吊销；B 签署事件 2
        UUID certB = f.registerCert(env, f.rightsId, f.rightsKeys, "cn=rights-holder-v2", null, null);
        var revoke = env.post("/api/certificates/" + f.rightsCertId + "/revoke", Json.obj(), null, null);
        assertEquals(200, revoke.status(), () -> String.valueOf(revoke.json()));
        var event2 = env.post("/api/evidence-events",
                f.evidenceBody(f.rightsId, f.rightsKeys, certB,
                        "OWNERSHIP_CONFIRMATION", Json.obj().put("patentNo", "ZL-NEW"),
                        OffsetDateTime.now(ZoneOffset.UTC).toString()),
                f.rightsId, "restart-ev-2");
        assertEquals(201, event2.status(), () -> String.valueOf(event2.json()));

        long cursorBefore = env.get("/api/meta/audit-cursor", null)
                .json().get("maxSeq").asLong();
        assertTrue(cursorBefore > 0);

        // —— 应用重启（数据库保持运行，模拟 compose restart app）——
        env.stopApp();
        env.startApp();

        long cursorAfterRestart = env.get("/api/meta/audit-cursor", null)
                .json().get("maxSeq").asLong();
        assertEquals(cursorBefore, cursorAfterRestart, "重启不应丢失审计记录");

        // 审计游标继续递增（不回退、不重复）
        var event3 = env.post("/api/evidence-events",
                f.evidenceBody(f.rightsId, f.rightsKeys, certB,
                        "OWNERSHIP_CONFIRMATION", Json.obj().put("patentNo", "ZL-AFTER-RESTART"),
                        OffsetDateTime.now(ZoneOffset.UTC).toString()),
                f.rightsId, "restart-ev-3");
        assertEquals(201, event3.status(), () -> String.valueOf(event3.json()));
        long cursorAfterWrite = env.get("/api/meta/audit-cursor", null)
                .json().get("maxSeq").asLong();
        assertTrue(cursorAfterWrite > cursorAfterRestart,
                "重启后审计游标应继续递增: " + cursorAfterRestart + " -> " + cursorAfterWrite);

        // 旧证书（已吊销）签署的历史事件仍可验证
        var verifyOld = env.get("/api/evidence-events/" + event1Id + "/verify", null);
        assertEquals(200, verifyOld.status());
        assertTrue(verifyOld.json().get("valid").asBoolean(),
                () -> "旧证书历史记录应可验证: " + verifyOld.json());
        assertTrue(verifyOld.json().get("certValidAtEventTime").asBoolean());
        assertEquals("REVOKED", verifyOld.json().get("certCurrentStatus").asText());

        // 重启前的幂等决定仍然有效：重放返回原响应
        var replay = env.post("/api/evidence-events", body1, f.rightsId, "restart-ev-1");
        assertEquals(201, replay.status());
        assertTrue(replay.replay());
        assertEquals(event1.json(), replay.json());
        assertEquals(3, env.count(
                "SELECT count(*) FROM evidence_event WHERE milestone_id = '" + f.milestoneId + "'"));
    }
}

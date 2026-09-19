package com.example.milestone;

import com.example.milestone.json.Json;
import com.example.milestone.support.Fixture;
import com.example.milestone.support.TestEnv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 证据提交：签名/权限/版本校验、幂等重放、商业字段裁剪。 */
class EvidenceFlowIT {

    private static TestEnv env;

    @BeforeAll
    static void up() throws Exception {
        env = TestEnv.start();
    }

    @AfterAll
    static void down() {
        env.close();
    }

    @Test
    void submitsAndReplaysIdempotently() throws Exception {
        var f = Fixture.create(env);
        String occurredAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        var body = f.evidenceBody(f.rightsId, f.rightsKeys, f.rightsCertId,
                "OWNERSHIP_CONFIRMATION", Json.obj().put("patentNo", "ZL-1"), occurredAt);

        var first = env.post("/api/evidence-events", body, f.rightsId, "key-happy-1");
        assertEquals(201, first.status(), () -> String.valueOf(first.json()));
        assertFalse(first.replay());
        assertNotNull(first.json().get("id"));
        assertTrue(first.json().get("seq").asLong() > 0);

        // 同一 idempotency_key + 同一请求体 -> 返回原决定，不产生新事件
        var replay = env.post("/api/evidence-events", body, f.rightsId, "key-happy-1");
        assertEquals(201, replay.status());
        assertTrue(replay.replay());
        assertEquals(first.json(), replay.json());
        assertEquals(1, env.count("SELECT count(*) FROM evidence_event WHERE milestone_id = '"
                + f.milestoneId + "'"));
    }

    @Test
    void rejectsSameKeyWithDifferentBody() throws Exception {
        var f = Fixture.create(env);
        String occurredAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        var body1 = f.evidenceBody(f.rightsId, f.rightsKeys, f.rightsCertId,
                "OWNERSHIP_CONFIRMATION", Json.obj().put("patentNo", "ZL-A"), occurredAt);
        var body2 = f.evidenceBody(f.rightsId, f.rightsKeys, f.rightsCertId,
                "OWNERSHIP_CONFIRMATION", Json.obj().put("patentNo", "ZL-B"), occurredAt);

        assertEquals(201, env.post("/api/evidence-events", body1, f.rightsId, "key-conflict").status());
        var conflict = env.post("/api/evidence-events", body2, f.rightsId, "key-conflict");
        assertEquals(409, conflict.status());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.errorCode());
    }

    @Test
    void rejectsBadSignature() throws Exception {
        var f = Fixture.create(env);
        String occurredAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        var body = f.evidenceBody(f.rightsId, f.rightsKeys, f.rightsCertId,
                "OWNERSHIP_CONFIRMATION", Json.obj().put("patentNo", "ZL-1"), occurredAt);
        // 用另一把私钥签名 -> 验签失败
        var forged = f.evidenceBody(f.rightsId, Fixture.generateKeys(), f.rightsCertId,
                "OWNERSHIP_CONFIRMATION", Json.obj().put("patentNo", "ZL-1"), occurredAt);
        var resp = env.post("/api/evidence-events", forged, f.rightsId, "key-badsig");
        assertEquals(401, resp.status());
        assertEquals("SIGNATURE_INVALID", resp.errorCode());
        assertEquals(0, env.count("SELECT count(*) FROM evidence_event WHERE milestone_id = '"
                + f.milestoneId + "'"));
    }

    @Test
    void rejectsCertificateOfAnotherParty() throws Exception {
        var f = Fixture.create(env);
        String occurredAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        // 权利方提交，但使用项目方的证书（主体不符）
        var body = f.evidenceBody(f.rightsId, f.ownerKeys, f.ownerCertId,
                "OWNERSHIP_CONFIRMATION", Json.obj().put("patentNo", "ZL-1"), occurredAt);
        var resp = env.post("/api/evidence-events", body, f.rightsId, "key-wrongcert");
        assertEquals(401, resp.status());
        assertEquals("CERT_SUBJECT_MISMATCH", resp.errorCode());
    }

    @Test
    void rejectsPartyWithoutPermission() throws Exception {
        var f = Fixture.create(env);
        // 注册一个没有任何项目权限的参与方
        var outsider = env.post("/api/parties",
                Json.obj().put("name", "局外人").put("role", "VERIFIER"), null, null);
        UUID outsiderId = UUID.fromString(outsider.json().get("id").asText());
        var outsiderKeys = Fixture.generateKeys();
        UUID outsiderCert = f.registerCert(env, outsiderId, outsiderKeys, "cn=outsider", null, null);

        String occurredAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        var body = f.evidenceBody(outsiderId, outsiderKeys, outsiderCert,
                "ACCEPTANCE_SIGNOFF", Json.obj().put("report", "X"), occurredAt);
        var resp = env.post("/api/evidence-events", body, outsiderId, "key-noperm");
        assertEquals(403, resp.status());
        assertEquals("FORBIDDEN", resp.errorCode());
    }

    @Test
    void rejectsCertificateNotValidAtEventTime() throws Exception {
        var f = Fixture.create(env);
        // 证书尚未生效时发生的事件
        String future = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2).toString();
        var body = f.evidenceBody(f.rightsId, f.rightsKeys, f.rightsCertId,
                "OWNERSHIP_CONFIRMATION", Json.obj().put("patentNo", "ZL-1"), future);
        var resp = env.post("/api/evidence-events", body, f.rightsId, "key-notyet");
        assertEquals(401, resp.status());
        assertEquals("CERT_NOT_VALID_AT_EVENT_TIME", resp.errorCode());
    }

    @Test
    void rejectsVersionMismatch() throws Exception {
        var f = Fixture.create(env);
        String occurredAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        var body = f.evidenceBody(f.rightsId, f.rightsKeys, f.rightsCertId,
                "OWNERSHIP_CONFIRMATION", Json.obj().put("patentNo", "ZL-1"), occurredAt);
        // 篡改版本号（签名随之失效，但应先命中版本不一致）
        ((com.fasterxml.jackson.databind.node.ObjectNode) body)
                .put("projectVersionId", UUID.randomUUID().toString());
        var resp = env.post("/api/evidence-events", body, f.rightsId, "key-vermis");
        assertEquals(409, resp.status());
        assertEquals("VERSION_MISMATCH", resp.errorCode());
    }

    @Test
    void trimsCommercialFieldsByCaller() throws Exception {
        var f = Fixture.create(env);
        f.submitAllEvidence(env);

        // 有 VIEW_COMMERCIAL 的项目方：看到金额与 commercial 子树
        var asOwner = env.get("/api/milestones/" + f.milestoneId + "/events", f.ownerId);
        assertEquals(200, asOwner.status());
        boolean sawCommercial = false;
        for (var e : asOwner.json().get("events")) {
            if (e.get("payload").has("commercial")) sawCommercial = true;
        }
        assertTrue(sawCommercial, "商业权限调用者应看到 commercial 子树");

        // 匿名调用者：commercial 子树被裁剪
        var asAnon = env.get("/api/milestones/" + f.milestoneId + "/events", null);
        assertEquals(200, asAnon.status());
        for (var e : asAnon.json().get("events")) {
            assertFalse(e.get("payload").has("commercial"), "匿名调用者不应看到 commercial 子树");
        }

        // 里程碑金额同样按调用者裁剪
        var msOwner = env.get("/api/milestones/" + f.milestoneId, f.ownerId);
        assertTrue(msOwner.json().get("amount").isNumber());
        assertEquals(0, msOwner.json().get("amount").decimalValue()
                .compareTo(new java.math.BigDecimal("1200000.00")), "金额应原样返回");
        var msAnon = env.get("/api/milestones/" + f.milestoneId, null);
        assertTrue(msAnon.json().get("amount").isNull());
    }
}

package com.example.milestone;

import com.example.milestone.support.Fixture;
import com.example.milestone.support.TestEnv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/** 指定时点快照、待补证据与审计游标分页。 */
class SnapshotQueryIT {

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
    void snapshotReconstructsStateAtGivenTime() throws Exception {
        var f = Fixture.create(env);

        // t0: 无任何事实 -> PENDING
        var before = env.get("/api/milestones/" + f.milestoneId + "/snapshot?at="
                + java.net.URLEncoder.encode(OffsetDateTime.now().toString(),
                java.nio.charset.StandardCharsets.UTF_8), null);
        assertEquals(200, before.status());
        assertEquals("PENDING", before.json().get("status").asText());

        // 待补证据：初始三项全缺
        var pending0 = env.get("/api/milestones/" + f.milestoneId + "/pending-evidence", null);
        assertEquals(3, pending0.json().get("missing").size());
        assertEquals(0, pending0.json().get("satisfied").size());

        // 提交全部证据，记录首条证据的 recordedAt
        var ev1 = f.submitEvidence(env, f.rightsId, f.rightsKeys, f.rightsCertId,
                "OWNERSHIP_CONFIRMATION", com.example.milestone.json.Json.obj().put("patentNo", "ZL-S"), "snap-ev-1");
        String r1 = ev1.json().get("recordedAt").asText();
        f.submitEvidence(env, f.ownerId, f.ownerKeys, f.ownerCertId,
                "PROTOTYPE_EVIDENCE", com.example.milestone.json.Json.obj().put("prototypeSerial", "P-S"), "snap-ev-2");
        f.submitEvidence(env, f.verifierId, f.verifierKeys, f.verifierCertId,
                "ACCEPTANCE_SIGNOFF", com.example.milestone.json.Json.obj().put("report", "A-S"), "snap-ev-3");

        var pending1 = env.get("/api/milestones/" + f.milestoneId + "/pending-evidence", null);
        assertEquals(0, pending1.json().get("missing").size());
        assertEquals(3, pending1.json().get("satisfied").size());

        // t1: 首条证据落库时刻 -> IN_REVIEW，无审批
        var atEvidence = env.get("/api/milestones/" + f.milestoneId + "/snapshot?at="
                + encode(r1), null);
        assertEquals("IN_REVIEW", atEvidence.json().get("status").asText());
        assertEquals(1, atEvidence.json().get("evidenceEventIds").size());
        assertEquals(0, atEvidence.json().get("approvals").size());

        // 两个审批方依次确认 -> 放款
        assertEquals(201, f.approve(env, f.rightsId, f.rightsKeys, f.rightsCertId, "snap-r").status());
        var paid = f.approve(env, f.verifierId, f.verifierKeys, f.verifierCertId, "snap-v");
        assertEquals(201, paid.status());
        String paymentId = paid.json().get("paymentId").asText();
        var payment = env.get("/api/payments/" + paymentId, f.ownerId);
        String paidAt = payment.json().get("createdAt").asText();

        // t2: 放款前一微秒 -> IN_REVIEW（第一审批已在，放款未至）
        var justBeforePaid = env.get("/api/milestones/" + f.milestoneId + "/snapshot?at="
                + encode(OffsetDateTime.parse(paidAt).minusNanos(1000).toString()), null);
        assertEquals("IN_REVIEW", justBeforePaid.json().get("status").asText());
        assertEquals(1, justBeforePaid.json().get("approvals").size());
        assertTrue(justBeforePaid.json().get("paymentId").isNull());

        // t3: 放款时刻 -> PAID，审批两方齐全
        var atPaid = env.get("/api/milestones/" + f.milestoneId + "/snapshot?at=" + encode(paidAt), null);
        assertEquals("PAID", atPaid.json().get("status").asText());
        assertEquals(2, atPaid.json().get("approvals").size());
        assertEquals(paymentId, atPaid.json().get("paymentId").asText());
        assertEquals(3, atPaid.json().get("evidenceEventIds").size());
    }

    @Test
    void auditLogPagesByMonotonicCursor() throws Exception {
        var f = Fixture.create(env);
        f.submitAllEvidence(env);
        f.approve(env, f.rightsId, f.rightsKeys, f.rightsCertId, "audit-r");
        f.approve(env, f.verifierId, f.verifierKeys, f.verifierCertId, "audit-v");

        var seen = new HashSet<Long>();
        long afterSeq = 0;
        int pages = 0;
        while (true) {
            var page = env.get("/api/projects/" + f.projectId + "/audit?afterSeq=" + afterSeq + "&limit=2", null);
            assertEquals(200, page.status());
            var entries = page.json().get("entries");
            if (entries.isEmpty()) break;
            for (var e : entries) {
                long seq = e.get("seq").asLong();
                assertTrue(seq > afterSeq, "审计游标必须单调递增");
                assertTrue(seen.add(seq), "审计记录不应重复出现: " + seq);
            }
            afterSeq = page.json().get("nextAfterSeq").asLong();
            pages++;
            assertTrue(pages < 50, "分页不应死循环");
        }
        // 项目创建 + 9 次授权 + 里程碑 + 3 证据 + 2 审批 + 1 放款 = 17 条
        assertEquals(17, seen.size(), () -> "实际审计条目: " + seen);
        assertTrue(pages >= 8, "limit=2 应至少翻 8 页, 实际: " + pages);
    }

    private static String encode(String iso) {
        return java.net.URLEncoder.encode(iso, java.nio.charset.StandardCharsets.UTF_8);
    }
}

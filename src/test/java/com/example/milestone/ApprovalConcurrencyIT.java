package com.example.milestone;

import com.example.milestone.support.Fixture;
import com.example.milestone.support.TestEnv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 两个审批方并发确认（各自还带并发重试）：
 * 证据集合、里程碑投影与放款指令在一个事务中提交 —— 最终只产生一笔放款。
 */
class ApprovalConcurrencyIT {

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
    void concurrentApprovalsProduceExactlyOnePayment() throws Exception {
        var f = Fixture.create(env);
        f.submitAllEvidence(env);

        // 每个审批方一个幂等键、一个请求体，4 个并发线程重试同一请求
        var rightsBody = f.approvalBody(f.rightsId, f.rightsKeys, f.rightsCertId,
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString());
        var verifierBody = f.approvalBody(f.verifierId, f.verifierKeys, f.verifierCertId,
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString());

        int retriesPerApprover = 4;
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(retriesPerApprover * 2);
        var rightsFutures = new ArrayList<Future<TestEnv.Resp>>();
        var verifierFutures = new ArrayList<Future<TestEnv.Resp>>();
        for (int i = 0; i < retriesPerApprover; i++) {
            rightsFutures.add(pool.submit(() -> {
                gate.await();
                return env.post("/api/milestones/" + f.milestoneId + "/approvals",
                        rightsBody, f.rightsId, "rk-concurrent");
            }));
            verifierFutures.add(pool.submit(() -> {
                gate.await();
                return env.post("/api/milestones/" + f.milestoneId + "/approvals",
                        verifierBody, f.verifierId, "vk-concurrent");
            }));
        }
        gate.countDown();

        var rightsResponses = new ArrayList<TestEnv.Resp>();
        var verifierResponses = new ArrayList<TestEnv.Resp>();
        for (var fut : rightsFutures) rightsResponses.add(fut.get());
        for (var fut : verifierFutures) verifierResponses.add(fut.get());
        pool.shutdown();

        // 同键重试：所有响应一致（一个原始决定 + 若干重放）
        for (var r : rightsResponses) {
            assertEquals(201, r.status(), () -> String.valueOf(r.json()));
            assertEquals(rightsResponses.get(0).json(), r.json());
        }
        for (var r : verifierResponses) {
            assertEquals(201, r.status(), () -> String.valueOf(r.json()));
            assertEquals(verifierResponses.get(0).json(), r.json());
        }
        assertTrue(rightsResponses.stream().anyMatch(TestEnv.Resp::replay)
                || verifierResponses.stream().anyMatch(TestEnv.Resp::replay),
                "并发重试中应至少出现一次幂等重放");

        // 账本断言：恰好 2 条审批、1 笔放款、里程碑 PAID
        assertEquals(2, env.count(
                "SELECT count(*) FROM milestone_approval WHERE milestone_id = '" + f.milestoneId + "'"));
        assertEquals(1, env.count(
                "SELECT count(*) FROM payment_instruction WHERE milestone_id = '" + f.milestoneId + "'"));
        assertEquals(3, env.count(
                "SELECT count(*) FROM payment_evidence pe JOIN payment_instruction pi"
                        + " ON pi.id = pe.payment_id WHERE pi.milestone_id = '" + f.milestoneId + "'"));
        assertEquals(2, env.count(
                "SELECT count(*) FROM idempotency_record WHERE idempotency_key IN"
                        + " ('rk-concurrent','vk-concurrent')"));

        var milestone = env.get("/api/milestones/" + f.milestoneId, f.ownerId);
        assertEquals("PAID", milestone.json().get("status").asText());

        // 放款证据集合：全部来自同一版本，哈希可重算一致
        String paymentId = milestone.json().get("paymentId").asText();
        var evidenceSet = env.get("/api/payments/" + paymentId + "/evidence-set", f.ownerId);
        assertEquals(200, evidenceSet.status());
        assertTrue(evidenceSet.json().get("singleVersion").asBoolean());
        assertTrue(evidenceSet.json().get("hashConsistent").asBoolean());
        assertEquals(3, evidenceSet.json().get("events").size());
        assertEquals(f.versionId.toString(),
                evidenceSet.json().get("projectVersionId").asText());
    }

    @Test
    void secondApproverWaitsForFirstAndCompletesAtomically() throws Exception {
        var f = Fixture.create(env);
        f.submitAllEvidence(env);

        var first = f.approve(env, f.rightsId, f.rightsKeys, f.rightsCertId, "seq-r-1");
        assertEquals(201, first.status());
        assertEquals("IN_REVIEW", first.json().get("status").asText());
        assertTrue(first.json().get("paymentId").isNull());

        var second = f.approve(env, f.verifierId, f.verifierKeys, f.verifierCertId, "seq-v-1");
        assertEquals(201, second.status());
        assertEquals("PAID", second.json().get("status").asText());
        assertFalse(second.json().get("paymentId").isNull());

        assertEquals(1, env.count(
                "SELECT count(*) FROM payment_instruction WHERE milestone_id = '" + f.milestoneId + "'"));
    }
}

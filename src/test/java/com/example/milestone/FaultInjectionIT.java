package com.example.milestone;

import com.example.milestone.fault.FaultInjector;
import com.example.milestone.support.Fixture;
import com.example.milestone.support.TestEnv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 故障注入：无论崩溃发生在放款事务提交前还是提交后，
 * 幂等重试后系统都处于一致状态，且只产生一笔放款。
 */
class FaultInjectionIT {

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
    void crashBeforeCommitRollsBackEverything() throws Exception {
        var f = Fixture.create(env);
        f.submitAllEvidence(env);
        assertEquals(201, f.approve(env, f.rightsId, f.rightsKeys, f.rightsCertId, "fi1-r").status());

        // 放款写入后、提交前崩溃 -> 整个事务回滚
        env.faults.arm(FaultInjector.Point.BEFORE_PAYMENT_COMMIT, 1);
        var verifierBody = f.approvalBody(f.verifierId, f.verifierKeys, f.verifierCertId,
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString());
        var crashed = env.post("/api/milestones/" + f.milestoneId + "/approvals",
                verifierBody, f.verifierId, "fi1-v");
        assertEquals(500, crashed.status());
        assertEquals("FAULT_INJECTED", crashed.errorCode());

        // 审批、放款、幂等记录全部回滚
        assertEquals(1, env.count(
                "SELECT count(*) FROM milestone_approval WHERE milestone_id = '" + f.milestoneId + "'"));
        assertEquals(0, env.count(
                "SELECT count(*) FROM payment_instruction WHERE milestone_id = '" + f.milestoneId + "'"));
        assertEquals(0, env.count(
                "SELECT count(*) FROM idempotency_record WHERE idempotency_key = 'fi1-v'"));
        var midState = env.get("/api/milestones/" + f.milestoneId, f.ownerId);
        assertEquals("IN_REVIEW", midState.json().get("status").asText());

        // 同一幂等键重试 -> 重新执行，只产生一笔放款
        var retried = env.post("/api/milestones/" + f.milestoneId + "/approvals",
                verifierBody, f.verifierId, "fi1-v");
        assertEquals(201, retried.status(), () -> String.valueOf(retried.json()));
        assertFalse(retried.replay());
        assertEquals("PAID", retried.json().get("status").asText());
        assertEquals(2, env.count(
                "SELECT count(*) FROM milestone_approval WHERE milestone_id = '" + f.milestoneId + "'"));
        assertEquals(1, env.count(
                "SELECT count(*) FROM payment_instruction WHERE milestone_id = '" + f.milestoneId + "'"));
    }

    @Test
    void crashAfterCommitIsHealedByIdempotentReplay() throws Exception {
        var f = Fixture.create(env);
        f.submitAllEvidence(env);
        assertEquals(201, f.approve(env, f.rightsId, f.rightsKeys, f.rightsCertId, "fi2-r").status());

        // 事务已提交、响应写回前崩溃 -> 客户端看到失败，但放款已落库
        env.faults.arm(FaultInjector.Point.AFTER_APPROVAL_COMMIT, 1);
        var verifierBody = f.approvalBody(f.verifierId, f.verifierKeys, f.verifierCertId,
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString());
        var crashed = env.post("/api/milestones/" + f.milestoneId + "/approvals",
                verifierBody, f.verifierId, "fi2-v");
        assertEquals(500, crashed.status());
        assertEquals(1, env.count(
                "SELECT count(*) FROM payment_instruction WHERE milestone_id = '" + f.milestoneId + "'"));

        // 客户端按原幂等键重试 -> 返回原决定（含 paymentId），不会重复放款
        var retried = env.post("/api/milestones/" + f.milestoneId + "/approvals",
                verifierBody, f.verifierId, "fi2-v");
        assertEquals(201, retried.status(), () -> String.valueOf(retried.json()));
        assertTrue(retried.replay());
        assertEquals("PAID", retried.json().get("status").asText());
        assertFalse(retried.json().get("paymentId").isNull());
        assertEquals(1, env.count(
                "SELECT count(*) FROM payment_instruction WHERE milestone_id = '" + f.milestoneId + "'"));
        assertEquals(2, env.count(
                "SELECT count(*) FROM milestone_approval WHERE milestone_id = '" + f.milestoneId + "'"));
    }
}

package com.example.milestone.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.milestone.service.TestHooks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 故障注入：放款指令落库后、事务提交前崩溃 → 整体回滚；
 * 同键重试安全重放，最终仍只生成一笔放款。
 */
class FaultInjectionIT extends ITBase {

    @BeforeAll
    static void enableHooks() {
        TestHooks.enableForTests();
    }

    @Test
    @DisplayName("提交前故障整体回滚，同键重试后只生成一笔放款")
    void failureBeforeCommitRollsBackThenRetryDisbursesOnce() {
        var f = newStandardProject();
        submitAllEvidence(f);

        // 第一方审批先落库（独立事务，已提交）
        var first = bob.approve(f.projectId(), f.milestoneId(), "approve-bob-" + System.nanoTime());
        assertEquals(202, first.status(), "第一方审批应进入等待: " + first.body());
        assertEquals(1, sqlLong("SELECT COUNT(*) FROM approvals WHERE milestone_id = ?::uuid", f.milestoneId()));

        // 故障注入：放款指令插入后、提交前断点
        String key = "approve-carol-" + System.nanoTime();
        TestHooks.arm(TestHooks.Point.AFTER_DISBURSEMENT_INSERT, 1);
        var crashed = carol.approve(f.projectId(), f.milestoneId(), key);
        assertEquals(500, crashed.status(), "注入故障应使请求失败");

        // 回滚核查：无放款、第二方审批未残留、里程碑未推进、幂等决定未记录
        assertEquals(0, disbursementCount(f.projectId()), "回滚后不得存在放款指令");
        assertEquals(1, sqlLong("SELECT COUNT(*) FROM approvals WHERE milestone_id = ?::uuid", f.milestoneId()),
                "回滚后不得残留崩溃事务的审批");
        assertEquals("IN_REVIEW", sqlString("SELECT status FROM milestones WHERE id = ?::uuid", f.milestoneId()));
        assertEquals(0, sqlLong("SELECT COUNT(*) FROM idempotency_keys WHERE idem_key = ?", key),
                "回滚后不得残留幂等决定");

        // 同键重试：重新执行（非回放），成功放款
        var retried = carol.approve(f.projectId(), f.milestoneId(), key);
        assertEquals(201, retried.status(), "故障恢复后同键重试应成功: " + retried.body());
        assertEquals("DISBURSED", retried.body().get("status").asText());
        assertEquals(1, disbursementCount(f.projectId()));

        // 再次同键：回放原决定，放款数不变
        var replayed = carol.approve(f.projectId(), f.milestoneId(), key);
        assertTrue(replayed.replay());
        assertEquals(201, replayed.status());
        assertEquals(1, disbursementCount(f.projectId()), "全流程只应有一笔放款");

        // 事件时间线自洽：审批事件与放款事件都在，且只有各一条（第二方）
        assertEquals(1, sqlLong(
                "SELECT COUNT(*) FROM evidence_events WHERE project_id = ?::uuid AND event_type = 'MILESTONE_DISBURSED'",
                f.projectId()));
        assertEquals(2, sqlLong(
                "SELECT COUNT(*) FROM evidence_events WHERE project_id = ?::uuid AND event_type = 'APPROVAL_GRANTED'",
                f.projectId()));
    }

    @Test
    @DisplayName("审批落库后故障：审批不残留，重试不冲突")
    void failureAfterApprovalInsertRollsBackApproval() {
        var f = newStandardProject();
        submitAllEvidence(f);
        String key = "approve-bob-" + System.nanoTime();
        TestHooks.arm(TestHooks.Point.AFTER_APPROVAL_INSERT, 1);
        var crashed = bob.approve(f.projectId(), f.milestoneId(), key);
        assertEquals(500, crashed.status());
        assertEquals(0, sqlLong("SELECT COUNT(*) FROM approvals WHERE milestone_id = ?::uuid", f.milestoneId()));

        var retried = bob.approve(f.projectId(), f.milestoneId(), key);
        assertEquals(202, retried.status(), "回滚后同键重试应重新执行: " + retried.body());
        assertEquals(1, sqlLong("SELECT COUNT(*) FROM approvals WHERE milestone_id = ?::uuid", f.milestoneId()));
    }
}

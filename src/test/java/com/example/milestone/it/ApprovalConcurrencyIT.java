package com.example.milestone.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 两个审批方并发确认：证据集合、里程碑投影与放款指令在一个事务提交，
 * 全库至多一笔放款；同键重试回放原决定。
 */
class ApprovalConcurrencyIT extends ITBase {

    @Test
    @DisplayName("并发双审批只生成一笔放款，重试回放原决定")
    void concurrentApprovalsProduceExactlyOneDisbursement() throws Exception {
        var f = newStandardProject();
        submitAllEvidence(f);

        String keyBob = "approve-bob-" + System.nanoTime();
        String keyCarol = "approve-carol-" + System.nanoTime();
        var ready = new CountDownLatch(2);
        var fire = new CountDownLatch(1);
        var bobResult = new AtomicReference<LedgerClient.Result>();
        var carolResult = new AtomicReference<LedgerClient.Result>();
        var failure = new AtomicReference<Throwable>();

        Thread t1 = Thread.ofVirtual().start(() -> {
            try {
                ready.countDown();
                assertTrue(fire.await(10, TimeUnit.SECONDS));
                bobResult.set(bob.approve(f.projectId(), f.milestoneId(), keyBob));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        Thread t2 = Thread.ofVirtual().start(() -> {
            try {
                ready.countDown();
                assertTrue(fire.await(10, TimeUnit.SECONDS));
                carolResult.set(carol.approve(f.projectId(), f.milestoneId(), keyCarol));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        fire.countDown();
        t1.join(30_000);
        t2.join(30_000);
        if (failure.get() != null) throw new AssertionError("并发审批线程异常", failure.get());

        // 两个审批方都成功：一个 202（先到，等待另一方），一个 201（完成放款）
        int s1 = bobResult.get().status();
        int s2 = carolResult.get().status();
        assertTrue((s1 == 202 && s2 == 201) || (s1 == 201 && s2 == 202),
                "并发审批应为一个 202 一个 201，实际: " + s1 + ", " + s2);

        // 核心断言：只生成一笔放款
        assertEquals(1, disbursementCount(f.projectId()), "并发审批不得产生重复放款");
        assertEquals(2, sqlLong("SELECT COUNT(*) FROM approvals WHERE milestone_id = ?::uuid", f.milestoneId()));
        assertEquals("DISBURSED", sqlString(
                "SELECT status FROM milestones WHERE id = ?::uuid", f.milestoneId()));

        // 同键重试：原决定回放，放款数不变
        var bobReplay = bob.approve(f.projectId(), f.milestoneId(), keyBob);
        assertTrue(bobReplay.replay(), "同键重试应标记回放");
        assertEquals(bobResult.get().status(), bobReplay.status());
        assertEquals(bobResult.get().body().toString(), bobReplay.body().toString());
        var carolReplay = carol.approve(f.projectId(), f.milestoneId(), keyCarol);
        assertTrue(carolReplay.replay());
        assertEquals(1, disbursementCount(f.projectId()));

        // 换键再批：里程碑已终结，返回领域决定 409（同样被记录、可回放）
        var late = dave; // 无审批角色 → 403，不记录
        assertEquals(403, late.approve(f.projectId(), f.milestoneId(), "k-" + System.nanoTime()).status());
        var again = bob.approve(f.projectId(), f.milestoneId(), "k-" + System.nanoTime());
        assertEquals(409, again.status());
        assertEquals("ALREADY_DISBURSED", again.errorCode());
        assertEquals(1, disbursementCount(f.projectId()));
    }

    @Test
    @DisplayName("同一审批方同键并发重试：一次执行一次回放")
    void sameKeyConcurrentRetryExecutesOnce() throws Exception {
        var f = newStandardProject();
        submitAllEvidence(f);
        String key = "approve-dup-" + System.nanoTime();

        var fire = new CountDownLatch(1);
        var r1 = new AtomicReference<LedgerClient.Result>();
        var r2 = new AtomicReference<LedgerClient.Result>();
        Thread t1 = Thread.ofVirtual().start(() -> {
            awaitQuietly(fire);
            r1.set(bob.approve(f.projectId(), f.milestoneId(), key));
        });
        Thread t2 = Thread.ofVirtual().start(() -> {
            awaitQuietly(fire);
            r2.set(bob.approve(f.projectId(), f.milestoneId(), key));
        });
        fire.countDown();
        t1.join(30_000);
        t2.join(30_000);

        // 恰好一个执行（202）、一个回放（同状态同体）
        boolean r1Replay = r1.get().replay();
        boolean r2Replay = r2.get().replay();
        assertTrue(r1Replay != r2Replay, "同键并发应恰好一次执行一次回放");
        assertEquals(r1.get().status(), r2.get().status());
        assertEquals(r1.get().body().toString(), r2.get().body().toString());
        assertEquals(1, sqlLong("SELECT COUNT(*) FROM approvals WHERE milestone_id = ?::uuid AND role = 'RIGHTS_HOLDER'",
                f.milestoneId()));

        // 补齐另一方审批后正常放款，仍只有一笔
        var done = carol.approve(f.projectId(), f.milestoneId(), "approve-carol-" + System.nanoTime());
        assertEquals(201, done.status());
        assertEquals(1, disbursementCount(f.projectId()));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

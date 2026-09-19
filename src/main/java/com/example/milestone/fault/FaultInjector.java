package com.example.milestone.fault;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 故障注入点（仅测试装配，生产永不 arm，hit 为零开销）。
 * 用于证明：无论在提交前还是提交后发生崩溃，重试后系统都处于一致状态且只产生一笔放款。
 */
public final class FaultInjector {

    public enum Point {
        /** 放款指令已写入、事务提交之前 —— 崩溃应导致整体回滚。 */
        BEFORE_PAYMENT_COMMIT,
        /** 审批事务提交之后、响应写回之前 —— 崩溃后靠幂等记录返回原决定。 */
        AFTER_APPROVAL_COMMIT
    }

    public static final class FaultInjected extends RuntimeException {
        public FaultInjected(Point point) {
            super("injected fault at " + point);
        }
    }

    private final Map<Point, AtomicInteger> armed = new EnumMap<>(Point.class);

    public FaultInjector() {
        for (Point p : Point.values()) armed.put(p, new AtomicInteger(0));
    }

    public void arm(Point point, int times) {
        armed.get(point).set(times);
    }

    public void hit(Point point) {
        var counter = armed.get(point);
        while (true) {
            int remaining = counter.get();
            if (remaining <= 0) return;
            if (counter.compareAndSet(remaining, remaining - 1)) throw new FaultInjected(point);
        }
    }
}

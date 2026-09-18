package com.example.milestone.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 故障注入钩子：仅集成测试使用（IT 与应用同 JVM，通过 {@link #enableForTests} 打开）。
 * 生产镜像中从未启用，checkpoint 为纯 no-op。
 */
public final class TestHooks {
    public enum Point {
        AFTER_APPROVAL_INSERT,
        AFTER_DISBURSEMENT_INSERT
    }

    public static final class InjectedFailure extends RuntimeException {
        public InjectedFailure(Point point) {
            super("注入故障: " + point);
        }
    }

    private static volatile boolean enabled = false;
    private static final Map<Point, AtomicInteger> armed = new ConcurrentHashMap<>();

    private TestHooks() {}

    public static void enableForTests() {
        enabled = true;
    }

    public static void arm(Point point, int times) {
        if (!enabled) throw new IllegalStateException("故障注入未启用");
        armed.put(point, new AtomicInteger(times));
    }

    public static void checkpoint(Point point) {
        if (!enabled) return;
        AtomicInteger remaining = armed.get(point);
        if (remaining != null && remaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            armed.remove(point);
            throw new InjectedFailure(point);
        }
    }
}

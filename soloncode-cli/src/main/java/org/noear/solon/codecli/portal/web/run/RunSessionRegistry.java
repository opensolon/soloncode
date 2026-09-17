package org.noear.solon.codecli.portal.web.run;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * /web/run 活跃执行登记表
 *
 * <p>按 run-headless-mode-http.md「并发」条目：同 {@code session_id} 的并发请求由
 * 登记表拒绝后到者（409），防止上下文交叉污染。interrupt 端点据此定位目标执行并触发
 * 与 CLI 进程 SIGTERM 等价的取消（{@link Process#destroy()}）。</p>
 *
 * <p>两阶段登记：请求受理时即占位（409 判定先行），子进程启动后回填句柄；
 * interrupt 落在占位与回填之间时置 {@code killPending}，回填方立即销毁。</p>
 *
 * @author noear 2026/8/28 created
 */
public class RunSessionRegistry {
    private static final RunSessionRegistry INSTANCE = new RunSessionRegistry();

    public static RunSessionRegistry getInstance() {
        return INSTANCE;
    }

    private final Map<String, RunHandle> activeRuns = new ConcurrentHashMap<>();

    /**
     * 单次执行的取消句柄（占位 → 回填）
     */
    public static class RunHandle {
        public final String sessionId;
        public final long startedAt;
        final AtomicReference<Process> processRef = new AtomicReference<>();
        volatile boolean killPending;

        RunHandle(String sessionId) {
            this.sessionId = sessionId;
            this.startedAt = System.currentTimeMillis();
        }

        /** 回填子进程；若 interrupt 已先行到达则立即销毁 */
        void attach(Process process) {
            processRef.set(process);
            if (killPending) {
                process.destroy();
            }
        }

        /** 触发取消（SIGTERM 等价） */
        void cancel() {
            Process p = processRef.get();
            if (p != null) {
                p.destroy();
            } else {
                killPending = true;
            }
        }

        boolean isKillPending() {
            return killPending;
        }
    }

    /**
     * 尝试占位登记。同 sessionId 已有活跃执行时返回 null（调用方回 409）。
     */
    public RunHandle tryRegister(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        RunHandle handle = new RunHandle(sessionId);
        return activeRuns.putIfAbsent(sessionId, handle) == null ? handle : null;
    }

    /**
     * 注销执行（兼容测试及非异步调用方）。异步完成路径应优先使用带句柄的重载。
     */
    public void unregister(String sessionId) {
        if (sessionId != null) {
            activeRuns.remove(sessionId);
        }
    }

    /**
     * 按句柄注销执行。只有登记表中仍是同一轮执行时才移除，避免旧任务的 finally
     * 误删新一轮同 session 的登记。
     */
    public void unregister(String sessionId, RunHandle handle) {
        if (sessionId != null && handle != null) {
            activeRuns.remove(sessionId, handle);
        }
    }

    /**
     * 是否有活跃执行
     */
    public boolean isActive(String sessionId) {
        return sessionId != null && activeRuns.containsKey(sessionId);
    }

    /**
     * 触发取消（interrupt 端点）。找不到活跃执行时返回 false（调用方回 404）。
     */
    public boolean interrupt(String sessionId) {
        RunHandle handle = sessionId == null ? null : activeRuns.get(sessionId);
        if (handle == null) {
            return false;
        }
        try {
            handle.cancel();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    int activeCount() {
        return activeRuns.size();
    }
}

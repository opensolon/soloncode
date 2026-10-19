package org.noear.solon.codecli.channel.feishu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** 进程共享的目标串行调度；容量包含执行中的任务，排空即删除目标。 */
final class FeishuReplyQueue {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuReplyQueue.class);
    private final int perTargetLimit;
    private final int totalLimit;
    private final long perTargetByteLimit;
    private final long totalByteLimit;
    private final Map<Key, Lane> lanes = new HashMap<>();
    private int outstanding;
    private long outstandingBytes;

    FeishuReplyQueue(int perTargetLimit, int totalLimit) {
        this(perTargetLimit, totalLimit, 32L * 1024 * 1024, 128L * 1024 * 1024);
    }

    FeishuReplyQueue(int perTargetLimit, int totalLimit, long perTargetByteLimit, long totalByteLimit) {
        if (perTargetLimit < 1 || totalLimit < 1 || perTargetByteLimit < 1 || totalByteLimit < 1) {
            throw new IllegalArgumentException("queue limits must be positive");
        }
        this.perTargetLimit = perTargetLimit;
        this.totalLimit = totalLimit;
        this.perTargetByteLimit = perTargetByteLimit;
        this.totalByteLimit = totalByteLimit;
    }

    boolean enqueue(String appId, String openId, Runnable task, Consumer<Runnable> submit) {
        return enqueue(appId, openId, 0, task, submit);
    }

    boolean enqueue(String appId, String openId, long imageBytes, Runnable task, Consumer<Runnable> submit) {
        if (imageBytes < 0) {
            throw new IllegalArgumentException("image bytes must not be negative");
        }
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(submit, "submit");
        Key key = new Key(appId, openId);
        Lane lane;
        boolean start;
        synchronized (this) {
            lane = lanes.get(key);
            if (outstanding >= totalLimit || (lane != null && lane.count >= perTargetLimit)
                    || imageBytes > totalByteLimit - outstandingBytes
                    || imageBytes > perTargetByteLimit - (lane == null ? 0 : lane.bytes)) {
                throw new RejectedExecutionException("Feishu reply queue capacity exceeded"
                        + " (targetPending=" + (lane == null ? 0 : lane.count)
                        + ", totalPending=" + outstanding + ", imageBytes=" + imageBytes
                        + ", targetBytes=" + (lane == null ? 0 : lane.bytes)
                        + ", totalBytes=" + outstandingBytes + ")");
            }
            start = lane == null;
            if (start) {
                lane = new Lane();
                lanes.put(key, lane);
            }
            lane.tasks.addLast(new PendingReply(task, imageBytes));
            lane.count++;
            lane.bytes += imageBytes;
            outstanding++;
            outstandingBytes += imageBytes;
        }
        if (start) {
            Lane scheduled = lane;
            try {
                // 提交钩子只接收消费者，不能绕过队列；同步执行同样安全。
                submit.accept(() -> drain(key, scheduled));
            } catch (RuntimeException e) {
                synchronized (this) {
                    if (lanes.get(key) == scheduled && !scheduled.running) {
                        lanes.remove(key);
                        outstanding -= scheduled.count;
                        outstandingBytes -= scheduled.bytes;
                        scheduled.tasks.clear();
                        LOG.warn("[Feishu] Reply queue submission rejected; dropped {} queued replies", scheduled.count, e);
                    } else {
                        LOG.warn("[Feishu] Reply queue submit threw after consumer started", e);
                    }
                }
                if (e instanceof RejectedExecutionException) {
                    throw (RejectedExecutionException) e;
                }
                throw new RejectedExecutionException("Feishu reply queue submission failed", e);
            }
        }
        return true;
    }

    private void drain(Key key, Lane lane) {
        synchronized (this) {
            if (lanes.get(key) != lane || lane.running) {
                return;
            }
            lane.running = true;
        }
        try {
            while (true) {
                // 在任务边界响应中断；保留标志并由 finally 显式丢弃剩余项。
                // 不清中断继续消费，也不重投消费者，以免绕过执行器的关闭语义。
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                PendingReply task;
                synchronized (this) {
                    task = lane.tasks.pollFirst();
                    if (task == null) {
                        lanes.remove(key);
                        return;
                    }
                }
                try {
                    task.task.run();
                } catch (RuntimeException e) {
                    LOG.error("[Feishu] Queued reply failed", e);
                } finally {
                    synchronized (this) {
                        lane.count--;
                        outstanding--;
                        lane.bytes -= task.imageBytes;
                        outstandingBytes -= task.imageBytes;
                    }
                }
            }
        } finally {
            synchronized (this) {
                // 中断或致命异常退出也要释放剩余容量；不得删除已替换的新队列。
                if (lanes.get(key) == lane) {
                    lanes.remove(key);
                    outstanding -= lane.count;
                    outstandingBytes -= lane.bytes;
                    lane.tasks.clear();
                    if (Thread.currentThread().isInterrupted()) {
                        LOG.warn("[Feishu] Reply queue consumer interrupted; stopped draining and dropped {} queued replies", lane.count);
                    }
                }
            }
        }
    }

    synchronized int targetCount() {
        return lanes.size();
    }

    private static final class PendingReply {
        final Runnable task;
        final long imageBytes;

        PendingReply(Runnable task, long imageBytes) {
            this.task = task;
            this.imageBytes = imageBytes;
        }
    }

    private static final class Lane {
        final ArrayDeque<PendingReply> tasks = new ArrayDeque<>();
        int count;
        long bytes;
        boolean running;
    }

    private static final class Key {
        final String appId;
        final String openId;

        Key(String appId, String openId) {
            this.appId = appId;
            this.openId = openId;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return Objects.equals(appId, key.appId) && Objects.equals(openId, key.openId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(appId, openId);
        }
    }
}

package org.noear.solon.codecli.channel.feishu;

import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.artifact.PublishedImage;
import org.noear.solon.codecli.channel.ChannelReply;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FeishuReplyQueueTest {
    @Test
    void blockedImageKeepsMixedEntrypointsAcrossInstancesFifoWhileOtherTargetsRun() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        String app = UUID.randomUUID().toString();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Link first = new Link(app, "user", events, pool);
            Link second = new Link(app, "user", events, pool);
            first.entered = entered;
            first.release = release;
            first.sendReply("s", structured("first", "one.png", "two.png"), null, null, null);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            second.sendReply("other-session", "legacy", true);
            first.sendReply("s", structured("next", "three.png"), null, null, null);
            second.binding.appSecret = "changed-secret";
            second.binding.openId = "changed-user";

            Link otherUser = new Link(app, "other", events, pool);
            Link otherApp = new Link(app + "-other", "user", events, pool);
            otherUser.sendReply("s", "parallel-user", true);
            otherApp.sendReply("s", "parallel-app", true);
            assertTrue(otherUser.done.await(5, TimeUnit.SECONDS));
            assertTrue(otherApp.done.await(5, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("text:first", "upload:one.png", "legacy:parallel-user", "legacy:parallel-app"),
                    orderedParallelEvents(events));
            release.countDown();
            assertTrue(first.done.await(5, TimeUnit.SECONDS));
            // 排空后再次提交，验证空闲目标可重新创建消费者。
            first.sendReply("s", "end", true);
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            List<String> serial = new ArrayList<>(events);
            serial.remove("legacy:parallel-user");
            serial.remove("legacy:parallel-app");
            assertEquals(Arrays.asList("text:first", "upload:one.png", "image:one.png", "upload:two.png",
                    "image:two.png", "legacy:legacy", "text:next", "upload:three.png", "image:three.png", "legacy:end"), serial);
            assertEquals(Collections.singletonList(app + "|secret|user"), second.routes);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static List<String> orderedParallelEvents(List<String> events) {
        List<String> result = new ArrayList<>(events);
        // 两个不同目标彼此没有先后约束。
        if (result.indexOf("legacy:parallel-app") < result.indexOf("legacy:parallel-user")) {
            Collections.swap(result, 2, 3);
        }
        return result;
    }

    @Test
    void blockedLegacyReplyAlsoHoldsStructuredReplyFromAnotherInstance() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch release = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        String app = UUID.randomUUID().toString();
        try {
            Link legacy = new Link(app, "user", events, pool);
            Link structured = new Link(app, "user", events, pool);
            legacy.blockLegacy = true;
            legacy.entered = new CountDownLatch(1);
            legacy.release = release;
            legacy.sendReply("s", "old", true);
            assertTrue(legacy.entered.await(5, TimeUnit.SECONDS));
            structured.sendReply("s", structured("new", "image.png"), null, null, null);
            // 消费者启动钩子也不应被第二个实例调用。
            pool.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(Collections.singletonList("legacy:old"), events);
            release.countDown();
            assertTrue(legacy.done.await(5, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("legacy:old", "text:new", "upload:image.png", "image:image.png"), events);
            assertEquals(1, structured.done.getCount());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
    
    @Test
    void boundsCleanupExceptionsAndRejectedOrRepeatedConsumers() {
        FeishuReplyQueue queue = new FeishuReplyQueue(2, 3);
        List<Runnable> consumers = new ArrayList<>();
        List<Integer> events = new ArrayList<>();
        assertTrue(queue.enqueue("a", "u", () -> { throw new IllegalStateException("failed"); }, consumers::add));
        assertTrue(queue.enqueue("a", "u", () -> events.add(2), consumers::add));
        assertFalse(queue.enqueue("a", "u", () -> fail("per-target limit"), consumers::add));
        assertTrue(queue.enqueue("a", "v", () -> events.add(3), consumers::add));
        assertFalse(queue.enqueue("b", "v", () -> fail("global limit"), consumers::add));
        assertEquals(2, consumers.size());
        consumers.get(0).run();
        consumers.get(0).run();
        consumers.get(1).run();
        assertEquals(Arrays.asList(2, 3), events);
        assertEquals(0, queue.targetCount());
        List<Runnable> late = new ArrayList<>();
        assertFalse(queue.enqueue("a", "u", () -> fail("rejected"), task -> {
            late.add(task);
            throw new RejectedExecutionException();
        }));
        assertEquals(0, queue.targetCount());
        assertTrue(queue.enqueue("a", "u", () -> events.add(4), Runnable::run));
        late.get(0).run();
        assertEquals(Arrays.asList(2, 3, 4), events);
        assertEquals(0, queue.targetCount());
    }

    @Test
    void concurrentEnqueueAndIdleCleanupNeverOverlapOrLoseTasks() throws Exception {
        FeishuReplyQueue queue = new FeishuReplyQueue(1000, 1000);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger overlaps = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(400);
        try {
            List<Future<?>> submissions = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                submissions.add(pool.submit(() -> assertTrue(queue.enqueue("a", "u", () -> {
                    if (active.incrementAndGet() != 1) overlaps.incrementAndGet();
                    Thread.yield();
                    active.decrementAndGet();
                    done.countDown();
                }, pool::execute))));
            }
            for (Future<?> future : submissions) future.get(5, TimeUnit.SECONDS);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, overlaps.get());
            assertEquals(0, queue.targetCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void imageByteLimitsIncludeRunningRepliesAndKeepTextCompatible() {
        FeishuReplyQueue queue = new FeishuReplyQueue(10, 20, 6, 10);
        List<Runnable> consumers = new ArrayList<>();
        List<String> events = new ArrayList<>();
        assertTrue(queue.enqueue("a", "u", 6, () -> {
            // 已从待执行列表取出，但预算仍被运行中的回复占用。
            assertFalse(queue.enqueue("a", "u", 1, () -> fail("target bytes"), consumers::add));
            assertFalse(queue.enqueue("a", "w", 1, () -> fail("global bytes"), consumers::add));
            assertTrue(queue.enqueue("a", "u", () -> events.add("text"), consumers::add));
            events.add("image");
        }, consumers::add));
        assertTrue(queue.enqueue("a", "v", 4, () -> events.add("other"), consumers::add));
        assertFalse(queue.enqueue("a", "w", Long.MAX_VALUE, () -> fail("overflow"), consumers::add));
        consumers.get(0).run();
        assertEquals(Arrays.asList("image", "text"), events);
        // 第一个目标完成后立即释放六字节，另一个目标仍占用四字节。
        assertTrue(queue.enqueue("a", "w", 6, () -> events.add("released"), Runnable::run));
        consumers.get(1).run();
        assertEquals(Arrays.asList("image", "text", "released", "other"), events);
        assertEquals(0, queue.targetCount());
        assertTrue(queue.enqueue("a", "u", 6, () -> {}, Runnable::run));
    }

    @Test
    void failedTasksReleaseImageBudgetAndContinueFifo() {
        FeishuReplyQueue queue = new FeishuReplyQueue(10, 20, 10, 10);
        List<Runnable> consumers = new ArrayList<>();
        List<Integer> events = new ArrayList<>();
        assertTrue(queue.enqueue("a", "u", 6, () -> { throw new IllegalStateException("failed"); }, consumers::add));
        assertTrue(queue.enqueue("a", "u", 4, () -> {
            // 前一条异常回复已经释放预算，当前运行中的四字节尚未释放。
            assertTrue(queue.enqueue("a", "v", 6, () -> events.add(1), Runnable::run));
            assertFalse(queue.enqueue("a", "v", 7, () -> fail("running bytes"), consumers::add));
            events.add(2);
        }, consumers::add));
        consumers.get(0).run();
        assertEquals(Arrays.asList(1, 2), events);
        assertTrue(queue.enqueue("a", "u", 10, () -> {}, Runnable::run));
        assertEquals(0, queue.targetCount());
    }

    @Test
    void submissionRejectionRollsBackAllLaneBytesAndLateConsumerCannotRun() {
        FeishuReplyQueue queue = new FeishuReplyQueue(10, 20, 10, 10);
        List<Runnable> late = new ArrayList<>();
        assertFalse(queue.enqueue("a", "u", 6, () -> fail("rejected first"), consumer -> {
            late.add(consumer);
            assertTrue(queue.enqueue("a", "u", 4, () -> fail("rejected follower"), late::add));
            throw new RejectedExecutionException();
        }));
        assertEquals(0, queue.targetCount());
        assertTrue(queue.enqueue("a", "v", 10, () -> {}, Runnable::run));
        late.get(0).run();
        assertTrue(queue.enqueue("a", "u", 10, () -> {}, Runnable::run));
        assertEquals(0, queue.targetCount());
    }

    @Test
    void fatalTaskReleasesRunningAndQueuedImageBytes() {
        FeishuReplyQueue queue = new FeishuReplyQueue(10, 20, 10, 10);
        List<Runnable> consumers = new ArrayList<>();
        assertTrue(queue.enqueue("a", "u", 6, () -> { throw new AssertionError("fatal"); }, consumers::add));
        assertTrue(queue.enqueue("a", "u", 4, () -> fail("dropped after fatal error"), consumers::add));
        assertThrows(AssertionError.class, () -> consumers.get(0).run());
        assertEquals(0, queue.targetCount());
        assertTrue(queue.enqueue("a", "v", 10, () -> {}, Runnable::run));
        assertThrows(IllegalArgumentException.class,
                () -> queue.enqueue("a", "u", -1, () -> {}, Runnable::run));
    }

    private static ChannelReply structured(String text, String... filenames) {
        List<PublishedImage> images = new ArrayList<>();
        for (String name : filenames) images.add(new PublishedImage(new byte[]{1}, name, null));
        return new ChannelReply(text, true, "run", images);
    }

    private static class Link extends FeishuLink {
        final FeishuBinding binding = new FeishuBinding();
        final List<String> events;
        final List<String> routes = new CopyOnWriteArrayList<>();
        final ExecutorService pool;
        final CountDownLatch done = new CountDownLatch(1);
        CountDownLatch entered;
        CountDownLatch release;
        boolean blockLegacy;

        Link(String app, String user, List<String> events, ExecutorService pool) {
            super("workspace", null);
            binding.appId = app;
            binding.appSecret = "secret";
            binding.openId = user;
            this.events = events;
            this.pool = pool;
        }

        @Override protected FeishuBinding currentBinding(String session) { return binding; }
        @Override protected void submitReplyTask(Runnable task) {
            pool.execute(() -> { try { task.run(); } finally { done.countDown(); } });
        }
        @Override protected String getReplyAccessToken(FeishuBinding snapshot) {
            routes.add(snapshot.appId + "|" + snapshot.appSecret + "|" + snapshot.openId);
            return "token";
        }
        @Override protected boolean sendReplyTextChunk(String token, String user, String text, int part) {
            events.add("legacy:" + text);
            if (blockLegacy) awaitRelease();
            return true;
        }
        @Override protected void sendTextOnce(FeishuBinding snapshot, String text) { events.add("text:" + text); }
        @Override protected String uploadReplyImage(FeishuBinding snapshot, byte[] bytes, String filename) {
            events.add("upload:" + filename);
            if (entered != null && filename.equals("one.png")) awaitRelease();
            return filename;
        }
        private void awaitRelease() {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        @Override protected String sendReplyImage(FeishuBinding snapshot, String key, String uuid) {
            events.add("image:" + key);
            return uuid;
        }
    }
}

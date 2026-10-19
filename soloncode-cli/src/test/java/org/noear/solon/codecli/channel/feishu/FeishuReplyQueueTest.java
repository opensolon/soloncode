package org.noear.solon.codecli.channel.feishu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.codecli.channel.ChannelReply;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FeishuReplyQueueTest {
    @TempDir
    Path directory;

    @Test
    void blockedImageKeepsMixedEntrypointsAcrossInstancesFifoWhileOtherTargetsRun() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        String app = UUID.randomUUID().toString();
        ImageFixture images = new ImageFixture(directory, "one.png", "two.jpg", "three.png");
        ChannelReply initial = images.prepare("first", "one.png", "two.jpg", "one.png");
        ChannelReply next = images.prepare("next", "three.png", "one.png");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Link first = new Link(app, "user", events, pool, images);
            Link second = new Link(app, "user", events, pool, images);
            first.entered = entered;
            first.release = release;
            first.sendReply("s", initial, null, null, null);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // 首次上传已阻塞；尚未上传的 JPEG 和后续回复都只能使用准备时的字节快照。
            images.deleteSources();
            second.sendReply("other-session", "legacy", true);
            first.sendReply("s", next, null, null, null);
            second.binding.appSecret = "changed-secret";
            second.binding.openId = "changed-user";

            Link otherUser = new Link(app, "other", events, pool, images);
            Link otherApp = new Link(app + "-other", "user", events, pool, images);
            otherUser.sendReply("s", "parallel-user", true);
            otherApp.sendReply("s", "parallel-app", true);
            assertTrue(otherUser.done.await(5, TimeUnit.SECONDS));
            assertTrue(otherApp.done.await(5, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("upload:one.png:image.png", "md:parallel-user", "md:parallel-app"),
                    orderedParallelEvents(events));
            release.countDown();
            assertTrue(first.done.await(5, TimeUnit.SECONDS));
            // 排空后再次提交，验证空闲目标可重新创建消费者。
            first.sendReply("s", "end", true);
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            List<String> serial = new ArrayList<>(events);
            serial.remove("md:parallel-user");
            serial.remove("md:parallel-app");
            // 重复引用在同一回复内只上传一次，下一条回复仍须重新上传；全部上传完成后才发送原位 Markdown。
            assertEquals(Arrays.asList("upload:one.png:image.png", "upload:two.jpg:image.jpg",
                    "md:first ![one\\.png](key-one.png) ![two\\.jpg](key-two.jpg) ![one\\.png](key-one.png)",
                    "md:legacy", "upload:three.png:image.png", "upload:one.png:image.png",
                    "md:next ![three\\.png](key-three.png) ![one\\.png](key-one.png)", "md:end"), serial);
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
        if (result.indexOf("md:parallel-app") < result.indexOf("md:parallel-user")) {
            Collections.swap(result, 1, 2);
        }
        return result;
    }

    @Test
    void blockedLegacyReplyAlsoHoldsStructuredReplyFromAnotherInstance() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch release = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        String app = UUID.randomUUID().toString();
        ImageFixture images = new ImageFixture(directory, "image.png");
        ChannelReply prepared = images.prepare("new", "image.png");
        // 准备完成后、入队之前删除源文件，验证入队及消费都不重新读取。
        images.deleteSources();
        try {
            Link legacy = new Link(app, "user", events, pool, images);
            Link structured = new Link(app, "user", events, pool, images);
            legacy.blockLegacy = true;
            legacy.entered = new CountDownLatch(1);
            legacy.release = release;
            legacy.sendReply("s", "old", true);
            assertTrue(legacy.entered.await(5, TimeUnit.SECONDS));
            structured.sendReply("s", prepared, null, null, null);
            // 消费者启动钩子也不应被第二个实例调用。
            pool.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(Collections.singletonList("md:old"), events);
            release.countDown();
            assertTrue(legacy.done.await(5, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("md:old", "upload:image.png:image.png",
                    "md:new ![image\\.png](key-image.png)"), events);
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
        assertThrows(RejectedExecutionException.class,
                () -> queue.enqueue("a", "u", () -> fail("per-target limit"), consumers::add));
        assertTrue(queue.enqueue("a", "v", () -> events.add(3), consumers::add));
        assertThrows(RejectedExecutionException.class,
                () -> queue.enqueue("b", "v", () -> fail("global limit"), consumers::add));
        assertEquals(2, consumers.size());
        consumers.get(0).run();
        consumers.get(0).run();
        consumers.get(1).run();
        assertEquals(Arrays.asList(2, 3), events);
        assertEquals(0, queue.targetCount());
        List<Runnable> late = new ArrayList<>();
        assertThrows(RejectedExecutionException.class, () -> queue.enqueue("a", "u", () -> fail("rejected"), task -> {
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
            assertThrows(RejectedExecutionException.class,
                    () -> queue.enqueue("a", "u", 1, () -> fail("target bytes"), consumers::add));
            assertThrows(RejectedExecutionException.class,
                    () -> queue.enqueue("a", "w", 1, () -> fail("global bytes"), consumers::add));
            assertTrue(queue.enqueue("a", "u", () -> events.add("text"), consumers::add));
            events.add("image");
        }, consumers::add));
        assertTrue(queue.enqueue("a", "v", 4, () -> events.add("other"), consumers::add));
        assertThrows(RejectedExecutionException.class,
                () -> queue.enqueue("a", "w", Long.MAX_VALUE, () -> fail("overflow"), consumers::add));
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
            assertThrows(RejectedExecutionException.class,
                    () -> queue.enqueue("a", "v", 7, () -> fail("running bytes"), consumers::add));
            events.add(2);
        }, consumers::add));
        consumers.get(0).run();
        assertEquals(Arrays.asList(1, 2), events);
        assertTrue(queue.enqueue("a", "u", 10, () -> {}, Runnable::run));
        assertEquals(0, queue.targetCount());
    }

    @Test
    void submissionRejectionRollsBackAllLaneBytesAndLateConsumerCannotRun() {
        FeishuReplyQueue queue = new FeishuReplyQueue(2, 2, 10, 10);
        List<Runnable> late = new ArrayList<>();
        RejectedExecutionException rejection = new RejectedExecutionException("executor closed");
        assertSame(rejection, assertThrows(RejectedExecutionException.class,
                () -> queue.enqueue("a", "u", 6, () -> fail("rejected first"), consumer -> {
                    late.add(consumer);
                    assertTrue(queue.enqueue("a", "u", 4, () -> fail("rejected follower"), late::add));
                    throw rejection;
                })));
        assertEquals(0, queue.targetCount());
        List<Runnable> accepted = new ArrayList<>();
        assertTrue(queue.enqueue("a", "u", 6, () -> {}, accepted::add));
        assertTrue(queue.enqueue("a", "u", 4, () -> {}, accepted::add));
        late.get(0).run();
        assertEquals(1, queue.targetCount());
        accepted.get(0).run();
        assertTrue(queue.enqueue("a", "v", 10, () -> {}, Runnable::run));
        late.get(0).run();
        assertTrue(queue.enqueue("a", "u", 10, () -> {}, Runnable::run));
        assertEquals(0, queue.targetCount());
    }

    @Test
    void interruptedTaskReturningNormallyDropsFollowersAndReleasesCapacity() throws Exception {
        assertInterruptedDrainReleasesCapacity(false);
    }

    @Test
    void interruptedTaskThrowingRuntimeExceptionDropsFollowersAndReleasesCapacity() throws Exception {
        assertInterruptedDrainReleasesCapacity(true);
    }

    private static void assertInterruptedDrainReleasesCapacity(boolean throwFailure) throws Exception {
        FeishuReplyQueue queue = new FeishuReplyQueue(2, 2, 10, 10);
        List<Runnable> consumers = new ArrayList<>();
        List<Integer> events = new CopyOnWriteArrayList<>();
        assertTrue(queue.enqueue("a", "u", 6, () -> {
            events.add(1);
            Thread.currentThread().interrupt();
            if (throwFailure) throw new IllegalStateException("interrupted reply");
        }, consumers::add));
        assertTrue(queue.enqueue("a", "u", 4, () -> events.add(99), consumers::add));
        assertEquals(1, consumers.size());
        // 独立线程保留并验证中断标志，不污染 JUnit 线程，也不依赖线程池清理中断。
        FutureTask<Boolean> drain = new FutureTask<>(() -> {
            consumers.get(0).run();
            return Thread.currentThread().isInterrupted();
        });
        Thread worker = new Thread(drain, "feishu-interrupted-drain-test");
        worker.setDaemon(true);
        worker.start();
        assertTrue(drain.get(5, TimeUnit.SECONDS));
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertEquals(Collections.singletonList(1), events);
        assertEquals(0, queue.targetCount());

        // 同目标重建 lane，复用运行项和丢弃项的全部容量；旧消费者不能影响新 lane。
        assertTrue(queue.enqueue("a", "u", 6, () -> events.add(2), consumers::add));
        consumers.get(0).run();
        assertEquals(1, queue.targetCount());
        assertThrows(RejectedExecutionException.class,
                () -> queue.enqueue("a", "u", 5, () -> fail("target bytes not negative"), consumers::add));
        assertThrows(RejectedExecutionException.class,
                () -> queue.enqueue("a", "v", 5, () -> fail("global bytes not negative"), consumers::add));
        assertTrue(queue.enqueue("a", "u", 4, () -> events.add(3), consumers::add));
        assertThrows(RejectedExecutionException.class,
                () -> queue.enqueue("a", "u", () -> fail("target count not negative"), consumers::add));
        assertThrows(RejectedExecutionException.class,
                () -> queue.enqueue("a", "v", () -> fail("global count not negative"), consumers::add));
        consumers.get(1).run();
        assertEquals(Arrays.asList(1, 2, 3), events);
        assertEquals(0, queue.targetCount());
        assertTrue(queue.enqueue("a", "v", 10, () -> events.add(4), Runnable::run));
        assertEquals(Arrays.asList(1, 2, 3, 4), events);
        assertEquals(0, queue.targetCount());
    }

    @Test
    void alreadyInterruptedConsumerDropsEntireLaneWithoutAffectingOtherTargets() throws Exception {
        FeishuReplyQueue queue = new FeishuReplyQueue(2, 3, 10, 20);
        List<Runnable> consumers = new ArrayList<>();
        List<Integer> events = new CopyOnWriteArrayList<>();
        assertTrue(queue.enqueue("a", "u", 6, () -> fail("interrupted first"), consumers::add));
        assertTrue(queue.enqueue("a", "u", 4, () -> fail("interrupted follower"), consumers::add));
        assertTrue(queue.enqueue("a", "v", 10, () -> events.add(1), consumers::add));
        FutureTask<Boolean> drain = new FutureTask<>(() -> {
            Thread.currentThread().interrupt();
            consumers.get(0).run();
            return Thread.currentThread().isInterrupted();
        });
        Thread worker = new Thread(drain, "feishu-already-interrupted-test");
        worker.setDaemon(true);
        worker.start();
        assertTrue(drain.get(5, TimeUnit.SECONDS));
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertEquals(1, queue.targetCount());
        assertTrue(events.isEmpty());
        // 另一目标仍占用预算；被中断目标的全部容量已释放，旧消费者不能重放。
        assertTrue(queue.enqueue("a", "u", 10, () -> events.add(2), consumers::add));
        consumers.get(0).run();
        assertEquals(2, queue.targetCount());
        assertThrows(RejectedExecutionException.class,
                () -> queue.enqueue("a", "w", 1, () -> fail("other target budget retained"), consumers::add));
        consumers.get(1).run();
        consumers.get(2).run();
        assertEquals(Arrays.asList(1, 2), events);
        assertEquals(0, queue.targetCount());
    }

    @Test
    void shutdownNowInterruptsBlockedReplyWithoutConsumingOrResubmittingFollowers() throws Exception {
        FeishuReplyQueue queue = new FeishuReplyQueue(2, 2, 10, 10);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        List<Runnable> consumers = new ArrayList<>();
        List<Integer> events = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        assertTrue(queue.enqueue("a", "u", 6, () -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("interrupt timeout");
                fail("shutdown must interrupt the blocked reply");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                events.add(1);
            }
        }, consumers::add));
        assertTrue(queue.enqueue("a", "u", 4, () -> fail("shutdown follower"),
                consumer -> fail("must not submit another consumer")));
        FutureTask<Boolean> drain = new FutureTask<>(() -> {
            consumers.get(0).run();
            return Thread.currentThread().isInterrupted();
        });
        try {
            pool.execute(drain);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(pool.shutdownNow().isEmpty());
            assertTrue(drain.get(5, TimeUnit.SECONDS));
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(Collections.singletonList(1), events);
            assertEquals(0, queue.targetCount());
            // 不自动交接给已关闭执行器；只允许后续显式提交创建新 lane。
            assertThrows(RejectedExecutionException.class,
                    () -> queue.enqueue("a", "u", 10, () -> fail("closed executor"), pool::execute));
            assertEquals(0, queue.targetCount());
            assertTrue(queue.enqueue("a", "u", 10, () -> events.add(2), Runnable::run));
            consumers.get(0).run();
            assertEquals(Arrays.asList(1, 2), events);
            assertEquals(0, queue.targetCount());
        } finally {
            pool.shutdownNow();
            release.countDown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
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

    @Test
    void countLimitsIncludeRunningRepliesAndRejectedReplyIsNeverReplayed() {
        FeishuReplyQueue queue = new FeishuReplyQueue(2, 3);
        List<Runnable> consumers = new ArrayList<>();
        List<Integer> events = new ArrayList<>();
        assertTrue(queue.enqueue("a", "u", () -> {
            assertThrows(RejectedExecutionException.class,
                    () -> queue.enqueue("a", "u", () -> events.add(99), consumers::add));
            assertThrows(RejectedExecutionException.class,
                    () -> queue.enqueue("a", "w", () -> events.add(98), consumers::add));
            events.add(1);
        }, consumers::add));
        assertTrue(queue.enqueue("a", "u", () -> events.add(2), consumers::add));
        assertTrue(queue.enqueue("a", "v", () -> events.add(3), consumers::add));
        assertEquals(2, consumers.size());
        consumers.get(0).run();
        assertTrue(queue.enqueue("a", "u", () -> events.add(4), consumers::add));
        consumers.get(1).run();
        consumers.get(2).run();
        consumers.get(0).run();
        assertEquals(Arrays.asList(1, 2, 3, 4), events);
        assertEquals(0, queue.targetCount());
    }

    @Test
    void submissionFailureWrapsCauseAndReleasesCountAndBytes() {
        FeishuReplyQueue queue = new FeishuReplyQueue(1, 1, 10, 10);
        IllegalStateException failure = new IllegalStateException("submission failed");
        RejectedExecutionException rejected = assertThrows(RejectedExecutionException.class,
                () -> queue.enqueue("a", "u", 10, () -> fail("no fallback"), consumer -> {
                    throw failure;
                }));
        assertSame(failure, rejected.getCause());
        assertEquals(0, queue.targetCount());
        assertTrue(queue.enqueue("a", "u", 10, () -> {}, Runnable::run));
        assertEquals(0, queue.targetCount());
    }

    @Test
    void submissionThrowAfterConsumerStartsDoesNotReleaseOrReplayRunningLane() throws Exception {
        FeishuReplyQueue queue = new FeishuReplyQueue(2, 2, 10, 10);
        List<Integer> events = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        List<Future<?>> consumers = new ArrayList<>();
        RejectedExecutionException rejection = new RejectedExecutionException("after start");
        try {
            assertSame(rejection, assertThrows(RejectedExecutionException.class,
                    () -> queue.enqueue("a", "u", 6, () -> {
                        entered.countDown();
                        try {
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                        events.add(1);
                    }, consumer -> {
                        consumers.add(pool.submit(consumer));
                        try {
                            assertTrue(entered.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                        throw rejection;
                    })));
            assertEquals(1, queue.targetCount());
            assertThrows(RejectedExecutionException.class,
                    () -> queue.enqueue("a", "v", 5, () -> fail("running bytes"), Runnable::run));
            assertTrue(queue.enqueue("a", "u", 4, () -> events.add(2), consumer -> fail("extra consumer")));
            assertThrows(RejectedExecutionException.class,
                    () -> queue.enqueue("a", "v", () -> fail("running count"), Runnable::run));
            release.countDown();
            consumers.get(0).get(5, TimeUnit.SECONDS);
            assertEquals(Arrays.asList(1, 2), events);
            assertEquals(0, queue.targetCount());
            assertTrue(queue.enqueue("a", "u", 10, () -> events.add(3), Runnable::run));
            assertEquals(Arrays.asList(1, 2, 3), events);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
    @Test
    void submissionThrowAfterSynchronousDrainDoesNotReplayOrDoubleRelease() {
        FeishuReplyQueue queue = new FeishuReplyQueue(1, 1, 10, 10);
        List<Integer> events = new ArrayList<>();
        assertThrows(RejectedExecutionException.class,
                () -> queue.enqueue("a", "u", 10, () -> events.add(1), consumer -> {
                    consumer.run();
                    throw new IllegalStateException("after drain");
                }));
        List<Runnable> consumers = new ArrayList<>();
        assertTrue(queue.enqueue("a", "u", 10, () -> events.add(2), consumers::add));
        assertThrows(RejectedExecutionException.class,
                () -> queue.enqueue("a", "v", () -> fail("count not negative"), consumers::add));
        consumers.get(0).run();
        assertEquals(Arrays.asList(1, 2), events);
        assertEquals(0, queue.targetCount());
    }

    @Test
    void bothReplyEntrypointsPropagateSubmissionRejectionWithoutFallback() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.shutdown();
        List<String> events = new CopyOnWriteArrayList<>();
        String app = UUID.randomUUID().toString();
        ImageFixture images = new ImageFixture(directory, "image.png");
        ChannelReply reply = images.prepare("structured", "image.png");
        Link legacy = new Link(app, "legacy-user", events, pool, images);
        Link structured = new Link(app, "structured-user", events, pool, images);
        assertThrows(RejectedExecutionException.class,
                () -> legacy.sendReply("s", "legacy", true));
        assertThrows(RejectedExecutionException.class,
                () -> structured.sendReply("s", reply, null, null, null));
        assertTrue(events.isEmpty());
        assertTrue(legacy.routes.isEmpty());
        assertTrue(structured.routes.isEmpty());
    }

    private static class ImageFixture {
        final Path root;
        final Map<String, byte[]> encoded = new LinkedHashMap<>();
        ImageFixture(Path directory, String... filenames) throws IOException {
            root = directory.toRealPath();
            for (int i = 0; i < filenames.length; i++) {
                String name = filenames[i];
                BufferedImage image = new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB);
                try {
                    // 不同源图使用不同颜色，完整编码字节可唯一标识原文件。
                    int rgb = 0x204060 + i * 0x302010;
                    for (int y = 0; y < image.getHeight(); y++) {
                        for (int x = 0; x < image.getWidth(); x++) {
                            image.setRGB(x, y, rgb);
                        }
                    }
                    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        assertTrue(ImageIO.write(image, name.endsWith(".jpg") ? "jpeg" : "png", output));
                        byte[] bytes = output.toByteArray();
                        encoded.put(name, bytes);
                        Files.write(root.resolve(name), bytes);
                    }
                } finally {
                    image.flush();
                }
            }
        }

        ChannelReply prepare(String text, String... filenames) {
            StringBuilder markdown = new StringBuilder(text);
            for (String name : filenames) {
                markdown.append(" ![").append(name).append("](").append(name).append(')');
            }
            ChannelReply reply = ChannelReply.fromMarkdown(markdown.toString(), true, "run", root.toString(), ".");
            assertEquals(new HashSet<>(Arrays.asList(filenames)).size(), reply.getImages().size());
            assertEquals(filenames.length, reply.getImageReferences().size());
            return reply;
        }

        void deleteSources() throws IOException {
            for (String name : encoded.keySet()) {
                Path source = root.resolve(name);
                Files.delete(source);
                assertFalse(Files.exists(source));
            }
        }

        String filename(byte[] bytes) {
            for (Map.Entry<String, byte[]> entry : encoded.entrySet()) {
                if (Arrays.equals(entry.getValue(), bytes)) return entry.getKey();
            }
            throw new AssertionError("上传字节与准备时的完整图片快照不符");
        }
    }

    private static class Link extends FeishuLink {
        final FeishuBinding binding = new FeishuBinding();
        final List<String> events;
        final List<String> routes = new CopyOnWriteArrayList<>();
        final ExecutorService pool;
        final ImageFixture images;
        final CountDownLatch done = new CountDownLatch(1);
        CountDownLatch entered;
        CountDownLatch release;
        boolean blockLegacy;

        Link(String app, String user, List<String> events, ExecutorService pool, ImageFixture images) {
            super("workspace", null);
            binding.appId = app;
            binding.appSecret = "secret";
            binding.openId = user;
            this.events = events;
            this.pool = pool;
            this.images = images;
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
            events.add("md:" + text);
            if (blockLegacy) awaitRelease();
            return true;
        }
        @Override protected String uploadReplyImage(FeishuBinding snapshot, byte[] bytes, String filename) {
            String marker = images.filename(bytes);
            events.add("upload:" + marker + ":" + filename);
            if (entered != null && marker.equals("one.png")) awaitRelease();
            return "key-" + marker;
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
    }
}

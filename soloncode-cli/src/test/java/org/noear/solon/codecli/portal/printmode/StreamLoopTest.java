/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.portal.printmode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

import java.io.PipedReader;
import java.io.PipedWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StreamLoop 单测：常驻 JSONL 输入泵的编排语义。
 *
 * <p>全部离线可重复，不依赖 HarnessEngine：轮次执行与中断由测试替身承担，
 * 输入通过 PipedWriter 逐行投递，因此可以精确构造「模型正在跑时上游插话」
 * 这类时序场景。</p>
 *
 * @author noear
 */
public class StreamLoopTest {

    private static final int TIMEOUT_SEC = 10;

    private static final String USER_1 = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"一\"}}";
    private static final String USER_2 = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"二\"}}";
    private static final String USER_3 = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"三\"}}";
    private static final String INTERRUPT =
            "{\"type\":\"control_request\",\"request_id\":\"req-1\",\"request\":{\"subtype\":\"interrupt\"}}";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
    private final List<ONode> events = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger interruptCalls = new AtomicInteger();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** 记录提示词并直接成功的轮次执行替身 */
    private StreamLoop.TurnRunner recordingRunner() {
        return prompt -> {
            prompts.add(prompt);
            return StreamLoop.TurnOutcome.ok();
        };
    }

    private static String typeOf(ONode node) {
        return node.get("type").getString();
    }

    private static String subtypeOf(ONode node) {
        if (node.get("response").isObject()) {
            return node.get("response").get("subtype").getString();
        }
        return node.get("subtype").getString();
    }

    // ========== 基础多轮 ==========

    @Test
    @DisplayName("多行用户消息按顺序逐轮执行，EOF 后返回")
    public void testMultipleTurnsInOrder() throws Exception {
        try (PipedWriter writer = new PipedWriter()) {
            PipedReader reader = new PipedReader(writer, 8192);
            StreamLoop loop = new StreamLoop(reader, events::add, recordingRunner(),
                    () -> false, false);
            Future<Integer> exit = executor.submit(loop::run);

            writer.write(USER_1 + "\n" + USER_2 + "\n" + USER_3 + "\n");
            writer.flush();
            writer.close();

            assertEquals(PrintMode.EXIT_SUCCESS, exit.get(TIMEOUT_SEC, TimeUnit.SECONDS));
            assertEquals(3, loop.getTurnsExecuted());
            assertIterableEquals(java.util.Arrays.asList("一", "二", "三"), prompts);
        }
    }

    @Test
    @DisplayName("EOF 前排队的轮次不会被丢弃")
    public void testQueuedTurnsDrainedBeforeExit() throws Exception {
        int total = 32;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < total; i++) {
            sb.append("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"m").append(i).append("\"}}\n");
        }

        try (PipedWriter writer = new PipedWriter()) {
            PipedReader reader = new PipedReader(writer, 64 * 1024);
            StreamLoop loop = new StreamLoop(reader, events::add, recordingRunner(),
                    () -> false, false);
            Future<Integer> exit = executor.submit(loop::run);

            // 一次性灌入后立刻 EOF：EOF 哨兵排在队尾，前面的轮次必须全部执行完
            writer.write(sb.toString());
            writer.flush();
            writer.close();

            assertEquals(PrintMode.EXIT_SUCCESS, exit.get(TIMEOUT_SEC, TimeUnit.SECONDS));
            assertEquals(total, prompts.size());
            assertEquals("m0", prompts.get(0));
            assertEquals("m" + (total - 1), prompts.get(total - 1));
        }
    }

    @Test
    @DisplayName("一条消息都没收到时退出码为 0（连上又关掉不算错误）")
    public void testNoMessagesExitsZero() throws Exception {
        try (PipedWriter writer = new PipedWriter()) {
            PipedReader reader = new PipedReader(writer, 1024);
            StreamLoop loop = new StreamLoop(reader, events::add, recordingRunner(),
                    () -> false, false);
            Future<Integer> exit = executor.submit(loop::run);

            writer.close();

            assertEquals(PrintMode.EXIT_SUCCESS, exit.get(TIMEOUT_SEC, TimeUnit.SECONDS));
            assertEquals(0, loop.getTurnsExecuted());
            assertTrue(prompts.isEmpty());
        }
    }

    @Test
    @DisplayName("退出码取最后一轮的结果")
    public void testExitCodeFromLastTurn() throws Exception {
        StreamLoop.TurnRunner runner = prompt -> {
            prompts.add(prompt);
            return "二".equals(prompt)
                    ? StreamLoop.TurnOutcome.of(PrintMode.EXIT_MAX_TURNS)
                    : StreamLoop.TurnOutcome.ok();
        };

        try (PipedWriter writer = new PipedWriter()) {
            PipedReader reader = new PipedReader(writer, 8192);
            StreamLoop loop = new StreamLoop(reader, events::add, runner, () -> false, false);
            Future<Integer> exit = executor.submit(loop::run);

            writer.write(USER_1 + "\n" + USER_2 + "\n");
            writer.flush();
            writer.close();

            assertEquals(PrintMode.EXIT_MAX_TURNS, exit.get(TIMEOUT_SEC, TimeUnit.SECONDS));
            assertEquals(2, prompts.size(), "非终止性的失败轮次不应中断会话");
        }
    }

    @Test
    @DisplayName("terminal 结论立即终止会话，后续排队轮次不再执行")
    public void testTerminalOutcomeStopsSession() throws Exception {
        StreamLoop.TurnRunner runner = prompt -> {
            prompts.add(prompt);
            return StreamLoop.TurnOutcome.terminal(PrintMode.EXIT_BUDGET_EXCEEDED);
        };

        try (PipedWriter writer = new PipedWriter()) {
            PipedReader reader = new PipedReader(writer, 8192);
            StreamLoop loop = new StreamLoop(reader, events::add, runner, () -> false, false);
            Future<Integer> exit = executor.submit(loop::run);

            writer.write(USER_1 + "\n" + USER_2 + "\n" + USER_3 + "\n");
            writer.flush();
            writer.close();

            assertEquals(PrintMode.EXIT_BUDGET_EXCEEDED, exit.get(TIMEOUT_SEC, TimeUnit.SECONDS));
            assertEquals(1, prompts.size(), "预算超限后不能继续执行排队的轮次");
        }
    }

    // ========== 中断优先级（核心时序断言） ==========

    @Test
    @DisplayName("中断帧越过排队轮次，立即作用于正在跑的那一轮")
    public void testInterruptJumpsQueue() throws Exception {
        CountDownLatch turn1Started = new CountDownLatch(1);
        CountDownLatch releaseTurn1 = new CountDownLatch(1);
        CountDownLatch interruptSeen = new CountDownLatch(1);

        StreamLoop.TurnRunner runner = prompt -> {
            prompts.add(prompt);
            if ("一".equals(prompt)) {
                turn1Started.countDown();
                try {
                    // 卡住第一轮，模拟模型正在处理
                    assertTrue(releaseTurn1.await(TIMEOUT_SEC, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return StreamLoop.TurnOutcome.ok();
        };

        StreamLoop.Interrupter interrupter = () -> {
            interruptCalls.incrementAndGet();
            interruptSeen.countDown();
            return true;
        };

        try (PipedWriter writer = new PipedWriter()) {
            PipedReader reader = new PipedReader(writer, 8192);
            StreamLoop loop = new StreamLoop(reader, events::add, runner, interrupter, false);
            Future<Integer> exit = executor.submit(loop::run);

            writer.write(USER_1 + "\n");
            writer.flush();
            assertTrue(turn1Started.await(TIMEOUT_SEC, TimeUnit.SECONDS), "第一轮应已开始");

            // 先排一个待执行轮次，再投中断：中断不得排在它后面
            writer.write(USER_2 + "\n");
            writer.write(INTERRUPT + "\n");
            writer.flush();

            assertTrue(interruptSeen.await(TIMEOUT_SEC, TimeUnit.SECONDS),
                    "第一轮仍在执行、且队列里还有待执行轮次时，中断必须已经被处理");
            assertEquals(1, prompts.size(), "中断被处理时第二轮还不该开始");

            releaseTurn1.countDown();
            writer.close();

            assertEquals(PrintMode.EXIT_SUCCESS, exit.get(TIMEOUT_SEC, TimeUnit.SECONDS));
            assertEquals(2, prompts.size(), "中断只取消在跑的轮次，已排队的消息仍应执行");
            assertEquals(1, interruptCalls.get());
        }
    }

    @Test
    @DisplayName("中断成功时回 control_response/success，并带回 request_id")
    public void testInterruptSuccessResponse() throws Exception {
        CountDownLatch turn1Started = new CountDownLatch(1);
        CountDownLatch releaseTurn1 = new CountDownLatch(1);

        StreamLoop.TurnRunner runner = prompt -> {
            prompts.add(prompt);
            turn1Started.countDown();
            try {
                assertTrue(releaseTurn1.await(TIMEOUT_SEC, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return StreamLoop.TurnOutcome.ok();
        };

        try (PipedWriter writer = new PipedWriter()) {
            PipedReader reader = new PipedReader(writer, 8192);
            StreamLoop loop = new StreamLoop(reader, events::add, runner, () -> true, false);
            Future<Integer> exit = executor.submit(loop::run);

            writer.write(USER_1 + "\n");
            writer.flush();
            assertTrue(turn1Started.await(TIMEOUT_SEC, TimeUnit.SECONDS));

            writer.write(INTERRUPT + "\n");
            writer.flush();

            ONode response = awaitEvent("control_response");
            assertEquals("success", subtypeOf(response));
            assertEquals("req-1", response.get("response").get("request_id").getString());

            releaseTurn1.countDown();
            writer.close();
            exit.get(TIMEOUT_SEC, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("空闲时收到中断回 control_response/error，不伪报成功")
    public void testInterruptWhenIdleReportsError() throws Exception {
        try (PipedWriter writer = new PipedWriter()) {
            PipedReader reader = new PipedReader(writer, 8192);
            StreamLoop loop = new StreamLoop(reader, events::add, recordingRunner(),
                    () -> false, false);
            Future<Integer> exit = executor.submit(loop::run);

            writer.write(INTERRUPT + "\n");
            writer.flush();

            ONode response = awaitEvent("control_response");
            assertEquals("error", subtypeOf(response));
            assertEquals("no turn in progress", response.get("response").get("error").getString());

            writer.close();
            assertEquals(PrintMode.EXIT_SUCCESS, exit.get(TIMEOUT_SEC, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("不支持的控制 subtype 回 control_response/error，会话继续")
    public void testUnsupportedControlResponds() throws Exception {
        try (PipedWriter writer = new PipedWriter()) {
            PipedReader reader = new PipedReader(writer, 8192);
            StreamLoop loop = new StreamLoop(reader, events::add, recordingRunner(),
                    () -> false, false);
            Future<Integer> exit = executor.submit(loop::run);

            writer.write("{\"type\":\"control_request\",\"request_id\":\"r7\","
                    + "\"request\":{\"subtype\":\"set_model\"}}\n");
            writer.write(USER_1 + "\n");
            writer.flush();
            writer.close();

            assertEquals(PrintMode.EXIT_SUCCESS, exit.get(TIMEOUT_SEC, TimeUnit.SECONDS));

            ONode response = findEvent("control_response");
            assertNotNull(response);
            assertEquals("error", subtypeOf(response));
            assertTrue(response.get("response").get("error").getString().contains("set_model"));
            assertEquals(1, prompts.size(), "不支持的控制帧不应终止会话");
        }
    }

    // ========== 坏行容错 ==========

    @Test
    @DisplayName("坏行发 system/input_error 且不终止会话")
    public void testMalformedLineIsNonTerminal() throws Exception {
        try (PipedWriter writer = new PipedWriter()) {
            PipedReader reader = new PipedReader(writer, 8192);
            StreamLoop loop = new StreamLoop(reader, events::add, recordingRunner(),
                    () -> false, false);
            Future<Integer> exit = executor.submit(loop::run);

            writer.write("this is not json\n");
            writer.write("\n");
            writer.write(USER_1 + "\n");
            writer.flush();
            writer.close();

            assertEquals(PrintMode.EXIT_SUCCESS, exit.get(TIMEOUT_SEC, TimeUnit.SECONDS));
            assertEquals(1, prompts.size(), "坏行之后的正常消息仍要执行");

            ONode inputError = findEvent("system");
            assertNotNull(inputError, "坏行必须对上游可见");
            assertEquals("input_error", subtypeOf(inputError));
            assertNotEquals("error", typeOf(inputError),
                    "坏行不能用轮次级终止事件 type:error 表达");
        }
    }

    // ========== 回显 ==========

    @Test
    @DisplayName("--replay-user-messages 原样回显收到的用户消息")
    public void testReplayUserMessages() throws Exception {
        try (PipedWriter writer = new PipedWriter()) {
            PipedReader reader = new PipedReader(writer, 8192);
            StreamLoop loop = new StreamLoop(reader, events::add, recordingRunner(),
                    () -> false, true);
            Future<Integer> exit = executor.submit(loop::run);

            writer.write(USER_1 + "\n");
            writer.flush();
            writer.close();
            assertEquals(PrintMode.EXIT_SUCCESS, exit.get(TIMEOUT_SEC, TimeUnit.SECONDS));

            ONode replayed = findEvent("user");
            assertNotNull(replayed, "开启回显时应能看到 user 事件");
            assertEquals("一", replayed.get("message").get("content").getString());
        }
    }

    @Test
    @DisplayName("默认不回显用户消息")
    public void testNoReplayByDefault() throws Exception {
        try (PipedWriter writer = new PipedWriter()) {
            PipedReader reader = new PipedReader(writer, 8192);
            StreamLoop loop = new StreamLoop(reader, events::add, recordingRunner(),
                    () -> false, false);
            Future<Integer> exit = executor.submit(loop::run);

            writer.write(USER_1 + "\n");
            writer.flush();
            writer.close();
            assertEquals(PrintMode.EXIT_SUCCESS, exit.get(TIMEOUT_SEC, TimeUnit.SECONDS));

            assertNull(findEvent("user"));
            assertEquals(1, prompts.size());
        }
    }

    // ========== 事件构建 ==========

    @Test
    @DisplayName("control_response 在缺 request_id 时不写空字段")
    public void testControlResponseWithoutRequestId() {
        ONode ok = StreamLoop.controlResponseSuccess(null);
        assertEquals("control_response", typeOf(ok));
        assertFalse(ok.get("response").hasKey("request_id"));

        ONode err = StreamLoop.controlResponseError(null, null);
        assertEquals("unsupported control request", err.get("response").get("error").getString(),
                "error 说明不能为空");
    }

    // ========== 辅助 ==========

    private ONode awaitEvent(String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_SEC * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ONode found = findEvent(type);
            if (found != null) {
                return found;
            }
            Thread.sleep(10);
        }
        fail("timed out waiting for event type=" + type + ", got=" + events);
        return null;
    }

    private ONode findEvent(String type) {
        synchronized (events) {
            for (ONode event : events) {
                if (type.equals(typeOf(event))) {
                    return event;
                }
            }
        }
        return null;
    }
}

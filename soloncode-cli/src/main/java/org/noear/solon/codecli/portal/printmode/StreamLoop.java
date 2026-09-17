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

import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 常驻 JSONL 输入泵：把 stdin 的每一行用户消息驱动成一轮 Agent 执行。
 *
 * <p>本类刻意不依赖 HarnessEngine / AgentSession，只依赖两个函数式接口
 * （{@link TurnRunner} 与 {@link Interrupter}），因此排队、EOF 收尾、中断优先级、
 * 坏行容错这些编排逻辑可以脱离引擎单测。真实接线见 {@link StreamMode}。</p>
 *
 * <p><b>并发模型</b>：读线程与执行线程分离，对齐 Claude Code 的
 * {@code --input-format stream-json} 语义——模型正在处理时上游仍可继续投递消息，
 * 排队的消息不会丢；中断帧不排队，直接由读线程处理，否则「中断」会排在
 * 待执行轮次后面，语义正好相反。</p>
 *
 * <p><b>背压</b>：队列有界。写满时读线程阻塞在 {@code put}，背压经由操作系统
 * 管道缓冲自然传导给上游写端，既不丢消息也不会无上限占用内存。</p>
 *
 * @author noear
 */
public class StreamLoop {
    private static final Logger LOG = LoggerFactory.getLogger(StreamLoop.class);

    /** 待执行轮次队列容量：写满即对上游形成背压 */
    static final int QUEUE_CAPACITY = 256;

    /**
     * 一轮执行的承载者。
     */
    public interface TurnRunner {
        /**
         * 执行一轮。
         *
         * @param prompt 用户提示词
         * @return 本轮结果
         */
        TurnOutcome run(String prompt);
    }

    /**
     * 中断当前进行中轮次的承载者。
     */
    public interface Interrupter {
        /**
         * @return true 表示确有一个在跑的轮次被取消
         */
        boolean interrupt();
    }

    /**
     * 一轮执行结果：退出码 + 是否应终止整个会话。
     */
    public static class TurnOutcome {
        private final int exitCode;
        private final boolean terminal;

        private TurnOutcome(int exitCode, boolean terminal) {
            this.exitCode = exitCode;
            this.terminal = terminal;
        }

        /** 本轮成功，继续接收后续轮次 */
        public static TurnOutcome ok() {
            return new TurnOutcome(PrintMode.EXIT_SUCCESS, false);
        }

        /** 本轮以给定退出码结束，但会话继续 */
        public static TurnOutcome of(int exitCode) {
            return new TurnOutcome(exitCode, false);
        }

        /** 本轮以给定退出码结束，且整个会话立即终止（如预算超限） */
        public static TurnOutcome terminal(int exitCode) {
            return new TurnOutcome(exitCode, true);
        }

        public int getExitCode() {
            return exitCode;
        }

        public boolean isTerminal() {
            return terminal;
        }
    }

    private final Reader in;
    private final Consumer<ONode> emit;
    private final TurnRunner runner;
    private final Interrupter interrupter;
    private final boolean replayUserMessages;

    private final BlockingQueue<StreamInputMessage> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicInteger turnsExecuted = new AtomicInteger();

    public StreamLoop(InputStream in, Consumer<ONode> emit, TurnRunner runner,
                      Interrupter interrupter, boolean replayUserMessages) {
        this(new InputStreamReader(in, StandardCharsets.UTF_8), emit, runner, interrupter, replayUserMessages);
    }

    public StreamLoop(Reader in, Consumer<ONode> emit, TurnRunner runner,
                      Interrupter interrupter, boolean replayUserMessages) {
        this.in = in;
        this.emit = emit;
        this.runner = runner;
        this.interrupter = interrupter;
        this.replayUserMessages = replayUserMessages;
    }

    /** 已执行的轮次数（供测试与收尾判断） */
    public int getTurnsExecuted() {
        return turnsExecuted.get();
    }

    /**
     * 跑完整个常驻会话：读到 EOF、排队轮次全部执行完毕后返回。
     *
     * @return 退出码。取最后一轮的退出码；一轮都没有执行时为 0
     *         （客户端连上又关掉、什么都没问，不算错误）
     */
    public int run() {
        Thread reader = new Thread(this::readLoop, "SolonCode-StreamInput");
        reader.setDaemon(true);
        reader.start();

        int lastExitCode = PrintMode.EXIT_SUCCESS;

        try {
            while (true) {
                StreamInputMessage msg = queue.take();

                if (msg.getKind() == StreamInputMessage.Kind.EOF) {
                    break;
                }

                turnsExecuted.incrementAndGet();
                TurnOutcome outcome = runner.run(msg.getText());
                lastExitCode = outcome.getExitCode();

                if (outcome.isTerminal()) {
                    LOG.warn("Stream session terminated after turn {} with exit code {}",
                            turnsExecuted.get(), lastExitCode);
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PrintMode.EXIT_SIGTERM;
        }

        if (turnsExecuted.get() == 0) {
            LOG.warn("Stream session ended without receiving any user message");
        }

        return lastExitCode;
    }

    /**
     * 读线程主体：逐行解析并分发。
     *
     * <p>无论以何种方式结束（EOF / IO 异常 / 中断），finally 都会投递 EOF 哨兵，
     * 否则主循环会永远阻塞在 {@code queue.take()}。</p>
     */
    private void readLoop() {
        try (BufferedReader br = new BufferedReader(in)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!dispatch(StreamInputMessage.parse(line))) {
                    break;
                }
            }
        } catch (Exception e) {
            LOG.warn("Stream input reader stopped: {}", e.getMessage());
        } finally {
            try {
                queue.put(StreamInputMessage.eof());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 分发一条已解析的输入。
     *
     * @return false 表示读线程应停止（仅在被中断时）
     */
    private boolean dispatch(StreamInputMessage msg) {
        switch (msg.getKind()) {
            case USER:
                if (replayUserMessages && msg.getRaw() != null) {
                    emit.accept(msg.getRaw());
                }
                try {
                    // 有界队列：满则阻塞，向上游形成背压，绝不丢消息
                    queue.put(msg);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                return true;

            case INTERRUPT:
                // 不入队：中断必须越过待执行轮次立即作用于在跑的那一轮
                boolean cancelled = interrupter.interrupt();
                emit.accept(cancelled
                        ? controlResponseSuccess(msg.getRequestId())
                        : controlResponseError(msg.getRequestId(), "no turn in progress"));
                return true;

            case UNSUPPORTED_CONTROL:
                emit.accept(controlResponseError(msg.getRequestId(), msg.getDetail()));
                return true;

            case MALFORMED:
                // 坏行不终止会话（管道里混入日志行是常态），但必须让上游看得见
                LOG.warn("Ignored malformed stream input line: {}", msg.getDetail());
                emit.accept(inputErrorEvent(msg.getDetail()));
                return true;

            case IGNORED:
            default:
                return true;
        }
    }

    // ========== 出向事件 ==========

    /**
     * <pre>{"type":"control_response","response":{"subtype":"success","request_id":"req-1"}}</pre>
     */
    static ONode controlResponseSuccess(String requestId) {
        ONode node = new ONode();
        node.set("type", "control_response");
        ONode response = node.getOrNew("response");
        response.set("subtype", "success");
        if (requestId != null) {
            response.set("request_id", requestId);
        }
        return node;
    }

    /**
     * <pre>{"type":"control_response","response":{"subtype":"error","request_id":"req-1","error":"..."}}</pre>
     */
    static ONode controlResponseError(String requestId, String error) {
        ONode node = new ONode();
        node.set("type", "control_response");
        ONode response = node.getOrNew("response");
        response.set("subtype", "error");
        if (requestId != null) {
            response.set("request_id", requestId);
        }
        response.set("error", error != null ? error : "unsupported control request");
        return node;
    }

    /**
     * 非终止性的输入错误提示。
     *
     * <p>刻意不用 {@code type:"error"}——那是轮次级终止事件；这里用
     * {@code system/input_error} 表示「这一行被丢弃了，会话继续」。</p>
     *
     * <pre>{"type":"system","subtype":"input_error","message":"..."}</pre>
     */
    static ONode inputErrorEvent(String detail) {
        ONode node = new ONode();
        node.set("type", "system");
        node.set("subtype", "input_error");
        node.set("message", detail != null ? detail : "malformed input line");
        return node;
    }
}

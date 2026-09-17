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
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.config.AgentSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * Stream / 常驻无头模式执行器（{@code soloncode stream}）。
 *
 * <p>在 stdio 上维持一个常驻会话：stdin 是 JSONL 消息流，每行一个用户消息即一轮对话，
 * 进程不退出直到 stdin EOF。等价于 Claude Code 的
 * {@code claude -p --input-format stream-json --output-format stream-json}，
 * 但作为独立子命令暴露——{@code run} 保持纯单次语义，避免同一子命令有两种生命周期。</p>
 *
 * <p>与 {@code run --resume} 的区别：会话上下文靠<b>同一个进程内的同一个
 * {@link AgentSession}</b> 延续，不再每轮重启进程、重建引擎，省掉 JVM 与引擎冷启动；
 * 并且 {@code control_request/interrupt} 在这里有真实接收方。</p>
 *
 * <p>用法：
 * <pre>
 *   soloncode stream --verbose
 *   {"type":"user","message":{"role":"user","content":"我叫 Alice"}}
 *   {"type":"user","message":{"role":"user","content":"我叫什么"}}
 *   {"type":"control_request","request_id":"req-1","request":{"subtype":"interrupt"}}
 * </pre>
 * </p>
 *
 * @author noear
 */
public class StreamMode {
    private static final Logger LOG = LoggerFactory.getLogger(StreamMode.class);

    private final PrintMode printMode;
    private final PrintModeOptions options;
    private final InputStream in;

    /** 会话累计费用：预算按整个常驻会话核算，否则拆成多轮即可绕过 --max-budget-usd */
    private double sessionCostUsd;

    public StreamMode(HarnessEngine engine, AgentSettings agentSettings, PrintModeOptions options) {
        this(engine, agentSettings, options, System.in);
    }

    public StreamMode(HarnessEngine engine, AgentSettings agentSettings, PrintModeOptions options, InputStream in) {
        this.printMode = new PrintMode(engine, agentSettings, options);
        this.options = options;
        this.in = in;
    }

    /**
     * 跑完整个常驻会话，返回退出码。
     *
     * @return 最后一轮的退出码；未收到任何消息时为 0
     */
    public int execute() {
        // 1. 应用运行时选项（与 run 完全一致：maxTurns/model/tools/permission-mode/bare/add-dir）
        printMode.applyOptions();

        // 2. 会话在整个常驻期间只解析一次——这就是多轮上下文的载体
        AgentSession session = printMode.resolveSession();

        // 3. init 事件只发一次（不是每轮）
        printMode.emitStreamEvent(printMode.buildInitEvent(session));

        if (!options.isVerbose()) {
            LOG.warn("Running without --verbose: only per-turn 'result' events will be emitted");
        }

        StreamLoop loop = new StreamLoop(
                in,
                printMode::emitStreamEvent,
                prompt -> runTurn(session, prompt),
                printMode::interruptCurrentTurn,
                options.isReplayUserMessages());

        return loop.run();
    }

    /**
     * 执行一轮，并把结果换算成循环层要的结论。
     */
    private StreamLoop.TurnOutcome runTurn(AgentSession session, String prompt) {
        PrintMode.PrintResult result = printMode.runAgent(session, prompt);

        // 补齐费用/预算，并确保本轮以一个 result 事件收尾
        //（被中断或早期异常的轮次不会走 handleChunk，需要补发）
        printMode.finishTurn(result);

        sessionCostUsd += result.estimatedCostUsd;

        int exitCode = PrintMode.exitCodeOf(result);

        // 单轮就超预算：PrintResult 已判定，直接终止
        if (result.budgetExceeded) {
            return StreamLoop.TurnOutcome.terminal(exitCode);
        }

        // 会话累计超预算：单独发一个事件说明终止原因，不篡改本轮 result 的 total_cost_usd
        Double limit = options.getMaxBudgetUsd();
        if (limit != null && sessionCostUsd > limit) {
            printMode.emitStreamEvent(budgetExceededEvent(sessionCostUsd, limit));
            LOG.warn("Session budget exceeded: accumulated ${} > limit ${}", sessionCostUsd, limit);
            return StreamLoop.TurnOutcome.terminal(PrintMode.EXIT_BUDGET_EXCEEDED);
        }

        return StreamLoop.TurnOutcome.of(exitCode);
    }

    /**
     * <pre>{"type":"system","subtype":"budget_exceeded","session_cost_usd":1.2,"budget_limit_usd":1.0}</pre>
     */
    static ONode budgetExceededEvent(double sessionCostUsd, double limitUsd) {
        ONode node = new ONode();
        node.set("type", "system");
        node.set("subtype", "budget_exceeded");
        node.set("session_cost_usd", PrintMode.roundCost(sessionCostUsd));
        node.set("budget_limit_usd", limitUsd);
        return node;
    }
}

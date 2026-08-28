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
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.*;
import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.permission.PermissionBehavior;
import org.noear.solon.ai.harness.permission.PermissionRule;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Print / Headless 模式执行器
 *
 * <p>对齐 Claude Code 的 {@code claude -p} 无头模式：
 * 读取提示词（参数或 stdin），运行 Agent 到完成，
 * 按指定格式输出结构化结果，并以退出码标识成功/失败。</p>
 *
 * <p>三种输出格式：
 * <ul>
 *   <li>{@code text} — 纯文本，直接打印最终答复（默认）</li>
 *   <li>{@code json} — 单个 JSON 对象，包含 result/session_id/metrics/cost</li>
 *   <li>{@code stream-json} — 逐行 JSONL，每个事件一行（需 --verbose）</li>
 * </ul>
 * </p>
 *
 * <p>对齐的 Claude Code 功能：
 * <ul>
 *   <li>{@code --bare} — 跳过 skills/MCP/memory 自动发现</li>
 *   <li>{@code --add-dir} — 注册额外工作目录</li>
 *   <li>{@code --fallback-model} — 主模型不可用时回退</li>
 *   <li>{@code --json-schema} — 结构化输出约束</li>
 *   <li>{@code --max-budget-usd} — 费用硬上限</li>
 *   <li>{@code Bash(rm *)} — 工具规则语法细粒度控制</li>
 *   <li>{@code plan} 权限模式 — 实际限制写入类工具</li>
 *   <li>{@code acceptEdits} 权限模式 — 自动批准文件编辑</li>
 *   <li>stream-json 事件结构 — 对齐 Claude Code JSONL 格式</li>
 * </ul>
 * </p>
 *
 * @author noear
 */
public class PrintMode {
    private static final Logger LOG = LoggerFactory.getLogger(PrintMode.class);

    /** 退出码：成功 */
    public static final int EXIT_SUCCESS = 0;
    /** 退出码：运行出错 */
    public static final int EXIT_ERROR = 1;
    /** 退出码：超过最大轮次 */
    public static final int EXIT_MAX_TURNS = 2;
    /** 退出码：提示词为空 */
    public static final int EXIT_NO_PROMPT = 3;
    /** 退出码：超过费用预算 */
    public static final int EXIT_BUDGET_EXCEEDED = 4;
    /** 退出码：收到 SIGTERM（= 128 + 15，与 Unix 惯例一致） */
    public static final int EXIT_SIGTERM = 143;

    /** 写入类工具集合（plan 模式下会被 DENY） */
    private static final String[] WRITE_TOOLS = {"Write", "Edit", "Bash"};

    /** 文件编辑类工具集合（acceptEdits 模式下会被 ALLOW） */
    private static final String[] EDIT_TOOLS = {"Write", "Edit", "Read", "Glob", "Grep"};

    /** 默认费用估算：每 1K 输入 token $0.003 */
    static final double COST_PER_1K_INPUT_TOKENS = 0.003;
    /** 默认费用估算：每 1K 输出 token $0.015 */
    static final double COST_PER_1K_OUTPUT_TOKENS = 0.015;

    private final HarnessEngine engine;
    private final AgentSettings agentSettings;
    private final PrintModeOptions options;
    private final PrintStream out;

    public PrintMode(HarnessEngine engine, AgentSettings agentSettings, PrintModeOptions options) {
        this.engine = engine;
        this.agentSettings = agentSettings;
        this.options = options;
        this.out = System.out;
    }

    /**
     * 执行 Print 模式任务，返回退出码。
     *
     * @return 退出码（0=成功, 非0=失败）
     */
    public int execute() {
        // 1. 确定提示词
        String prompt = resolvePrompt();
        if (Assert.isEmpty(prompt)) {
            System.err.println("Error: No prompt provided. Pass a prompt as argument or pipe via stdin.");
            return EXIT_NO_PROMPT;
        }

        // 2. 应用运行时选项（maxTurns, model, tools, permission-mode, bare, add-dir, tool-rules）
        applyOptions();

        // 3. 确定会话
        AgentSession session = resolveSession();

        // 4. 发射 init 事件（stream-json 模式）
        if (options.getOutputFormat() == PrintModeOptions.OutputFormat.STREAM_JSON) {
            emitStreamEvent(buildInitEvent(session));
        }

        // 5. 执行 Agent 任务
        PrintResult result = runAgent(session, prompt);

        // 6. 计算费用估算
        result.estimatedCostUsd = estimateCostUsd(result.metrics);
        result.budgetLimitUsd = options.getMaxBudgetUsd();
        result.budgetExceeded = result.budgetLimitUsd != null && result.estimatedCostUsd > result.budgetLimitUsd;

        // 7. 输出结果
        outputResult(session, result);

        // 8. 返回退出码
        if (result.budgetExceeded) {
            LOG.warn("Budget exceeded: estimated ${} > limit ${}", result.estimatedCostUsd, result.budgetLimitUsd);
            return EXIT_BUDGET_EXCEEDED;
        }
        if (result.error != null) {
            return EXIT_ERROR;
        }
        if (result.maxTurnsExceeded) {
            return EXIT_MAX_TURNS;
        }
        return EXIT_SUCCESS;
    }

    /**
     * 解析提示词：优先用参数中的 prompt，否则从 stdin 读取。
     * 如果设置了 --json-schema，追加结构化输出约束指令。
     */
    private String resolvePrompt() {
        String prompt = null;

        if (Assert.isNotEmpty(options.getPrompt())) {
            prompt = options.getPrompt();
        } else {
            // 从 stdin 读取。
            // 注意：不能只依赖 available() > 0——上游（如 SDK/脚本）刚 fork 完还没来得及写入时
            // available() 为 0，会被误判成“无提示词”而退出码 3。
            // 因此：stdin 被重定向（无控制台，即管道/文件）时直接阻塞读到 EOF。
            try {
                if (System.in.available() > 0 || isStdinRedirected()) {
                    byte[] bytes = readAllStdin();
                    if (bytes != null && bytes.length > 0) {
                        String stdinPrompt = new String(bytes, StandardCharsets.UTF_8).trim();
                        if (Assert.isNotEmpty(stdinPrompt)) {
                            options.setPrompt(stdinPrompt, true);
                            prompt = stdinPrompt;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("Failed to read stdin: {}", e.getMessage());
            }
        }

        if (Assert.isEmpty(prompt)) {
            return null;
        }

        // 追加 JSON Schema 约束
        if (Assert.isNotEmpty(options.getJsonSchema())) {
            prompt = prompt + "\n\n---\nYour response must be valid JSON conforming to this JSON Schema:\n"
                    + options.getJsonSchema()
                    + "\nReturn only the JSON value matching the schema, with no additional text or explanation.";
        }

        return prompt;
    }

    /** stdin 输入上限：10MB（对齐官方 v2.1.128+ 规范） */
    private static final int STDIN_MAX_BYTES = 10 * 1024 * 1024;

    /**
     * stdin 是否被重定向（管道 / 文件 / 父进程 stdio）。
     *
     * <p>交互式终端下 {@code System.console()} 非空；被重定向时为空，
     * 此时可安全阻塞读取——写端关闭即 EOF。</p>
     */
    private boolean isStdinRedirected() {
        return System.console() == null;
    }

    private byte[] readAllStdin() {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            int totalRead = 0;
            while ((len = System.in.read(buffer)) != -1) {
                totalRead += len;
                if (totalRead > STDIN_MAX_BYTES) {
                    System.err.println("Error: stdin input exceeds 10MB limit.");
                    return null;
                }
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 应用运行时选项到引擎
     */
    private void applyOptions() {
        // ---- bare 模式：跳过 skills/MCP/memory 自动发现 ----
        if (options.isBare()) {
            applyBareMode();
        }

        // ---- add-dir：注册额外工作目录 ----
        if (!options.getAddDirs().isEmpty()) {
            applyAddDirs();
        }

        // ---- max-turns ----
        if (options.getMaxTurns() != null) {
            engine.setMaxTurns(options.getMaxTurns());
        }

        // ---- allowedTools / disallowedTools（纯工具名）----
        if (!options.getAllowedTools().isEmpty() || !options.getDisallowedTools().isEmpty()) {
            engine.toolPermissionReset(
                    options.getAllowedTools().isEmpty() ? engine.getTools() : options.getAllowedTools(),
                    options.getDisallowedTools()
            );
        }

        // ---- 工具规则（带 glob 模式，如 Bash(rm *)）----
        applyToolRules();

        // ---- permission-mode ----
        applyPermissionMode();
    }

    /**
     * bare 模式：移除 skills/agents 挂载、MCP 服务、禁用 memory
     */
    private void applyBareMode() {
        // 移除 SKILLS 和 AGENTS 类型的挂载
        for (MountDir mount : new ArrayList<>(engine.getMounts())) {
            if (mount.getType() == MountType.SKILLS || mount.getType() == MountType.AGENTS) {
                engine.removeMount(mount.getAlias());
                LOG.debug("Bare mode: removed mount {}", mount.getAlias());
            }
        }

        // 移除所有 MCP 服务
        for (String name : new ArrayList<>(engine.getMcpServers().keySet())) {
            engine.removeMcpServer(name);
            LOG.debug("Bare mode: removed MCP server {}", name);
        }

        // 禁用 memory
        engine.setMemoryEnabled(false);
        LOG.debug("Bare mode: disabled memory");
    }

    /**
     * 注册额外工作目录
     */
    private void applyAddDirs() {
        int idx = 0;
        for (String dir : options.getAddDirs()) {
            String alias = "@add-dir-" + idx++;
            try {
                MountDir mount = MountDir.builder()
                        .alias(alias)
                        .type(MountType.FILES)
                        .path(dir)
                        .writeable(true)
                        .enabled(true)
                        .build();
                engine.addMount(mount);
                LOG.debug("Added extra directory: {} -> {}", alias, dir);
            } catch (Exception e) {
                LOG.warn("Failed to add directory '{}': {}", dir, e.getMessage());
            }
        }
    }

    /**
     * 应用工具规则（带 glob 模式的 allowedTools / disallowedTools）
     */
    private void applyToolRules() {
        for (PrintModeOptions.ToolRuleSpec rule : options.getAllowedToolRules()) {
            PermissionRule pr = PermissionRule.allow(rule.getToolName(), rule.getPattern());
            engine.addPermissionRule(pr);
            LOG.debug("Added allow rule: {}({})", rule.getToolName(), rule.getPattern());
        }
        for (PrintModeOptions.ToolRuleSpec rule : options.getDisallowedToolRules()) {
            PermissionRule pr = PermissionRule.deny(rule.getToolName(), rule.getPattern());
            engine.addPermissionRule(pr);
            LOG.debug("Added deny rule: {}({})", rule.getToolName(), rule.getPattern());
        }
    }

    /**
     * 应用权限模式
     */
    private void applyPermissionMode() {
        switch (options.getPermissionMode()) {
            case DONT_ASK:
                // 非交互模式：禁用 HITL，未授权操作自动拒绝
                engine.setHitlEnabled(false);
                break;
            case PLAN:
                // 仅分析模式：禁用 HITL + DENY 所有写入类工具
                engine.setHitlEnabled(false);
                for (String tool : WRITE_TOOLS) {
                    engine.addPermissionRule(new PermissionRule(tool, PermissionBehavior.DENY, null, 100));
                }
                LOG.debug("Plan mode: denied write tools: {}", Arrays.toString(WRITE_TOOLS));
                break;
            case BYPASS_PERMISSIONS:
                // 跳过所有权限检查
                engine.setHitlEnabled(false);
                engine.setSandboxEnabled(false);
                break;
            case ACCEPT_EDITS:
                // 接受编辑：自动批准文件编辑工具，其它操作自动拒绝
                engine.setHitlEnabled(false);
                for (String tool : EDIT_TOOLS) {
                    engine.addPermissionRule(new PermissionRule(tool, PermissionBehavior.ALLOW, null, 100));
                }
                LOG.debug("AcceptEdits mode: auto-approved edit tools: {}", Arrays.toString(EDIT_TOOLS));
                break;
            case DEFAULT:
            default:
                // 非交互模式默认禁用 HITL（无人值守时不能等待人工批准）
                engine.setHitlEnabled(false);
                break;
        }
    }

    /**
     * 确定或创建会话
     */
    private AgentSession resolveSession() {
        String sessionId;

        if (Assert.isNotEmpty(options.getResumeSessionId())) {
            sessionId = options.getResumeSessionId();
        } else if (Assert.isNotEmpty(options.getSessionId())) {
            sessionId = options.getSessionId();
        } else if (options.isContinueSession()) {
            sessionId = "cli";
        } else {
            sessionId = "print-" + UUID.randomUUID().toString().substring(0, 8);
        }

        return engine.getSession(sessionId);
    }

    /**
     * 运行 Agent 任务并收集结果
     */
    private PrintResult runAgent(AgentSession session, String prompt) {
        PrintResult result = new PrintResult();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicReference<Disposable> disposableRef = new AtomicReference<>();

        String modelSelected = options.getModel();
        if (modelSelected == null) {
            modelSelected = session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);
        }

        // ---- fallback-model：主模型不可用时回退 ----
        if (Assert.isNotEmpty(modelSelected) && !engine.hasModel(modelSelected)) {
            if (Assert.isNotEmpty(options.getFallbackModel())) {
                LOG.warn("Model '{}' not found, falling back to '{}'", modelSelected, options.getFallbackModel());
                modelSelected = options.getFallbackModel();
            }
        }

        ChatModel chatModel = engine.getModelOrDefInstance(modelSelected);
        ReActAgent agent = engine.getMainAgent();

        try {
            disposableRef.set(
                    agent.prompt(Prompt.of(prompt))
                            .session(session)
                            .options(o -> {
                                o.chatModel(chatModel);
                                if (options.getMaxTurns() != null) {
                                    o.maxTurns(options.getMaxTurns());
                                }
                            })
                            .stream()
                            .subscribeOn(Schedulers.boundedElastic())
                            .doOnNext(chunk -> {
                                handleChunk(session, chunk, result);
                            })
                            .doOnError(e -> {
                                errorRef.set(e);
                                LOG.error("Print mode task failed: {}", e.getMessage(), e);
                            })
                            .doFinally(signal -> {
                                latch.countDown();
                            })
                            .subscribe()
            );

            latch.await();

            result.error = errorRef.get();

            // 检查是否超过最大轮次
            if (result.trace != null && result.trace.isAbnormal()) {
                result.maxTurnsExceeded = true;
            }

        } catch (Exception e) {
            result.error = e;
            LOG.error("Print mode execution error: {}", e.getMessage(), e);
        }

        return result;
    }

    /**
     * 处理流式事件块
     */
    private void handleChunk(AgentSession session, Object chunk, PrintResult result) {
        if (chunk instanceof ReasonChunk) {
            ReasonChunk reason = (ReasonChunk) chunk;
            if (!reason.isToolCalls() && reason.hasContent()) {
                if (options.getOutputFormat() == PrintModeOptions.OutputFormat.STREAM_JSON && options.isVerbose()) {
                    String text = clearThink(reason.getContent());
                    if (Assert.isNotEmpty(text)) {
                        emitStreamEvent(buildAssistantTextEvent(text, reason.isThinking()));
                    }
                }
            }
        } else if (chunk instanceof ThoughtChunk) {
            ThoughtChunk thought = (ThoughtChunk) chunk;
            if (options.getOutputFormat() == PrintModeOptions.OutputFormat.STREAM_JSON && options.isVerbose()) {
                if (thought.isToolCalls()) {
                    emitStreamEvent(buildToolUseEvent(thought));
                }
            }
        } else if (chunk instanceof ObservationChunk) {
            ObservationChunk obs = (ObservationChunk) chunk;
            if (options.getOutputFormat() == PrintModeOptions.OutputFormat.STREAM_JSON && options.isVerbose()) {
                emitStreamEvent(buildToolResultEvent(obs));
            }
        } else if (chunk instanceof ReActChunk) {
            ReActChunk react = (ReActChunk) chunk;
            result.trace = react.getTrace();
            result.answer = clearThink(react.getContent());
            result.metrics = react.getMetrics();
            result.sessionId = session.getSessionId();

            if (options.getOutputFormat() == PrintModeOptions.OutputFormat.STREAM_JSON) {
                // result 事件在流中发出时需要费用数据，提前计算（execute() 步骤6 会再次赋值，幂等）
                result.estimatedCostUsd = estimateCostUsd(result.metrics);
                result.budgetLimitUsd = options.getMaxBudgetUsd();
                result.budgetExceeded = result.budgetLimitUsd != null
                        && result.estimatedCostUsd > result.budgetLimitUsd;
                emitStreamEvent(buildResultEvent(result));
                // 标记 result 事件已发出，outputResult 阶段将不再额外发 error 事件
                result.resultEventEmitted = true;
            }
        }
    }

    /**
     * 输出最终结果
     */
    private void outputResult(AgentSession session, PrintResult result) {
        switch (options.getOutputFormat()) {
            case JSON:
                outputJson(result);
                break;
            case STREAM_JSON:
                // 仅当 result 事件尚未发出时才发 error 事件，
                // 避免 result 作为终止符之后再出现额外事件（对齐 Claude Code 协议）
                if (result.error != null && !result.resultEventEmitted) {
                    emitStreamEvent(buildErrorEvent(result.error));
                }
                break;
            case TEXT:
            default:
                outputText(result);
                break;
        }
    }

    /**
     * 纯文本输出
     */
    private void outputText(PrintResult result) {
        if (result.error != null) {
            System.err.println("Error: " + result.error.getMessage());
            return;
        }
        if (Assert.isNotEmpty(result.answer)) {
            out.println(result.answer);
        }
    }

    /**
     * JSON 输出（单个 JSON 对象，对齐 Claude Code 格式）
     */
    private void outputJson(PrintResult result) {
        ONode root = new ONode();

        if (result.error != null) {
            root.set("result", "");
            root.set("is_error", true);
            root.set("error", result.error.getMessage());
        } else {
            root.set("result", result.answer != null ? result.answer : "");
            root.set("is_error", false);
        }

        root.set("session_id", result.sessionId != null ? result.sessionId : "");

        // metrics
        if (result.metrics != null) {
            ONode metrics = root.getOrNew("metrics");
            metrics.set("total_tokens", result.metrics.getTotalTokens());
            metrics.set("prompt_tokens", result.metrics.getPromptTokens());
            metrics.set("completion_tokens", result.metrics.getCompletionTokens());
            metrics.set("duration_ms", result.metrics.getTotalDuration());
        }

        // 费用估算
        root.set("total_cost_usd", roundCost(result.estimatedCostUsd));

        // 预算信息
        if (result.budgetLimitUsd != null) {
            root.set("budget_limit_usd", result.budgetLimitUsd);
            root.set("budget_exceeded", result.budgetExceeded);
        }

        // JSON Schema 结构化输出
        if (Assert.isNotEmpty(options.getJsonSchema()) && Assert.isNotEmpty(result.answer)) {
            ONode structured = tryParseJson(result.answer);
            if (structured != null) {
                root.set("structured_output", structured);
            }
        }

        if (result.maxTurnsExceeded) {
            root.set("max_turns_exceeded", true);
        }

        out.println(root.toJson());
    }

    // ========== Stream-JSON 事件构建（对齐 Claude Code JSONL 格式） ==========

    private void emitStreamEvent(ONode event) {
        out.println(event.toJson());
        out.flush();
    }

    /**
     * init 事件：对齐 Claude Code 格式
     * <pre>
     * {"type":"system","subtype":"init","session_id":"...","model":"sonnet","tools":["Read","Grep"]}
     * </pre>
     */
    private ONode buildInitEvent(AgentSession session) {
        ONode node = new ONode();
        node.set("type", "system");
        node.set("subtype", "init");
        node.set("session_id", session.getSessionId());

        // model 作为字符串（对齐 Claude Code）
        String modelName = options.getModel();
        if (modelName == null) {
            modelName = session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);
        }
        if (modelName == null) {
            ChatModel mainModel = engine.getMainModel();
            if (mainModel != null) {
                modelName = mainModel.getNameOrModel();
            }
        }
        node.set("model", modelName != null ? modelName : "default");

        // 工具列表
        ONode toolsNode = node.getOrNew("tools").asArray();
        for (String tool : engine.getTools()) {
            toolsNode.add(tool);
        }

        // MCP 服务列表（对齐官方格式：[{name, status}] 对象数组）
        ONode mcpServersNode = node.getOrNew("mcp_servers").asArray();
        for (String name : engine.getMcpServers().keySet()) {
            ONode mcpEntry = new ONode();
            mcpEntry.set("name", name);
            mcpEntry.set("status", "connected");
            mcpServersNode.add(mcpEntry);
        }

        // MCP 服务错误列表（加载失败的 server，v2.1.219+；当前占位空数组，CI 可检测非空来 fail）
        node.getOrNew("mcp_server_errors").asArray();

        // 协议能力声明（v2.1.205+，消费方应忽略未知值）
        node.getOrNew("capabilities").asArray();

        node.set("version", AgentFlags.getVersion());
        return node;
    }

    /**
     * assistant 文本事件：对齐 Claude Code 格式
     * <ul>
     *   <li>普通文本：{"type":"text","text":"..."}</li>
     *   <li>thinking 块：{"type":"thinking","thinking":"..."}（字段名与 Anthropic API 一致）</li>
     * </ul>
     */
    private ONode buildAssistantTextEvent(String text, boolean isThinking) {
        ONode node = new ONode();
        node.set("type", "assistant");

        ONode message = node.getOrNew("message");
        ONode contentArray = message.getOrNew("content").asArray();

        ONode contentBlock = new ONode();
        if (isThinking) {
            // thinking 块：字段名为 "thinking"，对齐 Anthropic API 规范
            contentBlock.set("type", "thinking");
            contentBlock.set("thinking", text);
        } else {
            contentBlock.set("type", "text");
            contentBlock.set("text", text);
        }
        contentArray.add(contentBlock);

        return node;
    }

    /**
     * assistant 工具调用事件：对齐 Claude Code 格式
     * <pre>
     * {"type":"assistant","message":{"content":[{"type":"tool_use","id":"...","name":"...","input":{...}}]}}
     * </pre>
     */
    private ONode buildToolUseEvent(ThoughtChunk thought) {
        ONode node = new ONode();
        node.set("type", "assistant");

        ONode message = node.getOrNew("message");
        ONode contentArray = message.getOrNew("content").asArray();

        if (Assert.isNotEmpty(thought.getToolCalls())) {
            thought.getToolCalls().forEach(tc -> {
                ONode contentBlock = new ONode();
                contentBlock.set("type", "tool_use");
                contentBlock.set("id", tc.getId());
                contentBlock.set("name", tc.getName());
                if (tc.getArguments() != null) {
                    contentBlock.set("input", tc.getArguments());
                }
                contentArray.add(contentBlock);
            });
        }

        return node;
    }

    /**
     * tool_result 事件：对齐 Claude Code 格式（包裹在 user 类型中）
     * <pre>
     * {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"...","content":"...","is_error":false}]}}
     * </pre>
     */
    private ONode buildToolResultEvent(ObservationChunk obs) {
        ONode node = new ONode();
        node.set("type", "user");

        ONode message = node.getOrNew("message");
        ONode contentArray = message.getOrNew("content").asArray();

        ONode contentBlock = new ONode();
        contentBlock.set("type", "tool_result");
        contentBlock.set("tool_use_id", obs.getCallId());
        contentBlock.set("is_error", obs.getError() != null);
        if (obs.getError() != null) {
            contentBlock.set("content", obs.getError().getMessage());
        } else if (Assert.isNotEmpty(obs.getContent())) {
            contentBlock.set("content", obs.getContent());
        } else {
            contentBlock.set("content", "");
        }
        contentArray.add(contentBlock);

        return node;
    }

    /**
     * result 事件：对齐 Claude Code 格式
     * <pre>
     * {"type":"result","result":"...","session_id":"...","is_error":false,"total_cost_usd":0.01}
     * </pre>
     */
    private ONode buildResultEvent(PrintResult result) {
        ONode node = new ONode();
        node.set("type", "result");
        node.set("result", result.answer != null ? result.answer : "");
        node.set("session_id", result.sessionId != null ? result.sessionId : "");
        node.set("is_error", result.error != null);
        if (result.error != null) {
            node.set("error", result.error.getMessage());
        }
        if (result.metrics != null) {
            ONode metrics = node.getOrNew("metrics");
            metrics.set("total_tokens", result.metrics.getTotalTokens());
            metrics.set("prompt_tokens", result.metrics.getPromptTokens());
            metrics.set("completion_tokens", result.metrics.getCompletionTokens());
            metrics.set("duration_ms", result.metrics.getTotalDuration());
        }
        node.set("total_cost_usd", roundCost(result.estimatedCostUsd));
        if (result.budgetLimitUsd != null) {
            node.set("budget_limit_usd", result.budgetLimitUsd);
            node.set("budget_exceeded", result.budgetExceeded);
        }
        if (Assert.isNotEmpty(options.getJsonSchema()) && Assert.isNotEmpty(result.answer)) {
            ONode structured = tryParseJson(result.answer);
            if (structured != null) {
                node.set("structured_output", structured);
            }
        }
        if (result.maxTurnsExceeded) {
            node.set("max_turns_exceeded", true);
        }
        return node;
    }

    /**
     * error 事件：对齐 Claude Code 格式
     * <pre>
     * {"type":"error","message":"...","code":"ERR_UNKNOWN"}
     * </pre>
     */
    private ONode buildErrorEvent(Throwable error) {
        ONode node = new ONode();
        node.set("type", "error");
        node.set("message", error.getMessage() != null ? error.getMessage() : "Unknown error");
        node.set("code", deriveErrorCode(error));
        return node;
    }

    /**
     * 从异常类型推导错误码（对齐 Claude Code 错误码规范）
     */
    private static String deriveErrorCode(Throwable error) {
        if (error == null) return "ERR_UNKNOWN";
        if (error instanceof java.io.IOException) return "ERR_IO";
        if (error instanceof InterruptedException) return "ERR_INTERRUPTED";
        if (error instanceof java.util.concurrent.TimeoutException) return "ERR_TIMEOUT";
        if (error instanceof IllegalArgumentException) return "ERR_INVALID_ARG";
        if (error instanceof IllegalStateException) return "ERR_INVALID_STATE";
        return "ERR_UNKNOWN";
    }


    // ========== 费用估算 ==========

    /**
     * 基于 metrics 估算费用（美元）
     */
    static double estimateCostUsd(Metrics metrics) {
        if (metrics == null) return 0.0;
        double inputCost = (metrics.getPromptTokens() / 1000.0) * COST_PER_1K_INPUT_TOKENS;
        double outputCost = (metrics.getCompletionTokens() / 1000.0) * COST_PER_1K_OUTPUT_TOKENS;
        return inputCost + outputCost;
    }

    /**
     * 费用四舍五入到 6 位小数
     */
    static double roundCost(double cost) {
        return Math.round(cost * 1000000.0) / 1000000.0;
    }

    // ========== 工具方法 ==========

    static String clearThink(String chunk) {
        if (chunk == null) {
            return null;
        }
        return chunk.replaceAll("(?s)<\\s*/?think\\s*>", "");
    }

    /**
     * 尝试将字符串解析为 JSON，失败返回 null
     */
    private ONode tryParseJson(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            return ONode.ofJson(text.trim());
        } catch (Exception e) {
            // 尝试提取 JSON 块（```json ... ``` 或裸 JSON 对象/数组）
            String extracted = extractJsonBlock(text);
            if (extracted != null) {
                try {
                return ONode.ofJson(extracted);
                } catch (Exception e2) {
                    return null;
                }
            }
            return null;
        }
    }

    /**
     * 从文本中提取 JSON 代码块
     */
    static String extractJsonBlock(String text) {
        if (text == null) return null;
        // 尝试 ```json ... ``` 格式
        int start = text.indexOf("```json");
        if (start >= 0) {
            int contentStart = start + 7;
            int end = text.indexOf("```", contentStart);
            if (end > contentStart) {
                return text.substring(contentStart, end).trim();
            }
        }
        // 尝试 ``` ... ``` 格式
        start = text.indexOf("```");
        if (start >= 0) {
            int contentStart = start + 3;
            // 跳过可能的语言标识行
            int lineEnd = text.indexOf('\n', contentStart);
            if (lineEnd > contentStart) {
                String firstLine = text.substring(contentStart, lineEnd).trim();
                if (firstLine.isEmpty() || firstLine.equals("json")) {
                    contentStart = lineEnd + 1;
                }
            }
            int end = text.indexOf("```", contentStart);
            if (end > contentStart) {
                return text.substring(contentStart, end).trim();
            }
        }
        // 尝试提取第一个 { ... } 或 [ ... ] 块
        int braceStart = text.indexOf('{');
        int bracketStart = text.indexOf('[');
        int jsonStart = -1;
        char openChar = 0;
        char closeChar = 0;
        if (braceStart >= 0 && (bracketStart < 0 || braceStart < bracketStart)) {
            jsonStart = braceStart;
            openChar = '{';
            closeChar = '}';
        } else if (bracketStart >= 0) {
            jsonStart = bracketStart;
            openChar = '[';
            closeChar = ']';
        }
        if (jsonStart >= 0) {
            int depth = 0;
            for (int i = jsonStart; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == openChar) depth++;
                else if (c == closeChar) {
                    depth--;
                    if (depth == 0) {
                        return text.substring(jsonStart, i + 1);
                    }
                }
            }
        }
        return null;
    }

    // ========== 结果类 ==========

    /**
     * Print 模式执行结果
     */
    private static class PrintResult {
        String answer;
        String sessionId;
        ReActTrace trace;
        Metrics metrics;
        Throwable error;
        boolean maxTurnsExceeded;
        double estimatedCostUsd;
        Double budgetLimitUsd;
        boolean budgetExceeded;
        /** result 事件是否已在流中发出；为 true 时 outputResult 不再补发 error 事件 */
        boolean resultEventEmitted;
    }
}

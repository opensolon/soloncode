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
package org.noear.solon.codecli.portal.web;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.ContextSizeChunk;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.*;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.talents.cli.TodoTalent;
import org.noear.solon.ai.talents.memory.MemoryTalent;
import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.channel.wechat.WeChatLink;
import org.noear.solon.codecli.command.builtin.GoalTalent;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

/**
 * Web 流式响应构建器
 *
 * <p><b>职责说明：</b>将 ReAct Agent 的流式输出（chunk）逐条映射为 {@link WebChunk}，
 * 构建可在 Web 端消费的响应式数据流（{@link reactor.core.publisher.Flux}）。</p>
 *
 * <p><b>核心机制：</b>
 * <ul>
 *   <li>基于 ReAct 流式 chunk 类型分发：ReasonDeltaChunk → 思维链/文本输出；
 *       ReasonCompleteChunk → 思考轮次输出 + IM 通道同步转发；
 *       ActionEndChunk → 工具调用结果；
 *       ReActChunk → 最终汇总（含异常）。</li>
 *   <li>IM 通道同步转发：在处理 ReasonCompleteChunk 和 FinalChunk 时，将内容同步推送到
 *       所有已绑定的 IM 通道（微信、飞书、钉钉等），实现 Web 端与 IM 端双路输出。</li>
 *   <li>HITL（人机交互循环）支持：流结束后自动检测挂起的人工审批任务，
 *       如有则生成对应的 HITL WebChunk 以暂停流等待人工确认。</li>
 * </ul></p>
 *
 * <p><b>架构位置：</b>位于 portal/web 层，是 Agent 后端与 Web 前端之间的流式适配器；
 * 上游对接 {@link org.noear.solon.ai.agent.react.ReActAgent} 的 stream 输出，
 * 下游输出面向 Web SSE / WebSocket 的 {@link WebChunk} 序列。</p>
 *
 * @author noear 2026/4/23 created
 */
public class WebStreamBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(WebStreamBuilder.class);

    /**
     * 任务执行引擎，用于判断当前引擎名称与 chunk 中代理名称的归属关系
     */
    private final HarnessEngine engine;

    /**
     * IM 通道路由表：所有注册的 IM 通道（微信、飞书、钉钉等）
     */
    private final List<Channel> imLinks = new ArrayList<>();

    /**
     * 注册 IM 通道（向后兼容：支持 WeChatLink 直接注册）
     */
    public WebStreamBuilder bind(WeChatLink weChatLink) {
        this.imLinks.add(weChatLink);
        return this;
    }

    /**
     * 注册 IM 通道（通用接口）
     */
    public WebStreamBuilder bind(Channel link) {
        this.imLinks.add(link);
        return this;
    }

    /**
     * 获取微信通道（向后兼容）
     */
    public WeChatLink getWeChatLink() {
        for (Channel link : imLinks) {
            if (link instanceof WeChatLink) {
                return (WeChatLink) link;
            }
        }
        return null;
    }


    /**
     * 构造函数
     *
     * @param engine 任务执行引擎实例，用于后续判断 chunk 所属的引擎/代理层级
     */
    public WebStreamBuilder(HarnessEngine engine) {
        this.engine = engine;
    }

    /**
     * 构建流式响应管线
     *
     * <p>核心流程：
     * <ol>
     *   <li>处理 prompt（null兜底、/resume重置）并记录当前选择的 Agent</li>
     *   <li>调用 {@link ReActAgent#stream()} 获取 ReAct 流式输出</li>
     *   <li>按 chunk 类型分发到对应的处理方法（onReasonDeltaChunk / onReasonCompleteChunk / onActionEndChunk / onFinalChunk）</li>
     *   <li>过滤空 chunk、捕获异常并生成错误 WebChunk</li>
     *   <li>流结束后检测 HITL 状态，如有挂起的人工审批任务则追加 HITL WebChunk</li>
     * </ol></p>
     *
     * @param session    Agent 会话，承载会话状态、属性及 HITL 上下文
     * @param agent      ReAct Agent 实例，提供流式推理能力
     * @param chatModel  聊天模型，用于配置 Agent 的底层模型调用
     * @param sessionCwd 当前会话的工作目录，作为工具上下文注入
     * @param prompt     用户提示词；为 null 时使用空提示，为 "/resume" 时重置为空提示
     * @return 映射后的 {@link WebChunk} 响应式流
     */
    public Flux<WebChunk> buildStreamFlux(AgentSession session, ReActAgent agent, ChatModel chatModel, String sessionCwd, Prompt prompt) {
        if (prompt == null) {
            prompt = Prompt.of();
        }

        if ("/resume".equals(prompt.getUserContent())) {
            prompt = Prompt.of();
        }

        //记录最新的选择
        session.attrs().put("_agent_selected_tmp", agent.name());

        return agent.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.chatModel(chatModel);

                    if (Assert.isNotEmpty(sessionCwd)) {
                        o.toolContextPut(HarnessEngine.ATTR_CWD, sessionCwd);
                    }
                })
                .stream()
                .map(chunk -> {
                    WebChunk webChunk = null;
                    if (chunk instanceof ContextSizeChunk) {
                        webChunk = onContextSizeChunk(chatModel, (ContextSizeChunk) chunk);
                    } else if (chunk instanceof ReasonChunk) {
                        webChunk = onReasonChunk((ReasonChunk) chunk);
                    } else if (chunk instanceof ThoughtChunk) {
                        webChunk = onThoughtChunk(session, (ThoughtChunk) chunk);
                    } else if (chunk instanceof ActionChunk) {
                        webChunk = onActionStartChunk((ActionChunk) chunk);
                    } else if (chunk instanceof ObservationChunk) {
                        webChunk = onObservationChunk((ObservationChunk) chunk);
                    } else if (chunk instanceof ReActChunk) {
                        webChunk = onFinalChunk(session, (ReActChunk) chunk);
                    }

                    if(webChunk == null || webChunk == WebChunk.EMPTY) {
                        return WebChunk.EMPTY;
                    } else {
                        webChunk.setRunId(chunk.getRunId());
                        return webChunk;
                    }
                })
                .filter(WebChunk::isNotEmpty)
                .onErrorResume(e -> {
                    LOG.error("Task fail: {}", e.getMessage(), e);

                    return Mono.just(WebChunk.ofError(e));
                })
                .concatWith(Flux.defer(() -> {
                    // Check HITL state after stream completes
                    if (HITL.isHitl(session)) {
                        HITLTask task = HITL.getPendingTask(session);
                        if (task != null) {
                            String command = "bash".equals(task.getToolName())
                                    ? String.valueOf(task.getArgs().get("command"))
                                    : null;

                            WebChunk hitlChuck = WebChunk.ofHitl(task.getToolName(), command);

                            return Flux.just(hitlChuck, WebChunk.ofDone());
                        }
                    }

                    return Flux.just(WebChunk.ofDone());
                }));
    }


    public WebChunk onContextSizeChunk(ChatModel chatModel, ContextSizeChunk chunk){
        WebChunk wc = new WebChunk();
        wc.setType("context_size");
        wc.setSessionId(chunk.getSession().getSessionId());
        wc.setTotalTokens((long) chunk.getTokenCount());
        wc.setText(String.valueOf(chunk.getMessageCount()));

        long contextLength = chatModel.getConfig().getContextLength();
        if(contextLength == 0){
            contextLength = 128_000; //默认
        }

        Map<String, Object> args = new HashMap<>();
        args.put("contextLength", contextLength);

        if (chunk.isCompressed()) {
            args.put("compressed", true);
            args.put("beforeTokenCount", chunk.getBeforeTokenCount());
            args.put("afterTokenCount", chunk.getAfterTokenCount());
            args.put("beforeMessageCount", chunk.getBeforeMessageCount());
            args.put("afterMessageCount", chunk.getAfterMessageCount());
        }
        wc.setArgs(args);
        wc.setCreatedAt(java.time.Instant.now().toEpochMilli());
        return wc;
    }

    /**
     * 处理推理阶段的 chunk
     *
     * <p>在非工具调用且存在内容时，根据消息是否处于 thinking 状态分别映射为：
     * <ul>
     *   <li>thinking 状态 → {@link WebChunk#ofReason(String)} 思维链输出（供前端折叠展示推理过程）</li>
     *   <li>非 thinking → {@link WebChunk#ofText(String)} 常规文本输出</li>
     * </ul>
     * 否则返回空 chunk。</p>
     *
     * @param chunk 推理阶段的 chunk 数据
     * @return 映射后的 WebChunk，或 {@link WebChunk#EMPTY}
     */
    private WebChunk onReasonChunk(ReasonChunk chunk) {
        if (!chunk.isToolCalls() && chunk.hasContent()) {
            if (chunk.getMessage().isThinking()) {
                return WebChunk.ofReason(chunk.getContent());
            } else {
                return WebChunk.ofText(chunk.getContent());
            }
        }

        return WebChunk.EMPTY;
    }


    /**
     * 处理工具调用开始阶段的 chunk（来源引擎 ActionChunk）
     *
     * <p>在工具实际执行前发送 action_start，让前端提前渲染 loading 状态的工具卡片骨架，
     * 待后续 {@link #onObservationChunk} 的结果到达时复用同一卡片填充并转完成态。
     * 过滤规则与 {@link #onObservationChunk} 保持一致，避免建卡后无对应结果填充。</p>
     *
     * @param chunk 工具调用开始的 chunk 数据
     * @return 映射后的 WebChunk（含工具名与参数），或 {@link WebChunk#EMPTY}（内部工具或无名称时）
     */
    private WebChunk onActionStartChunk(ActionChunk chunk) {
        if (Assert.isEmpty(chunk.getToolName())) {
            return WebChunk.EMPTY;
        }

        if (TaskTalent.TOOL_MULTITASK.equals(chunk.getToolName()) ||
                TaskTalent.TOOL_TASK.equals(chunk.getToolName()) ||
                MemoryTalent.isMemoryTool(chunk.getToolName()) ||
                GoalTalent.isGoalTool(chunk.getToolName())) {
            return WebChunk.EMPTY;
        }

        // todowrite 的展示走专用通道，由 ObservationChunk 携带完整 todos 渲染，开始阶段不提前建卡
        if (TodoTalent.TOOL_TODOWRITE.equals(chunk.getToolName())) {
            return WebChunk.EMPTY;
        }

        // toolName 恒为裸名（供前端识别/查表）；toolTitle 为显示名（子代理时加 agentName 前缀）
        String toolName = chunk.getToolName();
        String toolTitle;
        if (engine.getName().equals(chunk.getAgentName())) {
            toolTitle = toolName;
        } else {
            toolTitle = chunk.getAgentName() + "/" + toolName;
        }

        Map<String, Object> args = chunk.getArgs() != null
                ? new LinkedHashMap<>(chunk.getArgs())
                : null;

        // edit 开始阶段即重建 diff，让 loading 骨架卡也能预览改动
        fillEditDiff(args);

        return WebChunk.ofActionStart(toolName, toolTitle, args);
    }


    /**
     * 处理工具调用完成阶段的 chunk
     *
     * <p>过滤掉内部工具（多任务调度 task/multitask、记忆工具）后，
     * 将工具调用结果包装为 {@link WebChunk}，并附带工具名称和参数信息：
     * <ul>
     *   <li>工具名称：若属于当前引擎则使用短名，否则使用 {@code agentName/toolName} 全路径</li>
     *   <li>特殊处理 {@code todowrite} 工具：将 todos 参数内容设为文本</li>
     * </ul></p>
     *
     * @param chunk 工具调用结束的 chunk 数据
     * @return 映射后的 WebChunk（含工具信息），或 {@link WebChunk#EMPTY}（内部工具或无名称时）
     */
    private WebChunk onObservationChunk(ObservationChunk chunk) {
        if(chunk.getError() != null){
            return WebChunk.EMPTY;
        }

        // todowrite 完成时，前端通过 action chunk 的 toolName='todowrite' 自动刷新任务面板

        if (Assert.isNotEmpty(chunk.getToolName())) {
            if (TaskTalent.TOOL_MULTITASK.equals(chunk.getToolName()) ||
                    TaskTalent.TOOL_TASK.equals(chunk.getToolName()) ||
                    MemoryTalent.isMemoryTool(chunk.getToolName()) ||
                    GoalTalent.isGoalTool(chunk.getToolName())) {
                return WebChunk.EMPTY;
            }

            WebChunk webChunk = WebChunk.ofActionEnd(chunk.getContent());

            if (Assert.isNotEmpty(chunk.getToolName())) {
                webChunk.setArgs(new LinkedHashMap<>(chunk.getArgs()));

                // toolName 恒为裸名（供前端识别/查表）；toolTitle 为显示名（子代理时加 agentName 前缀）
                webChunk.setToolName(chunk.getToolName());
                if (engine.getName().equals(chunk.getAgentName())) {
                    webChunk.setToolTitle(chunk.getToolName());
                } else {
                    webChunk.setToolTitle(chunk.getAgentName() + "/" + chunk.getToolName());
                }

                if (TodoTalent.TOOL_TODOWRITE.equals(chunk.getToolName())) {
                    String todos = (String) chunk.getArgs().get(TodoTalent.PARAM_TODOS);

                    if (Assert.isNotEmpty(todos)) {
                        webChunk.setText(todos);
                        webChunk.getArgs().remove(TodoTalent.PARAM_TODOS);
                    }
                }

                if (TerminalTalent.TOOL_WRITE.equals(chunk.getToolName())) {
                    String content = (String) chunk.getArgs().get(TerminalTalent.PARAM_CONTENT);

                    if (Assert.isNotEmpty(content)) {
                        webChunk.setText(content);
                        webChunk.getArgs().remove(TerminalTalent.PARAM_CONTENT);
                    }
                }

                // edit：入参为结构化 edits 列表（无 diff 字段），在此由结构化参数重建 git diff 文本写入 args.diff，
                // text 保留工具真实返回（成功提示/错误信息）作为「输出」，由前端 edit 渲染器两段式展示。
                fillEditDiff(webChunk.getArgs());
            }

            return webChunk;
        }

        return WebChunk.EMPTY;
    }

    /**
     * 将 edit 工具的结构化 edits 列表转换为标准 git diff 文本，写入 {@code args.diff}，供前端 edit 渲染器着色展示。
     *
     * <p>edit 工具入参为 edits 列表（每项含 old_str / old_StrStartLine / new_str / replace_all），本身不含 diff 文本。
     * 前端渲染器依赖 {@code args.diff} 渲染，故在此由结构化参数重建 git diff：每个编辑操作生成一个 hunk，
     * old_str 各行打 {@code -}、new_str 各行打 {@code +}，old_StrStartLine 提供 {@code @@} 行号锚点（缺失时退化为 0）。
     * 转换后移除原始 edits，避免工具卡头部回显冗余结构。</p>
     *
     * @param args 工具参数（可为 null）
     */
    @SuppressWarnings("unchecked")
    private void fillEditDiff(Map<String, Object> args) {
        if (args == null || !(args.get(TerminalTalent.PARAM_EDITS) instanceof List)) {
            return;
        }

        List<?> edits = (List<?>) args.get(TerminalTalent.PARAM_EDITS);
        if (edits.isEmpty()) {
            return;
        }

        StringBuilder diff = new StringBuilder();
        for (Object item : edits) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> edit = (Map<String, Object>) item;

            int startLine = asInt(edit.get("old_StrStartLine"), 0);
            List<String> oldLines = splitLines(asString(edit.get("old_str")));
            List<String> newLines = splitLines(asString(edit.get("new_str")));

            diff.append("@@ -").append(startLine).append(',').append(oldLines.size())
                    .append(" +").append(startLine).append(',').append(newLines.size())
                    .append(" @@\n");

            for (String line : oldLines) {
                diff.append('-').append(line).append('\n');
            }
            for (String line : newLines) {
                diff.append('+').append(line).append('\n');
            }
        }

        if (diff.length() > 0) {
            args.put("diff", diff.toString());
            args.remove(TerminalTalent.PARAM_EDITS);
        }
    }

    private static String asString(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o instanceof String) {
            try {
                return Integer.parseInt(((String) o).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static List<String> splitLines(String s) {
        if (s == null || s.isEmpty()) {
            return Collections.emptyList();
        }
        // 统一换行符并去掉末尾换行，避免 split 产生多余空元素
        String normalized = s.replace("\r\n", "\n").replace('\r', '\n');
        while (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(normalized.split("\n", -1));
    }

    /**
     * 处理思考轮次（Thought）阶段的 chunk
     *
     * <p>核心职责：
     * <ol>
     *   <li><b>IM 通道转发</b>：根据本轮是否有工具调用、是否为源代理的最终结果，
     *       以不同的标记（isFinal）将内容推送到所有已绑定的 IM 通道。</li>
     *   <li><b>Web 输出</b>：仅在多任务并行（multitask）标记存在时，才向 Web 端输出文本 chunk；
     *       普通单轮 Thought 不输出到 Web（避免与 ReasonDeltaChunk 重复）。</li>
     * </ol></p>
     *
     * @param session Agent 会话，用于获取会话ID和已选择的代理名称
     * @param chunk 思考轮次的 chunk 数据，包含助手消息和追踪信息
     * @return 映射后的 WebChunk（多任务并行时有内容），或 {@link WebChunk#EMPTY}
     */
    private WebChunk onThoughtChunk(AgentSession session, ThoughtChunk chunk) {
        String sessionId = session.getSessionId();
        String resultContent = chunk.getAssistantMessage().getResultContent();

        if (Assert.isNotEmpty(resultContent)) {
            // 向所有已绑定的 IM 通道回复
            if (chunk.isToolCalls()) {
                // 说明是过程
                replyToBoundChannel(sessionId, resultContent, false);
            } else {
                // 说明是结果
                String agentSelectedTmp = (String) session.attrs().get("_agent_selected_tmp");

                if (chunk.getTrace().getAgentName().equals(agentSelectedTmp)) {
                    // 说明是源代理（说明是最终结果）
                    //StringBuilder traceInfo = getTraceInfo(thought.getTrace());
                    replyToBoundChannel(sessionId, resultContent, true);//+ traceInfo, true);
                } else {
                    // 说明是次代理
                    replyToBoundChannel(sessionId, resultContent, false);
                }
            }


            if (chunk.hasMeta(TaskTalent.TOOL_MULTITASK)) {
                // 仅在多任务并行且有内容时输出
                return WebChunk.ofText("\n" + resultContent);
            }
        }

        return WebChunk.EMPTY;
    }

    /**
     * 处理 ReAct 流的最终汇总 chunk
     *
     * <p>当 Agent 流结束时触发。若检测到异常终止，将异常内容连同追踪信息
     * 同步转发到所有已绑定的 IM 通道。无论是否异常，都将追踪信息
     * （模型名称、token 数、耗时）以结构化 trace 类型输出到 Web 端。</p>
     *
     * @param session Agent 会话，用于获取会话ID以进行 IM 通道转发
     * @param chunk   ReAct 最终汇总 chunk，包含追踪信息和可能的异常内容
     * @return 包含追踪信息的 trace 类型 WebChunk
     */
    private WebChunk onFinalChunk(AgentSession session, ReActChunk chunk) {
        ReActTrace trace = chunk.getTrace();

        if (chunk.isAbnormal()) {
            // 通知 IM 任务完成了
            replyToBoundChannel(session.getSessionId(), chunk.getContent(), true);
        }

        // 结构化 trace 数据，供前端独立渲染
        String model = trace.getOptions().getChatModel().getNameOrModel();
        Long totalTokens = trace.getMetrics() != null ? trace.getMetrics().getTotalTokens() : null;
        long startMs = trace.getBeginTimeMs();
        Long elapsedSeconds = startMs > 0 ? Duration.ofMillis(System.currentTimeMillis() - startMs).getSeconds() : null;

        // 最终答案全量文本（去除 think 标签，与正文输出保持一致），供前端复制使用
        String finalAnswer = chunk.getContent();
        if (finalAnswer != null) {
            finalAnswer = finalAnswer.replaceAll("(?s)<\\s*/?think\\s*>", "");
        }

        return WebChunk.ofTrace(model, totalTokens, elapsedSeconds, finalAnswer);
    }

    /**
     * 向所有已绑定的 IM 通道发送回复
     */
    public void replyToBoundChannel(String sessionId, String text, boolean isFinal) {
        for (Channel link : imLinks) {
            if (link.isBound(sessionId)) {
                link.sendReply(sessionId, text, isFinal);
            }
        }
    }
}
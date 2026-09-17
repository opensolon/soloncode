package org.noear.solon.codecli.portal.acp;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.task.PlanEvent;
import org.noear.solon.ai.agent.react.task.ReasonDeltaEvent;
import org.noear.solon.ai.agent.react.task.ReasonEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallStartEvent;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.talents.memory.MemoryTalent;
import org.noear.solon.codecli.command.builtin.GoalTalent;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.core.util.Assert;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AcpLink implements Runnable {
    private final HarnessEngine agentRuntime;
    private final AcpAgentTransport agentTransport;
    private final AgentSettings agentSettings;

    public AcpLink(HarnessEngine agentRuntime, AcpAgentTransport agentTransport, AgentSettings agentSettings) {
        this.agentRuntime = agentRuntime;
        this.agentTransport = agentTransport;
        this.agentSettings = agentSettings;
    }

    private final Map<String, AcpSessionContext> sessionStates = new ConcurrentHashMap<>();

    /**
     * callId -> toolCallId 映射，用于 ToolCallStart 与 ToolCallEnd 之间精确配对同一张工具卡。
     * key = sessionId + "#" + callId；end 阶段消费后移除，避免累积。
     */
    private final Map<String, String> callIdToToolCallId = new ConcurrentHashMap<>();

    public void run() {
        AcpAsyncAgent acpAgent = createAgent(agentTransport);
        acpAgent.start().subscribe();
    }

    public AcpAsyncAgent createAgent(AcpAgentTransport transport) {
        return AcpAgent.async(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .initializeHandler(req -> {
                    // SessionCapabilities: 参数为 Object，非 null 表示"支持"，null 表示"不支持"。
                    // 声明 list=false(null), close={}, resume=false(null)。
                    // 只有声明了 close 能力，客户端才会主动调用 session/close。
                    // close 用空对象 {} 而非 Boolean.TRUE，因为部分客户端（如 IDEA）
                    // 的 ACP 模型将 close 定义为 SessionCloseCapabilities 对象类型，
                    // 传入 true 会序列化为 JSON 字面量导致反序列化失败。
                    AcpSchema.SessionCapabilities sessionCaps =
                            new AcpSchema.SessionCapabilities(null, Collections.emptyMap(), null);

                    return Mono.just(new AcpSchema.InitializeResponse(
                            1,
                            new AcpSchema.AgentCapabilities(true,
                                    sessionCaps,
                                    new AcpSchema.McpCapabilities(true, true),
                                    new AcpSchema.PromptCapabilities(true, true, true),
                                    null),
                            Arrays.asList()
                    ));
                })
                .newSessionHandler(req -> {
                    String sessionId = "acp-" + UUID.randomUUID().toString().substring(0, 8);
                    String cwd = req.cwd();

                    sessionStates.put(sessionId, new AcpSessionContext(cwd, req.mcpServers()));

                    return Mono.just(new AcpSchema.NewSessionResponse(sessionId, null, null));
                })
                .loadSessionHandler(req -> {
                    String sessionId = req.sessionId();
                    String cwd = req.cwd();

                    sessionStates.put(sessionId, new AcpSessionContext(cwd, req.mcpServers()));

                    return Mono.just(new AcpSchema.LoadSessionResponse(null, null));
                })
                .cancelHandler(req -> {
                    String sessionId = req.sessionId();
                    AcpSessionContext context = sessionStates.get(sessionId);
                    if (context != null) {
                        // 设置取消标志，prompt 流中的 takeWhile(!cancelled) 会在下一个 chunk 时中断后端任务
                        context.setCancelled(true);
                    }
                    return Mono.empty();
                })
                .closeSessionHandler(req -> {
                    // 客户端显式关闭 session：正确的清理时机
                    String sessionId = req.sessionId();
                    AcpSessionContext context = sessionStates.remove(sessionId);
                    if (context != null) {
                        context.setCancelled(true);
                    }
                    // 同步清理后端 AgentSession，避免 SessionManager.sessionMap 内存泄漏。
                    // 注意：仅从内存 map 移除，磁盘上的 FileAgentSession 历史保留，供后续 loadSession 恢复。
                    removeBackendSession(sessionId);
                    return Mono.just(new AcpSchema.CloseSessionResponse());
                })
                .promptHandler((request, acpContext) -> {
                    String sessionId = acpContext.getSessionId();
                    final AcpSessionContext context = sessionStates.get(sessionId);

                    // session 不存在时抛出协议异常，SDK 会保留 SESSION_NOT_FOUND(-32002) 错误码，
                    // 客户端收到语义明确的错误而非 NPE 兵底的 -32603。
                    if (context == null) {
                        return Mono.error(new AcpProtocolException(
                                AcpErrorCodes.SESSION_NOT_FOUND,
                                "Session not found: " + sessionId));
                    }

                    Prompt userInput = toPrompt(request, context.getCwd());
                    AgentSession session = agentRuntime.getSession(sessionId);

                    final long startTime = System.currentTimeMillis();
                    final AtomicInteger toolCallCounter = new AtomicInteger(0);

                    return agentRuntime.prompt(userInput)
                            .session(session)
                            .options(o -> {
                                if (Assert.isNotEmpty(context.getCwd())) {
                                    o.toolContextPut(HarnessEngine.ATTR_CWD, context.getCwd());
                                }
                            })
                            .stream()
                            .takeWhile(event -> !context.isCancelled())
                            .concatMap(event -> {
                                // === 规划阶段：映射到 ACP Plan 结构化输出 ===
                                if (event instanceof PlanEvent) {
                                    String content = event.getText();
                                    AcpSchema.PlanEntry entry = new AcpSchema.PlanEntry(
                                            content != null ? content : "Planning...",
                                            AcpSchema.PlanEntryPriority.HIGH,
                                            AcpSchema.PlanEntryStatus.IN_PROGRESS
                                    );
                                    AcpSchema.Plan plan = new AcpSchema.Plan("plan", Collections.singletonList(entry));
                                    return acpContext.sendUpdate(sessionId, plan)
                                            .thenReturn(event);
                                }
                                // === 思考阶段 ===
                                else if (event instanceof ReasonDeltaEvent) {
                                    if (event.hasText()) {
                                        if (agentSettings.getGeneral().isCliThinkPrinted()) {
                                            return acpContext.sendThought(event.getText())
                                                    .thenReturn(event);
                                        }
                                    }
                                }
                                // === ThoughtChunk（多任务并行） ===
                                else if (event instanceof ReasonEndEvent) {
                                    ReasonEndEvent thoughtChunk = (ReasonEndEvent) event;
                                    if (thoughtChunk.hasMeta(TaskTalent.TOOL_MULTITASK)) {
                                        String content = thoughtChunk.getText();
                                        if (Assert.isNotEmpty(content)) {
                                            return acpContext.sendThought(content)
                                                    .thenReturn(event);
                                        }
                                    }
                                }
                                // === 工具执行开始阶段：发 pending ToolCall 骨架卡 ===
                                else if (event instanceof ToolCallStartEvent) {
                                    ToolCallStartEvent startChunk = (ToolCallStartEvent) event;
                                    String toolName = startChunk.getToolName();

                                    if (isInternalTool(toolName)) {
                                        return Mono.just(event);
                                    }

                                    // 以 callId 作为 toolCallId 的稳定来源，保证 start/end 精确配对；
                                    // callId 缺失时退化为自增序号。
                                    String toolCallId = resolveToolCallId(startChunk.getCallId(), toolCallCounter);
                                    callIdToToolCallId.put(sessionId + "#" + startChunk.getCallId(), toolCallId);

                                    Map<String, Object> args = startChunk.getArgs();
                                    AcpSchema.ToolCall toolCall = new AcpSchema.ToolCall(
                                            "tool_call",
                                            toolCallId,
                                            buildToolTitle(toolName, startChunk.getAgentName(), args, null, true),
                                            resolveToolKind(toolName),
                                            AcpSchema.ToolCallStatus.IN_PROGRESS,
                                            Collections.emptyList(),
                                            buildLocations(args),
                                            args,          // rawInput
                                            null,          // rawOutput
                                            null           // meta
                                    );
                                    return acpContext.sendUpdate(sessionId, toolCall)
                                            .thenReturn(event);
                                }
                                // === 工具执行结束阶段：发 ToolCallUpdate 填充完成/失败态 ===
                                else if (event instanceof ToolCallEndEvent) {
                                    ToolCallEndEvent observationChunk = (ToolCallEndEvent) event;
                                    String toolName = observationChunk.getToolName();

                                    if (isInternalTool(toolName)) {
                                        return Mono.just(event);
                                    }

                                    // 复用 start 阶段登记的 toolCallId；未登记（无 start 事件）时新建
                                    String key = sessionId + "#" + observationChunk.getCallId();
                                    String toolCallId = callIdToToolCallId.remove(key);
                                    if (toolCallId == null) {
                                        toolCallId = resolveToolCallId(observationChunk.getCallId(), toolCallCounter);
                                    }

                                    boolean failed = observationChunk.getError() != null;
                                    String content = failed
                                            ? String.valueOf(observationChunk.getError().getMessage())
                                            : event.getText();
                                    Map<String, Object> args = observationChunk.getArgs();

                                    AcpSchema.ToolCallUpdateNotification update = new AcpSchema.ToolCallUpdateNotification(
                                            "tool_call_update",
                                            toolCallId,
                                            buildToolTitle(toolName, observationChunk.getAgentName(), args, content, false),
                                            resolveToolKind(toolName),
                                            failed ? AcpSchema.ToolCallStatus.FAILED : AcpSchema.ToolCallStatus.COMPLETED,
                                            buildToolContent(toolName, args, content),
                                            buildLocations(args),
                                            args,          // rawInput
                                            content,       // rawOutput
                                            null           // meta
                                    );
                                    return acpContext.sendUpdate(sessionId, update)
                                            .thenReturn(event);
                                }
                                // === 最终回复阶段 ===
                                else if (event instanceof RunEndEvent) {
                                    String traceInfo = buildTraceInfo(((RunEndEvent) event).getTrace(), startTime);

                                    String finalContent = event.getText() + traceInfo;

                                    // 发送最终文本内容
                                    return acpContext.sendMessage(finalContent)
                                            .thenReturn(event);
                                }

                                return Mono.just(event);
                            })
                            .onErrorResume(e -> {
                                // 协议异常透传，保留原错误码；其余异常以消息形式反馈
                                if (e instanceof AcpProtocolException) {
                                    return Mono.error(e);
                                }
                                return acpContext.sendMessage("Error: " + e.getMessage())
                                        .then(Mono.empty());
                            })
                            .doFinally(signal -> {
                                // 不再删除 sessionStates（session 生命周期由 close/cancel 控制），
                                // 仅重置本轮取消标志，为下一轮 prompt 准备
                                context.setCancelled(false);
                                // 清理本轮残留的 callId 映射：正常路径下 end 阶段已 remove，
                                // 但工具 start 后被 cancel/异常中断（未走到 end）时会残留，此处按 sessionId 前缀兜底清理
                                callIdToToolCallId.keySet().removeIf(k -> k.startsWith(sessionId + "#"));
                            })
                            .then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)));
                })
                .build();
    }

    /**
     * 同步移除后端 AgentSession（仅内存 map，不删除磁盘历史）。
     *
     * <p>HarnessEngine.getSessionProvider() 返回 AgentSessionProvider 接口，
     * removeSession 仅定义在具体实现 SessionManager 上，故需 instanceof 判断。</p>
     */
    private void removeBackendSession(String sessionId) {
        AgentSessionProvider provider = agentRuntime.getSessionProvider();
        if (provider instanceof SessionManager) {
            ((SessionManager) provider).removeSession(sessionId);
        }
    }

    /**
     * 内部工具（不向 ACP 客户端展示）判定，与 WebStreamBuilder 保持一致：
     * 任务分发工具（task/multitask）、记忆工具、目标工具。
     */
    private boolean isInternalTool(String toolName) {
        return TaskTalent.TOOL_MULTITASK.equals(toolName)
                || TaskTalent.TOOL_TASK.equals(toolName)
                || MemoryTalent.isMemoryTool(toolName)
                || GoalTalent.isGoalTool(toolName);
    }

    /**
     * 生成稳定的 toolCallId：优先用引擎 callId（保证 start/end 配对），缺失时用自增序号兜底。
     */
    private String resolveToolCallId(String callId, AtomicInteger counter) {
        if (Assert.isNotEmpty(callId)) {
            return "tc-" + callId;
        }
        return "tc-" + counter.incrementAndGet();
    }

    /**
     * 按工具名映射 ACP ToolKind，让客户端按类型渲染（读/改/执行/搜索/抓取等）。
     */
    private AcpSchema.ToolKind resolveToolKind(String toolName) {
        if (Assert.isEmpty(toolName)) {
            return AcpSchema.ToolKind.OTHER;
        }
        switch (toolName) {
            case "read":
                return AcpSchema.ToolKind.READ;
            case TerminalTalent.TOOL_WRITE:
            case TerminalTalent.TOOL_EDIT:
                return AcpSchema.ToolKind.EDIT;
            case "grep":
            case "glob":
            case "ls":
                return AcpSchema.ToolKind.SEARCH;
            case "bash":
                return AcpSchema.ToolKind.EXECUTE;
            case "webfetch":
            case "websearch":
                return AcpSchema.ToolKind.FETCH;
            default:
                return AcpSchema.ToolKind.EXECUTE;
        }
    }

    /**
     * 构建结构化 ToolCallContent：
     * <ul>
     *   <li>write：以 ToolCallDiff 呈现新建文件内容（oldText=null, newText=content 参数）</li>
     *   <li>edit：以 ToolCallDiff 逐条呈现 old_str → new_str 改动</li>
     *   <li>其余工具：以 ToolCallContentBlock 包裹文本输出</li>
     * </ul>
     */
    private List<AcpSchema.ToolCallContent> buildToolContent(String toolName, Map<String, Object> args, String content) {
        // write：整文件新增/覆盖，用 diff 展示写入内容
        if (TerminalTalent.TOOL_WRITE.equals(toolName) && args != null) {
            String path = firstNonEmpty(args, "file_path", "path");
            Object body = args.get(TerminalTalent.PARAM_CONTENT);
            if (path != null && body != null) {
                return Collections.singletonList(new AcpSchema.ToolCallDiff(
                        "diff", path, null, String.valueOf(body)));
            }
        }

        // edit：结构化 edits 列表，逐条生成 diff
        if (TerminalTalent.TOOL_EDIT.equals(toolName) && args != null
                && args.get(TerminalTalent.PARAM_EDITS) instanceof List) {
            String path = firstNonEmpty(args, "file_path", "path");
            List<AcpSchema.ToolCallContent> diffs = new ArrayList<>();
            for (Object item : (List<?>) args.get(TerminalTalent.PARAM_EDITS)) {
                if (item instanceof Map) {
                    Map<?, ?> edit = (Map<?, ?>) item;
                    Object oldStr = edit.get("old_str");
                    Object newStr = edit.get("new_str");
                    diffs.add(new AcpSchema.ToolCallDiff(
                            "diff", path,
                            oldStr == null ? null : String.valueOf(oldStr),
                            newStr == null ? null : String.valueOf(newStr)));
                }
            }
            if (!diffs.isEmpty()) {
                return diffs;
            }
        }

        // 其余工具：文本输出
        if (Assert.isNotEmpty(content)) {
            return Collections.singletonList(new AcpSchema.ToolCallContentBlock(
                    "content", new AcpSchema.TextContent(content)));
        }

        return Collections.emptyList();
    }

    /**
     * 从工具参数提取文件位置，生成 ToolCallLocation，供客户端跳转/高亮。
     */
    private List<AcpSchema.ToolCallLocation> buildLocations(Map<String, Object> args) {
        if (args == null) {
            return Collections.emptyList();
        }
        String path = firstNonEmpty(args, "file_path", "path");
        if (path == null) {
            return Collections.emptyList();
        }
        Integer line = null;
        Object off = args.get("offset");
        if (off instanceof Number) {
            line = ((Number) off).intValue();
        }
        return Collections.singletonList(new AcpSchema.ToolCallLocation(path, line));
    }

    private String firstNonEmpty(Map<String, Object> args, String... keys) {
        for (String k : keys) {
            Object v = args.get(k);
            if (v != null && Assert.isNotEmpty(String.valueOf(v))) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    /**
     * 构建工具调用的显示标题（title）。
     *
     * <p>title 用于客户端卡片头部展示；子代理执行时加 agentName/ 前缀（对齐 WebStreamBuilder）。</p>
     *
     * @param running true=start 阶段（执行中，无结果摘要），false=end 阶段（已完成，可带结果摘要）。
     *                避免 start 阶段 content 为空时错误地显示“read: completed”（状态却是 IN_PROGRESS）。
     */
    private String buildToolTitle(String toolName, String agentName, Map<String, Object> args, String content, boolean running) {
        if (Assert.isEmpty(toolName)) {
            return content;
        }

        // 子代理前缀：非主引擎执行时标注归属
        String displayName = toolName;
        if (Assert.isNotEmpty(agentName) && !agentRuntime.getName().equals(agentName)) {
            displayName = agentName + "/" + toolName;
        }

        String argsStr = buildArgsStr(args);

        if (agentSettings.getGeneral().isCliPrintSimplified()) {
            // 简化模式：只显示工具名 + 状态/结果摘要
            String summary;
            if (running) {
                // start 阶段：优先展示参数（如 file_path），无参数时用 running 占位
                summary = Assert.isEmpty(argsStr) ? "running" : summary(argsStr);
            } else if (Assert.isEmpty(content)) {
                summary = "completed";
            } else {
                String[] lines = content.split("\n");
                if (lines.length > 1) {
                    summary = "returned " + lines.length + " lines";
                } else {
                    summary = summary(content);
                }
            }
            return displayName + ": " + summary;
        } else {
            // 全量模式：显示工具名 + 参数
            if (argsStr.length() > 100) {
                return displayName + "(" + argsStr.substring(0, 97) + "...)";
            }
            return displayName + "(" + argsStr + ")";
        }
    }

    /**
     * 构建 trace 统计信息（参考 WebStreamBuilder.getTraceInfo）
     */
    private String buildTraceInfo(ReActTrace trace, long startTime) {
        StringBuilder buf = new StringBuilder();
        buf.append("(");

        if (trace != null) {
            if (trace.getOptions() != null && trace.getOptions().getChatModel() != null) {
                buf.append(trace.getOptions().getChatModel().getNameOrModel());
            }
            if (trace.getMetrics() != null) {
                if (buf.length() > 1) buf.append(", ");
                buf.append(trace.getMetrics().getTotalTokens()).append("tk");
            }
        }

        long seconds = Duration.ofMillis(System.currentTimeMillis() - startTime).getSeconds();
        if (buf.length() > 1) buf.append(", ");
        buf.append(seconds).append("s");

        buf.append(")");
        return buf.toString();
    }

    /** 截断过长文本作为摘要（超过 40 字符截断并加省略号）。 */
    private String summary(String text) {
        return text.length() > 40 ? text.substring(0, 37) + "..." : text;
    }

    private String buildArgsStr(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        args.forEach((k, v) -> {
            if (sb.length() > 0) sb.append(" ");
            sb.append(k).append("=").append(v);
        });
        return sb.toString().replace("\n", " ");
    }

    public Prompt toPrompt(AcpSchema.PromptRequest promptRequest, String cwd) {
        Prompt prompt = Prompt.of();
        Contents contents = new Contents();

        // 文本块和 ResourceLink 的 [path] 统一收集到一个 StringBuilder，
        // 保证 "用户文本 [uri]" 连续输出在同一个 TextBlock 中。
        StringBuilder textBuilder = new StringBuilder();

        for (AcpSchema.ContentBlock cp : promptRequest.prompt()) {
            if (cp instanceof AcpSchema.TextContent) {
                AcpSchema.TextContent text = (AcpSchema.TextContent) cp;
                if (textBuilder.length() > 0) textBuilder.append(" ");
                textBuilder.append(text.text());
            } else if (cp instanceof AcpSchema.ImageContent) {
                // 遇到非文本块时先 flush 已累积的文本
                flushText(contents, textBuilder);
                AcpSchema.ImageContent image = (AcpSchema.ImageContent) cp;
                if (Assert.isEmpty(image.uri())) {
                    contents.addBlock(ImageBlock.ofBase64(image.data(), image.mimeType()));
                } else {
                    contents.addBlock(ImageBlock.ofUrl(image.uri(), image.mimeType()));
                }
            } else if (cp instanceof AcpSchema.ResourceLink) {
                // 文件拖拽引用：不读取内容，只将 URI 转为工作区相对路径，
                // 以 [path] 形式追加到用户文本后面
                String relativePath = toRelativePath(((AcpSchema.ResourceLink) cp).uri(), cwd);
                if (Assert.isNotEmpty(relativePath)) {
                    if (textBuilder.length() > 0) textBuilder.append(" ");
                    textBuilder.append("[").append(relativePath).append("]");
                }
            } else if (cp instanceof AcpSchema.Resource) {
                // 遇到非文本块时先 flush 已累积的文本
                flushText(contents, textBuilder);
                handleEmbeddedResource(contents, (AcpSchema.Resource) cp);
            }
        }

        // flush 剩余文本
        flushText(contents, textBuilder);

        return prompt.addMessage(ChatMessage.ofUser(contents));
    }

    /** 将 StringBuilder 中累积的文本作为 TextBlock 加入 contents，然后清空。 */
    private void flushText(Contents contents, StringBuilder textBuilder) {
        if (textBuilder.length() > 0) {
            contents.addBlock(TextBlock.of(textBuilder.toString()));
            textBuilder.setLength(0);
        }
    }

    /**
     * 将 ResourceLink 的 URI 转为工作区相对路径。
     * <p>去除 file:// 前缀，若路径以 cwd 开头则转为相对路径，否则返回原路径。</p>
     */
    private String toRelativePath(String uri, String cwd) {
        if (Assert.isEmpty(uri)) {
            return null;
        }

        // 去除 file:// 前缀
        String filePath = uri;
        if (filePath.startsWith("file://")) {
            filePath = filePath.substring(7);
            // Windows: file:///C:/... → C:/...
            if (filePath.startsWith("/") && filePath.length() > 2 && filePath.charAt(2) == ':') {
                filePath = filePath.substring(1);
            }
        }

        // 转为工作区相对路径
        if (Assert.isNotEmpty(cwd)) {
            Path fullPath = Paths.get(filePath);
            Path cwdPath = Paths.get(cwd);
            if (fullPath.startsWith(cwdPath)) {
                return cwdPath.relativize(fullPath).toString();
            }
        }

        return filePath;
    }



    /**
     * 处理 Resource：客户端内联嵌入的资源内容。
     * <ul>
     *   <li>TextResourceContents：text 字段已有内容，直接作为 TextBlock</li>
     *   <li>BlobResourceContents：base64 编码，图片转 ImageBlock，其余尝试解码为文本</li>
     * </ul>
     */
    private void handleEmbeddedResource(Contents contents, AcpSchema.Resource resource) {
        AcpSchema.EmbeddedResourceResource res = resource.resource();
        if (res == null) {
            return;
        }

        if (res instanceof AcpSchema.TextResourceContents) {
            AcpSchema.TextResourceContents textRes = (AcpSchema.TextResourceContents) res;
            String text = textRes.text();
            if (Assert.isNotEmpty(text)) {
                String uri = textRes.uri();
                String label = Assert.isNotEmpty(uri) ? "File: " + uri + "\n" : "";
                contents.addBlock(TextBlock.of(label + "```\n" + text + "\n```"));
            }
        } else if (res instanceof AcpSchema.BlobResourceContents) {
            AcpSchema.BlobResourceContents blobRes = (AcpSchema.BlobResourceContents) res;
            String blob = blobRes.blob();
            String mimeType = blobRes.mimeType();
            if (Assert.isEmpty(blob)) {
                return;
            }

            if (mimeType != null && mimeType.startsWith("image/")) {
                contents.addBlock(ImageBlock.ofBase64(blob, mimeType));
            } else {
                // 尝试解码为文本
                try {
                    byte[] decoded = Base64.getDecoder().decode(blob);
                    String text = new String(decoded, StandardCharsets.UTF_8);
                    String uri = blobRes.uri();
                    String label = Assert.isNotEmpty(uri) ? "File: " + uri + "\n" : "";
                    contents.addBlock(TextBlock.of(label + "```\n" + text + "\n```"));
                } catch (Exception e) {
                    // 二进制内容无法解码为文本
                    String uri = blobRes.uri();
                    String name = Assert.isNotEmpty(uri) ? uri : "binary resource";
                    contents.addBlock(TextBlock.of("[Binary resource: " + name + " ("
                            + (mimeType != null ? mimeType : "unknown") + ")]"));
                }
            }
        }
    }

    public static class AcpSessionContext {
        private final String cwd;
        private final List<AcpSchema.McpServer> mcpServers;
        private final Instant createdAt;
        private volatile boolean cancelled;

        public AcpSessionContext(String cwd, List<AcpSchema.McpServer> mcpServers) {
            this.cwd = cwd;
            this.mcpServers = mcpServers;
            this.createdAt = Instant.now();
        }

        public String getCwd() {
            return cwd;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public List<AcpSchema.McpServer> getMcpServers() {
            return mcpServers;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }
    }
}

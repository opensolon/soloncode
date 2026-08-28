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
package org.noear.solon.codecli.portal.web.service;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.codecli.portal.web.SteerInterceptor;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.portal.web.event.WebEventNames;
import org.noear.solon.codecli.portal.web.event.payload.ToolEndPayload;
import org.noear.solon.codecli.portal.web.pipeline.ToolPresentationFilter;
import org.noear.solon.codecli.portal.web.pipeline.ToolViewUtil;
import org.noear.solon.core.util.Assert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「最后一轮执行过程」还原服务。
 *
 * <p>Web 端刷新页面后，历史消息只能还原纯文本（ndjson 仅落用户输入与最终回答，
 * 中间的思考、插话与工具调用只存在于 {@link ReActTrace} 的 WorkingMemory 里）。本服务把
 * WorkingMemory 中最近一轮的过程抽成一条<b>线性事件序列</b>，交由前端合成与实时流同构的
 * {@code thought.delta} / {@code message.delta} / {@code tool.start} / {@code tool.end}
 * 事件回放，使最后一条 AI 消息也能展开执行过程。</p>
 *
 * <h3>为什么是线性事件而不是「工具轮」</h3>
 * <p>早期实现以「带 toolCalls 的 AssistantMessage」为回放单元，代价是三类内容凭空消失：
 * 不带工具的纯思考消息（推理模型把答案也写进 reasoning 通道时很常见）、被过滤工具那一轮的
 * 思考与正文、以及夹在它们之间的插话（只能整体挤到尾部，丢失真实位置）。改为线性扫描后，
 * 每条消息无论有无 toolCalls 都产出自己的思考/正文，插话按原位插入。</p>
 *
 * <h3>历史消息与最终回答的判别</h3>
 * <p>WorkingMemory 开头还包含 {@code session.getLatestMessages()} 载入的历史上下文，
 * 线性扫描不能像「有无 toolCalls」那样天然排除它们，否则整段历史会被重复渲染一遍。
 * 判据用 {@link AgentTrace#META_RUN_ID}：该标记在消息落 ndjson 的同一处打上
 * （{@code ReActAgent} 里 {@code session.addMessage} 前后），因此
 * 「带 {@code _runId} 且不等于当前 runId」精确等价于「属于往轮、已由历史渲染」；
 * 「带当前 runId 的 assistant」就是本轮最终回答，其正文归历史渲染，
 * 但<b>思考仍需回放</b>（历史只存正文投影，思考会丢）。</p>
 *
 * <h3>插话（steer）</h3>
 * <p>运行中插话是零持久化的（{@link SteerInterceptor} 不写 ndjson），它唯一的留存处就是
 * WorkingMemory 里那条带 {@code source=steer} 的 UserMessage。若不回放，刷新后用户会看到
 * 「AI 忽然改了方向」却找不到自己那句纠偏。</p>
 *
 * @author noear
 */
public class LastTraceService {
    /**
     * 单条工具结果的最大保留字节数（超出截断，前端显示省略提示）
     */
    private static final int MAX_RESULT_CHARS = 16 * 1024;
    /**
     * 本次响应所有工具结果的合计上限（超出后续结果只保留占位）
     */
    private static final int MAX_TOTAL_CHARS = 256 * 1024;
    /**
     * 单条插话的最大保留字符数（与 {@link SteerInterceptor#MAX_TEXT_LENGTH} 对齐）
     */
    private static final int MAX_STEER_CHARS = SteerInterceptor.MAX_TEXT_LENGTH;
    /**
     * 单段思考/正文的最大保留字符数（回放只为还原现场，不必也不该搬运整篇长文）
     */
    private static final int MAX_SEGMENT_CHARS = 32 * 1024;

    private static final String THINK_OPEN = "<think>";
    private static final String THINK_CLOSE = "</think>";

    /**
     * 事件类型：插话
     */
    public static final String KIND_STEER = "steer";
    /**
     * 事件类型：思考段（前端进可折叠思考块）
     */
    public static final String KIND_THINKING = "thinking";
    /**
     * 事件类型：正文段（前端进气泡正文）
     */
    public static final String KIND_NOTE = "note";
    /**
     * 事件类型：工具调用
     */
    public static final String KIND_TOOL = "tool";

    /**
     * 还原指定会话最后一轮的执行过程。
     *
     * @param session     会话（可为 null）
     * @param traceKey    trace 在会话上下文中的键（主代理为 {@code __main}）
     * @param running     该会话当前是否仍在运行
     * @param lastUserMsg ndjson 中最后一条用户消息内容，用于对齐校验（可为 null 表示跳过校验）
     * @return 供前端回放的结构化数据，永不返回 null
     */
    public Map<String, Object> buildLastTrace(AgentSession session, String traceKey, boolean running, String lastUserMsg) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("aligned", false);
        data.put("running", running);
        data.put("runId", null);
        data.put("events", new ArrayList<>());
        data.put("truncated", false);

        ReActTrace trace = resolveTrace(session, traceKey);
        if (trace == null) {
            return data;
        }

        Prompt workingMemory = trace.getWorkingMemory();
        if (workingMemory == null || Assert.isEmpty(workingMemory.getMessages())) {
            return data;
        }

        // 对齐校验：trace 描述的任务必须就是 ndjson 里最后那条用户消息发起的任务。
        // fork 会话（只复制 messages.ndjson，不复制 snapshot）、/compact、/clear 之后
        // 两者会错位，此时宁可退回纯文本，也不能把上一轮的过程挂到别的消息下面。
        if (isAligned(trace, lastUserMsg) == false) {
            return data;
        }

        List<Map<String, Object>> events = new ArrayList<>();
        boolean truncated = extractEvents(workingMemory, trace.getRunId(), events);

        if (hasVisible(events) == false) {
            // 无可回放的过程（纯对话轮 / 全被判为历史），保持 aligned=false 让前端走原路径。
            // 单独一句插话不足以支撑回放：它靠上下文才有意义，孤零零一句反而让人困惑
            return data;
        }

        data.put("aligned", true);
        // getRunId() 在 runId 为空时会懒生成；此处 WorkingMemory 非空说明任务已跑过，runId 必已存在
        data.put("runId", trace.getRunId());
        data.put("events", events);
        data.put("truncated", truncated);
        return data;
    }

    private ReActTrace resolveTrace(AgentSession session, String traceKey) {
        if (session == null || session.getContext() == null) {
            return null;
        }

        // 快照反序列化后类型可能退化，非 ReActTrace 一律视为不可用（宁缺勿错）
        Object obj = session.getContext().get(traceKey);
        return (obj instanceof ReActTrace) ? (ReActTrace) obj : null;
    }

    /**
     * trace 与 ndjson 最后一条用户消息是否对应同一轮任务。
     */
    private boolean isAligned(ReActTrace trace, String lastUserMsg) {
        if (lastUserMsg == null) {
            return true; // 调用方选择跳过校验
        }

        Prompt originalPrompt = trace.getOriginalPrompt();
        if (originalPrompt == null) {
            return false;
        }

        String promptContent = originalPrompt.getUserContent();
        if (Assert.isEmpty(promptContent)) {
            return false;
        }

        // 用户输入在入库前后可能有细微空白差异，按 trim 比对
        return promptContent.trim().equals(lastUserMsg.trim());
    }

    /**
     * 是否存在「插话之外」的可回放内容。
     *
     * <p>插话不能单独成立：它依附于 AI 的思考/工具过程才有语境。</p>
     */
    private boolean hasVisible(List<Map<String, Object>> events) {
        for (Map<String, Object> e : events) {
            if (KIND_STEER.equals(e.get("kind")) == false) {
                return true;
            }
        }
        return false;
    }

    /**
     * 线性扫描 WorkingMemory，按原序抽出可回放事件。
     *
     * @param currentRunId 当前 trace 的 runId，用于判别历史消息与最终回答
     * @return 是否发生过内容截断
     */
    private boolean extractEvents(Prompt workingMemory, String currentRunId, List<Map<String, Object>> events) {
        List<ChatMessage> messages = workingMemory.getMessages();
        boolean truncated = false;
        int totalChars = 0;
        int group = 0;
        boolean assistantSeen = false;

        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);

            // 往轮消息（历史上下文）整条跳过，否则线性扫描会把整段历史重复渲染一遍
            if (isFromEarlierRun(msg, currentRunId)) {
                continue;
            }

            String steerText = asSteerText(msg);
            if (steerText != null) {
                /* assistantSeen 才收：SteerInterceptor 的守卫 1（首轮 reason 不注入）保证插话绝不会
                 * 排在第一条 assistant 消息之前。据此可排除两类假阳性：存量会话里已持久化到
                 * ndjson 的旧插话行（已由历史渲染为独立 .msg-row，再回放会重复），
                 * 以及用户真的亲手敲了一句以 STEER_PREFIX 开头的提问。 */
                if (assistantSeen) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("kind", KIND_STEER);
                    e.put("text", steerText);
                    events.add(e);
                }
                continue;
            }

            if ((msg instanceof AssistantMessage) == false) {
                /* 非插话的 UserMessage 一律跳过：本轮任务提示（带当前 runId）已由历史渲染，
                 * ReasonTask 注入的「输出格式修正 / 自我反思」是系统内务，
                 * 文本模式的 Observation 也不走这条回放路径。 */
                continue;
            }

            AssistantMessage am = (AssistantMessage) msg;
            assistantSeen = true;
            group++;

            /* 最终回答（带当前 runId）：正文已落 ndjson 由历史渲染，重复输出会出现两份答案；
             * 但思考只存在于 WorkingMemory，历史渲染时被 stripThinkTags 剥掉了，必须回放。 */
            boolean isFinalAnswer = isCurrentRunAssistant(am, currentRunId);

            for (Segment seg : splitSegments(am)) {
                if (seg.think == false && isFinalAnswer) {
                    continue;
                }

                Map<String, Object> e = new LinkedHashMap<>();
                e.put("kind", seg.think ? KIND_THINKING : KIND_NOTE);
                e.put("group", group);
                e.put("text", clampSegment(seg.text));
                events.add(e);
            }

            List<ToolCall> toolCalls = am.getToolCalls();
            if (Assert.isEmpty(toolCalls)) {
                continue;
            }

            for (int c = 0; c < toolCalls.size(); c++) {
                ToolCall call = toolCalls.get(c);
                if (call == null || ToolViewUtil.isReplayHidden(call.getName())) {
                    continue;
                }

                String callId = call.getUuid();
                if (Assert.isEmpty(callId)) {
                    // 无 uuid 无法与实时事件对齐去重，跳过该条而不是造一个假 id
                    continue;
                }

                Map<String, Object> turn = new LinkedHashMap<>();
                turn.put("kind", KIND_TOOL);
                // 同一条 AssistantMessage 的思考、正文与工具卡共享 group（前端据此生成 reasonId）
                // 才能聚成一组；跨消息必须换 group，否则后一条的思考会挤进已收尾的思考块
                turn.put("group", group);
                turn.put("callId", callId);
                turn.put("name", call.getName());
                turn.put("title", ToolViewUtil.buildToolTitle(call.getName(), null, null));
                // tool.start 用模型给的原始参数（与实时流一致：展示层过滤只作用于 tool.end）
                turn.put("args", copyArgs(call.getArguments()));

                String result = findToolResult(messages, i, call, c);
                if (result == null) {
                    // 工具已发起但结果未落库：仍在执行中 / 被中断
                    turn.put("done", false);
                } else {
                    turn.put("done", true);
                    if (totalChars >= MAX_TOTAL_CHARS) {
                        turn.put("result", "");
                        turn.put("omitted", true);
                        truncated = true;
                    } else {
                        /* 交给实时流同一个展示层过滤器加工，而不是直接把工具原始返回当结果下发。
                         * 少了这一步，write/todowrite 的正文（真正要展示的内容藏在 args 里）不会被
                         * 提到 result，edit 的 diff 根本不会被算出来，LSP 诊断也不会结构化 ——
                         * 表现就是这几类卡片在回放里「显示了但没有结果内容」。
                         *
                         * 截断必须放在加工之后：write/todowrite 会用 args 里的正文整体替换 result，
                         * 先截断原始返回（往往只是一行「写入成功」）根本拦不住体积。 */
                        applyPresentation(turn, call.getName(), result, copyArgs(call.getArguments()));

                        String shown = (String) turn.get("result");
                        if (shown != null && shown.length() > MAX_RESULT_CHARS) {
                            turn.put("result", shown.substring(0, MAX_RESULT_CHARS));
                            turn.put("resultTruncated", true);
                            turn.put("resultChars", shown.length());
                            totalChars += MAX_RESULT_CHARS;
                            truncated = true;
                        } else {
                            totalChars += (shown == null) ? 0 : shown.length();
                        }
                    }
                }

                events.add(turn);
            }
        }

        return truncated;
    }

    /**
     * 该消息是否属于往轮（WorkingMemory 开头由 {@code session.getLatestMessages()} 载入的历史）。
     *
     * <p>{@link AgentTrace#META_RUN_ID} 与落 ndjson 同点打上，故「有 runId 且不等于当前」
     * 精确等价于「往轮已持久化消息」。极老的快照可能没有 runId，此时退回
     * {@link AgentTrace#META_FIRST}：该标记只加在系统提示与载入的历史/本轮提示上，
     * 过程中产生的中间消息永远没有它，故「有 _first 而无 runId」判为历史是安全的。</p>
     */
    private boolean isFromEarlierRun(ChatMessage msg, String currentRunId) {
        Object runId = msg.getMetadataAs(AgentTrace.META_RUN_ID);
        if (runId == null) {
            return msg.hasMetadata(AgentTrace.META_FIRST);
        }
        return String.valueOf(runId).equals(currentRunId) == false;
    }

    /**
     * 是否为本轮最终回答（已落 ndjson，正文由历史渲染）。
     */
    private boolean isCurrentRunAssistant(AssistantMessage msg, String currentRunId) {
        Object runId = msg.getMetadataAs(AgentTrace.META_RUN_ID);
        return runId != null && String.valueOf(runId).equals(currentRunId);
    }

    /**
     * 一段思考或正文。
     */
    private static class Segment {
        final boolean think;
        final String text;

        Segment(boolean think, String text) {
            this.think = think;
            this.text = text;
        }
    }

    /**
     * 把一条聚合消息还原成有序的「思考/正文」段序列。
     *
     * <p>solon-ai 4.1 起想法与正文分居 {@code thinking} / {@code text} 两个字段，这是新数据的
     * 正式形态，直接按双通道取即可。但仅取两个字段并不够 —— {@code text} 里仍可能内嵌
     * {@code <think>} 标签，此时必须按标签切段并保持原序：</p>
     * <ul>
     *   <li>升级前的存量快照：想法缝在 {@code content} 里（可能<b>多对</b>标签与正文交替），
     *       反序列化落到旧字段后，{@code getThinking()} 只认第一对、{@code getText()} 只剥到第一个
     *       闭标签为止，剩下的标签会原样留在正文里；</li>
     *   <li>部分模型把标签直接写进 content 通道，方言未及归并时同样表现为正文内嵌标签。</li>
     * </ul>
     * <p>不切段的后果是：第二段思考被当成答案铺进气泡、段间没有边界，且 {@code <think>}
     * 字面标签直接显示给用户 —— 也就是「几个思考消息和答案消息合到了一起」。</p>
     *
     * <p>{@code isThinking()} 不参与判定：该标记取自聚合时<b>最后一帧</b>的通道状态
     * （{@code ChatResponseDefault#getAggregationMessage}），只说明流末停在哪，不代表整条都是思考；
     * 据它短路会把正文与工具卡一并吞进思考块。</p>
     */
    private List<Segment> splitSegments(AssistantMessage msg) {
        List<Segment> out = new ArrayList<>();

        String thinking = msg.getThinking();
        if (Assert.isNotEmpty(thinking)) {
            addSegment(out, true, thinking);
        }

        String text = msg.getText();
        if (Assert.isNotEmpty(text)) {
            addSegment(out, false, text);
        }

        return out;
    }

    /**
     * 收一段（纯空白段丢弃：它只是标签之间的换行，上屏会变成一个空气泡）。
     */
    private void addSegment(List<Segment> out, boolean think, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        out.add(new Segment(think, text));
    }

    private String clampSegment(String text) {
        return (text.length() > MAX_SEGMENT_CHARS) ? text.substring(0, MAX_SEGMENT_CHARS) : text;
    }

    /**
     * 识别插话消息并还原为用户原话。
     *
     * <p>双判据：metadata {@code source=steer} 为主，内容前缀为兜底 —— 快照序列化
     * 往返后 metadata 未必保留，而前缀写在正文里必存。</p>
     *
     * @return 非插话消息返回 {@code null}
     */
    private String asSteerText(ChatMessage msg) {
        if ((msg instanceof UserMessage) == false) {
            return null;
        }

        String content = msg.getContent();
        if (Assert.isEmpty(content)) {
            return null;
        }

        boolean marked = SteerInterceptor.STEER_SOURCE.equals(msg.getMetadataAs("source"));
        boolean prefixed = content.startsWith(SteerInterceptor.STEER_PREFIX);
        if (marked == false && prefixed == false) {
            return null;
        }

        String text = prefixed ? content.substring(SteerInterceptor.STEER_PREFIX.length()) : content;
        return (text.length() > MAX_STEER_CHARS) ? text.substring(0, MAX_STEER_CHARS) : text;
    }

    /**
     * 用实时流同一个 {@link ToolPresentationFilter} 加工 tool.end 的展示字段。
     *
     * <p>该过滤器承担三件事，缺一就会让卡片「显示了却没内容」：
     * write/todowrite 把真正要展示的正文从 args 提到 result（并从 args 摘除以免重复铺一遍），
     * edit 依据 args.edits 算出统一 diff，write/edit 把 LSP 诊断从结果文本里剥离成结构化字段。</p>
     *
     * <p>lspState 传 null：历史回放无法知道当时的语言服务器状态，只解析结果文本里已有的诊断块，
     * 不去声称「文件已检查且干净」。</p>
     */
    private void applyPresentation(Map<String, Object> turn, String name, String result, Map<String, Object> args) {
        ToolEndPayload payload = ToolEndPayload.builder()
                .name(name)
                .result(result)
                .args(args)
                .build();

        try {
            new ToolPresentationFilter().apply(WebEvent.of(WebEventNames.TOOL_END, payload));
        } catch (Throwable e) {
            // 展示层加工失败不能拖垮回放，退回未加工的原始结果
            turn.put("result", result);
            turn.put("endArgs", args);
            return;
        }

        turn.put("result", nullToEmpty(payload.getResult()));
        // tool.end 用加工后的 args（edits 已换成 diff、content/todos 已摘除）
        turn.put("endArgs", payload.getArgs());
        if (Assert.isNotEmpty(payload.getDiff())) {
            turn.put("diff", payload.getDiff());
        }
        if (payload.getLsp() != null) {
            turn.put("lsp", payload.getLsp());
        }
    }

    /**
     * 复制一份可变 args：过滤器会就地增删键，绝不能污染 WorkingMemory 里的 ToolCall.arguments
     * （那是会话与审计看到的模型原始参数）。
     */
    private Map<String, Object> copyArgs(Map<String, Object> args) {
        return (args == null) ? new HashMap<>() : new HashMap<>(args);
    }

    /**
     * 为指定 ToolCall 找到对应的结果消息。
     *
     * <p>优先按 {@code ToolMessage.toolCallId == ToolCall.id} 精确匹配；
     * 提供方未回传 id 时，退化为「同一批 ToolMessage 中的第 index 条」。</p>
     *
     * @param assistantIndex 发起调用的 AssistantMessage 下标
     * @param callIndex      该调用在本批 toolCalls 中的序号
     */
    private String findToolResult(List<ChatMessage> messages, int assistantIndex, ToolCall call, int callIndex) {
        String callId = call.getId();
        List<ToolMessage> batch = new ArrayList<>();

        // 紧随其后的连续 ToolMessage 即本批结果
        for (int i = assistantIndex + 1; i < messages.size(); i++) {
            ChatMessage next = messages.get(i);
            if (next instanceof ToolMessage) {
                batch.add((ToolMessage) next);
            } else {
                break;
            }
        }

        if (batch.isEmpty()) {
            return null;
        }

        if (Assert.isNotEmpty(callId)) {
            for (ToolMessage tm : batch) {
                if (callId.equals(tm.getToolCallId())) {
                    return nullToEmpty(tm.getContent());
                }
            }
        }

        // id 缺失或未命中：按名字 + 序号兜底
        if (callIndex < batch.size()) {
            ToolMessage tm = batch.get(callIndex);
            if (Assert.isEmpty(tm.getName()) || tm.getName().equals(call.getName())) {
                return nullToEmpty(tm.getContent());
            }
        }

        return null;
    }

    private String nullToEmpty(String s) {
        return (s == null) ? "" : s;
    }
}

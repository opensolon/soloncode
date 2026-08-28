package org.noear.solon.codecli.portal.web.service;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.portal.web.SteerInterceptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「最后一轮执行过程」还原逻辑的边界验证。
 *
 * <p>回放产出的是一条线性事件序列（steer / thinking / note / tool，严格保持 WorkingMemory 原序）。
 * 重点覆盖四条：往轮历史不能被误当成本轮过程、最终回答的正文不能重复回放（思考却必须回放）、
 * 一条聚合消息里的多段思考/正文必须分段保序、无过程可回放时退回 aligned=false 走原纯文本路径。</p>
 *
 * @author noear
 */
public class LastTraceServiceTest {
    private final LastTraceService service = new LastTraceService();

    @Test
    public void session_null_should_not_align() {
        Map<String, Object> data = service.buildLastTrace(null, AgentFlags.TRACE_KEY_MAIN, false, null);
        assertEquals(false, data.get("aligned"));
        assertTrue(((List<?>) data.get("events")).isEmpty());
    }

    @Test
    public void no_trace_in_context_should_not_align() {
        InMemoryAgentSession session = new InMemoryAgentSession("s1");
        Map<String, Object> data = service.buildLastTrace(session, AgentFlags.TRACE_KEY_MAIN, false, null);
        assertEquals(false, data.get("aligned"));
    }

    @Test
    public void plain_chat_without_process_should_not_align() {
        // 仅有往轮历史与本轮最终回答：两者都已由 ndjson 历史渲染，无过程可回放
        ReActTrace trace = new ReActTrace();
        addHistory(trace, ChatMessage.ofUser("上一轮的问题"));
        addHistory(trace, ChatMessage.ofAssistant("上一轮的回答"));
        addHistory(trace, ChatMessage.ofUser("你好"));
        addFinalAnswer(trace, "你好呀");

        Map<String, Object> data = service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null);
        assertEquals(false, data.get("aligned"), "无过程时应退回纯文本路径");
    }

    @Test
    public void earlier_run_messages_should_be_ignored() {
        /* 线性扫描不再靠「有无 toolCalls」天然排除历史上下文，必须靠 _runId 判别，
         * 否则整段历史会被重复渲染一遍。 */
        ReActTrace trace = new ReActTrace();
        AssistantMessage old = new AssistantMessage("<think>上一轮的思考</think>上一轮的回答");
        old.addMetadata(AgentTrace.META_RUN_ID, "run-old");
        old.addMetadata(AgentTrace.META_FIRST, 1);
        trace.getWorkingMemory().addMessage(old);

        ToolCall call = new ToolCall("0", "call_1", "read", "{}", new HashMap<>());
        trace.getWorkingMemory().addMessage(newToolCallMessage("本轮说明", call));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("ok", "read", "call_1"));

        List<Map<?, ?>> events = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null));
        assertEquals(Arrays.asList("note", "tool"), kinds(events), "往轮的思考与回答都不该出现");
        assertEquals("本轮说明", events.get(0).get("text"));
    }

    @Test
    public void tool_call_should_be_extracted_with_result() {
        ReActTrace trace = new ReActTrace();
        addHistory(trace, ChatMessage.ofUser("看下 pom"));

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "pom.xml");
        ToolCall call = new ToolCall("0", "call_1", "read", "{}", args);
        trace.getWorkingMemory().addMessage(newToolCallMessage("我来读一下", call));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("<project>...</project>", "read", "call_1"));
        addFinalAnswer(trace, "这是一个 Maven 项目");

        Map<String, Object> data = service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, true, null);
        assertEquals(true, data.get("aligned"));
        assertEquals(true, data.get("running"));

        List<Map<?, ?>> events = events(data);
        assertEquals(Arrays.asList("note", "tool"), kinds(events), "最终回答的正文不得重复回放");

        assertEquals("我来读一下", events.get(0).get("text"));

        Map<?, ?> turn = events.get(1);
        assertEquals("read", turn.get("name"));
        assertEquals("read", turn.get("title"));
        assertEquals(call.getUuid(), turn.get("callId"), "callId 必须是 ToolCall.uuid，与实时事件一致");
        assertEquals(true, turn.get("done"));
        assertEquals("<project>...</project>", turn.get("result"));
    }

    @Test
    public void system_tool_should_be_filtered() {
        ReActTrace trace = new ReActTrace();
        ToolCall call = new ToolCall("0", "call_1", "memory_extract", "{}", new HashMap<>());
        trace.getWorkingMemory().addMessage(newToolCallMessage(null, call));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("done", "memory_extract", "call_1"));

        Map<String, Object> data = service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null);
        assertEquals(false, data.get("aligned"), "系统侧工具被过滤后无过程可回放");
    }

    @Test
    public void filtered_tool_round_should_still_replay_its_text() {
        /* 早期实现只在真正吐出工具卡时才挂载思考/正文，于是整轮工具被过滤（内部工具 / 无 uuid）
         * 时那段思考与正文一并消失。线性扫描下它们独立成事件，不再依附工具卡。 */
        ReActTrace trace = new ReActTrace();
        ToolCall call = new ToolCall("0", "call_1", "memory_extract", "{}", new HashMap<>());
        trace.getWorkingMemory().addMessage(
                newToolCallMessage("<think>先记一笔</think>我记录一下", call));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("done", "memory_extract", "call_1"));

        List<Map<?, ?>> events = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null));
        assertEquals(Arrays.asList("thinking", "note"), kinds(events));
    }

    @Test
    public void task_tool_should_be_visible_in_replay() {
        /* 实时流隐藏 task，是因为子代理自己的工具事件会另外汇聚成 task-group；
         * 而子代理过程记在它自己的 trace 里，主 trace 只留下这一次调用。回放若也隐藏，
         * 整段子代理工作会凭空消失 —— 即用户看到的「有些工具调用没显示」。 */
        ReActTrace trace = new ReActTrace();
        ToolCall call = new ToolCall("0", "call_1", "task", "{}", new HashMap<>());
        trace.getWorkingMemory().addMessage(newToolCallMessage(null, call));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("子代理的最终产出", "task", "call_1"));

        Map<String, Object> data = service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null);
        assertEquals(true, data.get("aligned"));

        List<Map<?, ?>> events = events(data);
        assertEquals(1, events.size());
        assertEquals("task", events.get(0).get("name"));
        assertEquals("子代理的最终产出", events.get(0).get("result"));
    }

    @Test
    public void edit_tool_should_carry_diff_and_slim_args() {
        /* 实时流的 tool.end 走 ToolPresentationFilter：edits 被换算成统一 diff。
         * 回放若不走同一个过滤器，diff 视图就是空的 —— 即「显示了却没结果内容」。 */
        Map<String, Object> edit = new HashMap<>();
        edit.put("old_StrStartLine", 3);
        edit.put("old_str", "aaa");
        edit.put("new_str", "bbb");

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "a.txt");
        args.put("edits", Collections.singletonList(edit));

        ReActTrace trace = new ReActTrace();
        ToolCall call = new ToolCall("0", "call_1", "edit", "{}", args);
        trace.getWorkingMemory().addMessage(newToolCallMessage(null, call));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("修改成功", "edit", "call_1"));

        Map<?, ?> turn = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null)).get(0);

        String diff = (String) turn.get("diff");
        assertNotNull(diff, "edit 必须算出 diff");
        assertTrue(diff.contains("-aaa") && diff.contains("+bbb"), diff);

        Map<?, ?> endArgs = (Map<?, ?>) turn.get("endArgs");
        assertNull(endArgs.get("edits"), "加工后的 args 不再带 edits");

        Map<?, ?> startArgs = (Map<?, ?>) turn.get("args");
        assertNotNull(startArgs.get("edits"), "tool.start 仍用模型给的原始参数");
        assertNotNull(call.getArguments().get("edits"), "绝不能污染 WorkingMemory 里的原始参数");
    }

    @Test
    public void write_tool_result_should_be_content() {
        // 实时流把 args.content 提到 result 并从 args 摘除，回放必须一致
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "a.txt");
        args.put("content", "hello world");

        ReActTrace trace = new ReActTrace();
        ToolCall call = new ToolCall("0", "call_1", "write", "{}", args);
        trace.getWorkingMemory().addMessage(newToolCallMessage(null, call));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("文件成功写入: a.txt", "write", "call_1"));

        Map<?, ?> turn = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null)).get(0);
        assertEquals("hello world", turn.get("result"), "write 卡片展示的是写入内容");
        assertNull(((Map<?, ?>) turn.get("endArgs")).get("content"));
    }

    @Test
    public void unmatched_last_user_message_should_not_align() {
        ReActTrace trace = new ReActTrace();
        ToolCall call = new ToolCall("0", "call_1", "read", "{}", new HashMap<>());
        trace.getWorkingMemory().addMessage(newToolCallMessage(null, call));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("ok", "read", "call_1"));

        // originalPrompt 为空（fork 出来的会话 / 未跑过的快照）：给定 lastUserMsg 时必须判为不对齐
        Map<String, Object> data = service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, "另一条消息");
        assertEquals(false, data.get("aligned"));
    }

    @Test
    public void thinking_and_answer_should_be_split() {
        // 推理模型的一条消息里同时包含思考与正文，实时流是 thought.delta 与 message.delta
        // 两个事件，回放也必须拆开，否则思考内容会被当成答案铺在气泡里
        ReActTrace trace = new ReActTrace();
        ToolCall call = new ToolCall("0", "call_1", "read", "{}", new HashMap<>());
        trace.getWorkingMemory().addMessage(
                newToolCallMessage("<think>先看看工程结构</think>我来读一下", call));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("ok", "read", "call_1"));

        List<Map<?, ?>> events = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null));
        assertEquals(Arrays.asList("thinking", "note", "tool"), kinds(events));
        assertEquals("先看看工程结构", events.get(0).get("text"));
        assertEquals("我来读一下", events.get(1).get("text"), "正文必须是剥除 think 标签后的纯内容");
    }

    @Test
    public void alternating_think_segments_should_stay_separate() {
        /* 这是「几个思考消息和答案消息被合到一起」的根因用例。
         * text 里可以内嵌多对标签（非流式路径前置 reasoning / 旧快照遗留）：
         * 简单地取 getThinking()+getText() 只能拿到「一段思考 + 整段带标签正文」，
         * 第二段思考会被当成答案铺进气泡，段间也没有边界。 */
        ReActTrace trace = new ReActTrace();
        ToolCall call = new ToolCall("0", "call_1", "read", "{}", new HashMap<>());
        trace.getWorkingMemory().addMessage(
                newToolCallMessage("<think>T1</think>A1<think>T2</think>A2", call));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("ok", "read", "call_1"));

        List<Map<?, ?>> events = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null));
        assertEquals(Arrays.asList("thinking", "note", "thinking", "note", "tool"), kinds(events));
        assertEquals(Arrays.asList("T1", "A1", "T2", "A2"), texts(events.subList(0, 4)));
        // 同一条消息的所有段共享 group，前端据此聚成一个 reason 分组
        for (Map<?, ?> e : events) {
            assertEquals(1, e.get("group"));
        }
    }

    @Test
    public void thinking_message_without_tool_calls_should_be_replayed() {
        /* 推理模型可能把答案也写进 reasoning 通道：整条消息 isThinking=true，
         * 流末才补 </think>，且不带 toolCalls。早期以「工具轮」为回放单元时这类消息整条丢弃，
         * 刷新后一大段内容凭空消失（实时流里它是以 thought.delta 显示过的）。 */
        ReActTrace trace = new ReActTrace();
        ToolCall call = new ToolCall("0", "call_1", "read", "{}", new HashMap<>());
        trace.getWorkingMemory().addMessage(newToolCallMessage(null, call));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("ok", "read", "call_1"));
        trace.getWorkingMemory().addMessage(
                new AssistantMessage("", "思考连着答案都在这里", true));

        List<Map<?, ?>> events = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null));
        assertEquals(Arrays.asList("tool", "thinking"), kinds(events));
        assertEquals("思考连着答案都在这里", events.get(1).get("text"));
        assertEquals(2, events.get(1).get("group"), "它是另一条消息，必须换 group 否则会挤进已收尾的思考块");
    }

    @Test
    public void thinking_flag_must_not_swallow_answer_and_tool_calls() {
        /* 一条聚合消息可以同时有思考、正文和 toolCalls，且 isThinking 仍为 true ——
         * 该标记取自聚合时最后一帧的状态，只说明流末停在 reasoning 通道，不代表整条都是思考。
         * 早期实现在 isThinking 处短路，把整条丢给思考块，正文与工具卡一并被吞。
         *
         * 新形态下思考/正文分居 thinking/text 双通道，交替顺序已不可恢复：
         * 源串还原为 <think>T1T2</think>A1，切出「一段思考 + 一段正文」——
         * 思考在前是存储形态保留的唯一顺序信息（也是推理模型的实际行为）。 */
        ReActTrace trace = new ReActTrace();
        ToolCall call = new ToolCall("0", "call_1", "read", "{}", new HashMap<>());
        trace.getWorkingMemory().addMessage(new AssistantMessage("A1",
                "T1T2", true,
                null, null, Collections.singletonList(call), null));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("ok", "read", "call_1"));

        List<Map<?, ?>> events = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null));
        assertEquals(Arrays.asList("thinking", "note", "tool"), kinds(events));
        assertEquals(Arrays.asList("T1T2", "A1"), texts(events.subList(0, 2)));
    }

    @Test
    public void trailing_text_after_close_tag_is_answer_even_while_thinking() {
        /* 末帧在思考，但尾段已经被 </think> 界定过 —— 该段确实来自 content 通道，必须算正文。
         * isThinking 仅在「整条无任何标签」时才用于定性。 */
        ReActTrace trace = new ReActTrace();
        ToolCall call = new ToolCall("0", "call_1", "read", "{}", new HashMap<>());
        trace.getWorkingMemory().addMessage(new AssistantMessage(
                "我来读一下",
                "先想一下", true,
                null, null, Collections.singletonList(call), null));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("ok", "read", "call_1"));

        List<Map<?, ?>> events = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null));
        assertEquals(Arrays.asList("thinking", "note", "tool"), kinds(events));
        assertEquals("我来读一下", events.get(1).get("text"));
    }

    @Test
    public void untagged_content_falls_back_to_thinking_flag() {
        // 完全无标签时凭内容分不出通道，只能以末帧状态定性；同形态下 flag 为 false 则算正文
        ReActTrace trace = new ReActTrace();
        ToolCall call = new ToolCall("0", "call_1", "read", "{}", new HashMap<>());
        trace.getWorkingMemory().addMessage(newToolCallMessage("没标签的正文", call));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("ok", "read", "call_1"));
        trace.getWorkingMemory().addMessage(new AssistantMessage("","没标签的思考", true));

        List<Map<?, ?>> events = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null));
        assertEquals(Arrays.asList("note", "tool", "thinking"), kinds(events));
    }

    @Test
    public void dangling_think_open_should_not_be_lost() {
        /* 任务在思考中被打断：content 只有开标签没有闭合。此时 getThinking() 因不含 </think>
         * 返回空、getAnswer() 因含 <think> 也返回空，整段凭空消失。 */
        ReActTrace trace = new ReActTrace();
        ToolCall call = new ToolCall("0", "call_1", "read", "{}", new HashMap<>());
        trace.getWorkingMemory().addMessage(newToolCallMessage("<think>刚想到一半就被打断", call));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("ok", "read", "call_1"));

        List<Map<?, ?>> events = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null));
        assertEquals(Arrays.asList("thinking", "tool"), kinds(events));
        assertEquals("刚想到一半就被打断", events.get(0).get("text"));
    }

    @Test
    public void close_tag_without_open_should_be_thinking() {
        // 开标签在裁剪 / 序列化往返中丢失时，前半段仍应按思考处理而不是铺进气泡
        ReActTrace trace = new ReActTrace();
        ToolCall call = new ToolCall("0", "call_1", "read", "{}", new HashMap<>());
        trace.getWorkingMemory().addMessage(newToolCallMessage("孤立的思考</think>随后的正文", call));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("ok", "read", "call_1"));

        List<Map<?, ?>> events = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null));
        assertEquals(Arrays.asList("thinking", "note", "tool"), kinds(events));
        assertEquals(Arrays.asList("孤立的思考", "随后的正文"), texts(events.subList(0, 2)));
    }

    @Test
    public void events_should_carry_group_number() {
        // 前端按 group 生成 reasonId：同消息聚为一组，跨消息必须换组，
        // 否则后一条的思考会挤进已收尾的思考块
        ReActTrace trace = new ReActTrace();
        ToolCall c1 = new ToolCall("0", "call_1", "read", "{}", new HashMap<>());
        ToolCall c2 = new ToolCall("0", "call_2", "read", "{}", new HashMap<>());
        ToolCall c3 = new ToolCall("1", "call_3", "grep", "{}", new HashMap<>());

        trace.getWorkingMemory().addMessage(
                new AssistantMessage("","", false, null, null, Arrays.asList(c1, c2), null));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("a", "read", "call_1"));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("b", "read", "call_2"));
        trace.getWorkingMemory().addMessage(newToolCallMessage(null, c3));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("c", "grep", "call_3"));

        List<Map<?, ?>> events = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null));
        assertEquals(3, events.size());
        assertEquals(1, events.get(0).get("group"));
        assertEquals(1, events.get(1).get("group"), "同一批并行调用属于同一组");
        assertEquals(2, events.get(2).get("group"));
        assertEquals("a", events.get(0).get("result"));
        assertEquals("b", events.get(1).get("result"), "并行调用的结果不得错位");
    }

    @Test
    public void steer_should_be_replayed_in_place() {
        /* 插话零持久化（不写 ndjson），唯一留存处就是 WorkingMemory 里那条带前缀的 user 消息。
         * 它由 onReasonStart 注入，故在序列中排在「受它影响的那一轮」之前 —— 必须按原位回放，
         * 早期实现把它挂到后继工具轮上，两条插话会被挤到一起而丢失真实间隔。 */
        ReActTrace trace = new ReActTrace();
        ToolCall c1 = new ToolCall("0", "call_1", "read", "{}", new HashMap<>());
        ToolCall c2 = new ToolCall("1", "call_2", "grep", "{}", new HashMap<>());

        trace.getWorkingMemory().addMessage(newToolCallMessage(null, c1));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("a", "read", "call_1"));
        ChatMessage steer = ChatMessage.ofUser(SteerInterceptor.STEER_PREFIX + "改用 grep 找");
        steer.addMetadata("source", SteerInterceptor.STEER_SOURCE);
        trace.getWorkingMemory().addMessage(steer);
        trace.getWorkingMemory().addMessage(newToolCallMessage(null, c2));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("b", "grep", "call_2"));

        List<Map<?, ?>> events = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null));
        assertEquals(Arrays.asList("tool", "steer", "tool"), kinds(events));
        assertEquals("改用 grep 找", events.get(1).get("text"), "必须剥掉注入前缀，还原用户原话");
    }

    @Test
    public void steer_without_metadata_should_be_recognized_by_prefix() {
        // 快照序列化往返后 metadata 未必保留，前缀写在正文里必存，故须能单靠前缀识别
        ReActTrace trace = new ReActTrace();
        ToolCall c1 = new ToolCall("0", "call_1", "read", "{}", new HashMap<>());

        trace.getWorkingMemory().addMessage(newToolCallMessage(null, c1));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("a", "read", "call_1"));
        trace.getWorkingMemory().addMessage(ChatMessage.ofUser(SteerInterceptor.STEER_PREFIX + "别改测试"));
        trace.getWorkingMemory().addMessage(new AssistantMessage("","好的", true));

        List<Map<?, ?>> events = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null));
        assertEquals(Arrays.asList("tool", "steer", "thinking"), kinds(events));
        assertEquals("别改测试", events.get(1).get("text"));
    }

    @Test
    public void steer_before_final_answer_should_be_kept() {
        // 注入在「产出最终回答」那一轮之前的插话没有后继工具轮，早期实现只能整体挤到尾部；
        // 线性回放下它就落在最终回答之前的真实位置
        ReActTrace trace = new ReActTrace();
        ToolCall c1 = new ToolCall("0", "call_1", "read", "{}", new HashMap<>());

        trace.getWorkingMemory().addMessage(newToolCallMessage(null, c1));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("a", "read", "call_1"));
        trace.getWorkingMemory().addMessage(ChatMessage.ofUser(SteerInterceptor.STEER_PREFIX + "直接给结论"));
        addFinalAnswer(trace, "结论是……");

        List<Map<?, ?>> events = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null));
        assertEquals(Arrays.asList("tool", "steer"), kinds(events));
        assertEquals("直接给结论", events.get(1).get("text"));
    }

    @Test
    public void prefixed_message_before_first_assistant_should_not_be_steer() {
        // WorkingMemory 开头是 ndjson 载入的历史上下文。存量会话里已持久化的旧插话行
        // 已由历史渲染成独立 .msg-row，再回放一次就是重复；守卫 1 保证真插话必在首轮之后
        ReActTrace trace = new ReActTrace();
        ToolCall c1 = new ToolCall("0", "call_1", "read", "{}", new HashMap<>());

        trace.getWorkingMemory().addMessage(ChatMessage.ofUser(SteerInterceptor.STEER_PREFIX + "上一轮的旧插话"));
        trace.getWorkingMemory().addMessage(newToolCallMessage(null, c1));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("a", "read", "call_1"));

        List<Map<?, ?>> events = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null));
        assertEquals(Collections.singletonList("tool"), kinds(events));
    }

    @Test
    public void final_answer_thinking_should_be_replayed_without_its_note() {
        /* 最终回答的正文已落 ndjson 由历史渲染，重复输出就是两份答案；
         * 但它的思考只存在于 WorkingMemory（历史渲染时被 stripThinkTags 剥掉），必须回放。 */
        ReActTrace trace = new ReActTrace();
        ToolCall c1 = new ToolCall("0", "call_1", "read", "{}", new HashMap<>());
        trace.getWorkingMemory().addMessage(newToolCallMessage(null, c1));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("a", "read", "call_1"));
        addFinalAnswer(trace, "<think>最后梳理一遍</think>这是答案");

        List<Map<?, ?>> events = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null));
        assertEquals(Arrays.asList("tool", "thinking"), kinds(events));
        assertEquals("最后梳理一遍", events.get(1).get("text"));
    }

    @Test
    public void steer_alone_should_not_align() {
        // 插话不能单独支撑回放：它依附于 AI 的思考/工具过程才有语境
        ReActTrace trace = new ReActTrace();
        trace.getWorkingMemory().addMessage(new AssistantMessage("","", false));
        trace.getWorkingMemory().addMessage(ChatMessage.ofUser(SteerInterceptor.STEER_PREFIX + "换个思路"));

        Map<String, Object> data = service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null);
        assertEquals(false, data.get("aligned"));
    }

    @Test
    public void blank_segments_should_be_dropped() {
        // 标签之间只有换行时不该产出空段（上屏会变成一个空气泡）
        ReActTrace trace = new ReActTrace();
        ToolCall call = new ToolCall("0", "call_1", "read", "{}", new HashMap<>());
        trace.getWorkingMemory().addMessage(newToolCallMessage("\n<think>想一下</think>\n\n", call));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("ok", "read", "call_1"));

        List<Map<?, ?>> events = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null));
        assertEquals(Arrays.asList("thinking", "tool"), kinds(events));
    }

    @Test
    public void real_session_web_mt9xbfvb_shape_should_replay_in_order() {
        /* 定点回归：按会话 web-mt9xbfvb 快照里的真实形态重建 WorkingMemory。
         * 该会话里模型把答案写进了 reasoning 通道（两条 isThinking=true 且无 toolCalls 的消息），
         * 早期实现以「工具轮」为回放单位时这两大段内容整条丢弃，两条插话又双双落进尾部
         * 挤在一起 —— 即用户看到的「几个思考消息和答案消息合到了一起」。 */
        ReActTrace trace = new ReActTrace();
        String runId = trace.getRunId();

        // #0 本轮任务提示（已落 ndjson）
        ChatMessage goal = ChatMessage.ofUser("查下杭州明天的天气");
        goal.addMetadata(AgentTrace.META_RUN_ID, runId);
        goal.addMetadata(AgentTrace.META_FIRST, 1);
        trace.getWorkingMemory().addMessage(goal);

        // #1 思考 + 工具调用
        ToolCall call = new ToolCall("0", "call_1", "websearch", "{}", new HashMap<>());
        trace.getWorkingMemory().addMessage(newToolCallMessage("<think>需要实时信息，搜一下。</think>", call));
        // #2 工具结果
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("杭州天气…", "websearch", "call_1"));
        // #3 插话
        ChatMessage steer1 = ChatMessage.ofUser(SteerInterceptor.STEER_PREFIX + "去哪儿玩好？");
        steer1.addMetadata("source", SteerInterceptor.STEER_SOURCE);
        trace.getWorkingMemory().addMessage(steer1);
        // #4 isThinking=true，思考与答案同在一对标签内，无 toolCalls
        trace.getWorkingMemory().addMessage(new AssistantMessage("","雨天推荐…", true));
        // #5 ReasonTask 注入的格式修正指令（系统内务，不得上屏）
        trace.getWorkingMemory().addMessage(ChatMessage.ofUser("【系统指令：输出格式修正】…"));
        // #6 第二条插话
        ChatMessage steer2 = ChatMessage.ofUser(SteerInterceptor.STEER_PREFIX + "要多少钱？");
        steer2.addMetadata("source", SteerInterceptor.STEER_SOURCE);
        trace.getWorkingMemory().addMessage(steer2);
        // #7 同 #4 形态
        trace.getWorkingMemory().addMessage(new AssistantMessage("","花费大概…", true));
        // #8 再一条格式修正指令
        trace.getWorkingMemory().addMessage(ChatMessage.ofUser("【系统指令：输出格式修正】…"));
        // #9 最终回答（已落 ndjson）
        addFinalAnswer(trace, "杭州明天…");

        List<Map<?, ?>> events = events(service.buildLastTrace(newSession(trace), AgentFlags.TRACE_KEY_MAIN, false, null));
        assertEquals(Arrays.asList("thinking", "tool", "steer", "thinking", "steer", "thinking"),
                kinds(events), "两条插话不得相邻，两大段纯思考内容不得丢失");
        assertEquals("去哪儿玩好？", events.get(2).get("text"));
        assertEquals("要多少钱？", events.get(4).get("text"));
        // 三段思考分属三条消息，必须是三个不同的 group（否则会被拼进同一个折叠块）
        assertEquals(1, events.get(0).get("group"));
        assertEquals(2, events.get(3).get("group"));
        assertEquals(3, events.get(5).get("group"));
    }

    /** 构造一条「带 toolCalls 的助手消息」——即模型发起工具调用的那一轮 */
    private AssistantMessage newToolCallMessage(String content, ToolCall call) {
        return new AssistantMessage(content, "", false, null, null, Collections.singletonList(call), null);
    }

    /** 模拟 ndjson 载入的往轮消息：带别的 runId 与「初心」标记 */
    private void addHistory(ReActTrace trace, ChatMessage msg) {
        msg.addMetadata(AgentTrace.META_RUN_ID, "run-old");
        msg.addMetadata(AgentTrace.META_FIRST, 1);
        trace.getWorkingMemory().addMessage(msg);
    }

    /** 模拟本轮最终回答：带当前 runId（与落 ndjson 同点打上） */
    private void addFinalAnswer(ReActTrace trace, String content) {
        AssistantMessage msg = new AssistantMessage(content);
        msg.addMetadata(AgentTrace.META_RUN_ID, trace.getRunId());
        trace.getWorkingMemory().addMessage(msg);
    }

    private InMemoryAgentSession newSession(ReActTrace trace) {
        InMemoryAgentSession session = new InMemoryAgentSession("s1");
        session.getContext().put(AgentFlags.TRACE_KEY_MAIN, trace);
        return session;
    }

    @SuppressWarnings("unchecked")
    private List<Map<?, ?>> events(Map<String, Object> data) {
        List<Map<?, ?>> out = new ArrayList<>();
        for (Object o : (List<Object>) data.get("events")) {
            out.add((Map<?, ?>) o);
        }
        return out;
    }

    private List<String> kinds(List<Map<?, ?>> events) {
        List<String> out = new ArrayList<>();
        for (Map<?, ?> e : events) {
            out.add(String.valueOf(e.get("kind")));
        }
        return out;
    }

    private List<String> texts(List<Map<?, ?>> events) {
        List<String> out = new ArrayList<>();
        for (Map<?, ?> e : events) {
            out.add(String.valueOf(e.get("text")));
        }
        return out;
    }
}

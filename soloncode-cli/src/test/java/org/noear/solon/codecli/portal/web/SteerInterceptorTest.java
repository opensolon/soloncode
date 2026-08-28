package org.noear.solon.codecli.portal.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SteerInterceptor 单元测试：守卫体系、方案 A 注入语义、onAgentEnd 兜底与清理。
 *
 * <p>覆盖：首轮不注入、非首轮注入（workingMemory + 邮箱清空 + runId 记录）、
 * tool_calls 未闭合跳过、任务结束残留转 dropped、正常清理。</p>
 */
public class SteerInterceptorTest {

    private AgentSession session;
    private ReActTrace trace;
    private SteerInterceptor interceptor;

    @BeforeEach
    public void setUp() {
        session = InMemoryAgentSession.of();
        trace = new ReActTrace() {
            @Override
            public AgentSession getSession() {
                return session;
            }
        };
        // webGate/wsContext 传 null：事件推送与展示记录为 no-op，聚焦注入逻辑
        interceptor = new SteerInterceptor(null, null);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentLinkedQueue<String> newBox(String... texts) {
        ConcurrentLinkedQueue<String> box = new ConcurrentLinkedQueue<>();
        for (String t : texts) {
            box.offer(t);
        }
        session.attrs().put(SteerInterceptor.ATTR_STEER_BOX, box);
        return box;
    }

    private static AssistantMessage toolCallMessage() {
        String json = "{" +
                "  \"role\": \"assistant\"," +
                "  \"toolCalls\": [{" +
                "    \"id\": \"c1\"," +
                "    \"name\": \"bash\"," +
                "    \"arguments\": {}" +
                "  }]" +
                "}";
        return (AssistantMessage) ChatMessage.fromJson(json);
    }

    @Test
    @DisplayName("守卫1：首轮（turnCount=1）不注入，邮箱保留到下一轮")
    public void firstTurn_notInjected() {
        ConcurrentLinkedQueue<String> box = newBox("改用方案B");
        trace.nextTurn(); // 首轮

        StringBuilder sp = new StringBuilder("base");
        interceptor.onReasonStart(trace, sp);

        assertTrue(trace.getWorkingMemory().isEmpty(), "首轮不应注入工作记忆");
        assertEquals(1, box.size(), "邮箱应保留");
        assertEquals("base", sp.toString(), "systemPrompt 不应追加说明");
        assertNotNull(session.attrs().get(SteerInterceptor.ATTR_ACTIVE_RUN_ID), "runId 仍应被记录");
    }

    @Test
    @DisplayName("非首轮：注入工作记忆（带前缀+metadata）、不污染 systemPrompt、清空邮箱、记录 runId")
    public void laterTurn_injected() {
        ConcurrentLinkedQueue<String> box = newBox("改用方案B", "注意性能");
        trace.nextTurn();
        trace.nextTurn(); // 第二轮

        StringBuilder sp = new StringBuilder("base");
        interceptor.onReasonStart(trace, sp);

        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
        assertEquals(2, messages.size());
        assertTrue(messages.get(0).getContent().contains(SteerInterceptor.STEER_PREFIX));
        assertTrue(messages.get(0).getContent().contains("改用方案B"));
        assertEquals("steer", messages.get(0).getMetadata().get("source"));
        assertEquals("user", messages.get(0).getRole().name().toLowerCase());
        // 注入消息自带 STEER_PREFIX 已足够表意，不再追加 systemPrompt（避免重复提示）
        assertEquals("base", sp.toString(), "systemPrompt 不应被修改");
        assertTrue(box.isEmpty(), "注入后邮箱应清空");
        assertEquals(trace.getRunId(), session.attrs().get(SteerInterceptor.ATTR_ACTIVE_RUN_ID));
    }

    @Test
    @DisplayName("守卫3：工作记忆尾部 tool_calls 未闭合（HITL 挂起窄窗）跳过注入")
    public void openToolCalls_skipped() {
        ConcurrentLinkedQueue<String> box = newBox("纠偏");
        trace.getWorkingMemory().addMessage(toolCallMessage());
        trace.nextTurn();
        trace.nextTurn();

        interceptor.onReasonStart(trace, new StringBuilder());

        assertEquals(1, trace.getWorkingMemory().getMessages().size(), "不应注入");
        assertEquals(1, box.size(), "邮箱应保留");
    }

    @Test
    @DisplayName("守卫3豁免：tool_calls 已有结果消息（正常 action 收口后）正常注入")
    public void closedToolCalls_injected() {
        newBox("继续");
        Prompt wm = trace.getWorkingMemory();
        wm.addMessage(toolCallMessage());
        wm.addMessage(ChatMessage.ofTool("done", "bash", "c1"));
        trace.nextTurn();
        trace.nextTurn();

        interceptor.onReasonStart(trace, new StringBuilder());

        assertEquals(3, wm.getMessages().size(), "tool+result 之外应新增 1 条注入");
        assertTrue(wm.getMessages().get(2).getContent().contains("继续"));
    }

    @Test
    @DisplayName("空邮箱零行为：不注入、不追加 systemPrompt")
    public void emptyBox_noop() {
        trace.nextTurn();
        trace.nextTurn();
        StringBuilder sp = new StringBuilder("x");
        interceptor.onReasonStart(trace, sp);
        assertEquals("x", sp.toString());
        assertTrue(trace.getWorkingMemory().isEmpty());
    }

    @Test
    @DisplayName("onAgentEnd 残留兜底：未消费文本清空邮箱并清理 attrs（dropped 事件由通知通道发出）")
    public void agentEnd_droppedAndCleaned() {
        ConcurrentLinkedQueue<String> box = newBox("未消费1", "未消费2");

        interceptor.onAgentEnd(trace);

        assertTrue(box.isEmpty(), "残留应被清出（交由前端转排队）");
        assertNull(session.attrs().get(SteerInterceptor.ATTR_STEER_BOX), "邮箱标记应移除");
        assertNull(session.attrs().get(SteerInterceptor.ATTR_ACTIVE_RUN_ID), "runId 标记应移除");
    }

    @Test
    @DisplayName("onAgentEnd 无残留：静默清理，不抛异常")
    public void agentEnd_noResidue_clean() {
        interceptor.onAgentEnd(trace);
        assertNull(session.attrs().get(SteerInterceptor.ATTR_STEER_BOX));
    }
}

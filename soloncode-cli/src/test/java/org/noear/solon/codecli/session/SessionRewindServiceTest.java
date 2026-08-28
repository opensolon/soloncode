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
package org.noear.solon.codecli.session;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link SessionRewindService} 单测。
 *
 * <p>重点覆盖两类历史缺陷：一是「按 DOM 行数删」导致的多删/少删，二是链安全逻辑下
 * {@code windowSize} 被当成条数传入造成的越过锚点超删。</p>
 *
 * @author noear
 */
public class SessionRewindServiceTest {
    private static final String TRACE_KEY = "__main";

    private final SessionRewindService service = new SessionRewindService();

    /** 按 assistant 锚点删除：同一轮的用户消息保留，只删该轮回答。 */
    @Test
    public void anchorAssistant_keepsSameRunUserMessage() {
        AgentSession session = newSession();
        session.addMessage(Collections.singletonList(user("u1", "r1")));
        session.addMessage(Collections.singletonList(assistant("a1", "r1")));
        session.addMessage(Collections.singletonList(user("u2", "r2")));
        session.addMessage(Collections.singletonList(assistant("a2", "r2")));

        SessionRewindService.RewindResult rr = service.rewind(session, TRACE_KEY, "r2", "assistant", 99);

        Assertions.assertFalse(rr.isAnchorMissing());
        Assertions.assertFalse(rr.isDegraded());
        Assertions.assertEquals(1, rr.getRemoved());
        Assertions.assertEquals(3, rr.getEffectiveAnchor());

        List<ChatMessage> left = session.getMessages();
        Assertions.assertEquals(3, left.size());
        Assertions.assertEquals("u2", left.get(2).getContent());
    }

    /** 按 user 锚点删除：整轮（用户输入 + 回答）一起删。 */
    @Test
    public void anchorUser_removesWholeRound() {
        AgentSession session = newSession();
        session.addMessage(Collections.singletonList(user("u1", "r1")));
        session.addMessage(Collections.singletonList(assistant("a1", "r1")));
        session.addMessage(Collections.singletonList(user("u2", "r2")));
        session.addMessage(Collections.singletonList(assistant("a2", "r2")));

        SessionRewindService.RewindResult rr = service.rewind(session, TRACE_KEY, "r2", "user", 99);

        Assertions.assertEquals(2, rr.getRemoved());
        Assertions.assertEquals(2, session.getMessages().size());
        Assertions.assertEquals("a1", session.getMessages().get(1).getContent());
    }

    /**
     * 工具链场景下不得越过锚点超删。
     *
     * <p>{@code [U0,A0, U1,A1(toolCalls),T1,A1final]} 删除 r1 整轮：若把「条数 4」直接当
     * {@code windowSize} 传给 {@code removeLatestMessage}，删 T1 时会连带回收 A1(toolCalls)
     * 却不消耗迭代次数，第 4 次迭代就会把 U1 之前的 A0 也删掉。</p>
     */
    @Test
    public void toolChain_doesNotOverDeleteAcrossAnchor() {
        AgentSession session = newSession();
        session.addMessage(Collections.singletonList(user("u0", "r0")));
        session.addMessage(Collections.singletonList(assistant("a0", "r0")));
        session.addMessage(Collections.singletonList(user("u1", "r1")));
        session.addMessage(Collections.singletonList(toolCallAssistant("r1")));
        session.addMessage(Collections.singletonList(ChatMessage.ofTool("sunny", "getWeather", "call_1")));
        session.addMessage(Collections.singletonList(assistant("a1", "r1")));

        SessionRewindService.RewindResult rr = service.rewind(session, TRACE_KEY, "r1", "user", 99);

        Assertions.assertEquals(4, rr.getRemoved());
        Assertions.assertEquals(2, rr.getEffectiveAnchor());

        List<ChatMessage> left = session.getMessages();
        Assertions.assertEquals(2, left.size());
        Assertions.assertEquals("u0", left.get(0).getContent());
        Assertions.assertEquals("a0", left.get(1).getContent());
    }

    /** 锚点未命中：一条都不删（调用方据此让前端重载历史，而不是按条数猜）。 */
    @Test
    public void anchorMissing_removesNothing() {
        AgentSession session = newSession();
        session.addMessage(Collections.singletonList(user("u1", "r1")));
        session.addMessage(Collections.singletonList(assistant("a1", "r1")));

        SessionRewindService.RewindResult rr = service.rewind(session, TRACE_KEY, "r-none", "assistant", 2);

        Assertions.assertTrue(rr.isAnchorMissing());
        Assertions.assertEquals(0, rr.getRemoved());
        Assertions.assertEquals(2, session.getMessages().size());
    }

    /** 老数据无 runId：按条数降级删除，并标记 degraded 供前端提示。 */
    @Test
    public void noAnchor_fallsBackToCount() {
        AgentSession session = newSession();
        session.addMessage(Collections.singletonList(ChatMessage.ofUser("u1")));
        session.addMessage(Collections.singletonList(ChatMessage.ofAssistant("a1")));
        session.addMessage(Collections.singletonList(ChatMessage.ofUser("u2")));
        session.addMessage(Collections.singletonList(ChatMessage.ofAssistant("a2")));

        SessionRewindService.RewindResult rr = service.rewind(session, TRACE_KEY, null, null, 2);

        Assertions.assertTrue(rr.isDegraded());
        Assertions.assertEquals(2, rr.getRemoved());
        Assertions.assertEquals(2, session.getMessages().size());
    }

    /** 轨迹属于被删的那一轮：清掉，否则刷新后已删的思考与工具卡会回放长回来。 */
    @Test
    public void trace_clearedWhenItsRunDeleted() {
        AgentSession session = newSession();
        session.addMessage(Collections.singletonList(user("u1", "r1")));
        session.addMessage(Collections.singletonList(assistant("a1", "r1")));
        session.getContext().put(TRACE_KEY, traceOf("r1"));

        service.rewind(session, TRACE_KEY, "r1", "user", 99);

        Assertions.assertNull(session.getContext().get(TRACE_KEY));
    }

    /** 轨迹属于保留下来的更早一轮：留着它，回放仍然有效。 */
    @Test
    public void trace_keptWhenItsRunSurvives() {
        AgentSession session = newSession();
        session.addMessage(Collections.singletonList(user("u1", "r1")));
        session.addMessage(Collections.singletonList(assistant("a1", "r1")));
        session.addMessage(Collections.singletonList(user("u2", "r2")));
        session.addMessage(Collections.singletonList(assistant("a2", "r2")));
        session.getContext().put(TRACE_KEY, traceOf("r1"));

        service.rewind(session, TRACE_KEY, "r2", "user", 99);

        Assertions.assertNotNull(session.getContext().get(TRACE_KEY));
    }

    // ==================== helpers ====================

    private AgentSession newSession() {
        return new InMemoryAgentSession("s1");
    }

    private ChatMessage user(String content, String runId) {
        ChatMessage msg = ChatMessage.ofUser(content);
        msg.addMetadata(AgentTrace.META_RUN_ID, runId);
        return msg;
    }

    private ChatMessage assistant(String content, String runId) {
        ChatMessage msg = ChatMessage.ofAssistant(content);
        msg.addMetadata(AgentTrace.META_RUN_ID, runId);
        return msg;
    }

    private ChatMessage toolCallAssistant(String runId) {
        Map<String, Object> funcMap = new LinkedHashMap<>();
        Map<String, String> funcNameMap = new LinkedHashMap<>();
        funcNameMap.put("name", "getWeather");
        funcMap.put("function", funcNameMap);

        AssistantMessage msg = new AssistantMessage("", "", false, null,
                Collections.singletonList(funcMap),
                Collections.singletonList(new ToolCall("0", "call_1", "getWeather", "{}", new LinkedHashMap<>())),
                null);
        msg.addMetadata(AgentTrace.META_RUN_ID, runId);
        return msg;
    }

    private ReActTrace traceOf(String runId) {
        ReActTrace trace = new ReActTrace();
        // runId 无 setter：借「懒生成」之外的唯一入口 —— 反射写入，仅测试用
        try {
            java.lang.reflect.Field field = ReActTrace.class.getDeclaredField("runId");
            field.setAccessible(true);
            field.set(trace, runId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return trace;
    }
}

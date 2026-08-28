package org.noear.solon.codecli.portal.web;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.core.util.Assert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * 运行中插话拦截器（Steering）
 *
 * <p>参考 Codex 的 steering 设计：用户在任务运行中插入的纠偏/补充消息存放在
 * {@link AgentSession#attrs()} 的邮箱队列（transient，不落 snapshot.json，任务级易失），
 * 由本拦截器在 {@code onReasonStart}（消息组装前的采样边界）排空并注入工作记忆。
 * 不打断进行中的模型流与工具调用；注入的消息只进当前任务的工作记忆，
 * 任务结束即失效。</p>
 *
 * <p><b>零持久化</b>：插话完全不写入 *.messages.ndjson，也不经 {@code session.addMessage}。
 * 因此它不占用 sessionWindowSize 名额、不会在后续任务中被当作初心（META_FIRST）重新入场，
 * 刷新页面后插话气泡即消失（刻意取舍）。展示层由前端在收到 steer_applied 时就地渲染进
 * 当前 AI 流式气泡内部，属纯客户端呈现。与上游 Codex 的差异：Codex 双写
 * （response_item + event_msg）且进模型上下文、重启后仍保留，我们不做。</p>
 *
 * <p>守卫体系（对齐 Codex 已踩过的坑）：</p>
 * <ul>
 *   <li>守卫 1：首轮 reason 不注入，确保本轮原始 prompt 先被采样，不篡改初始意图；</li>
 *   <li>守卫 3：工作记忆尾部存在未闭合 tool_calls（HITL 挂起恢复窄窗）时跳过，
 *       避免 user 消息打断 tool_use/tool_result 配对导致部分模型报错；</li>
 *   <li>压缩共存：请求级挂载（LinkedHashMap 插入序）天然排在默认的
 *       ContextCompressionInterceptor 之后，向压缩产物尾部追加，不破坏消息序列；</li>
 *   <li>无悬挂：{@code onAgentEnd} 检查残留并广播 steer_dropped，前端转为排队消息，
 *       绝不允许"已接受但永不生效"（Codex issue #15842 教训）。</li>
 * </ul>
 *
 * @author noear
 * @since 2026
 */
@Slf4j
public class SteerInterceptor implements ReActInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(SteerInterceptor.class);
    /** 会话级插话邮箱 key（attrs 为 transient Map，不落 snapshot.json） */
    public static final String ATTR_STEER_BOX = "web.steerBox";
    /** 当前运行任务 runId（供 steer API 比对，防跨任务错投） */
    public static final String ATTR_ACTIVE_RUN_ID = "web.activeRunId";
    /** 邮箱容量上限（Codex 建议：频繁 steer 加速上下文膨胀，高频场景应改用排队） */
    public static final int MAX_BOX_SIZE = 5;
    /** 单条插话长度上限 */
    public static final int MAX_TEXT_LENGTH = 4096;

    /** 注入到工作记忆的消息前缀，向模型标识这是运行中的用户补充。
     * 同时是 LastTraceService 回放时识别插话的兜底判据（metadata 可能在快照序列化中丢失） */
    public static final String STEER_PREFIX = "[用户实时补充] ";

    /** 插话消息的 metadata 标记值（{@code source=steer}） */
    public static final String STEER_SOURCE = "steer";

    private final WebGate webGate;
    private final WorkspaceContext wsContext;

    public SteerInterceptor(WebGate webGate, WorkspaceContext wsContext) {
        this.webGate = webGate;
        this.wsContext = wsContext;
    }

    @Override
    public void onReasonStart(ReActTrace trace, StringBuilder systemPromptBuf) {
        AgentSession session = trace.getSession();
        if (session == null) {
            return;
        }

        // 记录本次任务的 runId（getRunId 惰性且幂等，与事件流的 runId 同源），
        // 供 steer 接口比对。首轮 reason 之前提交的 steer 不受影响（此时比对端为 null，按接受处理）
        session.attrs().put(ATTR_ACTIVE_RUN_ID, trace.getRunId());

        Queue<String> box = steerBox(session);
        if (box == null || box.isEmpty()) {
            return;
        }

        // 守卫 1：首轮不注入——原始 prompt 必须先被采样
        if (trace.getTurnCount() <= 1) {
            return;
        }

        // 守卫 3：尾部 tool_calls 未闭合不注入（等下一轮，ActionTask 补齐结果后自然恢复）
        if (hasOpenToolCalls(trace)) {
            return;
        }

        List<String> texts = drain(box);
        if (texts.isEmpty()) {
            return;
        }

        for (String text : texts) {
            ChatMessage message = ChatMessage.ofUser(STEER_PREFIX + text);
            message.addMetadata("source", STEER_SOURCE);
            trace.getWorkingMemory().addMessage(message);
        }

        // 不追加 systemPrompt 说明：注入消息自带 STEER_PREFIX 已足够表意，避免重复提示

        // 必须广播 applied：它是「注入已生效」的唯一信号。前端据此清除待生效态并落气泡；
        // 若不发，前端 finishStream 的防御定时器会把已执行过的插话当作未消费重新入队，导致重复执行
        emitSteer(session, WebEvent.ofSteerApplied(trace.getRunId(), texts));
    }

    @Override
    public void onAgentEnd(ReActTrace trace) {
        AgentSession session = trace.getSession();
        if (session == null) {
            return;
        }

        // 先摘下邮箱再排空（方案 A：任务结束即失效）。顺序不可颠倒：
        // 若先 drain 再 remove，落在两步之间的 offer 会随 remove 静默丢失。
        // 摘下后并发到达的 offer 会写进一个新建的孤儿队列，由 steer 接口的 offer 后复查兜住
        @SuppressWarnings("unchecked")
        Queue<String> box = (Queue<String>) session.attrs().remove(ATTR_STEER_BOX);
        session.attrs().remove(ATTR_ACTIVE_RUN_ID);

        if (box != null && !box.isEmpty()) {
            // 残留兜底：任务已结束（单轮直接回答、守卫持续跳过后 END 等），
            // 未消费的插话绝不能静默丢弃——广播 dropped，前端转为排队消息发送
            List<String> dropped = drain(box);
            if (!dropped.isEmpty()) {
                emitSteer(session, WebEvent.ofSteerDropped(trace.getRunId(), dropped));
            }
        }
    }

    /**
     * 获取（不创建）会话插话邮箱
     */
    @SuppressWarnings("unchecked")
    public static Queue<String> steerBox(AgentSession session) {
        return (Queue<String>) session.attrs().get(ATTR_STEER_BOX);
    }

    private static List<String> drain(Queue<String> box) {
        List<String> texts = new ArrayList<>();
        for (String text; (text = box.poll()) != null; ) {
            texts.add(text);
        }
        return texts;
    }

    private static boolean hasOpenToolCalls(ReActTrace trace) {
        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
        if (messages.isEmpty()) {
            return false;
        }
        ChatMessage last = messages.get(messages.size() - 1);
        return last instanceof AssistantMessage
                && Assert.isNotEmpty(((AssistantMessage) last).getToolCalls());
    }

    /**
     * 推送插话状态事件到前端（steer_applied / steer_dropped）
     */
    private void emitSteer(AgentSession session, WebEvent<?> event) {
        try {
            if (webGate != null && wsContext != null) {
                webGate.emitToClient(wsContext, session.getSessionId(), event);
            }
        } catch (Throwable e) {
            LOG.warn("[SteerInterceptor] emit steer event failed: {}", e.toString());
        }
    }

}

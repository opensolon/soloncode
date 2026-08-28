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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话回退服务：删除会话中「某条消息及其之后」的全部消息。
 *
 * <p>删除动作<b>只允许</b>经由 {@link AgentSession#removeLatestMessage(int)}，不得再直接改写
 * {@code *.messages.ndjson}。文件层是 {@code FileAgentSession} 的实现细节：它是「内存缓存 + 文件」
 * 双层结构，{@code getMessages()} 只读缓存，删除时先改缓存再全量重写文件。绕过它直接截断文件，
 * 缓存仍是旧的，任何后续持久化（追加或再次删除都会触发）都会把陈旧缓存写回，已删的消息成批复活；
 * 旧实现只能靠随后 {@code removeSession()} 丢实例来兜，而丢实例又会连带丢掉 {@code attrs()} 里
 * 那些不进快照的运行态（插话信箱、循环任务标记）。</p>
 *
 * <h3>为什么按 runId 定位，而不是前端传条数</h3>
 * <p>前端能数的是 DOM 行，与 ndjson 行不是一一对应：系统通知行、被中断轮次的空气泡都无服务端记录
 * （多删），连续 assistant 会被历史渲染合并进同一个气泡（少删）。runId 由 {@code ReActAgent} 在
 * 消息落库的同一处打上（{@link AgentTrace#META_RUN_ID}），同一轮的用户消息与最终回答共享它，
 * 因此「首个匹配该 runId 的消息」就是稳定锚点，条数改由服务端在真实消息列表上算。</p>
 *
 * <h3>windowSize 是迭代次数，不是条数</h3>
 * <p>{@code removeLatestMessage} 的链安全逻辑在删 ToolMessage 时会连带删同组 ToolMessage 及其
 * 所属的 {@code Assistant(toolCalls)}，这些连带删除<b>不消耗</b>迭代次数。所以不能把
 * {@code size - anchor} 当参数传进去，否则会越过锚点继续往下删。本服务先在副本上按同一算法
 * 模拟到锚点，得出「迭代次数」再调用；模拟时链式回溯可能越过锚点（同一 tool 链不可拆），
 * 此时以回溯后的位置为 {@code effectiveAnchor} 回报前端，让界面删除与之对齐。</p>
 *
 * @author noear
 */
public class SessionRewindService {
    private static final Logger LOG = LoggerFactory.getLogger(SessionRewindService.class);

    /**
     * 回退结果。
     */
    public static class RewindResult {
        /** 锚点未命中：调用方应拒绝本次回退（不要退化成按条数删） */
        private boolean anchorMissing;
        /** 实际删除的消息条数 */
        private int removed;
        /** 实际切点（链式回溯后可能小于请求锚点），前端据此对齐界面删除 */
        private int effectiveAnchor;
        /** 降级：未提供 runId（老数据），按调用方给的条数删 */
        private boolean degraded;

        public boolean isAnchorMissing() {
            return anchorMissing;
        }

        public int getRemoved() {
            return removed;
        }

        public int getEffectiveAnchor() {
            return effectiveAnchor;
        }

        public boolean isDegraded() {
            return degraded;
        }
    }

    /**
     * 回退会话消息。
     *
     * @param session       目标会话（须是运行时正在用的那个实例，否则缓存不同步）
     * @param traceKey      主代理轨迹在会话上下文中的键；为空时不清理轨迹
     * @param anchorRunId   锚点运行 ID；为空则退化为按 fallbackCount 删除
     * @param anchorRole    锚点角色（{@code assistant} / {@code user}）；为空则不限角色
     * @param fallbackCount 降级条数（仅当 anchorRunId 为空时生效）
     */
    public RewindResult rewind(AgentSession session, String traceKey,
                               String anchorRunId, String anchorRole, int fallbackCount) {
        Assert.notNull(session, "session is required");

        RewindResult result = new RewindResult();

        /* getMessages() 返回的是内部 List 本体（InMemoryChatSession 直接 return messages），
         * 只能遍历不能就地改：模拟必须在副本上跑，真正的删除交给 removeLatestMessage。 */
        List<ChatMessage> origin = new ArrayList<>(session.getMessages());
        int size = origin.size();
        if (size == 0) {
            result.effectiveAnchor = 0;
            return result;
        }

        int anchor;
        if (Assert.isEmpty(anchorRunId)) {
            // 老数据无 runId：按条数删，但标记降级供前端提示
            result.degraded = true;
            int count = Math.max(0, Math.min(fallbackCount, size));
            anchor = size - count;
        } else {
            anchor = indexOfAnchor(origin, anchorRunId, anchorRole);
            if (anchor < 0) {
                // 宁可不删，也不能删错条数：调用方应改为重载历史
                result.anchorMissing = true;
                return result;
            }
        }

        if (anchor >= size) {
            result.effectiveAnchor = size;
            return result;
        }

        // 1. 在副本上按 removeLatestMessage 的同一算法模拟，得出迭代次数与真实切点
        List<ChatMessage> work = new ArrayList<>(origin);
        int iterations = simulateRemoval(work, anchor);
        result.effectiveAnchor = work.size();
        result.removed = size - work.size();

        if (iterations <= 0) {
            return result;
        }

        // 2. 唯一删除入口：内存缓存与文件由它一次性同步
        session.removeLatestMessage(iterations);

        // 3. 被删范围覆盖到的轨迹一并清掉，否则刷新后「最后一轮执行过程」会把已删的思考与工具卡长回来
        if (Assert.isNotEmpty(traceKey)) {
            clearTraceIfCovered(session, traceKey,
                    collectRunIds(origin, 0, result.effectiveAnchor),
                    collectRunIds(origin, result.effectiveAnchor, size));
        }

        session.updateSnapshot();
        return result;
    }

    /**
     * 定位锚点：首个满足 runId（且角色匹配）的消息下标。
     *
     * <p>同一 runId 的消息在列表中必然连续（运行是串行追加的），取首个即该轮起点。
     * 角色限定为 assistant 时，同轮的用户消息天然保留。</p>
     */
    private int indexOfAnchor(List<ChatMessage> messages, String anchorRunId, String anchorRole) {
        boolean wantAssistant = "assistant".equalsIgnoreCase(anchorRole);
        boolean wantUser = "user".equalsIgnoreCase(anchorRole);

        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (!anchorRunId.equals(runIdOf(msg))) {
                continue;
            }
            if (wantAssistant && !(msg instanceof AssistantMessage)) {
                continue;
            }
            if (wantUser && !(msg instanceof UserMessage)) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private String runIdOf(ChatMessage msg) {
        if (msg == null || msg.getMetadata() == null) {
            return null;
        }
        Object runId = msg.getMetadata().get(AgentTrace.META_RUN_ID);
        return (runId == null) ? null : String.valueOf(runId);
    }

    /**
     * 在副本上模拟 {@code InMemoryChatSession.removeLatestMessage} 的逐轮删除，直到列表尾部退到
     * 锚点（或更前，链式回溯不可拆时）。返回需要传给 {@code removeLatestMessage} 的迭代次数。
     *
     * <p>必须与上游算法逐分支一致：ToolMessage 会连带回收同组 ToolMessage 与其
     * {@code Assistant(toolCalls)}，而这些连带删除不计入迭代次数。</p>
     */
    private int simulateRemoval(List<ChatMessage> work, int anchor) {
        int iterations = 0;
        while (work.size() > anchor && !work.isEmpty()) {
            iterations++;
            int lastIndex = work.size() - 1;
            ChatMessage last = work.get(lastIndex);

            if (last instanceof AssistantMessage) {
                AssistantMessage am = (AssistantMessage) last;
                work.remove(lastIndex);
                if (am.isToolCalls()) {
                    while (!work.isEmpty() && work.get(work.size() - 1) instanceof ToolMessage) {
                        work.remove(work.size() - 1);
                    }
                }
            } else if (last instanceof ToolMessage) {
                work.remove(lastIndex);
                while (!work.isEmpty() && work.get(work.size() - 1) instanceof ToolMessage) {
                    work.remove(work.size() - 1);
                }
                if (!work.isEmpty() && work.get(work.size() - 1) instanceof AssistantMessage) {
                    AssistantMessage prev = (AssistantMessage) work.get(work.size() - 1);
                    if (prev.isToolCalls()) {
                        work.remove(work.size() - 1);
                    }
                }
            } else {
                work.remove(lastIndex);
            }
        }
        return iterations;
    }

    private Set<String> collectRunIds(List<ChatMessage> messages, int fromIndex, int toIndex) {
        Set<String> runIds = new HashSet<>();
        for (int i = Math.max(0, fromIndex); i < Math.min(toIndex, messages.size()); i++) {
            String runId = runIdOf(messages.get(i));
            if (runId != null) {
                runIds.add(runId);
            }
        }
        return runIds;
    }

    /**
     * 轨迹只在「仍属于保留下来的某一轮」时才留着，否则清除。
     *
     * <p>判据必须同时看<b>保留侧与删除侧</b>：同一轮的用户消息与最终回答共享 runId，只删 AI 回复
     * （anchorRole=assistant）时，保留侧仍会出现该 runId，若只查保留侧会把指向已删回复的 trace 留下，
     * 刷新后回放便以 tool 消息结尾（最终回答已删）。故 runId 命中删除侧即清，两边都不命中（未知 runId，
     * 如老快照懒生成的 uuid）也一律清掉，避免残留无法回放的过程。</p>
     *
     * <p>只动 traceKey 一项，不碰上下文里其它数据（HITL 决策、循环任务状态）。</p>
     */
    private void clearTraceIfCovered(AgentSession session, String traceKey,
                                     Set<String> keptRunIds, Set<String> removedRunIds) {
        try {
            if (session.getContext() == null) {
                return;
            }
            Object obj = session.getContext().get(traceKey);
            if (obj == null) {
                return;
            }
            // 快照反序列化后类型可能退化：认不出就清掉（它已无法回放，留着只会残留）
            if (obj instanceof ReActTrace) {
                String traceRunId = ((ReActTrace) obj).getRunId();
                if (traceRunId != null
                        && keptRunIds.contains(traceRunId)
                        && !removedRunIds.contains(traceRunId)) {
                    // 属于未被删除的那一轮：留着它，回放仍然有效
                    return;
                }
            }
            session.getContext().remove(traceKey);
        } catch (Throwable e) {
            // 清理失败只影响回放残留，不能让回退主流程失败
            LOG.debug("[SessionRewind] clear trace failed: {}", e.getMessage());
        }
    }
}

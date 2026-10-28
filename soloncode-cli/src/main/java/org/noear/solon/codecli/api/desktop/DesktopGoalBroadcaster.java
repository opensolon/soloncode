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
package org.noear.solon.codecli.api.desktop;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.goal.GoalEngine;
import org.noear.solon.codecli.goal.GoalState;
import org.noear.solon.core.util.Assert;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 桌面端 Goal 流状态处理：Goal 生命周期事件 → WS 协议消息。
 *
 * <p>监听 {@link GoalEngine#addListener}：每次状态变更广播 {@code goal_status}；
 * 终态时按会话一次性发送权威 {@code done}（携带 modelName/totalTokens/elapsedMs）。</p>
 *
 * @author bai
 * @since 3.9.1
 */
final class DesktopGoalBroadcaster {
    private static final String TYPE_GOAL_STATUS = "goal_status";

    private final HarnessEngine engine;
    private final GoalEngine goalEngine;
    private final DesktopStreamHub streamHub;
    /** 已发送 Goal 终态 done 的会话，防止监听器多次触发重复下发。 */
    private final Set<String> completedGoalStreams = ConcurrentHashMap.newKeySet();

    DesktopGoalBroadcaster(HarnessEngine engine, GoalEngine goalEngine, DesktopStreamHub streamHub) {
        this.engine = engine;
        this.goalEngine = goalEngine;
        this.streamHub = streamHub;
    }

    /** 新 Goal 流启动前清除终态标记，允许该会话再次接收终态 done。 */
    void clearCompleted(String sessionId) {
        completedGoalStreams.remove(sessionId);
    }

    void onGoalChanged(String sessionId, GoalState state, boolean removed) {
        if (state == null) {
            return;
        }

        boolean running = runningOf(sessionId);
        String status = removed ? "STOPPED" : state.getStatus().name();
        ONode message = new ONode().set("type", TYPE_GOAL_STATUS)
                .set("sessionId", sessionId)
                .set("goalId", state.getId())
                .set("objective", state.getCondition())
                .set("status", status)
                .set("running", running)
                .set("iteration", state.getCurrentIteration())
                .set("maxIterations", state.getMaxIterations())
                .set("consumedTokens", state.getConsumedTokens())
                .set("maxTokens", state.getMaxTokens());
        if (state.getLastResult() != null) {
            message.set("lastResult", state.getLastResult());
        }
        streamHub.emit(sessionId, message.toJson());

        boolean terminal = removed || state.getStatus().isTerminal();
        if (terminal && completedGoalStreams.add(sessionId)) {
            String modelName = null;
            try {
                AgentSession session = engine.getSession(sessionId);
                modelName = session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);
            } catch (Throwable ignored) {
                // 会话不可用时回落默认模型名
            }
            if (Assert.isEmpty(modelName)) {
                modelName = engine.getMainModel().getConfig().getNameOrModel();
            }
            long elapsed = Math.max(0L, System.currentTimeMillis() - state.getStartEpochMs());
            streamHub.emit(sessionId, new ONode().set("type", "done")
                    .set("sessionId", sessionId)
                    .set("modelName", modelName)
                    .set("totalTokens", state.getConsumedTokens())
                    .set("elapsedMs", elapsed)
                    .toJson());
        }
    }

    private boolean runningOf(String sessionId) {
        if (goalEngine == null) return false;
        AgentSession session = goalEngine.sessionOf(sessionId);
        return goalEngine.isRunning(session);
    }
}

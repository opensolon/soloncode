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
import org.noear.solon.codecli.loop.GoalState;
import org.noear.solon.codecli.loop.LoopTask;
import org.noear.solon.core.util.Assert;
import org.noear.solon.ai.harness.HarnessEngine;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 桌面端 Goal 流状态处理：Goal 生命周期事件 → WS 协议消息。
 *
 * <p>从原 {@code WsGate} 中拆出 {@code onGoalChanged}：LoopScheduler 的 Goal 监听器
 * 每次触发都广播 {@code goal_status}；终态时按会话一次性发送权威 {@code done}
 * （携带 modelName/totalTokens/elapsedMs）。</p>
 *
 * @author bai
 * @since 3.9.1
 */
final class DesktopGoalBroadcaster {
    private static final String TYPE_GOAL_STATUS = "goal_status";

    private final HarnessEngine engine;
    private final DesktopStreamHub streamHub;
    /** 已发送 Goal 终态 done 的会话，防止监听器多次触发重复下发。 */
    private final Set<String> completedGoalStreams = ConcurrentHashMap.newKeySet();

    DesktopGoalBroadcaster(HarnessEngine engine, DesktopStreamHub streamHub) {
        this.engine = engine;
        this.streamHub = streamHub;
    }

    /** 新 Goal 流启动前清除终态标记，允许该会话再次接收终态 done。 */
    void clearCompleted(String sessionId) {
        completedGoalStreams.remove(sessionId);
    }

    void onGoalChanged(String sessionId, LoopTask task, boolean removed) {
        GoalState state = task.getGoalState();
        if (state == null) {
            return;
        }

        String status = removed || task.isCancelled() ? "STOPPED" : state.getStatus().name();
        ONode message = new ONode().set("type", TYPE_GOAL_STATUS)
                .set("sessionId", sessionId)
                .set("goalId", task.getId())
                .set("objective", state.getCondition())
                .set("status", status)
                .set("running", task.isRunning())
                .set("iteration", task.getCurrentIteration())
                .set("maxIterations", state.getMaxIterations())
                .set("consumedTokens", state.getConsumedTokens())
                .set("maxTokens", state.getMaxTokens());
        if (task.getLastResult() != null) {
            message.set("lastResult", task.getLastResult());
        }
        streamHub.emit(sessionId, message.toJson());

        boolean terminal = removed || task.isCancelled() || state.getStatus().isTerminal();
        if (terminal && completedGoalStreams.add(sessionId)) {
            AgentSession session = engine.getSession(sessionId);
            String modelName = session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);
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
}

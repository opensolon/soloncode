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
package org.noear.solon.codecli.portal;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.ActionEndChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.agent.react.task.ThoughtChunk;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessFlags;
import org.noear.solon.ai.harness.agent.TaskSkill;
import org.noear.solon.ai.skills.memory.MemorySkill;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * Web Stream Builder
 * @author noear 2026/4/23 created
 */
public class WebStreamBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(WebStreamBuilder.class);

    private final HarnessEngine engine;

    public WebStreamBuilder(HarnessEngine engine) {
        this.engine = engine;
    }

    public Flux<String> buildStreamFlux(AgentSession session, ReActAgent agent, ChatModel chatModel, String sessionCwd, Prompt prompt) {
        if (prompt == null) {
            prompt = Prompt.of();
        }

        if ("/resume".equals(prompt.getUserContent())) {
            prompt = Prompt.of();
        }

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
                    if (chunk instanceof ReasonChunk) {
                        return onReasonChunk((ReasonChunk) chunk);
                    } else if (chunk instanceof ThoughtChunk) {
                        return onThoughtChunk((ThoughtChunk) chunk);
                    } else if (chunk instanceof ActionEndChunk) {
                        return onActionEndChunk((ActionEndChunk) chunk);
                    } else if (chunk instanceof ReActChunk) {
                        return onFinalChunk((ReActChunk) chunk);
                    }

                    return "";
                })
                .filter(Assert::isNotEmpty)
                .doOnSubscribe(subscription -> {
                    // 将 Subscription 包装为 Disposable
                    Disposable disposable = subscription::cancel;

                    // 在订阅开始时，将 disposable 存入 session
                    session.attrs().put("disposable", disposable);
                })
                .onErrorResume(e -> {
                    LOG.error("Task fail: {}", e.getMessage(), e);

                    String message = new ONode().set("type", "error")
                            .set("text", e.getMessage())
                            .toJson();

                    return Flux.just(message);
                })
                .concatWith(Flux.defer(() -> {
                    // Check HITL state after stream completes
                    if (HITL.isHitl(session)) {
                        HITLTask task = HITL.getPendingTask(session);
                        if (task != null) {
                            String command = "bash".equals(task.getToolName())
                                    ? String.valueOf(task.getArgs().get("command"))
                                    : null;
                            String hitlMsg = new ONode().set("type", "hitl")
                                    .set("toolName", task.getToolName())
                                    .set("command", command)
                                    .toJson();
                            return Flux.just(hitlMsg, "[DONE]");
                        }
                    }
                    return Flux.just("[DONE]");
                }))
                .doFinally(signal -> {
                    // 流结束或被取消后，清理掉引用，避免内存泄漏
                    session.attrs().remove("disposable");
                });
    }

    private StringBuilder getTraceInfo(ReActTrace trace) {
        long start_time = trace.getBeginTimeMs();

        StringBuilder buf = new StringBuilder();
        buf.append(" `(");

        buf.append(trace.getOptions().getChatModel().getNameOrModel());

        if (trace.getMetrics() != null) {
            if (buf.length() > 2) {
                buf.append(", ");
            }

            buf.append(trace.getMetrics().getTotalTokens()).append("tk");
        }

        if (start_time > 0) {
            if (buf.length() > 2) {
                buf.append(", ");
            }

            long seconds = Duration.ofMillis(System.currentTimeMillis() - start_time).getSeconds();
            buf.append(seconds).append("s");
        }

        buf.append(")`");

        return buf;
    }

    private String onReasonChunk(ReasonChunk reason) {
        if (!reason.isToolCalls() && reason.hasContent()) {
            if (reason.getMessage().isThinking()) {
                return new ONode().set("type", "reason")
                        .set("text", reason.getContent())
                        .toJson();
            } else {
                return new ONode().set("type", "text")
                        .set("text", reason.getContent())
                        .toJson();
            }
        }

        return "";
    }

    private String onThoughtChunk(ThoughtChunk thought) {
        if (thought.hasMeta(TaskSkill.TOOL_MULTITASK)) {
            // 仅在多任务并行且有内容时输出
            String content = thought.getAssistantMessage().getResultContent();
            if (Assert.isNotEmpty(content)) {
                String traceInfo = "`(" + thought.getTrace().getOptions().getChatModel().getNameOrModel() + ")`";

                return new ONode().set("type", "text")
                        .set("text", "\n" + content + traceInfo)
                        .toJson();
            }
        }

        return "";
    }

    private String onActionEndChunk(ActionEndChunk action) {
        if (Assert.isNotEmpty(action.getToolName())) {
            if (TaskSkill.TOOL_MULTITASK.equals(action.getToolName()) ||
                    TaskSkill.TOOL_TASK.equals(action.getToolName()) ||
                    MemorySkill.isMemoryTool(action.getToolName())) {
                return "";
            }

            ONode oNode = new ONode().set("type", "action")
                    .set("text", action.getContent());

            if (Assert.isNotEmpty(action.getToolName())) {
                if (engine.getName().equals(action.getAgentName())) {
                    oNode.set("toolName", action.getToolName());
                } else {
                    oNode.set("toolName", action.getAgentName() + "/" + action.getToolName());
                }
                oNode.set("args", action.getArgs());

                if ("todowrite".equals(action.getToolName())) {
                    String todos = (String) action.getArgs().get("todos");

                    if (Assert.isNotEmpty(todos)) {
                        oNode.set("text", todos);
                    }
                }
            }

            return oNode.toJson();
        }

        return "";
    }

    private String onFinalChunk(ReActChunk react) {
        StringBuilder traceInfo = getTraceInfo(react.getTrace());

        return new ONode().set("type", "text")
                .set("text", traceInfo.toString())
                .toJson();
    }
}
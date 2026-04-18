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
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.ActionEndChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.agent.react.task.ThoughtChunk;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.TaskSkill;
import org.noear.solon.codecli.core.AgentProperties;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Handler;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.lang.Preview;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * Code CLI 终端 (Pool-Box 模型)
 * <p>基于 ReAct 模式的代码协作终端，提供多池挂载与任务盒隔离体验</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class WebGate implements Handler {
    private final HarnessEngine agentRuntime;
    private final AgentProperties agentProps;

    public WebGate(HarnessEngine agentRuntime, AgentProperties agentProps) {
        this.agentRuntime = agentRuntime;
        this.agentProps = agentProps;
    }

    @Override
    public void handle(Context ctx) throws Throwable {
        String input = ctx.param("input");
        String mode = ctx.param("m");
        String sessionId = ctx.param("sessionId");
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = ctx.headerOrDefault("X-Session-Id", "web");
        }
        String sessionCwd = ctx.header("X-Session-Cwd");//工作区

        if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            ctx.status(400);
            ctx.output("Invalid Session ID");
            return;
        }

        if (Assert.isNotEmpty(sessionCwd)) {
            //只有第一次传有效（后续的无效）
            if (sessionCwd.contains("..")) {
                ctx.status(400);
                ctx.output("Invalid Session Cwd");
                return;
            }
        }

        AgentSession session = agentRuntime.getSession(sessionId);

        // HITL approve/reject handling
        String hitlAction = ctx.param("hitlAction");
        if (Assert.isNotEmpty(hitlAction)) {
            HITLTask task = HITL.getPendingTask(session);
            if (task != null) {
                if ("approve".equals(hitlAction)) {
                    HITL.approve(session, task.getToolName());
                } else {
                    HITL.reject(session, task.getToolName());
                }
            }
            // Resume streaming after HITL decision
            ctx.contentType(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE);
            ctx.returnValue(buildStreamFlux(session, sessionCwd, null));
            return;
        }

        if (Assert.isNotEmpty(input)) {
            if ("call".equals(mode)) {
                ctx.contentType(MimeType.TEXT_PLAIN_UTF8_VALUE);
                String result = agentRuntime.getMainAgent()
                        .prompt(input)
                        .session(session)
                        .options(o -> {
                            if (Assert.isNotEmpty(sessionCwd)) {
                                o.toolContextPut(HarnessEngine.ATTR_CWD, sessionCwd);
                            }
                        })
                        .call()
                        .getContent();

                ctx.output(result);
            } else {
                ctx.contentType(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE);
                ctx.returnValue(buildStreamFlux(session, sessionCwd, input));
            }
        }
    }

    private Flux<String> buildStreamFlux(AgentSession session, String sessionCwd, String input) {
        Prompt prompt = Prompt.of(input).attrPut("start_time", System.currentTimeMillis());

        return agentRuntime.getMainAgent()
                .prompt(prompt)
                .session(session)
                .options(o -> {
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
                .doOnSubscribe(disposable -> {
                    // 在订阅开始时，将 disposable 存入 session
                    Disposable old = (Disposable) session.attrs().put("disposable", disposable);
                    if (old != null && !old.isDisposed()) {
                        old.dispose();
                    }
                })
                .doFinally(signal -> {
                    // 流结束或被取消后，清理掉引用，避免内存泄漏
                    session.attrs().remove("disposable");
                })
                .onErrorResume(e -> {
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
                }));
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
                return new ONode().set("type", "text")
                        .set("text", content)
                        .toJson();
            }
        }

        return "";
    }

    private String onActionEndChunk(ActionEndChunk action) {
        if (Assert.isNotEmpty(action.getToolName())) {
            if (TaskSkill.TOOL_MULTITASK.equals(action.getToolName()) ||
                    TaskSkill.TOOL_TASK.equals(action.getToolName())) {
                return "";
            }

            ONode oNode = new ONode().set("type", "action")
                    .set("text", action.getContent());

            if (Assert.isNotEmpty(action.getToolName())) {
                if (agentRuntime.getName().equals(action.getAgentName())) {
                    oNode.set("toolName", action.getToolName());
                } else {
                    oNode.set("toolName", action.getAgentName() + "/" + action.getToolName());
                }
                oNode.set("args", action.getArgs());
            }

            return oNode.toJson();
        }

        return "";
    }

    private String onFinalChunk(ReActChunk react) {
        StringBuilder buf = new StringBuilder();

        Long start_time = react.getTrace().getOriginalPrompt().attrAs("start_time");


        buf.append(" (");

        if (react.getTrace().getMetrics() != null) {
            buf.append(react.getTrace().getMetrics().getTotalTokens()).append(" tokens");
        }

        if (start_time != null) {
            long seconds = Duration.ofMillis(System.currentTimeMillis() - start_time).getSeconds();
            if (buf.length() > 2) {
                buf.append(", ");
            }

            buf.append(seconds).append(" seconds");
        }

        buf.append(")");

        return new ONode().set("type", "text")
                .set("text", buf.toString())
                .toJson();
    }
}
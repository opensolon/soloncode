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
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.TaskSkill;
import org.noear.solon.ai.skills.memory.MemorySkill;
import org.noear.solon.codecli.portal.wechat.WeChatLink;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Web Stream Builder
 * @author noear 2026/4/23 created
 */
public class WebStreamBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(WebStreamBuilder.class);

    private final HarnessEngine engine;

    /**
     * IM 通道路由表：所有注册的 IM 通道（微信、飞书、钉钉等）
     */
    private final List<IMLink> imLinks = new ArrayList<>();

    /**
     * 注册 IM 通道（向后兼容：支持 WeChatLink 直接注册）
     */
    public WebStreamBuilder bind(WeChatLink weChatLink) {
        this.imLinks.add(weChatLink);
        return this;
    }

    /**
     * 注册 IM 通道（通用接口）
     */
    public WebStreamBuilder bind(IMLink link) {
        this.imLinks.add(link);
        return this;
    }

    /**
     * 获取微信通道（向后兼容）
     */
    public WeChatLink getWeChatLink() {
        for (IMLink link : imLinks) {
            if (link instanceof WeChatLink) {
                return (WeChatLink) link;
            }
        }
        return null;
    }


    public WebStreamBuilder(HarnessEngine engine) {
        this.engine = engine;
    }

    public Flux<WebChunk> buildStreamFlux(AgentSession session, ReActAgent agent, ChatModel chatModel, String sessionCwd, Prompt prompt) {
        if (prompt == null) {
            prompt = Prompt.of();
        }

        if ("/resume".equals(prompt.getUserContent())) {
            prompt = Prompt.of();
        }

        //记录最新的选择
        session.attrs().put("_model_selected_tmp", chatModel.getNameOrModel());

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
                        return onThoughtChunk(session, (ThoughtChunk) chunk);
                    } else if (chunk instanceof ActionEndChunk) {
                        return onActionEndChunk((ActionEndChunk) chunk);
                    } else if (chunk instanceof ReActChunk) {
                        return onFinalChunk(session, (ReActChunk) chunk);
                    }

                    return WebChunk.EMPTY;
                })
                .filter(WebChunk::isNotEmpty)
                .onErrorResume(e -> {
                    LOG.error("Task fail: {}", e.getMessage(), e);

                    return Mono.just(WebChunk.ofError(e));
                })
                .concatWith(Flux.defer(() -> {
                    // Check HITL state after stream completes
                    if (HITL.isHitl(session)) {
                        HITLTask task = HITL.getPendingTask(session);
                        if (task != null) {
                            String command = "bash".equals(task.getToolName())
                                    ? String.valueOf(task.getArgs().get("command"))
                                    : null;

                            WebChunk hitlChuck = WebChunk.ofHitl(task.getToolName(), command);

                            return Flux.just(hitlChuck, WebChunk.ofDone());
                        }
                    }

                    return Flux.just(WebChunk.ofDone());
                }));
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

    private WebChunk onReasonChunk(ReasonChunk reason) {
        if (!reason.isToolCalls() && reason.hasContent()) {
            if (reason.getMessage().isThinking()) {
                return WebChunk.ofReason(reason.getContent());
            } else {
                return WebChunk.ofText(reason.getContent());
            }
        }

        return WebChunk.EMPTY;
    }


    private WebChunk onActionEndChunk(ActionEndChunk action) {
        if (Assert.isNotEmpty(action.getToolName())) {
            if (TaskSkill.TOOL_MULTITASK.equals(action.getToolName()) ||
                    TaskSkill.TOOL_TASK.equals(action.getToolName()) ||
                    MemorySkill.isMemoryTool(action.getToolName())) {
                return WebChunk.EMPTY;
            }

            WebChunk webChunk = WebChunk.ofAction(action.getContent());

            if (Assert.isNotEmpty(action.getToolName())) {
                if (engine.getName().equals(action.getAgentName())) {
                    webChunk.setToolName(action.getToolName());
                } else {
                    webChunk.setToolName(action.getAgentName() + "/" + action.getToolName());
                }
                webChunk.setArgs(action.getArgs());

                if ("todowrite".equals(action.getToolName())) {
                    String todos = (String) action.getArgs().get("todos");

                    if (Assert.isNotEmpty(todos)) {
                        webChunk.setText(todos);
                    }
                }
            }

            return webChunk;
        }

        return WebChunk.EMPTY;
    }

    private WebChunk onThoughtChunk(AgentSession session, ThoughtChunk thought) {
        String sessionId = session.getSessionId();

        // 向所有已绑定的 IM 通道回复
        for (IMLink link : imLinks) {
            if (link.isBound(sessionId)) {
                String resultContent = thought.getAssistantMessage().getResultContent();

                if (thought.isToolCalls()) {
                    // 说明是过程
                    link.sendReply(sessionId, resultContent, false);
                } else {
                    // 说明是结果
                    String modelSelectedTmp = (String) session.attrs().get("_model_selected_tmp");

                    if (thought.getTrace().getOptions().getChatModel().getNameOrModel().equals(modelSelectedTmp)) {
                        // 说明是源代理（说明是最终结果）
                        StringBuilder traceInfo = getTraceInfo(thought.getTrace());
                        link.sendReply(sessionId, resultContent + traceInfo, true);
                    } else {
                        // 说明是次代理
                        link.sendReply(sessionId, resultContent, false);
                    }
                }
            }
        }


        if (thought.hasMeta(TaskSkill.TOOL_MULTITASK)) {
            // 仅在多任务并行且有内容时输出
            String content = thought.getAssistantMessage().getResultContent();
            if (Assert.isNotEmpty(content)) {
                //content = content + "`(" + thought.getTrace().getOptions().getChatModel().getNameOrModel() + ")`";


                return WebChunk.ofText("\n" + content);
            }
        }

        return WebChunk.EMPTY;
    }

    private WebChunk onFinalChunk(AgentSession session, ReActChunk react) {
        StringBuilder traceInfo = getTraceInfo(react.getTrace());

        if (react.isAbnormal()) {
            // 向所有已绑定的 IM 通道回复异常
            replyToBoundChannel(session.getSessionId(), react.getContent() + traceInfo, true);
        }

        return WebChunk.ofText(traceInfo.toString());
    }

    /**
     * 向所有已绑定的 IM 通道发送回复
     */
    private void replyToBoundChannel(String sessionId, String text, boolean isFinal) {
        for (IMLink link : imLinks) {
            if (link.isBound(sessionId)) {
                link.sendReply(sessionId, text, isFinal);
            }
        }
    }
}
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
package org.noear.solon.ai.codecli.portal;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.codecli.core.AgentKernel;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Handler;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.lang.Preview;
import reactor.core.publisher.Flux;

/**
 * Code CLI 终端 (Pool-Box 模型)
 * <p>基于 ReAct 模式的代码协作终端，提供多池挂载与任务盒隔离体验</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class WebGate implements Handler {
    private final AgentKernel kernel;

    public WebGate(AgentKernel kernel) {
        this.kernel = kernel;
    }

    @Override
    public void handle(Context ctx) throws Throwable {
        String input = ctx.param("input");
        String mode = ctx.param("m");
        String sessionId = ctx.headerOrDefault("X-Session-Id", "web");
        String sessionCwd = ctx.header("X-Session-Cwd");//工作区

        if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            ctx.status(400);
            ctx.output("Invalid Session ID");
            return;
        }

        if(Assert.isNotEmpty(sessionCwd)) {
            //只有第一次传有效（后续的无效）
            if (sessionCwd.contains("..")) {
                ctx.status(400);
                ctx.output("Invalid Session Cwd");
                return;
            }

            AgentSession session = kernel.getSession(sessionId);
            session.attrs().putIfAbsent(AgentKernel.ATTR_CWD, sessionCwd);
        }

        if (Assert.isNotEmpty(input)) {
            if ("call".equals(mode)) {
                ctx.contentType(MimeType.TEXT_PLAIN_UTF8_VALUE);
                String result = kernel.call(sessionId, Prompt.of(input))
                        .getContent();

                ctx.output(result);
            } else {
                ctx.contentType(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE);


                Flux<String> stringFlux = kernel.stream(sessionId, Prompt.of(input))
                        .map(chunk -> {
                            if (chunk.hasContent()) {
                                if (chunk instanceof ReasonChunk) {
                                    ReasonChunk reason = (ReasonChunk) chunk;

                                    if (!reason.isToolCalls() && reason.hasContent()) {
                                        return new ONode().set("type", "reason")
                                                .set("text", chunk.getContent())
                                                .toJson();
                                    }
                                } else if (chunk instanceof ActionChunk) {
                                    ActionChunk action = (ActionChunk) chunk;
                                    ONode oNode = new ONode().set("type", "action")
                                            .set("text", chunk.getContent());

                                    if (Assert.isNotEmpty(action.getToolName())) {
                                        oNode.set("toolName", action.getToolName());
                                        oNode.set("args", action.getArgs());
                                    }

                                    return oNode.toJson();
                                } else if (chunk instanceof ReActChunk) {
                                    ReActChunk react = (ReActChunk) chunk;

                                    if (react.isNormal() == false) {
                                        return new ONode().set("type", "reason")
                                                .set("text", chunk.getContent())
                                                .toJson();
                                    }

                                    return new ONode().set("type", "agent")
                                            .set("text", chunk.getContent())
                                            .toJson();
                                }
                            }

                            return "";
                        })
                        .filter(Assert::isNotEmpty)
                        .onErrorResume(e -> {
                            String message = new ONode().set("type", "error")
                                    .set("text", e.getMessage())
                                    .toJson();

                            return Flux.just(message);
                        })
                        .concatWithValues("[DONE]");

                ctx.returnValue(stringFlux);
            }
        }
    }
}
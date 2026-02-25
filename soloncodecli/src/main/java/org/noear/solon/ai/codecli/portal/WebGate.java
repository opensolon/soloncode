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
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.codecli.core.CodeAgent;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Handler;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.lang.Preview;
import reactor.core.publisher.Flux;

import java.io.Serializable;

/**
 * Code CLI 终端 (Pool-Box 模型)
 * <p>基于 ReAct 模式的代码协作终端，提供多池挂载与任务盒隔离体验</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class WebGate implements Handler {
    private final CodeAgent codeAgent;

    public WebGate(CodeAgent codeAgent) {
        this.codeAgent = codeAgent;
    }

    @Override
    public void handle(Context ctx) throws Throwable {
        codeAgent.prepare();

        String input = ctx.param("input");
        String mode = ctx.param("m");
        String sessionId = ctx.headerOrDefault("X-Session-Id", "web");

        if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            ctx.status(400);
            ctx.output("Invalid Session ID");
            return;
        }

        if (Assert.isNotEmpty(input)) {
            if ("call".equals(mode)) {
                ctx.contentType(MimeType.TEXT_PLAIN_UTF8_VALUE);
                String result = codeAgent.call(sessionId, Prompt.of(input))
                        .getContent();

                ctx.output(result);
            } else {
                ctx.contentType(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE);


                Flux<String> stringFlux = codeAgent.stream(sessionId, Prompt.of(input))
                        .map(chunk -> {
                            if (chunk.hasContent()) {
                                if (chunk instanceof ReasonChunk) {
                                    return ONode.serialize(new Chunk("reason", chunk.getContent()));
                                } else if (chunk instanceof ActionChunk) {
                                    return ONode.serialize(new Chunk("action", chunk.getContent()));
                                } else if (chunk instanceof ReActChunk) {
                                    return ONode.serialize(new Chunk("agent", chunk.getContent()));
                                }
                            }

                            return "";
                        })
                        .filter(Assert::isNotEmpty)
                        .onErrorResume(e -> Flux.just(ONode.serialize(new Chunk("error", e.getMessage()))))
                        .concatWithValues("[DONE]");

                ctx.returnValue(stringFlux);
            }
        }
    }

    public static class Chunk implements Serializable {
        public final String type;
        public final String text;

        public Chunk(String type, String text) {
            this.type = type;
            this.text = text;
        }
    }
}
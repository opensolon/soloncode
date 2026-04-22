package org.codecli.ext1;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.interceptor.CallChain;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.harness.HarnessExtension;

import java.io.IOException;
import java.util.Map;

/**
 *
 * @author noear 2026/4/21 created
 *
 */
public class Extension1 implements HarnessExtension {
    @Override
    public void configure(String agentName, ReActAgent.Builder agentBuilder) {
        // 在此处对 agentBuilder 进行定制，例如添加 Tool 或 Interceptor
        agentBuilder.defaultInterceptorAdd(new ReActInterceptor() {
            @Override
            public void onAgentStart(ReActTrace trace) {
                System.out.println("任务要开始了...");
            }

            @Override
            public void onReason(ReActTrace trace, AssistantMessage message) {
                System.out.println("又思考了...");
            }

            @Override
            public ChatResponse interceptCall(ChatRequest req, CallChain chain) throws IOException {
                System.out.println("调用工具了...");
                return chain.doIntercept(req);
            }
        });
    }
}

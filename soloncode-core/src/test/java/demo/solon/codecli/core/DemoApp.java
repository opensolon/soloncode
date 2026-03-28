package demo.solon.codecli.core;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.codecli.core.AgentProperties;
import org.noear.solon.codecli.core.AgentRuntime;
import org.noear.solon.codecli.core.agent.AgentDefinition;
import org.noear.solon.lang.NonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DemoApp {
    public static void main(String[] args) throws Throwable {
        //--- 初始化（AgentRuntime 建议单测）
        AgentProperties properties = new AgentProperties();
        ChatModel chatModel = ChatModel.of(properties.getChatModel()).build();
        AgentSessionProvider sessionProvider = new AgentSessionProvider() {
            private Map<String, AgentSession> sessionMap = new ConcurrentHashMap<>();

            @Override
            public @NonNull AgentSession getSession(String instanceId) {
                return sessionMap.computeIfAbsent(instanceId, k -> InMemoryAgentSession.of(k));
            }
        };

        AgentRuntime agentRuntime = AgentRuntime.builder()
                .properties(properties)
                .chatModel(chatModel)
                .sessionProvider(sessionProvider)
                .build();

        AgentSession session = agentRuntime.getSession("cli");
        Prompt prompt = Prompt.of("hello"); //动态指定工作区;


        //--- 用主代理模式
        agentRuntime.getRootAgent().prompt(prompt)
                .session(session) //没有，则为临时会话
                .options(o -> {
                    //按需，动态指定工作区（没有，则为默认工作区）
                    o.toolContextPut(AgentRuntime.ATTR_CWD, "xxx");
                })
                .call();

        //--- 用子代理模式（好处理可以动态创建不同的工具权限）
        AgentDefinition definition = new AgentDefinition();
        definition.setSystemPrompt("xxx");
        definition.getMetadata().addTools("*");

        //具体规划，参考 AgentFactory.create
        ReActAgent subagent = agentRuntime.createSubagent(definition).build();
        subagent.prompt(prompt)
                .session(session) //没有，则为临时会话
                .options(o -> {
                    //按需，动态指定工作区（没有，则为默认工作区）
                    o.toolContextPut(AgentRuntime.ATTR_CWD, "xxx");
                })
                .call();
    }
}
package features.ai.cli;

import org.junit.jupiter.api.Test;
import org.noear.solon.Solon;
import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.codecli.core.CodeAgent;
import org.noear.solon.ai.codecli.core.CodeProperties;
import org.noear.solon.ai.codecli.core.subagent.SubAgentManager;
import org.noear.solon.ai.codecli.core.subagent.SubAgentType;
import org.noear.solon.test.SolonTest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * SubAgent 功能测试
 *
 * @author noear
 * @since 3.9.5
 */
@SolonTest
public class SubAgentTest {

    Map<String, AgentSession> sessionMap = new ConcurrentHashMap<>();

    Function<String, AgentSessionProvider> sessionProvider = (String dir) -> (sessionId) -> sessionMap.computeIfAbsent(sessionId, key ->
            new FileAgentSession(key, dir+ CodeAgent.SOLONCODE_SESSIONS + key));

    /**
     * 测试探索代理
     */
    @Test
    public void testExploreAgent() throws Throwable {
        CodeProperties config = Solon.cfg().toBean("solon.code.cli", CodeProperties.class);
        ChatModel chatModel = ChatModel.of(config.chatModel).build();

        CodeAgent codeAgent = new CodeAgent(chatModel, sessionProvider.apply(config.workDir), config)
                .enableSubAgent(true);

        // 准备 Agent
        codeAgent.prepare();

        // 获取 SubAgentManager
        SubAgentManager manager = codeAgent.getSubAgentManager();

        // 使用探索代理
        String prompt = "请探索这个项目的代码结构，找出所有的 Java 源文件";
        AgentResponse response = manager.getAgent(SubAgentType.EXPLORE).execute(Prompt.of(prompt));

        System.out.println("探索结果：");
        System.out.println(response.getContent());
    }

    /**
     * 测试计划代理
     */
    @Test
    public void testPlanAgent() throws Throwable {
        CodeProperties config = Solon.cfg().toBean("solon.code.cli", CodeProperties.class);
        ChatModel chatModel = ChatModel.of(config.chatModel).build();

        CodeAgent codeAgent = new CodeAgent(chatModel, sessionProvider.apply(config.workDir), config)
                .enableSubAgent(true);

        codeAgent.prepare();

        SubAgentManager manager = codeAgent.getSubAgentManager();

        String prompt = "为添加一个新的 REST API 端点设计实现计划";
        AgentResponse response = manager.getAgent(SubAgentType.PLAN).execute(Prompt.of(prompt));

        System.out.println("计划结果：");
        System.out.println(response.getContent());
    }

    /**
     * 测试 Bash 代理
     */
    @Test
    public void testBashAgent() throws Throwable {
        CodeProperties config = Solon.cfg().toBean("solon.code.cli", CodeProperties.class);
        ChatModel chatModel = ChatModel.of(config.chatModel).build();

        CodeAgent codeAgent = new CodeAgent(chatModel, sessionProvider.apply(config.workDir), config)
                .enableSubAgent(true);

        codeAgent.prepare();

        SubAgentManager manager = codeAgent.getSubAgentManager();

        String prompt = "列出当前目录的文件";
        AgentResponse response = manager.getAgent(SubAgentType.BASH).execute(Prompt.of(prompt));

        System.out.println("Bash 执行结果：");
        System.out.println(response.getContent());
    }

    /**
     * 测试通过 SubAgentTool 调用（模拟主 Agent 调用）
     */
    @Test
    public void testSubAgentTool() throws Throwable {
        CodeProperties config = Solon.cfg().toBean("solon.code.cli", CodeProperties.class);
        ChatModel chatModel = ChatModel.of(config.chatModel).build();

        CodeAgent codeAgent = new CodeAgent(chatModel, sessionProvider.apply(config.workDir), config)
                .enableSubAgent(true);

        codeAgent.prepare();

        // 主 Agent 可以通过 SubAgentTool 调用子代理
        String prompt = "使用 explore 子代理探索项目的核心类";
        AgentResponse response = codeAgent.call(CodeAgent.SESSION_DEFAULT, Prompt.of(prompt));

        System.out.println("主 Agent 响应：");
        System.out.println(response.getContent());
    }
}

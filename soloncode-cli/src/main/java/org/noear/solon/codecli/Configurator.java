package org.noear.solon.codecli;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.WebSocketSolonAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.core.AgentFlags;
import org.noear.solon.codecli.core.AgentProperties;
import org.noear.solon.codecli.portal.AcpLink;
import org.noear.solon.codecli.portal.CliShell;
import org.noear.solon.codecli.portal.WebController;
import org.noear.solon.codecli.portal.WsGate;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.BeanWrap;
import org.noear.solon.core.util.JavaUtil;
import org.noear.solon.core.util.RunUtil;
import org.noear.solon.net.websocket.WebSocketRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author noear 2026/4/18 created
 *
 */
@Configuration
public class Configurator {
    private static final Logger LOG = LoggerFactory.getLogger(Configurator.class);

    @Inject
    HarnessEngine agentRuntime;

    @Inject
    AgentProperties agentProps;

    @Bean
    public HarnessEngine agentRuntime(AppContext context, AgentProperties props) {
        props.getSkillPools().put("@global", Paths.get(props.getUserHome(), props.getHarnessSkills()).toString());
        props.getSkillPools().put("@local", Paths.get(props.getWorkspace(), props.getHarnessSkills()).toString());

        props.getSkillPools().put("@skills", Paths.get(props.getWorkspace(), "skills").toString());
        props.getSkillPools().put("@skillhub", Paths.get(props.getUserHome(), ".skillhub/skills/").toString());

        props.getSkillPools().put("@opencode_skills", Paths.get(props.getWorkspace(), props.OPENCODE_SKILLS).toString());
        props.getSkillPools().put("@claude_skills", Paths.get(props.getWorkspace(), props.CLAUDE_SKILLS).toString());

        props.getAgentPools().add(Paths.get(props.getUserHome(), props.getHarnessAgents()).toString()); //global
        props.getAgentPools().add(Paths.get(props.getWorkspace(), props.getHarnessAgents()).toString()); //local



        Map<String, AgentSession> sessionMap = new ConcurrentHashMap<>();

        // 会话数据存到全局目录 ~/.soloncode/sessions/<sessionId>/
        AgentSessionProvider sessionProvider = (sessionId) -> sessionMap.computeIfAbsent(sessionId, key ->
                new FileAgentSession(key, Paths.get(props.getWorkspace(), props.getHarnessSessions()).resolve(key).normalize().toFile().toString()));

        //订阅容器扩展
        context.subBeansOfType(HarnessExtension.class, extension -> {
            props.addExtension(extension);
        });

        HarnessEngine engine = HarnessEngine.of(props)
                .sessionProvider(sessionProvider)
                .build();

        return engine;
    }

    @Init
    public void init() {

        CliShell cliShell = new CliShell(agentRuntime, agentProps);

        if (AgentFlags.checkUpdate()) {
            // 使用颜色代码让提示更醒目
            System.out.println("\033[33mDiscover the new version: " + AgentFlags.getLastVersion() + "\033[0m");

            if (JavaUtil.IS_WINDOWS) {
                System.out.println("Update: \033[36mirm https://solon.noear.org/soloncode/setup.ps1 | iex\033[0m");
            } else {
                System.out.println("Update: \033[36mcurl -fsSL https://solon.noear.org/soloncode/setup.sh | bash\033[0m");
            }
            System.out.println();
        }

        //flag
        if (Solon.cfg().argx().flags().size() > 0) {
            String flag = Solon.cfg().argx().flagAt(0);

            if (AgentFlags.FLAG_RUN.equals(flag)) { // java -jar soloncode.jar run '你好' // soloncode run '你好'
                //单次任务态
                String prompt = Solon.cfg().argx().flagAt(1);
                new CliShell(agentRuntime, agentProps).call(prompt);
                Solon.stop();
                return;
            }

            if (AgentFlags.FLAG_SERVE.equals(flag)) { // java -jar soloncode.jar server // soloncode server
                runWeb(agentRuntime, agentProps, null);
                runAcp(agentRuntime, agentProps, null);

                cliShell.printWelcome("Server port: " + Solon.cfg().serverPort());
                return;
            }

            if (AgentFlags.FLAG_WEB.equals(flag)) { // java -jar soloncode.jar web // soloncode web
                runWeb(agentRuntime, agentProps, cliShell);
                return;
            }

            if (AgentFlags.FLAG_ACP.equals(flag)) { // java -jar soloncode.jar acp // soloncode acp
                runAcp(agentRuntime, agentProps, cliShell);
                return;
            }

            //未来可以支持更多控制标记
        }


        //cli - default
        new Thread(cliShell, "CLI-Interactive-Thread").start();
    }


    private void runWeb(HarnessEngine agentRuntime, AgentProperties agentProps, CliShell cliShell) {
        //ws
        WebSocketRouter.getInstance().of(agentProps.getWsEndpoint(), new WsGate(agentRuntime, agentProps));
        //web
        BeanWrap webBean = Solon.context().wrapAndPut(WebController.class, new WebController(agentRuntime));
        Solon.app().router().add(webBean);

        if (cliShell == null) {
            return;
        }

        RunUtil.async(() -> {
            try {
                Thread.sleep(500);

                String url = "http://localhost:" + Solon.cfg().serverPort() + "/";

                if (JavaUtil.IS_WINDOWS) {
                    new ProcessBuilder("cmd", "/c", "start", url.replace("&", "^&")).start();
                } else if (JavaUtil.IS_MAC) {
                    new ProcessBuilder("open", url).start();
                } else {
                    new ProcessBuilder("xdg-open", url).start();
                }

                if (cliShell != null) {
                    cliShell.printWelcome("Web interface: " + url);
                }
            } catch (Throwable e) { // 使用 Throwable 捕获更全面
                LOG.warn("Failed to open browser: {}", e.getMessage());
            }
        });
    }


    private void runAcp(HarnessEngine agentRuntime, AgentProperties agentProps, CliShell cliShell) {
        AcpAgentTransport agentTransport;
        if ("stdio".equals(agentProps.getAcpTransport())) {
            agentTransport = new StdioAcpAgentTransport();
        } else {
            agentTransport = new WebSocketSolonAcpAgentTransport(
                    agentProps.getAcpTransport(), McpJsonMapper.getDefault());
        }

        new AcpLink(agentRuntime, agentTransport, agentProps).run();

        if (cliShell != null) {
            String url = "ws://localhost:" + Solon.cfg().serverPort() + "/acp";
            cliShell.printWelcome("Acp interface: " + url);
        }
    }
}
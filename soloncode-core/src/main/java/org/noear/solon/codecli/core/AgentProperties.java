package org.noear.solon.codecli.core;

import lombok.Getter;
import lombok.Setter;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.skills.restapi.ApiSource;

import java.io.*;
import java.util.Map;

/**
 * Cli 配置
 *
 * @author noear
 * @since 3.9.1
 */
@Getter
@Setter
public class AgentProperties implements Serializable {
    private ChatConfig chatModel;
    private String workDir = "work";
    private String tools = "**";
    private int maxSteps = 20;
    private boolean maxStepsAutoExtensible = false;
    private String uiType = "old";
    private int sessionWindowSize = 8;
    private int summaryWindowSize = 12;
    private int summaryWindowToken = 15000;
    private boolean sandboxMode = true;
    private boolean thinkPrinted = false;
    private boolean hitlEnabled = false;
    private boolean subagentEnabled = true;
    private boolean cliEnabled = true;
    private boolean cliPrintSimplified = true;
    private boolean webEnabled = false;
    private String webEndpoint = "/cli";
    private boolean acpEnabled = false;
    private String acpTransport = "stdio";
    private String acpEndpoint = "/acp";

    private Map<String, String> skillPools;

    private Map<String, McpServerParameters> mcpServers;
    private Map<String, ApiSource> restApis;

    /**
     * 当前目录
     */
    public static String getUserDir() {
        return System.getProperty("user.dir");
    }

    /**
     * 用户主目录
     */
    public static String getUserHome() {
        return System.getProperty("user.home");
    }
}

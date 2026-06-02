package org.noear.solon.codecli.config;

import lombok.Getter;
import lombok.Setter;

import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.talents.lsp.LspServerParameters;
import org.noear.solon.ai.talents.openapi.ApiSource;
import org.noear.solon.core.util.IoUtil;
import org.noear.solon.core.util.ResourceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 代理属性
 *
 * @author noear
 * @since 3.9.1
 */
@Getter
@Setter
public class AgentProperties implements Serializable {
    private static final Logger LOG = LoggerFactory.getLogger(AgentProperties.class);

    public final static String NAME_CONFIG_YML = "config.yml";
    public final static String NAME_SETTINGS_JSON = "settings.json";
    public final static String NAME_AGENTS_MD = "AGENTS.md";

    /**
     * @deprecated 2026.4.10 {@link #getModels()}
     *
     */
    @Deprecated
    private ChatConfig chatModel;

    public final static String X_SESSION_ID = "X-Session-Id";
    public final static String X_SESSION_CWD = "X-Session-Cwd";


    //马具目录
    private final String harnessHome;

    //主代理工具权限
    private List<String> tools = new CopyOnWriteArrayList<>();

    // 禁用工具（全局）
    private List<String> disallowedTools = new CopyOnWriteArrayList<>();

    //最大步数
    @Deprecated
    private Integer maxSteps;
    private Integer maxTurns;

    //自我反思
    private boolean autoRethink = true;

    private int sessionWindowSize = 8;
    private int summaryWindowSize = 30;
    private int summaryWindowToken = 30000;
    private String summaryModel; //摘要大模型

    private boolean memoryIsolation = false;
    private boolean memoryEnabled = true;

    private boolean sandboxMode = true;
    private boolean hitlEnabled = false;
    private boolean subagentEnabled = true;
    private boolean bashAsyncEnabled = false;

    private String userAgent;

    //api 重试次数
    private int apiRetries = 3;
    //Mcp 重试次数
    private int mcpRetries = 3;
    //模型重试次数
    private int modelRetries = 3;

    //扩展
    private List<HarnessExtension> extensions = new CopyOnWriteArrayList<>();

    //大模型
    private List<ChatConfig> models = new CopyOnWriteArrayList<>();
    /**
     * @deprecated 4.0.0
     */
    @Deprecated
    private Map<String, String> skillPools = new ConcurrentHashMap<>();
    /**
     * @deprecated 4.0.0
     */
    @Deprecated
    private List<String> agentPools = new CopyOnWriteArrayList<>();
    //mcp集
    private Map<String, McpServerParameters> mcpServers = new ConcurrentHashMap<>();
    //api集
    private Map<String, ApiSource> apiServers = new ConcurrentHashMap<>();
    //lsp集
    private Map<String, LspServerParameters> lspServers = new ConcurrentHashMap<>();

    private boolean thinkPrinted = false;
    private boolean cliPrintSimplified = true;
    private String webEndpoint = "/cli";
    private String acpTransport = "stdio";
    private String acpEndpoint = "/acp";
    private String wsEndpoint = "/ws";


    public AgentProperties() {
        this.harnessHome =".soloncode/";
    }

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

    public  String getUserExtensions(){
       return Paths.get(getUserHome(), getHarnessHome(), "extensions").toString();
    }

    public URL getConfigUrl() throws MalformedURLException {
        //1. 资源文件（一般开发时）
        URL tmp = ResourceUtil.getResource(NAME_CONFIG_YML);
        if (tmp != null) {
            return tmp;
        }

        //2. 工作区配置
        Path path = Paths.get(getUserDir(), getHarnessHome(), NAME_CONFIG_YML);
        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //3. 用户目录区配置
        path = Paths.get(getUserHome(), getHarnessHome(), NAME_CONFIG_YML);

        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //4. 程序边上的配置文件
        tmp = ResourceUtil.getResourceByFile(NAME_CONFIG_YML);
        if (tmp != null) {
            return tmp;
        }

        return null;
    }

    public URL getSettingsUrl() throws MalformedURLException {
        //1. 资源文件（一般开发时）
        URL tmp = ResourceUtil.getResource(NAME_SETTINGS_JSON);
        if (tmp != null) {
            return tmp;
        }

        //2. 工作区配置
        Path path = Paths.get(getUserDir(), getHarnessHome(), NAME_SETTINGS_JSON);
        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //3. 用户目录区配置
        path = Paths.get(getUserHome(), getHarnessHome(), NAME_SETTINGS_JSON);

        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //4. 程序边上的配置文件
        tmp = ResourceUtil.getResourceByFile(NAME_SETTINGS_JSON);
        if (tmp != null) {
            return tmp;
        }

        return null;
    }

    public URL getAgentsUrl() throws MalformedURLException {
        //1. 工作区配置
        Path path = Paths.get(getUserDir(), getHarnessHome(), NAME_AGENTS_MD);
        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //2. 用户目录区配置
        path = Paths.get(getUserHome(), getHarnessHome(), NAME_AGENTS_MD);

        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //3. 程序边上的配置文件
        URL tmp = ResourceUtil.getResourceByFile(NAME_AGENTS_MD);
        if (tmp != null) {
            return tmp;
        }

        return null;
    }

    public String getAgentsMd() {
        try {
            URL agentsUrl = getAgentsUrl();

            if (agentsUrl != null) {
                try (InputStream is = agentsUrl.openStream()) {
                    String content = IoUtil.transferToString(is, "utf-8").trim();

                    if (content.length() > 10000) { // 例如限制在 1万字符以内
                        LOG.warn("AGENTS.md is too large, truncating...");
                        return content.substring(0, 10000);
                    }
                    return content;
                }
            }
        } catch (Throwable e) {
            LOG.warn("AGENTS.md load failure: {}", e.getMessage(), e);
        }

        return null;
    }


    //---------------

    /**
     * @deprecated 4.0.0
     *
     */
    @Deprecated
    public Map<String, String> getSkillPools() {
        return skillPools;
    }


    public List<ChatConfig> getModels() {
        return models;
    }


    public boolean isAutoRethink() {
        return autoRethink;
    }


    //--------------------------

    /**
     * 马具主目录
     */
    public final String getHarnessHome() {
        return harnessHome;
    }

    /**
     * 马具会话存放区
     */
    public final String getHarnessSessions() {
        return getHarnessHome() + "sessions/";
    }

    /**
     * 马具技能存放区
     */
    public final String getHarnessSkills() {
        return getHarnessHome() + "skills/";
    }

    /**
     * 马具子代理描述存放区
     */
    public final String getHarnessAgents() {
        return getHarnessHome() + "agents/";
    }

    /**
     * 马具命令描述存放区
     */
    public final String getHarnessCommands() {
        return getHarnessHome() + "commands/";
    }

    /**
     * 马具记忆存放区
     */
    public final String getHarnessMemory() {
        return getHarnessHome() + "memory/";
    }

    /**
     * 马具下载存放区
     */
    public final String getHarnessDownload() {
        return getHarnessHome() + "download/";
    }

    /**
     * 马具连接通道存放区
     */
    public final String getHarnessChannels() {
        return getHarnessHome() + "channels/";
    }
}
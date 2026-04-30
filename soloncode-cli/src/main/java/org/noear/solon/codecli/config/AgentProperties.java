package org.noear.solon.codecli.config;

import lombok.Getter;
import lombok.Setter;

import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessProperties;
import org.noear.solon.core.util.IoUtil;
import org.noear.solon.core.util.ResourceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 代理属性
 *
 * @author noear
 * @since 3.9.1
 */
@Getter
@Setter
public class AgentProperties extends HarnessProperties {
    private static final Logger LOG = LoggerFactory.getLogger(AgentProperties.class);

    public final static String NAME_CONFIG_YML = "config.yml";
    public final static String NAME_AGENTS_MD = "AGENTS.md";

    /**
     * @deprecated 2026.4.10 {@link #getModels()}
     *
     */
    @Deprecated
    private ChatConfig chatModel;

    public final static String OPENCODE_SKILLS = ".opencode/skills/";
    public final static String CLAUDE_SKILLS = ".claude/skills/";

    public final static String X_SESSION_ID = "X-Session-Id";
    public final static String X_SESSION_CWD = "X-Session-Cwd";

    public final static String ARG_SESSION = "session";


    private String sessionId = "default"; //默认会话

    private boolean thinkPrinted = false;

    private boolean cliPrintSimplified = true;

    private boolean memoryIsolation = true;

    private String webEndpoint = "/cli";

    private String acpTransport = "stdio";
    private String acpEndpoint = "/acp";

    private String wsEndpoint = "/ws";

    private String startupSessionMode = "resume";
    private String uiType = "old";
    private String uiTheme = "solon";
    private Map<String, Map<String, String>> uiThemes;

    public AgentProperties() {
        super(".soloncode/");
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

    public URL getAgentsUrl() throws MalformedURLException {
        //1. 工作区配置
        Path path = Paths.get(getWorkspace(), getHarnessHome(), NAME_AGENTS_MD);
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
}
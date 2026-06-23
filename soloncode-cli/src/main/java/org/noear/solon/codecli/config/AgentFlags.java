package org.noear.solon.codecli.config;

import org.noear.snack4.ONode;
import org.noear.solon.core.util.DateUtil;
import org.noear.solon.core.util.IoUtil;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;

/**
 *
 * @author noear 2026/4/4 created
 *
 */
public class AgentFlags {
    private final static Logger LOG = LoggerFactory.getLogger(AgentFlags.class);
    public final static String NAME_CONFIG_YML = "config.yml";
    public final static String NAME_SETTINGS_JSON = "settings.json";
    public final static String NAME_AGENTS_MD = "AGENTS.md";

    public final static String X_SESSION_ID = "X-Session-Id";
    public final static String X_SESSION_CWD = "X-Session-Cwd";

    public final static String FLAG_VERSION = "version";

    public final static String FLAG_RUN = "run";
    public final static String FLAG_SERVE = "serve";
    public final static String FLAG_ACP = "acp";
    public final static String FLAG_WEB = "web";

    public final static String SCOPE_USER = "user"; //作用域：用户（用局）
    public final static String SCOPE_LOCAL = "workspace"; //作用域：本地

    public static String getVersion() {
        return "v2026.6.24";
    }

    private static String lastVersion;

    public static String getLastVersion() {
        if (lastVersion == null) {
            try {
                String json = HttpUtils.http("https://solon.noear.org/soloncode/info.json")
                        .timeout(2)
                        .get();

                lastVersion = ONode.ofJson(json).get("cli_version").getValueAs();
            } catch (Throwable e) {
                LOG.warn("Update detection failed: {}", e.getMessage());
            }
        }

        return lastVersion;
    }


    public static boolean checkUpdate() {
        String tmp = getLastVersion();
        if (tmp != null) {
            Date lastDate = DateUtil.parseTry(tmp.substring(1));
            Date currDate = DateUtil.parseTry(getVersion().substring(1));

            if (lastDate != null && currDate != null) {
                if (lastDate.getTime() > currDate.getTime()) {
                    return true;
                }
            }
        }

        return false;
    }

    //------------------

    //马具目录
    private static final String harnessHome = ".soloncode/";

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

    public static String getUserExtensions() {
        return Paths.get(getUserHome(), getHarnessHome(), "extensions").toString();
    }

    public static URL getConfigUrl() throws MalformedURLException {
        //1. 工作区配置
        Path path = Paths.get(getUserDir(), getHarnessHome(), NAME_CONFIG_YML);
        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //2. 用户目录区配置
        path = Paths.get(getUserHome(), getHarnessHome(), NAME_CONFIG_YML);

        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        return null;
    }

    public static URL getAgentsUrl() throws MalformedURLException {
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

        return null;
    }

    public static String getAgentsMd() {
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

    /**
     * 马具主目录
     */
    public static final String getHarnessHome() {
        return harnessHome;
    }

    /**
     * 马具会话存放区
     */
    public static final String getHarnessSessions() {
        return getHarnessHome() + "sessions/";
    }

    /**
     * 马具技能存放区
     */
    public static final String getHarnessSkills() {
        return getHarnessHome() + "skills/";
    }

    /**
     * 马具子代理描述存放区
     */
    public static final String getHarnessAgents() {
        return getHarnessHome() + "agents/";
    }

    /**
     * 马具命令描述存放区
     */
    public static final String getHarnessCommands() {
        return getHarnessHome() + "commands/";
    }

    /**
     * 马具记忆存放区
     */
    public static final String getHarnessMemory() {
        return getHarnessHome() + "memory/";
    }

    /**
     * 马具下载存放区
     */
    public static final String getHarnessDownload() {
        return getHarnessHome() + "download/";
    }

    /**
     * 马具连接通道存放区
     */
    public static final String getHarnessChannels() {
        return getHarnessHome() + "channels/";
    }

    /**
     * 马具循环任务状态存放区
     */
    public static final String getHarnessLoops() {
        return getHarnessHome() + "loops/";
    }


}

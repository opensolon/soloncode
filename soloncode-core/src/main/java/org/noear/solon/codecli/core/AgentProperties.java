package org.noear.solon.codecli.core;

import lombok.Getter;
import lombok.Setter;

import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessProperties;

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
    /**
     * @deprecated 2026.4.10 {@link #getModels()}
     * */
    @Deprecated
    private ChatConfig chatModel;

    public final static String OPENCODE_SKILLS = ".opencode/skills/";
    public final static String CLAUDE_SKILLS = ".claude/skills/";

    public final static String X_SESSION_ID = "X-Session-Id";
    public final static String X_SESSION_CWD = "X-Session-Cwd";

    public final static String ARG_SESSION = "session";

    private String sessionId = "default"; //默认会话

    private boolean thinkPrinted = false;

    private boolean cliEnabled = true;
    private boolean cliPrintSimplified = true;

    private boolean webEnabled = false;
    private String webEndpoint = "/cli";

    private boolean acpEnabled = false;
    private String acpTransport = "stdio";
    private String acpEndpoint = "/acp";

    private boolean wsEnabled = false;
    private String wsEndpoint = "/ws";

    private String startupSessionMode = "resume";
    private String uiType = "old";
    private String uiTheme = "solon";
    private Map<String, Map<String, String>> uiThemes;

    public AgentProperties() {
        super(".soloncode/");

        getSkillPools().put("@opencode_skills", Paths.get(getWorkspace(), OPENCODE_SKILLS).toString());
        getSkillPools().put("@claude_skills", Paths.get(getWorkspace(), CLAUDE_SKILLS).toString());
    }
}
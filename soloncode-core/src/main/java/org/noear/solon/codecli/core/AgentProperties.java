package org.noear.solon.codecli.core;

import lombok.Getter;
import lombok.Setter;

import org.noear.solon.ai.harness.HarnessProperties;

import java.nio.file.Paths;

/**
 * 代理属性
 *
 * @author noear
 * @since 3.9.1
 */
@Getter
@Setter
public class AgentProperties extends HarnessProperties {
    public final static String OPENCODE_SKILLS = ".opencode/skills/";
    public final static String CLAUDE_SKILLS = ".claude/skills/";

    private String uiType = "old";

    private boolean thinkPrinted = false;

    private boolean cliEnabled = true;
    private boolean cliPrintSimplified = true;

    private boolean webEnabled = false;
    private String webEndpoint = "/cli";

    private boolean acpEnabled = false;
    private String acpTransport = "stdio";
    private String acpEndpoint = "/acp";

    private boolean wsEnabled = true;
    private String wsEndpoint = "/ws";

    public AgentProperties() {
        super(".soloncode/");

        getSkillPools().put("@opencode_skills", Paths.get(getWorkspace(), OPENCODE_SKILLS).toString());
        getSkillPools().put("@claude_skills", Paths.get(getWorkspace(), CLAUDE_SKILLS).toString());
    }
}
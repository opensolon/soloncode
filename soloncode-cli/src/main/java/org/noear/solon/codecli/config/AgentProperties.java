package org.noear.solon.codecli.config;

import lombok.Getter;
import lombok.Setter;

import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.talents.lsp.LspServerParameters;
import org.noear.solon.codecli.config.entity.ApiSourceDo;
import org.noear.solon.codecli.config.entity.LspServerDo;
import org.noear.solon.codecli.config.entity.McpServerDo;
import org.noear.solon.codecli.config.entity.ModelDo;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 代理属性（相关配置从 config.yml, AgentProperties - 慢慢过度到 settings.json, AgentSettings）
 *
 * @author noear
 * @since 3.9.1
 */
@Getter
public class AgentProperties implements Serializable {
    /**
     * @deprecated 2026.4.10 {@link #getModels()}
     *
     */
    @Deprecated
    private ModelDo chatModel;

    //主代理工具权限
    private List<String> tools = new ArrayList<>();

    // 禁用工具（全局）
    private List<String> disallowedTools = new ArrayList<>();

    //最大步数
    @Deprecated
    private Integer maxSteps;
    private Integer maxTurns;

    //自我反思
    private boolean autoRethink = true;

    private int sessionWindowSize = 8;

    private int summaryWindowSize = 40;
    private int summaryWindowToken = 64_000;
    private String summaryModel; //摘要大模型

    private boolean memoryIsolation = true;
    private boolean memoryEnabled = true;

    private boolean sandboxMode = true;
    private boolean sandboxAllowUserHome = true;
    private boolean sandboxSystemRestrict = false;

    private boolean hitlEnabled = false;
    private boolean subagentEnabled = true;
    private boolean bashAsyncEnabled = false;

    private boolean mcpEnabled = true;
    private boolean openApiEnabled = true;
    private boolean lspEnabled = true;

    private String userAgent = "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; SolonCode/1.0 like claude-code; +https://solon.noear.org/)";
    //defaultModel
    private String defaultModel;

    //api 重试次数
    private int apiRetries = 3;
    //Mcp 重试次数
    private int mcpRetries = 3;
    //模型重试次数
    private int modelRetries = 3;

    //扩展
    private List<HarnessExtension> extensions = new ArrayList<>();

    //大模型
    private List<ModelDo> models = new ArrayList<>();
    /**
     * @deprecated 4.0.0
     */
    @Deprecated
    private Map<String, String> skillPools = new LinkedHashMap<>();
    /**
     * @deprecated 4.0.0
     */
    @Deprecated
    private List<String> agentPools = new ArrayList<>();
    //mcp集
    private Map<String, McpServerDo> mcpServers = new LinkedHashMap<>();
    //api集
    private Map<String, ApiSourceDo> apiServers = new LinkedHashMap<>();
    //lsp集
    private Map<String, LspServerDo> lspServers = new LinkedHashMap<>();

    private boolean thinkPrinted = false;
    private boolean cliPrintSimplified = true;
    private boolean goalsEnabled = true;


    //---------------

    /**
     * @deprecated 4.0.0
     *
     */
    @Deprecated
    public Map<String, String> getSkillPools() {
        return skillPools;
    }


    public List<ModelDo> getModels() {
        return models;
    }


    public boolean isAutoRethink() {
        return autoRethink;
    }

    public Integer getMaxTurns() {
        if (maxTurns == null) {
            return maxSteps;
        } else {
            return maxTurns;
        }
    }
}
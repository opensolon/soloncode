package org.noear.solon.codecli.config;

import lombok.Getter;
import lombok.Setter;
import org.noear.solon.codecli.config.entity.*;
import org.noear.solon.core.util.Assert;
import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.solon.ai.talents.mount.MountType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对应 ~/.soloncode/settings.json
 * <p>统一管理 LLM 模型、MCP 服务器、OpenApi 服务器的持久化配置。</p>
 *
 * @author noear 2026/5/29 created
 */
@Getter
@Setter
public class AgentSettings implements Serializable {
    private static final Logger LOG = LoggerFactory.getLogger(AgentSettings.class);

    //general 常规
    private final GeneralGroupDo general = new GeneralGroupDo();
    //permission 权限
    private final PermissionGroupDo permission = new PermissionGroupDo();
    //loop Goal 配置
    private final LoopGroupDo loop = new LoopGroupDo();

    //defaultModel
    private String defaultModel;
    //models
    private Map<String, ModelDo> models = new LinkedHashMap<>();
    //挂载
    private Map<String, MountDo> mountPools = new LinkedHashMap<>();

    //mcp集
    private Map<String, McpServerDo> mcpServers = new LinkedHashMap<>();
    //api集
    private Map<String, ApiSourceDo> apiServers = new LinkedHashMap<>();
    //lsp集
    private Map<String, LspServerDo> lspServers = new LinkedHashMap<>();
    //供应商集
    private Map<String, ProviderDo> providers = new LinkedHashMap<>();

    /**
     * 与 HarnessProperties（即 AgentProperties）双向合并。
     * <p>如果 settings 有数据，以 settings 为准同步到 props；
     * 如果 settings 为空，则从 props 补充到 settings。</p>
     */
    public void mergeFrom(AgentProperties props) {
        if (general.getSessionWindowSize() == null) {
            general.setSessionWindowSize(props.getSessionWindowSize());
        }

        if (general.getSummaryWindowSize() == null) {
            general.setSummaryWindowSize(props.getSummaryWindowSize());
        }

        if (general.getSummaryWindowToken() == null) {
            general.setSummaryWindowToken(props.getSummaryWindowToken());
        }

        if(general.getSummaryModel() == null){
            general.setSummaryModel(props.getSummaryModel());
        }

        if (general.getSandboxMode() == null) {
            general.setSandboxMode(props.isSandboxMode());
        }

        if (general.getSandboxAllowUserHome() == null) {
            general.setSandboxAllowUserHome(props.isSandboxAllowUserHome());
        }

        if (general.getSandboxSystemRestrict() == null) {
            general.setSandboxSystemRestrict(props.isSandboxSystemRestrict());
        }

        if (general.getApiRetries() == null) {
            general.setApiRetries(props.getApiRetries());
        }

        if (general.getMcpRetries() == null) {
            general.setMcpRetries(props.getMcpRetries());
        }

        if (general.getModelRetries() == null) {
            general.setModelRetries(props.getModelRetries());
        }

        if (general.getBashAsyncEnabled() == null) {
            general.setBashAsyncEnabled(props.isBashAsyncEnabled());
        }

        if (general.getMemoryEnabled() == null) {
            general.setMemoryEnabled(props.isMemoryEnabled());
        }

        if (general.getMemoryIsolation() == null) {
            general.setMemoryIsolation(props.isMemoryIsolation());
        }

        if (general.getMcpEnabled() == null) {
            general.setMcpEnabled(props.isMcpEnabled());
        }

        if (general.getOpenApiEnabled() == null) {
            general.setOpenApiEnabled(props.isOpenApiEnabled());
        }

        if (general.getLspEnabled() == null) {
            general.setLspEnabled(props.isLspEnabled());
        }

        if(general.getUserAgent() == null){
            general.setUserAgent(props.getUserAgent());
        }

        if(general.getMaxTurns() == null) {
            general.setMaxTurns(props.getMaxTurns());

            if (general.getMaxTurns() == null) {
                general.setMaxTurns(20);
            }
        }

        if(general.getAutoRethink() == null){
            general.setAutoRethink(props.isAutoRethink());
        }

        if(general.getHitlEnabled() == null){
            general.setHitlEnabled(props.isHitlEnabled());
        }

        if(general.getSubagentEnabled() == null){
            general.setSubagentEnabled(props.isSubagentEnabled());
        }

        if(general.getCliPrintSimplified() == null){
            general.setCliPrintSimplified(props.isCliPrintSimplified());
        }

        if(general.getGoalsEnabled() == null){
            general.setGoalsEnabled(props.isGoalsEnabled());
        }

        if(general.getCliThinkPrinted() == null){
            general.setCliThinkPrinted(props.isThinkPrinted());
        }

        //-----------------------------------------------------

        // loop: 从 app.yml 的 soloncode.loop.* 回填（仅当 settings.json 未配置时）
        try {
            org.noear.solon.core.Props cfg = org.noear.solon.Solon.cfg();
            if (loop.getBudgetWarningPercent() == null)
                loop.setBudgetWarningPercent(cfg.getInt("soloncode.loop.budgetWarningPercent", 70));
            if (loop.getBudgetCriticalPercent() == null)
                loop.setBudgetCriticalPercent(cfg.getInt("soloncode.loop.budgetCriticalPercent", 85));
            if (loop.getDefaultMaxTokens() == null)
                loop.setDefaultMaxTokens(cfg.getLong("soloncode.loop.defaultMaxTokens", 0L));
            if (loop.getDefaultMaxDurationMinutes() == null)
                loop.setDefaultMaxDurationMinutes(cfg.getInt("soloncode.loop.defaultMaxDurationMinutes", 0));
            if (loop.getStagnationThreshold() == null)
                loop.setStagnationThreshold(cfg.getInt("soloncode.loop.stagnationThreshold", 3));
            if (loop.getMaxConsecutiveErrors() == null)
                loop.setMaxConsecutiveErrors(cfg.getInt("soloncode.loop.maxConsecutiveErrors", 3));
            if (loop.getPauseAutoAbandonHours() == null)
                loop.setPauseAutoAbandonHours(cfg.getInt("soloncode.loop.pauseAutoAbandonHours", 24));
            if (loop.getValidatorEnabled() == null)
                loop.setValidatorEnabled(cfg.getBool("soloncode.loop.validatorEnabled", true));
        } catch (Exception ignored) {
            // 非 Solon 环境时保持 null，便捷方法提供默认值
        }

        //-----------------------------------------------------

        if(permission.getTools().size() == 0) {
            permission.getTools().addAll(props.getTools());

            if (permission.getTools().size() == 0) {
                permission.getTools().add("**");
            }
        }

        if(permission.getDisallowedTools().size() == 0){
            permission.getDisallowedTools().addAll(props.getDisallowedTools());
        }

        //-----------------------------------------------------

        if (Assert.isEmpty(this.defaultModel)) {
            this.defaultModel = props.getDefaultModel();
        }

        if (this.models.size() == 0) {
            for (ModelDo modelDo : props.getModels()) {
                this.models.put(modelDo.getNameOrModel(), modelDo);
            }
        }

        // 合并完成后统一兜底：如果 defaultModel 未指定，取第一个模型
        if (Assert.isEmpty(this.defaultModel) && this.models.size() > 0) {
            this.defaultModel = this.models.values().iterator().next().getNameOrModel();
        }

        if (this.mcpServers.size() == 0) {
            this.mcpServers.putAll(props.getMcpServers());
        }

        if (this.apiServers.size() == 0) {
            this.apiServers.putAll(props.getApiServers());
        }

        if (this.mountPools.size() == 0) {
            for (Map.Entry<String, String> entry : props.getSkillPools().entrySet()) {
                this.mountPools.put(entry.getKey(), new MountDo(AgentFlags.SCOPE_USER, "", MountType.SKILLS, entry.getValue(), false, true, false));
            }
        }

        if (this.lspServers.size() == 0) {
            this.lspServers.putAll(props.getLspServers());
        }
    }

    /**
     * 从文件加载配置
     */
    public static AgentSettings loadFromFile() {
        try {
            Path globalFile = Paths.get(AgentFlags.getUserHome(), ".soloncode", "settings.json").toAbsolutePath();
            Path localFile = Paths.get(AgentFlags.getUserDir(), ".soloncode", "settings.json").toAbsolutePath();
            boolean isLocalAsGlobal = localFile.toString().equals(globalFile.toString());

            AgentSettings agentSettings = new AgentSettings();


            if (Files.exists(globalFile)) {
                //全局配置
                String json = new String(Files.readAllBytes(globalFile), "UTF-8");
                ONode oNode = ONode.ofJson(json);

                ONode oModels = oNode.get("models");
                if (oModels.isArray()) { //旧格式，转成新格式
                    ONode map = new ONode().asObject();
                    for (ONode item : oModels.getArrayUnsafe()) {
                        map.set(item.get("name").getString(), item);
                    }
                    oNode.set("models", map);
                }

                oNode.bindTo(agentSettings);
            }

            if (isLocalAsGlobal == false) {
                //如果本地文件，不同于全局文件
                if (Files.exists(localFile)) {
                    //工作区配置
                    String json = new String(Files.readAllBytes(localFile), "UTF-8");
                    ONode oNode = ONode.ofJson(json);

                    ONode oModels = oNode.get("models");
                    if (oModels.isArray()) { //旧格式，转成新格式
                        ONode map = new ONode().asObject();
                        for (ONode item : oModels.getArrayUnsafe()) {
                            map.set(item.get("name").getString(), item);
                        }
                        oNode.set("models", map);
                    }

                    oNode.bindTo(agentSettings);
                }
            }

            return agentSettings;
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to load settings from file: {}", e.getMessage());
            return new AgentSettings();
        }
    }

    /**
     * 保存配置到文件
     */
    public void saveToFile() {
        try {
            Path globalFileOld = Paths.get(AgentFlags.getUserHome(), ".soloncode", "config.yml").toAbsolutePath();
            Path localFileOld = Paths.get(AgentFlags.getUserDir(), ".soloncode", "config.yml").toAbsolutePath();

            Path globalFile = Paths.get(AgentFlags.getUserHome(), ".soloncode", "settings.json").toAbsolutePath();
            Path localFile = Paths.get(AgentFlags.getUserDir(), ".soloncode", "settings.json").toAbsolutePath();
            boolean isLocalAsGlobal = localFile.toString().equals(globalFile.toString());

            Files.createDirectories(globalFile.getParent());
            Files.write(globalFile, getGlobalJson(isLocalAsGlobal).getBytes("UTF-8"));
            Files.deleteIfExists(globalFileOld); //有新配置后，去掉旧配置


            if (isLocalAsGlobal == false) {
                //如果本地文件，不同于全局文件
                Files.createDirectories(localFile.getParent());
                Files.write(localFile, getLocalJson().getBytes("UTF-8"));
                Files.deleteIfExists(localFileOld); //有新配置后，去掉旧配置
            }
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to save settings to file: {}", e.getMessage());
        }
    }


    public String getGlobalJson(boolean isLocalAsGlobal) {
        ONode oNode = new ONode(Options.of(Feature.Write_PrettyFormat));
        oNode.set("$schema", "https://solon.noear.org/soloncode/settings.schema.json");

        oNode.getOrNew("general").fill(general);
        oNode.getOrNew("permission").fill(permission);
        oNode.getOrNew("loop").fill(loop);

        oNode.set("defaultModel", this.defaultModel);

        oNode.getOrNew("models").asObject().then(map -> {
            for (Map.Entry<String, ModelDo> entry : models.entrySet()) {
                if (isLocalAsGlobal == false && AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope())) {
                    continue;
                }

                map.getOrNew(entry.getValue().getNameOrModel()).then(item -> {
                    item.fill(entry.getValue());
                    item.remove("userAgent");

                    if (entry.getValue().getTimeout() != null) {
                        item.set("timeout", entry.getValue().getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        oNode.getOrNew("mcpServers").asObject().then(map -> {
            for (Map.Entry<String, McpServerDo> entry : mcpServers.entrySet()) {
                if (isLocalAsGlobal == false && AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope())) {
                    continue;
                }

                map.getOrNew(entry.getKey()).then(item -> {
                    item.fill(entry.getValue());

                    if (entry.getValue().getTimeout() != null) {
                        item.set("timeout", entry.getValue().getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        oNode.getOrNew("apiServers").asObject().then(map -> {
            for (Map.Entry<String, ApiSourceDo> entry : apiServers.entrySet()) {
                if (isLocalAsGlobal == false && AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope())) {
                    continue;
                }

                map.getOrNew(entry.getKey()).then(item -> {
                    item.fill(entry.getValue());

                    if (entry.getValue().getTimeout() != null) {
                        item.set("timeout", entry.getValue().getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        oNode.getOrNew("mountPools").asObject().then(map -> {
            for (Map.Entry<String, MountDo> entry : mountPools.entrySet()) {
                if (isLocalAsGlobal == false && AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope())) {
                    continue;
                }

                map.getOrNew(entry.getKey()).fill(entry.getValue());
            }
        });

        oNode.getOrNew("lspServers").asObject().then(map -> {
            for (Map.Entry<String, LspServerDo> entry : lspServers.entrySet()) {
                if (isLocalAsGlobal == false && AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope())) {
                    continue;
                }

                map.getOrNew(entry.getKey()).fill(entry.getValue());
            }
        });

        oNode.getOrNew("providers").asObject().then(map -> {
            for (Map.Entry<String, ProviderDo> entry : providers.entrySet()) {
                if (isLocalAsGlobal == false && AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope())) {
                    continue;
                }

                map.getOrNew(entry.getKey()).fill(entry.getValue());
            }
        });

        return oNode.toJson();
    }

    public String getLocalJson() {
        ONode oNode = new ONode(Options.of(Feature.Write_PrettyFormat));
        oNode.set("$schema", "https://solon.noear.org/soloncode/settings.schema.json");

        oNode.getOrNew("models").asObject().then(map -> {
            for (Map.Entry<String, ModelDo> entry : models.entrySet()) {
                if (AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope()) == false) {
                    continue;
                }

                map.getOrNew(entry.getValue().getNameOrModel()).then(item -> {
                    item.fill(entry.getValue());
                    item.remove("userAgent");

                    if (entry.getValue().getTimeout() != null) {
                        item.set("timeout", entry.getValue().getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        oNode.getOrNew("mcpServers").asObject().then(map -> {
            for (Map.Entry<String, McpServerDo> entry : mcpServers.entrySet()) {
                if (AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope()) == false) {
                    continue;
                }

                map.getOrNew(entry.getKey()).then(item -> {
                    item.fill(entry.getValue());

                    if (entry.getValue().getTimeout() != null) {
                        item.set("timeout", entry.getValue().getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        oNode.getOrNew("apiServers").asObject().then(map -> {
            for (Map.Entry<String, ApiSourceDo> entry : apiServers.entrySet()) {
                if (AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope()) == false) {
                    continue;
                }

                map.getOrNew(entry.getKey()).then(item -> {
                    item.fill(entry.getValue());

                    if (entry.getValue().getTimeout() != null) {
                        item.set("timeout", entry.getValue().getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        oNode.getOrNew("mountPools").asObject().then(map -> {
            for (Map.Entry<String, MountDo> entry : mountPools.entrySet()) {
                if (AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope()) == false) {
                    continue;
                }

                map.getOrNew(entry.getKey()).fill(entry.getValue());
            }
        });

        oNode.getOrNew("lspServers").asObject().then(map -> {
            for (Map.Entry<String, LspServerDo> entry : lspServers.entrySet()) {
                if (AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope()) == false) {
                    continue;
                }

                map.getOrNew(entry.getKey()).fill(entry.getValue());
            }
        });

        oNode.getOrNew("providers").asObject().then(map -> {
            for (Map.Entry<String, ProviderDo> entry : providers.entrySet()) {
                if (AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope()) == false) {
                    continue;
                }

                map.getOrNew(entry.getKey()).fill(entry.getValue());
            }
        });

        return oNode.toJson();
    }
}
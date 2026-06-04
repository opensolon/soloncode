package org.noear.solon.codecli.config;

import lombok.Getter;
import lombok.Setter;
import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.codecli.config.entity.ApiSourceDo;
import org.noear.solon.codecli.config.entity.McpServerDo;
import org.noear.solon.codecli.config.entity.ModelDo;
import org.noear.solon.codecli.config.entity.LspServerDo;
import org.noear.solon.codecli.config.entity.MountDo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private GeneralSettings general = new GeneralSettings();
    //models
    private List<ModelDo> models = new ArrayList<>();
    //挂载
    private Map<String, MountDo> mountPools = new LinkedHashMap<>();

    //mcp集
    private Map<String, McpServerDo> mcpServers = new LinkedHashMap<>();
    //api集
    private Map<String, ApiSourceDo> apiServers = new LinkedHashMap<>();
    //lsp集
    private Map<String, LspServerDo> lspServers = new LinkedHashMap<>();

    /**
     * 与 HarnessProperties（即 AgentProperties）双向合并。
     * <p>如果 settings 有数据，以 settings 为准同步到 props；
     * 如果 settings 为空，则从 props 补充到 settings。</p>
     */
    public void mergeFrom(AgentProperties props) {
        if (general.getSessionWindowSize() != null) {
            props.setSessionWindowSize(general.getSessionWindowSize());
        } else {
            general.setSessionWindowSize(props.getSessionWindowSize());
        }

        if (general.getSummaryWindowSize() != null) {
            props.setSummaryWindowSize(general.getSummaryWindowSize());
        } else {
            general.setSummaryWindowSize(props.getSummaryWindowSize());
        }

        if (general.getSummaryWindowToken() != null) {
            props.setSummaryWindowToken(general.getSummaryWindowToken());
        } else {
            general.setSummaryWindowToken(props.getSummaryWindowToken());
        }

        if (general.getSandboxMode() != null) {
            props.setSandboxMode(general.getSandboxMode());
        } else {
            general.setSandboxMode(props.isSandboxMode());
        }

        if (general.getApiRetries() != null) {
            props.setApiRetries(general.getApiRetries());
        } else {
            general.setApiRetries(props.getApiRetries());
        }

        if (general.getMcpRetries() != null) {
            props.setMcpRetries(general.getMcpRetries());
        } else {
            general.setMcpRetries(props.getMcpRetries());
        }

        if (general.getModelRetries() != null) {
            props.setModelRetries(general.getModelRetries());
        } else {
            general.setModelRetries(props.getModelRetries());
        }

        if (general.getBashAsyncEnabled() != null) {
            props.setBashAsyncEnabled(general.getBashAsyncEnabled());
        } else {
            general.setBashAsyncEnabled(props.isBashAsyncEnabled());
        }

        if (general.getMemoryEnabled() != null) {
            props.setMemoryEnabled(general.getMemoryEnabled());
        } else {
            general.setMemoryEnabled(props.isMemoryEnabled());
        }

        if (general.getMcpEnabled() != null) {
            props.setMcpEnabled(general.getMcpEnabled());
        } else {
            general.setMcpEnabled(props.isMcpEnabled());
        }

        if (general.getOpenApiEnabled() != null) {
            props.setOpenApiEnabled(general.getOpenApiEnabled());
        } else {
            general.setOpenApiEnabled(props.isOpenApiEnabled());
        }

        if (general.getLspEnabled() != null) {
            props.setLspEnabled(general.getLspEnabled());
        } else {
            general.setLspEnabled(props.isLspEnabled());
        }

        //-------------

        if (this.models.size() > 0) {
            props.getModels().clear();
            props.getModels().addAll(this.models);
        } else {
            this.models.addAll(props.getModels());
        }

        if (this.mcpServers.size() > 0) {
            props.getMcpServers().clear();
            props.getMcpServers().putAll(this.mcpServers);
        } else {
            this.mcpServers.putAll(props.getMcpServers());
        }

        if (this.apiServers.size() > 0) {
            props.getApiServers().clear();
            props.getApiServers().putAll(this.apiServers);
        } else {
            this.apiServers.putAll(props.getApiServers());
        }

        if (this.mountPools.size() > 0) {
            props.getSkillPools().clear();
            for (Map.Entry<String, MountDo> entry : this.mountPools.entrySet()) {
                props.getSkillPools().put(entry.getKey(), entry.getValue().getPath());
            }
        } else {
            for (Map.Entry<String, String> entry : props.getSkillPools().entrySet()) {
                this.mountPools.put(entry.getKey(), new MountDo(AgentFlags.SCOPE_GLOBAL, "", MountType.SKILLS, entry.getValue(), false, true, false));
            }
        }

        if (this.lspServers.size() > 0) {
            props.getLspServers().clear();
            props.getLspServers().putAll(this.lspServers);
        } else {
            this.lspServers.putAll(props.getLspServers());
        }
    }

    /**
     * 从文件加载配置
     */
    public static AgentSettings loadFromFile() {
        try {
            Path globalFile = Paths.get(AgentProperties.getUserHome(), ".soloncode", "settings.json");
            Path localFile = Paths.get(AgentProperties.getUserDir(), ".soloncode", "settings.json");

            AgentSettings agentSettings = new AgentSettings();


            if (Files.exists(globalFile)) {
                //全局配置
                String json = new String(Files.readAllBytes(globalFile), "UTF-8");
                ONode.ofJson(json).bindTo(agentSettings);
            }

            if (Files.exists(localFile)) {
                //工作区配置
                String json = new String(Files.readAllBytes(localFile), "UTF-8");
                ONode.ofJson(json).bindTo(agentSettings);
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
            Path globalFile = Paths.get(AgentProperties.getUserHome(), ".soloncode", "settings.json");
            Path localFile = Paths.get(AgentProperties.getUserDir(), ".soloncode", "settings.json");

            Files.createDirectories(globalFile.getParent());
            Files.write(globalFile, getGlobalJson().getBytes("UTF-8"));

            Files.createDirectories(localFile.getParent());
            Files.write(localFile, getLocalJson().getBytes("UTF-8"));
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to save settings to file: {}", e.getMessage());
        }
    }


    public String getGlobalJson() {
        ONode oNode = new ONode(Options.of(Feature.Write_PrettyFormat));

        oNode.getOrNew("general").fill(general);

        oNode.getOrNew("models").asArray().then(ary -> {
            for (ModelDo entry : models) {
                if(AgentFlags.SCOPE_LOCAL.equals(entry.getScope())){
                    continue;
                }

                ary.addNew().then(item -> {
                    item.fill(entry);
                    item.remove("userAgent");

                    if (entry.getTimeout() != null) {
                        item.set("timeout", entry.getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        oNode.getOrNew("mcpServers").asObject().then(map -> {
            for (Map.Entry<String, McpServerDo> entry : mcpServers.entrySet()) {
                if(AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope())){
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
                if(AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope())){
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
                if(AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope())){
                    continue;
                }

                map.getOrNew(entry.getKey()).fill(entry.getValue());
            }
        });

        oNode.getOrNew("lspServers").asObject().then(map -> {
            for (Map.Entry<String, LspServerDo> entry : lspServers.entrySet()) {
                if(AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope())){
                    continue;
                }
                map.getOrNew(entry.getKey()).fill(entry.getValue());
            }
        });

        return oNode.toJson();
    }

    public String getLocalJson() {
        ONode oNode = new ONode(Options.of(Feature.Write_PrettyFormat));

        oNode.getOrNew("models").asArray().then(ary -> {
            for (ModelDo entry : models) {
                if(AgentFlags.SCOPE_LOCAL.equals(entry.getScope()) == false){
                    continue;
                }

                ary.addNew().then(item -> {
                    item.fill(entry);
                    item.remove("userAgent");

                    if (entry.getTimeout() != null) {
                        item.set("timeout", entry.getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        oNode.getOrNew("mcpServers").asObject().then(map -> {
            for (Map.Entry<String, McpServerDo> entry : mcpServers.entrySet()) {
                if(AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope()) == false){
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
                if(AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope()) == false){
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
                if(AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope()) == false){
                    continue;
                }

                map.getOrNew(entry.getKey()).fill(entry.getValue());
            }
        });

        oNode.getOrNew("lspServers").asObject().then(map -> {
            for (Map.Entry<String, LspServerDo> entry : lspServers.entrySet()) {
                if(AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope()) == false){
                    continue;
                }
                map.getOrNew(entry.getKey()).fill(entry.getValue());
            }
        });

        return oNode.toJson();
    }
}
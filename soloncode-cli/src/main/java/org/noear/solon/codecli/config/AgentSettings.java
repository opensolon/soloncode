package org.noear.solon.codecli.config;

import lombok.Getter;
import lombok.Setter;
import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.ai.talents.openapi.ApiSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private List<ChatConfig> models = new ArrayList<>();
    //mcp集
    private Map<String, McpServerParameters> mcpServers = new LinkedHashMap<>();
    //api集
    private Map<String, ApiSource> apiServers = new LinkedHashMap<>();
    //挂载
    private Map<String, MountDo> mountPools = new LinkedHashMap<>();

    /**
     * 从 JSON 字符串反序列化
     */
    public static AgentSettings fromJson(String json) {
        return ONode.ofJson(json).toBean(AgentSettings.class);
    }

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
                this.mountPools.put(entry.getKey(), new MountDo("", MountType.SKILLS, entry.getValue(), false, true, false));
            }
        }
    }

    /**
     * 从文件加载配置
     */
    public static AgentSettings loadFromFile(Path file) {
        try {
            if (!Files.exists(file)) {
                return new AgentSettings();
            }
            String content = new String(Files.readAllBytes(file), "UTF-8");
            return fromJson(content);
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to load settings from file: {}", e.getMessage());
            return new AgentSettings();
        }
    }

    /**
     * 保存配置到文件
     */
    public void saveToFile(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, toJson().getBytes("UTF-8"));
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to save settings to file: {}", e.getMessage());
        }
    }

    /**
     * 序列化为 JSON
     */
    public String toJson() {
        ONode oNode = new ONode(Options.of(Feature.Write_PrettyFormat));

        oNode.getOrNew("general").fill(general);

        oNode.getOrNew("models").asArray().then(ary -> {
            for (ChatConfig entry : models) {
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
            for (Map.Entry<String, McpServerParameters> entry : mcpServers.entrySet()) {
                map.getOrNew(entry.getKey()).then(item -> {
                    item.fill(entry.getValue());

                    if (entry.getValue().getTimeout() != null) {
                        item.set("timeout", entry.getValue().getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        oNode.getOrNew("apiServers").asObject().then(map -> {
            for (Map.Entry<String, ApiSource> entry : apiServers.entrySet()) {
                map.getOrNew(entry.getKey()).then(item -> {
                    item.fill(entry.getValue());

                    if (entry.getValue().getTimeout() != null) {
                        item.set("timeout", entry.getValue().getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        oNode.getOrNew("mountPools").fill(mountPools);

        return oNode.toJson();
    }
}
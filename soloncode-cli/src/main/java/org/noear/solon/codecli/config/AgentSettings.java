package org.noear.solon.codecli.config;

import lombok.Getter;
import lombok.Setter;
import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.skills.openapi.ApiSource;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对应 ~/.soloncode/settings.json
 *
 * @author noear 2026/5/29 created
 */
@Getter
@Setter
public class AgentSettings implements Serializable {
    private List<ChatConfig> models = new ArrayList<>();
    //mcp集
    private Map<String, McpServerParameters> mcpServers = new LinkedHashMap<>();
    //api集
    private Map<String, ApiSource> apiServers = new LinkedHashMap<>();

    public static AgentSettings fromJson(String json) {
        return ONode.ofJson(json).toBean(AgentSettings.class);
    }

    public String toJson() {
        ONode oNode = new ONode(Options.of(Feature.Write_PrettyFormat));

        oNode.getOrNew("models").asArray().then(ary -> {
            for (ChatConfig entry : models) {
                ary.addNew().then(item -> {
                    item.fill(entry);
                    item.remove("userAgent");

                    if(entry.getTimeout() != null) {
                        item.set("timeout", entry.getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        oNode.getOrNew("mcpServers").asObject().then(map -> {
            for (Map.Entry<String, McpServerParameters> entry : mcpServers.entrySet()) {
                map.getOrNew(entry.getKey()).then(item->{
                    item.fill(entry.getValue());

                    if(entry.getValue().getTimeout() != null) {
                        item.set("timeout", entry.getValue().getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        oNode.getOrNew("apiServers").asObject().then(map -> {
            for (Map.Entry<String, ApiSource> entry : apiServers.entrySet()) {
                map.getOrNew(entry.getKey()).then(item->{
                    item.fill(entry.getValue());

                    if(entry.getValue().getTimeout() != null) {
                        item.set("timeout", entry.getValue().getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        return oNode.toJson();
    }
}
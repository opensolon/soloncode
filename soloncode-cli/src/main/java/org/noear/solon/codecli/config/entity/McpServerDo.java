package org.noear.solon.codecli.config.entity;

import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.codecli.config.AgentFlags;

/**
 *
 * @author noear 2026/6/4 created
 *
 */
public class McpServerDo extends McpServerParameters {
    //作用域（全局或本地）
    private String scope = AgentFlags.SCOPE_GLOBAL;

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getScope() {
        return scope;
    }
}

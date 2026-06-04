package org.noear.solon.codecli.config.entity;

import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.codecli.config.AgentFlags;

/**
 *
 * @author noear 2026/6/4 created
 *
 */
public class ModelDo extends ChatConfig {
    //作用域（全局或本地）
    private String scope = AgentFlags.SCOPE_GLOBAL;

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getScope() {
        return scope;
    }
}

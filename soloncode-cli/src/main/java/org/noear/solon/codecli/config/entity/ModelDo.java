package org.noear.solon.codecli.config.entity;

import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.codecli.config.AgentFlags;

import java.util.Map;

/**
 *
 * @author noear 2026/6/4 created
 *
 */
public class ModelDo extends ChatConfig {
    private boolean visibled = true;

    //作用域（全局或本地）
    private String scope = AgentFlags.SCOPE_USER;
    private Map<String, Object> capabilities;


    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getScope() {
        return scope;
    }

    public boolean isVisibled() {
        return visibled;
    }

    public void setVisibled(boolean visibled) {
        this.visibled = visibled;
    }

    @Override
    public boolean isEnabled() {
        return visibled && enabled;
    }
}

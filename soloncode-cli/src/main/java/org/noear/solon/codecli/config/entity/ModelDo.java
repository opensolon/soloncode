package org.noear.solon.codecli.config.entity;

import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.codecli.config.AgentFlags;

/**
 *
 * @author noear 2026/6/4 created
 *
 */
public class ModelDo extends ChatConfig {
    private boolean visibled = true;

    //作用域（全局或本地）
    private String scope = AgentFlags.SCOPE_GLOBAL;

    //所属供应商（通过 ProviderDo.name 关联）
    private String provider;

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getScope() {
        return scope;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getProvider() {
        return provider;
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

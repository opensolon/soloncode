package org.noear.solon.codecli.config.entity;

import org.noear.solon.ai.talents.openapi.ApiSource;
import org.noear.solon.codecli.config.AgentFlags;

/**
 *
 * @author noear 2026/6/4 created
 *
 */
public class ApiSourceDo extends ApiSource {
    //作用域（全局或本地）
    private String scope = AgentFlags.SCOPE_LOCAL;

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }
}

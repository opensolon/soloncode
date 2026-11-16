package org.noear.solon.codecli.mcpmarket;

/**
 * 连接器市场条目的可配置参数（环境变量或请求头）。
 *
 * <p>来自官方 Registry 的 {@code packages[].environmentVariables[]} 与
 * {@code remotes[].headers[]}，统一为一个结构，便于前端渲染必填提示。</p>
 *
 * @author noear 2026/11/16 created
 */
public class McpMarketParam {
    /** 参数所在位置：env（环境变量）/ header（请求头） */
    public static final String IN_ENV = "env";
    public static final String IN_HEADER = "header";

    private String name;
    private String description;
    private boolean required;
    private boolean secret;
    private String defaultValue;
    private String in = IN_ENV;

    public McpMarketParam() {
    }

    public McpMarketParam name(String name) { this.name = name; return this; }
    public McpMarketParam description(String description) { this.description = description; return this; }
    public McpMarketParam required(boolean required) { this.required = required; return this; }
    public McpMarketParam secret(boolean secret) { this.secret = secret; return this; }
    public McpMarketParam defaultValue(String defaultValue) { this.defaultValue = defaultValue; return this; }
    public McpMarketParam in(String in) { this.in = in; return this; }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isRequired() { return required; }
    public boolean isSecret() { return secret; }
    public String getDefaultValue() { return defaultValue; }
    public String getIn() { return in; }

    /**
     * 是否需要用户手工填写（必填、或需要保密的值都不预填）。
     */
    public boolean isNeedUserInput() {
        return required || secret;
    }
}

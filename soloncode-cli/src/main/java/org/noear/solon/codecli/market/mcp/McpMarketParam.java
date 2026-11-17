package org.noear.solon.codecli.market.mcp;

/**
 * 连接器市场条目的可配置参数（环境变量或请求头）。
 *
 * <p>来自官方 Registry 的 {@code packages[].environmentVariables[]} 与
 * {@code remotes[].headers[]}，统一为一个结构，便于前端渲染必填提示。</p>
 *
 * @author noear 2026/11/16 created
 */
public class McpMarketParam {
    /** 参数所在位置：env（环境变量）/ header（请求头）/ query（URL 查询参数） */
    public static final String IN_ENV = "env";
    public static final String IN_HEADER = "header";
    public static final String IN_QUERY = "query";

    private String name;
    /** 参数说明（多数来自源站，是源站的语言，不翻译） */
    private String description;
    /**
     * 参数说明的本地化版本（本项目自己写的说明，如「API Key 在何处生成」）。
     *
     * <p>有值时前端优先用它，没有才用 {@link #description}。</p>
     */
    private McpLocaleHint descriptionHint;
    private boolean required;
    private boolean secret;
    private String defaultValue;
    private String in = IN_ENV;
    /**
     * 需要预填在等号之后的前缀（如 {@code "Bearer "}）。
     *
     * <p>源站把鉴权方案写在取值模板里（{@code "Bearer {api_key}"}），与密钥本身不在一处；
     * 只给出 {@code Authorization=} 会让用户粘出裸值。带前缀预填后用户直接补 key 即可。</p>
     */
    private String valuePrefix;

    public McpMarketParam() {
    }

    public McpMarketParam name(String name) { this.name = name; return this; }
    public McpMarketParam description(String description) { this.description = description; return this; }
    public McpMarketParam descriptionHint(McpLocaleHint descriptionHint) { this.descriptionHint = descriptionHint; return this; }
    public McpMarketParam required(boolean required) { this.required = required; return this; }
    public McpMarketParam secret(boolean secret) { this.secret = secret; return this; }
    public McpMarketParam defaultValue(String defaultValue) { this.defaultValue = defaultValue; return this; }
    public McpMarketParam in(String in) { this.in = in; return this; }
    public McpMarketParam valuePrefix(String valuePrefix) { this.valuePrefix = valuePrefix; return this; }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public McpLocaleHint getDescriptionHint() { return descriptionHint; }
    public boolean isRequired() { return required; }
    public boolean isSecret() { return secret; }
    public String getDefaultValue() { return defaultValue; }
    public String getIn() { return in; }
    public String getValuePrefix() { return valuePrefix; }

    /**
     * 是否需要用户手工填写（必填、或需要保密的值都不预填）。
     */
    public boolean isNeedUserInput() {
        return required || secret;
    }
}

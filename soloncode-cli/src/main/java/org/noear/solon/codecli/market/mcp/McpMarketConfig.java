package org.noear.solon.codecli.market.mcp;

import java.util.List;

/**
 * 连接器市场条目的某一种接入方式。
 *
 * <p>同一注册表条目通常既提供 stdio 包（npm/pypi/oci），又提供远程地址（remotes），
 * 每一种都对应一条可填写、可保存的 MCP 配置，故单独建模。</p>
 *
 * @author noear 2026/11/16 created
 */
public class McpMarketConfig {
    private String configId;
    private String label;
    /** 市场条目里给这个接入方式起的服务名（如 mcpServers 的键），可用于生成配置名 */
    private String serverKey;
    /** 标准化后的类型：stdio / sse / streamable */
    private String type;
    private String command;
    private List<String> args;
    private String url;
    /** 安装运行时：npx / uvx / docker */
    private String runtime;
    private List<McpMarketParam> params;

    public McpMarketConfig() {
    }

    public McpMarketConfig configId(String configId) { this.configId = configId; return this; }
    public McpMarketConfig label(String label) { this.label = label; return this; }
    public McpMarketConfig serverKey(String serverKey) { this.serverKey = serverKey; return this; }
    public McpMarketConfig type(String type) { this.type = type; return this; }
    public McpMarketConfig command(String command) { this.command = command; return this; }
    public McpMarketConfig args(List<String> args) { this.args = args; return this; }
    public McpMarketConfig url(String url) { this.url = url; return this; }
    public McpMarketConfig runtime(String runtime) { this.runtime = runtime; return this; }
    public McpMarketConfig params(List<McpMarketParam> params) { this.params = params; return this; }

    public String getConfigId() { return configId; }
    public String getLabel() { return label; }
    public String getServerKey() { return serverKey; }
    public String getType() { return type; }
    public String getCommand() { return command; }
    public List<String> getArgs() { return args; }
    public String getUrl() { return url; }
    public String getRuntime() { return runtime; }
    public List<McpMarketParam> getParams() { return params; }
}

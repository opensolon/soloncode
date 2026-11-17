package org.noear.solon.codecli.market.mcp;

import java.util.List;
import java.util.Map;

/**
 * 连接器配置草稿 — 市场条目的「可预填」结果。
 *
 * <p>MCP 市场的「安装」语义与技能市场不同：不是下载解压，而是生成一条
 * {@code McpServerDo} 配置交给表单预填，用户补齐密钥后走原保存链路。
 * 因此这里只产出草稿，不落库、不执行。</p>
 *
 * @author noear 2026/11/16 created
 */
public class McpServerDraft {
    /** 建议的服务名（已做合法性处理，重名由前端追加后缀） */
    private String name;
    /** 标准化类型：stdio / sse / streamable */
    private String type;
    /** 服务描述（来自市场条目，预填到表单的描述字段；会出现在 MCP 网关的服务目录里） */
    private String description;
    private String command;
    private List<String> args;
    private Map<String, String> env;
    private String url;
    private Map<String, String> headers;
    /** 需要用户补填的参数（必填或机密项） */
    private List<McpMarketParam> pendingParams;
    /** 来源市场名 */
    private String source;
    /** 来源条目 id */
    private String sourceId;
    private String configId;
    /**
     * 来源说明（接入前的注意事项，如「另有某些参数需在平台端配置」）。
     *
     * <p>只放 pendingParams 表达不了的信息：参数清单已由前端按必填/可选分组列出，
     * 这里不再重抄一遍参数名，也不复述「取值方式见文档」（文档链接已单独给出）。</p>
     */
    private McpLocaleHint note;
    /**
     * 参数/文档的出处页面（详情页或项目主页）。
     *
     * <p>只在「需要用户补参数」时有意义：提示里说「去广场页面或项目文档查取」时，
     * 得有个能点开的地址，否则用户只能自己搜。</p>
     */
    private String docUrl;

    public McpServerDraft() {
    }

    public McpServerDraft name(String name) { this.name = name; return this; }
    public McpServerDraft type(String type) { this.type = type; return this; }
    public McpServerDraft description(String description) { this.description = description; return this; }
    public McpServerDraft command(String command) { this.command = command; return this; }
    public McpServerDraft args(List<String> args) { this.args = args; return this; }
    public McpServerDraft env(Map<String, String> env) { this.env = env; return this; }
    public McpServerDraft url(String url) { this.url = url; return this; }
    public McpServerDraft headers(Map<String, String> headers) { this.headers = headers; return this; }
    public McpServerDraft pendingParams(List<McpMarketParam> pendingParams) { this.pendingParams = pendingParams; return this; }
    public McpServerDraft source(String source) { this.source = source; return this; }
    public McpServerDraft sourceId(String sourceId) { this.sourceId = sourceId; return this; }
    public McpServerDraft configId(String configId) { this.configId = configId; return this; }
    public McpServerDraft note(McpLocaleHint note) { this.note = note; return this; }
    public McpServerDraft docUrl(String docUrl) { this.docUrl = docUrl; return this; }

    public String getName() { return name; }
    public String getType() { return type; }
    public String getDescription() { return description; }
    public String getCommand() { return command; }
    public List<String> getArgs() { return args; }
    public Map<String, String> getEnv() { return env; }
    public String getUrl() { return url; }
    public Map<String, String> getHeaders() { return headers; }
    public List<McpMarketParam> getPendingParams() { return pendingParams; }
    public String getSource() { return source; }
    public String getSourceId() { return sourceId; }
    public String getConfigId() { return configId; }
    public McpLocaleHint getNote() { return note; }
    public String getDocUrl() { return docUrl; }
}

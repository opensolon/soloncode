package org.noear.solon.codecli.market.mcp;

/**
 * 连接器市场分类。
 *
 * <p>只有提供分类维度的市场才有数据（如腾讯云 MCP 广场），
 * 其余市场 {@link McpMarket#categories()} 返回空列表，前端据此隐藏分类条。</p>
 *
 * <p>分类清单是市场侧的静态内容，各适配器从内置资源读取（{@code MarketCategoryResource}），
 * 只保留 id 与展示名：分类条目数不作为界面信息展示。</p>
 *
 * @author noear 2026/11/16 created
 */
public class McpMarketCategory {
    /** 分类标识（由市场定义，原样回传给 list 的 categoryId 参数） */
    private String id;
    /**
     * 分类展示名。<b>属于市场的动态内容，必须原样展示，不做翻译</b>
     * （如腾讯云广场为中文、ClawHub 为英文），前端直接渲染。
     */
    private String name;

    public McpMarketCategory() {
    }

    public McpMarketCategory id(String id) { this.id = id; return this; }
    public McpMarketCategory name(String name) { this.name = name; return this; }

    public String getId() { return id; }
    public String getName() { return name; }
}

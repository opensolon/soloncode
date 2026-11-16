package org.noear.solon.codecli.mcpmarket;

import java.util.List;

/**
 * 连接器市场分页结果。
 *
 * <p>官方注册表使用游标分页（{@code metadata.nextCursor}），故不能只返回 List。</p>
 *
 * @author noear 2026/11/16 created
 */
public class McpMarketPage {
    private List<McpMarketItem> items;
    /** 下一页游标；为 null 表示没有更多 */
    private String nextCursor;

    public McpMarketPage() {
    }

    public McpMarketPage items(List<McpMarketItem> items) { this.items = items; return this; }
    public McpMarketPage nextCursor(String nextCursor) { this.nextCursor = nextCursor; return this; }

    public List<McpMarketItem> getItems() { return items; }
    public String getNextCursor() { return nextCursor; }
}

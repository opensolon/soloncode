package org.noear.solon.codecli.mcpmarket;

import java.util.List;

/**
 * 连接器市场详情 — 列表项 + 全部可用接入方式。
 *
 * @author noear 2026/11/16 created
 */
public class McpMarketDetail extends McpMarketItem {
    private List<McpMarketConfig> configs;

    public McpMarketDetail() {
    }

    public McpMarketDetail configs(List<McpMarketConfig> configs) {
        this.configs = configs;
        return this;
    }

    public List<McpMarketConfig> getConfigs() { return configs; }
}

package org.noear.solon.codecli.market.mcp;

import org.noear.solon.codecli.market.skill.SkillMarketManager;
import org.noear.solon.codecli.market.mcp.impl.OfficialRegistryMcpMarket;
import org.noear.solon.codecli.market.mcp.impl.SmitheryMcpMarket;
import org.noear.solon.codecli.market.mcp.impl.TencentMcpMarket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连接器市场管理器 — 管理多个 McpMarket 适配器。
 *
 * <p>与 {@link SkillMarketManager} 同构（单例 + register），
 * 便于后续接入国内源（如腾讯云 MCP 广场）或自建 bundle 源。</p>
 *
 * @author noear 2026/11/16 created
 */
public class McpMarketManager {
    private static McpMarketManager instance;

    public static McpMarketManager getInstance() {
        if (instance == null) {
            instance = new McpMarketManager();
        }
        return instance;
    }

    //-------

    private static final Logger LOG = LoggerFactory.getLogger(McpMarketManager.class);

    private final Map<String, McpMarket> markets = new LinkedHashMap<>();
    private McpMarket defaultMarket;

    private McpMarketManager() {
        // 腾讯云 MCP 广场：中文、分类齐全、国内直连，作为默认市场注册在首位
        // —— 前端下拉与缺省 marketName 都取第一个/默认实例。
        this.defaultMarket = register(new TencentMcpMarket());

        // Smithery：条目最多、带图标与热度（无服务端搜索，适配器内做本地过滤）
        register(new SmitheryMcpMarket());

        // 官方 Registry：字段最规范、接口稳定（境外，作为备选源）
        //register(new OfficialRegistryMcpMarket());
    }

    /**
     * 注册一个市场适配器
     */
    public McpMarket register(McpMarket market) {
        markets.put(market.name(), market);
        LOG.info("McpMarketManager: registered market -> {}", market.name());

        return market;
    }

    /**
     * 按名称获取市场适配器，找不到则返回默认市场
     */
    public McpMarket getMarketByName(String name) {
        if (name == null || name.isEmpty()) {
            return defaultMarket;
        }
        McpMarket market = markets.get(name);
        return market != null ? market : defaultMarket;
    }

    /**
     * 获取所有已注册市场的信息（用于前端下拉）
     */
    public List<MarketInfo> getMarketInfos() {
        List<MarketInfo> infos = new ArrayList<>();
        for (McpMarket market : markets.values()) {
            infos.add(new MarketInfo(market.name(), market.description()));
        }
        return infos;
    }

    /**
     * 市场信息实体
     */
    public static class MarketInfo {
        private final String name;
        private final String description;

        public MarketInfo(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }
    }
}

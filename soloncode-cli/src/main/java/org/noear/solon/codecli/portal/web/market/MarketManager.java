package org.noear.solon.codecli.portal.web.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能市场管理器 — 管理多个 Market 适配器，根据前端传入的 marketUrl 选择对应的市场。
 *
 * <p>默认注册 ClawHub 和 Skills.sh 两个市场，支持运行时动态添加。</p>
 *
 * @author noear 2026/5/29 created
 */
public class MarketManager {
    private static final Logger LOG = LoggerFactory.getLogger(MarketManager.class);

    private final Map<String, Market> markets = new ConcurrentHashMap<>();
    private Market defaultMarket;

    public MarketManager() {
        Market clawhub = new ClawhubMarket();
        register(clawhub);

        Market skillsSh = new SkillsShMarket();
        register(skillsSh);

        this.defaultMarket = clawhub;
    }

    /**
     * 注册一个市场适配器
     */
    public void register(Market market) {
        markets.put(market.url(), market);
        LOG.info("MarketManager: registered market -> {}", market.url());
    }

    /**
     * 根据 URL 获取市场适配器，找不到则返回默认市场
     */
    public Market getMarket(String url) {
        if (url == null || url.isEmpty()) {
            return defaultMarket;
        }
        Market m = markets.get(url);
        return m != null ? m : defaultMarket;
    }

    /**
     * 获取所有已注册市场的 URL 列表
     */
    public List<String> getMarketUrls() {
        return new ArrayList<>(markets.keySet());
    }

    /**
     * 获取所有已注册市场的信息（用于前端下拉选择）
     */
    public List<MarketInfo> getMarketInfos() {
        List<MarketInfo> infos = new ArrayList<>();
        for (Market m : markets.values()) {
            infos.add(new MarketInfo(m.url(), m.name(), m.description()));
        }
        return infos;
    }

    /**
     * 获取默认市场
     */
    public Market getDefaultMarket() {
        return defaultMarket;
    }

    /**
     * 市场信息实体
     */
    public static class MarketInfo {
        private final String url;
        private final String name;
        private final String description;

        public MarketInfo(String url, String name, String description) {
            this.url = url;
            this.name = name;
            this.description = description;
        }

        public String getUrl() { return url; }
        public String getName() { return name; }
        public String getDescription() { return description; }
    }
}

package org.noear.solon.codecli.market.skill;

import org.noear.solon.codecli.market.skill.impl.ClawhubSkillMarket;
import org.noear.solon.codecli.market.skill.impl.SkillhubSkillMarket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 技能市场管理器 — 管理多个 Market 适配器，根据前端传入的 marketName 选择对应的市场。
 *
 * <p>默认注册 ClawHub 和 Skills.sh 两个市场，支持运行时动态添加。</p>
 *
 * @author noear 2026/5/29 created
 */
public class SkillMarketManager {
    private static SkillMarketManager instance;

    public static SkillMarketManager getInstance() {
        if (instance == null) {
            instance = new SkillMarketManager();
        }

        return instance;
    }

    //-------

    private static final Logger LOG = LoggerFactory.getLogger(SkillMarketManager.class);

    private final Map<String, SkillMarket> markets = new LinkedHashMap<>();
    private SkillMarket defaultMarket;

    private SkillMarketManager() {
        SkillMarket skillhub = new SkillhubSkillMarket();
        register(skillhub);

        SkillMarket clawhub = new ClawhubSkillMarket();
        register(clawhub);

//        Market modelscope = new ModelscopeMarket();
//        register(modelscope);

        this.defaultMarket = skillhub;
    }

    /**
     * 注册一个市场适配器
     */
    public void register(SkillMarket market) {
        markets.put(market.name(), market);
        LOG.info("MarketManager: registered market -> {}", market.name());
    }

    /**
     * 根据名称获取市场适配器，找不到则返回默认市场
     */
    public SkillMarket getMarketByName(String name) {
        if (name == null || name.isEmpty()) {
            return defaultMarket;
        }
        SkillMarket m = markets.get(name);
        return m != null ? m : defaultMarket;
    }

    /**
     * 获取所有已注册市场的名称列表
     */
    public List<String> getMarketNames() {
        return new ArrayList<>(markets.keySet());
    }

    /**
     * 获取所有已注册市场的信息（用于前端下拉选择）
     */
    public List<MarketInfo> getMarketInfos() {
        List<MarketInfo> infos = new ArrayList<>();
        for (SkillMarket m : markets.values()) {
            infos.add(new MarketInfo(m.name(), m.description()));
        }
        return infos;
    }

    /**
     * 获取默认市场
     */
    public SkillMarket getDefaultMarket() {
        return defaultMarket;
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
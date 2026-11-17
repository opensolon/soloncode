package org.noear.solon.codecli.market.skill;

/**
 * 技能市场分类。
 *
 * <p>只有提供分类维度的市场才有数据（如 SkillHub），
 * 其余市场 {@link SkillMarket#categories()} 返回空列表，前端据此隐藏分类条。</p>
 *
 * @author noear 2026/11/16 created
 */
public class SkillMarketCategory {
    /** 分类标识（由市场定义，原样回传给 search 的 category 参数） */
    private String id;
    /**
     * 分类展示名。<b>属于市场的动态内容，必须原样展示，不做翻译</b>
     * （不同市场语种不同：如 SkillHub 为中文、ClawHub 为英文），前端直接渲染。
     */
    private String name;
    /** 该分类下的技能数，未知时为 0（前端不显示） */
    private int count;

    public SkillMarketCategory() {
    }

    public SkillMarketCategory id(String id) { this.id = id; return this; }
    public SkillMarketCategory name(String name) { this.name = name; return this; }
    public SkillMarketCategory count(int count) { this.count = count; return this; }

    public String getId() { return id; }
    public String getName() { return name; }
    public int getCount() { return count; }
}

package org.noear.solon.codecli.market.skill;

import java.util.Collections;
import java.util.List;

/**
 * 技能市场分页结果。
 *
 * <p>技能市场采用页码分页（SkillHub 的 {@code /api/skills?page=&pageSize=}），
 * 与连接器市场的游标分页不同，故页码直接放在结果里，前端只按 {@code hasMore} 决定是否显示「加载更多」。</p>
 *
 * @author noear 2026/11/16 created
 */
public class SkillMarketPage {
    /** 本页条目 */
    private List<SkillMarketItem> items = Collections.emptyList();
    /** 是否还有下一页 */
    private boolean hasMore;
    /** 结果总数；未知时为 -1（部分市场的搜索接口不返回总数） */
    private int total = -1;
    /** 本页页码（1 起） */
    private int page = 1;

    public SkillMarketPage() {
    }

    public SkillMarketPage items(List<SkillMarketItem> items) {
        this.items = (items == null) ? Collections.<SkillMarketItem>emptyList() : items;
        return this;
    }

    public SkillMarketPage hasMore(boolean hasMore) {
        this.hasMore = hasMore;
        return this;
    }

    public SkillMarketPage total(int total) {
        this.total = total;
        return this;
    }

    public SkillMarketPage page(int page) {
        this.page = page;
        return this;
    }

    public List<SkillMarketItem> getItems() {
        return items;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public int getTotal() {
        return total;
    }

    public int getPage() {
        return page;
    }
}

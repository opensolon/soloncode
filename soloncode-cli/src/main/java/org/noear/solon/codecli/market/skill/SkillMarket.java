package org.noear.solon.codecli.market.skill;

import org.noear.solon.core.handle.Result;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 技能市场接口 — 抽象技能市场的浏览、搜索、安装能力。
 *
 * <p>不同市场（ClawHub、Skills.sh 等）可实现此接口，由 MarketManager 注入管理。</p>
 *
 * @author noear 2026/5/28 created
 */
public interface SkillMarket {
    /**
     * 获取市场名称
     */
     String name() ;

    /**
     * 获取市场描述
     */
    default String description() {
        return "";
    }

    /**
     * 获取热门技能列表
     */
    Result<List<SkillMarketItem>> trending(int limit);

    /**
     * 搜索技能
     */
    Result<List<SkillMarketItem>> search(String query, int limit);

    /**
     * 获取市场分类清单。
     *
     * <p>无分类维度的市场返回空列表，前端据此隐藏整条分类栏。</p>
     */
    default List<SkillMarketCategory> categories() {
        return Collections.emptyList();
    }

    /**
     * 按分类搜索技能。
     *
     * <p>默认实现忽略分类（不支持分类维度的市场退化为普通搜索）。
     * category 为空时等价于 {@link #search(String, int)}；query 为空时表示浏览该分类。</p>
     */
    default Result<List<SkillMarketItem>> search(String query, int limit, String category) {
        return search(query, limit);
    }

    /**
     * 分页浏览/搜索技能（供市场页「加载更多」使用）。
     *
     * <p>默认实现取前 {@code page * pageSize} 条后在本地切片，适合不支持分页的市场：
     * 行为正确但每次翻页都会重复拉取前面的数据。分页能力原生的市场应覆盖本方法。</p>
     *
     * @param query    关键词，为空表示浏览
     * @param category 分类标识，为空表示全部分类
     * @param page     页码（1 起）
     * @param pageSize 每页条数
     */
    default Result<SkillMarketPage> page(String query, String category, int page, int pageSize) {
        int currentPage = Math.max(page, 1);
        int size = Math.max(pageSize, 1);
        int need = currentPage * size;

        Result<List<SkillMarketItem>> result = search(query, need, category);
        if (result.getCode() != 200) {
            return Result.failure(result.getDescription());
        }

        List<SkillMarketItem> all = (result.getData() == null)
                ? Collections.<SkillMarketItem>emptyList()
                : result.getData();

        int from = (currentPage - 1) * size;
        if (from >= all.size()) {
            return Result.succeed(new SkillMarketPage().page(currentPage).items(Collections.<SkillMarketItem>emptyList()));
        }

        int to = Math.min(need, all.size());
        // 取满 need 条说明上游可能还有更多；取不满一定到底了
        boolean hasMore = all.size() >= need;

        return Result.succeed(new SkillMarketPage()
                .page(currentPage)
                .items(new ArrayList<>(all.subList(from, to)))
                .hasMore(hasMore));
    }

    /**
     * 获取技能详情
     */
    Result<SkillMarketDetail> detail(String slug);

    /**
     * 安装技能 — 下载并解压到指定目录
     */
    Result<String> install(String slug, Path skillsDir);
}

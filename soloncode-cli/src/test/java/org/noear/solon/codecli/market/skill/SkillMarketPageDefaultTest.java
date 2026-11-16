package org.noear.solon.codecli.market.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.handle.Result;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SkillMarket#page} 默认实现的单元测试 —— 覆盖不支持分页的市场（本地切片路径）。
 *
 * @author noear 2026/11/16 created
 */
public class SkillMarketPageDefaultTest {

    /** 不支持分页的市场：只实现 search(query, limit)，page 走接口默认实现 */
    static class SliceMarket implements SkillMarket {
        private final List<SkillMarketItem> all = new ArrayList<>();

        SliceMarket(int total) {
            for (int i = 1; i <= total; i++) {
                all.add(new SkillMarketItem().slug("skill-" + i).displayName("skill-" + i));
            }
        }

        @Override
        public String name() {
            return "slice";
        }

        @Override
        public Result<List<SkillMarketItem>> trending(int limit) {
            return search(null, limit);
        }

        @Override
        public Result<List<SkillMarketItem>> search(String query, int limit) {
            int size = Math.min(Math.max(limit, 0), all.size());
            return Result.succeed(new ArrayList<>(all.subList(0, size)));
        }

        @Override
        public Result<SkillMarketDetail> detail(String slug) {
            return Result.failure("not supported");
        }

        @Override
        public Result<String> install(String slug, Path skillsDir) {
            return Result.failure("not supported");
        }
    }

    @Test
    @DisplayName("默认实现按 page/pageSize 本地切片")
    void slicesLocally() {
        SkillMarket market = new SliceMarket(25);

        SkillMarketPage page1 = market.page(null, null, 1, 10).getData();
        assertEquals(10, page1.getItems().size());
        assertEquals("skill-1", page1.getItems().get(0).getSlug());
        assertTrue(page1.isHasMore());

        SkillMarketPage page3 = market.page(null, null, 3, 10).getData();
        assertEquals(5, page3.getItems().size(), "最后一页应只有剩余 5 条");
        assertEquals("skill-21", page3.getItems().get(0).getSlug());
        assertFalse(page3.isHasMore(), "取不满一页说明到底了");
    }

    @Test
    @DisplayName("越界页码返回空列表且 hasMore=false")
    void beyondTotal() {
        SkillMarket market = new SliceMarket(25);

        SkillMarketPage page = market.page(null, null, 99, 10).getData();
        assertTrue(page.getItems().isEmpty());
        assertFalse(page.isHasMore());
    }

    @Test
    @DisplayName("页码/页大小非法值应被纠正为 1，而不是抛异常")
    void invalidParamsFallBack() {
        SkillMarket market = new SliceMarket(25);

        Result<SkillMarketPage> result = market.page(null, null, 0, 0);
        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().getPage());
        assertEquals(1, result.getData().getItems().size(), "pageSize=0 应按 1 处理");
    }

    @Test
    @DisplayName("上游搜索失败时默认实现应透传失败，而不是返回空页")
    void failurePropagates() {
        SkillMarket market = new SliceMarket(25) {
            @Override
            public Result<List<SkillMarketItem>> search(String query, int limit) {
                return Result.failure("upstream down");
            }
        };

        Result<SkillMarketPage> result = market.page(null, null, 1, 10);
        assertNotEquals(200, result.getCode(), "上游失败时应返回失败结果");
        assertEquals("upstream down", result.getDescription());
    }

    @Test
    @DisplayName("序列化字段名应为 items/hasMore/total/page（前端依赖该形状）")
    void jsonFieldNames() {
        SkillMarketPage page = new SkillMarketPage()
                .page(2)
                .total(58)
                .hasMore(true)
                .items(java.util.Collections.singletonList(
                        new SkillMarketItem().slug("a").displayName("A")));

        String json = org.noear.snack4.ONode.ofBean(page).toJson();

        assertTrue(json.contains("\"items\""), json);
        assertTrue(json.contains("\"hasMore\":true"), "布尔字段应序列化为 hasMore（isXxx 前缀应被去掉）: " + json);
        assertTrue(json.contains("\"total\":58"), json);
        assertTrue(json.contains("\"page\":2"), json);
    }
}

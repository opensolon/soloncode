package org.noear.solon.codecli.market.mcp.impl;

import org.junit.jupiter.api.*;
import org.noear.solon.codecli.market.mcp.McpMarketCategory;
import org.noear.solon.codecli.market.mcp.McpMarketItem;
import org.noear.solon.codecli.market.mcp.McpMarketPage;
import org.noear.solon.core.handle.Result;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TencentMcpMarket 分页集成测试 — 真实调用 cloud.tencent.com 广场接口，禁止 mock。
 *
 * <p>锁定的接口事实（2026-11-17 实测）：{@code /list-by-search} 单页固定 30 条，
 * {@code limit/size/pageSize} 全部被忽略，只能传 {@code page}（从 1 开始）翻页，
 * 返回体带 {@code total}。</p>
 *
 * <p>回归目标：分类浏览与关键词搜索都必须能给出 {@code nextCursor}（前端据此显示「加载更多」），
 * 且相邻两页不重叠、游标走到底时不再返回游标。</p>
 *
 * @author noear 2026/11/17 created
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TencentMcpMarketPagingTest {

    private static TencentMcpMarket market;
    /** 一定有多页的分类：探测首个「首屏满 30 条且带 nextCursor」的分类 */
    private static String bigCategoryId;

    @BeforeAll
    static void setUp() {
        market = new TencentMcpMarket();

        // 分类清单来自内置资源（不联网）；这里只把它当作候选 id 来源
        Result<List<McpMarketCategory>> result = market.categories();
        assertEquals(200, result.getCode(), "分类清单应可用");
        assertFalse(result.getData().isEmpty(), "广场应有分类");

        for (McpMarketCategory category : result.getData()) {
            Result<McpMarketPage> page = market.list("", 30, null, category.getId());
            if (page.getCode() == 200
                    && page.getData().getItems().size() >= 30
                    && page.getData().getNextCursor() != null) {
                bigCategoryId = category.getId();
                break;
            }
        }

        assertNotNull(bigCategoryId, "应至少有一个分类的条目数超过一页");
    }

    // ==================== 分类浏览分页 ====================

    @Test
    @Order(1)
    @DisplayName("分类浏览：首屏应带 nextCursor（否则前端不会出现加载更多）")
    void categoryFirstPageHasCursor() {
        Result<McpMarketPage> result = market.list("", 30, null, bigCategoryId);
        assertEquals(200, result.getCode(), () -> "分类列表失败: " + result.getDescription());

        McpMarketPage page = result.getData();
        assertEquals(30, page.getItems().size(), "首屏应满页");
        assertNotNull(page.getNextCursor(), "多页分类必须返回 nextCursor");
    }

    @Test
    @Order(2)
    @DisplayName("分类浏览：第二页与第一页不重叠，且游标可继续前进")
    void categorySecondPageIsDistinct() {
        Result<McpMarketPage> first = market.list("", 30, null, bigCategoryId);
        assertEquals(200, first.getCode());

        Result<McpMarketPage> second = market.list("", 30, first.getData().getNextCursor(), bigCategoryId);
        assertEquals(200, second.getCode(), () -> "翻页失败: " + second.getDescription());

        Set<String> firstIds = idsOf(first.getData());
        Set<String> secondIds = idsOf(second.getData());

        assertFalse(secondIds.isEmpty(), "第二页不应为空");
        for (String id : secondIds) {
            assertFalse(firstIds.contains(id), "第二页出现了第一页的条目: " + id);
        }
    }

    @Test
    @Order(3)
    @DisplayName("分类浏览：游标走到最后一页后不再返回 nextCursor")
    void categoryLastPageHasNoCursor() {
        String cursor = null;
        Set<String> seen = new HashSet<>();
        int pages = 0;

        while (pages++ < 100) {
            Result<McpMarketPage> result = market.list("", 30, cursor, bigCategoryId);
            assertEquals(200, result.getCode());

            McpMarketPage page = result.getData();
            for (String id : idsOf(page)) {
                assertTrue(seen.add(id), "翻页出现重复条目: " + id);
            }

            cursor = page.getNextCursor();
            if (cursor == null) {
                return;
            }
            assertFalse(page.getItems().isEmpty(), "还有游标却返回空页");
        }

        fail("游标翻页超过 100 页仍未结束，可能游标未推进");
    }

    // ==================== 关键词搜索分页 ====================

    @Test
    @Order(4)
    @DisplayName("关键词搜索：命中超过一页时应带 nextCursor，翻页内容不重叠")
    void searchIsPaged() {
        Result<McpMarketPage> first = market.list("mcp", 30, null, "");
        assertEquals(200, first.getCode(), () -> "搜索失败: " + first.getDescription());

        assertNotNull(first.getData().getNextCursor(), "命中数远超一页的搜索必须返回 nextCursor");

        Result<McpMarketPage> second = market.list("mcp", 30, first.getData().getNextCursor(), "");
        assertEquals(200, second.getCode());

        Set<String> firstIds = idsOf(first.getData());
        for (String id : idsOf(second.getData())) {
            assertFalse(firstIds.contains(id), "搜索第二页出现第一页的条目: " + id);
        }
    }

    @Test
    @Order(5)
    @DisplayName("关键词搜索：命中不足一页时不应返回 nextCursor")
    void searchSinglePageHasNoCursor() {
        Result<McpMarketPage> result = market.list("mysql", 30, null, "");
        assertEquals(200, result.getCode());

        McpMarketPage page = result.getData();
        assertTrue(page.getItems().size() <= 30);
        assertNull(page.getNextCursor(), "单页结果不应再给游标");
    }

    // ==================== 无筛选浏览（目录本地切页） ====================

    @Test
    @Order(6)
    @DisplayName("无筛选浏览：仍按本地切页返回 nextCursor")
    void catalogIsPaged() {
        Result<McpMarketPage> first = market.list("", 30, null, "");
        assertEquals(200, first.getCode(), () -> "目录列表失败: " + first.getDescription());
        assertEquals(30, first.getData().getItems().size());
        assertNotNull(first.getData().getNextCursor());
    }

    private Set<String> idsOf(McpMarketPage page) {
        Set<String> ids = new HashSet<>();
        List<McpMarketItem> items = page.getItems();
        if (items == null) {
            return ids;
        }
        for (McpMarketItem item : items) {
            ids.add(item.getId());
        }
        return ids;
    }
}

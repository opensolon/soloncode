package org.noear.solon.codecli.market.mcp.impl;

import org.junit.jupiter.api.*;
import org.noear.solon.codecli.market.mcp.McpMarketItem;
import org.noear.solon.codecli.market.mcp.McpMarketPage;
import org.noear.solon.core.handle.Result;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SmitheryMcpMarket 搜索分页集成测试 — 真实调用 registry.smithery.ai，禁止 mock。
 *
 * <p>该注册表没有可用的服务端搜索，适配器在「热度最高的前若干页」索引上本地过滤；
 * 分页因此是对索引切片（游标 = 偏移量）。回归目标：命中超过一页时必须给出
 * {@code nextCursor}（前端据此显示「加载更多」），且相邻两页不重叠。</p>
 *
 * @author noear 2026/11/17 created
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SmitheryMcpMarketSearchPagingTest {

    private static SmitheryMcpMarket market;

    @BeforeAll
    static void setUp() {
        market = new SmitheryMcpMarket();
    }

    @Test
    @Order(1)
    @DisplayName("搜索首屏：命中较多时应带 nextCursor")
    void searchFirstPageHasCursor() {
        Result<McpMarketPage> result = market.list("mcp", 10, null, "");
        assertEquals(200, result.getCode(), () -> "搜索失败: " + result.getDescription());

        McpMarketPage page = result.getData();
        assertEquals(10, page.getItems().size(), "索引内应能命中至少 10 条");
        assertNotNull(page.getNextCursor(), "命中超过一页必须返回 nextCursor");
    }

    @Test
    @Order(2)
    @DisplayName("搜索翻页：第二页与第一页不重叠，末尾不再返回游标")
    void searchPagesDoNotOverlap() {
        Result<McpMarketPage> first = market.list("mcp", 10, null, "");
        assertEquals(200, first.getCode());

        Result<McpMarketPage> second = market.list("mcp", 10, first.getData().getNextCursor(), "");
        assertEquals(200, second.getCode(), () -> "翻页失败: " + second.getDescription());

        Set<String> firstIds = idsOf(first.getData());
        List<McpMarketItem> secondItems = second.getData().getItems();

        assertFalse(secondItems.isEmpty(), "第二页不应为空");
        for (String id : idsOf(second.getData())) {
            assertFalse(firstIds.contains(id), "第二页出现第一页的条目: " + id);
        }

        // 游标越界时应返回空页且不再给游标，避免前端无限「加载更多」
        Result<McpMarketPage> beyond = market.list("mcp", 10, "100000", "");
        assertEquals(200, beyond.getCode());
        assertTrue(beyond.getData().getItems().isEmpty());
        assertNull(beyond.getData().getNextCursor());
    }

    private Set<String> idsOf(McpMarketPage page) {
        Set<String> ids = new HashSet<>();
        for (McpMarketItem item : page.getItems()) {
            ids.add(item.getId());
        }
        return ids;
    }
}

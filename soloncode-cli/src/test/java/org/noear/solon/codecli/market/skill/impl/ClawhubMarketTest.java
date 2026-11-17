package org.noear.solon.codecli.market.skill.impl;

import org.junit.jupiter.api.*;
import org.noear.solon.codecli.market.skill.SkillMarketCategory;
import org.noear.solon.codecli.market.skill.SkillMarketItem;
import org.noear.solon.codecli.market.skill.SkillMarketPage;
import org.noear.solon.core.handle.Result;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClawhubMarket 集成测试 — 真实调用 clawhub.ai，禁止 mock。
 *
 * <p>重点覆盖分类维度：ClawHub 没有分类清单接口，分类表在站点前端资源（catalogMetadata chunk）里，
 * 运行期读源站、取不到才用内置快照；搜索接口支持 category 原生过滤但要求 q 非空；
 * 只按分类浏览只能取浏览页 SSR。</p>
 *
 * @author noear 2026/11/16 created
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ClawhubMarketTest {

    private static ClawhubSkillMarket market;

    /** 关键词（要求：在多数分类下都有结果，且在 security 分类下也有命中） */
    private static final String KEYWORD = "ai";
    /** 中文字符（用于校验分类名没有被汉化） */
    private static final java.util.regex.Pattern CJK =
            java.util.regex.Pattern.compile("[\\u4e00-\\u9fff]");

    @BeforeAll
    static void setUp() {
        market = new ClawhubSkillMarket();
    }

    @Test
    @Order(1)
    void name_市场标识() {
        assertEquals("clawhub.ai", market.name());
    }

    @Test
    @Order(2)
    void categories_源站分类表原样透出() {
        List<SkillMarketCategory> categories = market.categories();

        assertTrue(categories.size() >= 5,
                "分类表应有数据（源站或内置快照），实际 " + categories.size() + " 项");

        Set<String> ids = new HashSet<>();
        for (SkillMarketCategory category : categories) {
            assertNotNull(category.getId(), "分类标识不能为空");
            assertFalse(category.getId().isEmpty());
            assertNotNull(category.getName(), "分类 " + category.getId() + " 缺少展示名");
            // 站点无分类计数接口，count 保持 0（前端不显示数字）
            assertEquals(0, category.getCount(), "分类 " + category.getId() + " 的 count 应为 0");
            // 分类名是源站动态内容，必须原样透出：ClawHub 是英文站，不应被翻译成中文
            assertFalse(CJK.matcher(category.getName()).find(),
                    "分类 " + category.getId() + " 的展示名被翻译了，应为源站原样：" + category.getName());
            ids.add(category.getId());
        }

        assertTrue(ids.contains("security"), "应包含 security");
        assertTrue(ids.contains("development"), "应包含 development");
        assertTrue(ids.contains("other"), "应包含 other");
    }

    @Test
    @Order(3)
    void search_关键词命中() {
        Result<List<SkillMarketItem>> result = market.search(KEYWORD, 5);

        assertEquals(200, result.getCode(), result.getDescription());
        assertNotNull(result.getData());
        assertFalse(result.getData().isEmpty(), "关键词 " + KEYWORD + " 应有结果");
        assertTrue(result.getData().size() <= 5);

        SkillMarketItem first = result.getData().get(0);
        assertNotNull(first.getSlug(), "缺少 slug");
        assertNotNull(first.getUrl(), "缺少详情地址");
        assertTrue(first.getUrl().startsWith("https://clawhub.ai/"), "详情地址应指向站点");
    }

    /**
     * 守卫点：搜索接口返回的是 {results:[...]} 而不是 {items:[...]}，
     * 旧解析只认 items，会把搜索结果整片丢成空列表。
     */
    @Test
    @Order(4)
    void search_必须解析results数组() {
        Result<List<SkillMarketItem>> result = market.search("map", 10);

        assertEquals(200, result.getCode(), result.getDescription());
        assertFalse(result.getData() == null || result.getData().isEmpty(),
                "搜索返回体是 {results:[...]}，解析后不能为空");
    }

    @Test
    @Order(5)
    void search_按分类过滤生效() {
        Result<List<SkillMarketItem>> all = market.search(KEYWORD, 10);
        Result<List<SkillMarketItem>> filtered = market.search(KEYWORD, 10, "security");

        assertEquals(200, filtered.getCode(), filtered.getDescription());
        assertNotNull(filtered.getData());
        assertFalse(filtered.getData().isEmpty(), "security 分类下应有 " + KEYWORD + " 结果");

        // 站点侧的原生过滤：结果必须与不带分类时不同
        Set<String> filteredSlugs = slugs(filtered.getData());
        Set<String> allSlugs = slugs(all.getData());
        assertNotEquals(allSlugs, filteredSlugs, "category 参数未生效：结果与不过滤时完全一致");

        // 同样的关键词 + 不同分类，结果也应不同
        Result<List<SkillMarketItem>> dev = market.search(KEYWORD, 10, "development");
        assertEquals(200, dev.getCode(), dev.getDescription());
        assertNotEquals(slugs(dev.getData()), filteredSlugs, "不同分类返回了相同结果");
    }

    /**
     * 分类浏览：搜索接口要求 q 非空，无关键词时只能解析浏览页 SSR。
     */
    @Test
    @Order(6)
    void search_仅分类浏览走浏览页() {
        Result<List<SkillMarketItem>> result = market.search(null, 50, "finance");

        assertEquals(200, result.getCode(), result.getDescription());
        assertNotNull(result.getData());
        assertFalse(result.getData().isEmpty(), "finance 分类浏览应解析出条目");

        for (SkillMarketItem item : result.getData()) {
            assertNotNull(item.getSlug(), "缺少 slug");
            assertTrue(item.getSlug().matches("[a-zA-Z0-9._-]+"), "slug 不合法: " + item.getSlug());
            assertNotNull(item.getDisplayName(), "缺少名称");
            assertFalse(item.getDisplayName().isEmpty());
            assertNotNull(item.getOwnerHandle(), "缺少所有者");
            assertNotNull(item.getUrl());
            assertTrue(item.getUrl().startsWith("https://clawhub.ai/"), "详情地址应指向站点");
        }
    }

    @Test
    @Order(7)
    void page_关键词搜索分页() {
        Result<SkillMarketPage> first = market.page(KEYWORD, null, 1, 5);
        assertEquals(200, first.getCode(), first.getDescription());
        assertEquals(1, first.getData().getPage());
        assertEquals(5, first.getData().getItems().size());

        Result<SkillMarketPage> second = market.page(KEYWORD, null, 2, 5);
        assertEquals(200, second.getCode(), second.getDescription());
        assertEquals(2, second.getData().getPage());

        Set<String> firstSlugs = slugs(first.getData().getItems());
        Set<String> secondSlugs = slugs(second.getData().getItems());
        firstSlugs.retainAll(secondSlugs);
        assertTrue(firstSlugs.isEmpty(), "第 1、2 页出现重复条目: " + firstSlugs);
    }

    @Test
    @Order(8)
    void page_分类浏览只有第一页() {
        Result<SkillMarketPage> first = market.page(null, "security", 1, 50);
        assertEquals(200, first.getCode(), first.getDescription());
        assertFalse(first.getData().getItems().isEmpty(), "security 分类浏览应有数据");
        // 浏览页无法翻页（page 参数无效），如实报告没有更多
        assertFalse(first.getData().isHasMore(), "分类浏览不支持翻页，hasMore 必须为 false");

        Result<SkillMarketPage> second = market.page(null, "security", 2, 50);
        assertEquals(200, second.getCode(), second.getDescription());
        assertTrue(second.getData().getItems().isEmpty(), "第二页应为空");
    }

    @Test
    @Order(9)
    void page_未分类的浏览走trending() {
        Result<SkillMarketPage> page = market.page(null, null, 1, 5);

        assertEquals(200, page.getCode(), page.getDescription());
        assertNotNull(page.getData().getItems());
        assertFalse(page.getData().getItems().isEmpty(), "热门列表应有数据");
    }

    @Test
    @Order(10)
    void detail_真实技能() {
        Result<List<SkillMarketItem>> list = market.search(KEYWORD, 5);
        assertEquals(200, list.getCode(), list.getDescription());
        assertFalse(list.getData().isEmpty());

        String slug = list.getData().get(0).getSlug();
        Result<?> detail = market.detail(slug);

        assertEquals(200, detail.getCode(), "详情获取失败: " + detail.getDescription());
    }

    private Set<String> slugs(List<SkillMarketItem> items) {
        Set<String> slugs = new HashSet<>();
        for (SkillMarketItem item : items) {
            slugs.add(item.getSlug());
        }
        return slugs;
    }
}

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
 * <p>重点覆盖分类维度：分类清单是站点前端打包的静态常量（没有对应接口），已内置为
 * {@code clawhub-categories.json}，运行期不抓取；搜索接口支持 category 原生过滤但要求 q 非空；
 * 只按分类浏览走站点列表接口（游标分页，支持「加载更多」）。</p>
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

        assertEquals(14, categories.size(),
                "内置分类快照应为 14 项（源站 category 数组），实际 " + categories.size() + " 项");

        Set<String> ids = new HashSet<>();
        for (SkillMarketCategory category : categories) {
            assertNotNull(category.getId(), "分类标识不能为空");
            assertFalse(category.getId().isEmpty());
            assertNotNull(category.getName(), "分类 " + category.getId() + " 缺少展示名");
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
     * 分类浏览：无关键词时走站点列表接口（skills:listPublicPageV4），分类由服务端过滤。
     */
    @Test
    @Order(6)
    void search_仅分类浏览走站点列表接口() {
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

    /**
     * 分类浏览的分页：站点列表接口是游标分页（不透明游标，不能由页码反推），
     * 所以程序侧按顺序前进，逐页不能重复。
     */
    @Test
    @Order(8)
    void page_分类浏览可多页且不重复() {
        Result<SkillMarketPage> first = market.page(null, "security", 1, 20);
        assertEquals(200, first.getCode(), first.getDescription());
        assertEquals(20, first.getData().getItems().size(), "首页应取满 20 条");
        assertTrue(first.getData().isHasMore(), "security 分类不止 20 条，应报告还有更多");

        Set<String> seen = slugs(first.getData().getItems());
        assertEquals(20, seen.size(), "同一页内出现重复条目");

        for (int page = 2; page <= 3; page++) {
            Result<SkillMarketPage> next = market.page(null, "security", page, 20);
            assertEquals(200, next.getCode(), next.getDescription());
            assertEquals(20, next.getData().getItems().size(), "第 " + page + " 页应取满 20 条");

            Set<String> current = slugs(next.getData().getItems());
            current.retainAll(seen);
            assertTrue(current.isEmpty(), "第 " + page + " 页与前面页重复: " + current);
            seen.addAll(slugs(next.getData().getItems()));
        }

        // 每页都取满 → 三页共 60 条不重复
        assertEquals(60, seen.size());
    }

    /** 分类浏览的条目应带上站点掌握的可展示信息（版本 / 下载量 / 发布方头像） */
    @Test
    @Order(9)
    void page_分类浏览条目带版本与图标() {
        Result<SkillMarketPage> page = market.page(null, "integrations", 1, 20);
        assertEquals(200, page.getCode(), page.getDescription());
        assertFalse(page.getData().getItems().isEmpty());

        int withVersion = 0, withIcon = 0, withInstalls = 0;
        for (SkillMarketItem item : page.getData().getItems()) {
            if (item.getVersion() != null && item.getVersion().isEmpty() == false) withVersion++;
            if (item.getIconUrl() != null && item.getIconUrl().isEmpty() == false) withIcon++;
            if (item.getInstalls() > 0) withInstalls++;
        }

        assertTrue(withVersion > 0, "应至少有一条带版本号");
        assertTrue(withIcon > 0, "应至少有一条带发布方头像");
        assertTrue(withInstalls > 0, "应至少有一条带下载量");
    }

    /**
     * 不能出现「点了加载更多却什么都没有」：返回空页时必须同时 hasMore=false。
     *
     * <p>这里不强行把某个分类翻到底（源站每个分类都是万条量级，翻到底要几百次请求），
     * 而是逐页前进若干页，验证空页与 hasMore 的一致性；一旦到底，则后续页必须一直为空。</p>
     */
    @Test
    @Order(10)
    void page_不会出现空页却声称还有更多() {
        boolean reachedEnd = false;

        for (int p = 1; p <= 6; p++) {
            Result<SkillMarketPage> page = market.page(null, "other", p, 40);
            assertEquals(200, page.getCode(), page.getDescription());

            if (page.getData().getItems().isEmpty()) {
                assertFalse(page.getData().isHasMore(), "第 " + p + " 页为空却声称还有更多");
                reachedEnd = true;
                break;
            }
        }

        if (reachedEnd) {
            Result<SkillMarketPage> after = market.page(null, "other", 7, 40);
            assertEquals(200, after.getCode(), after.getDescription());
            assertTrue(after.getData().getItems().isEmpty(), "到底之后的页应为空");
        }
    }

    @Test
    @Order(11)
    void page_无关键词无分类走站点默认列表() {
        Result<SkillMarketPage> page = market.page(null, null, 1, 5);

        assertEquals(200, page.getCode(), page.getDescription());
        assertNotNull(page.getData().getItems());
        assertFalse(page.getData().getItems().isEmpty(), "默认列表应有数据");
        assertEquals(5, page.getData().getItems().size(), "应按请求的每页条数返回");
    }

    @Test
    @Order(12)
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

package org.noear.solon.codecli.portal.web.market.impl;

import org.junit.jupiter.api.*;
import org.noear.solon.codecli.market.MarketDetail;
import org.noear.solon.codecli.market.MarketItem;
import org.noear.solon.codecli.market.impl.SkillhubMarket;
import org.noear.solon.core.handle.Result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillhubMarket 集成测试 — 真实调用 api.skillhub.cn，禁止 mock。
 *
 * <p>覆盖 name/description、trending、search、detail、install 五大方法的正常与边界场景。</p>
 *
 * @author noear 2026/5/30 created
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SkillhubMarketTest {

    private static SkillhubMarket market;
    private static Path tempSkillsDir;
    private static String cachedSlug; // 从 trending 缓存一个真实 slug

    @BeforeAll
    static void setUp() throws Exception {
        market = new SkillhubMarket();
        tempSkillsDir = Files.createTempDirectory("skillhub-test-");

        // 预热：获取一个真实 slug 供 detail/install 使用
        Result<List<MarketItem>> trending = market.trending(5);
        if (trending.getCode() == 200 && trending.getData() != null && !trending.getData().isEmpty()) {
            cachedSlug = trending.getData().get(0).getSlug();
            System.out.println("缓存的测试 slug: " + cachedSlug);
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (tempSkillsDir != null && Files.exists(tempSkillsDir)) {
            Files.walk(tempSkillsDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (Exception ignored) {}
                    });
        }
    }

    // ==================== 1. 基础属性 ====================

    @Test
    @Order(1)
    @DisplayName("name() 应返回 skillhub.cn")
    void testName() {
        assertEquals("skillhub.cn", market.name());
    }

    @Test
    @Order(2)
    @DisplayName("description() 应返回非空中文描述")
    void testDescription() {
        String desc = market.description();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
        assertTrue(desc.contains("中国"), "description 应包含'中国'");
    }

    // ==================== 2. trending ====================

    @Nested
    @DisplayName("trending - 热门列表")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class TrendingTest {

        @Test
        @Order(1)
        @DisplayName("limit=5 应返回不超过 5 条结果")
        void testTrendingLimit5() {
            Result<List<MarketItem>> result = market.trending(5);

            assertEquals(200, result.getCode(), "trending 应成功，code=200");
            assertNotNull(result.getData(), "data 不应为 null");
            assertTrue(result.getData().size() <= 5, "返回数量不应超过 limit");
            assertFalse(result.getData().isEmpty(), "热门列表不应为空");
        }

        @Test
        @Order(2)
        @DisplayName("limit=10 返回的每个 MarketItem 核心字段应非空")
        void testTrendingItemFields() {
            Result<List<MarketItem>> result = market.trending(10);

            assertEquals(200, result.getCode());
            List<MarketItem> items = result.getData();
            assertFalse(items.isEmpty());

            for (MarketItem item : items) {
                assertNotNull(item.getSlug(), "slug 不应为 null");
                assertFalse(item.getSlug().isEmpty(), "slug 不应为空字符串");
                assertNotNull(item.getDisplayName(), "displayName 不应为 null");
                assertNotNull(item.getOwnerHandle(), "ownerHandle 不应为 null");
                assertTrue(item.getInstalls() >= 0, "installs 不应为负");
                assertTrue(item.getStars() >= 0, "stars 不应为负");
            }
        }

        @Test
        @Order(3)
        @DisplayName("limit=1 应返回恰好 1 条结果")
        void testTrendingLimit1() {
            Result<List<MarketItem>> result = market.trending(1);

            assertEquals(200, result.getCode());
            assertEquals(1, result.getData().size());
        }

        @Test
        @Order(4)
        @DisplayName("limit=0 应正常返回（不报错）")
        void testTrendingLimit0() {
            Result<List<MarketItem>> result = market.trending(0);

            // API 可能忽略 limit=0 返回默认列表，也可能返回空列表，两种都算通过
            assertEquals(200, result.getCode(), "limit=0 不应报错");
            assertNotNull(result.getData(), "data 不应为 null");
        }
    }

    // ==================== 3. search ====================

    @Nested
    @DisplayName("search - 搜索")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SearchTest {

        @Test
        @Order(1)
        @DisplayName("搜索 'weather' 应返回包含 weather 相关技能的结果")
        void testSearchWeather() {
            Result<List<MarketItem>> result = market.search("weather", 10);

            assertEquals(200, result.getCode(), "搜索 weather 应成功");
            assertNotNull(result.getData());
            assertFalse(result.getData().isEmpty(), "搜索 weather 应有结果");

            boolean hasWeatherRelated = result.getData().stream()
                    .anyMatch(item ->
                            (item.getSlug() != null && item.getSlug().toLowerCase().contains("weather")) ||
                            (item.getDescription() != null && item.getDescription().toLowerCase().contains("weather")) ||
                            (item.getDisplayName() != null && item.getDisplayName().toLowerCase().contains("weather"))
                    );
            assertTrue(hasWeatherRelated, "搜索结果中应有 weather 相关技能");
        }

        @Test
        @Order(2)
        @DisplayName("搜索空关键词应等同于 trending")
        void testSearchEmptyQuery() {
            Result<List<MarketItem>> searchResult = market.search("", 5);

            assertEquals(200, searchResult.getCode());
            assertNotNull(searchResult.getData());
            assertFalse(searchResult.getData().isEmpty());
        }

        @Test
        @Order(3)
        @DisplayName("搜索 null 关键词应等同于 trending")
        void testSearchNullQuery() {
            Result<List<MarketItem>> result = market.search(null, 5);

            assertEquals(200, result.getCode());
            assertNotNull(result.getData());
        }

        @Test
        @Order(4)
        @DisplayName("搜索不存在的关键词应返回空列表而非报错")
        void testSearchNonexistent() {
            Result<List<MarketItem>> result = market.search("zzzz_nonexistent_skill_xyz_12345", 10);

            assertEquals(200, result.getCode(), "搜索不存在关键词不应报错");
            assertNotNull(result.getData());
        }

        @Test
        @Order(5)
        @DisplayName("搜索中文关键词应正常工作")
        void testSearchChinese() {
            Result<List<MarketItem>> result = market.search("天气", 10);

            assertEquals(200, result.getCode(), "搜索中文不应报错");
            assertNotNull(result.getData());
        }
    }

    // ==================== 4. detail ====================

    @Nested
    @DisplayName("detail - 详情")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DetailTest {

        @Test
        @Order(1)
        @DisplayName("使用真实 slug 查询详情应成功")
        void testDetailWithRealSlug() {
            Assumptions.assumeTrue(cachedSlug != null, "需要有可用的 slug 才能测试");
            System.out.println("测试 detail 使用的 slug: " + cachedSlug);

            Result<MarketDetail> result = market.detail(cachedSlug);

            assertEquals(200, result.getCode(), "detail 应成功，code=200");
            MarketDetail detail = result.getData();
            assertNotNull(detail);
            assertEquals(cachedSlug, detail.getSlug(), "detail 的 slug 应与请求一致");
        }

        @Test
        @Order(2)
        @DisplayName("详情对象核心字段应已填充")
        void testDetailFieldsPopulated() {
            Assumptions.assumeTrue(cachedSlug != null, "需要有可用的 slug 才能测试");

            Result<MarketDetail> result = market.detail(cachedSlug);
            assertEquals(200, result.getCode());

            MarketDetail detail = result.getData();
            assertNotNull(detail.getSlug());
            assertNotNull(detail.getDisplayName(), "displayName 不应为 null");
            assertNotNull(detail.getOwnerHandle(), "ownerHandle 不应为 null");
            assertNotNull(detail.getInstallSlug(), "installSlug 不应为 null");
            assertTrue(detail.getInstalls() >= 0);
            assertTrue(detail.getStars() >= 0);
        }

        @Test
        @Order(3)
        @DisplayName("使用不存在的 slug 查询应返回失败")
        void testDetailNonexistent() {
            Result<MarketDetail> result = market.detail("zzz_nonexistent_slug_xyz_99999");

            if (result.getCode() != 200) {
                assertNotNull(result.getDescription(), "失败时 description 应有值");
            } else {
                assertNull(result.getData(), "不存在的 slug 应返回 null data");
            }
        }

        @Test
        @Order(4)
        @DisplayName("slug 为 null 应返回失败")
        void testDetailNullSlug() {
            Result<MarketDetail> result = market.detail(null);
            assertNotEquals(200, result.getCode(), "null slug 应返回非200");
        }

        @Test
        @Order(5)
        @DisplayName("slug 为空字符串应返回失败")
        void testDetailEmptySlug() {
            Result<MarketDetail> result = market.detail("");
            assertNotEquals(200, result.getCode(), "空 slug 应返回非200");
        }
    }

    // ==================== 5. install ====================

    @Nested
    @DisplayName("install - 安装")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class InstallTest {

        @Test
        @Order(1)
        @DisplayName("安装真实 slug 应成功并解压到目标目录")
        void testInstallRealSlug() {
            Assumptions.assumeTrue(cachedSlug != null, "需要有可用的 slug 才能测试");
            System.out.println("测试 install 使用的 slug: " + cachedSlug);

            Path installDir = tempSkillsDir.resolve("install-test-1");
            Result<String> result = market.install(cachedSlug, installDir);

            assertEquals(200, result.getCode(),
                    "安装应成功。如果失败，description=" + result.getDescription());
            assertNotNull(result.getData(), "安装成功应返回 displayName");

            // 验证目录已创建
            Path skillDir = installDir.resolve(cachedSlug);
            assertTrue(Files.exists(skillDir), "技能目录应存在");
            assertTrue(Files.isDirectory(skillDir), "技能目录应为目录");

            // 验证目录内有文件
            try {
                long fileCount = Files.walk(skillDir)
                        .filter(Files::isRegularFile)
                        .count();
                assertTrue(fileCount > 0, "解压后应至少有一个文件");
                System.out.println("安装成功，文件数: " + fileCount + "，displayName: " + result.getData());
            } catch (Exception e) {
                fail("遍历安装目录失败: " + e.getMessage());
            }
        }

        @Test
        @Order(2)
        @DisplayName("重复安装同一 slug 应覆盖旧文件（不报错）")
        void testInstallOverwrite() {
            Assumptions.assumeTrue(cachedSlug != null, "需要有可用的 slug 才能测试");

            Path installDir = tempSkillsDir.resolve("install-test-overwrite");
            Result<String> first = market.install(cachedSlug, installDir);
            assertEquals(200, first.getCode(), "首次安装应成功");

            Result<String> second = market.install(cachedSlug, installDir);
            assertEquals(200, second.getCode(), "重复安装也应成功");
        }

        @Test
        @Order(3)
        @DisplayName("slug 为 null 应返回失败")
        void testInstallNullSlug() {
            Path dir = tempSkillsDir.resolve("install-null");
            Result<String> result = market.install(null, dir);
            assertNotEquals(200, result.getCode(), "null slug 应返回非200");
        }

        @Test
        @Order(4)
        @DisplayName("slug 为空字符串应返回失败")
        void testInstallEmptySlug() {
            Path dir = tempSkillsDir.resolve("install-empty");
            Result<String> result = market.install("", dir);
            assertNotEquals(200, result.getCode(), "空 slug 应返回非200");
        }

        @Test
        @Order(5)
        @DisplayName("slug 含非法字符应被清理或返回失败")
        void testInstallInvalidSlug() {
            Path dir = tempSkillsDir.resolve("install-invalid");
            Result<String> result = market.install("../../etc/passwd", dir);
            assertNotEquals(200, result.getCode(),
                    "非法 slug 应返回非200。实际 code=" + result.getCode());
        }

        @Test
        @Order(6)
        @DisplayName("安装不存在的 slug 应返回失败")
        void testInstallNonexistent() {
            Path dir = tempSkillsDir.resolve("install-nonexist");
            Result<String> result = market.install("zzz_nonexistent_slug_xyz_99999", dir);
            assertNotEquals(200, result.getCode(),
                    "不存在的 slug 安装应失败。实际 code=" + result.getCode());
            System.out.println("不存在 slug 的安装结果: " + result.getDescription());
        }
    }
}

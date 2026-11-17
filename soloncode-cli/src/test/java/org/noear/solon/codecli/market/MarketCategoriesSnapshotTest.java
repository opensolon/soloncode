package org.noear.solon.codecli.market;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.codecli.market.mcp.McpMarketCategory;
import org.noear.solon.codecli.market.mcp.impl.TencentMcpMarket;
import org.noear.solon.codecli.market.skill.SkillMarketCategory;
import org.noear.solon.codecli.market.skill.impl.ClawhubSkillMarket;
import org.noear.solon.codecli.market.skill.impl.SkillhubSkillMarket;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 市场分类内置资源的离线单测（不联网）。
 *
 * <p>锁住三件事：</p>
 * <ul>
 *   <li>三个有分类维度的市场（腾讯云广场 / SkillHub / ClawHub）的分类清单都来自随包资源，
 *       调用即时返回、不依赖源站可用性；</li>
 *   <li>分类名是源站原文，<b>原样展示、不翻译</b>（ClawHub 英文站不能被汉化，反之 SkillHub
 *       中文站也不能被英化）；</li>
 *   <li>资源缺失或损坏时降级为空列表（前端隐藏分类栏），不能抛异常影响市场页。</li>
 * </ul>
 *
 * <p>分类条目数已从界面移除，资源里只保留 id 与展示名。</p>
 */
public class MarketCategoriesSnapshotTest {

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    /** 分类清单必须“秒回”：任何实现走网络都会超过这个上限（HTTP 超时 + 重试间隔） */
    private static final long NO_NETWORK_MAX_MS = 2000L;

    @Test
    void clawhubCategoriesComeFromBundledResource() {
        ClawhubSkillMarket market = new ClawhubSkillMarket();

        long start = System.currentTimeMillis();
        List<SkillMarketCategory> categories = market.categories();
        long cost = System.currentTimeMillis() - start;

        assertTrue(cost < NO_NETWORK_MAX_MS, "分类清单应读内置资源，耗时 " + cost + "ms 说明可能仍在联网");
        assertEquals(14, categories.size(), "ClawHub 分类表为 14 项");
        assertEquals("integrations", categories.get(0).getId(), "首项应为 integrations（源站顺序）");
        assertEquals("other", categories.get(13).getId(), "末项应为 other（源站顺序）");

        List<String> ids = new ArrayList<>();
        for (SkillMarketCategory category : categories) {
            ids.add(category.getId());
            assertNotNull(category.getName());
            assertFalse(CJK.matcher(category.getName()).find(),
                    "ClawHub 是英文站，分类名不能是中文：" + category.getId() + "=" + category.getName());
        }
        assertHasDistinctIds(ids);
        assertTrue(ids.contains("security"), "应包含 security");
        assertTrue(ids.contains("development"), "应包含 development");
        assertTrue(ids.contains("other"), "应包含 other");
    }

    @Test
    void skillhubCategoriesComeFromBundledResource() {
        SkillhubSkillMarket market = new SkillhubSkillMarket();

        long start = System.currentTimeMillis();
        List<SkillMarketCategory> categories = market.categories();
        long cost = System.currentTimeMillis() - start;

        assertTrue(cost < NO_NETWORK_MAX_MS, "分类清单应读内置资源，耗时 " + cost + "ms 说明可能仍在联网");
        assertEquals(13, categories.size(), "SkillHub 一级分类为 13 个（源站 count=13）");

        List<String> ids = new ArrayList<>();
        boolean hasCjkName = false;
        for (SkillMarketCategory category : categories) {
            ids.add(category.getId());
            assertNotNull(category.getName());
            if (CJK.matcher(category.getName()).find()) {
                hasCjkName = true;
            }
        }
        assertHasDistinctIds(ids);
        assertTrue(hasCjkName, "SkillHub 是中文站，分类名应保留中文原文");
        assertTrue(ids.contains("dev-programming"), "应包含 dev-programming（列表与搜索的 category 取值）");
    }

    @Test
    void tencentCategoriesComeFromBundledResource() {
        TencentMcpMarket market = new TencentMcpMarket();

        long start = System.currentTimeMillis();
        List<McpMarketCategory> categories = market.categories().getData();
        long cost = System.currentTimeMillis() - start;

        assertTrue(cost < NO_NETWORK_MAX_MS, "分类清单应读内置资源，耗时 " + cost + "ms 说明可能仍在联网");
        assertEquals(12, categories.size(), "腾讯云广场分类为 12 个");

        List<String> ids = new ArrayList<>();
        for (McpMarketCategory category : categories) {
            ids.add(category.getId());
            assertNotNull(category.getName());
            assertTrue(CJK.matcher(category.getName()).find(),
                    "腾讯云广场是中文站，分类名应保留中文原文：" + category.getId());
            // 分类 id 是数字字符串，list 时会被转成数字回传给广场
            assertTrue(isDigits(category.getId()), "分类 id 应为数字串：" + category.getId());
        }
        assertHasDistinctIds(ids);
    }

    /** 资源文件必须带版本与来源，便于追溯快照是哪天、从哪抓的 */
    @Test
    void resourcesCarryVersionMetadata() throws Exception {
        assertMetadataPresent(ClawhubSkillMarket.class, "clawhub-categories.json");
        assertMetadataPresent(SkillhubSkillMarket.class, "skillhub-categories.json");
        assertMetadataPresent(TencentMcpMarket.class, "tencent-categories.json");
    }

    /** 资源缺失时返回空列表，不能抛异常（分类栏是辅助功能） */
    @Test
    void missingResourceDegradesToEmptyList() {
        List<MarketCategoryResource.Entry> entries =
                MarketCategoryResource.load(MarketCategoriesSnapshotTest.class, "no-such-categories.json");

        assertNotNull(entries);
        assertTrue(entries.isEmpty());
    }

    /** 每次调用返回新列表，调用方改动不会污染共享快照 */
    @Test
    void snapshotIsNotSharedMutableState() {
        ClawhubSkillMarket market = new ClawhubSkillMarket();

        List<SkillMarketCategory> first = market.categories();
        first.clear();

        assertEquals(14, market.categories().size(), "清空返回列表不应影响内置快照");
    }

    private static void assertMetadataPresent(Class<?> anchor, String resource) throws Exception {
        InputStream in = anchor.getResourceAsStream(resource);
        assertNotNull(in, "缺少内置分类资源：" + resource);

        String json = readAll(in);
        ONode root = ONode.ofJson(json);

        assertEquals(1, root.get("schemaVersion").getInt(), resource + " 的 schemaVersion 应为 1");
        assertFalse(root.get("version").getString().isEmpty(), resource + " 缺少 version");
        assertFalse(root.get("snapshotAt").getString().isEmpty(), resource + " 缺少 snapshotAt");
        assertFalse(root.get("source").getString().isEmpty(), resource + " 缺少 source");
        assertNotNull(root.get("categories"), resource + " 缺少 categories");
        assertTrue(root.get("categories").isArray(), resource + " 的 categories 应为数组");

        // 分类条目数不再展示，资源里也不应残留 count
        for (ONode node : root.get("categories").getArray()) {
            ONode countNode = node.get("count");
            assertTrue(countNode == null || countNode.isNull(), resource + " 不应再包含 count 字段");
        }
    }

    private static void assertHasDistinctIds(List<String> ids) {
        assertFalse(ids.isEmpty(), "分类清单不能为空");

        Set<String> distinct = new HashSet<>();
        for (String id : ids) {
            assertNotNull(id, "分类标识不能为空");
            assertFalse(id.isEmpty(), "分类标识不能为空串");
            assertTrue(distinct.add(id), "分类标识不能重复：" + id);
        }
    }

    private static boolean isDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i)) == false) {
                return false;
            }
        }
        return text.isEmpty() == false;
    }

    private static String readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, count);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }
}

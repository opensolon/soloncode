package org.noear.solon.codecli.market.skill.impl;

import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.market.skill.SkillMarketCategory;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClawHub 分类表解析的离线单测（不联网）。
 *
 * <p>分类名是源站动态内容，必须原样展示。这里锁住两件在线上踩过的事：</p>
 * <ul>
 *   <li>分类表 chunk 里同一文件有多套分类法、slug 重复，按首个 integrations 定位会串到
 *       purposes 那套（它没有 automation，也就没有 automation 之后的所有类目）；</li>
 *   <li>解析失败必须返回 null 让调用方退兜底清单，不能抛异常、也不能返回半套数据。</li>
 * </ul>
 */
public class ClawhubCategoryParseTest {

    /** 模拟源站：第 1 套分类法也以 security 开头且有 other，第 2 套才是真正的 category 数组 */
    private static final String MIXED_TAXONOMIES =
            "var a=[{slug:`security`,label:`Security`,icon:`shield`},"
                    + "{slug:`other`,label:`Other`,icon:`package`}],"
                    + "o=[{slug:`integrations`,label:`Integrations`,icon:`plug`},"
                    + "{slug:`automation`,label:`Automation`,icon:`zap`},"
                    + "{slug:`security`,label:`Security`,icon:`shield`},"
                    + "{slug:`other`,label:`Other`,icon:`package`}]";

    @Test
    void picksCategoryTaxonomyNotTheOtherOne() throws Exception {
        List<SkillMarketCategory> list = parse(MIXED_TAXONOMIES);

        assertNotNull(list, "应当识别出 category 数组");
        assertEquals(4, list.size(), "从 integrations 收到 other，共 4 项");
        assertEquals("integrations", list.get(0).getId());
        assertEquals("Integrations", list.get(0).getName());
        assertEquals("other", list.get(list.size() - 1).getId());
        assertTrue(list.stream().anyMatch(c -> "automation".equals(c.getId())),
                "串到 purposes 那套时不会有 automation");
    }

    @Test
    void categoryNameIsVerbatimSourceLabel() throws Exception {
        List<SkillMarketCategory> list = parse(MIXED_TAXONOMIES);

        assertNotNull(list);
        assertEquals("Automation", list.get(1).getName(), "分类名必须原样取源站 label，不做翻译");
        assertEquals("Security", list.get(2).getName());
    }

    @Test
    void returnsNullWhenTaxonomyMissing() throws Exception {
        assertNull(parse("function foo(){return 1}"), "没有分类表时必须返回 null 让调用方退兜底");
        assertNull(parse("o=[{slug:`integrations`,label:`Integrations`}]"),
                "只有 integrations 没有 automation 时不算 category 数组");
    }

    @Test
    void fallbackListStillMatchesSourceLabels() throws Exception {
        Method method = ClawhubSkillMarket.class.getDeclaredMethod("fallbackCategories");
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SkillMarketCategory> list = (List<SkillMarketCategory>) method.invoke(null);

        assertEquals(14, list.size(), "兜底快照应与源站 14 个分类一致");
        assertEquals("integrations", list.get(0).getId());
        assertEquals("other", list.get(13).getId());
        // 兜底也是源站标签（英文原样），不是翻译
        list.forEach(c -> {
            assertNotNull(c.getName());
            assertFalse(c.getName().matches(".*[\\u4e00-\\u9fff].*"),
                    "兜底分类名不能是中文（源站是英文标签）：" + c.getId());
        });
    }

    private static List<SkillMarketCategory> parse(String js) throws Exception {
        ClawhubSkillMarket market = new ClawhubSkillMarket();
        Method method = ClawhubSkillMarket.class.getDeclaredMethod("parseCategoryTaxonomy", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SkillMarketCategory> list = (List<SkillMarketCategory>) method.invoke(market, js);
        return list;
    }
}

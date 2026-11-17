package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 市场分类名展示契约。
 *
 * <p>分类名是市场返回的动态内容（腾讯云广场是中文、ClawHub 是英文），
 * 口径是「源方是什么就显示什么」，不许走 i18n 翻译。这里锁住三件事：</p>
 * <ul>
 *   <li>两个市场的分类 chip 都直接渲染 {@code c.name}；</li>
 *   <li>分类名没有经过任何 i18n 查表（回归点：容易「顺手」翻译成中文）；</li>
 *   <li>「全部」是界面文案而非分类，仍然要走 i18n（不能被一起改硬编码）。</li>
 * </ul>
 */
public class MarketCategoryNameWebContractTest {

    private static final String SKILL_JS = "/static/js/app-settings-skill.js";
    private static final String MCP_JS = "/static/js/app-settings-mcp-market.js";

    @Test
    public void skillCategoryNameRendersVerbatim() throws IOException {
        String js = resourceText(SKILL_JS);

        assertTrue(js.contains("+ escapeHtml(c.name || '')"),
                "技能市场分类 chip 必须原样渲染市场的分类名");
        assertTrue(js.contains("I18n.t('skills.marketAllCategories')"),
                "「全部」是界面文案，必须走 i18n");
        assertFalse(js.contains("I18n.t(c.name"),
                "分类名是动态内容，不许走 i18n");
        assertFalse(js.contains("I18n.t('skills.category"),
                "分类名是动态内容，不许按分类 key 查语言包");
    }

    @Test
    public void mcpCategoryNameRendersVerbatim() throws IOException {
        String js = resourceText(MCP_JS);

        assertTrue(js.contains("+ escapeHtml(c.name || '')"),
                "连接器市场分类 chip 必须原样渲染市场的分类名");
        assertTrue(js.contains("I18n.t('mcp.marketAllCategories')"),
                "「全部」是界面文案，必须走 i18n");
        assertFalse(js.contains("I18n.t(c.name"),
                "分类名是动态内容，不许走 i18n");
        assertFalse(js.contains("I18n.t('mcp.category"),
                "分类名是动态内容，不许按分类 key 查语言包");
    }

    private static String resourceText(String path) throws IOException {
        try (InputStream in = MarketCategoryNameWebContractTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing resource: " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) >= 0; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

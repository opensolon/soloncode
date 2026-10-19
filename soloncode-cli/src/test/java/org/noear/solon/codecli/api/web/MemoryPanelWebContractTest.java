package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 心智记忆面板关闭链路的静态资源契约测试。
 *
 * @author noear
 */
public class MemoryPanelWebContractTest {
    @Test
    void organizeAction_completelyClosesViewerBeforeFillingCommand() throws IOException {
        String javascript = resourceText("/static/js/app-memory.js");

        assertTrue(javascript.contains("centerViewer.style.display = 'none';"),
                "关闭记忆面板必须隐藏共享 Viewer，不能只移除样式类");
        assertTrue(javascript.contains("if (chatView) chatView.style.display = '';"),
                "关闭记忆面板后必须清除聊天视图的内联隐藏状态");
        assertTrue(javascript.contains("if (newChatView) newChatView.style.display = '';"),
                "关闭记忆面板后必须清除欢迎视图的内联隐藏状态");

        int organizeHandler = javascript.indexOf("centerViewerMemOrganize.addEventListener('click'");
        int closeCall = javascript.indexOf("closeOverlay();", organizeHandler);
        int fillCall = javascript.indexOf("window.fillMemoryText();", organizeHandler);
        assertTrue(organizeHandler >= 0 && closeCall > organizeHandler && fillCall > closeCall,
                "整理记忆必须先完整关闭面板，再向当前输入框填入命令");
    }

    @Test
    void memoryAndAutomation_shareTwoLevelEmptyState() throws IOException {
        String memory = resourceText("/static/js/app-memory.js");
        String automation = resourceText("/static/js/app-automation.js");
        for (String javascript : new String[]{memory, automation}) {
            assertTrue(javascript.contains("class=\"panel-list-empty\""));
            assertTrue(javascript.contains("class=\"panel-list-empty-title\""));
            assertTrue(javascript.contains("class=\"panel-list-empty-desc\""));
        }
        assertTrue(memory.contains("I18n.t('memory.empty.title')"));
        assertTrue(memory.contains("I18n.t('memory.empty.desc')"));
        assertTrue(automation.contains("I18n.t('automation.empty.title')"));
        assertTrue(automation.contains("I18n.t('automation.empty.desc')"));
        assertTrue(memory.contains("items.length === 0 && expandedKey !== NEW_KEY"));
        assertTrue(automation.contains("expandedId === NEW_ID ? rowHtml({}, true) : ''"));

        String css = resourceText("/static/css/app.css");
        assertTrue(css.contains(".panel-list-empty { padding: 36px 16px; text-align: center;"));
        assertTrue(css.contains(".panel-list-empty-title { color: var(--text-secondary); font-size: var(--fs-md); }"));
        assertTrue(css.contains(".panel-list-empty-desc { margin-top: 4px; color: var(--text-tertiary); font-size: var(--fs-sm); }"));
        assertFalse(css.contains(".memory-list-empty"));
        assertFalse(css.contains(".automation-empty"));
    }

    @Test
    void emptyStateCopy_isAvailableInEverySupportedLocale() throws IOException {
        String[] locales = {"ar", "bn", "br", "bs", "da", "de", "en", "es", "fr", "gr", "it",
                "ja", "ko", "no", "pl", "ru", "th", "tr", "uk", "vi", "zh-CN", "zh-TW"};
        for (String locale : locales) {
            String json = resourceText("/static/i18n/" + locale + ".json");
            for (String key : new String[]{"memory.empty.title", "memory.empty.desc",
                    "automation.empty.title", "automation.empty.desc"}) {
                assertTrue(json.matches("(?s).*\"" + java.util.regex.Pattern.quote(key)
                        + "\"\\s*:\\s*\"[^\"]+\".*"), locale + " missing " + key);
            }
        }
    }

    private String resourceText(String path) throws IOException {
        InputStream input = MemoryPanelWebContractTest.class.getResourceAsStream(path);
        if (input == null) {
            throw new IOException("Missing test resource: " + path);
        }
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) >= 0) {
                out.write(buffer, 0, len);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

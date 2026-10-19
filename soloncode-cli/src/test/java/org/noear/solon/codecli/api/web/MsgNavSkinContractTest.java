package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * msg-nav 导航点与思考块（reason-group-think）的皮肤配色契约：
 * 1. 导航点 idle/hover 使用专用前景令牌 --msg-nav-dot（对主背景保持可见），
 *    不得借用气泡表面色 --bg-user-msg（浅色主题下与背景趋同而不可见）；
 * 2. 内置皮肤 default/eyecare/contrast 均必须在 light 与 dark 两态定义 --msg-nav-dot，
 *    保证皮肤间、明暗间均有差异；
 * 3. contrast（高对比无障碍）皮肤的思考块边框不得低于其通用边框强度
 *    （历史上 dark 态曾用 #444444，对深色主背景仅约 1.5:1）。
 */
public class MsgNavSkinContractTest {

    @Test
    @DisplayName("导航点 idle/hover 使用 --msg-nav-dot 前景令牌而非 --bg-user-msg")
    void msgNavDotUsesDedicatedToken() throws IOException {
        String css = resourceText("/static/css/app.css");
        assertRule(css, ".msg-nav-block", "background: var(--msg-nav-dot, var(--text-secondary))");
        assertRule(css, ".msg-nav-block:hover", "background: var(--text-primary, var(--msg-nav-dot))");
        int start = css.indexOf(".msg-nav-block {");
        assertTrue(start >= 0, "missing .msg-nav-block rule");
        String rule = css.substring(start, css.indexOf('}', start));
        assertFalse(rule.contains("--bg-user-msg"),
                "导航点 idle 态不得使用气泡表面色（浅色皮肤下与主背景趋同）");
    }

    @Test
    @DisplayName("主题与全部内置皮肤在明暗两态均定义 --msg-nav-dot")
    void everyThemeAndSkinDefinesMsgNavDot() throws IOException {
        assertThemeBlockDefines(resourceText("/static/css/theme.css"), "msg-nav-dot", 2);
        // default 皮肤有意为空，完全继承 theme.css；不得单边覆盖（否则另一态会丢失令牌）
        String defaultSkin = resourceText("/static/skin/default/skin.css");
        assertFalse(defaultSkin.contains("--msg-nav-dot"),
                "default 皮肤应继承 theme.css，若覆盖必须两态同时定义");
        assertSkinDefines(resourceText("/static/skin/eyecare/skin.css"), "eyecare");
        assertSkinDefines(resourceText("/static/skin/contrast/skin.css"), "contrast");
    }

    @Test
    @DisplayName("contrast 皮肤两态的思考块边框与通用边框同强度")
    void contrastThinkBorderMatchesGenericBorder() throws IOException {
        String css = resourceText("/static/skin/contrast/skin.css");
        String lightBlock = blockOf(css, "[data-skin=\"contrast\"][data-theme=\"light\"]");
        String darkBlock = blockOf(css, "[data-skin=\"contrast\"][data-theme=\"dark\"]");
        assertTrue(lightBlock.contains("--reason-group-think-border: " + valueOf(lightBlock, "--border-color") + ";"),
                "contrast light 思考块边框应与其 --border-color 同值");
        assertTrue(darkBlock.contains("--reason-group-think-border: " + valueOf(darkBlock, "--border-color") + ";"),
                "contrast dark 思考块边框应与其 --border-color 同值（#444444 对深色背景仅约 1.5:1）");
    }

    private void assertSkinDefines(String css, String name) {
        assertThemeBlockDefines(css, "msg-nav-dot", 2, name);
    }

    private void assertThemeBlockDefines(String css, String token, int expectedBlocks) {
        assertThemeBlockDefines(css, token, expectedBlocks, "theme");
    }

    private void assertThemeBlockDefines(String css, String token, int expectedBlocks, String source) {
        int count = 0;
        int idx = 0;
        while ((idx = css.indexOf("--" + token + ":", idx)) >= 0) {
            count++;
            idx += token.length();
        }
        assertTrue(count >= expectedBlocks,
                source + " 应至少在 " + expectedBlocks + " 个主题块（light/dark）定义 --" + token + "，实际 " + count);
    }

    private String blockOf(String css, String selector) {
        int start = css.indexOf(selector + " {");
        assertTrue(start >= 0, "missing selector: " + selector);
        return css.substring(start, css.indexOf('}', start));
    }

    private String valueOf(String block, String prop) {
        int idx = block.indexOf(prop + ":");
        assertTrue(idx >= 0, "missing property in block: " + prop);
        int end = block.indexOf(';', idx);
        return block.substring(idx + prop.length() + 1, end).trim();
    }

    private void assertRule(String css, String selector, String... declarations) {
        int start = css.indexOf(selector + " {");
        assertTrue(start >= 0, "missing selector: " + selector);
        String rule = css.substring(start, css.indexOf('}', start));
        for (String declaration : declarations) {
            assertTrue(rule.contains(declaration + ";"), selector + " must declare " + declaration);
        }
    }

    private String resourceText(String path) throws IOException {
        try (InputStream in = MsgNavSkinContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("resource not found: " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

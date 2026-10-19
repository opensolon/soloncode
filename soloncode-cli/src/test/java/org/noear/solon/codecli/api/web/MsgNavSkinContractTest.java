package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Static contracts for the built-in skins' auxiliary navigation and reasoning header. */
public class MsgNavSkinContractTest {
    private static final String[] TOKENS = {
            "--msg-nav-dot", "--msg-nav-dot-hover", "--msg-nav-dot-active", "--msg-nav-dot-highlight",
            "--reason-header-color", "--reason-header-hover-bg", "--reason-header-streaming-color"
    };

    @Test
    @DisplayName("导航普通、悬停、当前位置及联动高亮使用独立令牌")
    void msgNavDotUsesDedicatedToken() throws IOException {
        String css = resourceText("/static/css/app.css");
        assertRule(css, ".msg-nav-block", "background: var(--msg-nav-dot, var(--text-secondary))");
        assertRule(css, ".msg-nav-block:hover", "background: var(--msg-nav-dot-hover, var(--text-secondary))");
        assertRule(css, ".msg-nav-block.active", "background: var(--msg-nav-dot-active, var(--accent))", "width: 16px");
        assertRule(css, ".msg-nav-block.highlight", "background: var(--msg-nav-dot-highlight, var(--accent))");
        String rule = blockOf(css, ".msg-nav-block");
        assertFalse(rule.contains("--bg-user-msg"));
        assertFalse(rule.contains("opacity:"), "导航颜色不得再叠加透明度导致不可见");
        // active width remains an independent location cue when highlight overrides its color.
        assertFalse(blockOf(css, ".msg-nav-block.highlight").contains("width:"));
    }

    @Test
    @DisplayName("思考标题、箭头、流式文字与图标统一走皮肤令牌")
    void reasonHeaderUsesDedicatedTokens() throws IOException {
        String css = resourceText("/static/css/app.css");
        assertRule(css, ".reason-group-think-header:hover", "background: var(--reason-header-hover-bg, var(--bg-hover))");
        for (String selector : new String[]{".reason-group-think-label", ".reason-group-think-toggle"}) {
            assertRule(css, selector, "color: var(--reason-header-color, var(--text-secondary))");
        }
        for (String selector : new String[]{".reason-group-think.streaming .reason-group-think-label", ".reason-group-think-spinner"}) {
            assertRule(css, selector, "color: var(--reason-header-streaming-color, var(--accent))");
        }
        assertRule(css, ".reason-group-think-dots span", "background: var(--reason-header-streaming-color, var(--accent))");
    }

    @Test
    @DisplayName("全部内置皮肤分别在明暗选择器定义全部组件令牌")
    void everyThemeAndSkinDefinesTokensInBothModes() throws IOException {
        String theme = resourceText("/static/css/theme.css");
        String defaultSkin = resourceText("/static/skin/default/skin.css");
        for (String mode : new String[]{"light", "dark"}) {
            for (String skin : new String[]{"default", "eyecare", "contrast"}) {
                String block = palette(skin, mode);
                for (String token : TOKENS) {
                    assertFalse(valueOf(block, token).isEmpty(), skin + "/" + mode + ": " + token);
                }
            }
        }
        String fallback = blockOf(theme, ":root:not([data-theme])");
        String dark = palette("default", "dark");
        for (String token : TOKENS) {
            assertEquals(valueOf(dark, token), valueOf(fallback, token), "系统深色回退: " + token);
            assertFalse(defaultSkin.contains(token), "default 皮肤保持继承 theme.css");
        }
    }

    @Test
    @DisplayName("六种配色标题普通及流式态在普通和悬停背景均达到4.5:1")
    void headerTextRemainsReadable() throws IOException {
        for (String skin : new String[]{"default", "eyecare", "contrast"}) {
            for (String mode : new String[]{"light", "dark"}) {
                String block = palette(skin, mode);
                for (String fg : new String[]{"--reason-header-color", "--reason-header-streaming-color"}) {
                    for (String bg : new String[]{"--reason-group-think-bg", "--reason-header-hover-bg"}) {
                        assertContrast(block, fg, bg, 4.5, skin + "/" + mode);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("导航各状态可辨识且层次递进，高对比强于默认")
    void navigationContrastAndHierarchy() throws IOException {
        for (String mode : new String[]{"light", "dark"}) {
            for (String skin : new String[]{"default", "eyecare", "contrast"}) {
                String block = palette(skin, mode);
                double previous = 0;
                for (String token : new String[]{"--msg-nav-dot", "--msg-nav-dot-hover", "--msg-nav-dot-active"}) {
                    double ratio = contrast(resolve(block, token), resolve(block, "--bg-main"));
                    assertTrue(ratio >= 3, skin + "/" + mode + token + " contrast=" + ratio);
                    assertTrue(ratio > previous, "idle < hover < active: " + skin + "/" + mode);
                    previous = ratio;
                }
                assertContrast(block, "--msg-nav-dot-highlight", "--bg-main", 3, skin + "/" + mode);
            }
            String normal = palette("default", mode);
            String high = palette("contrast", mode);
            assertTrue(contrast(resolve(high, "--msg-nav-dot"), resolve(high, "--bg-main"))
                    > contrast(resolve(normal, "--msg-nav-dot"), resolve(normal, "--bg-main")));
            assertNotEquals(valueOf(normal, "--reason-group-think-bg"), valueOf(high, "--reason-group-think-bg"));
            assertNotEquals(valueOf(normal, "--reason-header-color"), valueOf(high, "--reason-header-color"));
        }
        // Keep default light idle restrained; do not return to near-black body text.
        String light = palette("default", "light");
        assertTrue(contrast(resolve(light, "--msg-nav-dot"), resolve(light, "--bg-main")) < 3.5);
    }

    @Test
    @DisplayName("contrast 两态思考块边框保持与通用边框一致")
    void contrastThinkBorderMatchesGenericBorder() throws IOException {
        for (String mode : new String[]{"light", "dark"}) {
            String block = palette("contrast", mode);
            assertEquals(valueOf(block, "--border-color"), valueOf(block, "--reason-group-think-border"));
        }
    }

    private String palette(String skin, String mode) throws IOException {
        if ("default".equals(skin)) {
            return blockOf(resourceText("/static/css/theme.css"), "[data-theme=\"" + mode + "\"]");
        }
        return blockOf(resourceText("/static/skin/" + skin + "/skin.css"),
                "[data-skin=\"" + skin + "\"][data-theme=\"" + mode + "\"]");
    }

    private String resolve(String block, String prop) {
        String value = valueOf(block, prop);
        if (value.startsWith("var(")) {
            return resolve(block, value.substring(4, value.length() - 1));
        }
        assertTrue(value.matches("#[0-9a-fA-F]{6}"), "expected opaque RGB: " + prop + "=" + value);
        return value;
    }

    private void assertContrast(String block, String fg, String bg, double minimum, String context) {
        double ratio = contrast(resolve(block, fg), resolve(block, bg));
        assertTrue(ratio >= minimum, context + " " + fg + " on " + bg + " contrast=" + ratio);
    }

    private double contrast(String a, String b) {
        double l1 = luminance(a), l2 = luminance(b);
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    }

    private double luminance(String hex) {
        double result = 0;
        double[] weights = {0.2126, 0.7152, 0.0722};
        for (int i = 0; i < 3; i++) {
            double c = Integer.parseInt(hex.substring(1 + i * 2, 3 + i * 2), 16) / 255.0;
            result += weights[i] * (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
        return result;
    }

    private String blockOf(String css, String selector) {
        int start = css.indexOf(selector + " {");
        assertTrue(start >= 0, "missing selector: " + selector);
        return css.substring(start, css.indexOf('}', start));
    }

    private String valueOf(String block, String prop) {
        int idx = block.indexOf(prop + ":");
        assertTrue(idx >= 0, "missing property in block: " + prop);
        return block.substring(idx + prop.length() + 1, block.indexOf(';', idx)).trim();
    }

    private void assertRule(String css, String selector, String... declarations) {
        String rule = blockOf(css, selector);
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

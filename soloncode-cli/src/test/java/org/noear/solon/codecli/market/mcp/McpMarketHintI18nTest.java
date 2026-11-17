package org.noear.solon.codecli.market.mcp;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 市场草稿提示文案的离线单测（不联网）。
 *
 * <p>草稿里给界面的提示不再是写死的中文，而是「语言包键 + 参数」。
 * 键名拼错、或新增语言包时漏加，界面就会退化成显示键名本身，
 * 故这里对着全部语言包逐个核对。</p>
 */
public class McpMarketHintI18nTest {
    /**
     * 与 src/main/resources/static/i18n 下的语言包一一对应（新增语言包时同步这里）。
     */
    private static final List<String> LOCALES = Arrays.asList(
            "ar", "bn", "br", "bs", "da", "de", "en", "es", "fr", "gr", "it",
            "ja", "ko", "no", "pl", "ru", "th", "tr", "uk", "vi", "zh-CN", "zh-TW");

    /**
     * 适配器会交给界面的提示键。
     */
    private static final List<String> HINT_KEYS = Arrays.asList(
            "mcp.marketNoteDoc",
            "mcp.marketNotePlatformParams",
            "mcp.marketParamSmitheryKey",
            "mcp.marketParamPrefixed");

    private ONode loadPack(String locale) throws Exception {
        String path = "static/i18n/" + locale + ".json";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "语言包缺失: " + path);
            return ONode.ofJson(new String(readAll(in), StandardCharsets.UTF_8));
        }
    }

    /**
     * Java 8 没有 InputStream.readAllBytes()。
     */
    private byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    @Test
    public void everyLocalePackHasAllHintKeys() throws Exception {
        for (String locale : LOCALES) {
            ONode pack = loadPack(locale);
            for (String key : HINT_KEYS) {
                String value = pack.get(key).getString();
                assertNotNull(value, locale + " 缺少提示键: " + key);
                assertFalse(value.trim().isEmpty(), locale + " 的提示键为空: " + key);
                assertNotEquals(key, value, locale + " 的提示键未翻译: " + key);
            }
        }
    }

    @Test
    public void hintArgsFillPlaceholdersInEveryLocale() throws Exception {
        // 带占位符的两个键：参数名对不上，界面上就会原样显示 {names} / {site}
        for (String locale : LOCALES) {
            ONode pack = loadPack(locale);
            assertTrue(pack.get("mcp.marketNotePlatformParams").getString().contains("{names}"), locale);
            assertTrue(pack.get("mcp.marketNotePlatformParams").getString().contains("{site}"), locale);
            assertTrue(pack.get("mcp.marketParamPrefixed").getString().contains("{prefix}"), locale);
        }
    }

    @Test
    public void localeHintCarriesKeyAndArgs() {
        McpLocaleHint plain = McpLocaleHint.of("mcp.marketNoteDoc");
        assertEquals("mcp.marketNoteDoc", plain.getKey());
        assertNull(plain.getArgs());

        McpLocaleHint one = McpLocaleHint.of("mcp.marketParamPrefixed", "prefix", "Bearer");
        assertEquals("Bearer", one.getArgs().get("prefix"));

        McpLocaleHint two = McpLocaleHint.of("mcp.marketNotePlatformParams",
                "names", "braveApiKey", "site", "smithery.ai");
        assertEquals("braveApiKey", two.getArgs().get("names"));
        assertEquals("smithery.ai", two.getArgs().get("site"));
    }
}

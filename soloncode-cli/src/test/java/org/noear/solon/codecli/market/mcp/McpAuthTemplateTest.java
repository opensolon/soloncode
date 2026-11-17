package org.noear.solon.codecli.market.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 鉴权取值模板解析的离线单测（不联网）。
 *
 * <p>样本全部取自官方 Registry 的真实字段，验证「方案前缀能被抠出来预填」这件事，
 * 它决定用户粘 key 后得到的是 {@code Bearer sk_xxx} 还是必然 401 的裸值。</p>
 */
public class McpAuthTemplateTest {

    @Test
    public void literalPrefixKeepsSchemeAndTrailingSpace() {
        // ai.admux/admux: value = "Bearer {api_key}"
        assertEquals("Bearer ", McpAuthTemplate.literalPrefix("Bearer {api_key}"));
        // 变量在中间时，只取到第一个花括号为止
        assertEquals("https://", McpAuthTemplate.literalPrefix("https://{host}/mcp"));
    }

    @Test
    public void literalPrefixRejectsTemplatesWithoutLeadingLiteral() {
        assertNull(McpAuthTemplate.literalPrefix("{api_key}"));
        assertNull(McpAuthTemplate.literalPrefix("sk_live_literal_value"));
        assertNull(McpAuthTemplate.literalPrefix(null));
    }

    @Test
    public void variableNameComesFromFirstPlaceholder() {
        assertEquals("api_key", McpAuthTemplate.variableName("Bearer {api_key}"));
        assertEquals("host", McpAuthTemplate.variableName("https://{host}/mcp"));
        assertNull(McpAuthTemplate.variableName("Bearer sk_live_xxx"));
        assertNull(McpAuthTemplate.variableName("Bearer {}"));
        assertNull(McpAuthTemplate.variableName(null));
    }

    @Test
    public void schemePrefixNormalizesCaseAndSeparator() {
        assertEquals("Bearer ", McpAuthTemplate.schemePrefix("bearer vera_live_..."));
        assertEquals("Bearer ", McpAuthTemplate.schemePrefix("BEARER x"));
        assertEquals("Bearer ", McpAuthTemplate.schemePrefix("Bearer"));
        assertEquals("Basic ", McpAuthTemplate.schemePrefix("Basic dXNlcg=="));
        // 字段名不是方案
        assertNull(McpAuthTemplate.schemePrefix("BearerToken"));
        // 腾讯云的占位文案是中文指引，不该冒出前缀
        assertNull(McpAuthTemplate.schemePrefix("请输入腾讯云 SecretId"));
        assertNull(McpAuthTemplate.schemePrefix("vera_live_..."));
        assertNull(McpAuthTemplate.schemePrefix(null));
    }

    @Test
    public void hintCollapsesWhitespaceAndTruncates() {
        assertEquals("Bearer <key> 到后台获取", McpAuthTemplate.hint("Bearer <key>\n  到后台获取"));
        assertNull(McpAuthTemplate.hint("  \n\t "));
        assertNull(McpAuthTemplate.hint(null));

        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longText.append('x');
        }
        String hint = McpAuthTemplate.hint(longText.toString());
        assertEquals(201, hint.length());
        assertTrue(hint.endsWith("…"));
    }
}

package org.noear.solon.codecli.market.mcp.impl;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.codecli.market.mcp.McpMarketParam;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code remotes[].headers[]} → 待填参数的离线单测（不联网）。
 *
 * <p>样本全部摘自 registry.modelcontextprotocol.io 的真实条目，锁住三件事：</p>
 * <ol>
 *     <li>鉴权方案前缀（{@code "Bearer "}）能预填，用户只需粘 key；</li>
 *     <li>{@code variables.<name>} 的机密性/必填能补上 header 自身未标的信息；</li>
 *     <li>描述原样透传（含“可留空走 OAuth”这类关键提示），并压成一行、限长。</li>
 * </ol>
 */
public class OfficialRegistryHeaderParamTest {

    private static McpMarketParam param(String headerJson) {
        return OfficialRegistryMcpMarket.headerParam(ONode.ofJson(headerJson));
    }

    @Test
    public void templateHeaderGetsSchemePrefixAndSecretFromVariables() {
        // ai.admux/admux：value 里带 {api_key}，机密性写在 variables 里
        McpMarketParam p = param("{"
                + "\"name\":\"Authorization\","
                + "\"description\":\"Admux API key, created in the web app by a workspace owner or campaign manager.\","
                + "\"value\":\"Bearer {api_key}\","
                + "\"isSecret\":true,\"isRequired\":true,"
                + "\"variables\":{\"api_key\":{\"description\":\"Admux API key (amx_…)\","
                + "\"isRequired\":true,\"isSecret\":true}}}");

        assertEquals("Authorization", p.getName());
        assertEquals("Bearer ", p.getValuePrefix(), "模板里的方案前缀必须预填，否则用户粘出裸 key");
        assertTrue(p.isSecret());
        assertTrue(p.isRequired());
        assertNull(p.getDefaultValue(), "模板取值不能当默认值用");
        assertEquals(McpMarketParam.IN_HEADER, p.getIn());
        assertTrue(p.isNeedUserInput());
    }

    @Test
    public void variablesFillInSecretWhenHeaderOmitsIt() {
        // 变量名与 value 里的不一致时，variables 只有一个键仍可兜住
        McpMarketParam p = param("{"
                + "\"name\":\"Authorization\","
                + "\"value\":\"Bearer {apiKey}\","
                + "\"variables\":{\"api_key\":{\"isSecret\":true,\"isRequired\":true,"
                + "\"description\":\"Platform key from the console\"}}}");

        assertEquals("Bearer ", p.getValuePrefix());
        assertTrue(p.isSecret());
        assertTrue(p.isRequired(), "variables 里标了 isRequired，header 未标也要算必填");
        assertEquals("Platform key from the console", p.getDescription(), "header 无描述时取变量描述");
    }

    @Test
    public void placeholderProvidesPrefixAndHint() {
        // ae.waggle/vera：没有 value，只有占位文案与描述
        McpMarketParam p = param("{"
                + "\"name\":\"Authorization\","
                + "\"description\":\"Your Vera API key as a Bearer token. Create a key at vera.waggle.\","
                + "\"placeholder\":\"Bearer vera_live_...\","
                + "\"isRequired\":true}");

        assertEquals("Bearer ", p.getValuePrefix());
        assertTrue(p.isSecret(), "Authorization 里的值一律按凭据处理");
        assertTrue(p.getDescription().startsWith("Your Vera API key as a Bearer token."),
                "描述要透传到表单，供用户判断去哪里取 key");
    }

    @Test
    public void descriptionOnlyAuthorizationStillGetsPrefix() {
        // ae.easyvoice/tts：value 与 placeholder 都没有，方案只能从描述首词取
        McpMarketParam p = param("{"
                + "\"name\":\"Authorization\","
                + "\"description\":\"Bearer <your EasyVoice API key> (free key at https://easyvoice.ae/account/keys)\","
                + "\"isRequired\":true}");

        assertEquals("Bearer ", p.getValuePrefix());
    }

    @Test
    public void nonAuthorizationHeaderNeverGetsBearerPrefix() {
        // agency.thirdhemisphere/ai-authority-index：x-api-key 直接放 key，不带方案
        McpMarketParam p = param("{"
                + "\"name\":\"x-api-key\","
                + "\"description\":\"Optional free API key for higher rate limits. Request one at https://thirdhemisphere.agency\","
                + "\"isRequired\":false}");

        assertNull(p.getValuePrefix(), "x-api-key 预填 Bearer 反而是错的");
        assertFalse(p.isSecret());
        assertFalse(p.isRequired());
        assertFalse(p.isNeedUserInput());
    }

    @Test
    public void oauthHintInDescriptionSurvivesToForm() {
        // ai.acquisity/mcp：描述里就有“留空即走 OAuth”的说明，不能被截掉
        McpMarketParam p = param("{"
                + "\"name\":\"Authorization\","
                + "\"description\":\"Optional. \\\"Bearer <Acquisity API key>\\\". Leave unset to sign in with OAuth.\"}");

        assertTrue(p.getDescription().contains("OAuth"));
        assertNull(p.getValuePrefix(), "描述不是以方案开头，不预填前缀");
    }

    @Test
    public void literalValueStaysAsDefaultWhenNotSecret() {
        McpMarketParam p = param("{"
                + "\"name\":\"X-Client\","
                + "\"value\":\"soloncode\","
                + "\"isRequired\":false}");

        assertEquals("soloncode", p.getDefaultValue());
        assertNull(p.getValuePrefix());
        assertFalse(p.isNeedUserInput());
    }

    @Test
    public void longDescriptionIsFlattenedAndTruncated() {
        StringBuilder desc = new StringBuilder("Bearer <key> 到后台获取\\n");
        for (int i = 0; i < 300; i++) {
            desc.append("说明");
        }
        McpMarketParam p = param("{\"name\":\"Authorization\",\"description\":\"" + desc + "\"}");

        assertFalse(p.getDescription().contains("\n"), "提示文案要压成一行");
        assertTrue(p.getDescription().length() <= 201);
        assertEquals(200, p.getDescription().length() - (p.getDescription().endsWith("…") ? 1 : 0));
        assertEquals("Bearer ", p.getValuePrefix());
    }

    @Test
    public void namelessHeaderIsDropped() {
        assertNull(param("{\"value\":\"Bearer {api_key}\"}"));
        assertNull(param("{\"name\":\"\"}"));
    }
}

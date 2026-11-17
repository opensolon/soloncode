package org.noear.solon.codecli.market.mcp.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smithery 草稿的出处链接（docUrl）生成规则。
 *
 * <p>回归目标：详情接口不返回 homepage，草稿的 docUrl 曾因此恒为空；
 * 改为指向站点服务列表页。这里锁住三点：</p>
 * <ol>
 *     <li>永远不给 {@code /servers/{qualifiedName}} 这种大概率 404 的路径；</li>
 *     <li>带关键词时斜杠保留原样（不影响检索），大小写原样保留（站点侧大小写敏感）；</li>
 *     <li>空名兜底到列表页，不产出 {@code ?q=} 这种空查询。</li>
 * </ol>
 *
 * @author noear 2026/11/17 created
 */
public class SmitheryMcpMarketDocUrlTest {

    @Test
    @DisplayName("带关键词：落在服务列表页，且不拼条目详情路径")
    void buildsListPageWithQuery() {
        assertEquals("https://smithery.ai/servers?q=exa",
                SmitheryMcpMarket.serversPageUrl("exa"));
    }

    @Test
    @DisplayName("owner/name 形式的 qualifiedName：斜杠保留，不被编码成 %2F")
    void keepsSlashForNamespacedId() {
        assertEquals("https://smithery.ai/servers?q=zachary-royals/devmatch",
                SmitheryMcpMarket.serversPageUrl("zachary-royals/devmatch"));
    }

    @Test
    @DisplayName("大小写原样保留（站点侧路径与检索均区分大小写）")
    void keepsOriginalCase() {
        assertEquals("https://smithery.ai/servers?q=Tavily",
                SmitheryMcpMarket.serversPageUrl("Tavily"));
    }

    @Test
    @DisplayName("多单词名称：空格转义，不产生裸空格")
    void encodesSpaces() {
        String url = SmitheryMcpMarket.serversPageUrl("paper search");
        assertFalse(url.contains(" "), "URL 内不得出现裸空格: " + url);
        assertTrue(url.startsWith("https://smithery.ai/servers?q="), url);
    }

    @Test
    @DisplayName("名称为空：兜底到列表页，不产出空查询")
    void fallsBackToListPageWhenBlank() {
        assertEquals("https://smithery.ai/servers", SmitheryMcpMarket.serversPageUrl(null));
        assertEquals("https://smithery.ai/servers", SmitheryMcpMarket.serversPageUrl(""));
        assertEquals("https://smithery.ai/servers", SmitheryMcpMarket.serversPageUrl("   "));
    }

    @Test
    @DisplayName("不产出条目详情路径（该路径在注册表内多数条目上返回 404）")
    void neverBuildsDetailPath() {
        String url = SmitheryMcpMarket.serversPageUrl("brave");
        assertFalse(url.contains("/servers/brave"), "不应拼详情路径: " + url);
    }
}

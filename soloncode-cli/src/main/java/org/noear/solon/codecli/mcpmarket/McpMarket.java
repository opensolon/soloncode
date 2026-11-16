package org.noear.solon.codecli.mcpmarket;

import org.noear.solon.core.handle.Result;

/**
 * 连接器（MCP）市场接口 — 抽象连接器市场的浏览、搜索与配置生成能力。
 *
 * <p>与技能市场 {@link org.noear.solon.codecli.market.Market} 的区别：
 * 技能的 install 是「下载并解压到目录」，连接器的 install 等价物是
 * {@link #resolve}「生成可预填的配置草稿」，保存仍走原 MCP 保存链路。</p>
 *
 * @author noear 2026/11/16 created
 */
public interface McpMarket {
    /**
     * 市场名称（前端下拉与 marketName 参数使用）
     */
    String name();

    /**
     * 市场描述
     */
    default String description() {
        return "";
    }

    /**
     * 列表/搜索。
     *
     * @param query  关键词，为空表示浏览默认列表
     * @param limit  每页条数
     * @param cursor 翻页游标，为空表示第一页
     */
    Result<McpMarketPage> list(String query, int limit, String cursor);

    /**
     * 条目详情（含全部可用接入方式）
     */
    Result<McpMarketDetail> detail(String id);

    /**
     * 将条目的某一种接入方式解析为配置草稿（不落库、不执行）。
     *
     * @param id       条目 id
     * @param configId 接入方式 id，为空时取第一个可用项
     */
    Result<McpServerDraft> resolve(String id, String configId);
}

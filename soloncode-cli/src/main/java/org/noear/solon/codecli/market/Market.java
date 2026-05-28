package org.noear.solon.codecli.market;

import org.noear.solon.core.handle.Result;

import java.util.List;
import java.util.Map;

/**
 * 技能市场接口 — 抽象技能市场的浏览、搜索、安装能力。
 *
 * <p>不同市场（ClawHub、Skills.sh 等）可实现此接口，由 WebSettingsController 注入使用。</p>
 *
 * @author noear 2026/5/28 created
 */
public interface Market {

    /**
     * 获取热门技能列表
     *
     * @param limit 返回数量限制
     * @return Result 包含技能列表数据（Map 列表）
     */
    Result<List<Map<String, Object>>> trending(int limit);

    /**
     * 搜索技能
     *
     * @param query 搜索关键词
     * @param limit 返回数量限制
     * @return Result 包含技能列表数据（Map 列表）
     */
    Result<List<Map<String, Object>>> search(String query, int limit);

    /**
     * 获取技能详情
     *
     * @param slug 技能唯一标识
     * @return Result 包含技能详情数据
     */
    Result<Map<String, Object>> detail(String slug);

    /**
     * 安装技能 — 下载 zip 包并解压到指定目录
     *
     * @param slug      技能唯一标识
     * @param skillsDir 目标 skills 目录
     * @return Result 安装结果（成功时 data 为 displayName）
     */
    Result<String> install(String slug, java.nio.file.Path skillsDir);
}

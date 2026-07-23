package org.noear.solon.codecli.portal.web.settings;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.portal.FileWatchService;
import org.noear.solon.codecli.portal.web.WebGate;
import org.noear.solon.codecli.portal.web.market.Market;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 *
 * @author noear 2026/7/23 created
 *
 */
public class SkillSettingsController extends BaseSettingsController{
    /**
     * 日志记录器
     */
    private static final Logger LOG = LoggerFactory.getLogger(SkillSettingsController.class);

    /**
     * 构造函数：支持自定义所有依赖。
     */
    public SkillSettingsController(HarnessEngine engine, AgentSettings settings, FileWatchService fileWatchService, WebGate webGate) {
        super(engine, settings, fileWatchService, webGate);
    }

    // ==================== 设置：Skills 市场（委派给 Market 接口） ====================

    /**
     * 获取所有可用市场列表
     */
    @Get
    @Mapping("/web/settings/skills/markets")
    public Result skillsMarkets(Context ctx) {
        return Result.succeed(marketManager.getMarketInfos());
    }

    /**
     * 技能市场代理接口 — 获取热门技能或搜索技能。
     * <p>所有外部 API 调用均由后端 Market 适配器完成，前端不直接访问外部服务。</p>
     *
     * @param action     "trending" 获取热门 | "search" 搜索
     * @param query      搜索关键词（action=search 时使用）
     * @param limit      返回数量限制
     * @param marketName 市场名字（可选，默认使用 ClawHub）
     */
    @Get
    @Mapping("/web/settings/skills/proxy")
    public Result skillsProxy(Context ctx, @Param(value = "action", defaultValue = "trending") String action,
                              @Param(value = "q", defaultValue = "") String query,
                              @Param(value = "limit", defaultValue = "50") int limit,
                              @Param(value = "per_page", defaultValue = "50") int perPage,
                              @Param(value = "marketName", defaultValue = "") String marketName) {
        Market market = marketManager.getMarketByName(marketName);
        if ("search".equals(action) && query != null && !query.isEmpty()) {
            return market.search(query, limit);
        } else {
            return market.trending(limit);
        }
    }

    /**
     * 安装技能 — 委派给 Market 适配器完成下载、解压，然后刷新技能池。
     *
     * @param slug       技能 slug（必填）
     * @param marketName 市场名称（可选）
     * @param mountAlias 挂载点别名（可选，默认安装到 workspace/skills）
     */
    @Post
    @Mapping("/web/settings/skills/install")
    public Result skillsInstall(Context ctx, @Param("slug") String slug,
                                @Param(value = "marketName", defaultValue = "") String marketName,
                                @Param(value = "mountAlias", defaultValue = "") String mountAlias) {
        if (Assert.isEmpty(slug)) {
            return Result.failure("slug is required");
        }

        Market market = marketManager.getMarketByName(marketName);

        // 确定安装目标目录：若指定了挂载别名，则安装到对应池目录；否则默认 workspace/skills
        Path skillsDir;
        if (!Assert.isEmpty(mountAlias)) {
            MountDir poolDir = engine.getMount(mountAlias);
            if (poolDir == null) {
                return Result.failure("挂载池不存在: " + mountAlias);
            }

            skillsDir = poolDir.getRealPath();
        } else {
            skillsDir = Paths.get(engine.getWorkspace(), "skills");
        }

        Result<String> result = market.install(slug, skillsDir);

        // 安装成功后刷新技能池
        if (result.getCode() == 200) {
            engine.refreshMount(mountAlias);
        }

        return result;
    }
}

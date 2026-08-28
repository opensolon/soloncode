package org.noear.solon.codecli.portal.web.settings;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.config.models.ModelSpecService;
import org.noear.solon.codecli.config.models.ModelsAdapterManager;
import org.noear.solon.codecli.portal.FileWatchService;
import org.noear.solon.codecli.portal.web.WebGate;
import org.noear.solon.codecli.market.MarketManager;
import org.noear.solon.codecli.portal.web.service.SkinService;

import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.core.handle.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 *
 * @author noear 2026/7/23 created
 *
 */
public class BaseSettingsController {
    private final WorkspaceManager workspaceManager;

    /**
     * 本地皮肤服务（Zip 安装 / 列表 / 资源代理）
     */
    protected final SkinService skinService;

    /**
     * 技能市场适配器（通过构造函数注入，方便切换不同市场）
     */
    protected final MarketManager marketManager;

    /**
     * 模型提供商工厂，用于拉取模型列表
     */
    protected final ModelsAdapterManager modelsAdapterManager;

    /**
     * 模型规格参考服务，用于从 models.json 获取上下文大小
     */
    protected final ModelSpecService modelSpecService;


    // fileWatchService()/webGate() 从当前工作区上下文动态提取；
    // 不再构造注入全局实例——注入字段从未被使用，且跨工作区场景下全局实例语义也是错的

    // 动态提取所属工作区的引擎和服务
    public WorkspaceContext currentContext() {
        Context ctx = Context.current();
        org.noear.solon.codecli.workspace.WorkspaceContext wctx = null;

        if (ctx != null) {
            wctx = ctx.attr("WORKSPACE_CTX");
        }

        if (wctx == null) {
            wctx = workspaceManager.getOrCreate(null);
        }
        return wctx;
    }

    protected HarnessEngine engine() { return currentContext().getEngine(); }
    protected AgentSettings settings() { return currentContext().getSettings(); }
    protected FileWatchService fileWatchService() { return currentContext().getFileWatchService(); }
    protected WebGate webGate() { return currentContext().getWebGate(); }

    protected WorkspaceManager workspaceManager() { return workspaceManager; }

    /**
     * 返回所有已加载工作区的引擎（含默认工作区与当前工作区）。
     * <p>多工作区架构下，通用设置、工具权限等“全局”配置保存后必须热更新到全部引擎，
     * 而非仅当前 HTTP 请求所在工作区的引擎；否则其他已加载工作区的开关不会即时生效。</p>
     */
    protected List<HarnessEngine> engines() {
        List<HarnessEngine> list = new ArrayList<>();
        for (WorkspaceContext ctx : workspaceManager.getContexts()) {
            if (ctx != null && ctx.getEngine() != null) {
                list.add(ctx.getEngine());
            }
        }
        return list;
    }

    /**
     * 构造函数：支持自定义所有依赖。
     */
    public BaseSettingsController(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;

        this.skinService = SkinService.getInstance();
        this.marketManager = MarketManager.getInstance();
        this.modelsAdapterManager = ModelsAdapterManager.getInstance();
        this.modelSpecService = ModelSpecService.getInstance();
    }

    /**
     * 将当前配置保存到 settings.json
     */
    protected void saveSettings() {
        settings().saveToFile();
    }

    /**
     * 按 Map 中指定 key 进行不区分大小写排序
     */
    protected void sortByName(List<? extends Map> list, String key) {
        list.sort((a, b) -> {
            String nameA = (String) a.getOrDefault(key, "");
            String nameB = (String) b.getOrDefault(key, "");
            return nameA.compareToIgnoreCase(nameB);
        });
    }
}

package org.noear.solon.codecli.portal.web.settings;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.config.models.ModelSpecService;
import org.noear.solon.codecli.config.models.ModelsAdapterManager;
import org.noear.solon.codecli.portal.FileWatchService;
import org.noear.solon.codecli.portal.web.WebGate;
import org.noear.solon.codecli.portal.web.market.MarketManager;
import org.noear.solon.codecli.portal.web.service.SkinService;

import java.util.List;
import java.util.Map;

/**
 *
 * @author noear 2026/7/23 created
 *
 */
public class BaseSettingsController {

    /**
     * 本地皮肤服务（Zip 安装 / 列表 / 资源代理）
     */
    protected final SkinService skinService;


    /**
     * AI Agent 执行引擎，提供模型配置管理能力
     */
    protected final HarnessEngine engine;

    /**
     * 技能市场适配器（通过构造函数注入，方便切换不同市场）
     */
    protected final MarketManager marketManager;

    /**
     * 模型提供商工厂，用于拉取模型列表
     */
    protected final ModelsAdapterManager modelProviderFactory;

    /**
     * 模型规格参考服务，用于从 models.json 获取上下文大小
     */
    protected final ModelSpecService modelSpecService;

    /**
     * 统一配置管理器，管理 LLM 模型、MCP 服务器、OpenApi 服务器的持久化数据
     */
    protected final AgentSettings settings;


    /**
     * 文件变更监听服务（由 Configurator 注入，用于动态挂载管理）
     */
    protected final FileWatchService fileWatchService;

    /**
     * Web 网关（用于前端 WebSocket 广播）
     */
    protected final WebGate webGate;

    /**
     * 构造函数：支持自定义所有依赖。
     */
    public BaseSettingsController(HarnessEngine engine, AgentSettings settings,  FileWatchService fileWatchService, WebGate webGate) {
        this.engine = engine;
        this.settings = settings;
        this.fileWatchService = fileWatchService;
        this.webGate = webGate;

        this.skinService = SkinService.getInstance();
        this.marketManager = MarketManager.getInstance();
        this.modelProviderFactory = ModelsAdapterManager.getInstance();
        this.modelSpecService = ModelSpecService.getInstance();
    }

    /**
     * 将当前配置保存到 settings.json
     */
    protected void saveSettings() {
        settings.saveToFile();
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

/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.portal.web;

import org.noear.snack4.ONode;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.memory.MemorySearchResult;
import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;
import org.noear.solon.ai.talents.memory.MemorySearcher;
import org.noear.solon.ai.talents.memory.MemoryStorer;
import org.noear.solon.Solon;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 心智记忆管理控制器 —— 为 Web UI 的「心智记忆」面板提供 CRUD 接口。
 *
 * <p>与 {@link org.noear.solon.ai.talents.memory.MemoryTalent} 共享同一
 * {@link MemorySolutionProvider}（取自 {@link HarnessEngine#getMemoryProvider()}），
 * 保证 UI 侧的读写与 Agent 侧的记忆索引实时一致。</p>
 *
 * <p>底层方案为 {@link org.noear.solon.ai.talents.memory.md.MemorySolutionMdImpl}，
 * 内部支持双作用域合并：写入时按 scope 分域落盘，读取时自动合并（工作区覆盖用户全局同 key）。</p>
 *
 * @author noear
 * @since 4.0.0
 */
public class MemoryController {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryController.class);

    /** userId 统一引用 MemorySolutionProvider.MemorySolutionProvider.SHARED_USER_ID_ID，消除隐式字符串约定 */
    /** 列表返回上限，与 MemoryTalent.LIST_ALL_LIMIT 一致 */
    private static final int LIST_ALL_LIMIT = 100;

    private final HarnessEngine engine;

    public MemoryController(HarnessEngine engine) {
        this.engine = engine;
    }

    /**
     * 动态解析当前请求所属工作区的引擎。
     * <p>多工作区下，构造注入的 {@link #engine} 恒为默认工作区实例；
     * 必须按请求上下文取当前工作区 engine，否则「心智记忆」面板在任何工作区都只会读到默认工作区的记忆。</p>
     */
    private HarnessEngine currentEngine() {
        Context ctx = Context.current();
        WorkspaceContext wctx = null;
        if (ctx != null) {
            wctx = ctx.attr("WORKSPACE_CTX");
        }
        if (wctx == null) {
            wctx = Solon.context().getBean(WorkspaceManager.class).getOrCreate(null);
        }
        return wctx != null ? wctx.getEngine() : engine;
    }

    private MemorySolution solution() {
        HarnessEngine eng = currentEngine();
        MemorySolutionProvider provider = eng.getMemoryProvider();
        if (provider == null) {
            return null;
        }
        return provider.get(eng.getWorkspace());
    }

    private String getNow() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * 列表：返回合并后的记忆条目（已含 scope 标记），按重要度倒序、时间倒序排列。
     */
    @Mapping("/web/chat/memory/list")
    public Result<List<Map<String, Object>>> list() {
        MemorySolution ms = solution();
        if (ms == null || ms.getSearcher() == null) {
            return Result.failure("记忆存储未配置");
        }

        List<Map<String, Object>> out = new ArrayList<>();
        try {
            List<MemorySearchResult> results = ms.getSearcher().listAll(MemorySolutionProvider.SHARED_USER_ID, LIST_ALL_LIMIT);
            for (MemorySearchResult r : results) {
                Map<String, Object> m = new HashMap<>();
                m.put("key", r.getKey());
                m.put("content", r.getContent());
                m.put("importance", r.getImportance());
                m.put("time", r.getTime());
                m.put("scope", r.getScope());
                out.add(m);
            }
        } catch (Exception e) {
            LOG.warn("MemoryController list error", e);
            return Result.failure("列表查询失败：" + e.getMessage());
        }

        return Result.succeed(out);
    }

    /**
     * 详情：按 key 读取单条记忆。底层自动探测各域（工作区优先），返回合并后的值。
     */
    @Mapping("/web/chat/memory/get")
    public Result<Map<String, Object>> get(@Param("key") String key) {
        if (Assert.isEmpty(key)) {
            return Result.failure("key 不能为空");
        }

        MemorySolution ms = solution();
        if (ms == null || ms.getStorer() == null) {
            return Result.failure("记忆存储未配置");
        }

        try {
            String val = ms.getStorer().get(MemorySolutionProvider.SHARED_USER_ID, key);
            if (Assert.isEmpty(val)) {
                return Result.failure("未找到记忆条目：" + key);
            }

            ONode node = ONode.ofJson(val);
            Map<String, Object> m = new HashMap<>();
            m.put("key", key);
            m.put("content", node.get("content").getString());
            m.put("time", node.get("time").getString());
            m.put("importance", node.get("importance").getInt());
            m.put("scope", node.get("scope").getString());
            return Result.succeed(m);
        } catch (Exception e) {
            LOG.warn("MemoryController get error, key={}", key, e);
            return Result.failure("查询失败：" + e.getMessage());
        }
    }

    /**
     * 保存：新建或覆盖一条记忆。支持按 scope 写入（默认 workspace）。
     */
    @Mapping("/web/chat/memory/save")
    public Result save(@Param("key") String key,
                       @Param("content") String content,
                       @Param("importance") int importance,
                       @Param(value = "scope", required = false, defaultValue = "workspace") String scope) {
        if (Assert.isEmpty(key)) {
            return Result.failure("key 不能为空");
        }
        if (Assert.isEmpty(content)) {
            return Result.failure("content 不能为空");
        }
        if (importance < 1 || importance > 10) {
            return Result.failure("importance 需在 1-10 之间");
        }
        if (Assert.isEmpty(scope)) {
            scope = AgentFlags.SCOPE_LOCAL;
        }

        MemorySolution ms = solution();
        if (ms == null || ms.getStorer() == null) {
            return Result.failure("记忆存储未配置");
        }

        try {
            String now = getNow();

            Map<String, Object> data = new HashMap<>();
            data.put("content", content);
            data.put("time", now);
            data.put("importance", importance);
            data.put("scope", scope);

            // 动态 TTL：统一使用 ms.computeTtl 策略
            int ttl = ms.computeTtl(importance);

            MemoryStorer storer = ms.getStorer();
            storer.put(MemorySolutionProvider.SHARED_USER_ID, key, ONode.serialize(data), ttl, scope);

            // 同步搜索索引
            MemorySearcher searcher = ms.getSearcher();
            if (searcher != null) {
                searcher.updateIndex(MemorySolutionProvider.SHARED_USER_ID, key, content, importance, now, scope);
            }

            return Result.succeed();
        } catch (Exception e) {
            LOG.error("MemoryController save error, key={}, scope={}", key, scope, e);
            return Result.failure("保存失败：" + e.getMessage());
        }
    }

    /**
     * 清空：移除当前工作区可见的全部记忆（含用户全局域与工作区域中合并出的所有 key）。
     */
    @Mapping("/web/chat/memory/clear")
    public Result clear() {
        MemorySolution ms = solution();
        if (ms == null || ms.getStorer() == null) {
            return Result.failure("记忆存储未配置");
        }

        try {
            int removed = 0;
            if (ms.getSearcher() != null) {
                List<MemorySearchResult> results = ms.getSearcher().listAll(MemorySolutionProvider.SHARED_USER_ID, LIST_ALL_LIMIT);
                for (MemorySearchResult r : results) {
                    ms.getStorer().remove(MemorySolutionProvider.SHARED_USER_ID, r.getKey());
                    ms.getSearcher().removeIndex(MemorySolutionProvider.SHARED_USER_ID, r.getKey());
                    removed++;
                }
            }
            return Result.succeed(removed);
        } catch (Exception e) {
            LOG.error("MemoryController clear error", e);
            return Result.failure("清空失败：" + e.getMessage());
        }
    }

    /**
     * 删除：按 key 全域移除一条记忆（所有作用域中的同名条目都会被清除）。
     */
    @Mapping("/web/chat/memory/remove")
    public Result remove(@Param("key") String key) {
        if (Assert.isEmpty(key)) {
            return Result.failure("key 不能为空");
        }

        MemorySolution ms = solution();
        if (ms == null || ms.getStorer() == null) {
            return Result.failure("记忆存储未配置");
        }

        try {
            ms.getStorer().remove(MemorySolutionProvider.SHARED_USER_ID, key);

            MemorySearcher searcher = ms.getSearcher();
            if (searcher != null) {
                searcher.removeIndex(MemorySolutionProvider.SHARED_USER_ID, key);
            }

            return Result.succeed();
        } catch (Exception e) {
            LOG.error("MemoryController remove error, key={}", key, e);
            return Result.failure("删除失败：" + e.getMessage());
        }
    }
}

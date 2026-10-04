package org.noear.solon.codecli.workspace;

import org.noear.solon.Utils;
import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;
import org.noear.solon.ai.talents.memory.md.MemorySolutionMdImpl;
import org.noear.solon.codecli.config.AgentFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class MemoryProvider implements MemorySolutionProvider {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryProvider.class);
    private static final int MAX_CACHED = 16;

    // LRU 缓存：accessOrder=true，超出上限时自动淘汰最久未访问的 Solution 并 close 释放资源
    private Map<String, MemorySolution> cached = Collections.synchronizedMap(
            new LinkedHashMap<String, MemorySolution>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, MemorySolution> eldest) {
                    if (size() > MAX_CACHED) {
                        if (eldest.getValue() instanceof AutoCloseable) {
                            try { ((AutoCloseable) eldest.getValue()).close(); }
                            catch (Exception e) { LOG.warn("MemoryProvider close evicted solution", e); }
                        }
                        return true;
                    }
                    return false;
                }
            });


    @Override
    public MemorySolution get(String __cwd) {
        return cached.computeIfAbsent(__cwd, k ->
                new MemorySolutionMdImpl(
                        Utils.asMap(
                                AgentFlags.SCOPE_USER, Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessMemory()),
                                AgentFlags.SCOPE_LOCAL, Paths.get(k, AgentFlags.getHarnessMemory())
                        )));
    }

    public String getScopeDefault() {
        return AgentFlags.SCOPE_LOCAL;
    }

    public String getScopesDescription() {
        return "存储作用域: workspace(工作区,默认) 或 user(用户全局)。跨项目的通用认知用 user 域。";
    }
}
package org.noear.solon.codecli.workspace;

import org.noear.solon.Utils;
import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;
import org.noear.solon.ai.talents.memory.md.MemorySolutionMdImpl;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.codecli.config.AgentFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        // 记忆 MD 经挂载来源读写；本机宿主用本地目录，换成 db / http 来源即可算存分离
        return cached.computeIfAbsent(__cwd, k ->
                MemorySolutionMdImpl.of(
                        Utils.asMap(
                                AgentFlags.SCOPE_USER, localScope(Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessMemory())),
                                AgentFlags.SCOPE_LOCAL, localScope(Paths.get(k, AgentFlags.getHarnessMemory()))
                        )));
    }

    /** 本地作用域目录（不存在时创建，与旧的 Path 构造器行为一致）。 */
    private static FileMountSource localScope(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create memory storage directory: " + dir, e);
        }
        return FileMountSource.of(dir);
    }

    public String getScopeDefault() {
        return AgentFlags.SCOPE_LOCAL;
    }
}
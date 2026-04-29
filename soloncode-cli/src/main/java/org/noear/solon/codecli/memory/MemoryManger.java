package org.noear.solon.codecli.memory;

import org.noear.solon.ai.harness.HarnessProperties;
import org.noear.solon.ai.skills.memory.MemorySolution;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryManger implements MemorySolution.Factory {
    private Map<String, MemorySolution> cached = new ConcurrentHashMap<>();
    private HarnessProperties properties;

    public MemoryManger(HarnessProperties properties) {
        this.properties = properties;
    }

    @Override
    public MemorySolution get(String __cwd) {
        return cached.computeIfAbsent(__cwd, k -> new MemorySolutionImpl(k, properties));
    }
}

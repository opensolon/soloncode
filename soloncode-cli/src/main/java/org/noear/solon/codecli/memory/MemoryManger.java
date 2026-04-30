package org.noear.solon.codecli.memory;

import org.noear.solon.ai.skills.memory.MemorySolution;
import org.noear.solon.codecli.core.AgentProperties;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryManger implements MemorySolution.Factory {
    private Map<String, MemorySolution> cached = new ConcurrentHashMap<>();
    private AgentProperties properties;

    public MemoryManger(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public MemorySolution get(String __cwd) {
        if (properties.isMemoryIsolation()) {
            __cwd = properties.getUserHome();
        }

        return cached.computeIfAbsent(__cwd, k -> new MemorySolutionImpl(k, properties));
    }
}
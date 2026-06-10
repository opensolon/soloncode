package org.noear.solon.codecli.memory;

import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.memory.md.MemorySolutionMdImpl;
import org.noear.solon.codecli.config.AgentProperties;

import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryFactory implements MemorySolution.Factory {
    private Map<String, MemorySolution> cached = new ConcurrentHashMap<>();
    private AgentProperties properties;

    public MemoryFactory(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public MemorySolution get(String __cwd) {
        if (properties.isMemoryIsolation() == false) { //
            __cwd = properties.getUserHome();
        }


        return cached.computeIfAbsent(__cwd, k ->
                new MemorySolutionMdImpl(Paths.get(k, properties.getHarnessMemory())));
    }
}
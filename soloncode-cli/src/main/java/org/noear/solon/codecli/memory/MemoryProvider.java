package org.noear.solon.codecli.memory;

import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;
import org.noear.solon.ai.talents.memory.md.MemorySolutionMdImpl;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;

import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryProvider implements MemorySolutionProvider {
    private Map<String, MemorySolution> cached = new ConcurrentHashMap<>();
    private AgentSettings agentSettings;

    public MemoryProvider(AgentSettings agentSettings) {
        this.agentSettings = agentSettings;
    }

    @Override
    public MemorySolution get(String __cwd) {
        if (agentSettings.getGeneral().getMemoryIsolation() == false) { //
            __cwd = AgentFlags.getUserHome();
        }


        return cached.computeIfAbsent(__cwd, k ->
                new MemorySolutionMdImpl(Paths.get(k, AgentFlags.getHarnessMemory())));
    }
}
/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package agentTems;

import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.bot.core.memory.*;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MemoryStore 使用示例
 *
 * 演示如何使用不同的存储后端
 *
 * @author bai
 * @since 3.9.5
 */
public class MemoryStoreExample {

    /**
     * 示例 1：使用默认文件存储
     */
    public static void example1_FileStorage() throws Exception {
        System.out.println("=== 示例 1：文件存储 ===");

        // 直接创建（最简单）
        MemoryStore store = new MemoryStore("work");

        // 或者使用工厂方法
        // MemoryStore store = MemoryStore.create("file", Map.of("workDir", "./work"));

        // 使用方式完全相同
        ShortTermMemory memory = new ShortTermMemory("agent1", "测试内容", "task-1");
        store.store(memory);

        // 等待异步存储完成
        Thread.sleep(100);

        Memory loaded = store.load(memory.getId(), Memory.MemoryType.SHORT_TERM);
        System.out.println("加载记忆: " + (loaded != null ? "成功" : "失败"));

        // 获取统计信息
        Map<String, Integer> stats = store.getStats();
        System.out.println("统计信息: " + stats);
    }

    /**
     * 示例 2：使用内存存储（测试场景）
     */
    public static void example2_InMemoryStorage() {
        System.out.println("\n=== 示例 2：内存存储 ===");

        // 使用工厂方法创建内存存储
        Map<String, Object> config = new HashMap<>();
        config.put("maxSize", 1000);  // 最大存储 1000 条记忆

        MemoryStore store = MemoryStore.create("memory", config);

        // 或者直接创建
        // MemoryStore store = new MemoryStore(new InMemoryStore(1000));

        // 使用方式与文件存储完全相同
        ShortTermMemory memory = new ShortTermMemory("agent2", "内存存储测试", "task-2");
        store.store(memory);

        Memory loaded = store.load(memory.getId(), Memory.MemoryType.SHORT_TERM);
        System.out.println("加载记忆: " + (loaded != null ? "成功" : "失败"));

        // 批量加载
        List<Memory> all = store.loadAll(Memory.MemoryType.SHORT_TERM);
        System.out.println("总记忆数: " + all.size());
    }

    /**
     * 示例 3：使用自定义存储实现
     */
    public static void example3_CustomStorage() {
        System.out.println("\n=== 示例 3：自定义存储 ===");

        // 创建自定义存储实现
        MemoryStorage customStorage = new MemoryStorage() {
            private final Map<String, Memory> cache = new HashMap<>();

            @Override
            public void store(Memory memory) {
                if (memory != null && memory.getId() != null) {
                    cache.put(memory.getId(), memory);
                    System.out.println("自定义存储: " + memory.getId());
                }
            }

            @Override
            public Memory load(String memoryId, Memory.MemoryType type) {
                return cache.get(memoryId);
            }

            @Override
            public void delete(String memoryId, Memory.MemoryType type) {
                cache.remove(memoryId);
            }

            @Override
            public List<Memory> loadAll(Memory.MemoryType type) {
                return cache.values().stream()
                    .filter(m -> m.getType() == type)
                    .collect(java.util.stream.Collectors.toList());
            }

            @Override
            public void clear() {
                cache.clear();
            }

            @Override
            public Map<String, Integer> getStats() {
                Map<String, Integer> stats = new HashMap<>();
                stats.put("total", cache.size());
                return stats;
            }
        };

        // 使用自定义存储
        MemoryStore store = new MemoryStore(customStorage);

        // 使用方式完全相同
        ShortTermMemory memory = new ShortTermMemory("agent3", "自定义存储", "task-3");
        store.store(memory);

        Memory loaded = store.load(memory.getId(), Memory.MemoryType.SHORT_TERM);
        System.out.println("加载记忆: " + (loaded != null ? "成功" : "失败"));
    }

    /**
     * 示例 4：与 SharedMemoryManager 配合使用
     */
    public static void example4_WithSharedMemoryManager() throws Exception {
        System.out.println("\n=== 示例 4：与 SharedMemoryManager 配合 ===");

        // 创建文件存储
        MemoryStore fileStore = new MemoryStore("./work/file");

        // 创建内存存储（用于测试）
        Map<String, Object> memoryConfig = new HashMap<>();
        memoryConfig.put("maxSize", 100);
        MemoryStore memoryStore = MemoryStore.create("memory", memoryConfig);

        // 使用不同的存储后端创建 SharedMemoryManager
        SharedMemoryManager fileManager = new SharedMemoryManager(
                Paths.get("./work", AgentKernel.CLAUDE_SKILLS),
            3600_000L,
            7 * 24 * 3600_000L,
            300_000L,
            true,
            1000,
            500
        );

        // 注意：SharedMemoryManager 内部使用自己的 FileMemoryStore
        // 未来可以扩展为接受外部 MemoryStore

        ShortTermMemory memory = fileManager.createShortTermMemory("agent1", "测试", "task-1");
        fileManager.store(memory);

        // 等待异步存储完成
        Thread.sleep(100);

        List<Memory> memories = fileManager.retrieve(Memory.MemoryType.SHORT_TERM, 10);
        System.out.println("检索到 " + memories.size() + " 条记忆");

        fileManager.shutdown();
    }

    /**
     * 示例 5：切换存储后端
     */
    public static void example5_SwitchStorage() {
        System.out.println("\n=== 示例 5：切换存储后端 ===");

        // 定义配置
        Map<String, Object> fileConfig = new HashMap<>();
        fileConfig.put("workDir", "./work");

        Map<String, Object> memoryConfig = new HashMap<>();
        memoryConfig.put("maxSize", 100);

        // 根据环境变量选择存储类型
        String storageType = System.getenv().getOrDefault("STORAGE_TYPE", "file");

        MemoryStore store;
        if ("memory".equals(storageType)) {
            store = MemoryStore.create("memory", memoryConfig);
            System.out.println("使用内存存储");
        } else {
            store = MemoryStore.create("file", fileConfig);
            System.out.println("使用文件存储");
        }

        // 使用方式完全相同
        ShortTermMemory memory = new ShortTermMemory("agent1", "切换测试", "task-1");
        store.store(memory);
    }

    /**
     * 主函数：运行所有示例
     */
    public static void main(String[] args) throws Exception {
        example1_FileStorage();
        example2_InMemoryStorage();
        example3_CustomStorage();
        example4_WithSharedMemoryManager();
        example5_SwitchStorage();

        System.out.println("\n=== 所有示例运行完成 ===");
    }
}

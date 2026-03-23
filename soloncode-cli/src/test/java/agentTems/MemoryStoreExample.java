///*
// * Copyright 2017-2026 noear.org and authors
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// * https://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//package agentTems;
//
//import org.noear.solon.codecli.core.teams.memory.bank.Observation;
//import org.noear.solon.codecli.core.teams.memory.bank.store.FileMemoryStore;
//import org.noear.solon.codecli.core.teams.memory.bank.store.MemoryStore;
//
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.util.Arrays;
//import java.util.List;
//import java.util.UUID;
//
///**
// * MemoryStore 使用示例
// *
// * 演示如何使用不同的记忆存储方式
// *
// * @author bai
// * @since 3.9.5
// */
//public class MemoryStoreExample {
//
//    /**
//     * 示例 1：直接使用 FileMemoryStore（底层存储）
//     */
//    public static void example1_FileMemoryStore() throws Exception {
//        System.out.println("=== 示例 1：直接使用 FileMemoryStore ===");
//
//        // 创建临时目录
//        Path tempDir = Files.createTempDirectory("memory_example_");
//        String storePath = tempDir.toAbsolutePath().toString();
//
//        // 创建 FileMemoryStore
//        MemoryStore store = new FileMemoryStore(storePath);
//
//        // 创建 Observation（底层存储单位）
//        Observation obs = new Observation();
//        obs.setId("obs-" + UUID.randomUUID());
//        obs.setContent("测试 Observation 内容");
//        obs.setImportance(8.0);
//        obs.setTimestamp(System.currentTimeMillis());
//
//        // 存储
//        store.store(obs);
//        System.out.println("Observation 已存储: " + obs.getId());
//
//        // 加载
//        Observation loaded = store.load(obs.getId());
//        System.out.println("Observation 加载: " + (loaded != null ? "成功" : "失败"));
//
//        // 获取统计信息
//        System.out.println("统计信息: " + store.getStats());
//
//        // 清理
//        store.clear();
//        System.out.println("已清理临时目录: " + tempDir);
//    }
//
//    /**
//     * 示例 2：使用 SharedMemoryManager（推荐方式）
//     */
//    public static void example2_SharedMemoryManager() throws Exception {
//        System.out.println("\n=== 示例 2：使用 SharedMemoryManager（推荐）===");
//
//        // 创建临时目录
//        Path tempDir = Files.createTempDirectory("shared_memory_");
//
//        // 创建 SharedMemoryManager（高层 API）
//        MemoryManager manager = new MemoryManager(tempDir);
//
//        // 创建短期记忆
//        ShortTermMemory shortMemory = manager.createShortTermMemory(
//            "agent1",
//            "这是一个短期记忆，会自动过期",
//            "task-1"
//        );
//        shortMemory.putMetadata("测试", "示例");
//        shortMemory.putMetadata("标签", "测试标签");
//        manager.store(shortMemory);
//        System.out.println("短期记忆已存储: " + shortMemory.getId());
//
//        // 创建长期记忆
//        LongTermMemory longMemory = manager.createLongTermMemory(
//            "这是一个长期记忆",
//            "agent1",
//            Arrays.asList("重要", "长期")
//        );
//        longMemory.setImportance(9.0);  // 设置重要性
//        longMemory.putMetadata("taskId", "task-1");
//        manager.store(longMemory);
//        System.out.println("长期记忆已存储: " + longMemory.getId());
//
//        // 创建工作记忆（结构化数据）
//        WorkingMemory working = new WorkingMemory("goal-1");
//        working.setTaskId("task-1");
//        working.setCurrentAgent("main");
//        working.setStatus("进行中");
//        working.setTaskDescription("完成记忆系统测试");
//        manager.storeWorking(working);
//        System.out.println("工作记忆已存储: " + working.getId());
//
//        // 等待异步存储完成
//        Thread.sleep(200);
//
//        // 检索记忆
//        List<Memory> shortMemories = manager.retrieve(Memory.MemoryType.SHORT_TERM, 10);
//        System.out.println("检索到短期记忆: " + shortMemories.size() + " 条");
//
//        List<Memory> longMemories = manager.retrieve(Memory.MemoryType.LONG_TERM, 10);
//        System.out.println("检索到长期记忆: " + longMemories.size() + " 条");
//
//        // 按标签检索
//        List<LongTermMemory> tagged = manager.retrieveByTag("重要", 10);
//        System.out.println("按标签[重要]检索: " + tagged.size() + " 条");
//
//        // 获取工作记忆
//        WorkingMemory loadedWorking = manager.getWorking("goal-1");
//        System.out.println("工作记忆状态: " + (loadedWorking != null ? loadedWorking.getStatus() : "未找到"));
//
//        // 关闭管理器
//        manager.shutdown();
//
//        // 清理
//        System.out.println("已清理临时目录");
//    }
//
//    /**
//     * 示例 3：记忆的生命周期管理
//     */
//    public static void example3_MemoryLifecycle() throws Exception {
//        System.out.println("\n=== 示例 3：记忆的生命周期管理 ===");
//
//        // 创建临时目录
//        Path tempDir = Files.createTempDirectory("memory_lifecycle_");
//
//        // 创建 SharedMemoryManager（设置较短的 TTL）
//        MemoryManager manager = new MemoryManager(
//            tempDir,
//            2000L,      // 短期记忆 2 秒过期
//            5000L,      // 长期记忆 5 秒过期
//            1000L,      // 每秒清理一次
//            true,       // 立即持久化
//            100,        // 最多 100 条短期记忆
//            50          // 最多 50 条长期记忆
//        );
//
//        // 创建记忆
//        ShortTermMemory memory1 = manager.createShortTermMemory(
//            "agent1",
//            "这个记忆会在 2 秒后过期",
//            "task-1"
//        );
//        manager.store(memory1);
//        System.out.println("记忆已创建: " + memory1.getId());
//
//        // 立即检索（应该能找到）
//        List<Memory> immediate = manager.retrieve(Memory.MemoryType.SHORT_TERM, 10);
//        System.out.println("立即检索: " + immediate.size() + " 条");
//
//        // 等待 3 秒（应该已经过期）
//        System.out.println("等待 3 秒...");
//        Thread.sleep(3000);
//
//        // 再次检索（应该为空）
//        List<Memory> afterExpiry = manager.retrieve(Memory.MemoryType.SHORT_TERM, 10);
//        System.out.println("过期后检索: " + afterExpiry.size() + " 条");
//
//        // 关闭管理器
//        manager.shutdown();
//        System.out.println("生命周期示例完成");
//    }
//
//    /**
//     * 示例 4：永久记忆（KnowledgeMemory）
//     */
//    public static void example4_KnowledgeMemory() throws Exception {
//        System.out.println("\n=== 示例 4：永久记忆 ===");
//
//        // 创建临时目录
//        Path tempDir = Files.createTempDirectory("knowledge_memory_");
//
//        // 创建 SharedMemoryManager
//        MemoryManager manager = new MemoryManager(tempDir);
//
//        // 创建永久记忆（不会过期）
//        KnowledgeMemory knowledge = new KnowledgeMemory(
//            "系统架构说明",
//            "这是一个重要的架构文档，需要永久保存",
//            "架构文档"
//        );
//        knowledge.putMetadata("agentId", "agent1");
//        knowledge.putMetadata("来源", "用户输入");
//        knowledge.setKeywords(Arrays.asList("架构", "文档", "重要"));
//        manager.store(knowledge);
//        System.out.println("永久记忆已创建: " + knowledge.getId());
//
//        // 等待存储完成
//        Thread.sleep(200);
//
//        // 检索永久记忆
//        List<Memory> knowledges = manager.retrieve(Memory.MemoryType.KNOWLEDGE, 10);
//        System.out.println("检索到永久记忆: " + knowledges.size() + " 条");
//
//        if (!knowledges.isEmpty()) {
//            KnowledgeMemory loaded = (KnowledgeMemory) knowledges.get(0);
//            System.out.println("内容: " + loaded.getContent());
//            System.out.println("分类: " + loaded.getCategory());
//            System.out.println("关键词: " + loaded.getKeywords());
//            System.out.println("元数据-来源: " + loaded.getMetadata("来源"));
//        }
//
//        // 关闭管理器
//        manager.shutdown();
//        System.out.println("永久记忆示例完成");
//    }
//
//    /**
//     * 示例 5：跨代理共享记忆
//     */
//    public static void example5_SharedMemory() throws Exception {
//        System.out.println("\n=== 示例 5：跨代理共享记忆 ===");
//
//        // 创建临时目录
//        Path tempDir = Files.createTempDirectory("shared_memory_agents_");
//
//        // 创建 SharedMemoryManager（多个代理共享同一个实例）
//        MemoryManager sharedManager = new MemoryManager(tempDir);
//
//        // 代理 1 存储信息
//        ShortTermMemory mem1 = sharedManager.createShortTermMemory(
//            "coder",
//            "用户要求实现用户登录功能",
//            "task-login"
//        );
//        mem1.putMetadata("标签", "需求");
//        mem1.putMetadata("类型", "登录");
//        sharedManager.store(mem1);
//        System.out.println("[代理 coder] 存储了需求");
//
//        // 代理 2 查看信息
//        List<Memory> allMemories = sharedManager.retrieve(Memory.MemoryType.SHORT_TERM, 100);
//        int coderCount = 0;
//        for (Memory m : allMemories) {
//            if (m instanceof ShortTermMemory) {
//                ShortTermMemory stm = (ShortTermMemory) m;
//                if ("coder".equals(stm.getAgentId())) {
//                    coderCount++;
//                }
//            }
//        }
//        System.out.println("[代理 tester] 发现 coder 有 " + coderCount + " 条记忆");
//
//        // 代理 2 添加测试信息
//        ShortTermMemory mem2 = sharedManager.createShortTermMemory(
//            "tester",
//            "需要测试登录功能的边界条件",
//            "task-login"
//        );
//        mem2.putMetadata("标签", "测试");
//        mem2.putMetadata("类型", "边界条件");
//        sharedManager.store(mem2);
//        System.out.println("[代理 tester] 存储了测试计划");
//
//        // 等待存储完成
//        Thread.sleep(100);
//
//        // 查看所有与该任务相关的记忆
//        List<Memory> allAfter = sharedManager.retrieve(Memory.MemoryType.SHORT_TERM, 100);
//        System.out.println("\n任务 [task-login] 的所有记忆 (" + allAfter.size() + " 条):");
//        for (Memory mem : allAfter) {
//            if (mem instanceof ShortTermMemory) {
//                ShortTermMemory stm = (ShortTermMemory) mem;
//                if ("task-login".equals(stm.getTaskId())) {
//                    System.out.println("  - [" + stm.getAgentId() + "] " + stm.getContext());
//                }
//            }
//        }
//
//        // 关闭管理器
//        sharedManager.shutdown();
//        System.out.println("跨代理共享记忆示例完成");
//    }
//
//    /**
//     * 主函数：运行所有示例
//     */
//    public static void main(String[] args) throws Exception {
//        example1_FileMemoryStore();
//        example2_SharedMemoryManager();
//        example3_MemoryLifecycle();
//        example4_KnowledgeMemory();
//        example5_SharedMemory();
//
//        System.out.println("\n=== 所有示例运行完成 ===");
//    }
//}

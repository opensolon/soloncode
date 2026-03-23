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
//package org.noear.solon.codecli.core.teams_test;
//
//import org.noear.solon.ai.chat.prompt.Prompt;
//import org.noear.solon.codecli.core.agent.AgentDefinition;
//import org.noear.solon.codecli.core.teams.event.EventBus;
//import org.noear.solon.codecli.core.teams.memory.MemoryManager;
//import org.noear.solon.codecli.core.agent.AgentMetadata;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.nio.file.Paths;
//import java.util.List;
//
///**
// * MainAgent 任务编排测试
// *
// * 测试主代理的任务创建、依赖关系管理和协调功能
// *
// * @author bai
// * @since 3.9.5
// */
//public class MainAgentTaskOrchestrationTest {
//    private static final Logger LOG = LoggerFactory.getLogger(MainAgentTaskOrchestrationTest.class);
//
//    public static void main(String[] args) {
//        System.out.println("========== MainAgent 任务编排测试 ==========\n");
//
//        // 创建必要的组件
//        EventBus eventBus = new EventBus();
//        SharedTaskList taskList = new SharedTaskList(eventBus);
//        MemoryManager memoryManager = new MemoryManager(Paths.get("./work"));
//
//        AgentDefinition agentDefinition = new AgentDefinition();
//        //config.setCode("main-agent");
//        agentDefinition.setMetadata(AgentMetadata.builder()
//                .name("主代理")
//                .description("测试主代理")
//                .build());
//
//        // 创建模拟的 SessionProvider
//
//        // 创建 MainAgent
//        MainAgent mainAgent = new MainAgent(
//                null,
//                agentDefinition,
//                memoryManager,
//                eventBus,
//                null, // MessageChannel 为 null（测试用）
//                taskList,
//                "./work"
//        );
//
//        System.out.println(mainAgent.getTeamLeadInstruction());
//        return;
//
////        // 测试 1: 探索类任务创建
////        testExplorationTasks(mainAgent);
////
////        // 测试 2: 开发类任务创建
////        testDevelopmentTasks(mainAgent);
////
////        // 测试 3: 依赖关系验证
////        testDependencyValidation(mainAgent);
////
////        // 测试 4: 循环依赖检测
////        testCyclicDependencyDetection(taskList);
////
////        // 测试 5: 依赖关系可视化
////        testDependencyVisualization(taskList);
////
////        System.out.println("\n========== 测试完成 ==========");
//    }
//
//    /**
//     * 测试探索类任务创建
//     */
//    private static void testExplorationTasks(MainAgent mainAgent) {
//        System.out.println("=== 测试 1: 探索类任务创建 ===\n");
//
//        Prompt prompt = Prompt.of("请探索这个代码库并分析结构");
//
//        // 使用反射调用私有方法 analyzeAndCreateTasks
//        try {
//            java.lang.reflect.Method method = MainAgent.class.getDeclaredMethod("analyzeAndCreateTasks", Prompt.class);
//            method.setAccessible(true);
//            @SuppressWarnings("unchecked")
//            List<TeamTask> tasks = (List<TeamTask>) method.invoke(mainAgent, prompt);
//
//            System.out.println("✅ 创建了 " + tasks.size() + " 个探索任务:");
//            for (TeamTask task : tasks) {
//                System.out.println("  - " + task.getTitle() +
//                        " (优先级: " + task.getPriority() + ")" +
//                        (task.getDependencies().isEmpty() ? "" : " 依赖: " + task.getDependencies()));
//            }
//
//            // 验证依赖关系
//            boolean hasCorrectDependencies = true;
//            for (TeamTask task : tasks) {
//                if (!task.getDependencies().isEmpty()) {
//                    String firstDep = task.getDependencies().get(0);
//                    if (!firstDep.contains("explore")) {
//                        hasCorrectDependencies = false;
//                        System.err.println("❌ 依赖关系错误: " + task.getTitle() + " -> " + firstDep);
//                    }
//                }
//            }
//
//            if (hasCorrectDependencies) {
//                System.out.println("✅ 依赖关系正确\n");
//            }
//
//        } catch (Exception e) {
//            System.err.println("❌ 测试失败: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
//
//    /**
//     * 测试开发类任务创建
//     */
//    private static void testDevelopmentTasks(MainAgent mainAgent) {
//        System.out.println("=== 测试 2: 开发类任务创建 ===\n");
//
//        Prompt prompt = Prompt.of("请实现用户认证功能");
//
//        try {
//            java.lang.reflect.Method method = MainAgent.class.getDeclaredMethod("analyzeAndCreateTasks", Prompt.class);
//            method.setAccessible(true);
//            @SuppressWarnings("unchecked")
//            List<TeamTask> tasks = (List<TeamTask>) method.invoke(mainAgent, prompt);
//
//            System.out.println("✅ 创建了 " + tasks.size() + " 个开发任务:");
//
//            // 验证任务链：计划 -> 实现 -> 测试
//            String[] expectedOrder = {"计划", "实现", "测试"};
//            int index = 0;
//
//            for (TeamTask task : tasks) {
//                System.out.println("  " + (index + 1) + ". " + task.getTitle() +
//                        " (优先级: " + task.getPriority() + ")" +
//                        (task.getDependencies().isEmpty() ? "" : " 依赖: " + task.getDependencies()));
//
//                // 验证依赖关系
//                if (index > 0 && !task.getDependencies().isEmpty()) {
//                    String depId = task.getDependencies().get(0);
//                    if (!depId.contains(getTaskKeyword(expectedOrder[index - 1]))) {
//                        System.err.println("  ❌ 依赖关系不正确，期望依赖: " + expectedOrder[index - 1]);
//                    }
//                }
//                index++;
//            }
//
//            System.out.println("✅ 开发任务链正确\n");
//
//        } catch (Exception e) {
//            System.err.println("❌ 测试失败: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
//
//    /**
//     * 测试依赖关系验证
//     */
//    private static void testDependencyValidation(MainAgent mainAgent) {
//        System.out.println("=== 测试 3: 依赖关系验证 ===\n");
//
//        Prompt prompt = Prompt.of("开发并测试新功能");
//
//        try {
//            java.lang.reflect.Method method = MainAgent.class.getDeclaredMethod("analyzeAndCreateTasks", Prompt.class);
//            method.setAccessible(true);
//            @SuppressWarnings("unchecked")
//            List<TeamTask> tasks = (List<TeamTask>) method.invoke(mainAgent, prompt);
//
//            // 添加任务到任务列表
//            List<TeamTask> added = mainAgent.getTaskList().addTasks(tasks).join();
//            System.out.println("✅ 成功添加 " + added.size() + " 个任务到共享任务列表");
//
//            // 获取可认领任务
//            List<TeamTask> claimableTasks = mainAgent.getTaskList().getClaimableTasks();
//            System.out.println("✅ 当前可认领任务数: " + claimableTasks.size());
//
//            // 第一个任务（计划）应该可认领
//            if (claimableTasks.size() >= 1) {
//                System.out.println("✅ 第一个任务可认领: " + claimableTasks.get(0).getTitle());
//            }
//
//            // 其他任务应该被阻塞
//            List<TeamTask> blockedTasks = mainAgent.getTaskList().getBlockedTasks();
//            if (blockedTasks.size() > 0) {
//                System.out.println("✅ 阻塞任务数: " + blockedTasks.size());
//                for (TeamTask task : blockedTasks) {
//                    System.out.println("  - " + task.getTitle());
//                }
//            }
//
//            System.out.println();
//
//        } catch (Exception e) {
//            System.err.println("❌ 测试失败: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
//
//    /**
//     * 测试循环依赖检测
//     */
//    private static void testCyclicDependencyDetection(SharedTaskList taskList) {
//        System.out.println("=== 测试 4: 循环依赖检测 ===\n");
//
//        // 创建循环依赖任务
//        String time = String.valueOf(System.currentTimeMillis());
//        TeamTask taskX = TeamTask.builder()
//                .id("cyclic-x-" + time)
//                .title("循环任务 X")
//                .dependencies(java.util.Collections.singletonList("cyclic-z-" + time))
//                .build();
//
//        TeamTask taskY = TeamTask.builder()
//                .id("cyclic-y-" + time)
//                .title("循环任务 Y")
//                .dependencies(java.util.Collections.singletonList("cyclic-x-" + time))
//                .build();
//
//        TeamTask taskZ = TeamTask.builder()
//                .id("cyclic-z-" + time)
//                .title("循环任务 Z")
//                .dependencies(java.util.Collections.singletonList("cyclic-y-" + time))
//                .build();
//
//        try {
//            // 添加 X 应该失败，因为依赖关系还不存在
//            taskList.addTask(taskX).join();
//            System.out.println("❌ 应该检测到依赖任务不存在");
//        } catch (Exception e) {
//            System.out.println("✅ 正确检测到依赖任务不存在: " + e.getMessage());
//        }
//
//        // 先添加 Y 和 Z，再添加 X
//        taskList.addTask(taskY).join();
//        taskList.addTask(taskZ).join();
//
//        try {
//            taskList.addTask(taskX).join();
//            System.out.println("❌ 应该检测到循环依赖");
//        } catch (Exception e) {
//            if (e.getMessage().contains("循环依赖")) {
//                System.out.println("✅ 成功检测到循环依赖: " + e.getMessage());
//            } else {
//                System.err.println("❌ 错误信息不正确: " + e.getMessage());
//            }
//        }
//
//        // 使用检测方法
//        List<TeamTask> cyclicTasks = taskList.detectCyclicDependencies();
//        System.out.println("✅ 检测到 " + cyclicTasks.size() + " 个循环依赖任务\n");
//    }
//
//    /**
//     * 测试依赖关系可视化
//     */
//    private static void testDependencyVisualization(SharedTaskList taskList) {
//        System.out.println("=== 测试 5: 依赖关系可视化 ===\n");
//
//        // 获取依赖图
//        String graph = taskList.getDependencyGraph();
//        System.out.println("任务依赖图:");
//        System.out.println(graph);
//
//        // 获取统计信息
//        SharedTaskList.TaskStatistics stats = taskList.getStatistics();
//        System.out.println("任务统计: " + stats);
//
//        System.out.println();
//    }
//
//    /**
//     * 根据任务名称获取关键字
//     */
//    private static String getTaskKeyword(String taskName) {
//        if (taskName.contains("计划")) return "plan";
//        if (taskName.contains("实现")) return "impl";
//        if (taskName.contains("测试")) return "test";
//        return "";
//    }
//}
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
package org.noear.solon.bot.core.teams;

import org.noear.solon.bot.core.event.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * 任务编排测试示例
 *
 * 演示如何使用修复后的 TeamTask 和 SharedTaskList
 *
 * @author bai
 * @since 3.9.5
 */
public class TaskOrchestrationTest {
    private static final Logger LOG = LoggerFactory.getLogger(TaskOrchestrationTest.class);

    public static void main(String[] args) {
        // 创建事件总线和任务列表
        EventBus eventBus = new EventBus();
        SharedTaskList taskList = new SharedTaskList(eventBus);

        System.out.println("========== 任务编排测试 ==========\n");

        // 1. 测试基本依赖关系
        testBasicDependency(taskList);

        // 2. 测试间接依赖
        testIndirectDependency(taskList);

        // 3. 测试循环依赖检测
        testCyclicDependencyDetection(taskList);

        // 4. 测试依赖关系可视化
        testDependencyVisualization(taskList);
    }

    /**
     * 测试基本依赖关系
     */
    private static void testBasicDependency(SharedTaskList taskList) {
        System.out.println("=== 测试 1: 基本依赖关系 ===\n");

        // 创建任务：A -> B (A 依赖 B)
        TeamTask taskB = TeamTask.builder()
                .id("task-b")
                .title("编写单元测试")
                .description("为功能模块编写单元测试")
                .priority(7)
                .build();

        TeamTask taskA = TeamTask.builder()
                .id("task-a")
                .title("实现功能")
                .description("实现核心功能模块")
                .priority(8)
                .dependencies(Arrays.asList("task-b"))
                .build();

        try {
            // 先添加 B，再添加 A
            taskList.addTask(taskB).join();
            taskList.addTask(taskA).join();

            System.out.println("✅ 任务添加成功");
            System.out.println("可认领任务数: " + taskList.getClaimableTasks().size());
            System.out.println("（只有 taskB 可认领，taskA 等待 taskB 完成）\n");

        } catch (Throwable e) {
            System.err.println("❌ 失败: " + e.getMessage());
        }
    }

    /**
     * 测试间接依赖（多级依赖）
     */
    private static void testIndirectDependency(SharedTaskList taskList) {
        System.out.println("=== 测试 2: 间接依赖检查 ===\n");

        // 创建任务：A -> B -> C -> D (A 依赖 B，B 依赖 C，C 依赖 D)
        TeamTask taskD = TeamTask.builder()
                .id("task-d")
                .title("准备开发环境")
                .priority(5)
                .build();

        TeamTask taskC = TeamTask.builder()
                .id("task-c")
                .title("设计架构")
                .dependencies(Arrays.asList("task-d"))
                .priority(6)
                .build();

        TeamTask taskB2 = TeamTask.builder()
                .id("task-b2")
                .title("编写代码")
                .dependencies(Arrays.asList("task-c"))
                .priority(7)
                .build();

        TeamTask taskA2 = TeamTask.builder()
                .id("task-a2")
                .title("部署上线")
                .dependencies(Arrays.asList("task-b2"))
                .priority(8)
                .build();

        try {
            taskList.addTask(taskD).join();
            taskList.addTask(taskC).join();
            taskList.addTask(taskB2).join();
            taskList.addTask(taskA2).join();

            System.out.println("✅ 多级依赖任务添加成功");
            System.out.println("可认领任务数: " + taskList.getClaimableTasks().size());
            System.out.println("（只有 taskD 可认领，其他任务等待依赖链完成）\n");

            // 获取阻塞信息
            String blockingInfo = taskList.getBlockingInfo();
            if (blockingInfo.length() > 50) {
                System.out.println("阻塞任务详情:");
                System.out.println(blockingInfo);
            }

        } catch (Throwable e) {
            System.err.println("❌ 失败: " + e.getMessage());
        }
    }

    /**
     * 测试循环依赖检测
     */
    private static void testCyclicDependencyDetection(SharedTaskList taskList) {
        System.out.println("=== 测试 3: 循环依赖检测 ===\n");

        // 创建循环依赖：X -> Y -> Z -> X
        TeamTask taskX = TeamTask.builder()
                .id("task-x")
                .title("任务 X")
                .dependencies(Arrays.asList("task-z"))
                .build();

        TeamTask taskY = TeamTask.builder()
                .id("task-y")
                .title("任务 Y")
                .dependencies(Arrays.asList("task-x"))
                .build();

        TeamTask taskZ = TeamTask.builder()
                .id("task-z")
                .title("任务 Z")
                .dependencies(Arrays.asList("task-y"))
                .build();

        try {
            // 尝试添加循环依赖任务
            taskList.addTask(taskX).join();
            System.out.println("❌ 应该检测到循环依赖，但没有！");
        } catch (IllegalArgumentException e) {
            System.out.println("✅ 成功检测到循环依赖: " + e.getMessage());
        }

        // 测试检测方法
        System.out.println("\n检测所有任务的循环依赖:");
        List<TeamTask> cyclicTasks = taskList.detectCyclicDependencies();
        System.out.println("发现 " + cyclicTasks.size() + " 个循环依赖任务\n");
    }

    /**
     * 测试依赖关系可视化
     */
    private static void testDependencyVisualization(SharedTaskList taskList) {
        System.out.println("=== 测试 4: 依赖关系可视化 ===\n");

        // 显示完整依赖图
        System.out.println(taskList.getDependencyGraph());

        // 显示单个任务的依赖树
        System.out.println("单个任务依赖树:");
        String tree = taskList.getTaskDependencyTree("task-a2");
        System.out.println(tree);

        // 显示统计信息
        System.out.println("任务统计:");
        System.out.println(taskList.getStatistics());
    }
}

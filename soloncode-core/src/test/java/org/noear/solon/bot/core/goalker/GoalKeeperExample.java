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
package org.noear.solon.bot.core.goalker;

/**
 * GoalKeeper 使用示例
 *
 * 演示如何使用目标守护者防止 AI 偏离目标
 *
 * @author bai
 * @since 3.9.5
 */
public class GoalKeeperExample {

    public static void main(String[] args) {
        System.out.println("=== 目标守护者示例 ===\n");

        // 示例 1: 基本使用
        example1_BasicUsage();

        // 示例 2: 目标提醒
        example2_GoalReminder();

        // 示例 3: 强制结束检查
        example3_ForceFinishCheck();

        System.out.println("\n=== 所有示例完成 ===");
    }

    /**
     * 示例 1: 基本使用
     */
    private static void example1_BasicUsage() {
        System.out.println("示例 1: 基本使用\n");

        // 创建目标守护者
        GoalKeeper goalKeeper = new GoalKeeper(
            "goal-001",
            "实现用户登录功能"
        );

        System.out.println("目标ID: " + goalKeeper.getGoalId());
        System.out.println("原始目标: " + goalKeeper.getOriginalGoal());
        System.out.println("开始时间: " + goalKeeper.getStartTime());
        System.out.println();
    }

    /**
     * 示例 2: 目标提醒
     */
    private static void example2_GoalReminder() {
        System.out.println("示例 2: 目标提醒\n");

        GoalKeeper goalKeeper = new GoalKeeper(
            "goal-002",
            "优化数据库查询性能"
        );

        // 测试不同步数的提醒
        int[] testSteps = {1, 5, 10, 11, 15, 20, 25};

        for (int step : testSteps) {
            String reminder = goalKeeper.injectReminder(step);
            if (reminder != null) {
                System.out.println("第 " + step + " 步提醒:");
                System.out.println(reminder);
            } else {
                System.out.println("第 " + step + " 步: 无需提醒");
            }
            System.out.println();
        }
    }

    /**
     * 示例 3: 强制结束检查
     */
    private static void example3_ForceFinishCheck() {
        System.out.println("示例 3: 强制结束检查\n");

        GoalKeeper goalKeeper = new GoalKeeper(
            "goal-003",
            "实现JWT认证"
        );

        int maxSteps = 30;

        // 测试不同步数是否应该强制结束
        for (int step = 28; step <= 32; step++) {
            boolean shouldFinish = goalKeeper.shouldForceFinish(step, maxSteps);
            String status = shouldFinish ? "⚠️ 应该结束" : "✅ 可以继续";
            System.out.println("第 " + step + " 步: " + status);
        }
        System.out.println();
    }
}

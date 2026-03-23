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
///**
// * 团队名生成器测试
// *
// * 演示如何使用智能团队命名系统
// *
// * @author bai
// * @since 3.9.5
// */
//public class TeamNameGeneratorTest {
//
//    public static void main(String[] args) {
//        System.out.println("=== 团队名生成器测试 ===\n");
//
//        // 测试 1: 安全专家
//        testGenerate(
//            "security-expert",
//            "负责系统安全审计和漏洞检测",
//            "实现JWT认证系统"
//        );
//
//        // 测试 2: 数据库管理员
//        testGenerate(
//            "database-admin",
//            "数据库优化和索引设计",
//            "优化查询性能"
//        );
//
//        // 测试 3: 前端开发
//        testGenerate(
//            "frontend-dev",
//            "UI组件开发",
//            "实现响应式布局"
//        );
//
//        // 测试 4: 后端开发
//        testGenerate(
//            "backend-dev",
//            "API接口开发",
//            "实现RESTful服务"
//        );
//
//        // 测试 5: AI工程师
//        testGenerate(
//            "ai-engineer",
//            "机器学习模型集成",
//            "集成深度学习框架"
//        );
//
//        // 测试 6: 运维工程师
//        testGenerate(
//            "devops-engineer",
//            "自动化部署和容器化",
//            "搭建K8s集群"
//        );
//
//        System.out.println("\n=== 团队名验证测试 ===\n");
//
//        // 测试验证功能
//        testValidation("security-squad");      // ✅ 有效
//        testValidation("team-1736640123456");  // ✅ 有效（虽然不推荐）
//        testValidation("SecuritySquad");       // ❌ 无效（大写）
//        testValidation("security squad");      // ❌ 无效（空格）
//
//        System.out.println("\n=== 团队名规范化测试 ===\n");
//
//        // 测试规范化功能
//        testNormalization("SecuritySquad", "securitysquad");
//        testNormalization("security squad", "security-squad");
//        testNormalization("security_team", "security-team");
//        testNormalization("My.TEAM.Name", "my-team-name");
//
//        System.out.println("\n=== 领域提取测试 ===\n");
//
//        // 测试领域提取
//        testExtractDomain("security-squad", "security");
//        testExtractDomain("database-team", "database");
//        testExtractDomain("frontend-experts", "frontend");
//        testExtractDomain("unknown-team", null);
//
//        System.out.println("\n=== 所有测试完成 ===");
//    }
//
//    private static void testGenerate(String role, String description, String taskGoal) {
//        System.out.println("输入:");
//        System.out.println("  角色: " + role);
//        System.out.println("  描述: " + description);
//        System.out.println("  目标: " + taskGoal);
//
//        String teamName = TeamNameGenerator.generateTeamName(role, description, taskGoal);
//
//        System.out.println("生成: " + teamName);
//
//        String desc = TeamNameGenerator.getTeamDescription(teamName);
//        System.out.println("描述: " + desc);
//        System.out.println();
//    }
//
//    private static void testValidation(String teamName) {
//        boolean isValid = TeamNameGenerator.isValidTeamName(teamName);
//        System.out.println("验证: " + teamName + " -> " + (isValid ? "✅ 有效" : "❌ 无效"));
//    }
//
//    private static void testNormalization(String input, String expected) {
//        String normalized = TeamNameGenerator.normalizeTeamName(input);
//        String status = normalized.equals(expected) ? "✅" : "❌";
//        System.out.println(String.format("规范化: \"%s\" -> \"%s\" (期望: \"%s\") %s",
//            input, normalized, expected, status));
//    }
//
//    private static void testExtractDomain(String teamName, String expected) {
//        String domain = TeamNameGenerator.extractDomainFromTeamName(teamName);
//        String status = (expected == null && domain == null) || expected.equals(domain) ? "✅" : "❌";
//        System.out.println(String.format("提取领域: \"%s\" -> \"%s\" (期望: \"%s\") %s",
//            teamName, domain, expected, status));
//    }
//}

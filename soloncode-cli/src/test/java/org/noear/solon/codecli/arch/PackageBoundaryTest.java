/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 架构边界护栏。
 *
 * <p>锁定本次重构的核心决策，防止耦合回流：
 * <ol>
 *   <li><b>goal 不依赖 loop</b>：{@code codecli.goal} 包下的源码不得 import
 *       {@code codecli.loop}。</li>
 *   <li><b>goal 只依赖 AgentSession 抽象</b>：{@code GoalEngine} 不得依赖
 *       {@code FileAgentSession} 等具体存储实现，以保证内存/数据库会话同构可用。</li>
 * </ol>
 *
 * <p>采用纯文本断言，不引入 ArchUnit 等额外依赖。</p>
 */
class PackageBoundaryTest {

    private static final Path SRC = Paths.get("src/main/java/org/noear/solon/codecli");

    @Test
    void goalPackageMustNotImportLoopPackage() throws IOException {
        List<Path> goalFiles = javaFiles(SRC.resolve("goal"));
        assumeTrue(!goalFiles.isEmpty(), "goal 包源码不存在");

        List<String> violations = new ArrayList<>();
        for (Path file : goalFiles) {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (content.contains("import org.noear.solon.codecli.loop.")) {
                violations.add(file.getFileName().toString() + " 引入了 loop 包");
            }
        }
        assertTrue(violations.isEmpty(),
                "goal 必须是独立能力，不得依赖 loop 定时任务体系：" + violations);
    }

    @Test
    void goalEngineMustNotCoupleToConcreteSessionStorage() throws IOException {
        Path engine = SRC.resolve("goal/GoalEngine.java");
        assumeTrue(Files.exists(engine), "GoalEngine 源码不存在");

        String content = new String(Files.readAllBytes(engine), StandardCharsets.UTF_8);
        // 只允许依赖 AgentSession 抽象，禁止绑定具体实现（文件/内存）
        assertTrue(!content.contains("FileAgentSession"),
                "GoalEngine 不得依赖 FileAgentSession（应只依赖 AgentSession 接口）");
        assertTrue(!content.contains("InMemoryAgentSession"),
                "GoalEngine 不得依赖 InMemoryAgentSession（应只依赖 AgentSession 接口）");
        assertTrue(!content.contains("Files.write"),
                "GoalEngine 不得自行写盘（落盘由会话实现负责）");
    }

    @Test
    void goalCommandMustNotDelegateToLoopCommand() throws IOException {
        Path cmd = SRC.resolve("command/builtin/GoalCommand.java");
        assumeTrue(Files.exists(cmd), "GoalCommand 源码不存在");

        String content = new String(Files.readAllBytes(cmd), StandardCharsets.UTF_8);
        assertTrue(!content.contains("LoopCommand"),
                "/goal 必须是独立命令，不得委托 LoopCommand");
        assertTrue(!content.contains("codecli.loop."),
                "/goal 不得依赖 loop 包");
    }

    @Test
    void goalStateMustNotUseGlobalStaticConfiguration() throws IOException {
        Path gs = SRC.resolve("goal/GoalState.java");
        assumeTrue(Files.exists(gs), "GoalState 源码不存在");

        String content = new String(Files.readAllBytes(gs), StandardCharsets.UTF_8);
        // 曾经的缺陷：static 预算阈值被多工作区互相覆盖
        assertTrue(!content.contains("static volatile int budgetWarningPercent"),
                "预算阈值必须是实例级，不得为全局静态（多工作区会互相覆盖）");
        assertTrue(!content.contains("public static void configure"),
                "不得再提供全局静态 configure 入口");
    }

    @Test
    void goalPackageMustNotHoldSharedStaticState() throws IOException {
        List<Path> goalFiles = javaFiles(SRC.resolve("goal"));
        assumeTrue(!goalFiles.isEmpty(), "goal 包源码不存在");

        List<String> violations = new ArrayList<>();
        for (Path file : goalFiles) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                    continue;
                }
                // 曾经的缺陷：验证器注册表是全局静态，多工作区互相污染
                if (trimmed.contains("static") && trimmed.contains("List<") && !trimmed.contains("final long")) {
                    violations.add(file.getFileName() + ": " + trimmed);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "goal 不得持有全局静态注册表（每工作区须实例化）：" + violations);
    }

    @Test
    void goalPackageMustNotReferenceLoopScheduler() throws IOException {
        List<Path> goalFiles = javaFiles(SRC.resolve("goal"));
        assumeTrue(!goalFiles.isEmpty(), "goal 包源码不存在");

        List<String> violations = new ArrayList<>();
        for (Path file : goalFiles) {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (content.contains("LoopScheduler")) {
                violations.add(file.getFileName().toString() + " 引用了 LoopScheduler");
            }
        }
        assertTrue(violations.isEmpty(),
                "goal 不得持有 loop 的调度器（执行桥应来自组合根注入）：" + violations);
    }

    /**
     * loop 包必须与 goal 彻底分离：除注释外不得出现 Goal 标识符。
     *
     * <p>Goal 已是独立的会话级能力（{@code codecli.goal}），loop 只是定时任务。
     * 二者唯一的交汇点是 {@code runtime.ExecutionBridge}（执行通道）。
     * 允许的残留仅限：说明性注释中提及 Goal、以及拒绝 {@code type=GOAL}
     * 的入参校验（防止旧客户端把 goal 塞进定时任务表）。</p>
     */
    @Test
    void loopPackageMustNotContainGoalIdentifiers() throws IOException {
        List<Path> loopFiles = javaFiles(SRC.resolve("loop"));
        assumeTrue(!loopFiles.isEmpty(), "loop 包源码不存在");

        List<String> violations = new ArrayList<>();
        for (Path file : loopFiles) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                // 跳过注释行与 import 行
                if (trimmed.startsWith("*") || trimmed.startsWith("//")
                        || trimmed.startsWith("/*") || trimmed.startsWith("import")) {
                    continue;
                }
                if (!trimmed.contains("Goal") && !trimmed.contains("GOAL")) {
                    continue;
                }
                // 唯一豁免：拒绝 GOAL 类型入参（旧客户端兼容提示）
                if (trimmed.contains("GOAL") && trimmed.contains("\"GOAL\"")) {
                    continue;
                }
                if (trimmed.toLowerCase().contains("rejectgoaltype")) {
                    continue;
                }
                // 拒绝 GOAL 入参的异常文案（保证旧客户端得到明确提示）
                if (trimmed.contains("session-scoped capability")) {
                    continue;
                }
                violations.add(file.getFileName() + ": " + trimmed);
            }
        }
        assertTrue(violations.isEmpty(),
                "loop 包不得再持有 goal 实现（应只剩代码外的注释）：" + violations);
    }

    /**
     * loop 包不得 import goal 包任何类型。
     */
    @Test
    void loopPackageMustNotImportGoalPackage() throws IOException {
        List<Path> loopFiles = javaFiles(SRC.resolve("loop"));
        assumeTrue(!loopFiles.isEmpty(), "loop 包源码不存在");

        List<String> violations = new ArrayList<>();
        for (Path file : loopFiles) {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (content.contains("import org.noear.solon.codecli.goal.")) {
                violations.add(file.getFileName().toString() + " 引入了 goal 包");
            }
        }
        assertTrue(violations.isEmpty(),
                "loop 必须是纯定时任务体系，不得依赖 goal：" + violations);
    }

    /** 统计文件中 goal 相关标识出现次数（忽略 import 行的大小写差异）。 */
    private static int couplingCount(String relative) throws IOException {
        Path file = SRC.resolve(relative);
        if (!Files.exists(file)) {
            return 0;
        }
        int count = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String lower = line.toLowerCase();
            if (lower.contains("goal") && !line.trim().startsWith("import")) {
                count++;
            }
        }
        return count;
    }

    /**
     * Web 合并列表契约：goal 恒为第一条，其后才是定时任务。
     *
     * <p>前端 app-loop.js 不做任何排序（列表顺序即后端顺序），因此
     * 「goal 显示在最前」完全由后端保证，此处锁定该实现不被改动。</p>
     */
    @Test
    void webListMustPlaceGoalFirst() throws IOException {
        Path ctrl = SRC.resolve("api/web/controller/LoopWebController.java");
        assumeTrue(Files.exists(ctrl), "LoopWebController 源码不存在");

        String content = new String(Files.readAllBytes(ctrl), StandardCharsets.UTF_8);
        int goalIdx = content.indexOf("buildGoalMap(sessionId)");
        int loopIdx = content.indexOf("loopScheduler().listAll(sessionId)");
        assertTrue(goalIdx > 0, "合并列表应包含 goal 条目");
        assertTrue(loopIdx > 0, "合并列表应包含定时任务条目");
        assertTrue(goalIdx < loopIdx,
                "goal 必须先于定时任务被加入列表（前端不做排序，顺序即后端顺序）");
    }

    private static List<Path> javaFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(result::add);
        }
        return result;
    }
}

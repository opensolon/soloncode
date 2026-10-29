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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 宿主侧架构边界护栏。
 *
 * <p>goal 域已整体下沉 harness（{@code org.noear.solon.ai.harness.session.goal}），
 * 对应的域内护栏随之迁移到 harness 的 {@code GoalBoundaryTest}。此处只保留
 * <b>宿主视角</b>的约束：
 * <ol>
 *   <li>loop 包不得反向依赖 goal（loop 是纯定时任务体系）。</li>
 *   <li>{@code codecli.goal} 包只允许残留历史数据迁移器，不得让领域实现回流宿主。</li>
 * </ol>
 */
class PackageBoundaryTest {

    private static final Path SRC = Paths.get("src/main/java/org/noear/solon/codecli");

    /**
     * loop 包必须与 goal 彻底分离：除注释外不得出现 Goal 标识符。
     *
     * <p>Goal 已是独立的会话级能力（已下沉 harness），loop 只是定时任务。
     * 二者唯一的交汇点是 {@code harness.runtime.ExecutionBridge}（执行通道）。
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
                "loop 包不得持有 goal 实现（应只剩代码外的注释）：" + violations);
    }

    /**
     * loop 包不得 import goal 包任何类型（无论旧包还是 harness 新包）。
     */
    @Test
    void loopPackageMustNotImportGoalPackage() throws IOException {
        List<Path> loopFiles = javaFiles(SRC.resolve("loop"));
        assumeTrue(!loopFiles.isEmpty(), "loop 包源码不存在");

        List<String> violations = new ArrayList<>();
        for (Path file : loopFiles) {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (content.contains("import org.noear.solon.codecli.goal.")) {
                violations.add(file.getFileName().toString() + " 引入了旧 goal 包");
            }
            if (content.contains("import org.noear.solon.ai.harness.session.goal.")) {
                violations.add(file.getFileName().toString() + " 引入了 goal 域");
            }
        }
        assertTrue(violations.isEmpty(),
                "loop 必须是纯定时任务体系，不得依赖 goal：" + violations);
    }

    /**
     * 宿主侧 {@code codecli.goal} 包不得让领域实现回流。
     *
     * <p>goal 域下沉 harness 后，宿主只应保留「一次性历史数据迁移」这一
     * 存储布局相关的私有知识（{@code GoalDataMigrator}）。若包内出现其它类，
     * 说明有人在宿主重新实现了 goal 逻辑。</p>
     */
    @Test
    void hostGoalPackageMayOnlyHoldLegacyMigrator() throws IOException {
        List<Path> files = javaFiles(SRC.resolve("goal"));
        if (files.isEmpty()) {
            return; // 迁移器被彻底删除也是合法终态
        }

        List<String> names = new ArrayList<>();
        for (Path file : files) {
            names.add(file.getFileName().toString());
        }
        assertEquals(1, names.size(),
                "codecli.goal 只允许保留 GoalDataMigrator（goal 域应全部在 harness），实际：" + names);
        assertEquals("GoalDataMigrator.java", names.get(0),
                "codecli.goal 只允许保留 GoalDataMigrator（goal 域应全部在 harness），实际：" + names);
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

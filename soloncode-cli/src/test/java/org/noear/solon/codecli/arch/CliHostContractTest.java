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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLI 终端走 L0（{@code HarnessHost}）：受理、开流、续发积压、停流、审批续流、goal / loop 后台轮次都由骨架代办。
 *
 * <p>迁移前 CLI 自己写了一遍编排（结局分派、逐张审批循环、队列排水、手工累加 token 与工具证据交给 goal），
 * 其中「命令识别与回合后排水必须同源」这类不变量只能靠注释维持。这里锁定它不再回到 L1 写法。</p>
 */
class CliHostContractTest {
    private static final Path CLI = Paths.get("src/main/java/org/noear/solon/codecli/entry/cli");

    @Test
    void cliStaysOnHostSkeleton() throws IOException {
        List<String> forbidden = Arrays.asList(
                "engine.handle(",          // 受理走 host.submit / host.decide
                "claimNext(",              // 积压续发由骨架在轮次结束后完成
                "drainLoop(",
                "addExecutor(",            // goal / loop 执行器由骨架登记（programRuns）
                "GoalOutcome.done(",       // 工具证据与 token 由骨架归类
                "InterruptMode.",          // 停止走 host.interrupt
                "InputOutcome");

        List<String> violations = new ArrayList<>();
        for (String name : new String[]{"CliShell.java", "CliHostSink.java"}) {
            for (String line : Files.readAllLines(CLI.resolve(name), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                    continue;
                }
                for (String bad : forbidden) {
                    if (trimmed.contains(bad)) {
                        violations.add(name + ": " + trimmed);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "CLI 不得回到 L1 自己编排：" + violations);

        String shell = new String(Files.readAllBytes(CLI.resolve("CliShell.java")), StandardCharsets.UTF_8);
        assertTrue(shell.contains("host().submit(") && shell.contains("host().decide(") && shell.contains("host().interrupt("),
                "CLI 的提交、审批、停止都应经 HarnessHost");
    }
}

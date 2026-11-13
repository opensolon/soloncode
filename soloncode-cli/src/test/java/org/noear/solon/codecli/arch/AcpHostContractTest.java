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
 * ACP 入口走 L0（{@code HarnessHost}）：受理、开流、终态、停流都由骨架代办。
 *
 * <p>迁移前 ACP 是唯一直连 {@code engine.handle} 的入口，自己用 {@code takeWhile(!cancelled)} 停流、
 * 自己按位置撤回积压条目、取消只写一个布尔标记——没有领取运行凭据、没有取消屏障、不落取消消息，
 * 与 CLI / Web / 桌面端的「停止就是停止」语义不同。这里锁定它不再回到那种写法。</p>
 */
class AcpHostContractTest {
    private static final Path ACP = Paths.get("src/main/java/org/noear/solon/codecli/entry/acp/AcpLink.java");

    @Test
    void acpStaysOnHostSkeleton() throws IOException {
        List<String> forbidden = Arrays.asList(
                "agentRuntime.handle(",   // 受理走 host.stream / host.submit
                "takeWhile(",             // 停止由骨架的取消屏障完成
                "QueueExtension",         // 一问一答不排队，也就没有「撤回积压」
                "InputOutcome",           // 结局分派在骨架里
                "setCancelled(");         // 取消走 admin.interrupt

        List<String> violations = new ArrayList<>();
        for (String line : Files.readAllLines(ACP, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            for (String bad : forbidden) {
                if (trimmed.contains(bad)) {
                    violations.add(trimmed);
                }
            }
        }
        assertTrue(violations.isEmpty(), "ACP 不得自己编排一轮：" + violations);

        String source = new String(Files.readAllBytes(ACP), StandardCharsets.UTF_8);
        assertTrue(source.contains("HarnessHost.builder()"), "ACP 应装配宿主骨架");
        assertTrue(source.contains("host.stream("), "一轮应交给 host.stream 开流");
        assertTrue(source.contains("admin(TENANT).interrupt("), "取消 / 关闭会话应经 SessionAdmin 停流");
        assertTrue(source.contains("whenBusy(BusyPolicy.REJECT)"),
                "一问一答的入口不能让输入进积压：请求返回后没人接续发");
    }
}

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
import org.noear.solon.ai.harness.extension.goal.GoalOutcome;
import org.noear.solon.ai.harness.extension.goal.GoalExecutor;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注入回合（loop / goal）的通道契约。
 *
 * <p>背景：goal 与 loop 拆成两条独立执行通道后，最容易被悄悄弄坏的不是「能不能跑」，
 * 而是「每个入口是否真的在自己的通道上注册了执行器」。历史上就出过两次同型事故：</p>
 * <ol>
 *   <li>内置兜底恒认领且排在列表首位，<b>桌面端的流式投递永远轮不到被调用</b>（轮次照跑、结果照存，只是不再推流）；</li>
 *   <li>CLI / Web 从未向 {@code GoalRuntime} 注册执行器，于是 {@code /goal} 在这两端<b>只有最终结果、没有过程输出</b>。</li>
 * </ol>
 *
 * <p>两次都是静默的——没有异常、没有报错。所以这里用源码契约把「注册点存在」与
 * 「返回值类型正确」钉死，而不是等着用户发现「怎么没输出」。</p>
 */
public class InjectedRoundChannelContractTest {

    private static final Path SRC = Paths.get("src/main/java/org/noear/solon/codecli");

    /**
     * 三个入口都必须把 goal 执行器注册到 {@code GoalRuntime} 自己的通道上（通道化形态）。
     *
     * <p>CLI：{@code cli} 会话；Web：{@code web-*} / {@code auto-*}；桌面：纯数字会话。
     * 三者缺任一，该入口的 goal 就只能拿到最终文本、没有过程输出。</p>
     *
     * <p>注册必须带通道名与认领谓词（{@code addExecutor(channel, claim, handler)}）：
     * 谓词交给引擎后，多入口认领边界重叠可被静态评估并告警，不再依赖静默抢跑现象排障。</p>
     */
    @Test
    void everyEntryRegistersGoalExecutorOnGoalRuntimeChannel() throws IOException {
        // 注册统一走 GoalExtension 门面（addExecutor），宿主不再绕行 getGoalRuntime()；
        // 认领谓词统一引用 SessionIdOwnership，避免三处内联正则互不知情而抢跑会话。
        assertSourceRegisters(SRC.resolve("entry/cli/CliShell.java"),
                "goal.addExecutor(\"cli\", SessionIdOwnership::isCli",
                "CLI 未向 GoalRuntime 注册通道化执行器（/goal 将没有过程输出）");

        assertSourceRegisters(SRC.resolve("workspace/WorkspaceManager.java"),
                "goalExtension.addExecutor(\"web\", SessionIdOwnership::isWeb",
                "Web 未向 GoalRuntime 注册通道化执行器（/goal 将没有过程输出）");

        assertSourceRegisters(SRC.resolve("api/desktop/WsGate.java"),
                "goal.addExecutor(\"desktop\", SessionIdOwnership::isDesktop",
                "桌面未向 GoalRuntime 注册通道化执行器（goal 轮次将不再推流）");
    }

    /**
     * 宿主不得再绕门面直接操作 GoalRuntime 的执行通道，也不得自行轮询等停。
     *
     * <p>每条禁令各自对应一处曾经复制粘贴的样板：</p>
     * <ul>
     *   <li>{@code getGoalRuntime().addExecutor} → 用 {@code GoalExtension.addExecutor}；</li>
     *   <li>{@code Math.multiplyExact(x, 60_000L)} → 用 {@code GoalSpec.maxDuration(Duration.ofMinutes(x))}；</li>
     *   <li>先解析会话再操作（{@code goal.sessionOf}/{@code goal.state(session)}）→ 门面一律按 sessionId 定位；</li>
     *   <li>手工「暂停 → 停流 → 停等 → 改写 → 恢复」→ 用 {@code goal.update(sid, taskId, patch, stopper)}；</li>
     *   <li>补齐展示 Map 字段 → 用 {@code GoalSnapshot.toMap()}。</li>
     * </ul>
     */
    @Test
    void hostDoesNotReimplementGoalFacadeSamples() throws IOException {
        List<String> forbidden = java.util.Arrays.asList(
                "getGoalRuntime().addExecutor",
                "Math.multiplyExact(maxDurationMinutes",
                "goal.sessionOf(",
                "goal.state(",
                "awaitRoundStopped(",
                "pauseAndAwait(",
                "updateAndReschedule(",
                "toTaskMap(");

        try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(SRC)) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList());
            for (Path file : javaFiles) {
                for (String line : java.nio.file.Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                        continue;
                    }
                    for (String bad : forbidden) {
                        assertTrue(!trimmed.contains(bad),
                                "宿主重复实现了 goal 门面已提供的能力：" + bad + "（文件：" + file + "）");
                    }
                }
            }
        }
    }

    /**
     * 宿主只经 loop 门面的规格/补丁操作任务，不得重建已被插件接管的样板：
     * <ul>
     *   <li>{@code setInjectionGate} → 已删除：终端占用由 CLI 执行器返回 {@code LoopOutcome.skip}；</li>
     *   <li>{@code copyWithUpdate} / 手工「传了用新值、没传沿用旧值」→ {@code LoopPatch}；</li>
     *   <li>{@code safeChatInput(session, prompt)} 吞异常返回 null → 失败必须上抛（否则绕过熔断）。</li>
     * </ul>
     */
    @Test
    void hostDoesNotReimplementLoopFacadeSamples() throws IOException {
        List<String> forbidden = java.util.Arrays.asList(
                "setInjectionGate(", "copyWithUpdate(", "new LoopTask(", "safeChatInput(session, prompt)");
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(SRC)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).collect(java.util.stream.Collectors.toList())) {
                for (String line : java.nio.file.Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                        continue;
                    }
                    for (String bad : forbidden) {
                        assertTrue(!trimmed.contains(bad),
                                "宿主重复实现了 loop 门面已提供的能力：" + bad + "（文件：" + file + "）");
                    }
                }
            }
        }
    }

    /**
     * loop 与 goal 的注册点必须分别出现，不得互相替代。
     *
     * <p>拆桥的收益正是「两域各自拥有通道」；若某个入口只注册其中一个，
     * 另一个引擎就退回缺省执行器——静默、无声、且行为差异极难察觉。</p>
     */
    @Test
    void eachEntryRegistersBothChannelsSeparately() throws IOException {
        // CLI 与 Web 工作区都同时服务 loop 与 goal
        // loop 通道经插件门面注册（CLI 与 Web 同形：挂接后 addExecutor，认领谓词交给插件）
        assertSourceRegisters(SRC.resolve("entry/cli/CliShell.java"),
                "loop.addExecutor(\"cli\", SessionIdOwnership::isCli,", "CLI 的 loop 通道丢失");
        assertSourceRegisters(SRC.resolve("workspace/WorkspaceManager.java"),
                "loopExtension.addExecutor(\"web\", SessionIdOwnership::isWeb, webLoopExecutor(", "Web 的 loop 通道丢失");
    }

    /**
     * goal 执行器的返回值必须携带本轮客观事实，而不是裸文本。
     *
     * <p>返回 {@code String} 时，token 与工具证据只能写进会话 attrs，再被读回来——
     * 那正是 {@code _loop_*} 键与「轮前重置 / 读完即删」那套时序约定的来源，
     * 也是 CLI/Web 上「完成需行动证据」校验长期失效的根因。</p>
     */
    @Test
    void goalExecutorReturnsGoalOutcome() {
        Method[] methods = GoalExecutor.class.getDeclaredMethods();
        assertEquals(1, methods.length, "GoalExecutor 应当是单方法接口");

        Method handle = methods[0];
        assertEquals(GoalOutcome.class, handle.getReturnType(),
                "GoalExecutor 必须返回 GoalOutcome（返回 String 会迫使指标再走 attrs 旁路）");
        assertEquals(2, handle.getParameterCount(),
                "GoalExecutor 只应有 (sessionId, prompt) 两个参数："
                        + "agentName 恒传 null，子代理归属是会话属性 CTX_AGENT_SELECTED");
    }

    /**
     * Web 运行时端口必须分别暴露 loop 与 goal 两条注入入口。
     *
     * <p>两者是同一段执行、只有返回值不同（loop 要三态结论、goal 要事实）。
     * loop 若只拿到文本，「会话忙」与「流报错」都会变成 null，失败就绕过了任务级熔断。</p>
     */
    @Test
    void runtimePortExposesBothInjectionEntries() throws Exception {
        Class<?> port = Class.forName("org.noear.solon.codecli.workspace.WorkspaceRuntimePort");

        Method runLoop = port.getMethod("runLoop", String.class, String.class, String.class, String.class);
        assertEquals(org.noear.solon.ai.harness.extension.loop.LoopOutcome.class, runLoop.getReturnType(),
                "runLoop 必须回报三态结论（跳过与失败必须可区分）");

        Method runGoal = port.getMethod("runGoalRound", String.class, String.class, String.class, String.class);
        assertEquals(GoalOutcome.class, runGoal.getReturnType(), "runGoalRound 必须回报本轮结论（含客观事实）");
    }

    /** 断言源码中出现了指定片段（非注释行）。 */
    private static void assertSourceRegisters(Path file, String expectedSnippet, String message) throws IOException {
        assertTrue(Files.exists(file), "源码不存在：" + file);

        List<String> hits = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            if (trimmed.contains(expectedSnippet)) {
                hits.add(trimmed);
            }
        }

        assertTrue(!hits.isEmpty(), message + "（未找到：" + expectedSnippet + "，文件：" + file + "）");
    }
}

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
     * 二者现在<b>没有任何交汇点</b>（执行通道已各自内联：goal 在 GoalEngine、
     * loop 在 LoopScheduler，连共享类都已不存在）。
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
     * 防回归：曾用于跨域共享的 {@code ExecutionBridge} 已删除。
     *
     * <p>它把「执行投递」「忙判定」「轮次度量」三件不相干的事装进一个类，
     * 使 loop 的语义渗进 harness（典型症状：通用轮次度量被命名为 {@code _loop_*}）。
     * 现在 goal 与 loop 各自内联自己的执行通道；若有人重新引入跨域共享类，
     * 此测试会直接报错，而不会等到行为漂移才被发现。</p>
     *
     * <p>只检查非注释行：注释中提及旧类型名是有价值的上下文（解释为何不能回去），
     * 代码中引用才是回归。</p>
     */
    @Test
    void domainCodeMustNotReferenceRemovedExecutionBridge() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SRC)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                    continue;
                }
                if (trimmed.contains("ExecutionBridge")) {
                    violations.add(file.getFileName() + ": " + trimmed);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "ExecutionBridge 已删除（执行通道已各自内联），不得重新引入：" + violations);
    }

    /**
     * 防回归：不得再出现以 loop 名义挂在引擎侧轮次度量上的 {@code _loop_*} 键。
     *
     * <p>这两个键的实际写入者是所有入口的所有回合（CLI/Web/Desktop），与 loop 无关；
     * 它们之所以存在，只是因为执行通道返回类型装不下 token 与工具证据。
     * 现在证据随返回值上交，键已彻底消失。</p>
     */
    @Test
    void domainCodeMustNotUseLoopPrefixedSessionKeys() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SRC)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.contains("_loop_last_")) {
                    violations.add(file.getFileName() + ": " + line.trim());
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "不得再使用 _loop_* 会话 attrs（轮次度量已改为返回值携带）：" + violations);
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

    /**
     * 宿主不得再直接使用 harness 的 queue / goal <b>内部</b>类型与持久化格式。
     *
     * <p>本轮封装改造后的契约（已在代码中落地，此处锁定不被回退）：</p>
     * <ul>
     *   <li>禁止 {@code import QueueHelper}（内核静态类，仅门面与测试使用）；</li>
     *   <li>禁止从会话 attrs 里取 {@code QueueItem} 再强转（领取应由 {@code QueueClaim} 携带）；</li>
     *   <li>禁止硬编码 {@code GoalEngine.KEY_GOAL} 或投影字段名（读写走 {@code GoalProjection}）；</li>
     *   <li>禁止把 {@code QueueItem.toMap()}（持久化投影）当对外 API（应用 {@code QueueItemView}）；</li>
     *   <li>禁止 {@code import SteerHelper} 或硬编码 {@code web.steerBox}/{@code web.activeRunId}
     *       （steer 只经 {@code SteerExtension} 门面，内部键已迁到 {@code harness.*}）。</li>
     * </ul>
     */
    @Test
    void hostMustNotUseQueueAndGoalInternals() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SRC)) {
            String name = file.getFileName().toString();
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

            if (content.contains("import org.noear.solon.ai.harness.session.queue.QueueHelper")) {
                violations.add(name + " 直接依赖内核 QueueHelper（应经 QueueExtension 门面）");
            }
            if (content.contains("instanceof QueueItem") || content.contains("(QueueItem)")) {
                violations.add(name + " 从 attrs 强转 QueueItem（应由 QueueClaim 携带领域对象）");
            }
            if (content.contains("GoalEngine.KEY_GOAL")) {
                violations.add(name + " 硬编码 goal 投影键（应使用 GoalProjection）");
            }
            if (content.contains("import org.noear.solon.ai.harness.session.steer.SteerHelper")) {
                violations.add(name + " 直接依赖内核 SteerHelper（应经 SteerExtension 门面）");
            }
            if (content.contains("\"web.steerBox\"") || content.contains("\"web.activeRunId\"")) {
                violations.add(name + " 硬编码 steer 内部 attrs 键（已迁移到 harness.*，宿主不应读写）");
            }
            // 队列展示必须走视图对象；GoalDataMigrator 处理的是历史 ONode，不在此列
            if (!name.equals("GoalDataMigrator.java")
                    && content.contains("QueueItem item")
                    && content.contains(".toMap()")) {
                violations.add(name + " 把持久化投影当对外 API（应使用 QueueItemView）");
            }
        }
        assertTrue(violations.isEmpty(), "宿主不得触碰 harness 内部实现细节：" + violations);
    }

    /**
     * 宿主私有会话键不得占用引擎命名空间（{@code session.*}）。
     *
     * <p>命名空间规约见 harness 的 {@code HarnessAttrs}：{@code session.*} 归引擎契约，
     * 宿主自有键必须用 {@code cli.*} / {@code im.*} / {@code _} 前缀。此前
     * {@code session.replyRoute}、{@code session.queue.executing}、{@code session.input.admitting}
     * 均因占错命名空间而被逐个改正，此处防止再犯。</p>
     */
    @Test
    void hostMustNotClaimEngineAttrNamespace() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SRC)) {
            String name = file.getFileName().toString();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                    continue;
                }
                if (trimmed.contains("\"session.")) {
                    violations.add(name + ": " + trimmed);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "宿主私有键必须用 cli./im. 前缀，不得占用引擎的 session.* 命名空间：" + violations);
    }

    /**
     * {@code artifact} 包不得反向依赖 {@code api.web}。
     *
     * <p>共享的宿主会话键（{@code cli.replyRoute}、{@code im.terminal.sent} 等）
     * 已下沉到中立的 {@code codecli.session.HostAttrs}：{@code artifact} 与
     * {@code api.web} 双向都只依赖 {@code session}。此前为了用 {@code WebAttrs}
     * 中的一个常量，曾新增 {@code artifact -> api.web} 反向依赖——用架构问题换掉
     * 一个字符串字面量，得不偿失。</p>
     */
    @Test
    void artifactMustNotDependOnApiWeb() throws IOException {
        List<Path> files = javaFiles(SRC.resolve("artifact"));
        assumeTrue(!files.isEmpty(), "artifact 包源码不存在");

        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (content.contains("import org.noear.solon.codecli.api.web.")) {
                violations.add(file.getFileName() + " 反向依赖 api.web（共享键应用 codecli.session.HostAttrs）");
            }
        }
        assertTrue(violations.isEmpty(), "artifact 不得依赖 web 层：" + violations);
    }

    /**
     * 宿主不得自行维护「队列派发」状态（本轮封装改造的最终一步）。
     *
     * <p>异步队列任务必须回答两件事：本轮是否队列派发、该用哪个 claim 收口。
     * {@code QueueDispatch} 落地前，宿主只能自建内部类 + 宿主私有键 + 两处 synchronized
     * 来重新记一份，换一端就要重写。现在统一由 harness 持有：</p>
     * <ul>
     *   <li>{@code QueueDispatch#current(session)} 读（静态入口，与是否有扩展实例无关）；</li>
     *   <li>{@code QueueDispatch#settle(boolean)} / {@code QueueExtension#settleDispatch} 收口。</li>
     * </ul>
     */
    @Test
    void hostMustNotOwnQueueDispatchState() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SRC)) {
            String name = file.getFileName().toString();
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

            if (content.contains("cli.queue")) {
                violations.add(name + " 自建队列派发键（应用 QueueDispatch）");
            }
            if (content.contains("class QueuedTurn")) {
                violations.add(name + " 自建派发事务类（应用 QueueDispatch）");
            }
            // 队列派发路径不应再碰会话锁：能不能接的判定已与领取原子化
            if (name.equals("WebQueueDispatcher.java") && content.contains("synchronized (session.attrs())")) {
                violations.add(name + " 仍在队列路径上用会话锁（应把谓词下推到 claimNext）");
            }
            // 同步收口已内核化：宿主回调只报告 true/false，不得手写 acknowledge/rollback
            // （手写 ack 丢返回值 = Stop 被静默击败、任务无限重跑，该陷阱已从 API 形状上消灭）
            if (name.equals("CliShell.java") || name.equals("WebQueueDispatcher.java")) {
                if (content.contains("claim.acknowledge(") || content.contains("claim.rollback(")) {
                    violations.add(name + " 手写 claim 收口（应在回调里返回 true/false，收口归内核）");
                }
            }
            // CLI 消费是纯同步循环（返回 true），不存在异步队列轮，settleDispatch 在 CLI 无合法用途
            if (name.equals("CliShell.java") && content.contains("settleDispatch(")) {
                violations.add(name + " 在 CLI 队列路径上手写 settleDispatch（同步收口应返回 true 交内核）");
            }
            // Web 的 finishQueuedTurn（turn==null 兜底）仍用 QueueExtension.settleDispatch，为豁免点；
            // 但已删除的会话态判定不得回潮：流终态归属看订阅前捕获的 turn，不看会话当前派发
            if (name.equals("WebQueueDispatcher.java") && content.contains("hasQueuedDispatch")) {
                violations.add(name + " 仍引用已删除的 hasQueuedDispatch（流终态应用订阅前捕获的 turn）");
            }
        }
        assertTrue(violations.isEmpty(), "队列派发状态必须由 harness 持有：" + violations);
    }

    /**
     * 宿主写会话属性必须用登记过的常量，不得现写裸字面量键。
     *
     * <p>裸键的三个已证实后果（均已修）：引擎改键名时宿主静默失效（{@code _loop_last_*} 预算恒为零）、
     * 无前缀键占用共享平面（{@code streamDoneSent}）、写进去没人读的死键（{@code _plan_mode}）。</p>
     *
     * <p>豁免：镜像 / 快照类文件不读写会话属性，自然不命中；{@code HostAttrs} 自身定义常量。</p>
     */
    @Test
    void hostAttrWritesMustUseRegisteredConstants() throws IOException {
        List<String> violations = new ArrayList<>();
        java.util.regex.Pattern write = java.util.regex.Pattern.compile(
                "attrs\\(\\)\\.(put|putIfAbsent|remove|get|getOrDefault|computeIfAbsent)\\(\\s*\"");

        for (Path file : javaFiles(SRC)) {
            String name = file.getFileName().toString();
            if (name.equals("HostAttrs.java")) {
                continue;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                    continue;
                }
                if (write.matcher(trimmed).find()) {
                    violations.add(name + ": " + trimmed);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "宿主会话属性键必须来自 HostAttrs（裸字面量键会与 harness 契约脱钩）：" + violations);
    }

    /**
     * 宿主不得自己维护取消代数 / 派发身份：一轮流的生命周期凭据归 harness（{@code SessionTurn}）。
     *
     * <p>此前同一段「订阅前取代数基准 + 流终态判取消」的代码在 Web 的 async/sync 两条路径
     * 与桌面端各写一遍，三处必然漏一处（sync 路径曾漏掉派发事务收口）。
     * 「取消以 onComplete 收尾」这条不变量只靠 README 记住，属于典型的事故温床。</p>
     */
    @Test
    void hostMustNotTrackCancelGenerationManually() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SRC)) {
            String name = file.getFileName().toString();
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

            // 取消代数基准：任何宿主文件都不得自己取（这是「取消以 complete 收尾」不变量的入口）
            if (content.contains("SessionRuns.cancelCount") || content.contains("SessionRuns.isCancelledSince")) {
                violations.add(name + " 自行捕获取消代数（应用 SessionTurn）");
            }
        }
        // 派发事务身份：流生命周期路径不得自己捕获，只有队列适配器可读取派发线程身份
        for (String rel : new String[]{
                "api/web/WebSessionRunner.java",
                "api/desktop/DesktopStreamTurn.java",
                "entry/cli/CliShell.java",
                "entry/headless/PrintMode.java"}) {
            Path file = SRC.resolve(rel);
            if (!Files.exists(file)) continue;
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (content.contains("QueueDispatch.current")) {
                violations.add(file.getFileName() + " 自行捕获派发事务（应用 SessionTurn#begin/dispatch）");
            }
        }
        assertTrue(violations.isEmpty(), "一轮流的生命周期凭据必须由 SessionTurn 持有：" + violations);
    }

    /**
     * 宿主不得自己组合受理判定：能不能接、该走哪条路归 harness（{@code engine.admit}）。
     *
     * <p>受限文件：Web 输入入口与队列派发器。这两处曾用 50 余行 {@code synchronized} 块
     * 自行组合运行流、受理窗口、HITL、队列积压、派发身份、忙态旁路六个判定。</p>
     */
    @Test
    void hostMustNotComposeAdmissionManually() throws IOException {
        List<String> violations = new ArrayList<>();
        Path file = SRC.resolve("api/web/WebChatInputHandler.java");

        if (Files.exists(file)) {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

            // 受理入口不得再自己拼装：那段 50 余行 synchronized 块是历史事故源头
            if (content.contains("synchronized (session.attrs())")) {
                violations.add("WebChatInputHandler.java 仍在入口手拼受理判定（应用 engine.admit）");
            }
            // 必须真的走到 engine.admit，否则「收敛」只是删了代码
            if (!content.contains(".admit(")) {
                violations.add("WebChatInputHandler.java 未调用 engine.admit");
            }
        }

        // safeChatInput 的预检（IM 通道 / Loop 调度）保留 isBusy 判定：
        // 那是「要不要转发这条消息」的宿主策略，不是开流受理，不占位也不该占位。
        assertTrue(violations.isEmpty(), "受理判定必须走 engine.admit：" + violations);
    }

    /**
     * 宿主不得直接读写会话级选择项裸常量（模型 / 子代理）。
     *
     * <p>与「不得硬编码 {@code GoalEngine.KEY_GOAL}」同一条理由：同一键曾出现四种写法
     * （put 值 / put 空串 / remove / 带守卫），语义漂移正是「裸常量当私有状态用」的必然结果。
     * {@code SessionSelections} 是给拿不到引擎实例的静态工具用的读开口。</p>
     */
    @Test
    void hostMustNotTouchSessionSelectionKeys() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SRC)) {
            String name = file.getFileName().toString();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                    continue;
                }
                if (trimmed.contains("CTX_MODEL_SELECTED") || trimmed.contains("CTX_AGENT_SELECTED")) {
                    violations.add(name + ": " + trimmed);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "会话级选择项必须走 get/setSessionModel、get/setSessionAgent（或 SessionSelections）：" + violations);
    }

    /**
     * 宿主不得自行拼装 {@code AgentMentions.Options}。
     *
     * <p>失效形态是静默的：宿主为了提前拿到「剥离提及后的正文」（Goal 拦截、名字校验、
     * 规划模式改写都作用在正文上）而自己拼一份 Options，于是同一段输入在宿主与引擎里
     * <b>各解析一次、口径不同</b>（一个 trim 一个不 trim），最终生效的子代理与正文
     * 取决于哪一次先落地。{@code HarnessInput.parseMention} 是唯一入口，
     * {@code handle} 内部也走它。</p>
     */
    @Test
    void hostMustNotBuildMentionOptionsManually() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SRC)) {
            String name = file.getFileName().toString();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                    continue;
                }
                if (trimmed.contains("AgentMentions.Options.builder()")
                        || trimmed.contains("AgentMentions.parse(")) {
                    violations.add(name + ": " + trimmed);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "子代理提及解析必须走 HarnessInput#parseMention（与 handle 同口径）：" + violations);
    }

    /**
     * harness 内置三件套（steer / queue / goal）必须经聚合入口装配，不得在宿主组合根逐行手拼。
     *
     * <p>手拼装配的失效形态是静默的：少写一行就是「命令没注册、功能不存在」，
     * 且 <code>addExtension</code> 不查重，重复写会二次 <code>onAttach</code> 重建调度器。
     * 判定走 <code>HarnessExtensions.installDefaults</code> 后，顺序与幂等不再外包给宿主。</p>
     *
     * <p>豁免：测试源码不在 SRC 范围内，自然不命中。</p>
     */
    @Test
    void hostMustUseAggregateExtensionInstaller() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SRC)) {
            String name = file.getFileName().toString();
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

            if (content.contains("addExtension(new SteerExtension())")) {
                violations.add(name + " 逐行装配 steer（应用 HarnessExtensions.installDefaults）");
            }
            if (content.contains("addExtension(new QueueExtension())")) {
                violations.add(name + " 逐行装配 queue（应用 HarnessExtensions.installDefaults）");
            }
            if (content.contains("addExtension(goalExtension)")) {
                violations.add(name + " 逐行装配 goal（应用 HarnessExtensions.installDefaults）");
            }
        }
        assertTrue(violations.isEmpty(),
                "harness 内置三件套必须经 HarnessExtensions.installDefaults 装配：" + violations);
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

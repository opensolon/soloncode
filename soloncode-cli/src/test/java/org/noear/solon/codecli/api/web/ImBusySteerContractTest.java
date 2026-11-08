package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.channel.ImMessages;
import org.noear.solon.codecli.channel.ImStatus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IM 忙态默认语义契约：与 web 流式中回车一致，默认「插话」，插不进去才降级排队。
 *
 * <p>这条默认语义没有前端可依赖（IM 无前端），完全落在后端 {@code WebGate#safeChatInput} 与骨架的 {@code steerWhenBusy} 上，
 * 因此用源码契约把它钉住：防止后人「顺手」把忙态处理改回无脑入队。</p>
 *
 * @author noear
 */
class ImBusySteerContractTest {
    /** 与 {@code WebStreamBuilderReplyRouteTest.messagesHaveFrontendLocaleBundles} 对齐的前端语言集。 */
    private static final List<String> LOCALES = Arrays.asList(
            "ar", "bn", "br", "bs", "da", "de", "en", "es", "fr", "gr", "it",
            "ja", "ko", "nl", "no", "pl", "pt_BR", "ru", "th", "tr", "uk", "vi", "zh_TW");

    private static final String GATE = "src/main/java/org/noear/solon/codecli/api/web/WebGate.java";
    private static final String SINK = "src/main/java/org/noear/solon/codecli/api/web/WebHostSink.java";

    /** IM 入口：取 safeChatInput 方法体（7 参版本） */
    private static String safeInput(String gate) {
        String body = gate.substring(gate.indexOf("public boolean safeChatInput(WorkspaceContext wsContext, String sessionId, String input, String source,\n"));
        return body.substring(0, body.indexOf("\n    }\n"));
    }

    /**
     * 忙态默认走插话，而不是直接入队：IM 入口交给骨架的 steerWhenBusy
     * （会话有运行流、纯文本、非命令、装了 steer 插件时插话；插不进再回落到 handle）。
     */
    @Test
    void busySessionDefaultsToSteer() throws IOException {
        String gate = readProjectFile(GATE);
        String safe = safeInput(gate);
        assertTrue(safe.contains("true, true)"),
                "IM 入口必须以 echo=true、steerWhenBusy=true 提交（忙态普通输入默认插话）");
        assertTrue(gate.contains(".whenBusy(steerWhenBusy ? SubmitSpec.BusyPolicy.STEER"), "忙时插话必须交给骨架的 steerWhenBusy，而不是宿主自己判忙");
        assertFalse(gate.contains("steerExtension.steer(") || gate.contains("SteerExtension.class"),
                "宿主不得再自己调插话门面（判忙、排除命令、回落排队都在骨架里）");

        String sink = readProjectFile(SINK);
        assertTrue(sink.contains("ImStatus.STEERED"), "插话成功后必须给来源端一条可感知的回执，否则 IM 端是黑箱");
    }

    /** 插话失败仍降级为统一持久化排队（骨架回落到 handle → DEFERRED），并向 IM 回报排队位次。 */
    @Test
    void steerFailureFallsBackToQueue() throws IOException {
        String sink = readProjectFile(SINK);
        String deferred = sink.substring(sink.indexOf("public void deferred(HostInput input, int position)"));
        deferred = deferred.substring(0, deferred.indexOf("\n    }\n"));
        assertTrue(deferred.contains("ImStatus.QUEUED") && deferred.contains("ImMessages.queued(position - 1"),
                "排队必须把位次推给 IM 来源端（位置从 1 计）");
        assertTrue(deferred.contains("claimCommandHint"), "命令引导同一轮忙态只教学一次");
        assertTrue(deferred.contains("WebEvent.ofUserInput"), "排队时回放用户消息");
    }

    /** 插话回执文案按地区解析（zh/en 为代表），且 textOf 能兜底取到。 */
    @Test
    void steeredReceiptResolvesByLocale() {
        ImMessages.setLocale(Locale.SIMPLIFIED_CHINESE);
        try {
            assertTrue(ImMessages.STEERED().contains("插话"), ImMessages.STEERED());
            assertEquals(ImMessages.STEERED(), ImMessages.textOf(ImStatus.STEERED, null));
        } finally {
            ImMessages.setLocale(null);
        }

        ImMessages.setLocale(Locale.ENGLISH);
        try {
            assertTrue(ImMessages.STEERED().contains("steered"), ImMessages.STEERED());
            assertFalse(ImMessages.STEERED().contains("插话"), ImMessages.STEERED());
            assertEquals(ImMessages.STEERED(), ImMessages.textOf(ImStatus.STEERED, null));
        } finally {
            ImMessages.setLocale(null);
        }
    }

    /** 端到端接线：插话回执只经统一出口 signalOriginChannel 投给来源端。 */
    @Test
    void steeredSignalIsDeliveredByOriginChannelOnly() throws IOException {
        String sink = readProjectFile(SINK);
        String steered = sink.substring(sink.indexOf("public void steered(HostInput input)"));
        steered = steered.substring(0, steered.indexOf("\n    }\n"));
        assertTrue(steered.contains("signal(ctx, input, ImStatus.STEERED"), "插话回执必须经 signal（signalOriginChannel）只投来源端");
        assertTrue(sink.contains("gate.getStreamBuilder().signalOriginChannel("), "signal 必须走统一出口 signalOriginChannel");
        assertFalse(steered.contains("ofUserInput"), "插话不回放 user_input（web 端由 steer_applied 就地渲染）");
    }

    /**
     * HITL 挂起时拒新输入：判定只在引擎受理里（SessionControl 第 1 步，早于入积压；HarnessHost 的忙时插话也排除待审批），
     * 入口不再重复预检。IM 来源撞上待审批只告知来源端，不在 Web 端追加错误、不发终态（Web 端正停在审批卡上）。
     */
    @Test
    void pendingHitlRejectsBeforeBusyDispatch() throws IOException {
        String safe = safeInput(readProjectFile(GATE));
        assertFalse(safe.contains("hasPendingHitl("), "入口不再重复引擎的 HITL 判定（两处口径必然漂移）");

        String sink = readProjectFile(SINK);
        String rejected = sink.substring(sink.indexOf("public void rejected(HostInput input, RejectReason reason, String detail)"));
        rejected = rejected.substring(0, rejected.indexOf("\n    }\n"));
        int imBranch = rejected.indexOf("reason == RejectReason.HITL_PENDING && input.getOrigin().isIm()");
        int errorEmit = rejected.indexOf("WebEvent.ofError(reason.code(), detail)");
        assertTrue(imBranch > 0 && errorEmit > imBranch, "IM 撞上待审批：在追加 Web 错误之前分流");
        String branch = rejected.substring(imBranch, errorEmit);
        assertTrue(branch.contains("ImStatus.HITL_PENDING") && branch.contains("return;"),
                "拒收必须给来源端一个可区分的回执（HITL_PENDING ≠ 队列满），且不打扰 Web 端");
    }

    /**
     * 审批决策、/resume、忙态旁路命令不得被 HITL 拦截（否则审批本身都无法提交）：豁免口径只在引擎里
     * （审批决策 / 续流按输入本身豁免、旁路命令由命令自描述 runnableWhenBusy 决定）。
     */
    @Test
    void hitlGateExemptsDecisionDispatchAndBypassCommands() throws IOException {
        String gate = readProjectFile(GATE);
        assertFalse(gate.contains("isHitlExempt("), "豁免口径只在引擎里，入口不再维护第二份");

        String submit = gate.substring(gate.indexOf("private SubmitResult submit(WorkspaceContext"));
        assertFalse(submit.contains(".admit("), "受理判定只在唯一执行入口 engine.handle 里（admit 已删除）");
        assertTrue(submit.contains("\"/resume\".equals(") && submit.contains("in.resume(resume)"),
                "文本 /resume 必须转成显式 resume（引擎据此豁免待审批拦截），否则续流会被拦死");
        assertTrue(submit.contains("in.hitl(action, callId)") && submit.contains("HITLAction.of(hitlAction)"),
                "审批决策必须作为 hitl 输入交给引擎（引擎据此豁免待审批拦截），否则审批永远提不了");
    }

    /**
     * 回归：拒收分支必须补发终态包。
     *
     * <p>前端在 POST 之前就把 {@code isStreaming} 置真，只有 {@code system.done} 会复位；
     * {@code system.error} 只追加气泡不复位。漏发 done 会让输入面板永久停在流式态。</p>
     */
    @Test
    void hitlRejectBranchEmitsTerminalPacket() throws IOException {
        String sink = readProjectFile(SINK);
        String rejected = sink.substring(sink.indexOf("public void rejected(HostInput input, RejectReason reason, String detail)"));
        rejected = rejected.substring(0, rejected.indexOf("\n    }\n"));

        int errorEmit = rejected.indexOf("WebEvent.ofError(reason.code(), detail)");
        int imSignal = rejected.lastIndexOf("ImStatus.HITL_PENDING");
        int doneEmit = rejected.indexOf("doneGuarded(ctx, input)");
        assertTrue(errorEmit > 0, "拒收必须先给出可读错误（原因与细节取自引擎）");
        assertTrue(imSignal > errorEmit, "来源端（IM）也要知道本轮未受理，HITL_PENDING 与普通拒收可区分");
        assertTrue(doneEmit > imSignal, "拒收分支必须发终态包（emitDoneGuarded），否则前端 isStreaming 永久为真");

        String guarded = sink.substring(sink.indexOf("private void doneGuarded("));
        guarded = guarded.substring(0, guarded.indexOf("\n    }\n"));
        assertTrue(guarded.contains("emitDoneGuarded"), "有活跃流时不动终态门（拒收不得结束正在跑的那一轮）");
    }

    /** 新增的 im.hitlPending 必须补齐全部语言包与 FALLBACK，避免局部回退成中文。 */
    @Test
    void hitlPendingKeyExistsInEveryLocaleBundle() throws IOException {
        assertTrue(resourceText("/i18n/im-messages.properties").contains("im.hitlPending="),
                "默认语言包缺少 im.hitlPending");
        for (String locale : LOCALES) {
            String text = resourceText("/i18n/im-messages_" + locale + ".properties");
            assertNotNull(text);
            assertTrue(text.contains("im.hitlPending="),
                    "语言包 im-messages_" + locale + ".properties 缺少 im.hitlPending");
        }
        assertNotNull(ImMessages.HITL_PENDING());
        assertEquals(ImMessages.HITL_PENDING(), ImMessages.textOf(ImStatus.HITL_PENDING, null));
    }

    /** 新增的 im.steered 必须补齐全部已存在的前端语言包，避免单语言回退成默认中文。 */
    @Test
    void steeredKeyExistsInEveryLocaleBundle() throws IOException {
        assertTrue(resourceText("/i18n/im-messages.properties").contains("im.steered="),
                "默认语言包缺少 im.steered");
        for (String locale : LOCALES) {
            String text = resourceText("/i18n/im-messages_" + locale + ".properties");
            assertNotNull(text);
            assertTrue(text.contains("im.steered="),
                    "语言包 im-messages_" + locale + ".properties 缺少 im.steered");
        }
    }

    private static String readProjectFile(String path) throws IOException {
        java.nio.file.Path direct = Paths.get(path);
        if (Files.exists(direct)) {
            return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
        }
        java.nio.file.Path fromParent = Paths.get("soloncode-cli", path);
        if (Files.exists(fromParent)) {
            return new String(Files.readAllBytes(fromParent), StandardCharsets.UTF_8);
        }
        throw new IOException("Missing project file: " + path);
    }

    private static String resourceText(String path) throws IOException {
        try (InputStream in = ImBusySteerContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Missing resource: " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) >= 0; ) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

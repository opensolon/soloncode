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
 * <p>这条默认语义没有前端可依赖（IM 无前端），完全落在后端 {@code safeChatInput} 的兜底分支上，
 * 因此用源码契约把它钉住：防止后人「顺手」把忙态处理改回无脑入队。</p>
 *
 * @author noear
 */
class ImBusySteerContractTest {
    /** 与 {@code WebStreamBuilderReplyRouteTest.messagesHaveFrontendLocaleBundles} 对齐的前端语言集。 */
    private static final List<String> LOCALES = Arrays.asList(
            "ar", "bn", "br", "bs", "da", "de", "en", "es", "fr", "gr", "it",
            "ja", "ko", "nl", "no", "pl", "pt_BR", "ru", "th", "tr", "uk", "vi", "zh_TW");

    /** 忙态默认走插话（SteerExtension.steer），而不是直接入队。 */
    @Test
    void busySessionDefaultsToSteer() throws IOException {
        String source = readProjectFile("src/main/java/org/noear/solon/codecli/api/web/WebChatInputHandler.java");

        // 3 参形态：忙判定改由内核按 engine.status(session).isBusy() 自算，
        // 宿主不再传 busyRecheck lambda（原 6 参调用形态随之消失）
        assertTrue(source.contains("steerExtension.steer(session, input"),
                "忙态普通输入应默认插话（SteerExtension.steer）");
        assertFalse(source.contains("() -> SessionActivity.isBusy(session)"),
                "忙判定不得再以 lambda 形式外包给宿主（答案在 engine.status，见 SteerExtension.defaultBusy）");
        assertTrue(source.contains("ImStatus.STEERED"),
                "插话成功后必须给来源端一条可感知的回执，否则 IM 端是黑箱");
        // 斜杠命令不走插话（与 web 前端「运行中不把命令当插话」一致），仍走原有处理
        assertTrue(source.contains("input != null && !input.startsWith(\"/\")"),
                "忙态插话必须排除斜杠命令");
    }

    /** 插话失败仍降级为统一持久化排队，消息不丢。 */
    @Test
    void steerFailureFallsBackToQueue() throws IOException {
        String source = readProjectFile("src/main/java/org/noear/solon/codecli/api/web/WebChatInputHandler.java");

        int steer = source.indexOf("steerExtension.steer(session, input");
        int enqueue = source.indexOf("int position = queue(wsContext).enqueue(session, input,", steer);
        assertTrue(steer > 0 && enqueue > steer,
                "入队必须保留为插话之后的降级路径，保证消息不丢");
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

    /** 端到端接线：终态投递本身不产生这条回执，只有 signalOriginChannel 会下发。 */
    @Test
    void steeredSignalIsDeliveredByOriginChannelOnly() throws IOException {
        String source = readProjectFile("src/main/java/org/noear/solon/codecli/api/web/WebChatInputHandler.java");

        assertTrue(source.contains("signalOriginChannel(wsContext, sessionId, ImStatus.STEERED"),
                "插话回执必须经统一出口 signalOriginChannel 只投来源端");
    }

    /** HITL 挂起时拒新输入：该判定必须早于忙态分派，否则普通输入会被插话/入队而不是提示先审批。 */
    @Test
    void pendingHitlRejectsBeforeBusyDispatch() throws IOException {
        String source = readProjectFile("src/main/java/org/noear/solon/codecli/api/web/WebChatInputHandler.java");
        String safeInput = source.substring(source.indexOf("boolean safeChatInput(WorkspaceContext"));

        int hitl = safeInput.indexOf("SessionActivity.hasPendingHitl(session)");
        int busy = safeInput.indexOf("if (SessionActivity.isBusy(session))");
        assertTrue(hitl > 0 && busy > hitl,
                "HITL 挂起判定必须排在忙态分支之前，否则新输入会被插话/排队而非提示先处理审批");
        assertTrue(safeInput.contains("ImStatus.HITL_PENDING"),
                "拒收必须给来源端一个可区分的回执（HITL_PENDING ≠ 队列满）");
    }

    /**
     * 审批决策、队列派发、/resume、忙态旁路命令四类不得被 HITL 拦截（否则审批本身都无法提交）。
     *
     * <p>两个入口必须共用同一份豁免判定：此前 onChatInput 写了四项、safeChatInput 只写了一项，
     * IM 侧发 /resume 或点审批会被当成「请先处理审批」拒掉——而这两者恰恰是处理审批的手段。</p>
     */
    @Test
    void hitlGateExemptsDecisionDispatchAndBypassCommands() throws IOException {
        String source = readProjectFile("src/main/java/org/noear/solon/codecli/api/web/WebChatInputHandler.java");

        int methodStart = source.indexOf("private static boolean isHitlExempt(");
        assertTrue(methodStart > 0, "豁免口径必须收敛为一个共用方法（isHitlExempt）");
        String method = source.substring(methodStart);
        method = method.substring(0, method.indexOf("\n    }"));

        assertTrue(method.contains("Assert.isNotEmpty(hitlAction)"),
                "审批决策本身必须豁免，否则审批永远提不了");
        assertTrue(method.contains("/resume"), "显式续流必须豁免，它就是处理审批的手段");
        assertTrue(method.contains("isQueueDispatch"),
                "队列派发的一轮已过 isNotOccupied 闸门，不能二次拦截");        assertTrue(method.contains("isBusyBypassCommand"),
                "忙态旁路命令由命令自描述决定，闸门不得否定它");

        // 两个入口都必须覆盖同一口径，不能各写一份。
        // 4.2.3 起：onChatInput 的受理判定收归 engine.admit，豁免以谓词传入；
        // safeChatInput 的 IM 预检仍直接调用 isHitlExempt。
        assertTrue(source.indexOf("isHitlExempt(", methodStart + method.length()) > 0,
                "safeChatInput 仍须调用共用的豁免判定");

        String onInput = source.substring(source.indexOf("void onChatInput(WorkspaceContext"));
        onInput = onInput.substring(0, onInput.indexOf("boolean safeChatInput(WorkspaceContext"));
        assertTrue(onInput.contains(".admit("),
                "onChatInput 的受理判定必须走 engine.admit（不得再在入口手拼六个判定）");
        assertTrue(onInput.contains("/resume"),
                "onChatInput 传给 admit 的豁免谓词必须包含 /resume，否则续流会被拦死");
        assertTrue(onInput.contains("hitlAction"),
                "onChatInput 传给 admit 的 hitlDecision 必须来自 hitlAction，否则审批永远提不了");
    }

    /**
     * 回归：HITL 拒收分支必须补发终态包。
     *
     * <p>前端在 POST 之前就把 {@code isStreaming} 置真，只有 {@code system.done} 会复位；
     * {@code system.error} 只追加气泡不复位。该分支未占位（ticket 为 NONE），
     * finally 的兜底不会触发。漏发 done 会让输入面板永久停在流式态：停止按钮常亮、
     * 占位符不消失，后续所有输入被当作 steer 插话，用户只能刷新页面。</p>
     */
    @Test
    void hitlRejectBranchEmitsTerminalPacket() throws IOException {
        String source = readProjectFile("src/main/java/org/noear/solon/codecli/api/web/WebChatInputHandler.java");
        // 拒收分支在 onChatInput（有 WS 事件出口的那个），不是 IM 用的 safeChatInput
        String onInput = source.substring(source.indexOf("void onChatInput(WorkspaceContext"));
        onInput = onInput.substring(0, onInput.indexOf("boolean safeChatInput(WorkspaceContext"));

        // 4.2.3 起拒收判定来自 engine.admit 的 REJECT（值域与细节由引擎给出）
        int reject = onInput.indexOf("AdmitDecision.Kind.REJECT");
        assertTrue(reject > 0, "拒收分支必须按 admit 的 REJECT 判决");
        int errorEmit = onInput.indexOf("ofError(admit.getRejectReason()", reject);
        int doneEmit = onInput.indexOf("emitDoneGuarded", reject);
        int queueBranch = onInput.indexOf("AdmitDecision.Kind.QUEUE", reject);

        assertTrue(errorEmit > reject, "HITL 拒收必须先给出可读错误（原因与细节取自 AdmitDecision）");
        assertTrue(doneEmit > errorEmit,
                "拒收分支必须在 return 前发终态包（emitDoneGuarded），否则前端 isStreaming 永久为真");
        assertTrue(doneEmit < queueBranch,
                "终态包必须落在拒收分支内，不能落到后面的排队分支里");
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

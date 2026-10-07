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

    /** 忙态默认走插话（SteerInterceptor.steer），而不是直接入队。 */
    @Test
    void busySessionDefaultsToSteer() throws IOException {
        String source = readProjectFile("src/main/java/org/noear/solon/codecli/api/web/WebChatInputHandler.java");

        assertTrue(source.contains("SteerInterceptor.steer(session, null, null, input"),
                "忙态普通输入应默认插话（SteerInterceptor.steer）");
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

        int steer = source.indexOf("SteerInterceptor.steer(session, null, null, input");
        int enqueue = source.indexOf("int position = SessionQueue.enqueue(session, input, source, sourceUserId, replyTarget, messageId)");
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

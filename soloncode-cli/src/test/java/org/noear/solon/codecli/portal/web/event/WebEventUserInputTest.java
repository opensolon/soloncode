package org.noear.solon.codecli.portal.web.event;

import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.portal.web.event.payload.SystemUserInputPayload;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端推送用户消息的来源展示契约测试。
 *
 * @author noear
 */
class WebEventUserInputTest {
    @Test
    void userInputEventCarriesSourceLabel() {
        WebEvent<SystemUserInputPayload> event = WebEvent.ofUserInput("执行任务", "Loop");

        assertEquals(WebEventNames.SYSTEM_USER_INPUT, event.getEvent());
        assertEquals("执行任务", event.getPayload().getText());
        assertEquals("Loop", event.getPayload().getSource());
        assertEquals("Loop", event.getPayload().getSourceLabel());
    }

    @Test
    void webSourceKeepsHiddenLabelContract() {
        WebEvent<SystemUserInputPayload> event = WebEvent.ofUserInput("普通消息", null);

        assertEquals("Web", event.getPayload().getSourceLabel());
    }

    @Test
    void realtimeRendererFallsBackToRawSource() throws IOException {
        String javascript = resourceText("/static/js/app-streaming.js");

        assertTrue(javascript.contains("p.sourceLabel || p.source"),
                "实时消息应在展示标签缺失时回退到原始发起源");
    }

    /** 来源标签直通 source：展示文案由调用方（渠道侧）一处决定，服务端不再做映射，前端也不再转换。 */
    @Test
    void channelSourcesPassThroughAsLabels() {
        assertEquals("WeChat", WebEvent.toSourceLabel("WeChat"));
        assertEquals("Feishu", WebEvent.toSourceLabel("Feishu"));
        assertEquals("DingTalk", WebEvent.toSourceLabel("DingTalk"));
        assertEquals("Loop", WebEvent.toSourceLabel("Loop"));
        assertEquals(WebEvent.SOURCE_LABEL_WEB, WebEvent.toSourceLabel(""));
        // 未知来源同样原样返回，新通道免改后端即可上屏
        assertEquals("MyBot", WebEvent.toSourceLabel("MyBot"));
    }

    /** 直通意味着不再归一大小写：传什么就显示什么，故渠道侧必须传展示态名称。 */
    @Test
    void sourceLabelPreservesCaseVerbatim() {
        assertEquals("wechat", WebEvent.toSourceLabel("wechat"));
        assertEquals("DINGTALK", WebEvent.toSourceLabel("DINGTALK"));
        assertEquals("steer", WebEvent.toSourceLabel("steer"));
    }

    /** 前端识别插话不能依赖展示文案（尤其中文串）：文案一改或换语言就会静默失配。 */
    @Test
    void steerDetectionUsesSourceKeyNotLocalizedText() throws IOException {
        String javascript = resourceText("/static/js/app-message.js");

        assertTrue(javascript.contains("var STEER_SOURCE = 'steer'"),
                "插话识别应以后端来源键（steer）为准");
        assertTrue(javascript.contains("sourceKey === STEER_SOURCE"),
                "插话归一应按小写归一后的来源键比较");
        assertFalse(javascript.contains("STEER_SOURCE_LABEL = '插话'"),
                "不应把中文文案当作插话识别键");
    }

    /** 来源徽标只呈现服务端下发值：前端一旦重新接上 i18n，多语言下就会与历史/其他通道自相矛盾。 */
    @Test
    void sourceBadgeRendersServerLabelWithoutI18n() throws IOException {
        String javascript = resourceText("/static/js/app-message.js");

        assertFalse(javascript.contains("streaming.steerTag"),
                "来源徽标应原样使用服务端 sourceLabel，不得再经 i18n 转换");
        assertTrue(javascript.contains(".addClass('msg-source-label').text(sourceLabel)"),
                "徽标文本应直接取未加工的 sourceLabel");
    }

    private String resourceText(String path) throws IOException {
        InputStream input = WebEventUserInputTest.class.getResourceAsStream(path);
        if (input == null) {
            throw new IOException("Missing test resource: " + path);
        }
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) >= 0) {
                out.write(buffer, 0, len);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

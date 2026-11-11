package org.noear.solon.codecli.api.web.event.payload;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tool.end 事件的线上字段契约。
 *
 * <p>前端 {@code app-message.js} / {@code app-streaming.js} 依赖 JSON 里的 {@code isError}
 * 判断工具成败（不再解析提示文案）。字段名由序列化器按字段名产出，一旦序列化器行为变化
 * （例如改用 getter 推导成 {@code error}）前端会静默地全部判成成功——不报错、不崩溃。
 * 这里用 {@link ONode#serialize(Object)}（与 {@code WebGate} 发流同一入口）锁死字段名。</p>
 */
public class ToolEndPayloadJsonContractTest {

    @Test
    public void failedToolSerializesIsErrorField() {
        ToolEndPayload payload = ToolEndPayload.builder()
                .callId("c1")
                .name("edit")
                .result("失败：文件不存在")
                .isError(true)
                .build();

        String json = ONode.serialize(payload);

        // 字段名必须是 isError（不是 error），值为 true
        assertTrue(json.contains("\"isError\":true"), "tool.end 必须输出 isError=true：" + json);
        assertFalse(json.contains("\"error\":"), "不得输出 error 字段：" + json);
    }

    @Test
    public void okToolSerializesIsErrorFalse() {
        ToolEndPayload payload = ToolEndPayload.builder()
                .callId("c2")
                .name("edit")
                .result("成功完成")
                .isError(false)
                .build();

        String json = ONode.serialize(payload);

        assertTrue(json.contains("\"isError\":false"), "tool.end 必须输出 isError=false：" + json);
    }
}

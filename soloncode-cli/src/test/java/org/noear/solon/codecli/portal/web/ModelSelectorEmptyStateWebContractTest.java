package org.noear.solon.codecli.portal.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Web 模型选择器空状态提示样式契约测试。 */
public class ModelSelectorEmptyStateWebContractTest {
    @Test
    void emptyModelStateMarksTextAndAdjacentIconAsDanger() throws IOException {
        String javascript = resourceText("/static/js/app-history.js");
        String css = resourceText("/static/css/app.css");

        assertTrue(javascript.contains("$('#chatModelCurrent, #newChatModelCurrent').toggleClass('is-empty', isEmpty);"),
                "无模型时必须标记模型选择器文字");
        assertTrue(javascript.contains("$('#chatModelSelector, #newChatModelSelector').closest('.model-selector-group').toggleClass('is-empty', isEmpty);"),
                "无模型时必须同步标记模型选择器分组");
        assertTrue(css.contains(".model-selector-current.is-empty .model-name,\n.model-selector-group.is-empty > .toolbar-setting-icon { color: #e74c3c; }"),
                "无模型提示文字与相邻模型图标必须使用同一红色");
    }

    private static String resourceText(String path) throws IOException {
        try (InputStream in = ModelSelectorEmptyStateWebContractTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing resource: " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) >= 0; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

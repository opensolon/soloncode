package org.noear.solon.codecli.portal.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 心智记忆面板关闭链路的静态资源契约测试。
 *
 * @author noear
 */
public class MemoryPanelWebContractTest {
    @Test
    void organizeAction_completelyClosesViewerBeforeFillingCommand() throws IOException {
        String javascript = resourceText("/static/js/app-memory.js");

        assertTrue(javascript.contains("gitViewer.style.display = 'none';"),
                "关闭记忆面板必须隐藏共享 Viewer，不能只移除样式类");
        assertTrue(javascript.contains("if (chatView) chatView.style.display = '';"),
                "关闭记忆面板后必须清除聊天视图的内联隐藏状态");
        assertTrue(javascript.contains("if (newChatView) newChatView.style.display = '';"),
                "关闭记忆面板后必须清除欢迎视图的内联隐藏状态");

        int organizeHandler = javascript.indexOf("gitViewerMemOrganize.addEventListener('click'");
        int closeCall = javascript.indexOf("closeOverlay();", organizeHandler);
        int fillCall = javascript.indexOf("window.fillMemoryText();", organizeHandler);
        assertTrue(organizeHandler >= 0 && closeCall > organizeHandler && fillCall > closeCall,
                "整理记忆必须先完整关闭面板，再向当前输入框填入命令");
    }

    private String resourceText(String path) throws IOException {
        InputStream input = MemoryPanelWebContractTest.class.getResourceAsStream(path);
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

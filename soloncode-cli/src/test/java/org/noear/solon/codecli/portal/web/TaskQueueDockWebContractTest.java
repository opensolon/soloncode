package org.noear.solon.codecli.portal.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务排队应靠近聊天输入区，并与其它输入浮层保持一致的会话级交互。 */
public class TaskQueueDockWebContractTest {
    @Test
    public void queueDockLivesAboveChatInputWithAccessibleControls() throws IOException {
        String html = resourceText("/static/web.html");
        int inputWrap = html.indexOf("<div class=\"input-wrap\">");
        int queueDock = html.indexOf("id=\"chatQueueDock\"");
        int inputBox = html.indexOf("id=\"chatDropZone\"");
        int taskTab = html.indexOf("id=\"tabContentTasks\"");

        assertTrue(inputWrap >= 0 && queueDock > inputWrap && inputBox > queueDock,
                "队列卡片必须位于聊天输入框上方");
        assertTrue(taskTab > queueDock, "队列卡片不能继续嵌套在右侧任务 Tab 中");
        assertTrue(html.contains("aria-controls=\"chatQueueList\""));
        assertTrue(html.contains("aria-expanded=\"false\""));
        assertTrue(html.contains("id=\"chatQueueList\" role=\"list\""));
    }

    @Test
    public void queueDockIsSessionScopedAndDoesNotOpenWorkspacePanel() throws IOException {
        String base = resourceText("/static/js/app-base.js");
        String streaming = resourceText("/static/js/app-streaming.js");

        assertTrue(base.contains("this.queueDockExpanded = false"));
        assertTrue(streaming.contains("var expanded = !!(sess && sess.queueDockExpanded)"));
        assertTrue(streaming.contains("toggleEl.setAttribute('aria-expanded', expanded ? 'true' : 'false')"));
        assertTrue(streaming.contains("I18n.t('streaming.queueWaiting', {n: q.length})"),
                "空闲恢复队列必须提示用户按 Enter 继续，而不能误报自动发送");
        assertFalse(streaming.contains("window.expandFilerPanel()"),
                "排队不应再打断用户并自动展开右侧工作区");
        assertFalse(streaming.contains("_queueDockExpanded"),
                "展开状态不能由所有会话共享");
    }

    @Test
    public void queueDockIsMutuallyExclusiveAndMobileFriendly() throws IOException {
        String history = resourceText("/static/js/app-history.js");
        String loop = resourceText("/static/js/app-loop.js");
        String css = resourceText("/static/css/app.css");

        assertTrue(history.contains("window.collapseQueueDock"));
        assertTrue(history.contains("e.key === 'Escape' && hasOpenToolbarPanel()"),
                "Esc 必须优先关闭浮层，不能误删队尾任务");
        assertTrue(loop.contains("window.hideLoopPanel = hideLoopPanel"),
                "互斥关闭循环面板时必须同步其内部状态");
        assertTrue(css.contains(".queue-strip {\n    width: 100%;\n    max-width: 780px"));
        assertTrue(css.contains("max-height: min(42vh, 320px)"));
        assertTrue(css.contains("@media (prefers-reduced-motion: reduce)"));
        assertTrue(css.contains(".queue-item-actions button:focus-visible"));
        assertTrue(css.contains(".queue-item {\n    display: flex;\n    align-items: center;"),
                "队列正文、序号和右侧操作必须纵向居中对齐");
        assertTrue(css.contains(".queue-item-actions button {\n    min-height: 26px;\n    display: inline-flex;\n    align-items: center;"),
                "右侧操作按钮的文字必须在按钮内部纵向居中");
    }

    private static String resourceText(String path) throws IOException {
        try (InputStream in = TaskQueueDockWebContractTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing resource: " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) >= 0; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

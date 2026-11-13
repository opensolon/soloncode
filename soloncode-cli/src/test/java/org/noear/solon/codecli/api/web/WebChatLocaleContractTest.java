package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.Test;
import org.noear.solon.annotation.Param;
import org.noear.solon.codecli.api.web.controller.ChatWebController;
import org.noear.solon.codecli.api.web.controller.GitWebController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本轮对话语言由前端传入：发消息、UI 动作回传、入队三处请求都带上当前界面语言（I18n.getLocale()），
 * 后端对应入口按 {@code locale} 参数接收。两端任何一侧漏掉，语言都会静默回落到引擎默认语言。
 */
public class WebChatLocaleContractTest {

    @Test
    public void frontendSendsCurrentUiLocale() throws IOException {
        String streaming = resourceText("/static/js/app-streaming.js");

        assertTrue(streaming.contains("I18n.getLocale()"), "界面语言应取 I18n 当前生效语言（跟随系统时为解析后的语言）");
        assertTrue(streaming.contains("formData.append('locale', locale)"), "聊天输入必须带 locale");

        int uiAction = streaming.indexOf("$.post('/web/chat/ui_action'");
        int uiActionEnd = streaming.indexOf(".fail(", uiAction);
        assertTrue(uiAction >= 0 && streaming.substring(uiAction, uiActionEnd).contains("locale: currentUiLocale()"),
                "UI 动作回传必须带 locale");

        int queue = streaming.indexOf("fetch('/web/chat/queue/item'");
        int queueEnd = streaming.indexOf(".then(", queue);
        assertTrue(queue >= 0 && streaming.substring(queue, queueEnd).contains("locale: queuedItem.locale"),
                "入队必须带入队时的 locale");
    }

    /**
     * 生成提交摘要同样带界面语言：否则摘要、git-summary 子代理提示词与协议指令都回落到引擎默认语言
     */
    @Test
    public void gitSummarySendsCurrentUiLocale() throws IOException {
        String git = resourceText("/static/js/app-git.js");

        int summary = git.indexOf("fetch(gitUrl('/web/chat/git/summary')");
        int summaryEnd = git.indexOf(".then(", summary);
        assertTrue(summary >= 0 && git.substring(summary, summaryEnd).contains("'&locale=' + encodeURIComponent("),
                "生成摘要必须带 locale");
        assertTrue(git.substring(summary, summaryEnd).contains("currentUiLocale()"), "取值与发消息同一口径");
    }

    @Test
    public void backendAcceptsLocaleParam() {
        assertTrue(hasParam(ChatWebController.class, "chat_input", "locale"));
        assertTrue(hasParam(ChatWebController.class, "chat_ui_action", "locale"));
        assertTrue(hasParam(GitWebController.class, "gitSummary", "locale"));
    }

    private static boolean hasParam(Class<?> clz, String methodName, String paramName) {
        for (Method m : clz.getMethods()) {
            if (m.getName().equals(methodName)) {
                for (Parameter p : m.getParameters()) {
                    Param anno = p.getAnnotation(Param.class);
                    if (anno != null && paramName.equals(anno.value())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String resourceText(String path) throws IOException {
        try (InputStream in = WebChatLocaleContractTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing resource: " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) >= 0; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

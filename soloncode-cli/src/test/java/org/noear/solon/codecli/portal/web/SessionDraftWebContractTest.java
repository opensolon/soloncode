package org.noear.solon.codecli.portal.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Web 未发送草稿必须按会话进行页面内存隔离。 */
public class SessionDraftWebContractTest {
    @Test
    void textAndAttachmentsAreOwnedBySessionState() throws IOException {
        String base = resourceText("/static/js/app-base.js");
        String ui = resourceText("/static/js/app-ui.js");
        String streaming = resourceText("/static/js/app-streaming.js");

        assertTrue(base.contains("this.draftText = '';"), "文本草稿必须保存在 SessionState");
        assertTrue(base.contains("this.draftFiles = [];"), "附件草稿必须保存在 SessionState");
        assertTrue(base.contains("syncActiveSessionDraft(inChatMode ? chatInput : newChatInput);"),
                "切换会话前必须保存当前可见输入框");
        assertTrue(base.contains("restoreSessionDraft(sess);"), "激活会话后必须恢复该会话草稿");
        assertTrue(base.indexOf("syncActiveSessionDraft(inChatMode ? chatInput : newChatInput);")
                        < base.indexOf("activeSessionId = sessionId;"),
                "旧草稿必须在 activeSessionId 改变前保存");
        assertTrue(ui.contains("getActiveDraftFiles()"), "附件预览必须读取活动会话草稿");
        assertTrue(streaming.contains("var draftFiles = getActiveDraftFiles();"),
                "发送前必须从活动会话生成附件快照");
        assertFalse(base.contains("var pendingFiles = []"), "不得继续用全局附件数组跨会话共享");
        assertFalse(ui.contains("pendingFiles"), "附件交互不得绕过 SessionState");
        assertFalse(streaming.contains("pendingFiles"), "发送流程不得绕过 SessionState");
    }

    @Test
    void switchingViewsKeepsDraftBoundToOriginalSession() throws IOException {
        String ui = resourceText("/static/js/app-ui.js");
        String history = resourceText("/static/js/app-history.js");

        int activateHistory = history.indexOf("setActiveSession(entry.sessionId);");
        int enterChat = history.indexOf("if (!inChatMode) switchToChatMode();", activateHistory);
        assertTrue(activateHistory >= 0 && enterChat > activateHistory,
                "从欢迎页选择历史会话时必须先保存并切换会话，再改变输入视图");

        int createNewId = ui.indexOf("var newSessionId = 'web-' + Date.now().toString(36);");
        int activateNew = ui.indexOf("setActiveSession(newSessionId);", createNewId);
        int enterWelcome = ui.indexOf("inChatMode = false;", activateNew);
        assertTrue(createNewId >= 0 && activateNew > createNewId && enterWelcome > activateNew,
                "进入新对话时必须先保存旧聊天草稿，再切换到欢迎页输入框");
        assertTrue(ui.contains("syncActiveSessionDraft(newChatInput);\n    inChatMode = true;\n    restoreSessionDraft(getActiveSessionDraft());"),
                "同一会话从欢迎页进入聊天页时必须同步逻辑草稿");
    }

    @Test
    void lateFileReaderCallbackCannotLeakOrReviveDeletedSession() throws IOException {
        String base = resourceText("/static/js/app-base.js");
        String ui = resourceText("/static/js/app-ui.js");

        assertTrue(ui.contains("var sessionId = targetSessionId || activeSessionId || SESSION_ID;"),
                "选择附件时必须快照所属会话");
        assertTrue(ui.contains("sessionMap[target.sessionId] !== target"),
                "异步回调必须校验原 SessionState 对象，不能只校验可复用的会话 ID");
        assertTrue(ui.contains("target.draftGeneration !== generation"),
                "发送或清空后，旧 FileReader 回调必须失效");
        assertTrue(base.contains("sess.draftGeneration++;"),
                "替换附件草稿时必须推进生命周期代际");
        assertTrue(ui.contains("if (!appendDraftFile(target, item)) return;"),
                "图片必须在异步预览前加入草稿，预占名额且支持立即发送");
        assertFalse(ui.contains("reader.onload = function(evt) {\n            getOrCreateSession"),
                "FileReader 回调不得复活已删除会话");
    }

    private static String resourceText(String path) throws IOException {
        try (InputStream in = SessionDraftWebContractTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing resource: " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) >= 0; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

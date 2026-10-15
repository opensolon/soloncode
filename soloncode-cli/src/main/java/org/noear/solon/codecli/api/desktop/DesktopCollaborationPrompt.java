package org.noear.solon.codecli.api.desktop;

import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.core.util.IoUtil;

import java.io.IOException;
import java.io.InputStream;

/** 桌面请求级协作规则：补充角色提示词，不改变共享 Agent 或用户消息。 */
final class DesktopCollaborationPrompt implements ReActInterceptor {
    private static final String PROMPT = loadPrompt();
    private static final DesktopCollaborationPrompt INSTANCE = new DesktopCollaborationPrompt();

    private DesktopCollaborationPrompt() {
    }

    static void applyTo(ReActOptionsAmend options) {
        options.interceptorAdd(INSTANCE);
    }

    @Override
    public void onReasonStart(ReActTrace trace, StringBuilder systemPromptBuf) {
        systemPromptBuf.append("\n\n").append(PROMPT);
    }

    private static String loadPrompt() {
        try (InputStream input = DesktopCollaborationPrompt.class.getResourceAsStream("/prompts/desktop-collaboration.md")) {
            if (input == null) throw new IllegalStateException("Desktop collaboration prompt is missing");
            String prompt = IoUtil.transferToString(input, "utf-8").trim();
            if (prompt.isEmpty()) throw new IllegalStateException("Desktop collaboration prompt is empty");
            return prompt;
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load desktop collaboration prompt", error);
        }
    }
}

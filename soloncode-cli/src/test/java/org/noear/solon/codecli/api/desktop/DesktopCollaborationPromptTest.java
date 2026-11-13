package org.noear.solon.codecli.api.desktop;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桌面协作规则是模型可见的系统提示词补充：与协议指令同一语言（中文用中文，其余用英文）
 */
class DesktopCollaborationPromptTest {
    @Test
    void rulesFollowRunLocale() {
        String zh = DesktopCollaborationPrompt.rulesFor(Locale.SIMPLIFIED_CHINESE);
        assertTrue(zh.startsWith("## 桌面协作预置规则"), zh);
        assertEquals(zh, DesktopCollaborationPrompt.rulesFor(Locale.TRADITIONAL_CHINESE));

        String en = DesktopCollaborationPrompt.rulesFor(Locale.ENGLISH);
        assertTrue(en.startsWith("## Desktop collaboration rules"), en);
        assertFalse(en.matches("(?s).*[\\u4e00-\\u9fa5].*"), "英文规则里不应混入中文: " + en);
        // 未知语言落英文（与内置子代理、文案资源一致）
        assertEquals(en, DesktopCollaborationPrompt.rulesFor(Locale.JAPANESE));
    }
}

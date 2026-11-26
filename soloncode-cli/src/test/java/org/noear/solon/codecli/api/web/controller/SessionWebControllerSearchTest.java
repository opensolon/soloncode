package org.noear.solon.codecli.api.web.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the cross-session message search helpers in {@link SessionWebController}.
 *
 * <p>Searching chat history used to mean scrolling through every conversation
 * by hand. The snippet helper decides what a hit shows, so its boundary
 * behaviour is worth pinning down.</p>
 */
public class SessionWebControllerSearchTest {
    @Test
    public void snippetAround_returnsShortContentVerbatim() {
        String content = "很短的内容";

        assertEquals(content, SessionWebController.snippetAround(content, "内容"));
    }

    @Test
    public void snippetAround_centresOnKeyword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("前缀内容第").append(i).append("段。");
        }
        sb.append("这里是关键字附近的内容");
        for (int i = 0; i < 40; i++) {
            sb.append("后缀内容第").append(i).append("段。");
        }

        String out = SessionWebController.snippetAround(sb.toString(), "关键字");

        assertTrue(out.startsWith("..."), "关键词不在开头时应带前省略号");
        assertTrue(out.endsWith("..."), "关键词不在结尾时应带后省略号");
        assertTrue(out.contains("这里是关键字附近的内容"));
        assertTrue(out.length() < sb.length(), "片段应短于原文");
    }

    @Test
    public void snippetAround_noLeadingEllipsisWhenKeywordAtStart() {
        StringBuilder sb = new StringBuilder("关键字在最前面");
        for (int i = 0; i < 40; i++) {
            sb.append("后面还有很多无关内容第").append(i).append("段。");
        }

        String out = SessionWebController.snippetAround(sb.toString(), "关键字");

        assertFalse(out.startsWith("..."));
        assertTrue(out.endsWith("..."));
        assertTrue(out.startsWith("关键字在最前面"));
    }

    @Test
    public void snippetAround_truncatesWhenKeywordAbsent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("无关内容第").append(i).append("段。");
        }

        String out = SessionWebController.snippetAround(sb.toString(), "找不到的词");

        assertEquals(160, out.length());
        assertFalse(out.contains("..."));
    }

    @Test
    public void snippetAround_matchesCaseInsensitively() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("前缀第").append(i).append("段。");
        }
        sb.append("GOSUMDB=off 关掉了校验");
        for (int i = 0; i < 40; i++) {
            sb.append("后缀第").append(i).append("段。");
        }

        String out = SessionWebController.snippetAround(sb.toString(), "gosumdb");

        assertTrue(out.contains("GOSUMDB=off"));
    }

    @Test
    public void snippetAround_handlesEmptyAndNull() {
        assertEquals("", SessionWebController.snippetAround("", "kw"));
        assertNull(SessionWebController.snippetAround(null, "kw"));
    }
}

package org.noear.solon.codecli.portal.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Web 输入框命令、子代理和技能补全契约测试。
 *
 * @author noear
 */
public class InputCompletionWebContractTest {
    @Test
    void inlineAgentAndSkillCompletion_tracksAndReplacesCurrentToken() throws IOException {
        String javascript = resourceText("/static/js/app-history.js");

        assertTrue(javascript.contains("function findCompletionToken(value, cursorPos)"),
                "补全必须按光标定位当前 token");
        assertTrue(javascript.contains("function replaceCompletionToken(value, context, name)"),
                "候选选择必须按记录的 token 范围替换");
        assertTrue(javascript.contains("([@$])([A-Za-z0-9._-]*)$"),
                "@agent 与 $skill 必须支持正文 token 边界补全");
        assertTrue(javascript.contains("val.charAt(0) === '/'"),
                "斜杠命令必须继续限制在输入开头");
        assertTrue(javascript.contains("cmdTokenContext = tokenContext;"),
                "显示候选时必须保存当前 token 上下文");
        assertTrue(javascript.contains("var currentContext = findCompletionToken(inputEl.value, inputEl.selectionStart)"),
                "选择候选时必须按最新光标位置重新定位 token");
        assertTrue(javascript.contains("cmdTokenContext = null;"),
                "关闭补全后必须清理 token 上下文");

        assertFalse(javascript.contains("for (var i = val.length - 1; i >= 0; i--)"),
                "不能再从全文末尾反查触发符，否则多个提及会替换错位");
        assertFalse(javascript.contains("val.indexOf('/') === 0 || val.indexOf('@') === 0"),
                "不能继续要求整条输入必须以 @ 或 $ 开头");
    }

    @Test
    void toolbarKeepsSlashAsCommandAndInsertsInlineMentionsWithoutTrailingSpace() throws IOException {
        String javascript = resourceText("/static/js/app-history.js");

        assertTrue(javascript.contains("if (prefix === '/')"),
                "工具栏必须区分斜杠命令与行内提及");
        assertTrue(javascript.contains("if (val.trim())"),
                "已有正文时工具栏不得插入不可执行的行内斜杠命令");
        assertTrue(javascript.contains("before + boundary + prefix + after"),
                "@ 与 $ 按钮应在当前光标边界插入触发符");
        assertFalse(javascript.contains("textBefore + prefix + ' ' + textAfter"),
                "触发符后不能预插空格，否则补全面板无法继续过滤名称");
    }

    @Test
    void skillSearchKeepsStableCandidateMapping() throws IOException {
        String javascript = resourceText("/static/js/app-history.js");

        assertTrue(javascript.contains("data-base-index"),
                "技能搜索必须保存候选的稳定原始索引");
        assertTrue(javascript.contains("allSkillItems[parseInt($item.attr('data-base-index'))]"),
                "多次搜索过滤后仍应映射到正确技能");
        assertTrue(javascript.contains(".cmd-complete-item:visible"),
                "键盘导航只能遍历当前可见候选");
    }

    private String resourceText(String path) throws IOException {
        InputStream input = InputCompletionWebContractTest.class.getResourceAsStream(path);
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

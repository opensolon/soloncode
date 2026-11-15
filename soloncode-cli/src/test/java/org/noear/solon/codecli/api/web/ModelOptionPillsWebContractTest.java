package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「思考模式 / 推理强度」两行药丸的共享层契约。
 *
 * <p>历史问题：输入面板（web.html + app-history.js）与自动任务编辑器（app-automation.js）各写一套，
 * 结果自动任务那边根本没有这两个维度。现在标记由 app-base.js 的 {@code window.ModelOptions} 唯一产出，
 * 三处只负责“状态存哪里、怎么持久化”，本测试锁住这条边界。</p>
 */
public class ModelOptionPillsWebContractTest {
    @Test
    void sharedFooterMarkupAndPaintingLiveInAppBase() throws IOException {
        String base = resourceText("/static/js/app-base.js");

        assertTrue(base.contains("window.ModelOptions = (function () {"),
                "思考模式 / 推理强度必须收敛到 app-base.js 的共享层");
        assertTrue(base.contains("function footerHtml() {"),
                "共享层必须能产出两行标记，供非输入面板的下拉复用");
        assertTrue(base.contains("function paint(root, state, meta) {"),
                "共享层必须提供统一绘制入口（显隐 + 高亮 + 按模型能力隐藏无效档位）");
        // 与后端 ReasoningSupportUtil.clampEffort 同序：max → high → medium → low 就近降级
        assertTrue(base.contains("var EFFORT_ORDER = ['max', 'high', 'medium', 'low'];"),
                "档位收敛顺序必须与后端一致");
        assertTrue(base.contains("if (!supports(meta)) return '';"),
                "不支持推理的模型必须清空档位，否则会写入引擎不认的值");
    }

    @Test
    void chatPanelDelegatesInsteadOfKeepingItsOwnCopy() throws IOException {
        String history = resourceText("/static/js/app-history.js");

        assertTrue(history.contains("window.ModelOptions.paint($dropdown, {thinking: getSelectedThinking(), effort: userEffort}, meta);"),
                "输入面板必须改为委托共享层绘制，不能再自己遍历按钮");
        assertTrue(history.contains("return window.ModelOptions.clampEffort(effort, meta);"),
                "输入面板的档位收敛也必须走共享实现");
        assertTrue(history.contains("return window.ModelOptions.triggerLabel(displayName, effort, thinkingMode, showDepth);"),
                "触发器文案（短名 · on/off · 档位）必须与自动任务逐字一致");
    }

    @Test
    void automationEditorRendersTheSameFooterAndPersistsBothDimensions() throws IOException {
        String automation = resourceText("/static/js/app-automation.js");

        assertTrue(automation.contains("window.ModelOptions.footerHtml()"),
                "自动任务的模型下拉必须复用同一份两行标记");
        assertTrue(automation.contains("var pill = e.target.closest ? e.target.closest('.model-option-pills button') : null;"),
                "药丸点击必须与条目点击分开处理（不能当成选项而关掉浮层）");
        assertTrue(automation.contains("applyModelOptionPill(row, box, pill);"),
                "点击药丸必须写入该任务行的状态");
        assertTrue(automation.contains("row.setAttribute('data-thinking', t.thinkingMode || '');"),
                "编辑既有任务时必须回显思考模式");
        assertTrue(automation.contains("row.setAttribute('data-effort', t.reasoningEffort || '');"),
                "编辑既有任务时必须回显推理强度");
        assertTrue(automation.contains("reasoningEffort: row.getAttribute('data-effort') || '',"),
                "保存必须提交推理强度，否则定时轮次拿不到");
        assertTrue(automation.contains("thinkingMode: row.getAttribute('data-thinking') || '',"),
                "保存必须提交思考模式");
        // 换模型时档位要跟着目标模型收敛（与输入面板 selectModel 同规则）
        assertTrue(automation.contains("if (kind === 'model') clampModelOptionsFor(row);"),
                "换模型后必须按新模型能力收敛档位");
    }

    @Test
    void sameMarkupStructureInStaticHtmlAndSharedBuilder() throws IOException {
        String html = resourceText("/static/web.html");
        String base = resourceText("/static/js/app-base.js");

        // 静态标记与共享层构建的标记必须使用同一套 class / data 属性，否则 CSS 会分叉
        for (String marker : new String[]{
                "model-option-row model-thinking-row",
                "model-option-row model-reasoning-row",
                "model-option-pills\" data-kind=\"thinking\"",
                "model-option-pills\" data-kind=\"reasoning\""}) {
            assertTrue(html.contains(marker), "web.html 缺少标记: " + marker);
            assertTrue(base.contains(marker), "共享层生成的标记与 web.html 不一致: " + marker);
        }
        // 取值集合也必须一致：三个思考模式 + 五个档位
        for (String marker : new String[]{
                "data-mode=\"auto\"", "data-mode=\"on\"", "data-mode=\"off\"",
                "data-effort=\"auto\"", "data-effort=\"low\"", "data-effort=\"medium\"",
                "data-effort=\"high\"", "data-effort=\"max\""}) {
            assertTrue(html.contains(marker), "web.html 缺少标记: " + marker);
        }
        assertTrue(base.contains("['auto', 'on', 'off']"), "共享层必须覆盖三个思考模式");
        assertTrue(base.contains("['auto', 'low', 'medium', 'high', 'max']"), "共享层必须覆盖五个档位");
        assertTrue(base.contains("data-mode=\"' + mode + '\""), "共享层必须用同一套 data-mode 属性名");
        assertTrue(base.contains("data-effort=\"' + effort + '\""), "共享层必须用同一套 data-effort 属性名");
    }

    private static String resourceText(String path) throws IOException {
        try (InputStream in = ModelOptionPillsWebContractTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing resource: " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) >= 0; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

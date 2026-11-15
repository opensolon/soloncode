package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动任务编辑器的模型选择器与输入面板的「体验一致」契约。
 *
 * <p>历史问题（用户反馈三次仍在）：自动任务那边
 * ① 触发器宽度被定死在 140px，长模型名硬截断，不像输入面板那样弹性伸缩；
 * ② 下拉里的思考模式 / 推理强度永远停在 auto，而输入面板首屏显示的是全局默认值（如 on · max）。
 * 本测试锁住这两条，改动时先看这里。</p>
 */
public class AutomationModelSelectorParityTest {
    @Test
    void triggerWidthFollowsChatPanelInsteadOfBeingFixed() throws IOException {
        String css = resourceText("/static/css/app.css");

        // 定死 140px 是“显示短一截且不随内容伸缩”的直接原因
        assertFalse(css.contains(".auto-select-group .model-name { max-width: 140px;"),
                "自动任务模型触发器不能再定死 140px，应与输入面板同为弹性收缩");
        assertTrue(css.contains(".auto-select-group .model-name { max-width: 260px; min-width: 0; flex: 0 1 auto;"),
                "自动任务模型触发器必须与输入面板同一规则：260px 上限 + 可收缩");
    }

    @Test
    void dropdownMetricsMatchChatPanel() throws IOException {
        String css = resourceText("/static/css/app.css");

        // 与输入面板 .model-dropdown / .model-dropdown-footer / .model-option-pills 同值
        assertTrue(css.contains(".auto-select-group .model-dropdown.model-options-dropdown { width: 380px;"),
                "模型下拉宽度必须与输入面板一致（380px）");
        assertTrue(css.contains(".auto-select-group .model-options-footer { padding: 8px 10px 10px; gap: 8px; }"),
                "底部两行的内边距与行间距必须与输入面板一致");
        assertTrue(css.contains(".auto-select-group .model-option-label { min-width: 52px; }"),
                "标签列宽必须与输入面板一致，否则药丸起始位置对不齐");
        assertTrue(css.contains(".auto-select-group .model-option-pills button { padding: 3px 8px; font-size: var(--fs-xs); }"),
                "药丸尺寸与字号必须与输入面板一致");
    }

    @Test
    void unsetTaskShowsGlobalDefaultsLikeTheChatPanel() throws IOException {
        String automation = resourceText("/static/js/app-automation.js");

        // 默认值来源必须与输入面板同源：/web/chat/models 顶层的 thinkingMode / reasoningEffort
        assertTrue(automation.contains("modelBox.__defaultThinking = (data && data.thinkingMode) || '';"),
                "必须读取全局默认思考模式，否则界面永远停在 auto");
        assertTrue(automation.contains("modelBox.__defaultEffort = (data && data.reasoningEffort) || '';"),
                "必须读取全局默认推理强度");
        assertTrue(automation.contains("function effectiveOptions(box, row) {"),
                "“显示值”与“保存值”必须分开计算，不能为了显示就把默认值写进任务");
        assertTrue(automation.contains("window.ModelOptions.paint(box, {thinking: eff.thinking, effort: eff.effort}, eff.meta);"),
                "绘制必须使用实际生效值（未表态 → 全局默认）");
        assertTrue(automation.contains("window.ModelOptions.triggerLabel(displayName, eff.effort, eff.thinking, showDepth)"),
                "触发器上的档位后缀也必须用实际生效值，否则与输入面板不一致");
        // 只有“未表态”才回填默认值；显式 auto 必须保留，否则用户点了 auto 会跳回 max
        assertTrue(automation.contains("if (thinking === '' && box) thinking = box.__defaultThinking || '';"),
                "只有空串（未表态）才回填默认值");
        assertTrue(automation.contains("if (effort === '' && box) effort = window.ModelOptions.clampEffort(box.__defaultEffort || '', meta);"),
                "默认档位必须按目标模型能力收敛后再显示");
        // 保存语义不能被显示层污染
        assertTrue(automation.contains("reasoningEffort: row.getAttribute('data-effort') || '',"),
                "保存仍须提交任务行上的原始值（空串=跟随默认），不能提交显示用的默认值");
    }

    private static String resourceText(String path) throws IOException {
        try (InputStream in = AutomationModelSelectorParityTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing resource: " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) >= 0; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

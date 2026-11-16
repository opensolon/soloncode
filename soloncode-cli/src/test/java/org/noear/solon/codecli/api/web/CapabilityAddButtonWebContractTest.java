package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能 / 连接器选择器的「添加」按钮契约。
 *
 * <p>用户诉求：选择器面板里已有「不限制」，再加一个「添加」，点了直接跳到设置的对应
 * tab（技能 → 技能市场、连接器 → MCP 列表）。这里锁住四件容易回退的事：</p>
 * <ul>
 *   <li>底部标记只能有一份来源，不许自动任务页再手拼一套（历史问题复现三次）；</li>
 *   <li>「添加」只负责跳转，跳哪里必须由字段名决定，不能写死成某一个 tab；</li>
 *   <li>设置关闭后必须重拉能力选项，否则装完技能回到会话看不见新技能（按钮等于没用）；</li>
 *   <li>技能 tab 要能落到「技能市场」，否则用户还得再点一次。</li>
 * </ul>
 */
public class CapabilityAddButtonWebContractTest {

    @Test
    public void footerMarkupComesFromOnePlace() throws IOException {
        String capabilities = resourceText("/static/js/app-capabilities.js");
        String automation = resourceText("/static/js/app-automation.js");

        assertTrue(capabilities.contains("function footerHtml(field) {"),
                "底部的「添加」「不限制」必须由 app-capabilities.js 统一产出");
        assertTrue(capabilities.contains("'  <button type=\"button\" class=\"cap-add\" data-cap-add=\"' + esc(field) + '\"></button>'"),
                "「添加」按钮必须在共享 footer 里");
        assertTrue(capabilities.contains("footerHtml: footerHtml,"),
                "共享 footer 必须导出，否则自动任务页只能再拼一份");

        // 自动任务页曾经手拼过只有「不限制」的 footer，这次不允许回退
        assertTrue(automation.contains("? window.Capabilities.footerHtml(kind)"),
                "自动任务页必须调用共享 footer");
        assertFalse(automation.contains("model-dropdown-footer cap-footer\"><button type=\"button\" class=\"cap-clear\""),
                "自动任务页不许再手拼 cap-footer（少了「添加」，两处会再次长得不一样）");
        // 共享 footer 只产出空按钮，两处都必须在重绘时回填文案——漏一边就会出现空按钮（实测踩过）
        assertTrue(automation.contains("clearBtn.textContent = window.Capabilities ? window.Capabilities.clearText()"),
                "自动任务页必须在重绘时回填「不限制」文案，否则按钮是空的");
        assertTrue(capabilities.contains("widget.$dd.find('.cap-clear').text(t('chatcap.unrestricted'))"),
                "输入面板必须在重绘时回填「不限制」文案");
    }

    @Test
    public void addButtonTargetsTabByField() throws IOException {
        String capabilities = resourceText("/static/js/app-capabilities.js");
        String settings = resourceText("/static/js/app-settings.js");
        String skill = resourceText("/static/js/app-settings-skill.js");
        String web = resourceText("/static/web.html");

        // 字段 → 落点：连接器进 mcp，技能直接进技能市场
        assertTrue(capabilities.contains("return field === 'mcps' ? { tab: 'mcp', view: null } : { tab: 'skills', view: 'market' };"),
                "「添加」的去处必须按字段决定，不能写死某一个 tab");
        assertTrue(capabilities.contains("window.openSettingsTab(target.tab, target.view ? { view: target.view } : null);"),
                "必须复用既有 openSettingsTab 入口，并把子视图透传下去");

        // 点击「添加」前要先收起浮层，否则设置面板压在浮层上
        assertTrue(capabilities.contains("$root.removeClass('open');\n            openAdd(widget.dim.field);"),
                "点击「添加」必须先关闭选择器浮层");

        // 技能市场视图：入口 → 透传 → 落视图，三段都要在
        assertTrue(settings.contains("pendingSkillView = (opts && opts.view) || null;"),
                "openSettingsTab 必须接收并暂存子视图");
        assertTrue(settings.contains("window._skillModule.resetAndLoad(takeSkillView())"),
                "技能 tab 必须把子视图传给 resetAndLoad");
        assertTrue(skill.contains("switchView(view === 'market' ? 'market' : 'installed');"),
                "resetAndLoad 必须支持直接落到技能市场，否则用户点了「添加」还要自己再点一次");

        // 设置模块首次点击是延迟加载的，代理层不能把 opts 吞掉
        assertTrue(web.contains("function openSettingsAfterReady(tab, opts) {"),
                "延迟加载设置模块的代理必须保留子视图参数");
        assertTrue(web.contains("realOpenSettingsTab(tab, opts);"),
                "代理必须把子视图转发给真实实现");
    }

    @Test
    public void closingSettingsRefreshesCapabilityOptions() throws IOException {
        String capabilities = resourceText("/static/js/app-capabilities.js");
        String settings = resourceText("/static/js/app-settings.js");

        assertTrue(capabilities.contains("window.reloadCapabilities = function () {"),
                "重拉能力选项的入口必须存在");
        // 这个函数曾经是死代码：定义了但无人调用，装完技能回来下拉里看不到
        assertTrue(settings.contains("if (typeof window.reloadCapabilities === 'function') window.reloadCapabilities();"),
                "关闭设置时必须重拉技能 / 连接器选项，否则新装的技能不会出现在下拉里");
    }

    @Test
    public void addLabelExistsInEveryLocale() throws IOException {
        // 与前端 I18n.load 的可用集一致
        String[] locales = {"ar", "bn", "br", "bs", "da", "de", "en", "es", "fr", "gr", "it", "ja",
                "ko", "no", "pl", "ru", "th", "tr", "uk", "vi", "zh-CN", "zh-TW"};
        for (String locale : locales) {
            String json = resourceText("/static/i18n/" + locale + ".json");
            assertTrue(json.contains("\"chatcap.add\":"),
                    "语言包 " + locale + ".json 缺少 chatcap.add，「添加」按钮会显示裸 key");
            // 按钮常驻在下拉底部，文案不能是空串
            assertFalse(json.contains("\"chatcap.add\": \"\""),
                    "语言包 " + locale + ".json 的 chatcap.add 不能为空");
        }
    }

    private static String resourceText(String path) throws IOException {
        try (InputStream in = CapabilityAddButtonWebContractTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing resource: " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) >= 0; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

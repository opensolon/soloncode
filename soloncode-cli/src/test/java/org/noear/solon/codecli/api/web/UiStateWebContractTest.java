package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 界面状态「后端记忆」的前端契约测试（Gitee #IKJOCR）。
 *
 * <p>背景：localStorage 按 origin（scheme + host + <b>port</b>）隔离。Web 支持随机端口启动，
 * 端口一变 origin 就变，重启后浏览器给出空白的 localStorage ——
 * 表现就是「不会自动加载之前的对话」，主题/布局也一并回到默认。
 * 因此这些状态改由服务端承载（{@code /web/ui/state}，见 {@code js/ui-state.js}）。</p>
 *
 * <p>本测试锁定迁移后的接线，避免后续改动把 localStorage 又接回来：</p>
 * <ul>
 *   <li>三个入口页面都必须先加载 ui-state.js，再加载 i18n.js（语言在 i18n 初始化时就要能读到）；</li>
 *   <li>活动会话、主题、侧栏/文件树布局、活动标签页、语言、配置备份勾选不再读写 localStorage；</li>
 *   <li>字体与皮肤仍走 localStorage（它们的真相源本来就在 settings.json，本地只做首屏缓存）；</li>
 *   <li>门面必须能兜住「事件早于监听器注册」的竞态与页面卸载时的最后一次写入。</li>
 * </ul>
 *
 * @author noear
 */
public class UiStateWebContractTest {

    private static final String[] MANAGED_KEYS = {
            "chat-theme", "sidebar-collapsed", "sidebar-width",
            "files-collapsed", "files-width", "workspace-active-tab", "sc-locale"
    };

    @Test
    @DisplayName("三个入口页面都先加载 ui-state.js，再加载 i18n.js")
    void pagesLoadUiStateBeforeI18n() throws IOException {
        String[] pages = {"/static/web.html", "/static/admin.html", "/static/login-page.html"};

        for (String page : pages) {
            String html = resourceText(page);
            int uiState = html.indexOf("src=\"/js/ui-state.js\"");
            int i18n = html.indexOf("src=\"/js/i18n.js\"");

            assertTrue(uiState > 0, page + " 必须加载 js/ui-state.js（界面状态后端记忆门面）");
            assertTrue(i18n > 0, page + " 必须加载 js/i18n.js");
            assertTrue(uiState < i18n, page + " 中 ui-state.js 必须先于 i18n.js，否则语言拿不到服务端值");
        }
    }

    @Test
    @DisplayName("活动会话恢复走服务端记忆：不再用 localStorage")
    void activeSessionUsesServerState() throws IOException {
        String js = resourceText("/static/js/app-history.js");

        assertTrue(js.contains("UiState.set(getActiveSessionKey(), sessionId)"),
                "记住活动会话必须写入服务端记忆");
        assertTrue(js.contains("UiState.get(getActiveSessionKey(), null)"),
                "恢复活动会话必须读服务端记忆");
        assertTrue(js.contains("UiState.ready("),
                "服务端状态就绪后必须补一次恢复（会话列表与服务端状态谁后到都要覆盖）");
        assertTrue(js.contains("!historyLoaded || !UiState.isHydrated()"),
                "列表未就绪或状态未水合时必须等待，不能提前判定为「没有上次会话」");
        assertFalse(usesLocalStorage(js),
                "活动会话不得再依赖 localStorage（按 origin 隔离，随机端口重启即丢）");
    }

    @Test
    @DisplayName("主题/侧栏布局/活动标签页/语言走服务端记忆")
    void layoutStateUsesServerState() throws IOException {
        String ui = resourceText("/static/js/app-ui.js");
        assertTrue(ui.contains("UiState.get('chat-theme'"), "主题必须读服务端记忆");
        assertTrue(ui.contains("UiState.set('chat-theme'"), "主题必须写服务端记忆");
        assertTrue(ui.contains("UiState.get('sidebar-collapsed'"), "侧栏折叠态必须读服务端记忆");
        assertTrue(ui.contains("UiState.get('sidebar-width'"), "侧栏宽度必须读服务端记忆");
        assertFalse(ui.contains("localStorage.getItem('chat-theme'") || ui.contains("localStorage.setItem('chat-theme'"),
                "主题不得再写 localStorage");
        assertFalse(ui.contains("localStorage.getItem('sidebar-collapsed'") || ui.contains("localStorage.setItem('sidebar-collapsed'"),
                "侧栏折叠态不得再写 localStorage");
        assertFalse(ui.contains("localStorage.getItem('sidebar-width'") || ui.contains("localStorage.setItem('sidebar-width'"),
                "侧栏宽度不得再写 localStorage");

        String filer = resourceText("/static/js/app-filer.js");
        assertTrue(filer.contains("UiState.set('files-width'"), "文件树宽度必须写服务端记忆");
        assertTrue(filer.contains("UiState.get('files-collapsed'"), "文件树折叠态必须读服务端记忆");
        assertFalse(usesLocalStorage(filer), "文件树布局不得再依赖 localStorage");

        String git = resourceText("/static/js/app-git.js");
        assertTrue(git.contains("UiState.set('workspace-active-tab'"), "活动标签页必须写服务端记忆");
        assertFalse(usesLocalStorage(git), "活动标签页不得再依赖 localStorage");

        String i18n = resourceText("/static/js/i18n.js");
        assertTrue(i18n.contains("window.UiState.get(key, null)"), "语言设置必须优先走服务端记忆");
        assertTrue(i18n.contains("syncFromState"), "服务端状态就绪后必须校准语言（本地缓存可能为空）");

        String studio = resourceText("/static/js/studio.js");
        assertTrue(studio.contains("window.UiState.set(\"chat-theme\""), "Studio 切换主题也必须写服务端记忆");
    }

    @Test
    @DisplayName("字体与皮肤保持既有机制：真相源在 settings.json，localStorage 仅作首屏缓存")
    void fontAndSkinKeepServerSettingsSource() throws IOException {
        String ui = resourceText("/static/js/app-ui.js");

        assertTrue(ui.contains("localStorage.setItem('chat-skin'"), "皮肤仍以 localStorage 作首屏缓存");
        assertTrue(ui.contains("/web/settings/general"), "字体仍需由服务端配置校准");
        assertTrue(ui.contains("/web/settings/skins/list"), "皮肤仍需由服务端 activeSkin 校准");
    }

    @Test
    @DisplayName("门面：覆盖受管键、落盘接口、竞态与卸载兜底")
    void facadeCoversContract() throws IOException {
        String js = resourceText("/static/js/ui-state.js");

        assertTrue(js.contains("'/web/ui/state'"), "门面必须对接 GET /web/ui/state");
        assertTrue(js.contains("'/web/ui/state/save'"), "门面必须对接 POST /web/ui/state/save");
        assertTrue(js.contains("sendBeacon"), "页面卸载时必须兜底提交最后一次变更");
        assertTrue(js.contains("MANAGED_KEY"), "门面必须声明受管键（首次升级补推要按它筛选）");

        for (String key : MANAGED_KEYS) {
            assertTrue(js.contains(key), "受管键 " + key + " 必须在门面声明（否则首次升级补推会漏掉它）");
        }

        //读取必须同步（首屏不能等异步请求），写入必须批量合并
        assertTrue(js.contains("function get(key, def)"), "门面必须提供同步 get");
        assertTrue(js.contains("scheduleSave"), "写回必须批量合并，避免连续交互打出请求风暴");
    }

    /** 是否真的读写 localStorage（注释里提到不算） */
    private static boolean usesLocalStorage(String js) {
        return js.contains("localStorage.getItem(") || js.contains("localStorage.setItem(")
                || js.contains("localStorage.removeItem(");
    }

    private String resourceText(String path) throws IOException {
        try (InputStream in = UiStateWebContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("resource not found: " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能 / 连接器选择器的「会话隔离」契约。
 *
 * <p>用户反馈：在 A 对话选了技能或连接器，发完消息切到 B 对话，输入面板仍带着 A 的选择。</p>
 *
 * <p>根因是选择态只有一份全局变量（外加一个 loadedSessionId 单值），切会话时既不重绘也不重置，
 * 用户再点一下就会把旧选择连同新选择一起写进 B 会话。这里锁住三件事：</p>
 * <ul>
 *   <li>选择必须按 sessionId 缓存，不能有全局单选态；</li>
 *   <li>切会话（setActiveSession）必须同步选择器，与模型 / 子代理同一时机；</li>
 *   <li>异步返回只更新自己那次请求的会话缓存，不能覆盖当前会话的界面。</li>
 * </ul>
 */
public class CapabilitySessionScopeWebContractTest {

    @Test
    public void selectionIsCachedPerSession() throws IOException {
        String capabilities = resourceText("/static/js/app-capabilities.js");

        assertTrue(capabilities.contains("var selections = {};"),
                "技能 / 连接器的选择必须按会话缓存（sessionId → 选择），不能只有一份全局状态");
        assertTrue(capabilities.contains("function currentKey() {"),
                "必须有一个「当前会话键」入口，所有读写都从它派生");
        assertTrue(capabilities.contains("function current() {"),
                "读当前会话的选择必须走 current()，避免各处直接摸全局变量");

        // 历史实现：一份全局 selection + 单个 loadedSessionId，切会话不会重置 → 选择被带到别的对话
        assertFalse(capabilities.contains("var selection = { skills: null, mcps: null };"),
                "不允许再回到全局单份选择态");
        assertFalse(capabilities.contains("var loadedSessionId = null;"),
                "不允许再用单个 loadedSessionId 判定是否需要刷新（切回旧会话会误判）");
    }

    @Test
    public void switchingSessionSyncsSelector() throws IOException {
        String base = resourceText("/static/js/app-base.js");

        assertTrue(base.contains("if (window.Capabilities && typeof window.Capabilities.syncSession === 'function') window.Capabilities.syncSession();"),
                "setActiveSession 必须同步技能 / 连接器选择器，否则切到另一个对话会继续显示上一个会话的勾选");
        assertTrue(base.contains("if (typeof modelsLoaded !== 'undefined' && modelsLoaded) refreshSessionModel(sessionId);"),
                "同步点应与模型 / 子代理的会话刷新在同一处，避免两条刷新时机不一致");
    }

    @Test
    public void asyncRefreshDoesNotLeakAcrossSessions() throws IOException {
        String capabilities = resourceText("/static/js/app-capabilities.js");

        // 请求期间用户可能已经切走：只写缓存，界面留给当前会话
        assertTrue(capabilities.contains("if (currentKey() === key) renderAll();"),
                "拉取结果只能在仍是同一会话时重绘，否则会把 A 的选择画到 B 的工具栏上");
        assertTrue(capabilities.contains("stateOf(key)[field] = previous;"),
                "保存失败回滚必须写回发起请求的那个会话，不能污染当前会话");
        assertTrue(capabilities.contains("syncSession: syncSession"),
                "syncSession 必须导出给宿主调用");
    }

    private static String resourceText(String path) throws IOException {
        try (InputStream in = CapabilitySessionScopeWebContractTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing resource: " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) >= 0; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

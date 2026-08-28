package org.codecli.uiext;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;

/**
 * UI 扩展示例：通过拦截器在工具被调用时发射 {@code ui.render} / {@code ui.patch} 事件，
 * 并将回传的 {@code ui.action}（经 WebController 注入为普通用户消息）在工具再次被调用时
 * 通过 {@code ui.patch} 反馈到界面。
 *
 * <p>注册方式：META-INF/solon/org.codecli.uiext.properties 指向本扩展的 Plugin 引导类。</p>
 */
public class UiExtension implements HarnessExtension {

    /** 本扩展提供的工具名（拦截器据此判断是否发射 UI 事件） */
    public static final String UI_TOOL = "ui_demo";

    /** 演示用的 UI 块稳定 ID（ui.render / ui.patch 据此定位同一块） */
    public static final String BLOCK_ID = "demo-block-1";

    @Override
    public void configure(HarnessEngine engine, String agentName, ReActAgent.Builder agentBuilder) {
        System.out.println("[ui_extension_demo] configure() called for agent: " + agentName);
        // 注册拦截器：在 ui_demo 工具执行前发射 UI 事件
        agentBuilder.defaultInterceptorAdd(new UiRenderInterceptor());
        // 注册工具：用户可显式调用，LLM 也会在收到 __ui_action__ 后再次调用
        agentBuilder.defaultToolAdd(new MethodToolProvider(new UiDemoTool()));
        System.out.println("[ui_extension_demo] ui_demo tool + interceptor registered.");
    }
}

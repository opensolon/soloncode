package org.codecli.uiext;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

import java.util.Map;

/**
 * 可被 LLM 调用的示例工具。
 *
 * <p>首次调用（无 actionId）时由 {@link UiRenderInterceptor} 发射 {@code ui.render}；
 * 当用户在前端点按动作后，WebController 会把 {@code __ui_action__} 作为普通用户消息下发，
 * LLM 据此再次调用本工具并带上 {@code actionId} / {@code formData}，此时拦截器发射
 * {@code ui.patch} 更新界面。</p>
 */
public class UiDemoTool {

    @ToolMapping(name = "ui_demo", title = "UI 扩展示例",
            description = "展示一个可交互 UI 块（表格 + 操作按钮）。用户点击动作后会被再次调用并收到 actionId 与 formData。")
    public String uiDemo(@Param(name = "actionId", required = false, description = "UI 动作 ID（回传时由 ui.action 携带）") String actionId,
                         @Param(name = "formData", required = false, description = "UI 动作附带的表单数据") Map<String, Object> formData) {
        if (actionId == null || actionId.isEmpty()) {
            return "已向前端推送一个 UI 块（表格 + 「打开」按钮）。请用户点击动作；" +
                    "点击后我会再次被调用并收到 actionId 与 formData。";
        }
        return "收到 UI 动作回传：actionId=" + actionId + "，formData=" + formData +
                "。已通过 ui.patch 更新该 UI 块。";
    }
}

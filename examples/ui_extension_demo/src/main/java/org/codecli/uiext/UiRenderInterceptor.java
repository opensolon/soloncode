package org.codecli.uiext;

import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.codecli.portal.web.event.UiPatchEvent;
import org.noear.solon.codecli.portal.web.event.UiRenderEvent;
import org.noear.solon.codecli.portal.web.event.payload.UiAction;
import org.noear.solon.codecli.portal.web.event.payload.UiPatchPayload;
import org.noear.solon.codecli.portal.web.event.payload.UiRenderPayload;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在 {@code ui_demo} 工具被调用时发射 UI 事件。
 *
 * <p>拦截器持有 {@link ReActTrace}，因此可通过 {@code trace.pushAgentEvent(...)} 把
 * {@link UiRenderEvent} / {@link UiPatchEvent} 送入 Agent 流；下游的 WebEventMapper 会将其
 * 映射为 SAEP 2.0 的 {@code ui.render} / {@code ui.patch} WebEvent 推送给前端。</p>
 *
 * <p>注意：{@link UiRenderEvent} 等协议类位于 soloncode-cli 的 portal.web.event 包，
 * 工具侧只需构造并推送事件，无需关心前端如何渲染。</p>
 */
public class UiRenderInterceptor implements ReActInterceptor {

    @Override
    public void onToolCallStart(ReActTrace trace, ToolExchanger toolExchanger) {
        if (toolExchanger == null || !UiExtension.UI_TOOL.equals(toolExchanger.getToolName())) {
            return;
        }

        Map<String, Object> args = toolExchanger.getArgs();
        Object actionId = (args != null) ? args.get("actionId") : null;

        if (actionId == null || String.valueOf(actionId).isEmpty()) {
            // 首次调用：展示一个表格 + 操作按钮
            trace.pushAgentEvent(new UiRenderEvent(trace, buildTablePayload()));
        } else {
            // 回传动作：用 ui.patch 更新同一块标题，反馈「已点击」
            trace.pushAgentEvent(new UiPatchEvent(trace, buildPatchPayload(actionId)));
        }
    }

    private UiRenderPayload buildTablePayload() {
        UiRenderPayload payload = new UiRenderPayload();
        payload.setBlockId(UiExtension.BLOCK_ID);
        payload.setSchemaVersion("1");
        payload.setType("table");
        payload.setTitle("示例文件清单");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("columns", Arrays.asList("file", "loc"));
        List<List<String>> rows = new ArrayList<>();
        rows.add(Arrays.asList("src/a.java", "120"));
        rows.add(Arrays.asList("src/b.java", "88"));
        rows.add(Arrays.asList("README.md", "30"));
        props.put("rows", rows);
        payload.setProps(props);

        payload.setActions(Arrays.asList(new UiAction("open", "打开", "primary")));
        return payload;
    }

    private UiPatchPayload buildPatchPayload(Object actionId) {
        UiPatchPayload patch = new UiPatchPayload();
        patch.setBlockId(UiExtension.BLOCK_ID);
        patch.setSchemaVersion("1");
        patch.setOp("replace");
        patch.setPath("/title");
        patch.setValue("已点击动作: " + actionId);
        return patch;
    }
}

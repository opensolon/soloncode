package org.noear.solon.codecli.portal.web.pipeline;

import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.portal.web.event.WebEventNames;
import org.noear.solon.codecli.portal.web.event.payload.UiPatchPayload;
import org.noear.solon.codecli.portal.web.event.payload.UiRenderPayload;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * UI 渲染载荷专用过滤器（schema 校验 / 超长数据截断 / 样式白名单 / 兜底文本补全）。
 *
 * <p>与 {@link ToolPresentationFilter} 同构，放在 WebEvent 流管道中、仅处理 ui.* 事件；
 * 其他事件原样透传。</p>
 */
public class UiRenderFilter {

    /** 单块 rows/nodes 上限，防 Schema 膨胀/DoS */
    private static final int MAX_ROWS = 1000;

    /** 仅允许 CSS 变量引用或白名单安全值（颜色 / 长度 / 数字），拒绝 url()、expression() 等 */
    private static final Pattern SAFE_THEME_VALUE = Pattern.compile(
            "^var\\(--[\\w-]+\\)$"                       // var(--x)
                    + "|^#[0-9a-fA-F]{3,8}$"             // #rgb / #rrggbbaa
                    + "|^rgba?\\([\\d.,\\s%]+\\)"         // rgb()/rgba()
                    + "|^[\\w-]+$"                        // 具名颜色 (red, small...)
                    + "|^[0-9]+(\\.?[0-9]+)?(px|rem|em|%)$" // 长度/百分比
    );

    public WebEvent<?> apply(WebEvent<?> event) {
        if (event == null) {
            return event;
        }
        if (WebEventNames.UI_RENDER.equals(event.getEvent())
                && event.getPayload() instanceof UiRenderPayload) {
            UiRenderPayload clean = sanitize((UiRenderPayload) event.getPayload());
            WebEvent<UiRenderPayload> evt = WebEvent.ofUiRender(clean);
            evt.setSessionId(event.getSessionId());
            evt.setRunId(event.getRunId());
            evt.setTaskId(event.getTaskId());
            evt.setReasonId(event.getReasonId());
            evt.setAgentName(event.getAgentName());
            evt.setTimestamp(event.getTimestamp());
            return evt;
        }
        if (WebEventNames.UI_PATCH.equals(event.getEvent())
                && event.getPayload() instanceof UiPatchPayload) {
            return event; // patch 透传，由端侧按 JSON Pointer 应用
        }
        return event;
    }

    public static UiRenderPayload sanitize(UiRenderPayload payload) {
        if (payload.getSchemaVersion() == null || payload.getSchemaVersion().isEmpty()) {
            payload.setSchemaVersion("1.0");
        }
        if (payload.getType() == null || payload.getType().isEmpty()) {
            payload.setType("card");
        }

        // 超长 rows 截断（仅 table 类型）
        if ("table".equals(payload.getType()) && payload.getProps() != null) {
            Object rows = payload.getProps().get("rows");
            if (rows instanceof List && ((List<?>) rows).size() > MAX_ROWS) {
                List<?> list = (List<?>) rows;
                payload.getProps().put("rows", list.subList(0, MAX_ROWS));
                payload.setTruncated(true);
            }
        }

        // 样式白名单：丢弃非安全值
        if (payload.getTheme() != null) {
            payload.getTheme().entrySet().removeIf(e -> !isSafeThemeValue(String.valueOf(e.getValue())));
        }

        // 兜底文本补全
        ensureFallbackText(payload);

        return payload;
    }

    private static void ensureFallbackText(UiRenderPayload payload) {
        Map<String, Object> fallback = payload.getFallback();
        if (fallback == null) {
            fallback = new java.util.LinkedHashMap<>();
            payload.setFallback(fallback);
        }
        if (fallback.get("text") == null) {
            String type = payload.getType() == null ? "ui" : payload.getType();
            fallback.put("text", "[" + type + " UI 块]");
        }
    }

    private static boolean isSafeThemeValue(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return SAFE_THEME_VALUE.matcher(value.trim()).matches();
    }
}

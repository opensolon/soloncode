package org.noear.solon.codecli.portal.web.event.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * SAEP 2.0 {@code ui.render} 事件载荷。
 *
 * <p>描述「要展示什么」而非「怎么画」。{@code props} 为开放结构，由 {@code type} 决定语义；
 * Web 端动态分发到 React/Vue 组件，CLI 端降级为 ASCII / TUI，ACP 端有损翻译为文本。</p>
 *
 * @see org.noear.solon.codecli.portal.web.event.WebEventNames#UI_RENDER
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiRenderPayload implements Serializable {
    /** 端侧 UI 块实例稳定 ID；ui.patch / ui.action 据此定位 */
    private String blockId;
    /** UI 块 Schema 版本（语义化）；客户端无匹配渲染器时走 fallback */
    private String schemaVersion;
    /** 标准块类型（table/form/chart/diff/card/actions/progress/tree）或插件自定义 type */
    private String type;
    /** 块标题 */
    private String title;
    /** 块的具体数据/配置，开放结构 */
    private Map<String, Object> props;
    /** 局部样式覆盖，仅接受 CSS 变量引用或白名单安全值（见 UiRenderFilter） */
    private Map<String, Object> theme;
    /** 多端降级描述：cli（CLI 渲染提示）、text（纯文本降级） */
    private Map<String, Object> fallback;
    /** 可交互动作列表 */
    private List<UiAction> actions;
    /** 超长数据被截断标记（由 UiRenderFilter 设置） */
    private Boolean truncated;
}

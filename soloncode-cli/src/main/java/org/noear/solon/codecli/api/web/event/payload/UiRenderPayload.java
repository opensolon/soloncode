package org.noear.solon.codecli.api.web.event.payload;

import java.io.Serializable;
import java.util.Map;

/**
 * ui.render 事件载荷（SAEP 2.0 UI 扩展块渲染）。
 *
 * <p>结构化 UI 块声明：类型 + 属性 + 主题 + 降级文本。端侧按 {@code type} 分发渲染
 * （card / table 等），{@code fallback} 在端侧不支持该类型时展示。</p>
 */
public class UiRenderPayload implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 协议版本（缺省由 {@code UiRenderFilter#sanitize} 补 "1.0"） */
    private String schemaVersion;

    /** 块类型：card / table / ...（缺省补 "card"） */
    private String type;

    /** 块标题 */
    private String title;

    /** 块业务属性（table 的 columns/rows 等；rows 超限由 sanitize 截断并置 truncated） */
    private Map<String, Object> props;

    /** 主题样式（仅允许 CSS 变量引用或白名单安全值） */
    private Map<String, Object> theme;

    /** 端侧不支持时的降级展示 */
    private Map<String, Object> fallback;

    /** 数据是否被截断过 */
    private boolean truncated;

    /** 块标识（跨消息定位用；缺省由端侧生成） */
    private String blockId;

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Map<String, Object> getProps() {
        return props;
    }

    public void setProps(Map<String, Object> props) {
        this.props = props;
    }

    public Map<String, Object> getTheme() {
        return theme;
    }

    public void setTheme(Map<String, Object> theme) {
        this.theme = theme;
    }

    public Map<String, Object> getFallback() {
        return fallback;
    }

    public void setFallback(Map<String, Object> fallback) {
        this.fallback = fallback;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public String getBlockId() {
        return blockId;
    }

    public void setBlockId(String blockId) {
        this.blockId = blockId;
    }
}

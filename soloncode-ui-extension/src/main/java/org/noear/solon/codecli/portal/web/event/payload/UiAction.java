package org.noear.solon.codecli.portal.web.event.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * UI 渲染动作（按钮 / 菜单项）。
 *
 * <p>用户在 Web/CLI 端触发后，端侧封装为 {@code ui.action} 指令回传，不直接执行客户端代码。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiAction implements Serializable {
    /** 动作稳定 ID，回传时携带 */
    private String id;
    /** 展示文案 */
    private String label;
    /** 样式语义：primary | default | danger */
    private String kind;
}

package org.noear.solon.codecli.portal.web.event.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * SAEP 2.0 {@code ui.patch} 事件载荷：对同一 UI 块的增量更新。
 *
 * <p>避免大块重复下发，用于表格翻页、进度刷新、图表数据流。
 * {@code path} 采用 JSON Pointer（RFC 6901）子集。</p>
 *
 * @see org.noear.solon.codecli.portal.web.event.WebEventNames#UI_PATCH
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiPatchPayload implements Serializable {
    /** 目标 UI 块 ID */
    private String blockId;
    /** UI 块 Schema 版本（语义化） */
    private String schemaVersion;
    /** 操作：replace | merge | append | remove */
    private String op;
    /** JSON Pointer 子集路径，如 props.rows */
    private String path;
    /** 操作值（replace/merge/append 时使用） */
    private Object value;
}

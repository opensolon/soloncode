package org.noear.solon.codecli.api.web.event.payload;

import java.io.Serializable;

/**
 * ui.patch 事件载荷（SAEP 2.0 UI 扩展块增量更新）。
 *
 * <p>按 JSON Pointer 语义对既有 UI 块做增量修改，由端侧在
 * {@code patchUiBlock} 中应用（replace / props 整体替换等）。</p>
 */
public class UiPatchPayload implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 目标块标识 */
    private String blockId;

    /** patch 操作（缺省 "replace"） */
    private String op;

    /** JSON Pointer 路径（如 /title、/props） */
    private String path;

    /** 新值（/props 时为属性对象） */
    private Object value;

    /** 块类型提示（/props 替换时端侧重新分发渲染用，缺省 "card"） */
    private String type;

    public String getBlockId() {
        return blockId;
    }

    public void setBlockId(String blockId) {
        this.blockId = blockId;
    }

    public String getOp() {
        return op;
    }

    public void setOp(String op) {
        this.op = op;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}

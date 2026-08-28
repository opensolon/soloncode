package org.noear.solon.codecli.portal.web.event.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 工具调用的 LSP 附带信息（供 Web UI 展示"这次调用触发了语言服务器"）。
 *
 * <p>诊断文本本身是给模型看的（祈使句 + XML 包裹，措辞会随 prompt 调优而变），
 * 因此这里把它解析为结构化字段下发，前端不做文本解析，避免把面向模型的措辞
 * 变成前后端契约。
 *
 * <p>{@code errorCount == 0 && !pending} 表示语言服务器已检查该文件且未发现错误；
 * {@code pending} 表示已请求检查但等待预算内没拿到结论（多为语言服务器冷启动首次索引），
 * 此时不能声称文件是干净的；未被任何语言服务器覆盖的文件不会产生本对象（payload.lsp 为 null）。
 *
 * @author noear
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolLspInfo implements Serializable {
    /**
     * 展示用文件路径（相对工作区）
     */
    private String file;

    /**
     * ERROR 级诊断总数（含被截断的部分）
     */
    private int errorCount;

    /**
     * 诊断条目是否被截断（明细少于 errorCount）
     */
    private boolean truncated;

    /**
     * 诊断明细（已按 ERROR 过滤，条数受渲染层上限约束）
     */
    private List<ToolLspDiagnostic> items;

    /**
     * 检查结论未知：已请求但等待超时（冷启动索引中），不代表无错误
     */
    private boolean pending;
}

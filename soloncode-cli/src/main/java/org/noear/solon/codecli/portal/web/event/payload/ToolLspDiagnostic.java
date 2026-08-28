package org.noear.solon.codecli.portal.web.event.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 单条 LSP 诊断（结构化），行列均为 1-based，与 read/edit 的行号语义一致。
 *
 * @author noear
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolLspDiagnostic implements Serializable {
    private int line;
    private int column;
    private String message;
    /**
     * 诊断来源（如 javac / typescript / clangd），可能为空
     */
    private String source;
}

package org.noear.solon.codecli.portal.web.event.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolEndPayload implements Serializable {
    private String callId;
    private String name;
    private String title;
    private String result;
    private boolean isError;
    private String diff;
    private Map<String, Object> args;
    /**
     * LSP 附带信息：本次写入触发了语言服务器检查时非空（含已检查无错误的情况）。
     * 未触发语言服务器时为 null，序列化后字段不出现，对旧前端无影响。
     */
    private ToolLspInfo lsp;
}

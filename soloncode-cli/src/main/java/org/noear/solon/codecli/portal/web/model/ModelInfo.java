package org.noear.solon.codecli.portal.web.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 模型信息
 * 支持 OpenAI、Anthropic、Ollama 等多种格式
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelInfo {
    private String id;
    private String object;
    private long created;
    private String ownedBy;
    /** 扩展字段：chat / image */
    private String type;
    
    // Anthropic 扩展字段
    private String displayName;
    private Long maxInputTokens;
    private Long maxTokens;
    private Map<String, Object> capabilities;

    /** 是否为手动添加的模型（动态刷新时不被清除） */
    @Builder.Default
    private boolean manual = false;
}

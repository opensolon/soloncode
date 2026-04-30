package org.noear.solon.codecli.provider;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型信息（OpenAI /v1/models 兼容格式）
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
}

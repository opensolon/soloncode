package org.noear.solon.codecli.config.entity;

import lombok.Getter;
import lombok.Setter;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.models.ModelInfo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 供应商配置数据对象
 *
 * @author soloncode 2026-06-18 created
 */
@Getter
@Setter
public class ProviderDo implements Serializable {
    /**
     * 供应商名称（唯一标识）
     */
    private String name;

    /**
     * 接口规范：openai / ollama / anthropic
     */
    private String standard = "openai";

    /**
     * API 地址
     */
    private String apiUrl;

    /**
     * API 密钥
     */
    private String apiKey;

    /**
     * 是否启用
     */
    private boolean enabled = true;

    /**
     * 作用域：global（全局）/ local（工作区）
     */
    private String scope = AgentFlags.SCOPE_USER;

    /**
     * 该供应商下的模型列表（使用 ModelInfo 存储完整模型信息）
     *
     * ProviderDo.name - ModelDo.provider, ModelInfo.id <-> ModelDo.model
     */
    private List<ModelInfo> models = new ArrayList<>();
}

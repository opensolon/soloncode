package org.noear.solon.codecli.config.entity;

import lombok.Getter;
import lombok.Setter;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.model.discovery.ModelInfo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * 自定义请求头，随供应商下所有模型的请求一起发送。
     *
     * <p>用于接口规范之外的头部要求，例如 opencode 订阅需要为每段对话发送
     * 稳定的 {@code x-opencode-session}。值为静态文本，不支持按会话动态取值。</p>
     */
    private Map<String, String> headers = new LinkedHashMap<>();

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

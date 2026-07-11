/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.util;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.core.util.Assert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 推理水平：归一化、会话读写、能力探测与 options 注入。
 *
 * <p>会话级选择走 {@link AgentSession#getContext()}，请求级注入走 Prompt attr + optionSet。
 * 不修改 ChatModel 单例状态。</p>
 *
 * <p>语义：
 * <ul>
 *   <li>auto：会话无 effort 键 → 不注入 reasoning_effort，交给模型 defaultOptions / Agent Builder / 供应商</li>
 *   <li>user：会话有显式 low|medium|high|max</li>
 * </ul>
 * {@link ModelCapability#defaultReasoningEffort} 仅供 UI 推荐，不得在 auto 时强制注入。</p>
 *
 * <p>不再提供独立「速度」轴：方言层无 speed_mode 映射；浅/深响应由推理档（含「低」）直接控制。</p>
 *
 * @author noear
 * @since 2026.7
 */
public final class ReasoningEffortSupport {
    /** 会话上下文：推理水平 low|medium|high|max */
    public static final String CTX_REASONING_EFFORT = "_reasoning_effort";

    private static final List<String> ALL_EFFORTS = Collections.unmodifiableList(
            Arrays.asList("low", "medium", "high", "max"));

    private ReasoningEffortSupport() {
    }

    public static String normalizeEffort(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || "auto".equals(normalized)) {
            return null;
        }
        if ("low".equals(normalized) || "medium".equals(normalized)
                || "high".equals(normalized) || "max".equals(normalized)) {
            return normalized;
        }
        return null;
    }

    /**
     * 解析最终写入请求的 effort。
     * <p>优先级：请求显式 &gt; 会话 user；
     * <b>不再</b>在 preferred 为空时灌入 capability.defaultReasoningEffort（auto 不注入）。</p>
     */
    public static String resolveEffectiveEffort(String requestEffort,
                                               String sessionEffort,
                                               ModelCapability capability,
                                               boolean requestEffortPresent) {
        String preferred;
        if (requestEffortPresent) {
            preferred = normalizeEffort(requestEffort);
        } else {
            preferred = normalizeEffort(sessionEffort);
        }
        // auto：不注入默认档，交给 ChatConfig.defaultOptions / Agent Builder / 供应商
        return clampEffort(preferred, capability);
    }

    /**
     * UI 展示用 effort（不用于请求注入）。
     * <ul>
     *   <li>有 user 会话值 → clamp 后返回</li>
     *   <li>否则 null（auto；不把 defaultReasoningEffort 当成已选）</li>
     * </ul>
     */
    public static String resolveForUi(String sessionEffort, ModelCapability capability) {
        String user = normalizeEffort(sessionEffort);
        if (user != null) {
            return clampEffort(user, capability);
        }
        return null;
    }

    public static String clampEffort(String effort, ModelCapability capability) {
        String normalized = normalizeEffort(effort);
        if (normalized == null) {
            return null;
        }
        if (capability == null || !capability.supportsReasoning) {
            // 能力未知时仍允许下发（对齐 Desktop 行为）；UI 侧会按 supportsReasoning 隐藏
            return normalized;
        }
        if (capability.reasoningEfforts == null || capability.reasoningEfforts.isEmpty()) {
            return normalized;
        }
        if (capability.reasoningEfforts.contains(normalized)) {
            return normalized;
        }
        // clamp：max -> high -> medium -> low
        List<String> order = Arrays.asList("max", "high", "medium", "low");
        int start = order.indexOf(normalized);
        if (start < 0) {
            start = 0;
        }
        for (int i = start; i < order.size(); i++) {
            if (capability.reasoningEfforts.contains(order.get(i))) {
                return order.get(i);
            }
        }
        for (int i = start - 1; i >= 0; i--) {
            if (capability.reasoningEfforts.contains(order.get(i))) {
                return order.get(i);
            }
        }
        return null;
    }

    public static void applyToPrompt(Prompt prompt, String reasoningEffort) {
        if (prompt == null || Assert.isEmpty(reasoningEffort)) {
            return;
        }
        prompt.attrPut("reasoning_effort", reasoningEffort);
        prompt.attrPut("reasoningEffort", reasoningEffort);
    }

    /**
     * 写入 ReAct/Chat 模型 options（经 ReasonTask 透传到 ChatModel）。
     *
     * @param reasoningEffort low|medium|high|max，空则不写
     */
    public static void applyToOptions(ReActOptionsAmend options, String reasoningEffort) {
        if (options == null) {
            return;
        }
        if (Assert.isNotEmpty(reasoningEffort)) {
            options.reasoning_effort(reasoningEffort);
        }
    }

    public static String getSessionEffort(AgentSession session) {
        if (session == null) {
            return null;
        }
        Object v = session.getContext().get(CTX_REASONING_EFFORT);
        return v == null ? null : normalizeEffort(String.valueOf(v));
    }

    /**
     * 写入会话；effort 传空串/"auto"/null 时清除。
     */
    public static void putSessionEffort(AgentSession session, String reasoningEffort, boolean effortProvided) {
        if (session == null || !effortProvided) {
            return;
        }
        String effort = normalizeEffort(reasoningEffort);
        if (effort == null) {
            session.getContext().remove(CTX_REASONING_EFFORT);
        } else {
            session.getContext().put(CTX_REASONING_EFFORT, effort);
        }
    }

    /**
     * 按引擎模型列表查找与当前 ChatModel 匹配的完整配置（含 defaultOptions）。
     * 优先匹配 {@link ChatConfig#getNameOrModel()}，其次 name、model。
     */
    public static ChatConfig findEngineConfig(Iterable<? extends ChatConfig> models, String nameOrModel) {
        if (models == null || Assert.isEmpty(nameOrModel)) {
            return null;
        }
        for (ChatConfig config : models) {
            if (config == null) {
                continue;
            }
            if (nameOrModel.equals(config.getNameOrModel()) || nameOrModel.equals(config.getModel())
                    || nameOrModel.equals(config.getName())) {
                return config;
            }
        }
        return null;
    }

    /**
     * 从 name/model/standard + 可选 options 快照解析能力。
     */
    public static ModelCapability resolveCapability(String name, String model, String standard,
                                                    Map<String, Object> optionsSnapshot) {
        ChatConfig tmp = new ChatConfig();
        if (Assert.isNotEmpty(model)) {
            tmp.setModel(model);
        }
        if (Assert.isNotEmpty(name)) {
            tmp.setName(name);
        }
        if (Assert.isNotEmpty(standard)) {
            tmp.setStandard(standard);
        }
        if (optionsSnapshot != null && !optionsSnapshot.isEmpty()) {
            try {
                tmp.getModelOptions().optionSet(optionsSnapshot);
            } catch (Throwable ignored) {
            }
        }
        return resolveCapability(tmp);
    }

    public static ModelCapability resolveCapability(ChatConfig config) {
        ModelCapability cap = new ModelCapability();
        if (config == null) {
            return cap;
        }

        String standard = config.getStandardOrProvider();
        String model = config.getModel();
        String name = config.getNameOrModel();
        String blob = ((standard == null ? "" : standard) + " " + (model == null ? "" : model)
                + " " + (name == null ? "" : name)).toLowerCase(Locale.ROOT);

        Map<String, Object> options = null;
        try {
            options = config.getModelOptions().options();
        } catch (Throwable ignored) {
        }

        String defaultEffort = null;
        if (options != null && !options.isEmpty()) {
            if (options.containsKey("reasoning_effort") || options.containsKey("reasoningEffort")) {
                Object v = options.get("reasoning_effort");
                if (v == null) {
                    v = options.get("reasoningEffort");
                }
                if (v != null) {
                    defaultEffort = normalizeEffort(String.valueOf(v));
                }
            }
        }
            
        // 默认对所有已配置模型展示推理条；auto 仍不注入，由用户手选才写 session
        cap.supportsReasoning = true;
        cap.reasoningEfforts = new ArrayList<String>(ALL_EFFORTS);
        // Responses / o 系列常见三档
        if (blob.contains("openai-responses") || containsToken(blob, "o1")
                || containsToken(blob, "o3") || containsToken(blob, "o4")) {
            cap.reasoningEfforts = new ArrayList<String>(Arrays.asList("low", "medium", "high"));
        }
        if (defaultEffort != null) {
            cap.defaultReasoningEffort = defaultEffort;
        } else if (blob.contains("claude") || blob.contains("anthropic") || blob.contains("opus")
                || blob.contains("sonnet")) {
            cap.defaultReasoningEffort = "high";
        } else if (looksLikeReasoningModel(blob)) {
            cap.defaultReasoningEffort = "medium";
        } else {
            // 非启发式模型：UI 推荐 medium，auto 时仍不注入
            cap.defaultReasoningEffort = "medium";
        }
                
        return cap;
    }
            
    /**
     * 名称启发式：用于推荐 defaultReasoningEffort 与三档列表等微调。
     * 不再作为 supportsReasoning 的开关——能力默认 true，由 UI 统一展示推理条。
     */
    static boolean looksLikeReasoningModel(String blob) {
        if (Assert.isEmpty(blob)) {
            return false;
        }
        // 明确不支持的常见非思考档（除非 options 显式）
        if (blob.contains("haiku") && !blob.contains("thinking")) {
            if (!blob.contains("sonnet") && !blob.contains("opus")) {
                return false;
            }
        }
        
        String[] keys = new String[]{
                "o1", "o3", "o4", "gpt-5", "gpt5",
                "claude-3", "claude-4", "claude-sonnet", "claude-opus",
                "deepseek-r1", "deepseek-reasoner",
                "qwen-qwq", "qwq", "thinking", "reasoner",
                "gemini-2.5", "gemini-3", "openai-responses"
        };
        for (String k : keys) {
            if (blob.contains(k)) {
                return true;
            }
        }
        // 宽匹配 claude* 但排除 haiku
        if (blob.contains("claude") && !blob.contains("haiku")) {
            return true;
        }
        // standard 为 anthropic 且模型名含 sonnet/opus（无 claude 前缀的别名）
        if ((blob.contains("sonnet") || blob.contains("opus"))
                && (blob.contains("anthropic") || blob.contains("claude"))) {
            return true;
        }
        return false;
    }

    /**
     * Anthropic thinking.budget_tokens 参考映射（与 AnthropicRequestBuilder 对齐，供单测锁回归）。
     * @return budget；非法 effort 返回 -1
     */
    public static int mapAnthropicBudgetTokens(String effort) {
        String e = normalizeEffort(effort);
        if (e == null) {
            return -1;
        }
        if ("low".equals(e)) {
            return 4000;
        }
        if ("medium".equals(e)) {
            return 10000;
        }
        if ("high".equals(e)) {
            return 20000;
        }
        if ("max".equals(e)) {
            return 32000;
        }
        return -1;
    }

    /**
     * OpenAI Responses reasoning.effort 参考映射（max→high，与 OpenaiResponsesRequestBuilder 对齐）。
     */
    public static String mapResponsesReasoningEffort(String effort) {
        String e = normalizeEffort(effort);
        if (e == null) {
            return null;
        }
        if ("max".equals(e)) {
            return "high";
        }
        return e;
    }

    /** 避免 "o1" 误匹配到无关子串时仍尽量精确（空白/分隔） */
    private static boolean containsToken(String blob, String token) {
        if (blob == null || token == null) {
            return false;
        }
        int idx = blob.indexOf(token);
        while (idx >= 0) {
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(blob.charAt(idx - 1));
            int end = idx + token.length();
            boolean rightOk = end >= blob.length() || !Character.isLetterOrDigit(blob.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            idx = blob.indexOf(token, idx + 1);
        }
        return false;
    }

    public static Map<String, Object> toCapabilityMap(ModelCapability cap) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("supportsReasoning", cap.supportsReasoning);
        map.put("reasoningEfforts", cap.reasoningEfforts == null
                ? Collections.emptyList() : cap.reasoningEfforts);
        if (Assert.isNotEmpty(cap.defaultReasoningEffort)) {
            map.put("defaultReasoningEffort", cap.defaultReasoningEffort);
        }
        return map;
    }

    public static class ModelCapability {
        public boolean supportsReasoning;
        public List<String> reasoningEfforts = Collections.emptyList();
        public String defaultReasoningEffort;
    }
}

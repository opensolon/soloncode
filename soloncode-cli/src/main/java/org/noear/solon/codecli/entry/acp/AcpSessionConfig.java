package org.noear.solon.codecli.entry.acp;

import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.core.util.Assert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ACP v1 会话级配置选项（{@code configOptions}）的装配与写入。
 *
 * <p>标准类别复用 ACP 已有的 model / thought_level / mode；思考模式没有标准类别，
 * 以名字空间化的 {@link #THINKING} 暴露（auto/on/off，独立于推理强度）。</p>
 *
 * <p>所有选择都是<b>会话级</b>：写入落在 {@link AgentSession#getContext()} / 会话模型选择，
 * 不触碰引擎级全局配置；mode 只决定 ACP 侧对 HITL 挂起卡的处置（审批 / 拒绝 / 引擎策略），
 * 不能据此绕过引擎自身的权限与 HITL 策略。</p>
 */
final class AcpSessionConfig {
    /** 模型选择（category=model） */
    static final String MODEL = "model";
    /** 推理强度（category=thought_level）：auto + 模型支持的具体档位，不含 none（Solon 里 none 等于关闭思考） */
    static final String EFFORT = "effort";
    /** 审批模式（category=mode）：hitlEnabled 时 ask|reject，未启用时仅 configured */
    static final String MODE = "mode";
    /** 思考模式（自定义类别，auto/on/off），与推理强度相互独立 */
    static final String THINKING = "_soloncode_thinking_mode";

    /** mode 当前值在会话上下文里的键 */
    static final String PERMISSION_KEY = "_soloncode_acp_permission_mode";

    static final String MODE_ASK = "ask";
    static final String MODE_REJECT = "reject";
    static final String MODE_CONFIGURED = "configured";

    private static final String CATEGORY_MODEL = "model";
    private static final String CATEGORY_EFFORT = "thought_level";
    private static final String CATEGORY_MODE = "mode";
    private static final String CATEGORY_THINKING = "_soloncode_thinking_mode";

    private static final String VALUE_AUTO = "auto";

    private final HarnessEngine engine;
    private final AgentSettings settings;

    AcpSessionConfig(HarnessEngine engine, AgentSettings settings) {
        this.engine = engine;
        this.settings = settings;
    }

    /**
     * 组装该会话当前可展示的全部配置项（currentValue 随会话状态现读）。
     */
    List<AcpSchema.SessionConfigOption> options(AgentSession session) {
        List<AcpSchema.SessionConfigOption> out = new ArrayList<>();

        ChatConfig effective = effectiveModelConfig(session);
        if (effective != null) {
            // 模型：以 nameOrModel 去重（同一模型可能有多条配置）
            Set<String> names = new LinkedHashSet<>();
            for (ChatConfig config : engine.getModels()) {
                if (config.isEnabled() && Assert.isNotEmpty(config.getNameOrModel())) {
                    names.add(config.getNameOrModel());
                }
            }
            List<AcpSchema.SessionConfigSelectOption> modelValues = new ArrayList<>();
            for (String name : names) {
                modelValues.add(new AcpSchema.SessionConfigSelectOption(name, name));
            }
            out.add(new AcpSchema.SessionConfigSelect("select", MODEL, "Model", null,
                    CATEGORY_MODEL, effective.getNameOrModel(), modelValues, null));

            ReasoningSupportUtil.ModelCapability cap = ReasoningSupportUtil.resolveCapability(effective);

            // 思考模式：auto/on/off 独立档位，与推理强度分开选
            String thinking = ReasoningSupportUtil.resolveSessionThinkingOrDefault(session, globalThinking());
            List<AcpSchema.SessionConfigSelectOption> thinkingValues = Arrays.asList(
                    new AcpSchema.SessionConfigSelectOption(VALUE_AUTO, "Auto"),
                    new AcpSchema.SessionConfigSelectOption("on", "On"),
                    new AcpSchema.SessionConfigSelectOption("off", "Off"));
            out.add(new AcpSchema.SessionConfigSelect("select", THINKING, "Thinking mode", null,
                    CATEGORY_THINKING, thinking == null ? VALUE_AUTO : thinking, thinkingValues, null));

            // 推理强度：auto + 该模型支持的具体档位（none 不提供：Solon 里它等同关闭思考，走 thinking=off）
            List<AcpSchema.SessionConfigSelectOption> effortValues = new ArrayList<>();
            effortValues.add(new AcpSchema.SessionConfigSelectOption(VALUE_AUTO, "Auto"));
            for (String e : cap.reasoningEfforts) {
                if (!"none".equals(e)) {
                    effortValues.add(new AcpSchema.SessionConfigSelectOption(e, e));
                }
            }
            String effort = ReasoningSupportUtil.resolveSessionEffortOrDefault(session, globalEffort(), cap);
            if (effort == null || "none".equals(effort)) {
                effort = VALUE_AUTO;
            }
            out.add(new AcpSchema.SessionConfigSelect("select", EFFORT, "Reasoning effort", null,
                    CATEGORY_EFFORT, effort, effortValues, null));
        }

        // 审批模式：引擎开了 HITL 才是可选项（审批 / 自动拒绝）；未启用只有 configured（服务端策略，不对外伪装成可绕过）
        if (engine.isHitlEnabled()) {
            String current = permissionMode(session);
            out.add(new AcpSchema.SessionConfigSelect("select", MODE, "Permission mode", null,
                    CATEGORY_MODE, current, Arrays.asList(
                            new AcpSchema.SessionConfigSelectOption(MODE_ASK, "Ask for approval"),
                            new AcpSchema.SessionConfigSelectOption(MODE_REJECT, "Reject HITL cards")), null));
        } else {
            out.add(new AcpSchema.SessionConfigSelect("select", MODE, "Permission mode", null,
                    CATEGORY_MODE, MODE_CONFIGURED, Arrays.asList(
                            new AcpSchema.SessionConfigSelectOption(MODE_CONFIGURED, "Server policy")), null));
        }

        return out;
    }

    /**
     * 写入一个配置值：只接受当前已列出的选项与其字符串值，其余一律按参数错误拒绝（不回显原始值）。
     *
     * @return 写入后的全量配置项（供 set_config_option 响应回放）
     */
    List<AcpSchema.SessionConfigOption> set(AgentSession session, String configId, Object value) {
        AcpSchema.SessionConfigOption option = null;
        for (AcpSchema.SessionConfigOption o : options(session)) {
            if (o instanceof AcpSchema.SessionConfigSelect
                    && configId != null && configId.equals(((AcpSchema.SessionConfigSelect) o).id())) {
                option = o;
                break;
            }
        }

        String valueString = null;
        if (option instanceof AcpSchema.SessionConfigSelect && value instanceof String) {
            for (AcpSchema.SessionConfigSelectOption v : ((AcpSchema.SessionConfigSelect) option).options()) {
                if (value.equals(v.value())) {
                    valueString = v.value();
                    break;
                }
            }
        }

        if (valueString == null) {
            throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS,
                    "Unknown config option or value: " + configId);
        }

        switch (configId) {
            case MODEL:
                engine.setSessionModel(session, valueString);
                break;
            case EFFORT:
                ReasoningSupportUtil.putSessionEffort(session, valueString, true);
                break;
            case THINKING:
                ReasoningSupportUtil.putSessionThinkingMode(session, valueString, true);
                break;
            case MODE:
                session.getContext().put(PERMISSION_KEY, valueString);
                break;
            default:
                // options() 只产出上述四种 id；防御兜底，不静默吞掉未知 id
                throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS,
                        "Unknown config option or value: " + configId);
        }

        session.updateSnapshot();
        return options(session);
    }

    /**
     * 一轮开始时的生效快照：会话级选择可能被另一通道并行改掉，受理后按快照执行，
     * 不随运行中的 set_config_option 漂移。
     */
    Snapshot snapshot(AgentSession session) {
        ChatConfig effective = effectiveModelConfig(session);
        ReasoningSupportUtil.ModelCapability cap = effective == null
                ? new ReasoningSupportUtil.ModelCapability()
                : ReasoningSupportUtil.resolveCapability(effective);
        String model = effective == null ? null : effective.getNameOrModel();
        String thinking = ReasoningSupportUtil.resolveSessionThinkingOrDefault(session, globalThinking());
        String effort = ReasoningSupportUtil.resolveSessionEffortOrDefault(session, globalEffort(), cap);
        return new Snapshot(model, thinking, "none".equals(effort) ? null : effort, permissionMode(session));
    }

    /**
     * 生效模型：会话选择（须仍启用）→ 默认模型（须启用）→ 第一个启用模型；无启用模型返回 null。
     */
    private ChatConfig effectiveModelConfig(AgentSession session) {
        List<ChatConfig> enabled = new ArrayList<>();
        for (ChatConfig config : engine.getModels()) {
            if (config.isEnabled()) {
                enabled.add(config);
            }
        }
        if (enabled.isEmpty()) {
            return null;
        }

        String selected = engine.getSessionModel(session);
        if (Assert.isNotEmpty(selected)) {
            ChatConfig config = engine.getModelOrDef(selected);
            if (config != null && config.isEnabled()) {
                return config;
            }
        }

        ChatConfig def = engine.getModelOrDef(null);
        if (def != null && def.isEnabled()) {
            return def;
        }

        return enabled.get(0);
    }

    /**
     * 审批模式生效值：HITL 未启用恒为 configured；启用时会话值非法回落 ask。
     */
    String permissionMode(AgentSession session) {
        if (engine.isHitlEnabled() == false) {
            return MODE_CONFIGURED;
        }
        Object v = session == null ? null : session.getContext().get(PERMISSION_KEY);
        String mode = v == null ? null : String.valueOf(v);
        if (MODE_REJECT.equals(mode)) {
            return MODE_REJECT;
        }
        return MODE_ASK;
    }

    private String globalThinking() {
        return settings == null || settings.getGeneral() == null
                ? null : settings.getGeneral().getDefaultThinkingMode();
    }

    private String globalEffort() {
        return settings == null || settings.getGeneral() == null
                ? null : settings.getGeneral().getDefaultReasoningEffort();
    }

    /** 一轮的生效选择（受理时快照，运行中不可变） */
    static final class Snapshot {
        final String model;
        final String thinking;
        final String effort;
        final String permissionMode;

        Snapshot(String model, String thinking, String effort, String permissionMode) {
            this.model = model;
            this.thinking = thinking;
            this.effort = effort;
            this.permissionMode = permissionMode;
        }
    }
}

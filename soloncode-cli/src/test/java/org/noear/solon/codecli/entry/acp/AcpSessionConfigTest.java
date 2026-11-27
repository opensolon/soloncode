package org.noear.solon.codecli.entry.acp;

import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.session.SessionSelections;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.codecli.util.ReasoningSupportUtil;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AcpSessionConfig 单元测试。
 *
 * <p>覆盖：options 装配（模型 / 推理强度 / 思考模式 / 审批模式的 id、类别、当前值与门槛）、
 * set 的白名单校验与状态写入、snapshot 的生效值快照、无效输入失败关闭。</p>
 */
public class AcpSessionConfigTest {

    @TempDir
    Path tempDir;

    private HarnessEngine engine;
    private AgentSettings settings;
    private AcpSessionConfig config;
    private AgentSession session;

    private ChatConfig gpt;
    private ChatConfig o3;

    @BeforeEach
    void setUp() {
        SessionManager sessionManager = new SessionManager(tempDir.toString());
        session = sessionManager.getSession("s1");

        engine = mock(HarnessEngine.class);
        when(engine.getName()).thenReturn("main");

        gpt = model("gpt-5");
        o3 = model("o3");
        List<ChatConfig> models = new ArrayList<>(Arrays.asList(gpt, o3));
        when(engine.getModels()).thenReturn(models);
        when(engine.getModelOrDef(null)).thenReturn(gpt);
        when(engine.getModelOrDef(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            for (ChatConfig m : models) {
                if (name.equals(m.getNameOrModel())) {
                    return m;
                }
            }
            return null;
        });
        // 会话级模型读写与 HarnessEngine 的真实口径一致（SessionSelections）
        when(engine.getSessionModel(any())).thenAnswer(
                inv -> SessionSelections.modelOf(inv.getArgument(0)));
        doAnswer(inv -> {
            SessionSelections.setModel(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(engine).setSessionModel(any(), any());
        when(engine.isHitlEnabled()).thenReturn(true);

        settings = new AgentSettings();
        config = new AcpSessionConfig(engine, settings);
    }

    private static ChatConfig model(String name) {
        ChatConfig c = new ChatConfig();
        c.setName(name);
        c.setModel(name);
        c.setStandard("openai");
        return c;
    }

    private static AcpSchema.SessionConfigSelect findSelect(List<AcpSchema.SessionConfigOption> options,
                                                          String id) {
        for (AcpSchema.SessionConfigOption o : options) {
            if (o instanceof AcpSchema.SessionConfigSelect
                    && id.equals(((AcpSchema.SessionConfigSelect) o).id())) {
                return (AcpSchema.SessionConfigSelect) o;
            }
        }
        return null;
    }

    private static List<String> optionValues(AcpSchema.SessionConfigSelect select) {
        List<String> values = new ArrayList<>();
        for (AcpSchema.SessionConfigSelectOption o : select.options()) {
            values.add(o.value());
        }
        return values;
    }

    // ─────────────────── options ───────────────────

    @Test
    @DisplayName("options: 装配 model / effort / mode / thinking 四项与各自类别")
    void options_assemblesAllOptions() {
        List<AcpSchema.SessionConfigOption> options = config.options(session);

        AcpSchema.SessionConfigSelect model = findSelect(options, AcpSessionConfig.MODEL);
        assertNotNull(model);
        assertEquals("model", model.category());
        assertEquals(Arrays.asList("gpt-5", "o3"), optionValues(model));
        // 未选择 → 生效默认模型（gpt-5 是 getModelOrDef(null) 的返回）
        assertEquals("gpt-5", model.currentValue());

        AcpSchema.SessionConfigSelect effort = findSelect(options, AcpSessionConfig.EFFORT);
        assertNotNull(effort);
        assertEquals("thought_level", effort.category());
        assertEquals("auto", effort.currentValue());
        // auto + 具体档位；不提供 none（Solon 里 none 等同关闭思考）
        assertTrue(optionValues(effort).contains("auto"));
        assertFalse(optionValues(effort).contains("none"));
        assertTrue(optionValues(effort).contains("low"));

        AcpSchema.SessionConfigSelect thinking = findSelect(options, AcpSessionConfig.THINKING);
        assertNotNull(thinking);
        assertEquals(AcpSessionConfig.THINKING, thinking.category());
        assertEquals(Arrays.asList("auto", "on", "off"), optionValues(thinking));
        assertEquals("auto", thinking.currentValue());

        AcpSchema.SessionConfigSelect mode = findSelect(options, AcpSessionConfig.MODE);
        assertNotNull(mode);
        assertEquals("mode", mode.category());
        assertEquals(Arrays.asList("ask", "reject"), optionValues(mode));
        assertEquals("ask", mode.currentValue());
    }

    @Test
    @DisplayName("options: 无启用模型时省略 model/thinking/effort，mode 仍在")
    void options_noEnabledModels() {
        when(engine.getModels()).thenReturn(Collections.emptyList());
        when(engine.getModelOrDef(null)).thenReturn(null);

        List<AcpSchema.SessionConfigOption> options = config.options(session);

        assertNull(findSelect(options, AcpSessionConfig.MODEL));
        assertNull(findSelect(options, AcpSessionConfig.EFFORT));
        assertNull(findSelect(options, AcpSessionConfig.THINKING));
        assertNotNull(findSelect(options, AcpSessionConfig.MODE));
    }

    @Test
    @DisplayName("options: 禁用模型不出现在选择列表")
    void options_disabledModelOmitted() {
        o3.setEnabled(false);

        AcpSchema.SessionConfigSelect model =
                findSelect(config.options(session), AcpSessionConfig.MODEL);
        assertEquals(Collections.singletonList("gpt-5"), optionValues(model));
    }

    @Test
    @DisplayName("options: hitl 未启用时 mode 只有 configured（不伪装成可绕过）")
    void options_hitlDisabled_onlyConfigured() {
        when(engine.isHitlEnabled()).thenReturn(false);

        AcpSchema.SessionConfigSelect mode =
                findSelect(config.options(session), AcpSessionConfig.MODE);
        assertNotNull(mode);
        assertEquals(Collections.singletonList("configured"), optionValues(mode));
        assertEquals("configured", mode.currentValue());
    }

    @Test
    @DisplayName("options: effort 档位随当前模型能力变化（o3 三档，无 max）")
    void options_effortFollowsModel() {
        // gpt-5 → 全四档
        AcpSchema.SessionConfigSelect effort =
                findSelect(config.options(session), AcpSessionConfig.EFFORT);
        assertTrue(optionValues(effort)
                .containsAll(Arrays.asList("auto", "low", "medium", "high", "max")));

        // 切到 o3（启发式三档）→ 选项随当前模型收缩
        config.set(session, AcpSessionConfig.MODEL, "o3");
        effort = findSelect(config.options(session), AcpSessionConfig.EFFORT);
        List<String> values = optionValues(effort);
        assertTrue(values.containsAll(Arrays.asList("auto", "low", "medium", "high")));
        assertFalse(values.contains("max"));
    }

    // ─────────────────── set ───────────────────

    @Test
    @DisplayName("set: model 走 setSessionModel 并回放全量选项")
    void set_model() {
        List<AcpSchema.SessionConfigOption> options =
                config.set(session, AcpSessionConfig.MODEL, "o3");

        assertEquals("o3", SessionSelections.modelOf(session));
        assertEquals("o3", findSelect(options, AcpSessionConfig.MODEL).currentValue());
    }

    @Test
    @DisplayName("set: effort 写入会话并更新 currentValue")
    void set_effort() {
        List<AcpSchema.SessionConfigOption> options =
                config.set(session, AcpSessionConfig.EFFORT, "high");

        assertEquals("high", ReasoningSupportUtil.getSessionEffort(session));
        assertEquals("high", findSelect(options, AcpSessionConfig.EFFORT).currentValue());
    }

    @Test
    @DisplayName("set: effort=auto 记为显式 auto 哨兵（抵消全局默认）")
    void set_effortAuto() {
        settings.getGeneral().setDefaultReasoningEffort("high");

        config.set(session, AcpSessionConfig.EFFORT, "auto");

        assertTrue(ReasoningSupportUtil.hasExplicitEffort(session));
        // 显式 auto → 生效值不吃全局默认
        assertNull(ReasoningSupportUtil.getSessionEffort(session));
        AcpSessionConfig.Snapshot snapshot = config.snapshot(session);
        assertNull(snapshot.effort);
    }

    @Test
    @DisplayName("set: thinking on/off 独立于 effort")
    void set_thinking() {
        config.set(session, AcpSessionConfig.THINKING, "off");
        assertEquals("off", ReasoningSupportUtil.getSessionThinkingMode(session));

        config.set(session, AcpSessionConfig.THINKING, "on");
        assertEquals("on", ReasoningSupportUtil.getSessionThinkingMode(session));

        config.set(session, AcpSessionConfig.THINKING, "auto");
        assertTrue(ReasoningSupportUtil.hasExplicitThinkingMode(session));
        assertNull(ReasoningSupportUtil.getSessionThinkingMode(session));
    }

    @Test
    @DisplayName("set: mode=reject 写入会话权限键")
    void set_modeReject() {
        config.set(session, AcpSessionConfig.MODE, "reject");

        assertEquals("reject", session.getContext().get(AcpSessionConfig.PERMISSION_KEY));
        assertEquals("reject", config.permissionMode(session));
    }

    @Test
    @DisplayName("set: 未知 configId 拒绝且不落任何状态")
    void set_unknownIdRejected() {
        AcpProtocolException e = assertThrows(AcpProtocolException.class,
                () -> config.set(session, "nonexistent", "zzz9"));
        assertEquals(AcpErrorCodes.INVALID_PARAMS, e.getCode());
        // 错误信息不原样回显用户提交的 value
        assertFalse(e.getMessage().contains("zzz9"));
        assertNull(session.getContext().get(AcpSessionConfig.PERMISSION_KEY));
        assertNull(ReasoningSupportUtil.getSessionEffort(session));
    }

    @Test
    @DisplayName("set: 非法 value 拒绝且不落状态")
    void set_invalidValueRejected() {
        assertThrows(AcpProtocolException.class,
                () -> config.set(session, AcpSessionConfig.EFFORT, "bogus"));
        assertThrows(AcpProtocolException.class,
                () -> config.set(session, AcpSessionConfig.MODE, "bypass"));
        // effort 的 none 不在选项里（避免语义混淆），同样拒绝
        assertThrows(AcpProtocolException.class,
                () -> config.set(session, AcpSessionConfig.EFFORT, "none"));

        assertNull(session.getContext().get(AcpSessionConfig.PERMISSION_KEY));
        assertNull(ReasoningSupportUtil.getSessionEffort(session));
    }

    @Test
    @DisplayName("set: hitl 未启用时 mode 只能选 configured")
    void set_modeConfiguredOnlyWhenHitlOff() {
        when(engine.isHitlEnabled()).thenReturn(false);

        assertThrows(AcpProtocolException.class,
                () -> config.set(session, AcpSessionConfig.MODE, "reject"));

        List<AcpSchema.SessionConfigOption> options =
                config.set(session, AcpSessionConfig.MODE, "configured");
        assertEquals("configured",
                findSelect(options, AcpSessionConfig.MODE).currentValue());
    }

    // ─────────────────── snapshot ───────────────────

    @Test
    @DisplayName("snapshot: 快照受理时的生效选择（模型/思考/强度/审批模式）")
    void snapshot_capturesEffectiveState() {
        config.set(session, AcpSessionConfig.MODEL, "o3");
        config.set(session, AcpSessionConfig.THINKING, "off");
        config.set(session, AcpSessionConfig.EFFORT, "high");
        config.set(session, AcpSessionConfig.MODE, "reject");

        AcpSessionConfig.Snapshot snapshot = config.snapshot(session);

        assertEquals("o3", snapshot.model);
        assertEquals("off", snapshot.thinking);
        // o3 能力含 high 档 → 原样快照
        assertEquals("high", snapshot.effort);
        assertEquals("reject", snapshot.permissionMode);

        // 快照不受后续 set 影响（受理后不可变）
        config.set(session, AcpSessionConfig.MODE, "ask");
        assertEquals("reject", snapshot.permissionMode);
    }

    @Test
    @DisplayName("snapshot: 会话选择失效时回落启用默认模型")
    void snapshot_disabledSelectionFallsBack() {
        config.set(session, AcpSessionConfig.MODEL, "o3");
        assertEquals("o3", config.snapshot(session).model);

        o3.setEnabled(false);
        assertEquals("gpt-5", config.snapshot(session).model);
    }

    @Test
    @DisplayName("snapshot: effort 越档按当前模型能力 clamp")
    void snapshot_effortClampedToCapability() {
        config.set(session, AcpSessionConfig.MODEL, "o3");
        // 直接写会话值绕过白名单（模拟遗留的 max）
        session.getContext().put(ReasoningSupportUtil.CTX_REASONING_EFFORT, "max");

        // o3 只支持到 high → clamp 到 high
        assertEquals("high", config.snapshot(session).effort);
    }
}

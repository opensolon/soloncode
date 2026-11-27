package org.noear.solon.codecli.entry.acp;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.stubbing.Answer;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.ReActResponse;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.intercept.ContextSizeEvent;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.ReasonDeltaEvent;
import org.noear.solon.ai.agent.react.task.ReasonEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallStartEvent;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.event.ChatEventDefault;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.ai.harness.command.CommandDispatcher;
import org.noear.solon.ai.harness.command.CommandRegistry;
import org.noear.solon.ai.harness.command.MarkdownCommand;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.harness.command.builtin.ClearCommand;
import org.noear.solon.ai.harness.command.builtin.HelpCommand;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.session.HarnessAttrs;
import org.noear.solon.ai.harness.session.OwnerCheck;
import org.noear.solon.ai.harness.session.SessionSelections;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.ai.harness.util.TraceUtil;
import org.noear.solon.codecli.command.builtin.ExitCommand;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.session.SessionManager;
import reactor.core.publisher.Flux;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ACP v1 stdio 端到端线协议测试：真实 {@link StdioAcpAgentTransport} + 内存管道，
 * 断言原始 JSON-RPC 报文。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>initialize → agentInfo / capabilities 如实广告；</li>
 *   <li>session/new、load、resume 响应把 configOptions 放在 result 顶层（AcpSetupTransport 提升）；</li>
 *   <li>session/set_config_option 合法值回放全量、非法 id/value 按 INVALID_PARAMS 拒绝且不改状态；</li>
 *   <li>load / resume 只认领本端发放且已存在的会话 ID；</li>
 *   <li>permission：allow-once → APPROVE、reject-once → REJECT、RPC 错误 → 失败关闭 REJECT、
 *       cancelled → 整轮 CANCELLED 且残留卡按拒绝收尾；</li>
 *   <li>未知会话 prompt → SESSION_NOT_FOUND。</li>
 * </ul>
 */
public class AcpStdioTest {

    @TempDir
    Path tempDir;

    private HarnessEngine engine;
    private SessionManager sessionManager;
    private AgentSettings settings;
    private AcpLink acpLink;

    private WireClient client;
    private Thread readerThread;

    /** handle 收到的决策输入（按调用顺序） */
    private final List<HarnessInput> decisionInputs = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        sessionManager = new SessionManager(tempDir.toString());
        engine = mock(HarnessEngine.class);
        when(engine.getName()).thenReturn("main");
        when(engine.getSessionProvider()).thenReturn(sessionManager);
        when(engine.getSession(anyString())).thenAnswer(
                inv -> sessionManager.getSession(inv.getArgument(0)));
        when(engine.getSessionOrNull(anyString())).thenAnswer(
                inv -> sessionManager.getSessionOrNull(inv.getArgument(0)));
        when(engine.checkOwner(anyString())).thenReturn(OwnerCheck.owned());
        when(engine.status(any())).thenAnswer(
                inv -> SessionStatus.of(inv.getArgument(0)));
        when(engine.isHitlEnabled()).thenReturn(true);
        when(engine.getSessionModel(any())).thenAnswer(
                inv -> SessionSelections.modelOf(inv.getArgument(0)));
        doAnswer(inv -> {
            SessionSelections.setModel(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(engine).setSessionModel(any(), any());
        doAnswer(inv -> {
            AgentSession s = inv.getArgument(0);
            String cwd = inv.getArgument(1);
            s.attrs().put(HarnessEngine.ATTR_CWD, cwd);
            s.getContext().put(HarnessAttrs.SESSION_CWD, cwd);
            return null;
        }).when(engine).setSessionCwd(any(), anyString());
        when(engine.getExtension(any())).thenReturn(Optional.empty());
        when(engine.interrupt(any(), any())).thenReturn(HarnessResult.ok(true));

        // 斜杠命令："/" 前缀 + 引擎工作区；注册表与生产一致只含 help / markdown / 终端命令
        when(engine.getCommandPrefix()).thenReturn("/");
        when(engine.getWorkspace()).thenReturn(tempDir.toString());
        when(engine.getLocale()).thenReturn(Locale.ENGLISH);
        CommandRegistry commands = new CommandRegistry();
        commands.register(new HelpCommand());
        commands.register(new MarkdownCommand("review", "Review $ARGUMENTS", "Review changes", null));
        MarkdownCommand disabled = spy(new MarkdownCommand("disabled", "unused", "Disabled", null));
        doReturn(false).when(disabled).isEnabled();
        commands.register(disabled);
        commands.register(new ClearCommand());
        commands.register(new ExitCommand());
        when(engine.getCommandRegistry()).thenReturn(commands);
        when(engine.getSessionCwd(any())).thenAnswer(
                inv -> (String) ((AgentSession) inv.getArgument(0)).attrs().get(HarnessEngine.ATTR_CWD));

        ChatConfig model = new ChatConfig();
        model.setName("gpt-5");
        model.setModel("gpt-5");
        model.setStandard("openai");
        when(engine.getModels()).thenReturn(Collections.singletonList(model));
        when(engine.getModelOrDef(any())).thenReturn(model);

        // 默认受理行为：非决策输入一律落成「仍有挂起卡」的 HITL 结局；
        // 决策输入真实写入 HITL 决策后按剩余未决数回报
        when(engine.handle(any(HarnessInput.class))).thenAnswer((Answer<InputOutcome>) inv -> {
            HarnessInput in = inv.getArgument(0);
            AgentSession s = in.getSession();
            if (in.isHitlDecision()) {
                decisionInputs.add(in);
                HITLTask task = HITL.getPendingTaskByCallUuid(s, in.getHitlCallId());
                if (task != null) {
                    if (in.getHitlAction() == HITLAction.APPROVE) {
                        HITL.approve(s, task);
                    } else {
                        HITL.reject(s, task, "rejected by test");
                    }
                }
            }
            return InputOutcome.hitl("main", "gpt-5", true, HitlCard.pendingCount(s));
        });

        settings = new AgentSettings();

        PipedOutputStream toAgent = new PipedOutputStream();
        PipedInputStream agentIn = new PipedInputStream(toAgent, 1 << 20);
        PipedInputStream fromAgent = new PipedInputStream(1 << 20);
        PipedOutputStream agentOut = new PipedOutputStream(fromAgent);

        StdioAcpAgentTransport transport =
                new StdioAcpAgentTransport(AcpJsonMapper.createDefault(), agentIn, agentOut);
        acpLink = new AcpLink(engine, transport, settings);
        acpLink.createAgent(transport).start().block();

        client = new WireClient(toAgent, fromAgent);
        readerThread = new Thread(client);
        readerThread.setDaemon(true);
        readerThread.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
    }

    // ─────────────────── 协议辅助 ───────────────────

    private ObjectNode params() {
        return client.mapper.createObjectNode();
    }

    private JsonNode request(String method, ObjectNode params) throws Exception {
        return client.request(method, params).get(30, TimeUnit.SECONDS);
    }

    private String newSession() throws Exception {
        ObjectNode p = params();
        p.put("cwd", tempDir.toString());
        p.putArray("mcpServers");
        JsonNode resp = request("session/new", p);
        assertNull(resp.get("error"), "session/new error: " + resp);
        return resp.get("result").get("sessionId").asText();
    }

    private AgentSession backendSession(String sessionId) {
        return sessionManager.getSessionOrNull(sessionId);
    }

    private void plantHitlCard(String sessionId, String callUuid, String tool, Map<String, Object> args) {
        AgentSession session = backendSession(sessionId);
        session.getContext().put(HITL.PENDING_TASKS,
                new ArrayList<>(Collections.singletonList(
                        new HITLTask(callUuid, tool, args, "test intercept"))));
    }

    private JsonNode prompt(String sessionId, String text) throws Exception {
        ObjectNode p = params();
        p.put("sessionId", sessionId);
        ObjectNode block = p.putArray("prompt").addObject();
        block.put("type", "text");
        block.put("text", text);
        return request("session/prompt", p);
    }

    /** session/update 里的可用命令广播（new / load / resume 前奏，与轮次更新分开断言） */
    private static boolean isCommandAnnouncement(JsonNode node) {
        return "session/update".equals(node.path("method").asText())
                && "available_commands_update".equals(node.at("/params/update/sessionUpdate").asText());
    }

    /** 从 configOptions 数组里取指定 id 的 select 节点 */
    private static JsonNode findOption(JsonNode configOptions, String id) {
        for (JsonNode o : configOptions) {
            if (id.equals(o.get("id").asText())) {
                return o;
            }
        }
        return null;
    }

    // ─────────────────── initialize ───────────────────

    @Test
    @DisplayName("initialize: agentInfo 与能力如实（无 audio / 无客户端 MCP / image+embeddedContext）")
    void initialize_advertisesCapabilities() throws Exception {
        ObjectNode p = params();
        p.put("protocolVersion", 1);
        p.putObject("clientCapabilities");

        JsonNode resp = request("initialize", p);
        JsonNode result = resp.get("result");
        assertNotNull(result, "no result: " + resp);

        assertEquals("soloncode", result.at("/agentInfo/name").asText());
        assertEquals("SolonCode", result.at("/agentInfo/title").asText());
        assertTrue(result.at("/agentInfo/version").asText().length() > 0);

        JsonNode caps = result.get("agentCapabilities");
        assertTrue(caps.get("loadSession").asBoolean());
        // 不广告无法真正兑现的能力
        assertFalse(caps.at("/mcpCapabilities/http").asBoolean());
        assertFalse(caps.at("/mcpCapabilities/sse").asBoolean());
        assertFalse(caps.at("/promptCapabilities/audio").asBoolean());
        assertTrue(caps.at("/promptCapabilities/image").asBoolean());
        assertTrue(caps.at("/promptCapabilities/embeddedContext").asBoolean());
        // load/close/resume 均支持（load 靠 loadSession 标记，sessionCapabilities 里 null=list 不支持）
        assertTrue(caps.at("/sessionCapabilities").has("close"));
        assertTrue(caps.at("/sessionCapabilities").has("resume"));
    }

    // ─────────────────── configOptions 顶层 ───────────────────

    @Test
    @DisplayName("session/new: configOptions 在 result 顶层（非 _meta），含四项选择器")
    void newSession_configOptionsTopLevel() throws Exception {
        ObjectNode p = params();
        p.put("cwd", tempDir.toString());
        p.putArray("mcpServers");

        JsonNode resp = request("session/new", p);
        JsonNode result = resp.get("result");
        assertNotNull(result, "no result: " + resp);
        assertNotNull(result.get("sessionId"));

        JsonNode options = result.get("configOptions");
        assertNotNull(options, "configOptions must be top-level: " + result);
        assertTrue(options.isArray());
        assertNull(result.get("_meta"), "carrier must be lifted out of _meta: " + result);

        assertNotNull(findOption(options, "model"));
        assertNotNull(findOption(options, "effort"));
        assertNotNull(findOption(options, "mode"));
        assertNotNull(findOption(options, AcpSessionConfig.THINKING));
        assertEquals("thought_level", findOption(options, "effort").get("category").asText());
        assertEquals(AcpSessionConfig.THINKING,
                findOption(options, AcpSessionConfig.THINKING).get("category").asText());
    }

    @Test
    @DisplayName("session/load 与 session/resume: 同样把 configOptions 放在顶层")
    void loadAndResume_configOptionsTopLevel() throws Exception {
        String sessionId = newSession();

        for (String method : Arrays.asList("session/load", "session/resume")) {
            ObjectNode p = params();
            p.put("sessionId", sessionId);
            p.put("cwd", tempDir.toString());
            p.putArray("mcpServers");

            JsonNode resp = request(method, p);
            JsonNode result = resp.get("result");
            assertNotNull(result, method + " no result: " + resp);
            assertTrue(result.get("configOptions").isArray(),
                    method + " configOptions must be top-level: " + result);
            assertNull(result.get("_meta"), method + ": " + result);
        }
    }

    @Test
    @DisplayName("session/load: 拒绝任意/不存在的会话 ID（SESSION_NOT_FOUND）")
    void load_rejectsUnknownOrForeignId() throws Exception {
        // 形态不符
        ObjectNode p = params();
        p.put("sessionId", "evil-id");
        p.putArray("mcpServers");
        JsonNode resp = request("session/load", p);
        assertEquals(-32002, resp.at("/error/code").asInt(), resp.toString());

        // 形态合法但不存在
        p = params();
        p.put("sessionId", UUID.randomUUID().toString());
        p.putArray("mcpServers");
        resp = request("session/load", p);
        assertEquals(-32002, resp.at("/error/code").asInt(), resp.toString());
    }

    @Test
    @DisplayName("session/resume: 形态合法的陌生 UUID 也不能绑出会话")
    void resume_rejectsForeignId() throws Exception {
        ObjectNode p = params();
        p.put("sessionId", UUID.randomUUID().toString());
        p.putArray("mcpServers");
        JsonNode resp = request("session/resume", p);
        assertEquals(-32002, resp.at("/error/code").asInt(), resp.toString());
    }

    // ─────────────────── set_config_option ───────────────────

    @Test
    @DisplayName("set_config_option: 合法值写会话并回放全量 configOptions")
    void setConfigOption_validWritesAndReturnsAll() throws Exception {
        String sessionId = newSession();

        ObjectNode p = params();
        p.put("sessionId", sessionId);
        p.put("configId", "effort");
        p.put("value", "high");
        JsonNode resp = request("session/set_config_option", p);

        JsonNode options = resp.at("/result/configOptions");
        assertTrue(options.isArray(), "set must return configOptions: " + resp);
        assertEquals("high", findOption(options, "effort").get("currentValue").asText());
    }

    @Test
    @DisplayName("set_config_option: 非法 id / value / type 拒绝且不改会话状态")
    void setConfigOption_invalidRejectedStateUnchanged() throws Exception {
        String sessionId = newSession();

        // 未知 id
        ObjectNode p = params();
        p.put("sessionId", sessionId);
        p.put("configId", "bogus");
        p.put("value", "x");
        JsonNode resp = request("session/set_config_option", p);
        assertEquals(-32602, resp.at("/error/code").asInt(), resp.toString());
        assertFalse(resp.at("/error/message").asText().contains("\"x\""));

        // 非法 value
        p = params();
        p.put("sessionId", sessionId);
        p.put("configId", "effort");
        p.put("value", "bogus");
        resp = request("session/set_config_option", p);
        assertEquals(-32602, resp.at("/error/code").asInt(), resp.toString());

        // boolean type 未发布 → 拒绝
        p = params();
        p.put("sessionId", sessionId);
        p.put("configId", "effort");
        p.put("value", true);
        p.put("type", "boolean");
        resp = request("session/set_config_option", p);
        assertEquals(-32602, resp.at("/error/code").asInt(), resp.toString());

        // 状态未被改写：重新拉选项仍是默认
        p = params();
        p.put("sessionId", sessionId);
        p.put("configId", "mode");
        p.put("value", "ask");
        resp = request("session/set_config_option", p);
        JsonNode options = resp.at("/result/configOptions");
        assertTrue(options.isArray(), resp.toString());
        assertEquals("auto", findOption(options, "effort").get("currentValue").asText());
    }

    @Test
    @DisplayName("set_config_option: 未知会话 → SESSION_NOT_FOUND")
    void setConfigOption_unknownSession() throws Exception {
        ObjectNode p = params();
        p.put("sessionId", UUID.randomUUID().toString());
        p.put("configId", "effort");
        p.put("value", "high");
        JsonNode resp = request("session/set_config_option", p);
        assertEquals(-32002, resp.at("/error/code").asInt(), resp.toString());
    }

    // ─────────────────── prompt + permission ───────────────────

    @Test
    @DisplayName("prompt: 未知会话 → SESSION_NOT_FOUND")
    void prompt_unknownSession() throws Exception {
        JsonNode resp = prompt(UUID.randomUUID().toString(), "hi");
        assertEquals(-32002, resp.at("/error/code").asInt(), resp.toString());
    }

    @Test
    void prompt_streamsReActCyclesInOrderWithoutRepeatingText() throws Exception {
        String sessionId = newSession();
        ReActTrace first = reasonTrace(sessionId, "reason-1");
        ReActTrace second = reasonTrace(sessionId, "reason-2");
        Map<String, Object> args = Collections.singletonMap("file_path", "fixture.txt");
        streamForPrompt(
                reasonDelta(first, ChatEventType.THINKING_DELTA, "inspect"),
                reasonDelta(first, ChatEventType.THINKING_END, "inspect"),
                reasonDelta(first, ChatEventType.TEXT_DELTA, "I will read."),
                reasonDelta(first, ChatEventType.TEXT_END, "I will read."),
                new ReasonEndEvent(first, null, new AssistantMessage("I will read.", "inspect"), 1),
                new ToolCallStartEvent(first, "read-1", "read", args),
                new ToolCallEndEvent(first, "read-1", "read", args, ChatMessage.ofUser("file missing"), true, null, 1),
                reasonDelta(second, ChatEventType.THINKING_DELTA, "recover"),
                reasonDelta(second, ChatEventType.TEXT_DELTA, "No"),
                reasonDelta(second, ChatEventType.TEXT_DELTA, " file."),
                new ReasonEndEvent(second, null, new AssistantMessage("No file.", "recover"), 1),
                runEnd(second, "No file."));

        List<JsonNode> updates = promptUpdates(sessionId);
        assertEquals(Arrays.asList("agent_thought_chunk", "agent_message_chunk", "tool_call", "tool_call_update",
                "agent_thought_chunk", "agent_message_chunk", "agent_message_chunk"),
                updates.stream().map(u -> u.path("sessionUpdate").asText()).collect(Collectors.toList()));
        assertEquals("inspect", updates.get(0).at("/content/text").asText());
        assertEquals("I will read.", updates.get(1).at("/content/text").asText());
        assertEquals("tc-read-1", updates.get(2).path("toolCallId").asText());
        assertEquals("in_progress", updates.get(2).path("status").asText());
        assertEquals("tc-read-1", updates.get(3).path("toolCallId").asText());
        assertEquals("failed", updates.get(3).path("status").asText());
        assertEquals("file missing", updates.get(3).path("rawOutput").asText());
        assertEquals("recover", updates.get(4).at("/content/text").asText());
        assertEquals("No", updates.get(5).at("/content/text").asText());
        assertEquals(" file.", updates.get(6).at("/content/text").asText());
    }

    @Test
    void prompt_thinkingOffKeepsBodyAndFallsBackPerReason() throws Exception {
        String sessionId = newSession();
        ObjectNode p = params();
        p.put("sessionId", sessionId);
        p.put("configId", AcpSessionConfig.THINKING);
        p.put("value", "off");
        JsonNode response = request("session/set_config_option", p);
        assertNull(response.get("error"), response.toString());

        ReActTrace first = reasonTrace(sessionId, "reason-1");
        ReActTrace second = reasonTrace(sessionId, "reason-2");
        streamForPrompt(
                reasonDelta(first, ChatEventType.THINKING_DELTA, "hidden delta"),
                reasonDelta(first, ChatEventType.TEXT_DELTA, "prelude"),
                new ReasonEndEvent(second, null, new AssistantMessage("fallback body", "hidden fallback"), 1),
                runEnd(second, "fallback body"));

        List<JsonNode> updates = promptUpdates(sessionId);
        assertEquals(Arrays.asList("agent_message_chunk", "agent_message_chunk"),
                updates.stream().map(u -> u.path("sessionUpdate").asText()).collect(Collectors.toList()));
        assertEquals("prelude", updates.get(0).at("/content/text").asText());
        assertEquals("fallback body", updates.get(1).at("/content/text").asText());
    }

    @Test
    void prompt_keepsDirectFinalTextAndUndeliveredThought() throws Exception {
        String sessionId = newSession();
        ReActTrace trace = reasonTrace(sessionId, "reason-1");
        streamForPrompt(
                reasonDelta(trace, ChatEventType.TEXT_DELTA, "prelude"),
                new ReasonEndEvent(trace, null, new AssistantMessage("prelude", "fallback thought"), 1),
                runEnd(trace, "direct result"));

        List<JsonNode> updates = promptUpdates(sessionId);
        assertEquals(Arrays.asList("agent_message_chunk", "agent_thought_chunk", "agent_message_chunk"),
                updates.stream().map(u -> u.path("sessionUpdate").asText()).collect(Collectors.toList()));
        assertEquals("prelude", updates.get(0).at("/content/text").asText());
        assertEquals("fallback thought", updates.get(1).at("/content/text").asText());
        assertEquals("direct result", updates.get(2).at("/content/text").asText());
    }

    @Test
    void prompt_doesNotRepeatAnAbnormalFinalDeltaAfterToolOutput() throws Exception {
        String sessionId = newSession();
        ReActTrace trace = reasonTrace(sessionId, "reason-1");
        when(trace.isAbnormal()).thenReturn(true);
        Map<String, Object> args = Collections.singletonMap("file_path", "fixture.txt");
        streamForPrompt(
                reasonDelta(trace, ChatEventType.TEXT_DELTA, "prelude"),
                new ToolCallStartEvent(trace, "read-1", "read", args),
                new ToolCallEndEvent(trace, "read-1", "read", args, ChatMessage.ofUser("direct result"), false, null, 1),
                reasonDelta(trace, ChatEventType.TEXT_DELTA, "direct result"),
                runEnd(trace, "direct result"));

        List<JsonNode> updates = promptUpdates(sessionId);
        assertEquals(Arrays.asList("agent_message_chunk", "tool_call", "tool_call_update", "agent_message_chunk"),
                updates.stream().map(u -> u.path("sessionUpdate").asText()).collect(Collectors.toList()));
        assertEquals("prelude", updates.get(0).at("/content/text").asText());
        assertEquals("direct result", updates.get(3).at("/content/text").asText());
    }

    @Test
    void prompt_reportsContextUsageBeforeAndAfterCompression() throws Exception {
        String sessionId = newSession();
        ReActTrace first = reasonTrace(sessionId, "reason-1");
        ReActTrace second = reasonTrace(sessionId, "reason-2");
        streamForPrompt(
                new ContextSizeEvent(first, 65536, 15, 7100, false, 0, 0, 0, 0),
                reasonDelta(first, ChatEventType.TEXT_DELTA, "before"),
                new ContextSizeEvent(second, 65536, 8, 3040, true, 15, 8, 7100, 3040),
                runEnd(second, "after"));

        List<JsonNode> updates = promptUpdates(sessionId);
        assertEquals(Arrays.asList("usage_update", "agent_message_chunk", "usage_update", "agent_message_chunk"),
                updates.stream().map(u -> u.path("sessionUpdate").asText()).collect(Collectors.toList()));
        assertEquals(7100, updates.get(0).path("used").asLong());
        assertEquals(3040, updates.get(2).path("used").asLong());
        for (JsonNode usage : Arrays.asList(updates.get(0), updates.get(2))) {
            assertEquals(65536, usage.path("size").asLong());
            assertEquals("gpt-5", usage.at("/_meta/model").asText());
            assertFalse(usage.has("cost"));
        }
    }

    private ReActTrace reasonTrace(String sessionId, String turnId) {
        ReActTrace trace = mock(ReActTrace.class);
        when(trace.getRunId()).thenReturn("engine-run");
        when(trace.getCurrentTurnId()).thenReturn(turnId);
        when(trace.getAgentName()).thenReturn("main");
        when(trace.getSession()).thenReturn(backendSession(sessionId));
        return trace;
    }

    private static ReasonDeltaEvent reasonDelta(ReActTrace trace, ChatEventType type, String text) {
        return new ReasonDeltaEvent(trace, ChatEventDefault.of(type).text(text).build());
    }

    private static RunEndEvent runEnd(ReActTrace trace, String text) {
        return new RunEndEvent(trace, new ReActResponse(trace.getSession(), trace, new AssistantMessage(text, "")));
    }

    private void streamForPrompt(AgentEvent... events) {
        ReActRequest react = mock(ReActRequest.class);
        when(react.stream()).thenReturn(Flux.just(events));
        doReturn(InputOutcome.task(null, "main", "gpt-5", react)).when(engine).handle(any(HarnessInput.class));
    }

    private List<JsonNode> promptUpdates(String sessionId) throws Exception {
        JsonNode response = prompt(sessionId, "stream fixture");
        assertEquals("end_turn", response.at("/result/stopReason").asText(), response.toString());
        return client.received.stream()
                .filter(node -> "session/update".equals(node.path("method").asText()))
                .filter(node -> !isCommandAnnouncement(node))
                .map(node -> node.at("/params/update"))
                .filter(update -> !"config_option_update".equals(update.path("sessionUpdate").asText()))
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("permission: allow-once → APPROVE 决策（resume 续流），轮次 END_TURN")
    void permission_allowOnceApproves() throws Exception {
        String sessionId = newSession();
        plantHitlCard(sessionId, "call-1", "bash",
                Collections.singletonMap("command", "rm -rf build"));

        BlockingQueue<JsonNode> permissionParams = new LinkedBlockingQueue<>();
        client.requestResponder = node -> {
            permissionParams.add(node.get("params"));
            ObjectNode result = client.mapper.createObjectNode();
            ObjectNode outcome = result.putObject("outcome");
            outcome.put("outcome", "selected");
            outcome.put("optionId", "allow-once");
            return result;
        };

        JsonNode resp = prompt(sessionId, "run it");
        assertNull(resp.get("error"), "prompt error: " + resp);
        assertEquals("end_turn", resp.at("/result/stopReason").asText(), resp.toString());

        // 审批请求确实发到客户端，内容指向同一张卡
        JsonNode pp = permissionParams.poll(5, TimeUnit.SECONDS);
        assertNotNull(pp, "expected session/request_permission request");
        assertEquals(sessionId, pp.get("sessionId").asText());
        assertEquals("tc-call-1", pp.at("/toolCall/toolCallId").asText());
        assertEquals("pending", pp.at("/toolCall/status").asText());
        boolean hasAllow = false, hasReject = false;
        for (JsonNode o : pp.get("options")) {
            if ("allow-once".equals(o.get("optionId").asText())) hasAllow = true;
            if ("reject-once".equals(o.get("optionId").asText())) hasReject = true;
        }
        assertTrue(hasAllow && hasReject, "options: " + pp.get("options"));

        // allow-once 精确映射为 APPROVE 且最后一个决策带 resume
        assertEquals(1, decisionInputs.size());
        HarnessInput decision = decisionInputs.get(0);
        assertTrue(decision.isHitlDecision());
        assertEquals(HITLAction.APPROVE, decision.getHitlAction());
        assertEquals("call-1", decision.getHitlCallId());
        assertTrue(decision.isResume());
        // 决策真实落到了会话（卡不再未决）
        assertEquals(0, HitlCard.pendingCount(backendSession(sessionId)));
    }

    @Test
    @DisplayName("permission: reject-once → REJECT 决策")
    void permission_rejectOnceRejects() throws Exception {
        String sessionId = newSession();
        plantHitlCard(sessionId, "call-1", "write",
                Collections.singletonMap("file_path", "/tmp/x.txt"));

        client.requestResponder = node -> {
            ObjectNode result = client.mapper.createObjectNode();
            ObjectNode outcome = result.putObject("outcome");
            outcome.put("outcome", "selected");
            outcome.put("optionId", "reject-once");
            return result;
        };

        JsonNode resp = prompt(sessionId, "do it");
        assertEquals("end_turn", resp.at("/result/stopReason").asText(), resp.toString());
        assertEquals(1, decisionInputs.size());
        assertEquals(HITLAction.REJECT, decisionInputs.get(0).getHitlAction());
    }

    @Test
    @DisplayName("permission: 审批 RPC 出错 → 失败关闭按 REJECT")
    void permission_errorFailsClosed() throws Exception {
        String sessionId = newSession();
        plantHitlCard(sessionId, "call-1", "bash",
                Collections.singletonMap("command", "ls"));

        // 客户端对 request_permission 回 JSON-RPC 错误
        client.requestResponder = null;
        client.requestErrorResponder = node -> -32603;

        JsonNode resp = prompt(sessionId, "do it");
        assertEquals("end_turn", resp.at("/result/stopReason").asText(), resp.toString());
        assertEquals(1, decisionInputs.size());
        assertEquals(HITLAction.REJECT, decisionInputs.get(0).getHitlAction());
    }

    @Test
    @DisplayName("permission: cancelled → 整轮 CANCELLED；残留卡按拒绝收尾、不续流")
    void permission_cancelledStopsTurn() throws Exception {
        String sessionId = newSession();
        plantHitlCard(sessionId, "call-1", "bash",
                Collections.singletonMap("command", "rm -rf /"));
        plantSecondCard(sessionId, "call-2", "write",
                Collections.singletonMap("file_path", "/tmp/y.txt"));

        client.requestResponder = node -> {
            ObjectNode result = client.mapper.createObjectNode();
            result.putObject("outcome").put("outcome", "cancelled");
            return result;
        };

        JsonNode resp = prompt(sessionId, "do it");
        assertEquals("cancelled", resp.at("/result/stopReason").asText(), resp.toString());

        // 两张卡都按 REJECT 写入且不续流（resume=false）
        assertEquals(2, decisionInputs.size(), "both cards must be rejected without resume");
        for (HarnessInput d : decisionInputs) {
            assertEquals(HITLAction.REJECT, d.getHitlAction());
            assertFalse(d.isResume());
        }
        assertEquals(0, HitlCard.pendingCount(backendSession(sessionId)));
    }

    private void plantSecondCard(String sessionId, String callUuid, String tool, Map<String, Object> args) {
        AgentSession session = backendSession(sessionId);
        List<HITLTask> tasks = new ArrayList<>(HITL.getPendingTasks(session));
        tasks.add(new HITLTask(callUuid, tool, args, "test intercept"));
        session.getContext().put(HITL.PENDING_TASKS, tasks);
    }

    @Test
    @DisplayName("permission: mode=reject 时不再询问客户端，直接拒绝")
    void permission_modeRejectSkipsClient() throws Exception {
        String sessionId = newSession();

        ObjectNode p = params();
        p.put("sessionId", sessionId);
        p.put("configId", "mode");
        p.put("value", "reject");
        JsonNode setResp = request("session/set_config_option", p);
        assertNull(setResp.get("error"), "set mode error: " + setResp);

        plantHitlCard(sessionId, "call-1", "bash",
                Collections.singletonMap("command", "ls"));

        AtomicLong permissionRequests = new AtomicLong();
        client.requestResponder = node -> {
            permissionRequests.incrementAndGet();
            ObjectNode result = client.mapper.createObjectNode();
            ObjectNode outcome = result.putObject("outcome");
            outcome.put("outcome", "selected");
            outcome.put("optionId", "allow-once");
            return result;
        };

        JsonNode resp = prompt(sessionId, "do it");
        assertEquals("end_turn", resp.at("/result/stopReason").asText(), resp.toString());
        assertEquals(0, permissionRequests.get(), "mode=reject must not ask the client");
        assertEquals(1, decisionInputs.size());
        assertEquals(HITLAction.REJECT, decisionInputs.get(0).getHitlAction());
    }

    // ─────────────────── cwd 校验与工作区绑定 ───────────────────

    @Test
    @DisplayName("session/new: 相对 / 不存在的 cwd 按 INVALID_PARAMS 拒绝（不回落进程目录）")
    void newSession_rejectsBadCwd() throws Exception {
        ObjectNode p = params();
        p.put("cwd", "relative/path");
        p.putArray("mcpServers");
        JsonNode resp = request("session/new", p);
        assertEquals(-32602, resp.at("/error/code").asInt(), resp.toString());

        p = params();
        p.put("cwd", tempDir.resolve("missing-" + UUID.randomUUID()).toString());
        p.putArray("mcpServers");
        resp = request("session/new", p);
        assertEquals(-32602, resp.at("/error/code").asInt(), resp.toString());

        // 省略与空白同样拒绝：cwd 是必传参数，不允许静默回落进程目录
        p = params();
        p.putArray("mcpServers");
        resp = request("session/new", p);
        assertEquals(-32602, resp.at("/error/code").asInt(), resp.toString());

        p = params();
        p.put("cwd", "   ");
        p.putArray("mcpServers");
        resp = request("session/new", p);
        assertEquals(-32602, resp.at("/error/code").asInt(), resp.toString());
    }

    @Test
    @DisplayName("session/load: 请求目录与会话已绑定工作区不一致 → INVALID_PARAMS")
    void load_rejectsMismatchedCwd() throws Exception {
        String sessionId = newSession();

        Path other = tempDir.resolve("other");
        java.nio.file.Files.createDirectories(other);

        ObjectNode p = params();
        p.put("sessionId", sessionId);
        p.put("cwd", other.toString());
        p.putArray("mcpServers");
        JsonNode resp = request("session/load", p);
        assertEquals(-32602, resp.at("/error/code").asInt(), resp.toString());

        // 同一目录（规范化后一致）照旧放行
        p = params();
        p.put("sessionId", sessionId);
        p.put("cwd", tempDir.toString());
        p.putArray("mcpServers");
        resp = request("session/load", p);
        assertNull(resp.get("error"), "load with bound cwd: " + resp);
    }

    @Test
    void prompt_usesTheExistingNativeSession() throws Exception {
        String sessionId = newSession();
        AgentSession session = backendSession(sessionId);
        doAnswer(inv -> {
            HarnessInput input = inv.getArgument(0);
            assertSame(session, input.getSession());
            return InputOutcome.hitl("main", "gpt-5", true, 0);
        }).when(engine).handle(any(HarnessInput.class));

        assertEquals("end_turn", prompt(sessionId, "hello").at("/result/stopReason").asText());
        verify(engine, times(1)).getSession(sessionId);
    }

    @Test
    void close_doesNotEvictASessionStillInUse() throws Exception {
        String sessionId = newSession();
        AgentSession session = backendSession(sessionId);
        SessionStatus occupied = SessionStatus.builder(session).runningSubtaskCount(1).build();
        when(engine.status(session)).thenReturn(occupied);

        ObjectNode p = params();
        p.put("sessionId", sessionId);
        JsonNode response = request("session/close", p);
        assertNull(response.get("error"), response.toString());
        assertSame(session, sessionManager.getLoadedSession(sessionId));
        assertEquals(-32002, prompt(sessionId, "closed").at("/error/code").asInt());
    }

    @Test
    @DisplayName("session/load: 历史回放通知先于 load 响应到达")
    void load_replaysHistoryBeforeResult() throws Exception {
        String sessionId = newSession();
        AgentSession session = backendSession(sessionId);
        session.getMessages().add(org.noear.solon.ai.chat.message.ChatMessage.ofUser("历史问题"));
        session.getMessages().add(new org.noear.solon.ai.chat.message.AssistantMessage("历史答案", "历史思考"));

        ObjectNode p = params();
        p.put("sessionId", sessionId);
        p.put("cwd", tempDir.toString());
        p.putArray("mcpServers");
        JsonNode resp = request("session/load", p);
        assertNull(resp.get("error"), "load error: " + resp);

        // 线上顺序：user_message_chunk → agent_thought_chunk → agent_message_chunk，全部先于响应
        List<String> kinds = new ArrayList<>();
        int loadRespIdx = -1;
        List<Integer> updateIdx = new ArrayList<>();
        for (int i = 0; i < client.received.size(); i++) {
            JsonNode node = client.received.get(i);
            if (isCommandAnnouncement(node)) {
                continue;
            }
            if ("session/update".equals(node.path("method").asText())) {
                kinds.add(node.at("/params/update/sessionUpdate").asText());
                updateIdx.add(i);
            } else if (node.has("id") && !node.has("method")
                    && node.path("result").has("configOptions")) {
                loadRespIdx = i;
            }
        }
        assertTrue(loadRespIdx >= 0, "load response not seen on the wire");
        assertEquals(Arrays.asList("user_message_chunk", "agent_thought_chunk", "agent_message_chunk"),
                kinds, "replay order");
        for (int idx : updateIdx) {
            assertTrue(idx < loadRespIdx, "replay notification must precede load response");
        }
    }

    @Test
    void load_replaysAlignedToolTraceBeforeFinalAnswer() throws Exception {
        String sessionId = newSession();
        AgentSession session = backendSession(sessionId);
        ReActTrace trace = spy(new ReActTrace());
        doReturn(Prompt.of("List the directory")).when(trace).getOriginalPrompt();
        session.getContext().put(TraceUtil.TRACE_KEY_MAIN, trace);
        ChatMessage user = ChatMessage.ofUser("List the directory");
        AssistantMessage answer = new AssistantMessage("There is fixture.txt", "");
        user.addMetadata(AgentTrace.META_RUN_ID, trace.getRunId());
        answer.addMetadata(AgentTrace.META_RUN_ID, trace.getRunId());
        session.getMessages().add(user);
        session.getMessages().add(answer);
        ToolCall tool = new ToolCall("0", "call_list", "ls", "{}", Collections.emptyMap());
        AssistantMessage process = new AssistantMessage("Checking", "Inspect files", Collections.singletonList(tool), Collections.emptyList());
        trace.getWorkingMemory().addMessage(user);
        trace.getWorkingMemory().addMessage(process);
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("fixture.txt", "ls", "call_list"));
        trace.getWorkingMemory().addMessage(answer);

        ObjectNode p = params();
        p.put("sessionId", sessionId);
        p.put("cwd", tempDir.toString());
        p.putArray("mcpServers");
        JsonNode resumed = request("session/resume", p);
        assertNull(resumed.get("error"), resumed.toString());
        assertTrue(client.received.stream()
                .noneMatch(node -> "session/update".equals(node.path("method").asText())
                        && !isCommandAnnouncement(node)));
        JsonNode response = request("session/load", p);
        assertNull(response.get("error"), response.toString());
        List<JsonNode> updates = client.received.stream()
                .filter(node -> "session/update".equals(node.path("method").asText()))
                .filter(node -> !isCommandAnnouncement(node))
                .peek(node -> assertEquals(sessionId, node.at("/params/sessionId").asText()))
                .map(node -> node.at("/params/update"))
                .collect(Collectors.toList());
        assertEquals(Arrays.asList("user_message_chunk", "agent_thought_chunk", "agent_message_chunk", "tool_call", "agent_message_chunk"),
                updates.stream().map(update -> update.path("sessionUpdate").asText()).collect(Collectors.toList()));
        assertEquals("Inspect files", updates.get(1).at("/content/text").asText());
        assertEquals("tc-" + tool.getUuid(), updates.get(3).path("toolCallId").asText());
        assertEquals("completed", updates.get(3).path("status").asText());
        assertEquals("fixture.txt", updates.get(3).path("rawOutput").asText());
        assertEquals("There is fixture.txt", updates.get(4).at("/content/text").asText());
    }

    @Test
    @DisplayName("session/close: 未知会话 → SESSION_NOT_FOUND（不触碰 harness）")
    void close_unknownSession() throws Exception {
        ObjectNode p = params();
        p.put("sessionId", "acp-" + UUID.randomUUID());
        JsonNode resp = request("session/close", p);
        assertEquals(-32002, resp.at("/error/code").asInt(), resp.toString());
    }

    // ─────────────────── session/cancel ───────────────────

    @Test
    @DisplayName("session/cancel: 未答复的审批被中止——迟到 allow 不再批准/续流，挂起卡就地了结；下一轮照常")
    void cancel_lateAllowDoesNotApproveOrResume() throws Exception {
        String sessionId = newSession();
        plantHitlCard(sessionId, "call-1", "bash",
                Collections.singletonMap("command", "rm -rf build"));

        // 不答复审批：responder 留空，请求只进 agentRequests 队列
        ObjectNode p = params();
        p.put("sessionId", sessionId);
        ObjectNode block = p.putArray("prompt").addObject();
        block.put("type", "text");
        block.put("text", "run it");
        CompletableFuture<JsonNode> promptFuture = client.request("session/prompt", p);

        // 等审批请求到达（证明轮次确实停在屏障上）
        JsonNode permissionReq = client.agentRequests.poll(15, TimeUnit.SECONDS);
        assertNotNull(permissionReq, "expected session/request_permission on the wire");
        assertEquals("session/request_permission", permissionReq.get("method").asText());

        // 用户取消：纯通知
        ObjectNode cancelP = params();
        cancelP.put("sessionId", sessionId);
        client.notify("session/cancel", cancelP);

        JsonNode resp = promptFuture.get(30, TimeUnit.SECONDS);
        assertNull(resp.get("error"), "prompt error: " + resp);
        assertEquals("cancelled", resp.at("/result/stopReason").asText(), resp.toString());

        // 迟到的 allow 答复：不得落成 APPROVE / 续流决策
        ObjectNode late = client.mapper.createObjectNode();
        late.putObject("outcome").put("outcome", "selected").put("optionId", "allow-once");
        client.respondTo(permissionReq, late);

        // 给迟到的答复一点时间走完管线，再断言没有任何决策写入 harness
        Thread.sleep(500);
        assertEquals(0, decisionInputs.size(),
                "no decision (incl. late allow) may reach the harness after cancel: " + decisionInputs);
        // 挂起卡已被取消清理就地了结，不会给下一轮留屏障
        assertEquals(0, HitlCard.pendingCount(backendSession(sessionId)));

        // 取消只影响那一轮：普通后续轮次照常走完
        JsonNode next = prompt(sessionId, "hello again");
        assertNull(next.get("error"), "next prompt error: " + next);
        assertEquals("end_turn", next.at("/result/stopReason").asText(), next.toString());
    }

    @Test
    @DisplayName("session/cancel: 未知会话是纯空操作")
    void cancel_unknownSessionIsNoOp() throws Exception {
        ObjectNode p = params();
        p.put("sessionId", "acp-" + UUID.randomUUID());
        client.notify("session/cancel", p);

        // 未知取消不影响已知会话：正常 prompt 仍然走通
        String sessionId = newSession();
        JsonNode resp = prompt(sessionId, "hi");
        assertNull(resp.get("error"), "prompt after foreign cancel: " + resp);
        assertEquals("end_turn", resp.at("/result/stopReason").asText(), resp.toString());
        verify(engine, never()).interrupt(any(), any());
    }

    // ─────────────────── 斜杠命令 ───────────────────

    @Test
    @DisplayName("session/new、load、resume：响应前以 available_commands_update 广播可用命令")
    void setup_announcesAvailableCommandsWithoutAPrompt() throws Exception {
        int start = client.received.size();
        String sessionId = newSession();
        JsonNode announcement = singleAnnouncementSince(start);
        assertEquals(sessionId, announcement.at("/params/sessionId").asText());
        JsonNode list = announcement.at("/params/update/availableCommands");
        assertTrue(list.isArray());
        assertEquals(2, list.size());
        assertEquals("help", list.get(0).path("name").asText());
        assertEquals("[command]", list.get(0).at("/input/hint").asText());
        assertEquals("review", list.get(1).path("name").asText());
        assertEquals("Review changes", list.get(1).path("description").asText());

        for (String method : Arrays.asList("session/load", "session/resume")) {
            start = client.received.size();
            ObjectNode p = params();
            p.put("sessionId", sessionId);
            p.put("cwd", tempDir.toString());
            p.putArray("mcpServers");
            JsonNode resp = request(method, p);
            assertNull(resp.get("error"), method + " error: " + resp);
            JsonNode update = singleAnnouncementSince(start);
            assertEquals(sessionId, update.at("/params/sessionId").asText());
            assertEquals(2, update.at("/params/update/availableCommands").size(), method);
        }

        // 非引擎工作区的会话：Markdown 自定义命令不外借，只广播 help
        Path other = tempDir.resolve("other-ws");
        java.nio.file.Files.createDirectories(other);
        start = client.received.size();
        ObjectNode p = params();
        p.put("cwd", other.toString());
        p.putArray("mcpServers");
        JsonNode resp = request("session/new", p);
        assertNull(resp.get("error"), resp.toString());
        JsonNode otherList = singleAnnouncementSince(start).at("/params/update/availableCommands");
        assertEquals(1, otherList.size());
        assertEquals("help", otherList.get(0).path("name").asText());
    }

    /** 从 start 起恰好一条命令广播（多一条少一条都算失败） */
    private JsonNode singleAnnouncementSince(int start) {
        List<JsonNode> updates = client.received.subList(start, client.received.size()).stream()
                .filter(AcpStdioTest::isCommandAnnouncement)
                .collect(Collectors.toList());
        assertEquals(1, updates.size(), "announcements since " + start);
        return updates.get(0);
    }

    @Test
    @DisplayName("prompt: 仅已广播的命令生效——/help 直接回执、/review 展开任务、/clear 与含附件文本按原文")
    void prompt_runsOnlyAdvertisedSlashCommands() throws Exception {
        String sessionId = newSession();

        // /help 不经引擎：直接回执命令清单（只列已发布的命令）
        JsonNode helpResp = prompt(sessionId, "/help");
        assertEquals("end_turn", helpResp.at("/result/stopReason").asText(), helpResp.toString());
        verify(engine, never()).handle(any(HarnessInput.class));
        String helpText = client.received.stream()
                .filter(node -> "session/update".equals(node.path("method").asText()))
                .map(node -> node.at("/params/update"))
                .filter(u -> "agent_message_chunk".equals(u.path("sessionUpdate").asText()))
                .map(u -> u.at("/content/text").asText())
                .collect(Collectors.joining());
        assertTrue(helpText.contains("/help -"), helpText);
        assertTrue(helpText.contains("/review -"), helpText);
        assertFalse(helpText.contains("/exit"), helpText);
        assertFalse(helpText.contains("/clear"), helpText);

        // 命令感知只放给已发布的 Markdown 命令；未发布的 /clear 按原文发给模型
        List<String> texts = new CopyOnWriteArrayList<>();
        ReActRequest react = mock(ReActRequest.class);
        when(react.stream()).thenReturn(Flux.empty());
        doAnswer(inv -> {
            HarnessInput in = inv.getArgument(0);
            if (in.isCommandAware()) {
                return CommandDispatcher.dispatch(engine, in, ParsedCommand.parse(in.getText()));
            }
            texts.add(in.getText());
            return InputOutcome.task(null, "main", "gpt-5", react);
        }).when(engine).handle(any(HarnessInput.class));

        assertEquals("end_turn", prompt(sessionId, "/review main").at("/result/stopReason").asText());
        assertEquals("end_turn", prompt(sessionId, "/clear").at("/result/stopReason").asText());

        // 混有附件块时 "/review main" 不解析为命令：拼出的原文原样送模型，不展开
        Path fixture = tempDir.resolve("fixture.txt");
        java.nio.file.Files.write(fixture, "x".getBytes(StandardCharsets.UTF_8));
        ObjectNode p = params();
        p.put("sessionId", sessionId);
        ArrayNode blocks = p.putArray("prompt");
        ObjectNode block = blocks.addObject();
        block.put("type", "text");
        block.put("text", "/review main");
        ObjectNode link = blocks.addObject();
        link.put("type", "resource_link");
        link.put("name", "fixture.txt");
        link.put("uri", fixture.toUri().toString());
        JsonNode resp = request("session/prompt", p);
        assertEquals("end_turn", resp.at("/result/stopReason").asText(), resp.toString());

        assertEquals(Arrays.asList("Review main", "/clear", "/review main [fixture.txt]"), texts);
    }

    // ─────────────────── 线协议客户端 ───────────────────

    /**
     * 极简 ACP 客户端：按行读 agent 输出 —— 响应配对 pending、通知入队、
     * agent→client 请求（request_permission）交给可替换的 responder。
     */
    private static final class WireClient implements Runnable, Closeable {
        final ObjectMapper mapper = new ObjectMapper();
        private final OutputStream out;
        private final InputStream rawIn;
        private final BufferedReader in;
        private final AtomicLong ids = new AtomicLong();
        private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        final BlockingQueue<JsonNode> notifications = new LinkedBlockingQueue<>();
        final BlockingQueue<JsonNode> agentRequests = new LinkedBlockingQueue<>();
        /** 线上到达的全部报文（按序）：用来断言通知先于响应、迟到答复的位置等 */
        final List<JsonNode> received = new CopyOnWriteArrayList<>();
        volatile Function<JsonNode, ObjectNode> requestResponder;
        volatile Function<JsonNode, Integer> requestErrorResponder;
        private volatile boolean running = true;

        WireClient(OutputStream out, InputStream in) {
            this.out = out;
            this.rawIn = in;
            this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        CompletableFuture<JsonNode> request(String method, ObjectNode params) {
            long id = ids.incrementAndGet();
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pending.put(id, future);
            ObjectNode req = mapper.createObjectNode();
            req.put("jsonrpc", "2.0");
            req.put("id", id);
            req.put("method", method);
            req.set("params", params);
            write(req);
            return future;
        }

        /** 无 id 的通知（session/cancel 等）：对等方不会回响应 */
        void notify(String method, ObjectNode params) {
            ObjectNode req = mapper.createObjectNode();
            req.put("jsonrpc", "2.0");
            req.put("method", method);
            req.set("params", params);
            write(req);
        }

        /** 对 agent→client 请求补一条迟到响应（取消测试用） */
        void respondTo(JsonNode agentRequest, ObjectNode result) {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("jsonrpc", "2.0");
            resp.set("id", agentRequest.get("id"));
            resp.set("result", result);
            write(resp);
        }

        private synchronized void write(JsonNode node) {
            try {
                out.write((mapper.writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void run() {
            try {
                String line;
                while (running && (line = in.readLine()) != null) {
                    JsonNode node = mapper.readTree(line);
                    received.add(node);
                    if (node.has("method") && node.has("id")) {
                        // agent → client 请求（session/request_permission）
                        agentRequests.add(node);
                        Function<JsonNode, ObjectNode> responder = requestResponder;
                        Function<JsonNode, Integer> errorResponder = requestErrorResponder;
                        if (errorResponder != null) {
                            ObjectNode err = mapper.createObjectNode();
                            err.put("jsonrpc", "2.0");
                            err.set("id", node.get("id"));
                            err.putObject("error")
                                    .put("code", errorResponder.apply(node))
                                    .put("message", "client-side failure");
                            write(err);
                        } else if (responder != null) {
                            ObjectNode resp = mapper.createObjectNode();
                            resp.put("jsonrpc", "2.0");
                            resp.set("id", node.get("id"));
                            resp.set("result", responder.apply(node));
                            write(resp);
                        }
                    } else if (node.has("method")) {
                        notifications.add(node);
                    } else if (node.has("id")) {
                        CompletableFuture<JsonNode> f = pending.remove(node.get("id").asLong());
                        if (f != null) {
                            f.complete(node);
                        }
                    }
                }
            } catch (IOException ignored) {
            }
            // 断流：放行所有等待者
            for (CompletableFuture<JsonNode> f : pending.values()) {
                f.completeExceptionally(new IOException("wire closed"));
            }
        }

        @Override
        public void close() throws IOException {
            running = false;
            // 关裸管道而不是 BufferedReader：后者要先拿到读锁，而读线程正阻塞在 readLine 里
            rawIn.close();
        }
    }
}

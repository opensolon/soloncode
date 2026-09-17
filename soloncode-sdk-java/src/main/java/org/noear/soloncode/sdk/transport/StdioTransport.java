/*
 * Copyright 2025 soloncode
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.noear.soloncode.sdk.transport;

import org.noear.soloncode.sdk.util.SdkJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.noear.soloncode.sdk.config.SolonCodeCliDiscovery;
import org.noear.soloncode.sdk.config.PermissionMode;
import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.exceptions.SessionClosedException;
import org.noear.soloncode.sdk.exceptions.TransportException;
import org.noear.soloncode.sdk.mcp.McpServerConfig;
import org.noear.soloncode.sdk.parsing.ControlMessageParser;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.types.control.ControlRequest;
import org.noear.soloncode.sdk.types.control.ControlResponse;
import org.noear.soloncode.sdk.util.SdkCollections;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Streaming transport for SolonCode CLI communication over stdio. Manages the subprocess
 * lifecycle and handles JSON message streaming via stdin/stdout.
 *
 * <p>
 * {@link Transport} 的默认实现：在本机拉起 {@code soloncode run} 子进程，提示词经 argv
 * 或 stdin 管道投递，stream-json 事件流从 stdout 逐行读回。
 * </p>
 *
 * <p>
 * Key features:
 * </p>
 * <ul>
 * <li>Uses --input-format stream-json for sending messages to CLI</li>
 * <li>Uses --output-format stream-json for receiving messages</li>
 * <li>Uses --permission-prompt-tool stdio for hook/permission callbacks</li>
 * <li>Handles both regular messages and control requests</li>
 * <li>Thread-safe response writing with scheduler separation</li>
 * <li>Explicit state machine for lifecycle management</li>
 * <li>Reactive Sinks for message buffering with backpressure</li>
 * <li>Iterator-based API for non-reactive consumers</li>
 * </ul>
 *
 * @see Transport
 * @see TransportSpec
 * @see ControlMessageParser
 * @see ControlRequest
 * @see ControlResponse
 */
public class StdioTransport implements Transport {

	private static final Logger logger = LoggerFactory.getLogger(StdioTransport.class);

	// ============================================================
	// Configuration
	// ============================================================

	private final String soloncodeCommand;

	private final Path workingDirectory;

	private final Duration defaultTimeout;

	/** true 使用 soloncode stream 常驻协议；false 使用兼容的 soloncode run 单次协议。 */
	private final boolean persistent;

	/** Parser is re-created per session to respect maxBufferSize from options. */
	private ControlMessageParser parser;


	// ============================================================
	// State Management (Atomic State Machine)
	// ============================================================

	private final AtomicInteger state = new AtomicInteger(STATE_DISCONNECTED);

	private final AtomicReference<Throwable> sessionError = new AtomicReference<>();

	private final AtomicReference<String> sessionId = new AtomicReference<>();

	/** Flag for clean shutdown - volatile for visibility across threads (MCP pattern) */
	private volatile boolean isClosing = false;

	/** Temp file for MCP config — written before session start, deleted on close. */
	private volatile Path mcpConfigFile;

	/** Stderr handler for the current session (may be null if using default logging). */
	private volatile StderrHandler currentStderrHandler;

	/** Tool permission callback for the current session (may be null). */
	private volatile ToolPermissionCallback currentToolPermissionCallback;

	/** 本轮执行要 resume 的会话 ID（一次性进程模型下的多轮串接）。 */
	private volatile String turnResume;

	/** 本轮执行要固定的会话 ID（首轮使用）。 */
	private volatile String turnSessionId;

	/**
	 * 设置本轮执行的会话语境。必须在 startSession() 之前调用。
	 * @param sessionId 首轮固定的会话 ID（传 null 则用 options 中的值）
	 * @param resume 需要续接的会话 ID（传 null 表示首轮）
	 */
	public void setTurnSession(String sessionId, String resume) {
		this.turnSessionId = sessionId;
		this.turnResume = resume;
	}

	// ============================================================
	// Scheduler Separation (from MCP SDK pattern)
	// ============================================================

	private final Scheduler inboundScheduler;

	private final Scheduler outboundScheduler;

	private final Scheduler errorScheduler;

	// ============================================================
	// Reactive Sinks for Message Buffering
	// ============================================================

	private final Sinks.Many<ParsedMessage> inboundSink;

	private final Sinks.Many<String> outboundSink;

	private final Sinks.One<Map<String, Object>> serverInfoSink;

	// ============================================================
	// Resource Tracking (Disposable.Composite pattern)
	// ============================================================

	private final Disposable.Composite subscriptions = Disposables.composite();

	// ============================================================
	// Process Management
	// ============================================================

	private volatile Process process;

	private volatile BufferedWriter stdinWriter;

	private volatile BufferedReader stdoutReader;

	private volatile BufferedReader stderrReader;

	// Synchronization for stdin writes (belt-and-suspenders with outbound scheduler)
	private final Object stdinLock = new Object();

	/**
	 * 实测 soloncode CLI 成功执行也可能以退出码 1 结束（文档语义与实际行为不一致），
	 * 因此退出码 1 需结合是否收到 result 事件判断成败。
	 */
	private volatile boolean resultReceived = false;

	private volatile java.util.concurrent.CountDownLatch inboundDone = new java.util.concurrent.CountDownLatch(1);

	// ============================================================
	// Constructors
	// ============================================================

	public StdioTransport(Path workingDirectory) {
		this(workingDirectory, Duration.ofMinutes(10), null);
	}

	public StdioTransport(Path workingDirectory, Duration defaultTimeout) {
		this(workingDirectory, defaultTimeout, null);
	}

	/**
	 * Creates a StdioTransport for SolonCode CLI communication.
	 * @param workingDirectory the working directory for the CLI
	 * @param defaultTimeout default timeout for operations
	 * @param cliPath optional path to SolonCode CLI executable (auto-discovers if null)
	 * @throws IllegalArgumentException if workingDirectory or defaultTimeout is null
	 */
	public StdioTransport(Path workingDirectory, Duration defaultTimeout, String cliPath) {
		this(workingDirectory, defaultTimeout, cliPath, false);
	}

	/**
	 * @param persistent true 使用 {@code soloncode stream} 并保持 stdin；false 使用单次 run
	 */
	public StdioTransport(Path workingDirectory, Duration defaultTimeout, String cliPath, boolean persistent) {
		// MCP SDK pattern: strict validation for required arguments
		if (workingDirectory == null) {
			throw new IllegalArgumentException("workingDirectory must not be null");
		}
		if (defaultTimeout == null) {
			throw new IllegalArgumentException("defaultTimeout must not be null");
		}
		this.workingDirectory = workingDirectory;
		this.defaultTimeout = defaultTimeout;
		this.persistent = persistent;
		this.soloncodeCommand = cliPath != null ? cliPath : discoverSolonCodePath();
		this.parser = new ControlMessageParser();

		// Initialize schedulers with named threads for debugging
		this.inboundScheduler = Schedulers
			.fromExecutorService(Executors.newSingleThreadExecutor(r -> new Thread(r, "soloncode-inbound")), "inbound");
		this.outboundScheduler = Schedulers
			.fromExecutorService(Executors.newSingleThreadExecutor(r -> new Thread(r, "soloncode-outbound")), "outbound");
		this.errorScheduler = Schedulers
			.fromExecutorService(Executors.newSingleThreadExecutor(r -> new Thread(r, "soloncode-error")), "error");

		// Initialize sinks with backpressure
		// Use replay() to buffer messages for late subscribers in multi-turn
		// conversations
		// This ensures messages aren't lost between turns when there's no active
		// subscriber
		this.inboundSink = Sinks.many().replay().limit(1000);
		this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();
		this.serverInfoSink = Sinks.one();
	}

	private String discoverSolonCodePath() {
		try {
			return SolonCodeCliDiscovery.discoverSolonCodePath();
		}
		catch (Exception e) {
			logger.warn("Could not discover SolonCode CLI path, using 'soloncode'", e);
			return "soloncode";
		}
	}

	/**
	 * 判定环境变量是否为需要透传的凭证类变量。
	 * @param key 环境变量名
	 * @return true 表示应透传给子进程
	 */
	private static boolean isCredentialEnvVar(String key) {
		for (String suffix : CREDENTIAL_ENV_VAR_SUFFIXES) {
			if (key.endsWith(suffix)) {
				return true;
			}
		}
		return false;
	}

	// ============================================================
	// State Machine Methods
	// ============================================================

	/**
	 * Gets the current state of the transport.
	 */
	public int getState() {
		return state.get();
	}

	/**
	 * Gets the state name for logging/debugging.
	 */
	public String getStateName() {
		switch (state.get()) {
			case STATE_DISCONNECTED:
				return "DISCONNECTED";
			case STATE_CONNECTING:
				return "CONNECTING";
			case STATE_CONNECTED:
				return "CONNECTED";
			case STATE_CLOSING:
				return "CLOSING";
			case STATE_CLOSED:
				return "CLOSED";
			default:
				return "UNKNOWN";
		}
	}

	/**
	 * Attempts a state transition. Returns true if successful.
	 */
	private boolean transitionTo(int expectedState, int newState) {
		boolean success = state.compareAndSet(expectedState, newState);
		if (success) {
			logger.debug("State transition: {} -> {}", getStateName(expectedState), getStateName(newState));
		}
		return success;
	}

	private String getStateName(int stateValue) {
		switch (stateValue) {
			case STATE_DISCONNECTED:
				return "DISCONNECTED";
			case STATE_CONNECTING:
				return "CONNECTING";
			case STATE_CONNECTED:
				return "CONNECTED";
			case STATE_CLOSING:
				return "CLOSING";
			case STATE_CLOSED:
				return "CLOSED";
			default:
				return "UNKNOWN";
		}
	}

	// ============================================================
	// Session Lifecycle
	// ============================================================

	/**
	 * Starts a bidirectional session with the SolonCode CLI.
	 * @param prompt the initial prompt
	 * @param options CLI options (will be modified for bidirectional mode)
	 * @param messageHandler handler for regular messages
	 * @param controlRequestHandler handler for control requests, returns response
	 * @throws SolonCodeSDKException if the session fails to start
	 */
	public void startSession(String prompt, CLIOptions options, Consumer<ParsedMessage> messageHandler,
			ControlRequestHandler controlRequestHandler) throws SolonCodeSDKException {
		startSession(prompt, options, messageHandler, controlRequestHandler, null);
	}

	/**
	 * Starts a bidirectional session with the SolonCode CLI.
	 * @param prompt the initial prompt
	 * @param options CLI options (will be modified for bidirectional mode)
	 * @param messageHandler handler for regular messages
	 * @param controlRequestHandler handler for control requests, returns response
	 * @param controlResponseHandler handler for control responses to our outgoing
	 * requests
	 * @throws SolonCodeSDKException if the session fails to start
	 */
	public void startSession(String prompt, CLIOptions options, Consumer<ParsedMessage> messageHandler,
			ControlRequestHandler controlRequestHandler, Consumer<ControlResponse> controlResponseHandler)
			throws SolonCodeSDKException {

		// State transition: DISCONNECTED -> CONNECTING
		if (!transitionTo(STATE_DISCONNECTED, STATE_CONNECTING)) {
			int currentState = state.get();
			if (currentState == STATE_CLOSED) {
				throw new IllegalStateException("Transport has been closed and cannot be reused");
			}
			throw new IllegalStateException("Cannot start session in state: " + getStateName());
		}

		try {
			if (options.getMaxTokens() != null) {
				throw new UnsupportedOperationException(
						"maxTokens is not supported by the current SolonCode CLI protocol");
			}
			resultReceived = false;
			inboundDone = new java.util.concurrent.CountDownLatch(1);
			// Create parser with buffer size from options (buffer overflow protection)
			this.parser = new ControlMessageParser(options.getEffectiveMaxBufferSize());

			// Store stderr handler for this session
			this.currentStderrHandler = options.getStderrHandler();

			// Store tool permission callback for this session
			this.currentToolPermissionCallback = options.getToolPermissionCallback();

			// soloncode run 是「一次性」执行：提示词默认作为 run 后的第一个位置参数传入。
			// 但 Solon argx 会把 argv 里含 '=' 的词解析成 key=value、把 '-' 开头的词解析成选项，
			// 两种情况下提示词都会丢失（CLI 报 "No prompt provided" 并退出码 3），
			// 因此这类提示词必须改走 stdin 管道。
			boolean promptViaStdin = !persistent && needsStdinPrompt(prompt);
			List<String> command = persistent
					? buildPersistentCommand(options)
					: buildStreamingCommand(options, promptViaStdin ? null : prompt);

			// Wrap command with sudo if user is specified (Unix only)
			command = wrapCommandForUser(command, options.getUser());

			// The constructed command carries --system-prompt, --append-system-prompt,
			// --agents, --settings, --json-schema and --mcp-config values. MCP server
			// configuration routinely holds API tokens, so the full command line is a
			// credential and payload disclosure and belongs at DEBUG. INFO gets the
			// executable and the flag names only.
			logger.info("Starting bidirectional session: {} [{} args: {}]", command.get(0), command.size() - 1,
					command.stream().skip(1).filter(a -> a.startsWith("--")).collect(Collectors.joining(" ")));
			logger.debug("Bidirectional session command: {}", command);

			// Build environment variables with MCP-style safe filtering
			Map<String, String> env = buildProcessEnvironment(options);

			// Start process using ProcessBuilder
			ProcessBuilder pb = new ProcessBuilder(command);
			pb.directory(workingDirectory.toFile());
			pb.environment().putAll(env);
			process = pb.start();

			// Setup streams
			stdinWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
			stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
			stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));

			// Start inbound message processing on dedicated scheduler (MCP pattern)
			// Using scheduler.schedule() instead of Mono.subscribeOn() for immediate
			// execution
			inboundScheduler
				.schedule(() -> processInboundMessages(messageHandler, controlRequestHandler, controlResponseHandler));

			// Start stderr reader on dedicated scheduler
			errorScheduler.schedule(this::readStderr);

			// Start outbound message processing
			Disposable outboundDisposable = outboundSink.asFlux()
				.publishOn(outboundScheduler)
				.doOnNext(this::writeToStdin)
				.subscribe();
			subscriptions.add(outboundDisposable);

			// State transition: CONNECTING -> CONNECTED
			if (!transitionTo(STATE_CONNECTING, STATE_CONNECTED)) {
				throw new TransportException("Failed to complete connection - unexpected state change");
			}

			// 提示词已作为位置参数传入时，立即关闭 stdin：
			// soloncode run 的 resolvePrompt() 用 System.in.available() > 0 判断是否读管道，
			// 保持 stdin 打开不会带来任何好处，反而让 CLI 侧的判断依赖写入时序。
			if (persistent) {
				sendPersistentUserMessage(prompt);
			}
			else if (promptViaStdin) {
				sendPromptViaStdin(prompt);
			}
			else {
				closeStdinQuietly();
			}

		}
		catch (Exception e) {
			// Revert state on failure
			state.set(STATE_DISCONNECTED);
			sessionError.set(e);
			if (e instanceof SolonCodeSDKException) {
				throw (SolonCodeSDKException) e;
			}
			throw new TransportException("Failed to start bidirectional session", e);
		}
	}

	/**
	 * 提示词是否必须通过 stdin 管道投递（而不能作为 argv 位置参数）。
	 *
	 * <p>soloncode 的参数解析基于 Solon {@code argx}，位置参数也会进入 argx 成为一个
	 * key。以下形态的提示词无法安全地作为 argv 传递：
	 * <ul>
	 * <li>以 {@code -} 开头 → 被当作选项名，{@code flagAt(1)} 取不到，CLI 退出码 3</li>
	 * <li>含 {@code =} → 被当作 key=value 配置项，同样取不到，CLI 退出码 3</li>
	 * <li>含 {@code .} → 旧版 {@code SolonProps.syncArgsToSys()} 对含点的 key 执行
	 * {@code System.setProperty(key, kv.getFirstValue())}，而位置参数没有 value，
	 * {@code getFirstValue()} 返回 null，触发 {@code Hashtable.put} 的
	 * {@code NullPointerException}，CLI 启动即失败（退出码 1）。Solon 已补 null 防护，
	 * 但自然语言提示词几乎必然包含句点，此处保留分支以兼容旧版 CLI</li>
	 * </ul>
	 * 由于自然语言提示词几乎必然包含句点，该分支在实际使用中命中率很高；stdin 路径
	 * 对长文本和多行提示词也更友好，与 {@code --resume} 可共存。
	 * 其余提示词（含空格、中文、问号）作为单个 argv 参数是安全的。
	 *
	 * @param prompt 提示词
	 * @return true 表示改走 stdin
	 */
	static boolean needsStdinPrompt(String prompt) {
		if (prompt == null || prompt.isEmpty()) {
			return false;
		}
		return prompt.startsWith("-") || prompt.indexOf('=') >= 0 || prompt.indexOf('.') >= 0;
	}

	/**
	 * Builds the command without a positional prompt (prompt supplied via stdin pipe).
	 */
	List<String> buildStreamingCommand(CLIOptions options) {
		return buildStreamingCommand(options, null);
	}

	/** 构建常驻 {@code soloncode stream} 命令；输入输出协议由子命令固定为 JSONL。 */
	List<String> buildPersistentCommand(CLIOptions options) {
		List<String> command = buildStreamingCommand(options, null);
		command.set(1, "stream");
		int outputFlag = command.indexOf("--output-format");
		if (outputFlag >= 0) {
			command.remove(outputFlag);
			command.remove(outputFlag);
		}
		return command;
	}

	/**
	 * Builds the command for a one-shot {@code soloncode run} execution.
	 *
	 * <p>soloncode 的提示词必须是 {@code run} 之后的第一个位置参数：PrintModeOptions.parse()
	 * 只看 flags[1]，而 Solon argx 对选项做贪心 lookahead（如 {@code --verbose <prompt>} 会把
	 * prompt 当成 verbose 的值）。因此 prompt 必须紧跟在 run 后面，不能放到尾部。</p>
	 * @param options CLI options
	 * @param prompt 提示词；null 表示由 stdin 管道提供
	 * @return 完整命令行
	 */
	List<String> buildStreamingCommand(CLIOptions options, String prompt) {
		List<String> command = new ArrayList<>();
		command.add(soloncodeCommand);
		// SolonCode CLI: use the `run` subcommand for headless agent execution
		command.add("run");

		// 提示词作为 run 后的第一个位置参数（必须在所有 --flag 之前）
		if (prompt != null && !prompt.isEmpty()) {
			command.add(prompt);
		}

		// soloncode run does NOT support --input-format; prompt is plain text via stdin.
		command.add("--output-format");
		command.add("stream-json");
		// NOTE: --permission-prompt-tool is NOT added unconditionally
		// This matches Python SDK behavior where it's only added if explicitly configured
		// Adding it unconditionally may affect how --allowedTools restrictions are
		// enforced
		command.add("--verbose");

		// Standard options
		if (options.getModel() != null) {
			command.add("--model");
			command.add(options.getModel());
		}

		// Use --system-prompt to set the system prompt (matching Python SDK behavior)
		// Note: --append-system-prompt adds to the default, --system-prompt replaces it
		// --system-prompt is not supported by soloncode run
		if (options.getSystemPrompt() != null) {
			logger.warn("systemPrompt is not supported by soloncode run; ignoring");
		}

		// Handle --tools option (base set of tools) - added in Python SDK v0.1.10
		// null = don't add flag (use CLI defaults)
		// empty list = --tools "" (disable all built-in tools)
		// non-empty list = --tools "Read,Edit,Bash" (specific tools)
		if (options.getTools() != null) {
			logger.warn("tools is not supported by soloncode run; use allowedTools/disallowedTools instead");
		}

		// Use --allowedTools (camelCase) to match Python SDK
		if (!options.getAllowedTools().isEmpty()) {
			command.add("--allowedTools");
			command.add(String.join(",", options.getAllowedTools()));
		}

		// Use --disallowedTools (camelCase) to match Python SDK
		if (!options.getDisallowedTools().isEmpty()) {
			command.add("--disallowedTools");
			command.add(String.join(",", options.getDisallowedTools()));
		}

		if (options.getPermissionMode() != null) {
			String modeValue = options.getPermissionMode().getValue();
			// soloncode run has no --dangerously-skip-permissions flag;
			// map it to --permission-mode bypassPermissions
			if (options.getPermissionMode() == PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS) {
				modeValue = "bypassPermissions";
			}
			command.add("--permission-mode");
			command.add(modeValue);
		}

		// Session resume options
		// 一次性执行模型下，多轮对话靠 --session-id / --resume 串接，
		// 客户端会在第 2 轮及之后通过 setTurnResume() 注入 resume 会话 ID。
		String effectiveResume = turnResume != null ? turnResume : options.getResume();
		boolean resuming = effectiveResume != null && !effectiveResume.trim().isEmpty();

		if (options.isContinueConversation() && !resuming) {
			command.add("--continue");
		}

		if (resuming) {
			command.add("--resume");
			command.add(effectiveResume);
		}

		// Fixed session ID for this execution
		String effectiveSessionId = turnSessionId != null ? turnSessionId : options.getSessionId();
		if (!resuming && effectiveSessionId != null && !effectiveSessionId.trim().isEmpty()) {
			command.add("--session-id");
			command.add(effectiveSessionId);
		}

		// Bare mode: skip skills/agents mounts, MCP services and memory auto-discovery
		if (options.isBare()) {
			command.add("--bare");
		}

		// Session forking (creates new session ID when resuming)
		if (options.isForkSession()) {
			logger.warn("forkSession is not supported by soloncode run; ignoring");
		}

		// Partial message streaming support
		if (options.isIncludePartialMessages()) {
			logger.warn("includePartialMessages is not supported by soloncode run; ignoring");
		}

		// Add agents JSON for multi-agent coordination (Task tool with subagents)
		if (options.getAgents() != null && !options.getAgents().trim().isEmpty()) {
			logger.warn("agents is not supported by soloncode run; ignoring");
		}

		// Add max thinking tokens for extended thinking support
		if (options.getMaxThinkingTokens() != null) {
			logger.warn("maxThinkingTokens is not supported by soloncode run; ignoring");
		}

		// Add JSON schema for structured output support
		if (options.getJsonSchema() != null && !options.getJsonSchema().isEmpty()) {
			try {
				// Jackson 默认会输出 Map 里的 null 值，这里用 Write_Nulls 对齐，避免 schema 字段静默消失
				String schemaJson = SdkJson.toJsonWithNulls(options.getJsonSchema());
				command.add("--json-schema");
				command.add(schemaJson);
			}
			catch (RuntimeException e) {
				logger.warn("Failed to serialize JSON schema, skipping --json-schema flag", e);
			}
		}

		// Add MCP server configuration via temp file (avoids shell escaping issues)
		// --mcp-config is not supported by soloncode run
		if (options.getMcpServers() != null && !options.getMcpServers().isEmpty()) {
			logger.warn("mcpServers is not supported by soloncode run (register MCP on CLI side); ignoring");
		}

		// Add max turns for budget control
		if (options.getMaxTurns() != null) {
			command.add("--max-turns");
			command.add(String.valueOf(options.getMaxTurns()));
		}

		// Add max budget USD for cost control
		if (options.getMaxBudgetUsd() != null) {
			command.add("--max-budget-usd");
			command.add(String.valueOf(options.getMaxBudgetUsd()));
		}

		// Add fallback model
		if (options.getFallbackModel() != null && !options.getFallbackModel().isEmpty()) {
			command.add("--fallback-model");
			command.add(options.getFallbackModel());
		}

		// Add append system prompt (uses preset mode with append)
		if (options.getAppendSystemPrompt() != null && !options.getAppendSystemPrompt().isEmpty()) {
			logger.warn("appendSystemPrompt is not supported by soloncode run; ignoring");
		}

		// ============================================================
		// Advanced options for full Python SDK parity
		// ============================================================

		// Add directories (repeated flag)
		if (options.getAddDirs() != null && !options.getAddDirs().isEmpty()) {
			for (Path dir : options.getAddDirs()) {
				command.add("--add-dir");
				command.add(dir.toString());
			}
		}

		// Custom settings file
		if (options.getSettings() != null && !options.getSettings().trim().isEmpty()) {
			logger.warn("settings is not supported by soloncode run; ignoring");
		}

		// Add setting sources for skill/config loading (matching CLITransport)
		if (options.getSettingSources() != null && !options.getSettingSources().isEmpty()) {
			logger.warn("settingSources is not supported by soloncode run; ignoring");
		}

		// Permission prompt tool - matches Python SDK auto-detection pattern
		// Python SDK (client.py lines 68-69): Automatically sets
		// permission_prompt_tool_name="stdio"
		// when a can_use_tool callback is configured
		// --permission-prompt-tool / stdin control protocol is not supported by soloncode run
		if (options.getPermissionPromptToolName() != null
				&& !options.getPermissionPromptToolName().trim().isEmpty()) {
			logger.warn("permissionPromptToolName is not supported by soloncode run; ignoring");
		}
		if (options.getToolPermissionCallback() != null) {
			logger.warn("toolPermissionCallback is not supported by soloncode run; ignoring");
		}

		// Plugins (repeated flag)
		if (options.getPlugins() != null && !options.getPlugins().isEmpty()) {
			logger.warn("plugins is not supported by soloncode run; ignoring");
		}

		// Extra args (arbitrary flags - MUST BE LAST before return)
		if (options.getExtraArgs() != null && !options.getExtraArgs().isEmpty()) {
			for (Map.Entry<String, String> entry : options.getExtraArgs().entrySet()) {
				String flag = entry.getKey();
				String value = entry.getValue();
				if (value == null) {
					// Boolean flag (no value)
					command.add("--" + flag);
				}
				else {
					// Flag with value
					command.add("--" + flag);
					command.add(value);
				}
			}
		}

		return command;
	}

	/**
	 * Safe inherited environment variables (MCP SDK pattern). These are the only system
	 * env vars that are inherited by default for security.
	 *
	 * <p>soloncode 是 JVM 程序：启动脚本用 PATH 里的 {@code java}，而 CLI 内部的
	 * {@code JdkHomeUtil} 会读 {@code JAVA_HOME}，因此 {@code JAVA_HOME} 必须在白名单内，
	 * 否则子进程可能找不到 JDK。</p>
	 */
	private static final List<String> SAFE_INHERITED_ENV_VARS_UNIX = SdkCollections.list("HOME", "LOGNAME", "PATH",
			"SHELL", "TERM", "USER", "LANG", "LC_ALL", "LC_CTYPE", "JAVA_HOME");

	private static final List<String> SAFE_INHERITED_ENV_VARS_WINDOWS = SdkCollections.list("APPDATA", "HOMEDRIVE",
			"HOMEPATH", "LOCALAPPDATA", "PATH", "PROCESSOR_ARCHITECTURE", "SYSTEMDRIVE", "SYSTEMROOT", "TEMP",
			"USERNAME", "USERPROFILE", "JAVA_HOME");

	/**
	 * 需要透传给 soloncode 子进程的凭证类变量名后缀。
	 *
	 * <p>claude SDK 只透传 {@code ANTHROPIC_API_KEY} 一个变量；soloncode 的模型凭证由
	 * 工作区配置声明，可以引用任意供应商的环境变量（{@code ${OPENAI_API_KEY}} 等）。
	 * 白名单机制会把这些变量全部过滤掉，导致配置里的占位符解析不到值，所以这里按后缀
	 * 放行凭证类变量，而不是硬编码某一家供应商。</p>
	 */
	private static final List<String> CREDENTIAL_ENV_VAR_SUFFIXES = SdkCollections.list("_API_KEY", "_API_TOKEN",
			"_API_BASE", "_API_URL", "_ACCESS_KEY", "_SECRET_KEY");

	/**
	 * Builds the process environment with MCP-style safe filtering. Follows the MCP SDK
	 * pattern: whitelist safe vars, filter shell functions, add SDK identity, merge user
	 * vars.
	 * @param options CLI options that may contain user-provided env vars
	 * @return environment map for process execution
	 */
	private Map<String, String> buildProcessEnvironment(CLIOptions options) {
		Map<String, String> env = new HashMap<>();

		// 1. Start with safe inherited vars (whitelist approach, MCP pattern)
		List<String> safeVars = System.getProperty("os.name").toLowerCase().contains("win")
				? SAFE_INHERITED_ENV_VARS_WINDOWS : SAFE_INHERITED_ENV_VARS_UNIX;

		for (String key : safeVars) {
			String value = System.getenv(key);
			// Exclude shell functions (start with '()') for security
			if (value != null && !value.startsWith("()")) {
				env.put(key, value);
			}
		}

		// 2. Pass through provider-agnostic credential vars and SOLONCODE_* settings
		for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();
			if (value == null || value.startsWith("()")) {
				continue;
			}
			if (key.startsWith("SOLONCODE_") || isCredentialEnvVar(key)) {
				env.put(key, value);
			}
		}

		// 3. SDK identification
		env.put("SOLONCODE_ENTRYPOINT", "sdk-java");
		env.put("SOLONCODE_SDK_JAVA_VERSION", getClass().getPackage().getImplementationVersion() != null
				? getClass().getPackage().getImplementationVersion() : "dev");

		// 4. User-provided env vars override (last wins)
		if (options.getEnv() != null && !options.getEnv().isEmpty()) {
			env.putAll(options.getEnv());
		}

		return env;
	}

	/**
	 * Wraps a command with sudo for user switching (Unix only). On Windows, user
	 * switching is not supported and a warning is logged.
	 * @param command the original command
	 * @param user the Unix user to run as (null or blank to skip)
	 * @return the wrapped command, or original if no wrapping needed
	 */
	private List<String> wrapCommandForUser(List<String> command, String user) {
		if (user == null || user.trim().isEmpty()) {
			return command;
		}

		// Check if running on Unix
		if (!System.getProperty("os.name").toLowerCase().contains("win")) {
			List<String> wrapped = new ArrayList<>();
			wrapped.add("sudo");
			wrapped.add("-u");
			wrapped.add(user);
			wrapped.addAll(command);
			logger.info("Wrapping command for user '{}' with sudo", user);
			return wrapped;
		}

		logger.warn("User switching not supported on Windows, ignoring user: {}", user);
		return command;
	}

	// ============================================================
	// Message Processing
	// ============================================================

	/**
	 * Processes inbound messages from stdout, dispatching to appropriate handlers.
	 */
	private void processInboundMessages(Consumer<ParsedMessage> messageHandler,
			ControlRequestHandler controlRequestHandler, Consumer<ControlResponse> controlResponseHandler) {
		try {
			String line;
			// Use isClosing flag for clean shutdown (MCP pattern)
			logger.debug("Starting message processing loop, isClosing={}", isClosing);
			while (!isClosing && (line = stdoutReader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty()) {
					continue;
				}

				try {
					ParsedMessage parsed = parser.parse(line);

					// Skip unrecognized message types (forward-compatibility)
					if (parsed == null) {
						continue;
					}

				// Emit to sink for reactive consumers
				if (parsed.isResultMessage()) {
					resultReceived = true;
					String sid = ((org.noear.soloncode.sdk.types.ResultMessage) parsed.asMessage()).sessionId();
					if (sid != null && !sid.isEmpty()) {
						sessionId.set(sid);
					}
				}
				else if (parsed.isRegularMessage()
						&& parsed.asMessage() instanceof org.noear.soloncode.sdk.types.SystemMessage) {
					org.noear.soloncode.sdk.types.SystemMessage system =
							(org.noear.soloncode.sdk.types.SystemMessage) parsed.asMessage();
					if ("error".equalsIgnoreCase(system.subtype())) {
						resultReceived = true;
					}
					Object sid = system.data().get("session_id");
					if (sid != null && !String.valueOf(sid).isEmpty()) {
						sessionId.set(String.valueOf(sid));
					}
				}
				Sinks.EmitResult emitResult = inboundSink.tryEmitNext(parsed);
					if (!emitResult.isSuccess()) {
						if (!isClosing) {
							logger.error("Failed to emit inbound message: result={}", emitResult);
						}
						break;
					}

					if (parsed.isControlRequest()) {
						// Handle control request from CLI (hooks, can_use_tool, etc.)
						ControlRequest request = parsed.asControlRequest();
						logger.debug("Received control request: type={}, requestId={}",
								request.request() != null ? request.request().subtype() : "null", request.requestId());

						// Get response from handler
						ControlResponse response = controlRequestHandler.handle(request);

						// Send response back to CLI via outbound sink
						sendResponse(response);
					}
					else if (parsed.isControlResponse()) {
						// Handle control response to our outgoing request
						ControlResponse response = parsed.asControlResponse();
						String requestId = response.response() != null ? response.response().requestId() : null;
						String subtype = response.response() != null ? response.response().subtype() : null;
						logger.debug("Received control response: requestId={}, subtype={}", requestId, subtype);

						// Route to response handler if provided
						if (controlResponseHandler != null) {
							controlResponseHandler.accept(response);
						}
					}
					else {
						// Regular message - pass to handler
						messageHandler.accept(parsed);
					}
				}
				catch (Exception e) {
					if (!isClosing) {
						sessionError.compareAndSet(null, e);
						logger.error("Failed to process protocol message: {}",
								line.substring(0, Math.min(200, line.length())), e);
					}
					break;
				}
			}

			// Log why loop ended
			if (isClosing) {
				logger.debug("Message processing loop ended: isClosing=true");
			}
			else if (process != null && !process.isAlive()) {
				logger.debug("Message processing loop ended: process exited with code {}", process.exitValue());
			}
			else {
				logger.debug("Message processing loop ended: stdout closed");
			}
		}
		catch (IOException e) {
			if (!isClosing) {
				sessionError.set(e);
				logger.error("Error reading from stdout", e);
			}
		}
		finally {
			boolean closingByCaller = isClosing;
			if (!closingByCaller && !resultReceived && sessionError.get() == null) {
				sessionError.compareAndSet(null,
						new TransportException("stdio stream ended before result event"));
			}
			inboundDone.countDown();
			logger.debug("processInboundMessages finally block, setting isClosing=true");
			isClosing = true;
			inboundSink.tryEmitComplete();
			// Signal the message handler that the session has ended so iterators
			// stop polling immediately, rather than waiting for close() to be called.
			try {
				messageHandler.accept(ParsedMessage.EndOfStream.INSTANCE);
			}
			catch (Exception e) {
				logger.debug("Error signaling session end to message handler", e);
			}
		}
	}

	/**
	 * Writes a message directly to stdin. Called on the outbound scheduler.
	 */
	private void writeToStdin(String message) {
		synchronized (stdinLock) {
			try {
				if (stdinWriter == null || isClosing) {
					logger.debug("Dropping message - transport closing or closed");
					return;
				}

				stdinWriter.write(message);
				stdinWriter.newLine();
				stdinWriter.flush();
			}
			catch (IOException e) {
				logger.error("Error writing to stdin", e);
				sessionError.set(e);
			}
		}
	}

	/**
	 * Reads and logs stderr output.
	 */
	private void readStderr() {
		try {
			String line;
			while (!isClosing && (line = stderrReader.readLine()) != null) {
				// Use custom handler if provided, otherwise log at warn level
				if (currentStderrHandler != null) {
					currentStderrHandler.handle(line);
				}
				else {
					logger.warn("CLI stderr: {}", line);
				}
			}
		}
		catch (IOException e) {
			if (!isClosing) {
				logger.debug("Error reading stderr", e);
			}
		}
	}

	// ============================================================
	// Message Sending
	// ============================================================

	/**
	 * Sends a user message to the CLI via stdin.
	 * @param content the message content
	 * @param sid the session ID (use "default" for initial session)
	 * @throws SolonCodeSDKException if sending fails
	 */
	public void sendUserMessage(String content, String sid) throws SolonCodeSDKException {
		assertConnected();
		if (persistent) {
			resultReceived = false;
			sendPersistentUserMessage(content);
		}
		else {
			sendPromptViaStdin(content);
		}
	}

	private void sendPersistentUserMessage(String content) {
		if (content == null || content.isEmpty()) {
			throw new TransportException("prompt must not be null or empty");
		}
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("role", "user");
		message.put("content", content);
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("type", "user");
		envelope.put("message", message);
		writeLineToStdin(SdkJson.toJson(envelope));
	}

	private void writeLineToStdin(String line) {
		synchronized (stdinLock) {
			try {
				if (stdinWriter == null || isClosing) {
					throw new SessionClosedException("stdin is closed");
				}
				stdinWriter.write(line);
				stdinWriter.newLine();
				stdinWriter.flush();
			}
			catch (IOException e) {
				sessionError.set(e);
				throw new TransportException("Failed to write to persistent stdin", e);
			}
		}
	}

	/**
	 * 通过 stdin 管道投递提示词并立即关闭 stdin。
	 *
	 * <p>soloncode run 的 stdin 只接受纯文本 prompt（无 JSON 信封），读到 EOF 才开始执行；
	 * 且 CLI 侧用 {@code System.in.available() > 0} 判断是否走 stdin 分支，因此写入必须尽早、
	 * 且必须显式关闭。常规路径优先用位置参数传 prompt，这里只服务于 prompt 以 '-' 开头的回退场景。</p>
	 * @param content 提示词纯文本
	 * @throws SolonCodeSDKException 写入失败
	 */
	void sendPromptViaStdin(String content) throws SolonCodeSDKException {
		synchronized (stdinLock) {
			try {
				if (stdinWriter == null) {
					throw new TransportException("stdin writer is not available: soloncode run is one-shot, "
							+ "每轮提问需要新的 CLI 进程（客户端会自动 --resume 续接会话）");
				}
				stdinWriter.write(content);
				stdinWriter.flush();
				stdinWriter.close();
				stdinWriter = null;
				logger.info("Prompt sent via stdin ({} chars) and stdin closed", content.length());
			}
			catch (IOException e) {
				throw new TransportException("Failed to send prompt via stdin", e);
			}
		}
	}

	/**
	 * 关闭 stdin，忽略异常。提示词走位置参数时 CLI 不会读 stdin，尽早关闭避免误判。
	 */
	void closeStdinQuietly() {
		synchronized (stdinLock) {
			if (stdinWriter == null) {
				return;
			}
			try {
				stdinWriter.close();
			}
			catch (IOException e) {
				logger.debug("Failed to close stdin", e);
			}
			finally {
				stdinWriter = null;
			}
		}
	}

	/**
	 * Sends a control response back to the CLI via stdin.
	 * @param response the response to send
	 * @throws SolonCodeSDKException if sending fails
	 */
	public void sendResponse(ControlResponse response) throws SolonCodeSDKException {
		assertConnected();

		final String json;
		try {
			// ControlResponse 原带 @JsonInclude(NON_NULL)，snack4 默认即不写 null，语义一致
			json = SdkJson.toJson(response);
		}
		catch (RuntimeException e) {
			throw new TransportException("Failed to serialize control response", e);
		}

		logger.debug("Sending control response: {}", json);

		Sinks.EmitResult result = outboundSink.tryEmitNext(json);
		if (result.isFailure()) {
			throw new TransportException("Failed to queue control response: " + result);
		}
	}

	/**
	 * Sends a raw message to the CLI via stdin.
	 * @param message the message JSON to send
	 * @throws SolonCodeSDKException if sending fails
	 */
	public void sendMessage(String message) throws SolonCodeSDKException {
		assertConnected();

		logger.debug("Sending message: {}", message);
		Sinks.EmitResult result = outboundSink.tryEmitNext(message);
		if (result.isFailure()) {
			throw new TransportException("Failed to queue message: " + result);
		}
	}

	private void assertConnected() {
		int currentState = state.get();
		if (currentState != STATE_CONNECTED) {
			if (currentState == STATE_CLOSED || currentState == STATE_CLOSING) {
				throw new SessionClosedException("Transport is closed");
			}
			throw new IllegalStateException("Transport not connected. State: " + getStateName());
		}
	}

	// ============================================================
	// Reactive API
	// ============================================================

	/**
	 * Returns a Flux of all inbound messages. This is the reactive API for message
	 * consumption.
	 */
	public Flux<ParsedMessage> receiveMessages() {
		return inboundSink.asFlux();
	}

	/**
	 * Returns the inbound message Flux. Alias for {@link #receiveMessages()} for
	 * consistency with internal naming.
	 * @return Flux of parsed messages from the CLI
	 */
	public Flux<ParsedMessage> getInboundFlux() {
		return inboundSink.asFlux();
	}

	/**
	 * Returns a Mono that completes when the server info is received.
	 */
	public Mono<Map<String, Object>> getServerInfo() {
		return serverInfoSink.asMono();
	}

	// ============================================================
	// Iterator API (Critical - not in MCP/ACP)
	// ============================================================

	/**
	 * Returns an iterator over inbound messages. This enables non-reactive consumers to
	 * process messages using standard Iterator/Iterable patterns.
	 *
	 * <p>
	 * Usage:
	 * </p>
	 *
	 * <pre>{@code
	 * try (StdioTransport transport = new StdioTransport(...)) {
	 *     transport.startSession(...);
	 *     for (ParsedMessage message : transport.messageIterable()) {
	 *         handleMessage(message);
	 *     }
	 * }
	 * }</pre>
	 */
	public Iterator<ParsedMessage> messageIterator() {
		return inboundSink.asFlux().toIterable().iterator();
	}

	/**
	 * Returns an iterable over inbound messages for use with for-each loops.
	 */
	public Iterable<ParsedMessage> messageIterable() {
		return inboundSink.asFlux().toIterable();
	}

	// ============================================================
	// Status and Lifecycle
	// ============================================================

	/**
	 * Waits for the session to complete.
	 * @param timeout maximum time to wait
	 * @return true if completed within timeout, false otherwise
	 * @throws SolonCodeSDKException if the session failed with an error
	 */
	public boolean waitForCompletion(Duration timeout) throws SolonCodeSDKException {
		if (process == null) {
			return true;
		}
		if (timeout == null || timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("timeout must be positive");
		}

		try {
			long deadlineNanos = System.nanoTime() + timeout.toNanos();
			boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);

			if (completed) {
				long remainingNanos = deadlineNanos - System.nanoTime();
				if (remainingNanos > 0L) {
					inboundDone.await(remainingNanos, TimeUnit.NANOSECONDS);
				}
				int exitCode = process.exitValue();
				// SolonCode CLI exit codes: 2 max turns exceeded, 3 no prompt, 4 budget exceeded。
				// 注意：实测 CLI 在成功完成时也可能返回退出码 1，
				// 因此退出码 1 时若已收到 result 事件则视为成功。
				boolean success = exitCode == 0 || (exitCode == 1 && resultReceived);
				if (!success) {
					String detail;
					switch (exitCode) {
						case 2:
							detail = "CLI process failed: maximum number of turns exceeded";
							break;
						case 3:
							detail = "CLI process failed: no prompt provided";
							break;
						case 4:
							detail = "CLI process failed: maximum budget exceeded";
							break;
						default:
							detail = "CLI process failed";
							break;
					}
					throw TransportException.withExitCode(detail, exitCode);
				}
			}

			Throwable err = sessionError.get();
			if (err != null) {
				throw new TransportException("Session error", err);
			}

			return completed;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new TransportException("Wait interrupted", e);
		}
	}

	/**
	 * Checks if the session is currently running.
	 */
	public boolean isRunning() {
		return state.get() == STATE_CONNECTED && process != null && process.isAlive();
	}

	/**
	 * Gets any error that occurred during the session.
	 */
	public Throwable getSessionError() {
		return sessionError.get();
	}

	/**
	 * Gets the session ID if assigned.
	 */
	public String getSessionId() {
		return sessionId.get();
	}

	/**
	 * Interrupts the current session.
	 */
	public void interrupt() {
		if (persistent && state.get() == STATE_CONNECTED) {
			Map<String, Object> request = new LinkedHashMap<>();
			request.put("subtype", "interrupt");
			Map<String, Object> envelope = new LinkedHashMap<>();
			envelope.put("type", "control_request");
			envelope.put("request_id", java.util.UUID.randomUUID().toString());
			envelope.put("request", request);
			writeLineToStdin(SdkJson.toJson(envelope));
			return;
		}
		if (state.get() == STATE_CONNECTED) {
			transitionTo(STATE_CONNECTED, STATE_CLOSING);
		}
		if (process != null) {
			process.destroy();
		}
	}

	// ============================================================
	// Graceful Shutdown (from MCP SDK pattern)
	// ============================================================

	/**
	 * Initiates graceful shutdown. Returns a Mono that completes when shutdown is done.
	 */
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			// Set isClosing first for immediate visibility to read loops (MCP pattern)
			isClosing = true;
			logger.debug("closeGracefully called, setting isClosing=true");

			// State transition to CLOSING
			int currentState = state.get();
			if (currentState == STATE_CLOSED || currentState == STATE_CLOSING) {
				return;
			}
			state.set(STATE_CLOSING);
			logger.debug("Initiating graceful shutdown");
		}).then(Mono.defer(() -> {
			// Complete all sinks
			inboundSink.tryEmitComplete();
			outboundSink.tryEmitComplete();

			// Allow time for pending messages
			return Mono.delay(Duration.ofMillis(100));
		})).then(Mono.defer(() -> {
			// Dispose all subscriptions
			subscriptions.dispose();

			return Mono.empty();
		})).then(Mono.defer(() -> {
			// Close transport resources
			closeStreams();

			// Terminate process tree gracefully
			if (process != null) {
				destroyProcessTree(process);
				return Mono.empty();
			}
			return Mono.empty();
		})).then(Mono.<Void>fromRunnable(() -> {
			// Dispose schedulers
			inboundScheduler.dispose();
			outboundScheduler.dispose();
			errorScheduler.dispose();

			state.set(STATE_CLOSED);
			logger.debug("StdioTransport closed gracefully");
		})).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public void close() {
		// Synchronous close for AutoCloseable compatibility
		int currentState = state.get();
		if (currentState == STATE_CLOSED) {
			return;
		}

		// Set isClosing first for immediate visibility to read loops (MCP pattern)
		isClosing = true;
		logger.debug("close() called, setting isClosing=true, currentState={}", getStateName(currentState));
		state.set(STATE_CLOSING);

		// Complete sinks
		inboundSink.tryEmitComplete();
		outboundSink.tryEmitComplete();

		// Dispose subscriptions
		subscriptions.dispose();

		// Close streams
		closeStreams();

		// Terminate process tree — child processes may hold pipes open
		if (process != null) {
			destroyProcessTree(process);
		}

		// Clean up MCP config temp file
		if (mcpConfigFile != null) {
			try {
				Files.deleteIfExists(mcpConfigFile);
				logger.debug("Deleted MCP config temp file: {}", mcpConfigFile);
			}
			catch (IOException e) {
				logger.debug("Failed to delete MCP config temp file: {}", mcpConfigFile, e);
			}
			mcpConfigFile = null;
		}

		// Shutdown schedulers
		inboundScheduler.dispose();
		outboundScheduler.dispose();
		errorScheduler.dispose();

		state.set(STATE_CLOSED);
		logger.debug("StdioTransport closed");
	}

	/**
	 * Destroys the CLI process and all its descendant processes. Child processes (e.g.,
	 * Node.js workers spawned by the CLI) may inherit stdout file descriptors, keeping
	 * pipes open even after the main process exits. This ensures the entire process tree
	 * is cleaned up.
	 */
	private void destroyProcessTree(Process proc) {
		// Kill descendants first so they don't hold pipes/resources open
		// Java 8 has no ProcessHandle; use best-effort "pkill -P <pid>" on Unix-like systems
		try {
			Long pid = getProcessId(proc);
			if (pid != null) {
				Runtime.getRuntime()
						.exec(new String[] { "pkill", "-TERM", "-P", String.valueOf(pid) })
						.waitFor();
			}
		}
		catch (Exception e) {
			logger.debug("Error destroying descendant processes", e);
		}

		// Kill the main process
		proc.destroy();
		try {
			if (!proc.waitFor(5, TimeUnit.SECONDS)) {
				logger.debug("Process did not exit within 5s, destroying forcibly");
				proc.destroyForcibly();
				proc.waitFor(2, TimeUnit.SECONDS);
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			proc.destroyForcibly();
		}
	}

	/**
	 * Best-effort process id retrieval for Java 8 (no Process.pid() until JDK 9).
	 */
	private Long getProcessId(Process proc) {
		try {
			if (proc.getClass().getName().equals("java.lang.UNIXProcess")) {
				java.lang.reflect.Field f = proc.getClass().getDeclaredField("pid");
				f.setAccessible(true);
				return (Integer) f.get(proc) != null ? ((Integer) f.get(proc)).longValue() : null;
			}
		}
		catch (Exception e) {
			logger.debug("Unable to determine process id", e);
		}
		return null;
	}

	private void closeStreams() {
		closeQuietly(stdinWriter);
		closeQuietly(stdoutReader);
		closeQuietly(stderrReader);
	}

	private void closeQuietly(Closeable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			}
			catch (IOException e) {
				logger.debug("Error closing stream", e);
			}
		}
	}

	// ============================================================
	// Tool Permission Support
	// ============================================================

	/**
	 * Returns the tool permission callback for this session, if configured.
	 * @return the callback, or null if not configured
	 */
	public ToolPermissionCallback getToolPermissionCallback() {
		return currentToolPermissionCallback;
	}

	/**
	 * Handles a can_use_tool request using the configured callback. This is a convenience
	 * method for ControlRequestHandler implementations.
	 * @param requestId the control request ID
	 * @param request the can_use_tool request payload
	 * @return the control response to send back to CLI
	 */
	public ControlResponse handleCanUseTool(String requestId, ControlRequest.CanUseToolRequest request) {
		if (currentToolPermissionCallback == null) {
			// No callback configured - allow by default
			return ControlResponse.success(requestId, SdkCollections.map("decision", "allow"));
		}

		// Create context from request
		ToolPermissionCallback.ToolPermissionContext context = ToolPermissionCallback.ToolPermissionContext
			.of(request.permissionSuggestions(), request.blockedPath());

		try {
			// Invoke callback (synchronously for now, could be enhanced to async)
			ToolPermissionCallback.ToolPermissionResult result = currentToolPermissionCallback
				.canUseTool(request.toolName(), request.input(), context)
				.get(); // Block for result

			// Convert result to response format
			if (result instanceof ToolPermissionCallback.ToolPermissionResult.Allow) {
					ToolPermissionCallback.ToolPermissionResult.Allow allow = (ToolPermissionCallback.ToolPermissionResult.Allow) result;
					if (allow.updatedInput() != null) {
						return ControlResponse.success(requestId,
								SdkCollections.map("decision", "allow", "updated_input", allow.updatedInput()));
					}
					return ControlResponse.success(requestId, SdkCollections.map("decision", "allow"));
				}
				else if (result instanceof ToolPermissionCallback.ToolPermissionResult.Deny) {
					ToolPermissionCallback.ToolPermissionResult.Deny deny = (ToolPermissionCallback.ToolPermissionResult.Deny) result;
				Map<String, Object> response = new java.util.HashMap<>();
				response.put("decision", "deny");
				response.put("reason", deny.reason());
				if (deny.interrupt()) {
					response.put("interrupt", true);
				}
				return ControlResponse.success(requestId, response);
			}

			// Should never reach here due to sealed interface
			return ControlResponse.error(requestId, "Unknown permission result type");

		}
		catch (Exception e) {
			logger.error("Error in tool permission callback", e);
			return ControlResponse.error(requestId, "Permission callback error: " + e.getMessage());
		}
	}

}

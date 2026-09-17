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

package org.noear.soloncode.sdk;

import org.noear.soloncode.sdk.util.SdkJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.noear.soloncode.sdk.exceptions.TransportException;
import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.hooks.HookCallback;
import org.noear.soloncode.sdk.hooks.HookRegistry;
import org.noear.soloncode.sdk.mcp.McpMessageHandler;
import org.noear.soloncode.sdk.mcp.McpServerConfig;
import org.noear.soloncode.sdk.parsing.ParsedMessage;

import org.noear.soloncode.sdk.streaming.MessageReceiver;
import org.noear.soloncode.sdk.streaming.MessageStreamIterator;
import org.noear.soloncode.sdk.streaming.ResponseBoundedReceiver;
import org.noear.soloncode.sdk.transport.Transport;
import org.noear.soloncode.sdk.transport.TransportSpec;
import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.util.SdkCollections;
import org.noear.soloncode.sdk.types.AssistantMessage;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.SystemMessage;
import org.noear.soloncode.sdk.types.control.ControlRequest;
import org.noear.soloncode.sdk.types.control.ControlResponse;
import org.noear.soloncode.sdk.types.control.HookEvent;
import org.noear.soloncode.sdk.types.control.HookInput;
import org.noear.soloncode.sdk.types.control.HookOutput;
import org.noear.soloncode.sdk.permission.PermissionResult;
import org.noear.soloncode.sdk.permission.ToolPermissionCallback;
import org.noear.soloncode.sdk.permission.ToolPermissionContext;

import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Internal session implementation used by the unified request-oriented client.
 *
 * <p>This implementation maintains a persistent connection to the SolonCode CLI, allowing
 * multi-turn conversations where context is preserved across queries.</p>
 *
 * <p>
 * Thread-safety: This class is thread-safe. Multiple threads can call query() and consume
 * messages concurrently, though typically one thread sends queries and another consumes
 * responses.
 * </p>
 *
 * @see SolonCodeClient
 * @see Transport
 */
final class DefaultSolonCodeSession implements SolonCodeSession {

	private static final Logger logger = LoggerFactory.getLogger(DefaultSolonCodeSession.class);

	private static final String DEFAULT_SESSION_ID = "default";

	private final Path workingDirectory;

	private final CLIOptions options;

	private final Duration timeout;

	private final TransportSpec transportSpec;

	private final HookRegistry hookRegistry;


	// MCP message handler for in-process SDK servers
	private final McpMessageHandler mcpMessageHandler;

	// Session state
	private final AtomicBoolean connected = new AtomicBoolean(false);

	private final AtomicBoolean closed = new AtomicBoolean(false);

	private final AtomicReference<Map<String, Object>> serverInfo = new AtomicReference<>(Collections.emptyMap());

	private final AtomicReference<String> currentSessionId = new AtomicReference<>(DEFAULT_SESSION_ID);

	/** SDK 侧固定的会话 ID：首轮 --session-id，后续轮 --resume。 */
	private final String sdkSessionId;

	/** 是否已经跑过至少一轮（用于判定首轮/续接）。 */
	private final AtomicBoolean turnStarted = new AtomicBoolean(false);

	/** Whether the current turn has delivered its required terminal result message. */
	private final AtomicBoolean turnResultReceived = new AtomicBoolean(false);

	private final AtomicBoolean activeTurn = new AtomicBoolean(false);

	private final AtomicBoolean turnTimeoutTriggered = new AtomicBoolean(false);

	// Runtime state tracking
	private final AtomicReference<String> currentModel = new AtomicReference<>();

	private final AtomicReference<String> currentPermissionMode = new AtomicReference<>();

	// Tool permission callback
	private volatile ToolPermissionCallback toolPermissionCallback;

	// Transport and streaming
	private volatile Transport transport;

	private volatile MessageStreamIterator messageIterator;


	// Control request handling (MCP SDK pattern using MonoSink for correlation)
	private final AtomicInteger requestCounter = new AtomicInteger(0);

	private final String sessionPrefix = UUID.randomUUID().toString().substring(0, 8);

	private final ConcurrentHashMap<String, MonoSink<Map<String, Object>>> pendingResponses = new ConcurrentHashMap<>();

	/**
	 * Creates a session with the specified configuration.
	 */
	DefaultSolonCodeSession(Path workingDirectory, CLIOptions options, Duration timeout,
			TransportSpec transportSpec, HookRegistry hookRegistry) {
		this.workingDirectory = workingDirectory;
		this.options = options != null ? options : CLIOptions.builder().build();
		this.timeout = timeout != null ? timeout : Duration.ofMinutes(10);
		this.transportSpec = transportSpec != null ? transportSpec : TransportSpec.stdio();
		this.hookRegistry = hookRegistry != null ? hookRegistry : new HookRegistry();
		this.mcpMessageHandler = new McpMessageHandler();

		// 会话 ID：优先用调用方指定的 --session-id，否则自生成（用于多轮 --resume 串接）
		String explicitSessionId = this.options.getSessionId();
		String resumeSessionId = this.options.getResume();
		this.sdkSessionId = (explicitSessionId != null && !explicitSessionId.trim().isEmpty()) ? explicitSessionId
				: (resumeSessionId != null && !resumeSessionId.trim().isEmpty() ? resumeSessionId
						: "sdk-" + UUID.randomUUID().toString().substring(0, 8));
		this.currentSessionId.set(this.sdkSessionId);

		// Initialize runtime state from options
		if (this.options.model() != null) {
			this.currentModel.set(this.options.model());
		}
		if (this.options.permissionMode() != null) {
			this.currentPermissionMode.set(this.options.permissionMode().getValue());
		}

		// Register SDK MCP servers for mcp_message handling
		registerMcpServers();
	}

	private void registerMcpServers() {
		Map<String, McpServerConfig> servers = this.options.mcpServers();
		if (servers == null || servers.isEmpty()) {
			return;
		}

		for (Map.Entry<String, McpServerConfig> entry : servers.entrySet()) {
			if (entry.getValue() instanceof McpServerConfig.McpSdkServerConfig) {
			McpServerConfig.McpSdkServerConfig sdkConfig = (McpServerConfig.McpSdkServerConfig) entry.getValue();
				if (sdkConfig.instance() != null) {
					mcpMessageHandler.registerServer(entry.getKey(), sdkConfig.instance());
					logger.info("Registered SDK MCP server: {}", entry.getKey());
				}
				else {
					logger.warn("SDK MCP server {} has null instance", entry.getKey());
				}
			}
		}
	}

	@Override
	public void connect(String initialPrompt) throws SolonCodeSDKException {
		if (closed.get()) {
			throw new TransportException("Client has been closed");
		}
		if (connected.get()) {
			throw new TransportException("Client is already connected");
		}
		if (hookRegistry.hasHooks()) {
			throw new UnsupportedOperationException(
					"SolonCode CLI stream does not currently support hook initialize/callback control frames");
		}

		try {
			// soloncode run 是一次性执行：提示词作为命令行位置参数，进程跑完即退出。
			// 因此无提示词的 connect() 不能先拉起进程（否则 CLI 报退出码 3），
			// 而是延迟到首次 query() 时才启动。
			prepareReceivers();
			connected.set(true);

			if (initialPrompt != null) {
				startTurn(initialPrompt);
				// Length only. Prompts routinely carry credentials, customer data and
				// other material that must not reach an application log at INFO.
				logger.info("Client connected with prompt ({} chars)", initialPrompt.length());
			}
			else {
				logger.info("Client connected without an initial prompt (CLI 进程将在首次 query() 时启动)");
			}
		}
		catch (Exception e) {
			cleanup();
			throw new TransportException("Failed to connect client", e);
		}
	}

	private void sendInitialize() throws SolonCodeSDKException {
		Map<String, List<ControlRequest.HookMatcherConfig>> hookConfig = hookRegistry.buildHookConfig();

		if (hookConfig.isEmpty()) {
			logger.debug("No hooks to initialize");
			return;
		}

		Map<String, Object> request = new LinkedHashMap<>();
		request.put("subtype", "initialize");
		request.put("hooks", hookConfig);

		logger.debug("Sending initialize with {} hook event types", hookConfig.size());
		sendControlRequest(request);
		logger.info("Hook configuration sent to CLI: {} event types", hookConfig.size());
	}

	@Override
	public void query(String prompt) throws SolonCodeSDKException {
		ensureConnected();
		startTurn(prompt, currentSessionId.get());
		logger.debug("Sent query in session {}: {}", currentSessionId.get(),
				prompt.substring(0, Math.min(50, prompt.length())));
	}

	/** 创建（或重建）当前轮次的消息接收器。常驻进程仍按 ResultMessage 切分轮次。 */
	private void prepareReceivers() {
		if (messageIterator != null) {
			messageIterator.complete();
			messageIterator.close();
		}
		messageIterator = new MessageStreamIterator(1000, 100, timeout, this::timeoutCurrentTurn,
				() -> activeTurn.set(false));
		turnTimeoutTriggered.set(false);
	}

	private void timeoutCurrentTurn() {
		if (!turnTimeoutTriggered.compareAndSet(false, true)) {
			return;
		}
		TransportException failure = new TransportException("Response timed out after " + timeout);
		activeTurn.set(false);
		if (messageIterator != null) {
			messageIterator.completeWithError(failure);
		}
		Transport current = transport;
		if (current != null) {
			try {
				current.interrupt();
			}
			catch (Throwable e) {
				logger.debug("Failed to interrupt timed out turn", e);
			}
		}
	}

	/**
	 * 启动一轮执行。常驻 stdio 首轮启动 {@code soloncode stream}，后续只写 JSONL 用户帧；
	 * HTTP 与显式 stdioOneShot 继续每轮创建一次性 transport。
	 */
	private synchronized void startTurn(String prompt) throws SolonCodeSDKException {
		startTurn(prompt, null);
	}

	private synchronized void startTurn(String prompt, String requestedSessionId) throws SolonCodeSDKException {
		if (prompt == null || prompt.isEmpty()) {
			throw new TransportException("prompt 不能为空：soloncode run 无提示词时以退出码 3 终止");
		}
		if (transportSpec.isPersistent() && requestedSessionId != null && !requestedSessionId.trim().isEmpty()
				&& !requestedSessionId.equals(currentSessionId.get())) {
			throw new UnsupportedOperationException(
					"A persistent stdio client cannot switch session_id; create a new client for that session");
		}
		if (!activeTurn.compareAndSet(false, true)) {
			throw new IllegalStateException(
					"A response is still active; consume or cancel it before starting another turn");
		}

		try {
			String turnSessionId = requestedSessionId != null && !requestedSessionId.trim().isEmpty()
					? requestedSessionId : currentSessionId.get();
			prepareReceivers();
			turnResultReceived.set(false);

			if (transportSpec.isPersistent()) {
				Transport current = transport;
				if (current == null) {
					turnStarted.set(true);
					current = transportSpec.create(workingDirectory, timeout);
					current.setTurnSession(turnSessionId, null);
					transport = current;
					current.startSession(prompt, options, this::handleMessage, this::handleControlRequest,
							this::handleControlResponse);
				}
				else {
					current.sendUserMessage(prompt, turnSessionId);
				}
			}
			else {
				Transport previous = transport;
				if (previous != null) {
					previous.close();
				}

				boolean firstTurn = !turnStarted.getAndSet(true);
				transport = transportSpec.create(workingDirectory, timeout);
				transport.setTurnSession(turnSessionId, firstTurn ? null : turnSessionId);
				transport.startSession(prompt, options, this::handleMessage, this::handleControlRequest,
						this::handleControlResponse);
			}
			currentSessionId.set(turnSessionId);
		}
		catch (Throwable e) {
			activeTurn.set(false);
			throw e;
		}
	}

	@Override
	public Iterator<ParsedMessage> receiveResponse() {
		ensureConnected();
		return new ResponseBoundedIterator(messageIterator);
	}

	@Override
	public void interrupt() throws SolonCodeSDKException {
		ensureConnected();
		activeTurn.set(false);
		// 常驻 stdio 发送 interrupt 控制帧并保留进程；one-shot/http 由各 transport 处理。
		if (transport != null) {
			transport.interrupt();
		}
		else {
			sendControlRequest(SdkCollections.map("subtype", "interrupt"));
		}
	}

	@Override
	public void setPermissionMode(String mode) throws SolonCodeSDKException {
		ensureConnected();
		sendControlRequest(SdkCollections.map("subtype", "set_permission_mode", "mode", mode));
		currentPermissionMode.set(mode);
	}

	@Override
	public void setModel(String model) throws SolonCodeSDKException {
		ensureConnected();
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("subtype", "set_model");
		request.put("model", model);
		sendControlRequest(request);
		currentModel.set(model);
	}

	@Override
	public String getCurrentModel() {
		return currentModel.get();
	}

	@Override
	public String getCurrentPermissionMode() {
		return currentPermissionMode.get();
	}

	@Override
	public void setToolPermissionCallback(ToolPermissionCallback callback) {
		this.toolPermissionCallback = callback;
	}

	@Override
	public ToolPermissionCallback getToolPermissionCallback() {
		return toolPermissionCallback;
	}

	@Override
	public Map<String, Object> getServerInfo() {
		return serverInfo.get();
	}

	@Override
	public CLIOptions getOptions() {
		return options;
	}

	@Override
	public boolean isConnected() {
		return connected.get() && !closed.get() && transport != null && transport.isRunning();
	}

	@Override
	public void close() {
		if (closed.compareAndSet(false, true)) {
			connected.set(false);
			cleanup();
			logger.info("Client closed");
		}
	}

	private void handleMessage(ParsedMessage message) {
		// Detect session-end signal from transport (process exited, stream closed)
		if (message instanceof ParsedMessage.EndOfStream) {
			activeTurn.set(false);
			Throwable failure = transport == null ? null : transport.getSessionError();
			if (failure == null && !turnResultReceived.get() && !closed.get()) {
				failure = new TransportException("Response stream ended before result event");
			}
			logger.debug("Session ended — completing message receivers");
			if (messageIterator != null) {
				if (failure == null) {
					messageIterator.complete();
				}
				else {
					messageIterator.completeWithError(failure);
				}
			}
			return;
		}
		// Forward regular messages to both receivers
		if (message.isRegularMessage()) {
			Message regular = message.asMessage();
			if (regular instanceof SystemMessage) {
				SystemMessage system = (SystemMessage) regular;
				Object sid = system.data().get("session_id");
				if ("error".equalsIgnoreCase(system.subtype())) {
					turnResultReceived.set(true);
				}
				if (sid != null && !String.valueOf(sid).isEmpty()) {
					currentSessionId.set(String.valueOf(sid));
				}
			}
			if (regular instanceof ResultMessage) {
				ResultMessage result = (ResultMessage) regular;
				turnResultReceived.set(true);
				if (result.sessionId() != null && !result.sessionId().isEmpty()) {
					currentSessionId.set(result.sessionId());
				}
			}
			if (!messageIterator.offer(message)) {
				messageIterator.completeWithError(new TransportException("Response buffer is full"));
			}
		}
	}

	private ControlResponse handleControlRequest(ControlRequest request) {
		String requestId = request.requestId();
		ControlRequest.ControlRequestPayload payload = request.request();

		logger.debug("Handling control request: type={}, requestId={}", payload != null ? payload.subtype() : "null",
				requestId);

		try {
			if (payload instanceof ControlRequest.HookCallbackRequest) {
				ControlRequest.HookCallbackRequest hookCallback = (ControlRequest.HookCallbackRequest) payload;
				return handleHookCallback(requestId, hookCallback);
			}
			else if (payload instanceof ControlRequest.CanUseToolRequest) {
				ControlRequest.CanUseToolRequest canUseTool = (ControlRequest.CanUseToolRequest) payload;
				return handleCanUseTool(requestId, canUseTool);
			}
			else if (payload instanceof ControlRequest.InitializeRequest) {
				ControlRequest.InitializeRequest init = (ControlRequest.InitializeRequest) payload;
				serverInfo.set(SdkCollections.map("hooks", init.hooks() != null ? init.hooks() : Collections.<String, Object>emptyMap()));
				return ControlResponse.success(requestId, SdkCollections.map("status", "ok"));
			}
			else if (payload instanceof ControlRequest.McpMessageRequest) {
				ControlRequest.McpMessageRequest mcpMessage = (ControlRequest.McpMessageRequest) payload;
				return handleMcpMessage(requestId, mcpMessage);
			}
			else {
				return ControlResponse.success(requestId, SdkCollections.map());
			}
		}
		catch (Exception e) {
			logger.error("Error handling control request", e);
			return ControlResponse.error(requestId, e.getMessage());
		}
	}

	private ControlResponse handleHookCallback(String requestId, ControlRequest.HookCallbackRequest hookCallback) {
		try {
			String callbackId = hookCallback.callbackId();
			Map<String, Object> inputMap = hookCallback.input();

			// 多态：按 hook_event_name 分派（见 HookInput.HookInputCreator）
			HookInput input = SdkJson.convert(inputMap, HookInput.class);
			HookOutput output = hookRegistry.executeHook(callbackId, input);

			Map<String, Object> responsePayload = new LinkedHashMap<>();
			responsePayload.put("continue", output.continueExecution());
			if (output.decision() != null) {
				responsePayload.put("decision", output.decision());
			}
			if (output.reason() != null) {
				responsePayload.put("reason", output.reason());
			}
			if (output.hookSpecificOutput() != null) {
				HookOutput.HookSpecificOutput specific = output.hookSpecificOutput();
				if (specific.permissionDecision() != null) {
					responsePayload.put("permission_decision", specific.permissionDecision());
				}
				if (specific.permissionDecisionReason() != null) {
					responsePayload.put("permission_decision_reason", specific.permissionDecisionReason());
				}
				if (specific.updatedInput() != null) {
					responsePayload.put("updated_input", specific.updatedInput());
				}
			}

			return ControlResponse.success(requestId, responsePayload);
		}
		catch (Exception e) {
			logger.error("Error executing hook callback", e);
			return ControlResponse.error(requestId, e.getMessage());
		}
	}

	private ControlResponse handleMcpMessage(String requestId, ControlRequest.McpMessageRequest mcpMessage) {
		String serverName = mcpMessage.serverName();
		Map<String, Object> message = mcpMessage.message();

		logger.debug("Handling MCP message for server {}: method={}", serverName, mcpMessage.getMethod());

		if (!mcpMessageHandler.hasServer(serverName)) {
			logger.warn("MCP server not registered: {}", serverName);
			return ControlResponse.error(requestId, "Unknown MCP server: " + serverName);
		}

		try {
			Map<String, Object> response = mcpMessageHandler.handleMcpMessage(serverName, message);

			if (response == null) {
				return ControlResponse.success(requestId, SdkCollections.map());
			}

			return ControlResponse.success(requestId, SdkCollections.map("mcp_response", response));
		}
		catch (Exception e) {
			logger.error("Error handling MCP message for server {}", serverName, e);
			return ControlResponse.error(requestId, "MCP error: " + e.getMessage());
		}
	}

	private ControlResponse handleCanUseTool(String requestId, ControlRequest.CanUseToolRequest canUseTool) {
		ToolPermissionCallback callback = toolPermissionCallback;
		if (callback == null) {
			return ControlResponse.success(requestId, SdkCollections.map("behavior", "allow"));
		}

		try {
			ToolPermissionContext context = ToolPermissionContext.of(canUseTool.permissionSuggestions(),
					canUseTool.blockedPath(), requestId);

			PermissionResult result = callback.checkPermission(canUseTool.toolName(), canUseTool.input(), context);

			if (result.isAllowed()) {
				PermissionResult.Allow allow = (PermissionResult.Allow) result;
				Map<String, Object> response = new LinkedHashMap<>();
				response.put("behavior", "allow");

				// Always include updatedInput — SolonCode Code's Zod schema requires it
				response.put("updatedInput", allow.hasUpdatedInput() ? allow.updatedInput() : SdkCollections.map());
				return ControlResponse.success(requestId, response);
			}
			else {
				PermissionResult.Deny deny = (PermissionResult.Deny) result;
				Map<String, Object> response = new LinkedHashMap<>();
				response.put("behavior", "deny");

				if (deny.hasMessage()) {
					response.put("message", deny.message());
				}
				return ControlResponse.success(requestId, response);
			}
		}
		catch (Exception e) {
			logger.error("Tool permission callback threw exception for tool {}", canUseTool.toolName(), e);
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("behavior", "deny");
			response.put("message", "Permission callback error: " + e.getMessage());
			return ControlResponse.success(requestId, response);
		}
	}

	private void handleControlResponse(ControlResponse response) {
		if (response.response() == null) {
			logger.warn("Received control response with null payload");
			return;
		}

		String requestId = response.response().requestId();
		if (requestId == null) {
			logger.warn("Received control response without request_id");
			return;
		}

		logger.debug("Handling control response: requestId={}, subtype={}", requestId, response.response().subtype());

		MonoSink<Map<String, Object>> sink = pendingResponses.remove(requestId);
		if (sink == null) {
			logger.warn("Unexpected response for unknown request id {}", requestId);
			return;
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("subtype", response.response().subtype());

		if (response.response() instanceof ControlResponse.SuccessPayload) {
			ControlResponse.SuccessPayload success = (ControlResponse.SuccessPayload) response.response();
			if (success.response() instanceof Map<?, ?>) {
				Map<?, ?> responseMap = (Map<?, ?>) success.response();
				@SuppressWarnings("unchecked")
				Map<String, Object> typedMap = (Map<String, Object>) responseMap;
				payload.putAll(typedMap);
			}
			sink.success(payload);
			logger.debug("Control response delivered for requestId={}", requestId);
		}
		else if (response.response() instanceof ControlResponse.ErrorPayload) {
			ControlResponse.ErrorPayload error = (ControlResponse.ErrorPayload) response.response();
			sink.error(new SolonCodeSDKException("Control request failed: " + error.error()));
			logger.debug("Control response error delivered for requestId={}", requestId);
		}
		else {
			sink.success(payload);
		}
	}

	private String generateRequestId() {
		return sessionPrefix + "-" + requestCounter.getAndIncrement();
	}

	private void sendControlRequest(Map<String, Object> request) throws SolonCodeSDKException {
		ensureConnected();

		String requestId = generateRequestId();

		try {
			Map<String, Object> result = Mono.<Map<String, Object>>create(sink -> {
				logger.debug("Sending control request: subtype={}, requestId={}", request.get("subtype"), requestId);

				pendingResponses.put(requestId, sink);

				try {
					Map<String, Object> controlRequest = new LinkedHashMap<>();
					controlRequest.put("type", "control_request");
					controlRequest.put("request_id", requestId);
					controlRequest.put("request", request);

					String json = SdkJson.toJsonWithNulls(controlRequest);
					transport.sendMessage(json);
				}
				catch (Exception e) {
					pendingResponses.remove(requestId);
					sink.error(e);
				}
			}).timeout(timeout).doOnError(e -> {
				pendingResponses.remove(requestId);
			}).block();

			if (result != null && result.containsKey("error")) {
				throw new SolonCodeSDKException("Control request failed: " + result.get("error"));
			}
		}
		catch (SolonCodeSDKException e) {
			throw e;
		}
		catch (Exception e) {
			if (e.getCause() instanceof java.util.concurrent.TimeoutException
					|| e instanceof java.util.concurrent.TimeoutException) {
				throw new SolonCodeSDKException("Control request timed out: " + request.get("subtype"), e);
			}
			throw new SolonCodeSDKException("Failed to send control request", e);
		}
	}

	private void ensureConnected() {
		if (!connected.get()) {
			throw new IllegalStateException("Client is not connected. Call connect() first.");
		}
		if (closed.get()) {
			throw new IllegalStateException("Client has been closed.");
		}
	}

	private void cleanup() {
		if (messageIterator != null) {
			messageIterator.complete();
			messageIterator.close();
		}
		if (transport != null) {
			transport.close();
		}
		dismissPendingResponses();
	}

	private void dismissPendingResponses() {
		pendingResponses.forEach((id, sink) -> {
			logger.warn("Abruptly terminating pending request: {}", id);
			sink.error(new SolonCodeSDKException("Client closed while request was pending"));
		});
		pendingResponses.clear();
	}

	/**
	 * Iterator that stops after receiving a ResultMessage.
	 */
	private static class ResponseBoundedIterator implements Iterator<ParsedMessage> {

		private final Iterator<ParsedMessage> delegate;

		private ParsedMessage next;

		private boolean resultReceived = false;

		ResponseBoundedIterator(Iterator<ParsedMessage> delegate) {
			this.delegate = delegate;
		}

		@Override
		public boolean hasNext() {
			if (resultReceived) {
				return false;
			}
			if (next != null) {
				return true;
			}
			if (delegate.hasNext()) {
				next = delegate.next();
				if (next.isRegularMessage()) {
					Message msg = next.asMessage();
					if (msg.isTerminal()) {
						resultReceived = true;
					}
				}
				return true;
			}
			return false;
		}

		@Override
		public ParsedMessage next() {
			if (next == null) {
				throw new NoSuchElementException("No element available. Did you call hasNext() first?");
			}
			ParsedMessage result = next;
			next = null;
			return result;
		}

	}

	/**
	 * Iterable wrapper that filters ParsedMessages to regular Messages for elegant
	 * for-each usage.
	 */
	private static class MessageIterable implements Iterable<Message> {

		private final Iterator<ParsedMessage> delegate;

		MessageIterable(Iterator<ParsedMessage> delegate) {
			this.delegate = delegate;
		}

		@Override
		public Iterator<Message> iterator() {
			return new MessageIterator(delegate);
		}

		private static class MessageIterator implements Iterator<Message> {

			private final Iterator<ParsedMessage> delegate;

			private Message next;

			MessageIterator(Iterator<ParsedMessage> delegate) {
				this.delegate = delegate;
			}

			@Override
			public boolean hasNext() {
				if (next != null) {
					return true;
				}
				while (delegate.hasNext()) {
					ParsedMessage parsed = delegate.next();
					if (parsed.isRegularMessage()) {
						next = parsed.asMessage();
						return true;
					}
				}
				return false;
			}

			@Override
			public Message next() {
				if (next == null && !hasNext()) {
					throw new NoSuchElementException();
				}
				Message result = next;
				next = null;
				return result;
			}

		}

	}

}

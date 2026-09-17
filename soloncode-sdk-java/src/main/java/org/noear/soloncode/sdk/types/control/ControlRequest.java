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

package org.noear.soloncode.sdk.types.control;

import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.snack4.annotation.ONodeAttr;
import org.noear.snack4.codec.CodecException;
import org.noear.snack4.codec.ObjectCreator;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Control request wrapper for bidirectional communication with SolonCode CLI. The CLI sends
 * these requests to the SDK for permission checks, hook callbacks, etc.
 */
public final class ControlRequest {

	@ONodeAttr(name = "type")
	private final String type;

	@ONodeAttr(name = "request_id")
	private final String requestId;

	@ONodeAttr(name = "request")
	private final ControlRequestPayload request;

	public ControlRequest(@ONodeAttr(name = "type") String type, @ONodeAttr(name = "request_id") String requestId,
			@ONodeAttr(name = "request") ControlRequestPayload request) {
		this.type = type;
		this.requestId = requestId;
		this.request = request;
	}

	public String type() {
		return type;
	}

	public String requestId() {
		return requestId;
	}

	public ControlRequestPayload request() {
		return request;
	}

	public static final String TYPE = "control_request";

	/**
	 * Check if this is a control request by type.
	 */
	public boolean isControlRequest() {
		return TYPE.equals(type);
	}

	/**
	 * Interface for control request payload types.
	 *
	 * <p>
	 * snack4 无 Jackson 的 {@code @JsonTypeInfo/@JsonSubTypes} 等价物，多态入向由
	 * {@link PayloadCreator} 按判别字段 {@code subtype} 手工分派；出向由各实现类自带的
	 * {@code subtype} 字段落地（与 Jackson 的 As.PROPERTY 输出等价）。
	 * </p>
	 */
	@ONodeAttr(creator = PayloadCreator.class)
	public interface ControlRequestPayload {

		String subtype();

	}

	/**
	 * {@link ControlRequestPayload} 的多态解码分派器。判别字段：{@code subtype}。
	 * 映射表与原 Jackson {@code @JsonSubTypes} 逐项对齐。
	 */
	public static final class PayloadCreator implements ObjectCreator<ControlRequestPayload> {

		@Override
		public ControlRequestPayload create(Options opts, ONode node, Class<?> clazz) {
			if (node == null || !node.isObject()) {
				return null;
			}

			String subtype = node.get("subtype").getString();
			if (subtype == null) {
				throw new CodecException("Missing 'subtype' in control request payload");
			}

			switch (subtype) {
				case "initialize":
					return node.toBean(InitializeRequest.class);
				case "can_use_tool":
					return node.toBean(CanUseToolRequest.class);
				case "hook_callback":
					return node.toBean(HookCallbackRequest.class);
				case "interrupt":
					return node.toBean(InterruptRequest.class);
				case "set_permission_mode":
					return node.toBean(SetPermissionModeRequest.class);
				case "set_model":
					return node.toBean(SetModelRequest.class);
				case "mcp_message":
					return node.toBean(McpMessageRequest.class);
				default:
					throw new CodecException("Unknown control request subtype: " + subtype);
			}
		}

	}

	/**
	 * Initialize request - sent by SDK to register hooks at startup.
	 */
	public static final class InitializeRequest implements ControlRequestPayload {

		/** 判别字段：原 Jackson 由 @JsonTypeInfo(As.PROPERTY) 输出，snack4 下由本字段输出。 */
		@ONodeAttr(name = "subtype")
		private final String subtypeValue = "initialize";

		@ONodeAttr(name = "hooks")
		private final Map<String, List<HookMatcherConfig>> hooks;

		public InitializeRequest(@ONodeAttr(name = "hooks") Map<String, List<HookMatcherConfig>> hooks) {
			this.hooks = hooks;
		}

		public Map<String, List<HookMatcherConfig>> hooks() {
			return hooks;
		}

		@Override
		public String subtype() {
			return subtypeValue;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof InitializeRequest)) {
				return false;
			}
			InitializeRequest that = (InitializeRequest) o;
			return Objects.equals(hooks, that.hooks);
		}

		@Override
		public int hashCode() {
			return Objects.hash(hooks);
		}

		@Override
		public String toString() {
			return "InitializeRequest[hooks=" + hooks + "]";
		}
	}

	/**
	 * Hook matcher configuration sent during initialization.
	 */
	public static final class HookMatcherConfig {

		@ONodeAttr(name = "matcher")
		private final String matcher;

		@ONodeAttr(name = "hookCallbackIds")
		private final List<String> hookCallbackIds;

		@ONodeAttr(name = "timeout")
		private final Integer timeout;

		public HookMatcherConfig(@ONodeAttr(name = "matcher") String matcher,
				@ONodeAttr(name = "hookCallbackIds") List<String> hookCallbackIds,
				@ONodeAttr(name = "timeout") Integer timeout) {
			this.matcher = matcher;
			this.hookCallbackIds = hookCallbackIds;
			this.timeout = timeout;
		}

		public String matcher() {
			return matcher;
		}

		public List<String> hookCallbackIds() {
			return hookCallbackIds;
		}

		public Integer timeout() {
			return timeout;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof HookMatcherConfig)) {
				return false;
			}
			HookMatcherConfig that = (HookMatcherConfig) o;
			return Objects.equals(matcher, that.matcher) && Objects.equals(hookCallbackIds, that.hookCallbackIds)
					&& Objects.equals(timeout, that.timeout);
		}

		@Override
		public int hashCode() {
			return Objects.hash(matcher, hookCallbackIds, timeout);
		}

		@Override
		public String toString() {
			return "HookMatcherConfig[matcher=" + matcher + ", hookCallbackIds=" + hookCallbackIds + ", timeout="
					+ timeout + "]";
		}
	}

	/**
	 * Permission request - CLI asks SDK if a tool can be used.
	 */
	public static final class CanUseToolRequest implements ControlRequestPayload {

		/** 判别字段：原 Jackson 由 @JsonTypeInfo(As.PROPERTY) 输出，snack4 下由本字段输出。 */
		@ONodeAttr(name = "subtype")
		private final String subtypeValue = "can_use_tool";

		@ONodeAttr(name = "tool_name")
		private final String toolName;

		@ONodeAttr(name = "input")
		private final Map<String, Object> input;

		@ONodeAttr(name = "permission_suggestions")
		private final List<Map<String, Object>> permissionSuggestions;

		@ONodeAttr(name = "blocked_path")
		private final String blockedPath;

		public CanUseToolRequest(@ONodeAttr(name = "tool_name") String toolName,
				@ONodeAttr(name = "input") Map<String, Object> input,
				@ONodeAttr(name = "permission_suggestions") List<Map<String, Object>> permissionSuggestions,
				@ONodeAttr(name = "blocked_path") String blockedPath) {
			this.toolName = toolName;
			this.input = input;
			this.permissionSuggestions = permissionSuggestions;
			this.blockedPath = blockedPath;
		}

		public String toolName() {
			return toolName;
		}

		public Map<String, Object> input() {
			return input;
		}

		public List<Map<String, Object>> permissionSuggestions() {
			return permissionSuggestions;
		}

		public String blockedPath() {
			return blockedPath;
		}

		@Override
		public String subtype() {
			return subtypeValue;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof CanUseToolRequest)) {
				return false;
			}
			CanUseToolRequest that = (CanUseToolRequest) o;
			return Objects.equals(toolName, that.toolName) && Objects.equals(input, that.input)
					&& Objects.equals(permissionSuggestions, that.permissionSuggestions)
					&& Objects.equals(blockedPath, that.blockedPath);
		}

		@Override
		public int hashCode() {
			return Objects.hash(toolName, input, permissionSuggestions, blockedPath);
		}

		@Override
		public String toString() {
			return "CanUseToolRequest[toolName=" + toolName + ", input=" + input + ", permissionSuggestions="
					+ permissionSuggestions + ", blockedPath=" + blockedPath + "]";
		}
	}

	/**
	 * Hook callback request - CLI asks SDK to execute a registered hook.
	 */
	public static final class HookCallbackRequest implements ControlRequestPayload {

		/** 判别字段：原 Jackson 由 @JsonTypeInfo(As.PROPERTY) 输出，snack4 下由本字段输出。 */
		@ONodeAttr(name = "subtype")
		private final String subtypeValue = "hook_callback";

		@ONodeAttr(name = "callback_id")
		private final String callbackId;

		@ONodeAttr(name = "input")
		private final Map<String, Object> input;

		@ONodeAttr(name = "tool_use_id")
		private final String toolUseId;

		public HookCallbackRequest(@ONodeAttr(name = "callback_id") String callbackId,
				@ONodeAttr(name = "input") Map<String, Object> input,
				@ONodeAttr(name = "tool_use_id") String toolUseId) {
			this.callbackId = callbackId;
			this.input = input;
			this.toolUseId = toolUseId;
		}

		public String callbackId() {
			return callbackId;
		}

		public Map<String, Object> input() {
			return input;
		}

		public String toolUseId() {
			return toolUseId;
		}

		@Override
		public String subtype() {
			return subtypeValue;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof HookCallbackRequest)) {
				return false;
			}
			HookCallbackRequest that = (HookCallbackRequest) o;
			return Objects.equals(callbackId, that.callbackId) && Objects.equals(input, that.input)
					&& Objects.equals(toolUseId, that.toolUseId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(callbackId, input, toolUseId);
		}

		@Override
		public String toString() {
			return "HookCallbackRequest[callbackId=" + callbackId + ", input=" + input + ", toolUseId=" + toolUseId
					+ "]";
		}
	}

	/**
	 * Interrupt request - SDK tells CLI to stop execution.
	 */
	public static final class InterruptRequest implements ControlRequestPayload {

		/** 判别字段：原 Jackson 由 @JsonTypeInfo(As.PROPERTY) 输出，snack4 下由本字段输出。 */
		@ONodeAttr(name = "subtype")
		private final String subtypeValue = "interrupt";

		public InterruptRequest() {
		}

		@Override
		public String subtype() {
			return subtypeValue;
		}

		@Override
		public boolean equals(Object o) {
			return this == o || o instanceof InterruptRequest;
		}

		@Override
		public int hashCode() {
			return InterruptRequest.class.hashCode();
		}

		@Override
		public String toString() {
			return "InterruptRequest[]";
		}
	}

	/**
	 * Set permission mode request - SDK changes permission mode dynamically.
	 */
	public static final class SetPermissionModeRequest implements ControlRequestPayload {

		/** 判别字段：原 Jackson 由 @JsonTypeInfo(As.PROPERTY) 输出，snack4 下由本字段输出。 */
		@ONodeAttr(name = "subtype")
		private final String subtypeValue = "set_permission_mode";

		@ONodeAttr(name = "mode")
		private final String mode;

		public SetPermissionModeRequest(@ONodeAttr(name = "mode") String mode) {
			this.mode = mode;
		}

		public String mode() {
			return mode;
		}

		@Override
		public String subtype() {
			return subtypeValue;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof SetPermissionModeRequest)) {
				return false;
			}
			SetPermissionModeRequest that = (SetPermissionModeRequest) o;
			return Objects.equals(mode, that.mode);
		}

		@Override
		public int hashCode() {
			return Objects.hash(mode);
		}

		@Override
		public String toString() {
			return "SetPermissionModeRequest[mode=" + mode + "]";
		}
	}

	/**
	 * Set model request - SDK changes model dynamically.
	 */
	public static final class SetModelRequest implements ControlRequestPayload {

		/** 判别字段：原 Jackson 由 @JsonTypeInfo(As.PROPERTY) 输出，snack4 下由本字段输出。 */
		@ONodeAttr(name = "subtype")
		private final String subtypeValue = "set_model";

		@ONodeAttr(name = "model")
		private final String model;

		public SetModelRequest(@ONodeAttr(name = "model") String model) {
			this.model = model;
		}

		public String model() {
			return model;
		}

		@Override
		public String subtype() {
			return subtypeValue;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof SetModelRequest)) {
				return false;
			}
			SetModelRequest that = (SetModelRequest) o;
			return Objects.equals(model, that.model);
		}

		@Override
		public int hashCode() {
			return Objects.hash(model);
		}

		@Override
		public String toString() {
			return "SetModelRequest[model=" + model + "]";
		}
	}

	/**
	 * MCP message request - routes JSON-RPC messages to in-process SDK MCP servers.
	 * <p>
	 * The CLI sends this when it needs to invoke tools on SDK-managed MCP servers. The
	 * message field contains a JSON-RPC 2.0 request (with method, params, id fields).
	 */
	public static final class McpMessageRequest implements ControlRequestPayload {

		/** 判别字段：原 Jackson 由 @JsonTypeInfo(As.PROPERTY) 输出，snack4 下由本字段输出。 */
		@ONodeAttr(name = "subtype")
		private final String subtypeValue = "mcp_message";

		@ONodeAttr(name = "server_name")
		private final String serverName;

		@ONodeAttr(name = "message")
		private final Map<String, Object> message;

		public McpMessageRequest(@ONodeAttr(name = "server_name") String serverName,
				@ONodeAttr(name = "message") Map<String, Object> message) {
			this.serverName = serverName;
			this.message = message;
		}

		public String serverName() {
			return serverName;
		}

		public Map<String, Object> message() {
			return message;
		}

		@Override
		public String subtype() {
			return subtypeValue;
		}

		/**
		 * Extracts the JSON-RPC method name from the message.
		 * @return the method name, or null if not present
		 */
		public String getMethod() {
			return message != null ? (String) message.get("method") : null;
		}

		/**
		 * Extracts the JSON-RPC request ID from the message.
		 * @return the request ID, or null if not present (notification)
		 */
		public Object getId() {
			return message != null ? message.get("id") : null;
		}

		/**
		 * Extracts the JSON-RPC params from the message.
		 * @return the params map, or null if not present
		 */
		@SuppressWarnings("unchecked")
		public Map<String, Object> getParams() {
			return message != null ? (Map<String, Object>) message.get("params") : null;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof McpMessageRequest)) {
				return false;
			}
			McpMessageRequest that = (McpMessageRequest) o;
			return Objects.equals(serverName, that.serverName) && Objects.equals(message, that.message);
		}

		@Override
		public int hashCode() {
			return Objects.hash(serverName, message);
		}

		@Override
		public String toString() {
			return "McpMessageRequest[serverName=" + serverName + ", message=" + message + "]";
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ControlRequest)) {
			return false;
		}
		ControlRequest that = (ControlRequest) o;
		return Objects.equals(type, that.type) && Objects.equals(requestId, that.requestId)
				&& Objects.equals(request, that.request);
	}

	@Override
	public int hashCode() {
		return Objects.hash(type, requestId, request);
	}

	@Override
	public String toString() {
		return "ControlRequest[type=" + type + ", requestId=" + requestId + ", request=" + request + "]";
	}
}

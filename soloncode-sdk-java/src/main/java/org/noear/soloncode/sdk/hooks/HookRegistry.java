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

package org.noear.soloncode.sdk.hooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.noear.soloncode.sdk.types.control.ControlRequest;
import org.noear.soloncode.sdk.types.control.HookEvent;
import org.noear.soloncode.sdk.types.control.HookInput;
import org.noear.soloncode.sdk.types.control.HookOutput;
import org.noear.soloncode.sdk.util.SdkCollections;
import org.noear.soloncode.sdk.types.control.HookEvent;
import org.noear.soloncode.sdk.types.control.HookInput;
import org.noear.soloncode.sdk.types.control.HookOutput;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Registry for managing hook registrations and building initialization configuration.
 *
 * <p>
 * The registry maintains hooks organized by event type and provides methods to:
 * </p>
 * <ul>
 * <li>Register hooks with pattern matching</li>
 * <li>Find matching hooks for incoming requests</li>
 * <li>Build initialization configuration for CLI</li>
 * <li>Execute callbacks and collect results</li>
 * </ul>
 *
 * <p>
 * Example:
 * </p>
 *
 * <pre>{@code
 * HookRegistry registry = new HookRegistry();
 *
 * // Register a PreToolUse hook for Bash commands
 * registry.register(HookRegistration.preToolUse("bash_guard", "Bash",
 *     input -> {
 *         HookInput.PreToolUseInput preToolUse = (HookInput.PreToolUseInput) input;
 *         String cmd = preToolUse.getArgument("command", String.class).orElse("");
 *         if (cmd.contains("rm -rf")) {
 *             return HookOutput.block("Dangerous command blocked");
 *         }
 *         return HookOutput.allow();
 *     }));
 *
 * // Build initialization config
 * Map<String, List<ControlRequest.HookMatcherConfig>> config = registry.buildHookConfig();
 * }</pre>
 */
public class HookRegistry {

	private static final Logger logger = LoggerFactory.getLogger(HookRegistry.class);

	private final Map<HookEvent, List<HookRegistration>> hooksByEvent = new ConcurrentHashMap<>();

	private final Map<String, HookRegistration> hooksById = new ConcurrentHashMap<>();

	private final AtomicInteger idCounter = new AtomicInteger(0);

	/**
	 * Registers a hook.
	 * @param registration the hook registration
	 * @return this registry for chaining
	 */
	public HookRegistry register(HookRegistration registration) {
		if (hooksById.containsKey(registration.id())) {
			throw new IllegalArgumentException("Hook with id '" + registration.id() + "' already registered");
		}

		hooksById.put(registration.id(), registration);
		hooksByEvent.computeIfAbsent(registration.event(), k -> new ArrayList<>()).add(registration);

		logger.debug("Registered hook: id={}, event={}, pattern={}", registration.id(), registration.event(),
				registration.getPatternString());

		return this;
	}

	/**
	 * Registers a hook for a specific event and tool pattern.
	 * @param event the hook event type
	 * @param toolPattern regex pattern for tool names, or null for all tools
	 * @param callback the callback to execute
	 * @return the generated hook ID
	 */
	public String register(HookEvent event, String toolPattern, HookCallback callback) {
		String id = "hook_" + idCounter.getAndIncrement();
		java.util.regex.Pattern pattern = toolPattern != null ? java.util.regex.Pattern.compile(toolPattern) : null;
		register(new HookRegistration(id, event, pattern, callback, HookRegistration.DEFAULT_TIMEOUT));
		return id;
	}

	/**
	 * Registers a PreToolUse hook for specific tools.
	 * @param toolPattern regex pattern for tool names
	 * @param callback the callback
	 * @return the generated hook ID
	 */
	public String registerPreToolUse(String toolPattern, HookCallback callback) {
		String id = "hook_" + idCounter.getAndIncrement();
		register(HookRegistration.preToolUse(id, toolPattern, callback));
		return id;
	}

	/**
	 * Registers a PreToolUse hook for all tools.
	 * @param callback the callback
	 * @return the generated hook ID
	 */
	public String registerPreToolUse(HookCallback callback) {
		String id = "hook_" + idCounter.getAndIncrement();
		register(HookRegistration.preToolUse(id, callback));
		return id;
	}

	/**
	 * Registers a PostToolUse hook for specific tools.
	 * @param toolPattern regex pattern for tool names
	 * @param callback the callback
	 * @return the generated hook ID
	 */
	public String registerPostToolUse(String toolPattern, HookCallback callback) {
		String id = "hook_" + idCounter.getAndIncrement();
		register(HookRegistration.postToolUse(id, toolPattern, callback));
		return id;
	}

	/**
	 * Registers a PostToolUse hook for all tools.
	 * @param callback the callback
	 * @return the generated hook ID
	 */
	public String registerPostToolUse(HookCallback callback) {
		String id = "hook_" + idCounter.getAndIncrement();
		register(HookRegistration.postToolUse(id, callback));
		return id;
	}

	/**
	 * Registers a UserPromptSubmit hook.
	 * @param callback the callback
	 * @return the generated hook ID
	 */
	public String registerUserPromptSubmit(HookCallback callback) {
		String id = "hook_" + idCounter.getAndIncrement();
		register(HookRegistration.userPromptSubmit(id, callback));
		return id;
	}

	/**
	 * Registers a Stop hook.
	 * @param callback the callback
	 * @return the generated hook ID
	 */
	public String registerStop(HookCallback callback) {
		String id = "hook_" + idCounter.getAndIncrement();
		register(HookRegistration.stop(id, callback));
		return id;
	}

	/**
	 * Unregisters a hook by ID.
	 * @param id the hook ID
	 * @return true if removed, false if not found
	 */
	public boolean unregister(String id) {
		HookRegistration registration = hooksById.remove(id);
		if (registration != null) {
			List<HookRegistration> eventHooks = hooksByEvent.get(registration.event());
			if (eventHooks != null) {
				eventHooks.removeIf(h -> h.id().equals(id));
			}
			logger.debug("Unregistered hook: id={}", id);
			return true;
		}
		return false;
	}

	/**
	 * Gets a hook by ID.
	 * @param id the hook ID
	 * @return the registration, or null if not found
	 */
	public HookRegistration getById(String id) {
		return hooksById.get(id);
	}

	/**
	 * Gets all hooks for an event type.
	 * @param event the event type
	 * @return list of registrations (never null)
	 */
	public List<HookRegistration> getByEvent(HookEvent event) {
		return hooksByEvent.getOrDefault(event, SdkCollections.list());
	}

	/**
	 * Gets all registered hook IDs.
	 * @return set of hook IDs
	 */
	public Set<String> getRegisteredIds() {
		return Collections.unmodifiableSet(new LinkedHashSet<>(hooksById.keySet()));
	}

	/**
	 * Checks if any hooks are registered.
	 * @return true if at least one hook is registered
	 */
	public boolean hasHooks() {
		return !hooksById.isEmpty();
	}

	/**
	 * Clears all registered hooks.
	 */
	public void clear() {
		hooksById.clear();
		hooksByEvent.clear();
		logger.debug("Cleared all hooks");
	}

	/**
	 * Executes a hook callback by ID.
	 * @param hookId the hook ID
	 * @param input the hook input
	 * @return the hook output, or null if hook not found
	 */
	public HookOutput executeHook(String hookId, HookInput input) {
		HookRegistration registration = hooksById.get(hookId);
		if (registration == null) {
			logger.warn("Hook not found: {}", hookId);
			return null;
		}

		try {
			logger.debug("Executing hook: id={}, event={}", hookId, registration.event());
			HookOutput output = registration.callback().handle(input);
			logger.debug("Hook result: id={}, continue={}", hookId, output.continueExecution());
			return output;
		}
		catch (Exception e) {
			logger.error("Hook execution failed: id={}", hookId, e);
			// Return a safe default on error
			return HookOutput.block("Hook execution failed: " + e.getMessage());
		}
	}

	/**
	 * Builds the hook configuration for CLI initialization. Returns a map from event
	 * names to hook matcher configs.
	 * @return configuration map for InitializeRequest
	 */
	public Map<String, List<ControlRequest.HookMatcherConfig>> buildHookConfig() {
		Map<String, List<ControlRequest.HookMatcherConfig>> config = new HashMap<>();

		for (Map.Entry<HookEvent, List<HookRegistration>> entry : hooksByEvent.entrySet()) {
			HookEvent event = entry.getKey();
			List<HookRegistration> registrations = entry.getValue();

			// Group hooks by pattern
			Map<String, List<HookRegistration>> byPattern = registrations.stream()
				.collect(Collectors.groupingBy(r -> r.getPatternString() != null ? r.getPatternString() : ".*"));

			List<ControlRequest.HookMatcherConfig> matchers = new ArrayList<>();

			for (Map.Entry<String, List<HookRegistration>> patternEntry : byPattern.entrySet()) {
				String pattern = patternEntry.getKey();
				List<String> hookIds = patternEntry.getValue().stream().map(HookRegistration::id).collect(Collectors.toList());

				int timeout = patternEntry.getValue()
					.stream()
					.mapToInt(HookRegistration::timeout)
					.max()
					.orElse(HookRegistration.DEFAULT_TIMEOUT);

				matchers.add(new ControlRequest.HookMatcherConfig(pattern, hookIds, timeout));
			}

			config.put(event.getProtocolName(), matchers);
		}

		return config;
	}

	/**
	 * Creates an initialize request with the current hook configuration.
	 * @param requestId the request ID to use
	 * @return the control request
	 */
	public ControlRequest createInitializeRequest(String requestId) {
		Map<String, List<ControlRequest.HookMatcherConfig>> hookConfig = buildHookConfig();
		ControlRequest.InitializeRequest init = new ControlRequest.InitializeRequest(hookConfig);
		return new ControlRequest(ControlRequest.TYPE, requestId, init);
	}

}

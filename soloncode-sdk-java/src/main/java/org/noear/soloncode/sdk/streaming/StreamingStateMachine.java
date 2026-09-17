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
 *
 * Adapted from claude-agent-sdk-java (Apache License 2.0).
 */

package org.noear.soloncode.sdk.streaming;

import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.AssistantMessage;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.SystemMessage;
import org.noear.soloncode.sdk.types.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * State machine for tracking and validating SolonCode CLI streaming message flow.
 *
 * <p>
 * Based on Python SDK analysis, the expected flow is:
 * </p>
 * <ol>
 * <li>{@code SystemMessage} (init) - Session initialization</li>
 * <li>{@code AssistantMessage}(s) - Response content (may be multiple)</li>
 * <li>{@code ResultMessage} - Final metadata and completion</li>
 * </ol>
 *
 * <p>
 * This state machine validates message ordering, detects anomalies, and provides
 * diagnostics for debugging streaming issues.
 * </p>
 */
public class StreamingStateMachine {

	private static final Logger logger = LoggerFactory.getLogger(StreamingStateMachine.class);

	/**
	 * Possible states in the streaming message flow.
	 */
	public enum State {

		/** Initial state, expecting SystemMessage (init) */
		AWAITING_INIT,

		/** Received init, expecting AssistantMessage(s) or ResultMessage */
		AWAITING_CONTENT,

		/** Stream completed with ResultMessage */
		COMPLETED,

		/** Error state due to unexpected message flow */
		ERROR

	}

	private State currentState = State.AWAITING_INIT;

	private final List<Message> receivedMessages = new ArrayList<>();

	private final Instant streamStartTime = Instant.now();

	private Instant lastMessageTime = Instant.now();

	private String sessionId;

	private int expectedTurns = 1;

	private boolean hasAssistantResponse = false;

	/**
	 * Validates and processes the next message in the stream.
	 * @param message the message to validate and process
	 * @throws SolonCodeSDKException if the message violates expected flow
	 */
	public void processMessage(Message message) throws SolonCodeSDKException {
		if (message == null) {
			throw new SolonCodeSDKException("Received null message");
		}

		lastMessageTime = Instant.now();
		receivedMessages.add(message);

		logger.debug("Processing message type: {} in state: {}", message.getClass().getSimpleName(), currentState);

		switch (currentState) {
			case AWAITING_INIT:
				handleAwaitingInit(message);
				break;
			case AWAITING_CONTENT:
				handleAwaitingContent(message);
				break;
			case COMPLETED:
				handleUnexpectedMessage(message);
				break;
			case ERROR:
				handleErrorState(message);
				break;
			default:
				break;
		}
	}

	private void handleAwaitingInit(Message message) throws SolonCodeSDKException {
		if (message instanceof SystemMessage) {
			SystemMessage systemMsg = (SystemMessage) message;
			// Extract session information for validation
			if (systemMsg.subtype().equals("init")) {
				sessionId = extractSessionId(systemMsg);
				currentState = State.AWAITING_CONTENT;
				logger.debug("Stream initialized with session ID: {}", sessionId);
			}
			else {
				throw new SolonCodeSDKException("Expected 'init' system message, got: " + systemMsg.subtype());
			}
		}
		else {
			currentState = State.ERROR;
			throw new SolonCodeSDKException("Expected SystemMessage (init), got: " + message.getClass().getSimpleName());
		}
	}

	private void handleAwaitingContent(Message message) throws SolonCodeSDKException {
		if (message instanceof AssistantMessage) {
			AssistantMessage assistantMsg = (AssistantMessage) message;
			hasAssistantResponse = true;
			logger.debug("Received assistant message with {} content blocks", assistantMsg.content().size());
			// Stay in AWAITING_CONTENT state (can receive multiple assistant messages)
		}
		else if (message instanceof UserMessage) {
			UserMessage userMsg = (UserMessage) message;
			logger.debug("Received user message (tool result or follow-up)");
			// Stay in AWAITING_CONTENT state
		}
		else if (message instanceof ResultMessage) {
			ResultMessage resultMsg = (ResultMessage) message;
			validateResultMessage(resultMsg);
			currentState = State.COMPLETED;
			logger.debug("Stream completed successfully");
		}
		else if (message instanceof SystemMessage) {
			SystemMessage systemMsg = (SystemMessage) message;
			// Additional system messages during flow (e.g., tool confirmations)
			logger.debug("Received additional system message: {}", systemMsg.subtype());
			// Stay in AWAITING_CONTENT state
		}
		else {
			currentState = State.ERROR;
			throw new SolonCodeSDKException("Unexpected message type: " + message.getClass().getSimpleName());
		}
	}

	private void handleUnexpectedMessage(Message message) throws SolonCodeSDKException {
		logger.warn("Received message after stream completion: {}", message.getClass().getSimpleName());
		// Could be additional metadata or late messages - log but don't fail
	}

	private void handleErrorState(Message message) throws SolonCodeSDKException {
		logger.error("Received message in error state: {}", message.getClass().getSimpleName());
		throw new SolonCodeSDKException("Stream is in error state, cannot process messages");
	}

	private void validateResultMessage(ResultMessage resultMsg) throws SolonCodeSDKException {
		// Validate session consistency
		if (sessionId != null && !sessionId.equals(resultMsg.sessionId())) {
			throw new SolonCodeSDKException(
					"Session ID mismatch: expected " + sessionId + ", got " + resultMsg.sessionId());
		}

		// Validate we received actual content
		if (!hasAssistantResponse && !resultMsg.isError()) {
			logger.warn("Completed stream without assistant response (may be empty result)");
		}

		// Validate turn count if specified
		if (resultMsg.numTurns() != expectedTurns) {
			logger.debug("Turn count mismatch: expected {}, got {}", expectedTurns, resultMsg.numTurns());
		}

		// Log completion details
		logger.info("Stream completed: {} ms duration, {} messages, session: {}", resultMsg.durationMs(),
				receivedMessages.size(), resultMsg.sessionId());
	}

	private String extractSessionId(SystemMessage systemMsg) {
		// Extract session ID from system message data for validation
		if (systemMsg.data() != null && systemMsg.data().containsKey("session_id")) {
			return systemMsg.data().get("session_id").toString();
		}
		return null;
	}

	/**
	 * Checks if the stream is complete and valid.
	 * @return true if stream completed successfully
	 */
	public boolean isComplete() {
		return currentState == State.COMPLETED;
	}

	/**
	 * Checks if the stream is in an error state.
	 * @return true if stream encountered errors
	 */
	public boolean isError() {
		return currentState == State.ERROR;
	}

	/**
	 * Gets the current state of the streaming flow.
	 * @return current state
	 */
	public State getCurrentState() {
		return currentState;
	}

	/**
	 * Gets all messages received so far.
	 * @return list of received messages
	 */
	public List<Message> getReceivedMessages() {
		return new ArrayList<>(receivedMessages);
	}

	/**
	 * Gets streaming statistics and diagnostics.
	 * @return streaming statistics
	 */
	public StreamingStats getStats() {
		Duration totalDuration = Duration.between(streamStartTime, Instant.now());
		Duration timeSinceLastMessage = Duration.between(lastMessageTime, Instant.now());

		return new StreamingStats(currentState, receivedMessages.size(), totalDuration, timeSinceLastMessage, sessionId,
				hasAssistantResponse);
	}

	/**
	 * Validates stream completion and returns summary.
	 * @return stream completion summary
	 * @throws SolonCodeSDKException if stream is not properly completed
	 */
	public StreamCompletionSummary validateCompletion() throws SolonCodeSDKException {
		if (currentState == State.ERROR) {
			throw new SolonCodeSDKException("Stream ended in error state");
		}

		if (currentState != State.COMPLETED) {
			throw new SolonCodeSDKException("Stream incomplete: " + currentState);
		}

		return new StreamCompletionSummary(receivedMessages.size(), Duration.between(streamStartTime, lastMessageTime),
				sessionId, hasAssistantResponse);
	}

	/**
	 * Statistics about the streaming session.
	 */
	public static final class StreamingStats {

		private final State currentState;

		private final int messageCount;

		private final Duration totalDuration;

		private final Duration timeSinceLastMessage;

		private final String sessionId;

		private final boolean hasAssistantResponse;

		public StreamingStats(State currentState, int messageCount, Duration totalDuration,
				Duration timeSinceLastMessage, String sessionId, boolean hasAssistantResponse) {
			this.currentState = currentState;
			this.messageCount = messageCount;
			this.totalDuration = totalDuration;
			this.timeSinceLastMessage = timeSinceLastMessage;
			this.sessionId = sessionId;
			this.hasAssistantResponse = hasAssistantResponse;
		}

		public State currentState() {
			return currentState;
		}

		public int messageCount() {
			return messageCount;
		}

		public Duration totalDuration() {
			return totalDuration;
		}

		public Duration timeSinceLastMessage() {
			return timeSinceLastMessage;
		}

		public String sessionId() {
			return sessionId;
		}

		public boolean hasAssistantResponse() {
			return hasAssistantResponse;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof StreamingStats)) {
				return false;
			}
			StreamingStats that = (StreamingStats) o;
			return messageCount == that.messageCount && hasAssistantResponse == that.hasAssistantResponse
					&& currentState == that.currentState && Objects.equals(totalDuration, that.totalDuration)
					&& Objects.equals(timeSinceLastMessage, that.timeSinceLastMessage)
					&& Objects.equals(sessionId, that.sessionId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(currentState, messageCount, totalDuration, timeSinceLastMessage, sessionId,
					hasAssistantResponse);
		}

		@Override
		public String toString() {
			return "StreamingStats[currentState=" + currentState + ", messageCount=" + messageCount
					+ ", totalDuration=" + totalDuration + ", timeSinceLastMessage=" + timeSinceLastMessage
					+ ", sessionId=" + sessionId + ", hasAssistantResponse=" + hasAssistantResponse + "]";
		}
	}

	/**
	 * Summary of a completed streaming session.
	 */
	public static final class StreamCompletionSummary {

		private final int totalMessages;

		private final Duration streamDuration;

		private final String sessionId;

		private final boolean hasAssistantResponse;

		public StreamCompletionSummary(int totalMessages, Duration streamDuration, String sessionId,
				boolean hasAssistantResponse) {
			this.totalMessages = totalMessages;
			this.streamDuration = streamDuration;
			this.sessionId = sessionId;
			this.hasAssistantResponse = hasAssistantResponse;
		}

		public int totalMessages() {
			return totalMessages;
		}

		public Duration streamDuration() {
			return streamDuration;
		}

		public String sessionId() {
			return sessionId;
		}

		public boolean hasAssistantResponse() {
			return hasAssistantResponse;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof StreamCompletionSummary)) {
				return false;
			}
			StreamCompletionSummary that = (StreamCompletionSummary) o;
			return totalMessages == that.totalMessages && hasAssistantResponse == that.hasAssistantResponse
					&& Objects.equals(streamDuration, that.streamDuration) && Objects.equals(sessionId, that.sessionId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(totalMessages, streamDuration, sessionId, hasAssistantResponse);
		}

		@Override
		public String toString() {
			return "StreamCompletionSummary[totalMessages=" + totalMessages + ", streamDuration=" + streamDuration
					+ ", sessionId=" + sessionId + ", hasAssistantResponse=" + hasAssistantResponse + "]";
		}
	}

}
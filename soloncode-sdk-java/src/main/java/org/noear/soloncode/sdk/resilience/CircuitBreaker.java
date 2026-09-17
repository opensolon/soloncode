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

package org.noear.soloncode.sdk.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Circuit breaker implementation for SolonCode SDK operations. Prevents cascade failures by
 * temporarily failing fast when error rate is high.
 */
public class CircuitBreaker {

	private static final Logger logger = LoggerFactory.getLogger(CircuitBreaker.class);

	/**
	 * Circuit breaker state enumeration.
	 */
	public enum State {

		/** Normal operation - requests are allowed through. */
		CLOSED, // Normal operation
		/** Failing fast - requests are rejected immediately. */
		OPEN, // Failing fast
		/** Testing if service recovered - allowing limited requests. */
		HALF_OPEN // Testing if service recovered

	}

	private final String name;

	private final int failureThreshold;

	private final Duration recoveryTimeout;

	private final Duration slidingWindowDuration;

	private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

	private final AtomicInteger failureCount = new AtomicInteger(0);

	private final AtomicInteger successCount = new AtomicInteger(0);

	private final AtomicLong lastFailureTime = new AtomicLong(0);

	private final AtomicLong lastSuccessTime = new AtomicLong(0);

	private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis());

	/**
	 * Constructor for CircuitBreaker.
	 * @param name the circuit breaker name
	 * @param failureThreshold the failure threshold to trigger open state
	 * @param recoveryTimeout the timeout before attempting recovery
	 * @param slidingWindowDuration the sliding window duration for failure tracking
	 */
	public CircuitBreaker(String name, int failureThreshold, Duration recoveryTimeout, Duration slidingWindowDuration) {
		this.name = name;
		this.failureThreshold = failureThreshold;
		this.recoveryTimeout = recoveryTimeout;
		this.slidingWindowDuration = slidingWindowDuration;
	}

	/**
	 * Creates a default circuit breaker for SolonCode CLI operations.
	 * @return a new CircuitBreaker with default settings
	 */
	public static CircuitBreaker defaultSolonCodeBreaker() {
		return new CircuitBreaker("soloncode-cli", 5, // 5 failures trigger open
				Duration.ofSeconds(30), // 30 second recovery timeout
				Duration.ofMinutes(2) // 2 minute sliding window
		);
	}

	/**
	 * Creates a sensitive circuit breaker for critical operations.
	 * @param name the circuit breaker name
	 * @return a new sensitive CircuitBreaker
	 */
	public static CircuitBreaker sensitive(String name) {
		return new CircuitBreaker(name, 3, // 3 failures trigger open
				Duration.ofSeconds(60), // 1 minute recovery timeout
				Duration.ofMinutes(1) // 1 minute sliding window
		);
	}

	/**
	 * Creates a tolerant circuit breaker for background operations.
	 * @param name the circuit breaker name
	 * @return a new tolerant CircuitBreaker
	 */
	public static CircuitBreaker tolerant(String name) {
		return new CircuitBreaker(name, 10, // 10 failures trigger open
				Duration.ofSeconds(15), // 15 second recovery timeout
				Duration.ofMinutes(5) // 5 minute sliding window
		);
	}

	/**
	 * Executes a supplier with circuit breaker protection.
	 * @param <T> the return type
	 * @param operation the operation to execute
	 * @return the operation result
	 * @throws CircuitBreakerOpenException if circuit breaker is open
	 */
	public <T> T execute(Supplier<T> operation) throws CircuitBreakerOpenException {
		State currentState = checkAndUpdateState();

		if (currentState == State.OPEN) {
			throw new CircuitBreakerOpenException("Circuit breaker '" + name + "' is OPEN - failing fast");
		}

		try {
			T result = operation.get();
			onSuccess();
			return result;
		}
		catch (Exception e) {
			onFailure();
			throw e;
		}
	}

	/**
	 * Gets the current state of the circuit breaker.
	 * @return the current state
	 */
	public State getState() {
		return checkAndUpdateState();
	}

	/**
	 * Gets circuit breaker metrics.
	 * @return the circuit breaker metrics
	 */
	public CircuitBreakerMetrics getMetrics() {
		return new CircuitBreakerMetrics(name, state.get(), failureCount.get(), successCount.get(),
				calculateFailureRate(), Instant.ofEpochMilli(lastFailureTime.get()),
				Instant.ofEpochMilli(lastSuccessTime.get()));
	}

	/**
	 * Manually resets the circuit breaker to CLOSED state.
	 */
	public void reset() {
		logger.info("Circuit breaker '{}' manually reset to CLOSED", name);
		state.set(State.CLOSED);
		failureCount.set(0);
		successCount.set(0);
		windowStartTime.set(System.currentTimeMillis());
	}

	private State checkAndUpdateState() {
		State currentState = state.get();
		long now = System.currentTimeMillis();

		// Check if sliding window should reset
		if (now - windowStartTime.get() > slidingWindowDuration.toMillis()) {
			resetWindow(now);
		}

		switch (currentState) {
			case CLOSED:
				if (failureCount.get() >= failureThreshold) {
					if (state.compareAndSet(State.CLOSED, State.OPEN)) {
						lastFailureTime.set(now);
						logger.warn("Circuit breaker '{}' opened - failure threshold {} reached", name,
								failureThreshold);
					}
					return State.OPEN;
				}
				break;

			case OPEN:
				if (now - lastFailureTime.get() > recoveryTimeout.toMillis()) {
					if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
						logger.info("Circuit breaker '{}' moved to HALF_OPEN - testing recovery", name);
					}
					return State.HALF_OPEN;
				}
				break;

			case HALF_OPEN:
				// Stay in HALF_OPEN until next success or failure
				break;
		}

		return currentState;
	}

	private void onSuccess() {
		long now = System.currentTimeMillis();
		successCount.incrementAndGet();
		lastSuccessTime.set(now);

		State currentState = state.get();
		if (currentState == State.HALF_OPEN) {
			if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
				logger.info("Circuit breaker '{}' closed - recovery successful", name);
				failureCount.set(0); // Reset failure count on recovery
			}
		}
	}

	private void onFailure() {
		long now = System.currentTimeMillis();
		failureCount.incrementAndGet();
		lastFailureTime.set(now);

		State currentState = state.get();
		if (currentState == State.HALF_OPEN) {
			if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
				logger.warn("Circuit breaker '{}' reopened - recovery failed", name);
			}
		}
	}

	private void resetWindow(long now) {
		windowStartTime.set(now);
		int oldFailures = failureCount.get();
		int oldSuccesses = successCount.get();

		// Exponential decay instead of hard reset
		failureCount.set(oldFailures / 2);
		successCount.set(oldSuccesses / 2);

		logger.debug("Circuit breaker '{}' sliding window reset - failures: {} -> {}, successes: {} -> {}", name,
				oldFailures, failureCount.get(), oldSuccesses, successCount.get());
	}

	private double calculateFailureRate() {
		int total = failureCount.get() + successCount.get();
		return total > 0 ? (double) failureCount.get() / total : 0.0;
	}

	/**
	 * Exception thrown when circuit breaker is in OPEN state.
	 */
	public static class CircuitBreakerOpenException extends RuntimeException {

		/**
		 * Constructor for CircuitBreakerOpenException.
		 * @param message the exception message
		 */
		public CircuitBreakerOpenException(String message) {
			super(message);
		}

	}

	/**
	 * Metrics snapshot for circuit breaker monitoring.
	 */
	public static final class CircuitBreakerMetrics {

		private final String name;

		private final State state;

		private final int failureCount;

		private final int successCount;

		private final double failureRate;

		private final Instant lastFailureTime;

		private final Instant lastSuccessTime;

		public CircuitBreakerMetrics(String name, State state, int failureCount, int successCount, double failureRate,
				Instant lastFailureTime, Instant lastSuccessTime) {
			this.name = name;
			this.state = state;
			this.failureCount = failureCount;
			this.successCount = successCount;
			this.failureRate = failureRate;
			this.lastFailureTime = lastFailureTime;
			this.lastSuccessTime = lastSuccessTime;
		}

		public String name() {
			return name;
		}

		public State state() {
			return state;
		}

		public int failureCount() {
			return failureCount;
		}

		public int successCount() {
			return successCount;
		}

		public double failureRate() {
			return failureRate;
		}

		public Instant lastFailureTime() {
			return lastFailureTime;
		}

		public Instant lastSuccessTime() {
			return lastSuccessTime;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof CircuitBreakerMetrics)) {
				return false;
			}
			CircuitBreakerMetrics that = (CircuitBreakerMetrics) o;
			return failureCount == that.failureCount && successCount == that.successCount
					&& Double.compare(that.failureRate, failureRate) == 0 && Objects.equals(name, that.name)
					&& state == that.state && Objects.equals(lastFailureTime, that.lastFailureTime)
					&& Objects.equals(lastSuccessTime, that.lastSuccessTime);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, state, failureCount, successCount, failureRate, lastFailureTime, lastSuccessTime);
		}

		@Override
		public String toString() {
			return "CircuitBreakerMetrics[name=" + name + ", state=" + state + ", failureCount=" + failureCount
					+ ", successCount=" + successCount + ", failureRate=" + failureRate + ", lastFailureTime="
					+ lastFailureTime + ", lastSuccessTime=" + lastSuccessTime + "]";
		}

		/**
		 * Checks if the circuit breaker is healthy.
		 * @return true if the circuit breaker is in a healthy state
		 */
		public boolean isHealthy() {
			return state == State.CLOSED && failureRate < 0.5;
		}

		/**
		 * Gets a status description of the circuit breaker.
		 * @return a formatted status description
		 */
		public String getStatusDescription() {
			return String.format("CircuitBreaker[%s] state=%s, failures=%d, successes=%d, failureRate=%.2f%%", name,
					state, failureCount, successCount, failureRate * 100);
		}
	}

}
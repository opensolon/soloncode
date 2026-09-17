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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Comprehensive resilience manager for SolonCode SDK operations. Combines retry strategies,
 * circuit breakers, and timeouts.
 */
public class ResilienceManager {

	private static final Logger logger = LoggerFactory.getLogger(ResilienceManager.class);

	private final ConcurrentMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

	private final RetryConfiguration defaultRetryConfig;

	private final Duration defaultTimeout;

	public ResilienceManager() {
		this(RetryConfiguration.defaultNetwork(), Duration.ofMinutes(2));
	}

	public ResilienceManager(RetryConfiguration defaultRetryConfig, Duration defaultTimeout) {
		this.defaultRetryConfig = defaultRetryConfig;
		this.defaultTimeout = defaultTimeout;

		// Create default circuit breakers
		circuitBreakers.put("soloncode-cli", CircuitBreaker.defaultSolonCodeBreaker());
		circuitBreakers.put("streaming", CircuitBreaker.tolerant("streaming"));
		circuitBreakers.put("query", CircuitBreaker.sensitive("query"));
	}

	/**
	 * Executes a synchronous operation with full resilience (retry + circuit breaker).
	 */
	public <T> T executeResilient(String operationName, Supplier<T> operation) {
		return executeResilient(operationName, operation, defaultRetryConfig);
	}

	/**
	 * Executes a synchronous operation with custom retry configuration.
	 */
	public <T> T executeResilient(String operationName, Supplier<T> operation, RetryConfiguration retryConfig) {
		CircuitBreaker circuitBreaker = getOrCreateCircuitBreaker(operationName);

		return executeWithRetry(() -> circuitBreaker.execute(operation), retryConfig);
	}

	/**
	 * Executes a reactive operation with full resilience.
	 */
	public <T> Mono<T> executeResilientMono(String operationName, Supplier<Mono<T>> operation) {
		return executeResilientMono(operationName, operation, defaultRetryConfig);
	}

	/**
	 * Executes a reactive operation with custom retry configuration.
	 */
	public <T> Mono<T> executeResilientMono(String operationName, Supplier<Mono<T>> operation,
			RetryConfiguration retryConfig) {
		CircuitBreaker circuitBreaker = getOrCreateCircuitBreaker(operationName);

		return Mono.fromSupplier(() -> operation.get())
			.flatMap(mono -> mono)
			.doOnSubscribe(subscription -> checkCircuitBreaker(circuitBreaker))
			.doOnSuccess(result -> circuitBreaker.getMetrics()) // Touch circuit breaker
																// for success
			.doOnError(error -> logger.warn("Operation '{}' failed", operationName, error))
			.retryWhen(createReactiveRetry(retryConfig))
			.timeout(defaultTimeout)
			.doOnSuccess(result -> logger.debug("Operation '{}' completed successfully", operationName));
	}

	/**
	 * Executes a reactive streaming operation with resilience.
	 */
	public <T> Flux<T> executeResilientFlux(String operationName, Supplier<Flux<T>> operation) {
		return executeResilientFlux(operationName, operation, defaultRetryConfig);
	}

	/**
	 * Executes a reactive streaming operation with custom retry configuration.
	 */
	public <T> Flux<T> executeResilientFlux(String operationName, Supplier<Flux<T>> operation,
			RetryConfiguration retryConfig) {
		CircuitBreaker circuitBreaker = getOrCreateCircuitBreaker(operationName);

		return Flux.defer(() -> {
			checkCircuitBreaker(circuitBreaker);
			return operation.get();
		})
			.doOnSubscribe(subscription -> logger.debug("Starting resilient flux operation '{}'", operationName))
			.doOnComplete(() -> logger.debug("Flux operation '{}' completed", operationName))
			.doOnError(error -> logger.warn("Flux operation '{}' failed", operationName, error))
			.retryWhen(createReactiveRetry(retryConfig))
			.timeout(defaultTimeout);
	}

	/**
	 * Gets circuit breaker metrics for monitoring.
	 */
	public CircuitBreaker.CircuitBreakerMetrics getCircuitBreakerMetrics(String operationName) {
		CircuitBreaker breaker = circuitBreakers.get(operationName);
		return breaker != null ? breaker.getMetrics() : null;
	}

	/**
	 * Gets all circuit breaker metrics.
	 */
	public ConcurrentMap<String, CircuitBreaker.CircuitBreakerMetrics> getAllMetrics() {
		ConcurrentMap<String, CircuitBreaker.CircuitBreakerMetrics> metrics = new ConcurrentHashMap<>();
		circuitBreakers.forEach((name, breaker) -> metrics.put(name, breaker.getMetrics()));
		return metrics;
	}

	/**
	 * Manually resets a circuit breaker.
	 */
	public void resetCircuitBreaker(String operationName) {
		CircuitBreaker breaker = circuitBreakers.get(operationName);
		if (breaker != null) {
			breaker.reset();
		}
	}

	/**
	 * Resets all circuit breakers.
	 */
	public void resetAllCircuitBreakers() {
		circuitBreakers.values().forEach(CircuitBreaker::reset);
		logger.info("All circuit breakers reset");
	}

	/**
	 * Creates a custom circuit breaker for specific operations.
	 */
	public CircuitBreaker createCircuitBreaker(String name, int failureThreshold, Duration recoveryTimeout,
			Duration slidingWindow) {
		CircuitBreaker breaker = new CircuitBreaker(name, failureThreshold, recoveryTimeout, slidingWindow);
		circuitBreakers.put(name, breaker);
		return breaker;
	}

	private CircuitBreaker getOrCreateCircuitBreaker(String operationName) {
		return circuitBreakers.computeIfAbsent(operationName, name -> CircuitBreaker.defaultSolonCodeBreaker());
	}

	private void checkCircuitBreaker(CircuitBreaker circuitBreaker) {
		if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
			throw new CircuitBreaker.CircuitBreakerOpenException("Circuit breaker is OPEN for operation");
		}
	}

	private <T> T executeWithRetry(Supplier<T> operation, RetryConfiguration retryConfig) {
		Exception lastException = null;

		for (int attempt = 1; attempt <= retryConfig.maxAttempts(); attempt++) {
			try {
				return operation.get();
			}
			catch (Exception e) {
				lastException = e;

				if (!retryConfig.shouldRetry(e, attempt)) {
					break;
				}

				Duration delay = retryConfig.calculateDelay(attempt);
				logger.debug("Attempt {} failed, retrying in {}ms", attempt, delay.toMillis(), e);

				try {
					Thread.sleep(delay.toMillis());
				}
				catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new RuntimeException("Retry interrupted", ie);
				}
			}
		}

		if (lastException instanceof RuntimeException) {
			throw (RuntimeException) lastException;
		}
		else {
			throw new RuntimeException("Operation failed after retries", lastException);
		}
	}

	private Retry createReactiveRetry(RetryConfiguration retryConfig) {
		return Retry.backoff(retryConfig.maxAttempts() - 1, retryConfig.initialDelay())
			.maxBackoff(retryConfig.maxDelay())
			.filter(retryConfig.retryablePredicate())
			.doBeforeRetry(
					retrySignal -> logger.debug("Retrying operation, attempt {}", retrySignal.totalRetries() + 1))
			.onRetryExhaustedThrow(
					(retryBackoffSpec, retrySignal) -> new RuntimeException("Retry exhausted", retrySignal.failure()));
	}

	/**
	 * Builder for creating custom resilience managers.
	 */
	public static class Builder {

		private RetryConfiguration retryConfig = RetryConfiguration.defaultNetwork();

		private Duration timeout = Duration.ofMinutes(2);

		public Builder retryConfig(RetryConfiguration retryConfig) {
			this.retryConfig = retryConfig;
			return this;
		}

		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		public ResilienceManager build() {
			return new ResilienceManager(retryConfig, timeout);
		}

	}

	public static Builder builder() {
		return new Builder();
	}

}
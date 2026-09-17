package org.noear.soloncode.sdk;

import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.permission.ToolPermissionCallback;
import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.QueryResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

/**
 * Unified request-oriented facade. It owns one internal session implementation and
 * exposes execution mode only on {@link Request}: {@code call()} or {@code stream()}.
 */
final class DefaultSolonCodeClient implements SolonCodeClient {
    private static final long NO_DEMAND_PARK_NANOS = 1_000_000L;

    private SolonCodeSession delegate;
    private final Function<QueryOptions, SolonCodeSession> sessionFactory;
    private final Object turnLock = new Object();
    private final AtomicBoolean activeTurn = new AtomicBoolean(false);
    private boolean started;
    private boolean closed;

    DefaultSolonCodeClient(SolonCodeSession delegate) {
        this.delegate = delegate;
        this.sessionFactory = ignored -> delegate;
    }

    DefaultSolonCodeClient(Function<QueryOptions, SolonCodeSession> sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    private SolonCodeSession session(QueryOptions firstTurnOptions) {
        synchronized (turnLock) {
            if (delegate == null) {
                if (closed) {
                    throw new SolonCodeSDKException("SolonCodeClient is closed");
                }
                delegate = sessionFactory.apply(firstTurnOptions);
            }
            return delegate;
        }
    }

    @Override
    public SolonCodeClient.Request prompt(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt must not be empty");
        }
        return new RequestImpl(prompt);
    }

    @Override
    public void interrupt() throws SolonCodeSDKException {
        session(null).interrupt();
    }

    @Override
    public void setModel(String model) throws SolonCodeSDKException {
        session(null).setModel(model);
    }

    @Override
    public void setPermissionMode(String mode) throws SolonCodeSDKException {
        session(null).setPermissionMode(mode);
    }

    @Override
    public CLIOptions getOptions() {
        return session(null).getOptions();
    }

    @Override
    public String getCurrentModel() {
        return session(null).getCurrentModel();
    }

    @Override
    public String getCurrentPermissionMode() {
        return session(null).getCurrentPermissionMode();
    }

    @Override
    public java.util.Map<String, Object> getServerInfo() {
        return session(null).getServerInfo();
    }

    @Override
    public boolean isConnected() {
        return delegate != null && delegate.isConnected();
    }

    @Override
    public void setToolPermissionCallback(ToolPermissionCallback callback) {
        session(null).setToolPermissionCallback(callback);
    }

    @Override
    public ToolPermissionCallback getToolPermissionCallback() {
        return session(null).getToolPermissionCallback();
    }

    @Override
    public void close() {
        synchronized (turnLock) {
            if (closed) {
                return;
            }
            closed = true;
            activeTurn.set(false);
            if (delegate != null) {
                delegate.close();
            }
        }
    }

    private void start(String prompt) {
        SolonCodeSession current = session(null);
        if (started) {
            current.query(prompt);
        }
        else {
            current.connect(prompt);
            started = true;
        }
    }

    private SolonCodeSession beginTurn(String prompt, QueryOptions requestOptions) {
        synchronized (turnLock) {
            if (closed) {
                throw new SolonCodeSDKException("SolonCodeClient is closed");
            }
            if (!activeTurn.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "A response is still active; consume or cancel it before starting another turn");
            }
            try {
                applyRequestOptions(requestOptions);
                start(prompt);
                return delegate;
            }
            catch (Throwable e) {
                activeTurn.set(false);
                throw e;
            }
        }
    }

    private void endTurn() {
        activeTurn.set(false);
    }

    private void applyRequestOptions(QueryOptions requestOptions) {
        if (requestOptions == null) {
            return;
        }
        // Model and permission mode are the only options that can be changed on an
        // already-created persistent session. Transport, workspace and process-level
        // options remain client-scoped; silently ignoring them would be misleading.
        CLIOptions base = session(requestOptions).getOptions();
        CLIOptions requested = requestOptions.toCLIOptions();
        if (!started) {
            return;
        }
        if (requested.model() != null && !requested.model().equals(base == null ? null : base.model())) {
            session(requestOptions).setModel(requested.model());
        }
        if (requestOptions.systemPrompt() != null
                || requestOptions.appendSystemPrompt() != null
                || !requestOptions.allowedTools().isEmpty()
                || !requestOptions.disallowedTools().isEmpty()
                || requestOptions.maxTurns() != null
                || requestOptions.maxBudgetUsd() != null
                || requestOptions.maxTokens() != null
                || requestOptions.maxThinkingTokens() != null
                || requestOptions.fallbackModel() != null
                || requestOptions.jsonSchema() != null
                || requestOptions.sessionId() != null
                || requestOptions.bare()) {
            throw new IllegalArgumentException(
                    "Only model can be overridden per request; configure process and transport options on the client builder");
        }
    }

    private final class RequestImpl implements SolonCodeClient.Request {
        private final String prompt;
        private final AtomicBoolean executed = new AtomicBoolean();
        private QueryOptions requestOptions;

        private RequestImpl(String prompt) {
            this.prompt = prompt;
        }

        @Override
        public SolonCodeClient.Request options(QueryOptions options) {
            if (executed.get()) {
                throw new IllegalStateException("request options must be set before execution");
            }
            this.requestOptions = options == null ? QueryOptions.defaults() : options;
            return this;
        }

        private void ensureSingleUse() {
            if (!executed.compareAndSet(false, true)) {
                throw new IllegalStateException("request can only be executed once");
            }
        }

        @Override
        public QueryResult call() throws SolonCodeSDKException {
            ensureSingleUse();
            SolonCodeSession current = beginTurn(prompt, requestOptions);
            try {
                List<Message> messages = new ArrayList<>();
                Iterator<ParsedMessage> it = current.receiveResponse();
                while (it.hasNext()) {
                    ParsedMessage parsed = it.next();
                    if (parsed.isRegularMessage()) {
                        messages.add(parsed.asMessage());
                    }
                }
                CLIOptions effective = current.getOptions();
                return Query.buildQueryResult(messages,
                        effective == null ? CLIOptions.builder().build() : effective);
            }
            finally {
                endTurn();
            }
        }

        @Override
        public Flux<Message> stream() {
            if (executed.get()) {
                return Flux.error(new IllegalStateException("request can only be executed once"));
            }
            return Flux.<Message>create(sink -> {
                AtomicBoolean cancelled = new AtomicBoolean(false);
                AtomicReference<SolonCodeSession> currentRef = new AtomicReference<>();
                AtomicReference<Thread> workerRef = new AtomicReference<>();

                sink.onCancel(() -> {
                    cancelled.set(true);
                    Thread worker = workerRef.get();
                    if (worker != null) {
                        LockSupport.unpark(worker);
                    }
                    SolonCodeSession current = currentRef.get();
                    if (current != null) {
                        // HTTP interrupt 等待网络响应，不能阻塞 Reactor 的取消线程。
                        Schedulers.boundedElastic().schedule(() -> {
                            try {
                                current.interrupt();
                            }
                            catch (Throwable ignored) {
                                // Cancellation must not replace the downstream cancel signal.
                            }
                        });
                    }
                });

                try {
                    workerRef.set(Thread.currentThread());
                    ensureSingleUse();
                    SolonCodeSession current = beginTurn(prompt, requestOptions);
                    currentRef.set(current);
                    if (cancelled.get() || sink.isCancelled()) {
                        try {
                            current.interrupt();
                        }
                        catch (Throwable ignored) {
                            // Best-effort cancellation after a race with turn startup.
                        }
                        return;
                    }

                    Iterator<ParsedMessage> it = current.receiveResponse();
                    while (!cancelled.get() && !sink.isCancelled()) {
                        // Probe at most one source element ahead so completion can be
                        // observed without additional downstream demand. The iterator's
                        // own queue remains bounded, so this is a one-element prefetch.
                        if (!it.hasNext()) {
                            sink.complete();
                            break;
                        }
                        while (!cancelled.get() && !sink.isCancelled()
                                && sink.requestedFromDownstream() <= 0) {
                            LockSupport.parkNanos(NO_DEMAND_PARK_NANOS);
                        }
                        if (cancelled.get() || sink.isCancelled()) {
                            break;
                        }
                        ParsedMessage parsed = it.next();
                        if (parsed.isRegularMessage()) {
                            sink.next(parsed.asMessage());
                        }
                    }
                }
                catch (Throwable e) {
                    if (!cancelled.get() && !sink.isCancelled()) {
                        sink.error(e);
                    }
                }
                finally {
                    workerRef.set(null);
                    endTurn();
                }
            }, FluxSink.OverflowStrategy.ERROR).subscribeOn(Schedulers.boundedElastic(), false);
        }

        @Override
        public reactor.core.publisher.Mono<QueryResult> streamResult() {
            return stream().collectList().map(messages -> {
                CLIOptions effective = session(requestOptions).getOptions();
                return Query.buildQueryResult(messages,
                        effective == null ? CLIOptions.builder().build() : effective);
            });
        }
    }
}

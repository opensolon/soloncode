package org.noear.soloncode.sdk;

import org.junit.jupiter.api.Test;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.types.AssistantMessage;
import org.noear.soloncode.sdk.types.ContentBlock;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.TextBlock;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;

class UnifiedSolonCodeClientTest {
    private static ParsedMessage assistant(String text) {
        return ParsedMessage.RegularMessage.of(
                AssistantMessage.of(Arrays.<ContentBlock>asList(TextBlock.of(text))));
    }

    private static ParsedMessage result() {
        return ParsedMessage.RegularMessage.of(
                ResultMessage.builder().subtype("success").result("ok").build());
    }

    @Test
    void callAndStreamShareOneMultiTurnClient() {
        SolonCodeSession delegate = mock(SolonCodeSession.class);
        when(delegate.receiveResponse())
                .thenReturn(Arrays.asList(assistant("one"), result()).iterator())
                .thenReturn(Arrays.asList(assistant("two"), result()).iterator());

        SolonCodeClient client = new DefaultSolonCodeClient(delegate);
        assertThat(client.prompt("first").call().messages()).hasSize(2);
        StepVerifier.create(client.prompt("second").stream())
                .expectNextCount(2)
                .verifyComplete();

        verify(delegate).connect("first");
        verify(delegate).query("second");
        client.close();
        verify(delegate).close();
    }

    @Test
    void streamCancellationInterruptsRunningTurn() {
        SolonCodeSession delegate = mock(SolonCodeSession.class);
        AtomicBoolean interrupted = new AtomicBoolean();
        doAnswer(invocation -> {
            interrupted.set(true);
            return null;
        }).when(delegate).interrupt();
        when(delegate.receiveResponse()).thenReturn(new Iterator<ParsedMessage>() {
            @Override
            public boolean hasNext() {
                while (!interrupted.get()) {
                    try {
                        Thread.sleep(10L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return false;
            }

            @Override
            public ParsedMessage next() {
                throw new NoSuchElementException();
            }
        });

        SolonCodeClient client = new DefaultSolonCodeClient(delegate);
        StepVerifier.create(client.prompt("long task").stream())
                .thenAwait(java.time.Duration.ofMillis(200L))
                .thenCancel()
                .verify();

        verify(delegate, timeout(1000)).interrupt();
        client.close();
    }
    @Test
    void requestCanOnlyBeExecutedOnce() {
        SolonCodeSession delegate = mock(SolonCodeSession.class);
        when(delegate.receiveResponse()).thenReturn(Arrays.asList(result()).iterator());
        SolonCodeClient client = new DefaultSolonCodeClient(delegate);
        SolonCodeClient.Request request = client.prompt("hello");

        request.call();
        assertThatThrownBy(request::call)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only be executed once");
    }

    @Test
    void firstTurnOptionsAreAppliedBeforeSessionCreation() {
        AtomicReference<QueryOptions> captured = new AtomicReference<>();
        SolonCodeSession delegate = mock(SolonCodeSession.class);
        when(delegate.getOptions()).thenReturn(org.noear.soloncode.sdk.transport.CLIOptions.builder()
                .model("request-model").systemPrompt("request-system").build());
        when(delegate.receiveResponse()).thenReturn(Arrays.asList(result()).iterator());
        SolonCodeClient client = new DefaultSolonCodeClient(options -> {
            captured.set(options);
            return delegate;
        });
        QueryOptions options = QueryOptions.builder()
                .model("request-model")
                .systemPrompt("request-system")
                .maxTurns(3)
                .build();

        client.prompt("hello").options(options).call();

        assertThat(captured.get()).isSameAs(options);
        verify(delegate).connect("hello");
    }

    @Test
    void streamHonorsDownstreamDemandBeforePullingNextMessage() throws Exception {
        SolonCodeSession delegate = mock(SolonCodeSession.class);
        CountDownLatch firstPulled = new CountDownLatch(1);
        CountDownLatch secondDelivered = new CountDownLatch(1);
        Iterator<ParsedMessage> iterator = new Iterator<ParsedMessage>() {
            private int index;

            @Override
            public boolean hasNext() {
                if (index == 0) {
                    firstPulled.countDown();
                }
                else if (index == 1) {
                    // hasNext may prefetch one item so completion can be observed without demand.
                }
                return index < 2;
            }

            @Override
            public ParsedMessage next() {
                if (index == 1) {
                    secondDelivered.countDown();
                }
                return index++ == 0 ? assistant("one") : result();
            }
        };
        when(delegate.receiveResponse()).thenReturn(iterator);
        SolonCodeClient client = new DefaultSolonCodeClient(delegate);

        StepVerifier.create(client.prompt("slow").stream(), 0)
                .thenRequest(1)
                .assertNext(message -> assertThat(message).isInstanceOf(AssistantMessage.class))
                .then(() -> {
                    assertThat(firstPulled.getCount()).isZero();
                    assertThat(secondDelivered.getCount()).isEqualTo(1L);
                })
                .thenRequest(1)
                .assertNext(message -> assertThat(message).isInstanceOf(ResultMessage.class))
                .verifyComplete();

        assertThat(secondDelivered.await(1, TimeUnit.SECONDS)).isTrue();
        client.close();
    }

    @Test
    void overlappingCallsAreRejectedInsteadOfSerializedBehindTheFirstTurn() throws Exception {
        SolonCodeSession delegate = mock(SolonCodeSession.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(delegate.receiveResponse()).thenReturn(new Iterator<ParsedMessage>() {
            @Override
            public boolean hasNext() {
                entered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }

            @Override
            public ParsedMessage next() {
                throw new NoSuchElementException();
            }
        });
        SolonCodeClient client = new DefaultSolonCodeClient(delegate);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                client.prompt("first").call();
            }
            catch (Throwable e) {
                firstFailure.set(e);
            }
        });
        first.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> client.prompt("second").call())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("response is still active");

        release.countDown();
        first.join(1000L);
        assertThat(firstFailure.get()).isNull();
        client.close();
    }

    @Test
    void streamResultAggregatesMessagesAndTerminalMetadata() {
        SolonCodeSession delegate = mock(SolonCodeSession.class);
        when(delegate.getOptions()).thenReturn(org.noear.soloncode.sdk.transport.CLIOptions.builder()
                .model("sonnet").build());
        when(delegate.receiveResponse()).thenReturn(Arrays.asList(assistant("one"), result()).iterator());
        SolonCodeClient client = new DefaultSolonCodeClient(delegate);

        StepVerifier.create(client.prompt("hello").streamResult())
                .assertNext(queryResult -> {
                    assertThat(queryResult.messages()).hasSize(2);
                    assertThat(queryResult.text()).contains("one");
                    assertThat(queryResult.metadata().model()).isEqualTo("sonnet");
                    assertThat(queryResult.isSuccessful()).isTrue();
                })
                .verifyComplete();
    }
}

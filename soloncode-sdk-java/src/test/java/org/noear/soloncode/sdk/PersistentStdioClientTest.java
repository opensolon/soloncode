/*
 * Copyright 2025 soloncode
 * Licensed under the Apache License, Version 2.0
 */
package org.noear.soloncode.sdk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.noear.soloncode.sdk.hooks.HookRegistry;
import org.noear.soloncode.sdk.types.control.HookEvent;
import org.noear.soloncode.sdk.types.control.HookOutput;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unified-client offline regression tests for persistent {@code soloncode stream}. */
@DisabledOnOs(OS.WINDOWS)
class PersistentStdioClientTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @TempDir
    Path tempDir;

    private Path invocationLog;
    private Path stdinLog;
    private String fakeCli;

    @BeforeEach
    void setUp() throws Exception {
        invocationLog = tempDir.resolve("invocations.log");
        stdinLog = tempDir.resolve("stdin.log");
        Path script = tempDir.resolve("fake-stream-cli.sh");
        String body = "#!/bin/bash\n"
                + "printf '%s\\n' \"$1\" >> '" + invocationLog.toAbsolutePath() + "'\n"
                + "printf '%s\\n' '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"client-stream\",\"model\":\"fake\"}'\n"
                + "while IFS= read -r line; do\n"
                + "  printf '%s\\n' \"$line\" >> '" + stdinLog.toAbsolutePath() + "'\n"
                + "  if [[ \"$line\" == *'\"type\":\"control_request\"'* ]]; then\n"
                + "    id=$(printf '%s' \"$line\" | sed -n 's/.*\"request_id\":\"\\([^\"]*\\)\".*/\\1/p')\n"
                + "    printf '{\"type\":\"control_response\",\"response\":{\"subtype\":\"error\",\"request_id\":\"%s\",\"error\":\"unsupported control\"}}\\n' \"$id\"\n"
                + "    continue\n"
                + "  fi\n"
                + "  case \"$line\" in *error-only*) printf '%s\\n' '{\"type\":\"system\",\"subtype\":\"error\",\"data\":{\"message\":\"turn failed\"}}'; continue ;; *timeout*) sleep 5; continue ;; *slow*) sleep 1 ;; esac\n"
                + "  printf '%s\\n' '{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}'\n"
                + "  printf '%s\\n' '{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"client-stream\"}'\n"
                + "done\n";
        Files.write(script, body.getBytes(StandardCharsets.UTF_8));
        script.toFile().setExecutable(true);
        fakeCli = script.toAbsolutePath().toString();
    }

    private SolonCodeClient newClient() {
        return SolonCodeClient.builder()
                .workingDirectory(tempDir)
                .stdio(fakeCli)
                .timeout(TIMEOUT)
                .build();
    }

    @Test
    void callReusesOneProcessAcrossTurns() {
        try (SolonCodeClient client = newClient()) {
            assertThat(client.prompt("first").call().text()).contains("ok");
            assertThat(client.prompt("second").call().text()).contains("ok");
        }
        assertOneStreamProcessAndTwoUserFrames();
    }

    @Test
    void streamInstallsTurnConsumerBeforeFastPersistentResponse() {
        try (SolonCodeClient client = newClient()) {
            client.prompt("first").stream().blockLast(TIMEOUT);
            client.prompt("second").stream().blockLast(TIMEOUT);
        }
        assertOneStreamProcessAndTwoUserFrames();
    }

    @Test
    void reactiveCompositionCanStartTheNextTurnAfterCompletion() throws Exception {
        try (SolonCodeClient client = newClient()) {
            String second = client.prompt("first").streamResult()
                    .flatMap(ignored -> client.prompt("second").streamResult())
                    .flatMap(result -> Mono.just(result.text().orElse("")))
                    .block(TIMEOUT);
            assertThat(second).isEqualTo("ok");
            awaitStdinLines(2);
        }
    }

    @Test
    void controlRequestWaitsForTheMatchingErrorResponse() {
        try (SolonCodeClient client = newClient()) {
            client.prompt("first").call();
            assertThatThrownBy(() -> client.setModel("new-model"))
                    .hasMessageContaining("Control request failed: unsupported control");
        }
    }

    @Test
    void configuredHooksFailFastInsteadOfBeingSilentlyIgnored() {
        HookRegistry hooks = new HookRegistry();
        hooks.register(HookEvent.PRE_TOOL_USE, "Bash", input -> HookOutput.allow());
        try (SolonCodeClient client = SolonCodeClient.builder()
                .workingDirectory(tempDir).stdio(fakeCli).hookRegistry(hooks).build()) {
            assertThatThrownBy(() -> client.prompt("first").call())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("does not currently support hook");
        }
    }

    @Test
    void overlappingTurnsAreRejectedWithoutReplacingTheFirstResponse() throws Exception {
        try (SolonCodeClient client = newClient()) {
            reactor.core.Disposable first = client.prompt("slow-first").stream().subscribe();
            try {
                awaitStdinLines(1);
                assertThatThrownBy(() -> client.prompt("second").call())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("still active");
            }
            finally {
                first.dispose();
            }
        }
    }

    @Test
    void topLevelErrorTerminatesPersistentStreamWithoutResultOrEof() {
        try (SolonCodeClient client = newClient()) {
            org.noear.soloncode.sdk.types.QueryResult result = client.prompt("error-only").call();
            assertThat(result.status()).isEqualTo(org.noear.soloncode.sdk.types.ResultStatus.ERROR);
            assertThat(result.messages()).hasSize(2);
            assertThat(result.messages()).anyMatch(message ->
                    message instanceof org.noear.soloncode.sdk.types.SystemMessage
                            && "error".equalsIgnoreCase(
                            ((org.noear.soloncode.sdk.types.SystemMessage) message).subtype()));
        }
    }

    @Test
    void timeoutInterruptsAndFailsTheTurn() {
        try (SolonCodeClient client = SolonCodeClient.builder()
                .workingDirectory(tempDir).stdio(fakeCli).timeout(Duration.ofMillis(200)).build()) {
            assertThatThrownBy(() -> client.prompt("timeout").call())
                    .hasMessageContaining("Stream failed")
                    .hasRootCauseMessage("Response timed out after PT0.2S");
        }
    }

    private void awaitStdinLines(int count) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline
                && (!Files.exists(stdinLog) || Files.readAllLines(stdinLog, StandardCharsets.UTF_8).size() < count)) {
            Thread.sleep(20L);
        }
        assertThat(Files.readAllLines(stdinLog, StandardCharsets.UTF_8)).hasSizeGreaterThanOrEqualTo(count);
    }

    private void assertOneStreamProcessAndTwoUserFrames() {
        try {
            assertThat(Files.readAllLines(invocationLog, StandardCharsets.UTF_8)).containsExactly("stream");
            List<String> lines = Files.readAllLines(stdinLog, StandardCharsets.UTF_8);
            assertThat(lines).hasSize(2);
            assertThat(lines.get(0)).contains("\"type\":\"user\"").contains("first");
            assertThat(lines.get(1)).contains("\"type\":\"user\"").contains("second");
        }
        catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}

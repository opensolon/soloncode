package org.noear.solon.bot.core.subagent;

import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;

public class AgentStreamOutput {

    private static final Logger LOG = LoggerFactory.getLogger(AgentStreamOutput.class);

    // 子代理执行超时配置（单位：毫秒）
    private static final long SUBAGENT_SYNC_TIMEOUT_MS = 120_000;
    private static final long SUBAGENT_STREAM_TIMEOUT_MS = 180_000;
    private static final long FIRST_CHUNK_TIMEOUT_MS = 45_000;

    /**
     * 执行流式子代理调用
     */
    public static String executeStream(Subagent agent, String __cwd, String sessionId,
                                 Prompt prompt, ReActTrace __parentTrace, String name) {
        try {
            String promptStr = prompt.toString();
            LOG.info("[子代理] 启动异步流式执行: type={}, sessionId={}, promptLength={}",
                    name, sessionId, promptStr != null ? promptStr.length() : 0);

            final long[] firstChunkTime = {0};
            final long[] lastChunkTime = {System.currentTimeMillis()};
            final int[] chunkCount = {0};
            final StringBuilder contentBuilder = new StringBuilder();

            String result = agent.stream(__cwd, sessionId, prompt)
                    .doOnSubscribe(s -> {
                        LOG.info("[子代理] 流订阅成功: name={}, sessionId={}", name, sessionId);
                    })
                    .doOnNext(chunk -> {
                        long now = System.currentTimeMillis();
                        if (chunkCount[0] == 0) {
                            firstChunkTime[0] = now;
                            long firstChunkDelay = now - lastChunkTime[0];
                            LOG.info("[子代理] 收到首个chunk: name={}, delay={}ms, chunkType={}",
                                    name, firstChunkDelay, chunk.getClass().getSimpleName());
                        }
                        lastChunkTime[0] = now;
                        chunkCount[0]++;

                        LOG.debug("[子代理] 收到chunk: type={}, chunkType={}, total={}",
                                name, chunk.getClass().getSimpleName(), chunkCount[0]);

                        if (chunk instanceof ActionChunk) {
                            __parentTrace.getOptions().getStreamSink().next(chunk);
                        } else if (chunk instanceof ReasonChunk) {
                            __parentTrace.getOptions().getStreamSink().next(chunk);
                        }

                        if (chunk != null && chunk.hasContent()) {
                            contentBuilder.append(chunk.getContent());
                        }
                    })
                    .doOnComplete(() -> {
                        long totalDuration = System.currentTimeMillis() - firstChunkTime[0];
                        LOG.info("[子代理] 流完成: type={}, sessionId={}, totalChunks={}, totalDuration={}ms",
                                name, sessionId, chunkCount[0], totalDuration);
                    })
                    .doOnError(e -> {
                        LOG.error("[子代理] 流错误: type={}, sessionId={}, error={}, chunksReceived={}",
                                name, sessionId, e.getMessage(), chunkCount[0]);
                    })
                    .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                    .then(Mono.fromCallable(() -> contentBuilder.toString()))
                    .block(Duration.ofMillis(SUBAGENT_STREAM_TIMEOUT_MS));

            LOG.info("[子代理] 执行成功: type={}, sessionId={}, chunks={}, resultLength={}",
                    name, sessionId, chunkCount[0],
                    result != null ? result.length() : 0);

            return result;

        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("Timeout")) {
                LOG.error("[子代理] 执行超时: name={}, sessionId={}", name, sessionId);
                return "ERROR: 子代理执行超时。\n\n" +
                        "可能原因：\n" +
                        "1. LLM API 响应过慢或无响应\n" +
                        "2. 子代理执行的任务过于复杂\n" +
                        "3. 网络连接问题\n\n" +
                        "建议：\n" +
                        "- 简化任务描述\n" +
                        "- 检查网络连接\n" +
                        "- 查看子代理日志了解详情";
            }
            throw new RuntimeException(e);
        }
    }

}

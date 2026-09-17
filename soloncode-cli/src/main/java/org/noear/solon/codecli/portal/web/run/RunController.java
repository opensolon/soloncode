package org.noear.solon.codecli.portal.web.run;

import org.noear.snack4.ONode;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;

import org.noear.solon.web.sse.SseEmitter;
import org.noear.solon.web.sse.SseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * /web/run 控制器 —— soloncode run 的 HTTP/SSE 远程执行入口。
 *
 * <p>对齐 run-headless-mode-http.md：与 CLI 共享同一执行内核（子进程跑 {@code App run}，
 * cwd=工作区路径），SSE 的每个 {@code data:} 行即 CLI 的一行 JSONL，客户端解析层零改动。</p>
 *
 * <h3>安全（上线硬门槛）</h3>
 * <ul>
 *   <li>Bearer token 必须：无 token 一律 401，不提供关闭选项（{@link RunTokenService}）</li>
 *   <li>permission-mode 收口：bypassPermissions 拒绝（403）</li>
 *   <li>workspace 白名单：只能取服务端已注册工作区，不接受任意路径</li>
 *   <li>审计日志：时间/IP/workspace/prompt 长度（不落内容）/session_id/退出码</li>
 * </ul>
 *
 * <h3>退出码 → HTTP</h3>
 * <ul>
 *   <li>0 → 200；2(max_turns)/4(budget) → 200 + is_error:true（执行结论，不触发客户端 HTTP 重试）</li>
 *   <li>1 → 500；3 → 400（no_prompt）</li>
 * </ul>
 *
 * @author noear 2026/8/28 created
 * @see RunRequestService 请求规范化
 * @see RunSessionRegistry 会话锁与中断
 */
public class RunController {
    private static final Logger LOG = LoggerFactory.getLogger(RunController.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("run.audit");

    /** 子进程执行线程池（IO 密集，独立于 HTTP 线程） */
    private final ExecutorService runExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "soloncode-web-run");
        thread.setDaemon(true);
        return thread;
    });

    private final WorkspaceManager workspaceManager;
    private final RunRequestService requestService;
    private final RunTokenService tokenService;
    private final ProcessStarter processStarter;

    public RunController(WorkspaceManager workspaceManager) {
        this(workspaceManager, new RunRequestService(workspaceManager),
                RunTokenService.getInstance(), RunController::startSubprocess);
    }

    RunController(WorkspaceManager workspaceManager,
                  RunRequestService requestService,
                  RunTokenService tokenService,
                  ProcessStarter processStarter) {
        this.workspaceManager = workspaceManager;
        this.requestService = requestService;
        this.tokenService = tokenService;
        this.processStarter = processStarter;
    }

    /**
     * POST /web/run — 执行一次无头任务
     *
     * <p>stream-json：SSE 逐事件透传（连接在 result/error 事件后关闭）；
     * text/json：聚合 stdout 后一次性返回。</p>
     */
    @Post
    @Mapping("/web/run")
    public Object run(Context ctx) throws Exception {
        // ---- 1. Bearer token 校验（强制，先于一切解析）----
        if (!verifyToken(ctx)) {
            ctx.status(401);
            ctx.headerSet("WWW-Authenticate", "Bearer realm=\"soloncode-run\"");
            return Result.failure(401, "Missing or invalid bearer token");
        }

        // ---- 2. 请求体解析与规范化 ----
        ONode bodyJson;
        try {
            bodyJson = ONode.ofJson(ctx.body());
        } catch (Exception e) {
            return badRequest(ctx, "Request body must be valid JSON");
        }

        RunRequestService.NormalizedRequest req;
        try {
            req = requestService.normalize(bodyJson);
        } catch (RunRequestService.BadRequestException e) {
            return badRequest(ctx, e.getMessage());
        } catch (RunRequestService.ForbiddenException e) {
            ctx.status(403);
            return Result.failure(403, e.getMessage());
        } catch (RunRequestService.WorkspaceNotFoundException e) {
            ctx.status(404);
            return Result.failure(404, e.getMessage());
        }

        // ---- 3. 同 session 并发锁定（409）----
        RunSessionRegistry.RunHandle handle = null;
        if (req.sessionId != null) {
            handle = RunSessionRegistry.getInstance().tryRegister(req.sessionId);
            if (handle == null) {
                ctx.status(409);
                return Result.failure(409, "Session '" + req.sessionId + "' already has an active run");
            }
        }
        final RunSessionRegistry.RunHandle runHandle = handle;
        final String lockSessionId = req.sessionId;

        // ---- 4. 输出格式分流 ----
        boolean stream = bodyJson.get("options").get("output_format").getString() != null
                && "stream-json".equals(bodyJson.get("options").get("output_format").getString());

        try {
            if (stream) {
                // SSE 在后台执行；会话必须一直登记到子进程和 SSE 都结束，不能在此处提前注销。
                return startStreamRun(ctx, req, runHandle, lockSessionId);
            } else {
                try {
                    return runBlocking(ctx, req, runHandle, lockSessionId);
                } finally {
                    unregister(lockSessionId, runHandle);
                }
            }
        } catch (RuntimeException e) {
            unregister(lockSessionId, runHandle);
            throw e;
        }
    }

    /**
     * POST /web/run/interrupt — 中断指定会话的活跃执行（SIGTERM 等价）
     */
    @Post
    @Mapping("/web/run/interrupt")
    public Result interrupt(Context ctx, @org.noear.solon.annotation.Param("session_id") String sessionId) {
        if (!verifyToken(ctx)) {
            ctx.status(401);
            return Result.failure(401, "Missing or invalid bearer token");
        }
        if (sessionId == null || sessionId.trim().isEmpty()) {
            try {
                String raw = ctx.body();
                if (raw != null && !raw.trim().isEmpty()) {
                    sessionId = ONode.ofJson(raw).get("session_id").getString();
                }
            } catch (Exception ignored) {
                // Keep the normal validation response below for malformed bodies.
            }
        }
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return badRequestResult(ctx, "session_id is required");
        }

        boolean interrupted = RunSessionRegistry.getInstance().interrupt(sessionId.trim());
        if (!interrupted) {
            ctx.status(404);
            return Result.failure(404, "No active run for session: " + sessionId);
        }
        audit("interrupt", ctx, null, null, sessionId, 0);
        ctx.status(202);
        return Result.succeed();
    }

    // ========== 执行 ==========

    /**
     * 阻塞执行（text/json）：stdout 聚合，退出码→HTTP 映射后一次性返回。
     */
    private Object runBlocking(Context ctx, RunRequestService.NormalizedRequest req,
                               RunSessionRegistry.RunHandle handle, String lockSessionId) throws Exception {
        ProcessAndOutput result;
        try {
            result = execSubprocess(req, handle);
        } catch (Exception e) {
            LOG.error("[web/run] Subprocess failed: {}", e.getMessage(), e);
            ctx.status(500);
            return Result.failure(500, "Run failed: " + e.getMessage());
        }

        audit("run", ctx, req, result.lastSessionId, lockSessionId, result.exitCode);

        switch (result.exitCode) {
            case PrintModeExitCodes.EXIT_SUCCESS:
            case PrintModeExitCodes.EXIT_MAX_TURNS:
            case PrintModeExitCodes.EXIT_BUDGET_EXCEEDED:
                // 0/2/4 → 200（2/4 是执行结论，客户端按 is_error 分支处理）
                if (isJsonOutput(req)) {
                    ctx.headerSet("Content-Type", "application/json; charset=utf-8");
                    ctx.output(result.stdout);
                    return null;
                }
                ctx.headerSet("Content-Type", "text/plain; charset=utf-8");
                ctx.output(result.stdout);
                return null;
            case PrintModeExitCodes.EXIT_NO_PROMPT:
                return badRequest(ctx, "No prompt provided");
            case PrintModeExitCodes.EXIT_ERROR:
            default:
                ctx.status(500);
                ctx.headerSet("Content-Type", "application/json; charset=utf-8");
                ctx.output(buildErrorPayload(result));
                return null;
        }
    }

    /**
     * 流式执行（stream-json）：SseEmitter，stdout 逐行透传。
     */
    private SseEmitter startStreamRun(Context ctx, RunRequestService.NormalizedRequest req,
                                      RunSessionRegistry.RunHandle handle, String lockSessionId) {
        // SseEmitter 在 onInited 后才可 send；return 之前不能发消息
        final CountDownLatch emitterReady = new CountDownLatch(1);
        SseEmitter emitter = new SseEmitter(0L) // 0L = 默认超时（异步）
                .onInited(s -> emitterReady.countDown())
                .onError(t -> LOG.warn("[web/run] SSE error: {}", t.getMessage()))
                .onCompletion(() -> {
                    if (handle != null) {
                        handle.cancel();
                    }
                    unregister(lockSessionId, handle);
                    LOG.debug("[web/run] SSE completed");
                });

        final String clientIp = safeRealIp(ctx);
        runExecutor.submit(() -> {
            String lastSessionId = null;
            int exitCode = -1;
            try {
                emitterReady.await();
                ProcessAndOutput result = execSubprocess(req, handle, line -> {
                    if (!line.trim().isEmpty()) {
                        // send 失败（通常意味着客户端断开）必须向上传播，让读取循环立即销毁子进程。
                        emitter.send(new SseEvent().name("message").data(line));
                    }
                });
                exitCode = result.exitCode;
                lastSessionId = result.lastSessionId;

                // 正常情况下 CLI 已输出 result；异常退出且没有终态时补充协议错误。
                boolean abnormalExit = exitCode != PrintModeExitCodes.EXIT_SUCCESS
                        && exitCode != PrintModeExitCodes.EXIT_MAX_TURNS
                        && exitCode != PrintModeExitCodes.EXIT_BUDGET_EXCEEDED;
                if (abnormalExit && !result.resultSent) {
                    emitter.send(new SseEvent().name("message").data(
                            "{\"type\":\"error\",\"message\":\"Run failed with exit code " + exitCode
                                    + "\",\"code\":\"ERR_SUBPROCESS\"}"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("[web/run] Stream worker interrupted");
            } catch (Exception e) {
                LOG.error("[web/run] Stream run failed: {}", e.getMessage(), e);
                try {
                    emitter.send(new SseEvent().name("message").data(
                            "{\"type\":\"error\",\"message\":\"" + escape(e.getMessage())
                                    + "\",\"code\":\"ERR_SUBPROCESS\"}"));
                } catch (Exception ignored) {
                }
            } finally {
                audit("run-stream", clientIp, req, lastSessionId, lockSessionId, exitCode);
                unregister(lockSessionId, handle);
                emitter.complete();
            }
        });

        return emitter;
    }

    // ========== 子进程 ==========

    static class ProcessAndOutput {
        int exitCode = -1;
        String stdout = "";
        List<String> lines = new ArrayList<>();
        String lastSessionId;
        boolean resultSent;
    }

    /**
     * 启动子进程：java -cp <classpath> App run <prompt> [flags]，cwd=工作区路径。
     * stdout 逐行读取（stream-json 每行一个事件；text/json 即最终载荷）。
     */
    private ProcessAndOutput execSubprocess(RunRequestService.NormalizedRequest req,
                                             RunSessionRegistry.RunHandle handle) throws Exception {
        ProcessAndOutput out = new ProcessAndOutput();
        StringBuilder sb = new StringBuilder();
        execSubprocess(req, handle, line -> {
            // The streaming overload already records the line; this callback only
            // keeps the blocking aggregate path's output representation.
        }, out, sb);
        out.stdout = sb.toString();
        return out;
    }

    @FunctionalInterface
    interface LineConsumer {
        void accept(String line) throws Exception;
    }
    private ProcessAndOutput execSubprocess(RunRequestService.NormalizedRequest req,
                                              RunSessionRegistry.RunHandle handle,
                                              LineConsumer lineConsumer) throws Exception {
        ProcessAndOutput out = new ProcessAndOutput();
        execSubprocess(req, handle, lineConsumer, out, new StringBuilder());
        return out;
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(RunRequestService.NormalizedRequest req) throws Exception;
    }

    /**
     * 启动子进程：java -cp <classpath> App run <prompt> [flags]，cwd=工作区路径。
     * stdout 逐行读取（stream-json 每行一个事件；text/json 即最终载荷）。
     */
    private void execSubprocess(RunRequestService.NormalizedRequest req,
                                RunSessionRegistry.RunHandle handle,
                                LineConsumer lineConsumer,
                                ProcessAndOutput out,
                                StringBuilder stdout) throws Exception {
        Process process = processStarter.start(req);
        consumeProcess(process, handle, lineConsumer, out, stdout);
    }

    private static Process startSubprocess(RunRequestService.NormalizedRequest req) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaBin());
        // fat jar 部署（BOOT-INF/classes）下 -cp 找不到主类，须走 -jar；
        // classpath 为单 jar 即 fat jar 形态（IDE/脚本展开运行时是目录列表，-cp 正常）
        String classpath = System.getProperty("java.class.path");
        boolean fatJar = !classpath.contains(java.io.File.pathSeparator)
                && classpath.endsWith(".jar");
        if (fatJar) {
            command.add("-jar");
            command.add(classpath);
        } else {
            command.add("-cp");
            command.add(classpath);
            command.add("org.noear.solon.codecli.App");
        }
        command.add("run");
        command.add(req.prompt);
        command.addAll(req.argv);

        // stream-json 必须 verbose 才有逐事件输出
        boolean needVerbose = isStreamJsonOutput(req);
        if (needVerbose && !command.contains("--verbose")) {
            command.add("--verbose");
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new java.io.File(req.workspacePath));
        pb.redirectErrorStream(false);
        // 继承 HOME（token/配置解析依赖）等关键环境
        pb.environment().putIfAbsent("SOLONCODE_ENTRYPOINT", "web-run");
        return pb.start();
    }

    /**
     * 逐行消费子进程输出。回调失败代表下游 SSE 已不可写，必须立刻销毁子进程，
     * 不能继续把无人接收的任务跑到自然结束。
     */
    static void consumeProcess(Process process,
                               RunSessionRegistry.RunHandle handle,
                               LineConsumer lineConsumer,
                               ProcessAndOutput out,
                               StringBuilder stdout) throws Exception {
        // stderr must be drained independently; otherwise a verbose child can block
        // after filling the OS error pipe while stdout is being streamed.
        Thread errorDrainer = new Thread(() -> drain(process.getErrorStream()), "soloncode-web-run-stderr");
        errorDrainer.setDaemon(true);
        errorDrainer.start();
        if (handle != null) {
            handle.attach(process);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.lines.add(line);
                stdout.append(line).append('\n');
                String sid = extractSessionId(line);
                if (sid != null) {
                    out.lastSessionId = sid;
                }
                if (isResultLine(line)) {
                    out.resultSent = true;
                }
                lineConsumer.accept(line);
            }
        } catch (Exception e) {
            process.destroy();
            throw e;
        } finally {
            out.stdout = stdout.toString();
            out.exitCode = process.waitFor();
        }
    }

    private static boolean isResultLine(String line) {
        return line != null && line.replace(" ", "").contains("\"type\":\"result\"");
    }

    private static void drain(java.io.InputStream input) {
        byte[] buffer = new byte[1024];
        try {
            while (input.read(buffer) >= 0) {
                // stderr is intentionally not mixed into the JSONL protocol.
            }
        } catch (Exception ignored) {
        }
    }

    private static void unregister(String sessionId, RunSessionRegistry.RunHandle handle) {
        if (sessionId != null && handle != null) {
            RunSessionRegistry.getInstance().unregister(sessionId, handle);
        }
    }
    static class PrintModeExitCodes {
        static final int EXIT_SUCCESS = 0;
        static final int EXIT_ERROR = 1;
        static final int EXIT_MAX_TURNS = 2;
        static final int EXIT_NO_PROMPT = 3;
        static final int EXIT_BUDGET_EXCEEDED = 4;
    }

    // ========== 工具 ==========

    private boolean verifyToken(Context ctx) {
        String auth = ctx.header("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return false;
        }
        return tokenService.verify(auth.substring("Bearer ".length()).trim());
    }

    private static boolean isStreamJsonOutput(RunRequestService.NormalizedRequest req) {
        for (String flag : req.argv) {
            if (flag.startsWith("--output-format=") && flag.endsWith("stream-json")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJsonOutput(RunRequestService.NormalizedRequest req) {
        for (String flag : req.argv) {
            if (flag.startsWith("--output-format=") && flag.endsWith("json")
                    && !flag.endsWith("stream-json")) {
                return true;
            }
        }
        return false;
    }

    private static String extractSessionId(String jsonLine) {
        // 快速路径：session_id 只在 system/init 与 result 行出现
        int idx = jsonLine.indexOf("\"session_id\"");
        if (idx < 0) return null;
        try {
            ONode node = ONode.ofJson(jsonLine);
            return node.get("session_id").getString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String buildErrorPayload(ProcessAndOutput result) {
        ONode node = new ONode();
        node.set("is_error", true);
        node.set("error", "Run failed with exit code " + result.exitCode);
        node.set("session_id", result.lastSessionId != null ? result.lastSessionId : "");
        ONode error = node.getOrNew("error_detail");
        error.set("code", "ERR_SUBPROCESS");
        String stderrTail = result.stdout.length() > 2000
                ? result.stdout.substring(result.stdout.length() - 2000) : result.stdout;
        error.set("output_tail", stderrTail);
        return node.toJson();
    }

    private static String javaBin() {
        String javaHome = System.getProperty("java.home");
        return javaHome + java.io.File.separator + "bin" + java.io.File.separator + "java";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    // ========== 响应辅助 ==========

    private Result badRequestResult(Context ctx, String message) {
        ctx.status(400);
        return Result.failure(400, message);
    }

    private Object badRequest(Context ctx, String message) {
        ctx.status(400);
        ctx.headerSet("Content-Type", "application/json; charset=utf-8");
        ctx.output(new ONode().set("code", 400).set("message", message).toJson());
        return null;
    }

    // ========== 审计 ==========

    private void audit(String action, Context ctx, RunRequestService.NormalizedRequest req,
                       String resultSessionId, String lockSessionId, int exitCode) {
        audit(action, safeRealIp(ctx), req, resultSessionId, lockSessionId, exitCode);
    }

    private void audit(String action, String ip, RunRequestService.NormalizedRequest req,
                       String resultSessionId, String lockSessionId, int exitCode) {
        try {
            String ws = req != null ? req.workspaceName : "-";
            int promptLen = req != null && req.prompt != null ? req.prompt.length() : 0;
            String sid = resultSessionId != null ? resultSessionId
                    : (lockSessionId != null ? lockSessionId : "-");
            AUDIT.info("action={} ip={} workspace={} prompt_len={} session={} exit={}",
                    action, ip, ws, promptLen, sid, exitCode);
        } catch (Exception e) {
            LOG.debug("audit failed: {}", e.getMessage());
        }
    }

    private static String safeRealIp(Context ctx) {
        try {
            String ip = ctx == null ? null : ctx.realIp();
            return ip == null ? "-" : ip;
        } catch (Exception e) {
            return "-";
        }
    }
}

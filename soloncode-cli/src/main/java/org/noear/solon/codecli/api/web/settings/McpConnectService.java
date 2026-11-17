package org.noear.solon.codecli.api.web.settings;

import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.codecli.config.McpTypeResolver;
import org.noear.solon.codecli.config.entity.McpServerDo;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 服务器后台连接器。
 *
 * <p>设置页保存/启停 MCP 配置时只写配置并立即返回，真实连接（尤其 {@code npx} 首次运行需要
 * 下载依赖，可能耗时数十秒）统一交给这里的<b>单线程</b>队列串行执行，避免阻塞 HTTP 请求线程，
 * 也避免多个 {@code npx} 进程并发下载互相拖慢。</p>
 *
 * <p>连接结果以状态形式供列表接口读取（{@code pending / connecting / connected / failed / disabled}），
 * 失败原因会落进状态里上屏——{@code McpGatewayTalent} 内部只记日志、不抛异常，宿主无法从调用结果判断成败，
 * 因此这里先自行做一次握手探测，成功后才会真正注册到引擎。</p>
 *
 * <p>线程纪律：后台线程<b>不得</b>调用 {@code BaseSettingsController#currentContext()} 等依赖
 * 请求上下文的方法；所有上下文均由请求线程以参数形式传入。</p>
 *
 * @since 4.3.0
 */
public class McpConnectService {
    private static final Logger LOG = LoggerFactory.getLogger(McpConnectService.class);

    private static final McpConnectService INSTANCE = new McpConnectService();

    public static final String STATE_PENDING = "pending";
    public static final String STATE_CONNECTING = "connecting";
    public static final String STATE_CONNECTED = "connected";
    public static final String STATE_FAILED = "failed";
    public static final String STATE_DISABLED = "disabled";

    /**
     * 单次连接预算：覆盖 {@code npx} 冷启动下载 + 初始化握手。
     * 未单独设置时库默认 30 秒且超时判定为传输错误后会重试一次，冷启动几乎必然失败。
     */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(120);

    /** 失败原因上屏长度上限（避免整段堆栈塞进列表响应） */
    private static final int MESSAGE_MAX = 500;

    public static McpConnectService getInstance() {
        return INSTANCE;
    }

    /**
     * 连接状态快照（不可变）。
     */
    public static final class Status {
        private final String state;
        private final int toolCount;
        private final String message;
        private final long updatedAt;

        Status(String state, int toolCount, String message) {
            this.state = state;
            this.toolCount = toolCount;
            this.message = message;
            this.updatedAt = System.currentTimeMillis();
        }

        public String getState() {
            return state;
        }

        public int getToolCount() {
            return toolCount;
        }

        public String getMessage() {
            return message;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }
    }

    private final ExecutorService executor;
    private final Map<String, Status> statusMap = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> attemptMap = new ConcurrentHashMap<>();

    private McpConnectService() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "mcp-connect");
            thread.setDaemon(true);
            return thread;
        };
        // 单线程：串行连接，既保护全局线程池，也避免多个 npx 并发拉包
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    /**
     * 读取指定工作区下某个服务的连接状态（无记录返回 null）。
     */
    public Status statusOf(WorkspaceContext ctx, String name) {
        if (ctx == null || Assert.isEmpty(name)) {
            return null;
        }
        return statusMap.get(keyOf(ctx, name));
    }

    /**
     * 直接登记状态（用于停用/移除等无需连接的场景）。
     */
    public void mark(WorkspaceContext ctx, String name, String state, String message) {
        if (ctx == null || Assert.isEmpty(name)) {
            return;
        }
        String key = keyOf(ctx, name);
        // 使在途任务的结果失效，避免旧任务把状态覆盖回去
        attemptsOf(key).incrementAndGet();
        statusMap.put(key, new Status(state, 0, message));
    }

    /**
     * 清除状态（服务被移除时调用）。
     */
    public void clear(WorkspaceContext ctx, String name) {
        if (ctx == null || Assert.isEmpty(name)) {
            return;
        }
        String key = keyOf(ctx, name);
        attemptsOf(key).incrementAndGet();
        statusMap.remove(key);
    }

    /**
     * 提交一次后台连接任务。
     *
     * @param ctx        目标工作区（请求线程捕获，后台线程只读使用）
     * @param name       服务名
     * @param params     服务配置（已落盘的那一份）
     * @param onFinished 任务收尾回调（无论成败都会执行，用于多工作区同步），可为 null
     */
    public void connect(WorkspaceContext ctx, String name, McpServerDo params, Runnable onFinished) {
        if (ctx == null || Assert.isEmpty(name) || params == null) {
            return;
        }

        String key = keyOf(ctx, name);
        long attempt = attemptsOf(key).incrementAndGet();
        statusMap.put(key, new Status(STATE_PENDING, 0, null));

        executor.execute(() -> runConnect(ctx, name, params, key, attempt, onFinished));
    }

    private void runConnect(WorkspaceContext ctx, String name, McpServerDo params,
                            String key, long attempt, Runnable onFinished) {
        // 排队期间已被更新的一次提交取代：直接丢弃，不必再拉起进程
        if (isCurrent(key, attempt) == false) {
            return;
        }

        try {
            if (params.isEnabled() == false) {
                setState(key, attempt, new Status(STATE_DISABLED, 0, null));
                return;
            }

            setState(key, attempt, new Status(STATE_CONNECTING, 0, null));

            String error = null;
            int toolCount = 0;
            McpClientProvider probe = null;
            try {
                probe = buildProvider(params);
                toolCount = probe.getTools().size();
            } catch (Throwable e) {
                error = errorMessage(e);
            } finally {
                closeQuietly(name, probe);
            }

            if (error != null) {
                LOG.warn("[Settings] MCP server '{}' connect failed: {}", name, error);
                setState(key, attempt, new Status(STATE_FAILED, 0, error));
                return;
            }

            if (isCurrent(key, attempt) == false) {
                return;
            }

            try {
                // 探测通过后再正式注册：走参数重载以保证 options 快照同步更新
                ctx.getEngine().addMcpServer(name, params);
            } catch (Throwable e) {
                String message = "注册失败: " + errorMessage(e);
                LOG.warn("[Settings] MCP server '{}' register failed: {}", name, message);
                setState(key, attempt, new Status(STATE_FAILED, 0, message));
                return;
            }

            setState(key, attempt, new Status(STATE_CONNECTED, toolCount, null));
            LOG.info("[Settings] MCP server '{}' connected ({} tools)", name, toolCount);
        } catch (Throwable e) {
            LOG.warn("[Settings] MCP server '{}' connect task failed", name, e);
            setState(key, attempt, new Status(STATE_FAILED, 0, errorMessage(e)));
        } finally {
            if (onFinished != null) {
                try {
                    onFinished.run();
                } catch (Throwable e) {
                    LOG.warn("[Settings] MCP server '{}' post-connect task failed", name, e);
                }
            }
        }
    }

    /**
     * 构建一次性探测客户端：显式带上 120 秒预算。
     *
     * <p>与 {@code McpClientProviders.fromMcpServer} 保持同一套字段映射，区别只在超时——
     * 库里的 stdio 分支不设置超时，会落回默认 30 秒并在超时后重试一次。</p>
     */
    private static McpClientProvider buildProvider(McpServerDo params) {
        String type = params.getTypeOrTransport();
        if (Assert.isEmpty(type)) {
            type = McpTypeResolver.TYPE_STDIO;
        }

        McpClientProvider.Builder builder = McpClientProvider.builder()
                .channel(type)
                .description(params.getDescription())
                .timeout(PROBE_TIMEOUT);

        if (McpTypeResolver.isStdio(type)) {
            builder.command(params.getCommand());
            if (Assert.isNotEmpty(params.getArgs())) {
                builder.args(params.getArgs());
            }
            if (Assert.isNotEmpty(params.getEnv())) {
                builder.env(params.getEnv());
            }
        } else {
            builder.url(params.getUrl());
            if (Assert.isNotEmpty(params.getEnv())) {
                builder.headers(params.getEnv());
            }
            if (Assert.isNotEmpty(params.getHeaders())) {
                builder.headers(params.getHeaders());
            }
        }

        McpClientProvider provider = builder.build();
        provider.setAllowedTools(params.getAllowedTools());
        provider.setDisallowedTools(params.getDisallowedTools());
        return provider;
    }

    private void setState(String key, long attempt, Status status) {
        if (isCurrent(key, attempt)) {
            statusMap.put(key, status);
        }
    }

    private boolean isCurrent(String key, long attempt) {
        AtomicLong current = attemptMap.get(key);
        return current != null && current.get() == attempt;
    }

    private AtomicLong attemptsOf(String key) {
        return attemptMap.computeIfAbsent(key, k -> new AtomicLong());
    }

    private static String keyOf(WorkspaceContext ctx, String name) {
        return ctx.getMeta().getId() + "|" + name;
    }

    private static void closeQuietly(String name, McpClientProvider provider) {
        if (provider == null) {
            return;
        }
        try {
            provider.close();
        } catch (Exception e) {
            LOG.warn("[Settings] MCP server '{}' probe close failed", name, e);
        }
    }

    private static String errorMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        String message = root.getMessage();
        if (Assert.isEmpty(message)) {
            message = root.getClass().getSimpleName();
        } else {
            message = root.getClass().getSimpleName() + ": " + message;
        }

        return message.length() > MESSAGE_MAX ? message.substring(0, MESSAGE_MAX) + "..." : message;
    }
}

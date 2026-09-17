/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.channel.wechat;

import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.channel.ChunkedSender;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RunUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 微信 iLink Bot 通道
 *
 * <p>每个绑定了微信的会话对应一个独立的长轮询线程，
 * 从微信收取消息后通过 HarnessEngine 调用 AI，再将回复发回微信。</p>
 *
 * <p><b>线程模型</b>：长轮询会阻塞 35s 以上，因此使用本通道自己的缓存线程池，
 * 不占用 {@code RunUtil.timer()} 共享调度池（否则会挤占 Loop 调度、心跳等全局定时任务）。
 * 回复发送走每会话单线程队列，既不阻塞 AI 流线程，又能保证分段消息的先后顺序。</p>
 *
 * @author noear 2026/5/5 created
 */
public class WeChatLink implements Channel, Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(WeChatLink.class);

    /**
     * 服务端正常 hold 约 35s；若接口立即返回（异常响应等），用该下限兜底避免热循环打满接口
     */
    private static final long POLL_MIN_INTERVAL_MS = 1_000L;
    /**
     * 连续失败的退避基数与上限：3s、6s、12s、24s、30s(封顶)
     */
    private static final long BACKOFF_BASE_MS = 3_000L;
    private static final long BACKOFF_MAX_MS = 30_000L;

    /**
     * 「正在输入」续期间隔。iLink 的 typing 状态是短时效的，
     * 单次 sendtyping 撑不过一次完整的 AI 生成，需要按间隔续期。
     */
    private static final long TYPING_REFRESH_MS = 5_000L;
    /**
     * 「正在输入」最长保持时长，防止某轮没有最终回复（如异常中断）时状态永久悬挂
     */
    private static final long TYPING_MAX_MS = 300_000L;

    private static final int TYPING_ON = 1;
    private static final int TYPING_OFF = 2;

    private static final String BUSY_HINT = "⏳ 正在处理上一条消息，请稍候再试";

    /**
     * 同一会话「无回复目标」告警的最小间隔，避免一轮多段回复刷爆日志
     */
    private static final long NO_TARGET_WARN_INTERVAL_MS = 60_000L;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private final WorkspaceContext wsContext;
    private final WeChatCredentialStore credentialStore;
    private final Transport transport;

    /**
     * sessionId -> WeChatBinding
     */
    private final Map<String, WeChatBinding> bindings = new ConcurrentHashMap<>();

    /**
     * sessionId -> PollWorker
     */
    private final Map<String, PollWorker> pollWorkers = new ConcurrentHashMap<>();

    /**
     * sessionId -> 「正在输入」续期任务
     */
    private final Map<String, Future<?>> typingKeepers = new ConcurrentHashMap<>();

    /**
     * sessionId -> 回复发送队列（单线程，保证分段顺序）
     */
    private final Map<String, ExecutorService> senders = new ConcurrentHashMap<>();

    /**
     * sessionId -> 上次「无回复目标」告警时间
     */
    private final Map<String, Long> noTargetWarnAt = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile ExecutorService pollExecutor;
    private volatile ScheduledExecutorService typingScheduler;

    public WeChatLink(WorkspaceContext wsContext) {
        this(wsContext, new WeChatCredentialStore(wsContext.getEngine()), DEFAULT_TRANSPORT);
    }

    /**
     * 测试用构造：可注入凭据存储与传输层
     */
    WeChatLink(WorkspaceContext wsContext, WeChatCredentialStore credentialStore, Transport transport) {
        this.wsContext = wsContext;
        this.credentialStore = credentialStore;
        this.transport = transport;
    }

    // ==================== 绑定管理 ====================

    /**
     * 绑定微信到指定会话
     */
    public void bindSession(String sessionId, String botToken, String ilinkBotId, String ilinkUserId) {
        bindSession(sessionId, botToken, ilinkBotId, ilinkUserId, null);
    }

    /**
     * 绑定微信到指定会话
     *
     * @param baseUrl 扫码响应中服务端指派的接入点，可为 null
     */
    public void bindSession(String sessionId, String botToken, String ilinkBotId, String ilinkUserId, String baseUrl) {
        if (Assert.isEmpty(sessionId) || Assert.isEmpty(botToken)) {
            LOG.warn("[WeChat] bindSession ignored: sessionId or botToken is empty");
            return;
        }

        WeChatBinding existing = bindings.get(sessionId);
        if (isSameCredential(existing, botToken, ilinkBotId, ilinkUserId)) {
            // 重复确认：前端 2s 轮询、而 get_qrcode_status 对 confirmed 是幂等的，
            // 同一次扫码通常会有多个在途请求都拿到 confirmed，于是本方法被调用多次。
            //
            // 若在此重建 binding，cursor 退回 ""、回复目标归 null，并且重启长轮询会
            // 中断正在挂起的那次请求，把服务端已经投递的消息直接丢弃 —— 空游标语义是
            // "从当前 seq 开始"，被丢弃的消息不会补发。这正是"绑定成功后第一条微信消息
            // 收不到答复"的根因，因此这里必须原样保留会话状态。
            boolean changed = false;
            String normalized = WeChatClient.normalizeBaseUrl(baseUrl);
            if (normalized != null && !normalized.equals(existing.baseUrl)) {
                existing.baseUrl = normalized;
                changed = true;
            }
            if (changed) {
                credentialStore.save(bindings);
            }
            // 轮询线程可能因异常退出，这里补一次守护（已在跑则不动）
            ensurePolling(sessionId);
            LOG.debug("[WeChat] Duplicate bind for session {} ignored, cursor preserved", sessionId);
            return;
        }

        WeChatBinding binding = new WeChatBinding();
        binding.botToken = botToken;
        binding.ilinkBotId = ilinkBotId;
        binding.ilinkUserId = ilinkUserId;
        binding.baseUrl = WeChatClient.normalizeBaseUrl(baseUrl);
        binding.cursor = "";

        // 一个 ilinkUserId 只能有一个 session 绑定（自己除外）
        List<String> staleSessionIds = new ArrayList<>();
        bindings.forEach((k, v) -> {
            if (!k.equals(sessionId) && Objects.equals(v.ilinkUserId, ilinkUserId)) {
                staleSessionIds.add(k);
            }
        });
        for (String staleSessionId : staleSessionIds) {
            RunUtil.runAndTry(() -> unbindSession(staleSessionId));
        }

        bindings.put(sessionId, binding);

        // 持久化凭据
        credentialStore.save(bindings);

        // 启动该会话的长轮询
        startPolling(sessionId);

        LOG.info("[WeChat] Session {} bound to WeChat user {}", sessionId, ilinkUserId);
    }

    /**
     * 判断是否为同一套凭据（即重复绑定）。
     *
     * <p>bot_token 变化意味着新的登录态，按协议此时游标必须清空重建。</p>
     */
    static boolean isSameCredential(WeChatBinding binding, String botToken, String ilinkBotId, String ilinkUserId) {
        return binding != null
                && Objects.equals(binding.botToken, botToken)
                && Objects.equals(binding.ilinkBotId, ilinkBotId)
                && Objects.equals(binding.ilinkUserId, ilinkUserId);
    }

    /**
     * 解绑微信
     */
    public void unbindSession(String sessionId) {
        WeChatBinding binding = bindings.remove(sessionId);
        stopPolling(sessionId);
        cancelTypingKeeper(sessionId);
        shutdownSender(sessionId);
        noTargetWarnAt.remove(sessionId);
        if (binding != null) {
            binding.replyTarget = null;
        }
        // 持久化凭据（解绑后保存空的映射会删除文件）
        credentialStore.save(bindings);
        LOG.info("[WeChat] Session {} unbound", sessionId);
    }

    @Override
    public String getChannelName() {
        return "wechat";
    }

    /**
     * 查询会话是否已绑定微信
     */
    @Override
    public boolean isBound(String sessionId) {
        return bindings.containsKey(sessionId);
    }

    /**
     * 获取所有已绑定会话 ID
     */
    public Set<String> getBoundSessionIds() {
        return Collections.unmodifiableSet(bindings.keySet());
    }

    /**
     * 仅供内部与测试读取绑定态
     */
    WeChatBinding getBinding(String sessionId) {
        return bindings.get(sessionId);
    }

    /**
     * 从持久化存储恢复所有已绑定的会话
     */
    public void loadBindings() {
        Map<String, WeChatBinding> saved = credentialStore.load();
        if (saved.isEmpty()) return;

        LOG.info("[WeChat] Restoring {} saved binding(s)", saved.size());
        for (Map.Entry<String, WeChatBinding> entry : saved.entrySet()) {
            String sessionId = entry.getKey();
            bindings.put(sessionId, entry.getValue());
            startPolling(sessionId);
            LOG.info("[WeChat] Restored session {}", sessionId);
        }
    }

    /**
     * 启动通道：恢复已保存的绑定。
     *
     * <p><b>注意</b>：本方法由 {@code ChannelHub.start()} 调用，而后者目前在代码库中
     * 没有任何调用点，因此重启后的绑定恢复实际未生效（长轮询本身不依赖它，扫码绑定后
     * 会立即开始工作）。修复需要在工作区初始化处补上 ChannelHub 的 start，涉及飞书、
     * 钉钉三个通道的连接时机，宜单独评估。</p>
     */
    @Override
    public void run() {
        if (!running.compareAndSet(false, true)) {
            return; // 已在运行
        }
        LOG.info("[WeChat] Link started");
        // 恢复已保存的绑定
        loadBindings();
        // 主线程保持存活，等待关闭信号
    }

    /**
     * 停止所有轮询并关闭
     */
    public void stop() {
        running.set(false);
        for (String sid : new ArrayList<>(pollWorkers.keySet())) {
            stopPolling(sid);
        }
        for (String sid : new ArrayList<>(typingKeepers.keySet())) {
            cancelTypingKeeper(sid);
        }
        for (String sid : new ArrayList<>(senders.keySet())) {
            shutdownSender(sid);
        }
        shutdownQuietly(pollExecutor);
        shutdownQuietly(typingScheduler);
        pollExecutor = null;
        typingScheduler = null;
        LOG.info("[WeChat] Link stopped");
    }

    // ==================== 长轮询 ====================

    /**
     * 启动该会话的长轮询（已存在则先停掉重建）
     */
    protected void startPolling(String sessionId) {
        stopPolling(sessionId);

        PollWorker worker = new PollWorker(sessionId);
        pollWorkers.put(sessionId, worker);
        worker.submitTo(pollExecutor());
    }

    /**
     * 确保长轮询在跑；已在运行则不做任何事（不重启、不打断在途请求）
     */
    protected void ensurePolling(String sessionId) {
        PollWorker worker = pollWorkers.get(sessionId);
        if (worker != null && worker.isAlive()) {
            return;
        }
        startPolling(sessionId);
    }

    private void stopPolling(String sessionId) {
        PollWorker worker = pollWorkers.remove(sessionId);
        if (worker != null) {
            worker.cancel();
        }
    }

    private synchronized ExecutorService pollExecutor() {
        ExecutorService es = pollExecutor;
        if (es == null || es.isShutdown()) {
            es = Executors.newCachedThreadPool(daemonFactory("wechat-poll"));
            pollExecutor = es;
        }
        return es;
    }

    private synchronized ScheduledExecutorService typingScheduler() {
        ScheduledExecutorService es = typingScheduler;
        if (es == null || es.isShutdown()) {
            es = Executors.newSingleThreadScheduledExecutor(daemonFactory("wechat-typing"));
            typingScheduler = es;
        }
        return es;
    }

    /**
     * 单次轮询的结果
     */
    enum PollOutcome {
        /**
         * 拿到了有效响应
         */
        OK,
        /**
         * 请求失败或响应不可用，需要退避
         */
        FAILED,
        /**
         * 已解绑或凭据失效，循环应结束
         */
        STOP
    }

    /**
     * 长轮询工作线程：响应回来立刻重发（紧密循环），失败按指数退避。
     *
     * <p>原实现用 {@code scheduleWithFixedDelay(0, 2s)}，每轮之间存在 2 秒无挂起窗口，
     * 该窗口内到达的消息只能等下一轮，且阻塞任务会长期占用共享调度线程。</p>
     */
    private final class PollWorker implements Runnable {
        private final String sessionId;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private volatile Future<?> future;
        private volatile Thread thread;
        private volatile boolean finished;

        private PollWorker(String sessionId) {
            this.sessionId = sessionId;
        }

        private void submitTo(ExecutorService executor) {
            future = executor.submit(this);
        }

        private boolean isAlive() {
            return active.get() && !finished;
        }

        private void cancel() {
            active.set(false);
            Future<?> f = future;
            if (f == null) {
                return;
            }
            // 不打断自己：pollOnce 检出 token 过期时会就地 unbindSession，
            // 若在此中断当前线程，紧随其后的凭据落盘会被 ClosedByInterruptException 打断
            boolean self = (thread != null && thread == Thread.currentThread());
            f.cancel(!self);
        }

        @Override
        public void run() {
            thread = Thread.currentThread();
            long failures = 0;

            try {
                // 循环存续只取决于「本 worker 未被取消」与「绑定仍在」：
                // 不能再依赖 running —— ChannelHub.start() 目前无人调用，run() 不会被执行，
                // 若把 running 作为前置条件，扫码绑定后的轮询会直接空转。
                // 关闭路径由 stop() 显式 cancel 每个 worker 保证。
                while (active.get() && bindings.containsKey(sessionId)) {
                    long startedAt = System.currentTimeMillis();
                    PollOutcome outcome;
                    try {
                        outcome = pollOnce(sessionId);
                    } catch (Exception e) {
                        LOG.error("[WeChat] Poll error for session {}: {}", sessionId, e.getMessage());
                        outcome = PollOutcome.FAILED;
                    }

                    if (outcome == PollOutcome.STOP) {
                        break;
                    }

                    long sleepMs;
                    if (outcome == PollOutcome.FAILED) {
                        failures++;
                        sleepMs = backoffOf(failures);
                        LOG.warn("[WeChat] Poll failed for session {} ({} in a row), retry in {}ms",
                                sessionId, failures, sleepMs);
                    } else {
                        failures = 0;
                        long elapsed = System.currentTimeMillis() - startedAt;
                        sleepMs = Math.max(0L, POLL_MIN_INTERVAL_MS - elapsed);
                    }

                    if (sleepMs > 0 && !sleepQuietly(sleepMs)) {
                        break;
                    }
                }
            } finally {
                finished = true;
                pollWorkers.remove(sessionId, this);
            }
        }
    }

    private static long backoffOf(long failures) {
        int shift = (int) Math.min(failures - 1, 8);
        long delay = BACKOFF_BASE_MS << shift;
        return Math.min(delay < 0 ? BACKOFF_MAX_MS : delay, BACKOFF_MAX_MS);
    }

    /**
     * @return false 表示被中断，调用方应结束循环
     */
    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 单次轮询：获取消息 -> 调用 AI -> 回复微信
     */
    PollOutcome pollOnce(String sessionId) {
        WeChatBinding binding = bindings.get(sessionId);
        if (binding == null) {
            return PollOutcome.STOP;
        }

        Map<String, Object> result = transport.getUpdates(binding.baseUrl, binding.botToken, binding.cursor);
        if (result == null) {
            return PollOutcome.FAILED;
        }

        // token 过期，自动解绑
        if (Boolean.TRUE.equals(result.get("expired"))) {
            LOG.warn("[WeChat] Token expired for session {}, auto-unbinding", sessionId);
            unbindSession(sessionId);
            notifyExpired(sessionId);
            return PollOutcome.STOP;
        }

        boolean dirty = false;

        // 更新游标
        String newCursor = (String) result.get("cursor");
        if (newCursor != null && !newCursor.isEmpty() && !newCursor.equals(binding.cursor)) {
            binding.cursor = newCursor;
            dirty = true;
        }

        // 处理消息
        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) result.get("messages");
        if (Assert.isNotEmpty(messages)) {
            for (Map<String, String> msg : messages) {
                if (handleInbound(sessionId, binding, msg)) {
                    dirty = true;
                }
            }
        }

        // 仅在游标或回复目标真的变化时落盘（原实现每轮都全量重写）
        if (dirty) {
            credentialStore.saveThrottled(bindings);
        }
        return PollOutcome.OK;
    }

    /**
     * 处理单条入站消息
     *
     * @return true 表示绑定状态有变化，需要落盘
     */
    private boolean handleInbound(String sessionId, WeChatBinding binding, Map<String, String> msg) {
        String text = msg.get("text");
        String fromUserId = msg.get("from_user_id");
        String contextToken = msg.get("context_token");

        if (Assert.isEmpty(text)) {
            return false;
        }
        if (Assert.isEmpty(contextToken)) {
            // 没有 context_token 就无法回复，直接处理只会产生"已读不回"
            LOG.warn("[WeChat] Inbound message from {} has no context_token, dropped", fromUserId);
            return false;
        }

        LOG.info("[WeChat] Received from {}: {}", fromUserId, abbreviate(text, 50));

        // 回复目标必须成对替换：拆成两个字段赋值时，并发下可能出现
        // 甲的 userId 配上乙的 contextToken
        ReplyTarget target = new ReplyTarget(fromUserId, contextToken);
        binding.replyTarget = target;

        // typing_ticket 与 context_token 绑定，换轮需要重新获取
        String ticket = transport.getConfig(binding.baseUrl, binding.botToken, fromUserId, contextToken);
        if (ticket != null) {
            binding.typingTicket = ticket;
        }

        boolean accepted = dispatchToAgent(sessionId, text);
        if (accepted) {
            // safeChatInput 只负责投递、不等 AI 生成完成，
            // 所以「正在输入」要一直保持到最终回复发出（见 sendReplyDo 的 isFinal 分支）
            startTypingKeeper(sessionId, binding, target);
        } else {
            // 会话繁忙，向用户发送提示而不是静默丢弃
            transport.sendMessage(binding.baseUrl, binding.botToken, fromUserId, contextToken, BUSY_HINT);
        }
        return true;
    }

    /**
     * 把消息交给 AI（测试可覆写）
     */
    protected boolean dispatchToAgent(String sessionId, String text) {
        return wsContext.getWebGate().safeChatInput(wsContext, sessionId, text, "WeChat");
    }

    /**
     * 通知前端凭据已过期（测试可覆写）
     */
    protected void notifyExpired(String sessionId) {
        wsContext.getWebGate().emitToClient(wsContext, sessionId,
                WebEvent.ofError("微信连接已过期，请重新扫码绑定"));
        wsContext.getWebGate().emitToClient(wsContext, sessionId, WebEvent.ofDone());
    }

    // ==================== 「正在输入」状态 ====================

    private void startTypingKeeper(String sessionId, WeChatBinding binding, ReplyTarget target) {
        cancelTypingKeeper(sessionId);

        if (binding.typingTicket == null) {
            return;
        }

        sendTypingQuietly(binding, target, TYPING_ON);

        final long deadline = System.currentTimeMillis() + TYPING_MAX_MS;
        Future<?> keeper = typingScheduler().scheduleWithFixedDelay(() -> {
            if (System.currentTimeMillis() >= deadline || !bindings.containsKey(sessionId)) {
                stopTyping(sessionId, binding, target);
                return;
            }
            sendTypingQuietly(binding, target, TYPING_ON);
        }, TYPING_REFRESH_MS, TYPING_REFRESH_MS, TimeUnit.MILLISECONDS);

        typingKeepers.put(sessionId, keeper);
    }

    /**
     * 取消续期任务，但不发送停止状态
     */
    private void cancelTypingKeeper(String sessionId) {
        Future<?> keeper = typingKeepers.remove(sessionId);
        if (keeper != null) {
            keeper.cancel(false);
        }
    }

    /**
     * 结束「正在输入」：取消续期并发送停止状态
     */
    private void stopTyping(String sessionId, WeChatBinding binding, ReplyTarget target) {
        cancelTypingKeeper(sessionId);
        if (binding.typingTicket != null && target != null) {
            sendTypingQuietly(binding, target, TYPING_OFF);
        }
    }

    private void sendTypingQuietly(WeChatBinding binding, ReplyTarget target, int status) {
        try {
            transport.sendTyping(binding.baseUrl, binding.botToken, target.userId, binding.typingTicket, status);
        } catch (Exception e) {
            LOG.debug("[WeChat] sendTyping({}) failed: {}", status, e.getMessage());
        }
    }

    // ==================== 回复发送 ====================

    @Override
    public void sendReply(String sessionId, String reply, boolean isFinal) {
        WeChatBinding binding = bindings.get(sessionId);
        if (binding == null) {
            return;
        }

        if (Assert.isEmpty(reply)) {
            return;
        }

        ReplyTarget target = binding.replyTarget;
        if (target == null) {
            // iLink 协议不支持主动推送：没有入站消息带回的 context_token 就无法投递。
            // 静默 return 会让"微信端收不到答复"完全失去可观测性，这里必须留痕。
            warnNoReplyTarget(sessionId);
            return;
        }

        submitSend(sessionId, () -> {
            try {
                sendReplyDo(sessionId, binding, target, reply, isFinal);
            } catch (Exception e) {
                LOG.error("[WeChat] Reply error for session {}: {}", sessionId, e.getMessage(), e);
            }
        });
    }

    /**
     * 提交发送任务（测试可覆写为同步执行）。
     *
     * <p>每会话单线程：既不阻塞 AI 流线程，又能保证同一轮多段回复的顺序。</p>
     */
    protected void submitSend(String sessionId, Runnable task) {
        try {
            senders.computeIfAbsent(sessionId,
                    sid -> Executors.newSingleThreadExecutor(daemonFactory("wechat-send"))).execute(task);
        } catch (RejectedExecutionException e) {
            LOG.warn("[WeChat] Send queue closed for session {}, reply dropped", sessionId);
        }
    }

    private void sendReplyDo(String sessionId, WeChatBinding binding, ReplyTarget target, String reply, boolean isFinal) {
        // 发送前调用 getconfig 刷新 typing_ticket，可能延长 context_token 生命周期
        try {
            String freshTicket = transport.getConfig(binding.baseUrl, binding.botToken, target.userId, target.contextToken);
            if (freshTicket != null) {
                binding.typingTicket = freshTicket;
            }
        } catch (Exception e) {
            LOG.warn("[WeChat] pre-send getconfig failed, using cached ticket: {}", e.getMessage());
        }

        // 清理 markdown 标记，微信不渲染 markdown
        String cleanReply = cleanMarkdown(reply);
        if (cleanReply.isEmpty()) {
            cleanReply = reply; // fallback 到原文
        }

        // 微信消息长度限制，分段发送（含段间间隔与失败重试，与飞书/钉钉统一）。
        // 每个分段只生成一次 client_id；该段重试时复用，确保网络结果未知时仍具备幂等性。
        Map<Integer, String> clientIds = new HashMap<>();
        ChunkedSender.SendResult result = ChunkedSender.sendChunked(cleanReply,
                ChunkedSender.Config.wechat(),
                (chunk, part) -> {
                    String clientId = clientIds.computeIfAbsent(part,
                            key -> UUID.randomUUID().toString().replace("-", ""));
                    return transport.sendMessage(binding.baseUrl, binding.botToken,
                            target.userId, target.contextToken, chunk, clientId);
                });

        if (result.getFailedParts() > 0) {
            LOG.warn("[WeChat] Partial send failure for session {}: {}/{} part(s) failed",
                    sessionId, result.getFailedParts(), result.getTotalParts());
        }

        if (isFinal) {
            stopTyping(sessionId, binding, target);
        }
    }

    /**
     * 清理 markdown 标记。
     *
     * <p>与原实现的关键差异：只去掉代码围栏行，保留代码正文。
     * 原来整段删除代码块，导致代码类回复在微信端残缺甚至变成空白。</p>
     */
    static String cleanMarkdown(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replaceAll("(?m)^[ \\t]*(?:`{3,}|~{3,})[^\\n]*\\R?", "") // 去掉围栏行，保留代码正文
                .replaceAll("`([^`]+)`", "$1")                            // 去掉行内代码
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")                  // 去掉加粗
                .replaceAll("\\*([^*]+)\\*", "$1")                        // 去掉斜体
                .trim();
    }

    private void warnNoReplyTarget(String sessionId) {
        long now = System.currentTimeMillis();
        Long last = noTargetWarnAt.get(sessionId);
        if (last != null && now - last < NO_TARGET_WARN_INTERVAL_MS) {
            return;
        }
        noTargetWarnAt.put(sessionId, now);
        LOG.warn("[WeChat] Reply dropped for session {}: no inbound context_token yet. "
                + "iLink 不支持主动推送，必须由微信侧先发消息；若绑定后首条消息即丢失，请检查是否发生了重复绑定。",
                sessionId);
    }

    private void shutdownSender(String sessionId) {
        shutdownQuietly(senders.remove(sessionId));
    }

    // ==================== 工具 ====================

    private static ThreadFactory daemonFactory(String prefix) {
        return r -> {
            Thread t = new Thread(r, prefix + "-" + THREAD_SEQ.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private static void shutdownQuietly(ExecutorService es) {
        if (es == null) {
            return;
        }
        try {
            es.shutdownNow();
        } catch (Exception ignored) {
        }
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    // ==================== 传输层接缝 ====================

    /**
     * iLink HTTP 调用接缝：默认走 {@link WeChatClient} 静态方法，测试可注入内存实现
     */
    interface Transport {
        Map<String, Object> getUpdates(String baseUrl, String botToken, String cursor);

        boolean sendMessage(String baseUrl, String botToken, String toUserId, String contextToken, String text);

        /**
         * 带稳定 client_id 的发送入口。同一逻辑分段重试时调用方会复用该 ID。
         * 测试替身及兼容实现可沿用旧入口。
         */
        default boolean sendMessage(String baseUrl, String botToken, String toUserId, String contextToken,
                                    String text, String clientId) {
            return sendMessage(baseUrl, botToken, toUserId, contextToken, text);
        }

        String getConfig(String baseUrl, String botToken, String ilinkUserId, String contextToken);

        boolean sendTyping(String baseUrl, String botToken, String ilinkUserId, String typingTicket, int status);
    }

    static final Transport DEFAULT_TRANSPORT = new Transport() {
        @Override
        public Map<String, Object> getUpdates(String baseUrl, String botToken, String cursor) {
            return WeChatClient.getUpdates(baseUrl, botToken, cursor);
        }

        @Override
        public boolean sendMessage(String baseUrl, String botToken, String toUserId, String contextToken, String text) {
            return WeChatClient.sendMessage(baseUrl, botToken, toUserId, contextToken, text);
        }

        @Override
        public boolean sendMessage(String baseUrl, String botToken, String toUserId, String contextToken,
                                   String text, String clientId) {
            return WeChatClient.sendMessage(baseUrl, botToken, toUserId, contextToken, text, clientId);
        }

        @Override
        public String getConfig(String baseUrl, String botToken, String ilinkUserId, String contextToken) {
            return WeChatClient.getConfig(baseUrl, botToken, ilinkUserId, contextToken);
        }

        @Override
        public boolean sendTyping(String baseUrl, String botToken, String ilinkUserId, String typingTicket, int status) {
            return WeChatClient.sendTyping(baseUrl, botToken, ilinkUserId, typingTicket, status);
        }
    };

    // ==================== 内部数据类 ====================

    /**
     * 回复目标快照：user_id 与 context_token 必须成对使用，故做成不可变对象
     */
    public static class ReplyTarget {
        public final String userId;
        public final String contextToken;

        public ReplyTarget(String userId, String contextToken) {
            this.userId = userId;
            this.contextToken = contextToken;
        }
    }

    public static class WeChatBinding {
        public String botToken;
        public String ilinkBotId;
        public String ilinkUserId;
        /**
         * 服务端指派的接入点，null 表示使用默认地址
         */
        public String baseUrl;
        public String cursor;
        /**
         * 当前回复目标（最近一条入站消息）。
         * iLink 的 context_token 是每条消息独有的会话上下文令牌，回复必须原样回传。
         */
        public volatile ReplyTarget replyTarget;
        /**
         * typing_ticket 缓存，从 getconfig 获取
         */
        public volatile String typingTicket;

        public String getLastFromUserId() {
            ReplyTarget t = replyTarget;
            return t == null ? null : t.userId;
        }

        public String getLastContextToken() {
            ReplyTarget t = replyTarget;
            return t == null ? null : t.contextToken;
        }

        /**
         * 从持久化数据恢复回复目标
         */
        public void restoreReplyTarget(String userId, String contextToken) {
            if (contextToken != null && !contextToken.isEmpty()) {
                replyTarget = new ReplyTarget(userId, contextToken);
            }
        }
    }
}

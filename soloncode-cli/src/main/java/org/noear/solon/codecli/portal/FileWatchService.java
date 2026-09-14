package org.noear.solon.codecli.portal;

import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import org.noear.solon.codecli.workspace.WorkspaceLogRouter;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * 通用文件变化监听服务
 *
 * <p>基于 JDK {@link WatchService} 监控多个根目录（工作区 + 挂载点）的新增、删除、修改事件，
 * 经去重防抖后，按根目录分发到注册的处理器，实现文件树的实时同步及资源刷新。</p>
 *
 * <h3>核心流程</h3>
 * <pre>
 *   磁盘文件变化 → WatchService 捕获 → changedPaths 汇聚（含工作区标识）
 *       → flushChanges() 去重防抖 → 按 WatchRoot 分组 → 各根的手处理器分发
 * </pre>
 *
 * <h3>按根分发机制</h3>
 * <p>每个 {@link WatchRoot} 拥有独立的处理器列表。例如：</p>
 * <ul>
 *   <li><b>FILES 挂载</b> → JSON 广播到前端 WebSocket，更新文件面板</li>
 *   <li><b>SKILLS 挂载</b> → 调用技能刷新</li>
 *   <li><b>AGENTS 挂载</b> → 调用代理刷新</li>
 * </ul>
 *
 * <h3>动态挂载管理</h3>
 * <ul>
 *   <li>{@link #addRoot(String, Path)} 在 {@link #start()} 前后均可调用，自动判断是否需要立即注册目录树</li>
 *   <li>{@link #removeRoot(String)} 动态移除监听根，取消所有关联的 WatchKey</li>
 *   <li>自动排除 .git、node_modules、target 等无关目录</li>
 *   <li>新增目录时自动注册监听，覆盖子树</li>
 *   <li>使用守护线程，随主进程退出</li>
 * </ul>
 */
public class FileWatchService {
    private static final Logger LOG = LoggerFactory.getLogger(FileWatchService.class);

    /**
     * 所属工作区目录（仅用于日志分流）：本服务自建的轮询/初始化线程不在任何请求上下文里，
     * 靠它在线程入口打标，否则日志全部回退到启动工作区文件。
     */
    private String logWorkspacePath;

    /**
     * 设置日志归属的工作区目录（需在 {@link #start()} 之前调用才能生效）
     */
    public FileWatchService logWorkspacePath(String workspacePath) {
        this.logWorkspacePath = workspacePath;
        return this;
    }

    /** 监听的事件类型 */
    private static final WatchEvent.Kind<?>[] WATCH_KINDS = {ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY};

    /**
     * 高灵敏度修饰符（若当前 JDK 提供）。
     *
     * <p>macOS 上 JDK 的 WatchService 是轮询实现（PollingWatchService），默认灵敏度 10 秒，
     * 用 HIGH 可降到 2 秒；inotify 等原生实现不接受该修饰符，届时按 {@link #watchModifierSupported}
     * 回退为无修饰符注册。</p>
     */
    private static final WatchEvent.Modifier[] WATCH_MODIFIERS = resolveHighSensitivity();

    /** 平台是否接受 {@link #WATCH_MODIFIERS}；首次注册被拒后置 false，后续不再尝试 */
    private static volatile boolean watchModifierSupported = WATCH_MODIFIERS.length > 0;

    /** 变更推送防抖窗口（毫秒）：把一轮密集变更合并为一次推送 */
    private static final long FLUSH_DEBOUNCE_MS = 250;

    /** 新建目录补扫的条目上限：超过则改推一条整树对账，避免海量事件打爆前端 */
    private static final int SCAN_LIMIT = 500;

    /** 监听根映射表（按 id 索引，支持动态增删） */
    private final Map<String, WatchRoot> watchRoots = new ConcurrentHashMap<>();
    private volatile WatchService watchService;
    /** 防抖调度器（不能与轮询共用：轮询是永不返回的死循环，会独占单线程池） */
    private volatile ScheduledExecutorService scheduler;
    /** 事件轮询线程（start 在 init 线程赋值、stop 在调用方线程读取，须 volatile） */
    private volatile Thread pollThread;

    /** 标记 start() 是否已执行，决定 addRoot 时是否需要立即注册目录树 */
    private volatile boolean started = false;

    /** 待推送的变更（按 wsId+path 去重合并，线程安全） */
    private final ConcurrentHashMap<String, ChangeEntry> changedPaths = new ConcurrentHashMap<>();

    /** 防抖任务在飞标记：同一窗口内只排一个 flush */
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    private static WatchEvent.Modifier[] resolveHighSensitivity() {
        try {
            Class<?> clazz = Class.forName("com.sun.nio.file.SensitivityWatchEventModifier");
            Object[] constants = clazz.getEnumConstants();
            if (constants != null) {
                for (Object c : constants) {
                    if (c instanceof Enum && "HIGH".equals(((Enum<?>) c).name())) {
                        return new WatchEvent.Modifier[]{(WatchEvent.Modifier) c};
                    }
                }
            }
        } catch (Throwable ignored) {
            // JDK 内部 API，取不到就按默认灵敏度走
        }
        return new WatchEvent.Modifier[0];
    }

    /**
     * 监听根节点 —— 包含工作区标识、真实路径、独立的处理器列表及关联的 WatchKey 列表
     */
    public static class WatchRoot {
        final String id;   // "workspace" 或 "@mount-alias"
        final Path path;   // 真实文件系统绝对路径
        final List<Consumer<List<ChangeEntry>>> handlers = new ArrayList<>();
        /** 该根注册的所有 WatchKey，用于 removeRoot 时批量取消（Set 去重，避免重复注册时膨胀） */
        final Set<WatchKey> watchKeys = Collections.synchronizedSet(new LinkedHashSet<>());

        /**
         * 仅监听根目录自身，不递归注册其子树。
         *
         * <p>用于「根目录过于庞大、铺开监听得不偿失」的场景（如用户主目录）：此时前端本就不展示
         * 该根下的内容，递归注册只是在白白消耗轮询开销/系统 watch 配额。</p>
         */
        boolean shallow = false;

        WatchRoot(String id, Path path) {
            this.id = id;
            this.path = path.toAbsolutePath().normalize();
        }

        /**
         * 设置「仅监听根目录自身，不递归子树」（需在注册前调用，即 {@code addRoot} 链上）
         */
        public WatchRoot shallow(boolean shallow) {
            this.shallow = shallow;
            return this;
        }

        /**
         * 添加一个处理器，监听此根下的文件变更
         *
         * @param handler 接收该根下所有的变更条目列表
         * @return 自身，支持链式调用
         */
        public WatchRoot addHandler(Consumer<List<ChangeEntry>> handler) {
            this.handlers.add(handler);
            return this;
        }
    }

    /**
     * 变更条目 —— 包含工作区标识、相对路径、事件类型与节点类型
     */
    public static class ChangeEntry {
        public final String wsId;
        public final String path;
        /**
         * create / delete / modify / resync
         *
         * <p>{@code resync} 是无法枚举具体路径时的兜底信号（事件溢出、补扫超限等），
         * path 为空串，前端收到后对该根做一次整树对账。</p>
         */
        public final String kind;
        /** file / directory；delete 时可能为 null */
        public final String type;

        public ChangeEntry(String wsId, String path) {
            this(wsId, path, "modify", null);
        }

        public ChangeEntry(String wsId, String path, String kind, String type) {
            this.wsId = wsId;
            this.path = path;
            this.kind = kind != null ? kind : "modify";
            this.type = type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChangeEntry)) return false;
            ChangeEntry that = (ChangeEntry) o;
            return wsId.equals(that.wsId)
                    && path.equals(that.path)
                    && kind.equals(that.kind)
                    && Objects.equals(type, that.type);
        }

        @Override
        public int hashCode() {
            return Objects.hash(wsId, path, kind, type);
        }
    }

    /**
     * 添加一个监听根目录
     *
     * <p>在 {@link #start()} 之前调用：仅记录，待 start() 时统一注册目录树。<br>
     * 在 {@link #start()} 之后调用：立即注册目录树，实现动态挂载监听。</p>
     *
     * <p>若 id 已存在，先移除旧根（等价于替换场景），再创建新根。</p>
     *
     * @param id   工作区标识（如 "workspace" 或 "@solon-ai-source"）
     * @param path 真实文件系统路径
     * @return 创建的 {@link WatchRoot} 实例，可链式调用 {@link WatchRoot#addHandler}
     */
    public WatchRoot addRoot(String id, Path path) {
        // 若已存在同 id 的根，先清理（防止重复注册）
        removeRoot(id);

        WatchRoot root = new WatchRoot(id, path);
        watchRoots.put(id, root);

        // start() 之后动态添加：立即注册目录树
        if (started && watchService != null) {
            try {
                if (Files.exists(root.path)) {
                    registerTree(root.path, root);
                    LOG.info("[FileWatchService] dynamically registered root: {}{} -> {}",
                            id, root.shallow ? " (shallow)" : "", root.path);
                } else {
                    LOG.warn("[FileWatchService] root path not exists, skip: {} -> {}", id, root.path);
                }
            } catch (Exception e) {
                LOG.error("[FileWatchService] dynamic registerTree failed for root '{}': {}", id, e.getMessage(), e);
            }
        }

        return root;
    }

    /**
     * 移除一个监听根目录，取消其所有 WatchKey
     *
     * <p>用于挂载禁用、删除等场景。移除后，该根目录下的文件变更不再被捕获和分发。</p>
     *
     * @param id 工作区标识
     */
    public void removeRoot(String id) {
        WatchRoot root = watchRoots.remove(id);
        if (root == null) return;

        // 同一目录对同一 WatchService 只会得到同一个 WatchKey，若该目录同时落在另一个根内
        // （如 FILES 挂载指向工作区子目录），直接 cancel 会连带掐掉那个根的监听。
        Set<WatchKey> stillUsed = collectKeysOfOtherRoots(null);

        synchronized (root.watchKeys) {
            for (WatchKey key : root.watchKeys) {
                if (stillUsed.contains(key)) continue;
                try {
                    key.cancel();
                } catch (Exception ignored) {
                }
            }
            root.watchKeys.clear();
        }

        LOG.info("[FileWatchService] removed root: {}", id);
    }

    /**
     * 汇总除 exclude 以外所有存活根持有的 WatchKey
     *
     * @param exclude 需排除的根（可为 null；调用时已从 watchRoots 移除的根天然不在列中）
     */
    private Set<WatchKey> collectKeysOfOtherRoots(WatchRoot exclude) {
        Set<WatchKey> keys = new LinkedHashSet<>();
        for (WatchRoot other : watchRoots.values()) {
            if (other == exclude) continue;
            synchronized (other.watchKeys) {
                keys.addAll(other.watchKeys);
            }
        }
        return keys;
    }

    /** WatchKey 已失效（reset 返回 false）：从所有根的引用中清除 */
    private void forgetKey(WatchKey key) {
        for (WatchRoot root : watchRoots.values()) {
            root.watchKeys.remove(key);
        }
    }

    /**
     * 启动文件监听：初始化 WatchService、异步注册所有根目录树、开启轮询线程
     *
     * <p>目录树注册（{@link #registerTree}）可能在大工作区下耗时较长，
     * 因此放在独立守护线程中执行，避免阻塞主线程。</p>
     */
    public void start() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                //轮询线程入口打工作区日志标记（单线程池专属于本工作区，不会串用）
                Thread t = new Thread(WorkspaceLogRouter.withWorkspaceLogKey(logWorkspacePath, r), "file-watch-service");
                t.setDaemon(true);
                return t;
            });

            started = true;

            // 异步执行所有根目录的目录树注册，避免阻塞主线程
            Thread initThread = new Thread(WorkspaceLogRouter.withWorkspaceLogKey(logWorkspacePath, () -> {
                for (WatchRoot root : watchRoots.values()) {
                    try {
                        if (Files.exists(root.path)) {
                            registerTree(root.path, root);
                            LOG.info("[FileWatchService] registered root: {}{} -> {}",
                                    root.id, root.shallow ? " (shallow)" : "", root.path);
                        } else {
                            LOG.warn("[FileWatchService] root path not exists, skip: {} -> {}", root.id, root.path);
                        }
                    } catch (Exception e) {
                        // 单个根注册失败不影响其他根和后续轮询启动
                        LOG.error("[FileWatchService] registerTree failed for root '{}': {}", root.id, e.getMessage(), e);
                    }
                }

                // 无论是否有根注册失败，都启动事件轮询
                pollThread = new Thread(WorkspaceLogRouter.withWorkspaceLogKey(logWorkspacePath, this::pollEvents),
                        "file-watch-poll");
                pollThread.setDaemon(true);
                pollThread.start();
                LOG.info("[FileWatchService] started for {} roots", watchRoots.size());
            }), "file-watch-service-init");
            initThread.setDaemon(true);
            initThread.start();

        } catch (Exception e) {
            LOG.error("[FileWatchService] start failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 停止监听：关闭轮询线程、调度器和 WatchService
     */
    public void stop() {
        try {
            started = false;
            Thread poll = pollThread;
            if (poll != null) {
                poll.interrupt();
                pollThread = null;
            }
            ScheduledExecutorService executor = scheduler;
            if (executor != null) executor.shutdownNow();
            if (watchService != null) watchService.close();
        } catch (Exception e) {
            LOG.warn("[FileWatchService] stop error: {}", e.getMessage());
        } finally {
            // shutdownNow 会取消挂起的防抖任务，而复位标记正是该任务负责的；
            // 不在此处复位，实例一旦被复用（stop 后再 start）CAS 将永久失败 —— 变更只堆积不推送。
            flushScheduled.set(false);
            changedPaths.clear();
        }
    }

    /**
     * 递归注册目录树到 WatchService（排除无关目录），将 WatchKey 存入 root 便于后续清理
     *
     * @param dir  要注册的起始目录
     * @param root 所属的监听根，用于关联 WatchKey
     */
    private void registerTree(Path dir, WatchRoot root) throws Exception {
        // 本次注册失败的目录数：单点失败会被忽略（权限不足等属正常），但成片失败通常是
        // 触及系统 watch 配额（Linux inotify max_user_watches），必须让人看得见，
        // 否则表现为「某些目录永远不刷新」却毫无日志线索
        final int[] failures = {0};

        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                // 起始目录不做名称过滤：根自身就可能叫 .xxx（如挂载到某个隐藏目录）
                if (!d.equals(dir) && FilerIgnoreRules.isIgnoredName(nameOf(d))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                try {
                    root.watchKeys.add(registerDir(d));
                } catch (Exception e) {
                    failures[0]++;
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("[FileWatchService] register dir failed: {} ({})", d, e.getMessage());
                    }
                }
                // 浅监听：只挂根目录自身，不再深入子树
                if (root.shallow && d.equals(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // 某个文件/目录无访问权限时跳过，不让异常中断整个注册流程
                return FileVisitResult.CONTINUE;
            }
        });

        if (failures[0] > 0) {
            LOG.warn("[FileWatchService] registerTree for root '{}' finished with {} failed director{} "
                            + "(possibly hit the system watch limit, e.g. fs.inotify.max_user_watches): {}",
                    root.id, failures[0], failures[0] > 1 ? "ies" : "y", dir);
        }
    }

    /**
     * 注册单个目录：优先带高灵敏度修饰符（macOS 轮询实现下把 10s 降到 2s），
     * 平台不接受时回退为无修饰符注册——不能让修饰符不兼容变成整个目录漏监听。
     */
    private WatchKey registerDir(Path dir) throws IOException {
        if (watchModifierSupported) {
            try {
                return dir.register(watchService, WATCH_KINDS, WATCH_MODIFIERS);
            } catch (UnsupportedOperationException | IllegalArgumentException e) {
                watchModifierSupported = false;
                LOG.debug("[FileWatchService] sensitivity modifier unsupported, fallback to default");
            }
        }
        return dir.register(watchService, WATCH_KINDS);
    }

    /**
     * 新建目录的补扫：「目录创建」到「注册监听完成」之间存在窗口，窗口内落盘的子项
     * 不会产生任何事件（工具带 mkdirs 写文件时几乎必然命中）。注册完成后立即枚举一次，
     * 把已存在的子项补成 create 事件。
     *
     * @return true 补扫完整；false 条目过多或枚举失败，调用方应改为整树对账
     */
    private boolean scanNewDir(Path dir, WatchRoot root) {
        final int[] count = {0};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (d.equals(dir)) return FileVisitResult.CONTINUE; // 自身已由 ENTRY_CREATE 推送
                    if (FilerIgnoreRules.isIgnoredName(nameOf(d))) return FileVisitResult.SKIP_SUBTREE;
                    if (++count[0] > SCAN_LIMIT) return FileVisitResult.TERMINATE;
                    putChange(new ChangeEntry(root.id, relativize(root, d), "create", "directory"));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                    if (FilerIgnoreRules.isIgnoredName(nameOf(f), false)) return FileVisitResult.CONTINUE;
                    if (++count[0] > SCAN_LIMIT) return FileVisitResult.TERMINATE;
                    putChange(new ChangeEntry(root.id, relativize(root, f), "create", "file"));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            return false;
        }
        return count[0] <= SCAN_LIMIT;
    }

    private static String nameOf(Path path) {
        Path name = path.getFileName();
        return name != null ? name.toString() : "";
    }

    /** 相对于根的路径（统一用 / 分隔，与前端一致） */
    private static String relativize(WatchRoot root, Path fullPath) {
        return root.path.relativize(fullPath).toString().replace('\\', '/');
    }

    /**
     * 查找覆盖给定目录的所有 WatchRoot
     *
     * <p>一个目录可能同时落在多个根内（例如某个 FILES 挂载正好指向工作区的子目录），
     * 只认第一个会让其余根静默丢事件，故逐根分发。</p>
     */
    private List<WatchRoot> findRoots(Path dir) {
        List<WatchRoot> hits = null;
        for (WatchRoot root : watchRoots.values()) {
            if (dir.startsWith(root.path)) {
                if (hits == null) hits = new ArrayList<>(2);
                hits.add(root);
            }
        }
        return hits != null ? hits : Collections.<WatchRoot>emptyList();
    }

    /**
     * 轮询 WatchService 事件，捕获文件变更并触发防抖推送
     */
    private void pollEvents() {
        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break; // stop() 已关闭服务，正常退出
            }

            try {
                handleWatchKey(key);
            } catch (Exception e) {
                // 单个 key 处理异常不杀掉整个轮询线程
                LOG.error("[FileWatchService] poll error: {}", e.getMessage(), e);
            } finally {
                // reset 必须无条件执行：漏掉一次（例如上面抛了异常），
                // 按 WatchKey 契约该目录此后永远不再产生任何事件。
                if (!key.reset()) {
                    forgetKey(key);
                }
            }
            scheduleFlush();
        }
    }

    /** 处理单个就绪的 WatchKey */
    private void handleWatchKey(WatchKey key) {
        Path dir = (Path) key.watchable();

        List<WatchRoot> roots = findRoots(dir);
        if (roots.isEmpty()) {
            // 根已被移除，取消此 key 避免空转
            key.cancel();
            return;
        }

        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == OVERFLOW) {
                // 事件溢出：丢了哪些无从枚举（context 在各平台实现里也不是路径），只能让前端整树对账。
                // OVERFLOW 恰好出现在批量变更时（npm install / mvn package / 批量改文件），
                // 也就是用户最需要看到刷新的时刻。
                LOG.warn("[FileWatchService] event overflow at {}, request resync", dir);
                for (WatchRoot root : roots) {
                    putResync(root);
                }
                continue;
            }

            Object context = event.context();
            if (!(context instanceof Path)) continue;
            Path fullPath = dir.resolve((Path) context);

            String kind = toChangeKind(event.kind());
            String nodeType = resolveNodeType(fullPath, kind);
            boolean newDir = "create".equals(kind) && "directory".equals(nodeType);

            for (WatchRoot root : roots) {
                if (shouldIgnore(fullPath, root.path)) continue;

                // 记录结构化变更条目（同路径合并为净效果）
                putChange(new ChangeEntry(root.id, relativize(root, fullPath), kind, nodeType));

                if (newDir && !root.shallow) {
                    // 新增目录：递归注册子目录监听，并补扫注册窗口内已落盘的子项
                    try {
                        registerTree(fullPath, root);
                        if (!scanNewDir(fullPath, root)) {
                            putResync(root);
                        }
                    } catch (Exception e) {
                        LOG.warn("[FileWatchService] register new dir failed: {} ({})", fullPath, e.getMessage());
                        putResync(root);
                    }
                }
            }
        }
    }

    /**
     * 判断路径是否应忽略（隐藏文件或排除目录下的文件）
     */
    private boolean shouldIgnore(Path fullPath, Path rootPath) {
        return FilerIgnoreRules.isIgnoredPath(rootPath.relativize(fullPath));
    }

    /**
     * 标记某个根需要整树对账（无法枚举具体路径时的兜底）
     */
    private void putResync(WatchRoot root) {
        // 整树对账会覆盖一切增量，先清掉该根已累积的条目，避免重复工作
        final String prefix = root.id + "\0";
        changedPaths.keySet().removeIf(k -> k.startsWith(prefix));
        changedPaths.put(changeKey(root.id, ""), new ChangeEntry(root.id, "", "resync", null));
    }

    /**
     * 安排一次防抖推送：把窗口内的密集变更合并成一次广播
     *
     * <p>调度器不可用（未 start / 已 stop）时直接同步推送，保证不丢事件。</p>
     */
    private void scheduleFlush() {
        if (changedPaths.isEmpty()) return;

        ScheduledExecutorService executor = scheduler;
        if (executor == null || executor.isShutdown()) {
            flushChanges();
            return;
        }
        if (!flushScheduled.compareAndSet(false, true)) return;
        try {
            executor.schedule(() -> {
                flushScheduled.set(false);
                flushChanges();
            }, FLUSH_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            flushScheduled.set(false);
            flushChanges();
        }
    }

    /**
     * 将累积的变更路径按根目录分组，分发到各根注册的处理器
     *
     * <p>每个根目录的处理器仅收到属于该根的变更条目列表。
     * 处理器可自行决定如何处理，例如构建 JSON 广播到前端、触发技能刷新等。</p>
     */
    private void flushChanges() {
        if (changedPaths.isEmpty()) return;

        List<ChangeEntry> batch = new ArrayList<>(changedPaths.values());
        changedPaths.clear();

        // 按 wsId 分组
        Map<String, List<ChangeEntry>> grouped = batch.stream()
                .collect(Collectors.groupingBy(e -> e.wsId));

        // 逐根分发
        for (WatchRoot root : watchRoots.values()) {
            List<ChangeEntry> rootChanges = grouped.get(root.id);
            if (rootChanges != null && !rootChanges.isEmpty()) {
                for (Consumer<List<ChangeEntry>> handler : root.handlers) {
                    try {
                        handler.accept(rootChanges);
                    } catch (Exception e) {
                        LOG.warn("[FileWatchService] handler error for root '{}': {}", root.id, e.getMessage());
                    }
                }
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("[FileWatchService] pushed {} changes across {} roots", batch.size(), grouped.size());
        }
    }

    /**
     * 工具方法：将 {@link ChangeEntry} 列表构建为前端 {@code filer_change} 事件 JSON
     *
     * @param changes 变更条目列表
     * @return JSON 字符串，格式如下：
     * <pre>{
     *   "type": "filer_change",
     *   "changes": [
     *     {"wsId": "workspace", "path": "src/Foo.java", "kind": "create", "type": "file"},
     *     {"wsId": "@solon-ai", "path": "src/main/java/Bar.java", "kind": "delete", "type": "file"}
     *   ],
     *   "createdAt": 1716153600000
     * }</pre>
     */
    public static String buildFrontendJson(List<ChangeEntry> changes) {
        ONode changesNode = new ONode().asArray();
        for (ChangeEntry entry : changes) {
            ONode item = new ONode()
                    .set("wsId", entry.wsId)
                    .set("path", entry.path)
                    .set("kind", entry.kind);
            if (entry.type != null) {
                item.set("type", entry.type);
            }
            changesNode.add(item);
        }

        // SAEP 2.0 信封：前端 AgentEventDispatcher.toWebEvent 依赖 event 字段，
        // 缺失时会被判为 null 直接丢弃（文件树将不再自动刷新）。changes 放入 payload。
        long now = System.currentTimeMillis();
        ONode payload = new ONode()
                .set("changes", changesNode)
                .set("createdAt", now);
        return new ONode()
                .set("event", "system.filer_change")
                .set("timestamp", now)
                .set("payload", payload)
                .toJson();
    }

    private static String changeKey(String wsId, String path) {
        return wsId + "\0" + path;
    }

    private static String toChangeKind(WatchEvent.Kind<?> kind) {
        if (kind == ENTRY_CREATE) return "create";
        if (kind == ENTRY_DELETE) return "delete";
        return "modify";
    }

    private static String resolveNodeType(Path fullPath, String kind) {
        if ("delete".equals(kind)) {
            return null;
        }
        try {
            return Files.isDirectory(fullPath) ? "directory" : "file";
        } catch (Exception e) {
            return "file";
        }
    }

    /**
     * 合并同路径变更，保留对树结构有意义的净效果：
     * create+modify=create，create+delete=取消，delete+create=create。
     */
    private void putChange(ChangeEntry entry) {
        String key = changeKey(entry.wsId, entry.path);
        for (;;) {
            ChangeEntry existing = changedPaths.get(key);
            if (existing == null) {
                if (changedPaths.putIfAbsent(key, entry) == null) {
                    return;
                }
                continue;
            }
            ChangeEntry merged = mergeChange(existing, entry);
            if (merged == null) {
                if (changedPaths.remove(key, existing)) {
                    return;
                }
                continue;
            }
            if (changedPaths.replace(key, existing, merged)) {
                return;
            }
        }
    }

    static ChangeEntry mergeChange(ChangeEntry oldEntry, ChangeEntry newEntry) {
        if (oldEntry == null) return newEntry;
        if (newEntry == null) return oldEntry;

        String oldKind = oldEntry.kind;
        String newKind = newEntry.kind;

        if ("create".equals(oldKind) && "delete".equals(newKind)) {
            return null;
        }
        if ("delete".equals(oldKind) && "create".equals(newKind)) {
            return newEntry;
        }
        if ("delete".equals(newKind)) {
            return new ChangeEntry(newEntry.wsId, newEntry.path, "delete",
                    newEntry.type != null ? newEntry.type : oldEntry.type);
        }
        if ("create".equals(newKind)) {
            return newEntry;
        }
        // newKind == modify
        if ("create".equals(oldKind) || "delete".equals(oldKind)) {
            return oldEntry;
        }
        return newEntry;
    }
}

package org.noear.solon.codecli.portal;

import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
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

    /** 需要排除的目录名（不监听、不同步） */
    private static final Set<String> EXCLUDED_DIRS = new HashSet<>(Arrays.asList(
            // 项目元数据 & IDE
            ".soloncode", ".claude", ".opencode",
            ".idea", ".vscode", ".settings",
            // 版本控制 & 构建工具
            ".git", ".gradle", ".mvn",
            // 运行时缓存
            ".pytest_cache", "__pycache__",
            ".DS_Store",
            // 依赖目录
            "node_modules", "venv", "vendor",
            // 构建输出
            "target", "build"
    ));

    /** 监听根映射表（按 id 索引，支持动态增删） */
    private final Map<String, WatchRoot> watchRoots = new ConcurrentHashMap<>();
    private WatchService watchService;
    private ScheduledExecutorService scheduler;

    /** 标记 start() 是否已执行，决定 addRoot 时是否需要立即注册目录树 */
    private volatile boolean started = false;

    /** 待推送的变更（按 wsId+path 去重合并，线程安全） */
    private final ConcurrentHashMap<String, ChangeEntry> changedPaths = new ConcurrentHashMap<>();

    /**
     * 监听根节点 —— 包含工作区标识、真实路径、独立的处理器列表及关联的 WatchKey 列表
     */
    public static class WatchRoot {
        final String id;   // "workspace" 或 "@mount-alias"
        final Path path;   // 真实文件系统绝对路径
        final List<Consumer<List<ChangeEntry>>> handlers = new ArrayList<>();
        /** 该根注册的所有 WatchKey，用于 removeRoot 时批量取消 */
        final List<WatchKey> watchKeys = Collections.synchronizedList(new ArrayList<>());

        WatchRoot(String id, Path path) {
            this.id = id;
            this.path = path.toAbsolutePath().normalize();
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
        /** create / delete / modify */
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
                    LOG.info("[FileWatchService] dynamically registered root: {} -> {}", id, root.path);
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

        // 取消该根注册的所有 WatchKey
        synchronized (root.watchKeys) {
            for (WatchKey key : root.watchKeys) {
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
     * 启动文件监听：初始化 WatchService、异步注册所有根目录树、开启轮询线程
     *
     * <p>目录树注册（{@link #registerTree}）可能在大工作区下耗时较长，
     * 因此放在独立守护线程中执行，避免阻塞主线程。</p>
     */
    public void start() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "file-watch-service");
                t.setDaemon(true);
                return t;
            });

            started = true;

            // 异步执行所有根目录的目录树注册，避免阻塞主线程
            Thread initThread = new Thread(() -> {
                for (WatchRoot root : watchRoots.values()) {
                    try {
                        if (Files.exists(root.path)) {
                            registerTree(root.path, root);
                            LOG.info("[FileWatchService] registered root: {} -> {}", root.id, root.path);
                        } else {
                            LOG.warn("[FileWatchService] root path not exists, skip: {} -> {}", root.id, root.path);
                        }
                    } catch (Exception e) {
                        // 单个根注册失败不影响其他根和后续轮询启动
                        LOG.error("[FileWatchService] registerTree failed for root '{}': {}", root.id, e.getMessage(), e);
                    }
                }

                // 无论是否有根注册失败，都启动事件轮询
                scheduler.submit(this::pollEvents);
                LOG.info("[FileWatchService] started for {} roots", watchRoots.size());
            }, "file-watch-service-init");
            initThread.setDaemon(true);
            initThread.start();

        } catch (Exception e) {
            LOG.error("[FileWatchService] start failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 停止监听：关闭调度器和 WatchService
     */
    public void stop() {
        try {
            started = false;
            if (scheduler != null) scheduler.shutdownNow();
            if (watchService != null) watchService.close();
        } catch (Exception e) {
            LOG.warn("[FileWatchService] stop error: {}", e.getMessage());
        }
    }

    /**
     * 递归注册目录树到 WatchService（排除无关目录），将 WatchKey 存入 root 便于后续清理
     *
     * @param dir  要注册的起始目录
     * @param root 所属的监听根，用于关联 WatchKey
     */
    private void registerTree(Path dir, WatchRoot root) throws Exception {
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                String name = d.getFileName() != null ? d.getFileName().toString() : "";
                if (EXCLUDED_DIRS.contains(name) || name.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                try {
                    WatchKey key = d.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                    root.watchKeys.add(key);
                } catch (Exception ignored) {
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // 某个文件/目录无访问权限时跳过，不让异常中断整个注册流程
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 查找给定路径所属的 WatchRoot
     */
    private WatchRoot findRoot(Path dir) {
        for (WatchRoot root : watchRoots.values()) {
            if (dir.startsWith(root.path)) {
                return root;
            }
        }
        return null;
    }

    /**
     * 轮询 WatchService 事件，捕获文件变更并触发防抖推送
     */
    private void pollEvents() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WatchKey key = watchService.take();
                Path dir = (Path) key.watchable();

                // 找到所属的根，以确定相对化基准
                WatchRoot root = findRoot(dir);
                if (root == null) {
                    // 根已被移除，取消此 key 避免空转
                    key.cancel();
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    Path fullPath = dir.resolve((Path) event.context());

                    if (shouldIgnore(fullPath, root.path)) continue;

                    // 相对于根的路径
                    String relativePath = root.path.relativize(fullPath).toString().replace('\\', '/');

                    // 记录结构化变更条目（同路径合并为净效果）
                    String kind = toChangeKind(event.kind());
                    String nodeType = resolveNodeType(fullPath, kind);
                    putChange(new ChangeEntry(root.id, relativePath, kind, nodeType));

                    // 新增目录时，递归注册其子目录监听
                    if (event.kind() == ENTRY_CREATE && fullPath.toFile().isDirectory()) {
                        try {
                            registerTree(fullPath, root);
                        } catch (Exception ignored) {
                        }
                    }
                }

                key.reset();
                flushChanges();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 单个事件处理异常不杀掉整个轮询线程
                LOG.error("[FileWatchService] poll error: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 判断路径是否应忽略（隐藏文件或排除目录下的文件）
     */
    private boolean shouldIgnore(Path fullPath, Path rootPath) {
        Path relative = rootPath.relativize(fullPath);
        for (Path segment : relative) {
            String name = segment.toString();
            if (name.startsWith(".") || EXCLUDED_DIRS.contains(name)) return true;
        }
        return false;
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

        return new ONode()
                .set("type", "filer_change")
                .set("changes", changesNode)
                .set("createdAt", System.currentTimeMillis())
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

package org.noear.solon.codecli.portal;

import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <h3>支持多根目录</h3>
 * <ul>
 *   <li>通过 {@link #addRoot(String, Path)} 添加任意数量的监听根，返回 {@link WatchRoot} 供链式注册处理器</li>
 *   <li>广播变更时使用结构化对象 {@code ChangeEntry {wsId, path}}，处理器可按需构建 JSON</li>
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

    /** 监听根列表 */
    private final List<WatchRoot> watchRoots = new ArrayList<>();
    private WatchService watchService;
    private ScheduledExecutorService scheduler;

    /** 待推送的变更路径集合（去重、线程安全） */
    private final Set<ChangeEntry> changedPaths = ConcurrentHashMap.newKeySet();

    /**
     * 监听根节点 —— 包含工作区标识、真实路径及独立的处理器列表
     */
    public static class WatchRoot {
        final String id;   // "workspace" 或 "@mount-alias"
        final Path path;   // 真实文件系统绝对路径
        final List<Consumer<List<ChangeEntry>>> handlers = new ArrayList<>();

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
     * 变更条目 —— 包含工作区标识和相对路径
     */
    public static class ChangeEntry {
        public final String wsId;
        public final String path;

        public ChangeEntry(String wsId, String path) {
            this.wsId = wsId;
            this.path = path;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChangeEntry)) return false;
            ChangeEntry that = (ChangeEntry) o;
            return wsId.equals(that.wsId) && path.equals(that.path);
        }

        @Override
        public int hashCode() {
            return 31 * wsId.hashCode() + path.hashCode();
        }
    }

    /**
     * 添加一个监听根目录
     *
     * @param id   工作区标识（如 "workspace" 或 "@solon-ai-source"）
     * @param path 真实文件系统路径
     * @return 创建的 {@link WatchRoot} 实例，可链式调用 {@link WatchRoot#addHandler}
     */
    public WatchRoot addRoot(String id, Path path) {
        WatchRoot root = new WatchRoot(id, path);
        this.watchRoots.add(root);
        return root;
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

            // 异步执行所有根目录的目录树注册，避免阻塞主线程
            Thread initThread = new Thread(() -> {
                try {
                    for (WatchRoot root : watchRoots) {
                        registerTree(root.path);
                        LOG.info("[FileWatchService] registered root: {} -> {}", root.id, root.path);
                    }
                    scheduler.submit(FileWatchService.this::pollEvents);
                    LOG.info("[FileWatchService] started for {} roots", watchRoots.size());
                } catch (Exception e) {
                    LOG.error("[FileWatchService] start failed: {}", e.getMessage(), e);
                }
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
            if (scheduler != null) scheduler.shutdownNow();
            if (watchService != null) watchService.close();
        } catch (Exception e) {
            LOG.warn("[FileWatchService] stop error: {}", e.getMessage());
        }
    }

    /**
     * 递归注册目录树到 WatchService（排除无关目录）
     */
    private void registerTree(Path dir) throws Exception {
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                String name = d.getFileName() != null ? d.getFileName().toString() : "";
                if (EXCLUDED_DIRS.contains(name) || name.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                try {
                    d.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                } catch (Exception ignored) {
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 查找给定路径所属的 WatchRoot
     */
    private WatchRoot findRoot(Path dir) {
        for (WatchRoot root : watchRoots) {
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
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watchService.take();
                Path dir = (Path) key.watchable();

                // 找到所属的根，以确定相对化基准
                WatchRoot root = findRoot(dir);
                if (root == null) {
                    key.reset();
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    Path fullPath = dir.resolve((Path) event.context());

                    if (shouldIgnore(fullPath, root.path)) continue;

                    // 相对于根的路径
                    String relativePath = root.path.relativize(fullPath).toString().replace('\\', '/');

                    // 记录结构化变更条目
                    changedPaths.add(new ChangeEntry(root.id, relativePath));

                    // 新增目录时，递归注册其子目录监听
                    if (event.kind() == ENTRY_CREATE && fullPath.toFile().isDirectory()) {
                        try {
                            registerTree(fullPath);
                        } catch (Exception ignored) {
                        }
                    }
                }

                key.reset();
                flushChanges();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.error("[FileWatchService] poll error: {}", e.getMessage());
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

        Set<ChangeEntry> batch = new LinkedHashSet<>(changedPaths);
        changedPaths.clear();

        // 按 wsId 分组
        Map<String, List<ChangeEntry>> grouped = batch.stream()
                .collect(Collectors.groupingBy(e -> e.wsId));

        // 逐根分发
        for (WatchRoot root : watchRoots) {
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
     *     {"wsId": "workspace", "path": "src/Foo.java"},
     *     {"wsId": "@solon-ai", "path": "src/main/java/Bar.java"}
     *   ],
     *   "createdAt": 1716153600000
     * }</pre>
     */
    public static String buildFrontendJson(List<ChangeEntry> changes) {
        ONode changesNode = new ONode().asArray();
        for (ChangeEntry entry : changes) {
            changesNode.add(new ONode()
                    .set("wsId", entry.wsId)
                    .set("path", entry.path));
        }

        return new ONode()
                .set("type", "filer_change")
                .set("changes", changesNode)
                .set("createdAt", System.currentTimeMillis())
                .toJson();
    }
}
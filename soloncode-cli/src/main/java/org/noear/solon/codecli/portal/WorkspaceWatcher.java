package org.noear.solon.codecli.portal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * 工作区文件变化监听器
 *
 * <p>使用 WatchService 监控工作区目录变化，
 * 通过 WebGate WebSocket 推送 filer_change 事件到前端。</p>
 */
public class WorkspaceWatcher {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceWatcher.class);

    private static final Set<String> EXCLUDED_DIRS = new HashSet<>(Arrays.asList(
            ".git", ".idea", ".soloncode", "node_modules", "target",
            "__pycache__", ".gradle", ".mvn", "build"
    ));

    private final Path workspace;
    private final WebGate webGate;
    private WatchService watchService;
    private ScheduledExecutorService scheduler;

    private final Set<String> changedPaths = ConcurrentHashMap.newKeySet();
    private ScheduledFuture<?> debounceTask;

    private static final long DEBOUNCE_MS = 500;

    public WorkspaceWatcher(Path workspace, WebGate webGate) {
        this.workspace = workspace;
        this.webGate = webGate;
    }

    public void start() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "workspace-watcher");
                t.setDaemon(true);
                return t;
            });

            registerTree(workspace);
            scheduler.submit(this::pollEvents);

            LOG.info("[WorkspaceWatcher] started for: {}", workspace);
        } catch (Exception e) {
            LOG.error("[WorkspaceWatcher] start failed: {}", e.getMessage(), e);
        }
    }

    public void stop() {
        try {
            if (debounceTask != null) debounceTask.cancel(false);
            if (scheduler != null) scheduler.shutdownNow();
            if (watchService != null) watchService.close();
        } catch (Exception e) {
            LOG.warn("[WorkspaceWatcher] stop error: {}", e.getMessage());
        }
    }

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
                } catch (Exception ignored) {}
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void pollEvents() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watchService.take();
                Path dir = (Path) key.watchable();

                for (WatchEvent<?> event : key.pollEvents()) {
                    Path fullPath = dir.resolve((Path) event.context());

                    if (shouldIgnore(fullPath)) continue;

                    String relativePath = workspace.relativize(fullPath).toString().replace('\\', '/');
                    changedPaths.add(relativePath);

                    if (event.kind() == ENTRY_CREATE && fullPath.toFile().isDirectory()) {
                        try {
                            fullPath.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                        } catch (Exception ignored) {}
                    }
                }

                key.reset();
                scheduleDebounce();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.error("[WorkspaceWatcher] poll error: {}", e.getMessage());
        }
    }

    private boolean shouldIgnore(Path path) {
        for (Path segment : workspace.relativize(path)) {
            String name = segment.toString();
            if (name.startsWith(".") || EXCLUDED_DIRS.contains(name)) return true;
        }
        return false;
    }

    private synchronized void scheduleDebounce() {
        if (debounceTask != null) {
            debounceTask.cancel(false);
        }
        debounceTask = scheduler.schedule(this::flushChanges, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void flushChanges() {
        if (changedPaths.isEmpty()) return;

        Set<String> batch = new LinkedHashSet<>(changedPaths);
        changedPaths.clear();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"filer_change\",\"changes\":[");
        boolean first = true;
        for (String p : batch) {
            if (!first) sb.append(",");
            sb.append("\"").append(p.replace("\"", "\\\"")).append("\"");
            first = false;
        }
        sb.append("],\"createdAt\":").append(System.currentTimeMillis()).append("}");

        String json = sb.toString();
        webGate.broadcastRaw(json);

        if (LOG.isDebugEnabled()) {
            LOG.debug("[WorkspaceWatcher] pushed {} changes", batch.size());
        }
    }
}

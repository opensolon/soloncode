package org.noear.solon.codecli.auth;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** 跨 SolonCode 进程共享的文件会话存储；文件中只保存令牌摘要。 */
final class FileSessionStore {
    private static final ConcurrentMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();
    private final Path sessionsFile;
    private final Path lockFile;

    FileSessionStore(Path userHome) {
        this(userHome.resolve(".soloncode").resolve("auth").resolve("sessions.json"), true);
    }

    FileSessionStore(Path sessionsFile, boolean directPath) {
        this.sessionsFile = sessionsFile.toAbsolutePath().normalize();
        this.lockFile = this.sessionsFile.resolveSibling(this.sessionsFile.getFileName().toString() + ".lock");
    }

    void init() {
        withLock(() -> {
            if (Files.isSymbolicLink(sessionsFile.getParent()) || Files.isSymbolicLink(sessionsFile)
                    || Files.isSymbolicLink(lockFile)) {
                throw new IllegalStateException("共享会话路径不允许符号链接");
            }
            Files.createDirectories(sessionsFile.getParent());
            restrictOwnerOnly(sessionsFile.getParent(), true);
            restrictOwnerOnly(lockFile, false);
            if (!Files.exists(sessionsFile)) writeUnlocked(Collections.<Record>emptyList());
            return null;
        });
    }

    List<Record> read() {
        return withLock(this::readUnlocked);
    }

    void write(List<Record> records) {
        withLock(() -> {
            writeUnlocked(records);
            return null;
        });
    }

    <T> T update(Updater<T> updater) {
        return access(records -> AccessResult.changed(updater.apply(records)));
    }

    <T> T access(Accessor<T> accessor) {
        return withLock(() -> {
            List<Record> records = readUnlocked();
            AccessResult<T> result = accessor.apply(records);
            if (result.changed) writeUnlocked(records);
            return result.value;
        });
    }

    private List<Record> readUnlocked() throws IOException {
        if (!Files.exists(sessionsFile)) return new ArrayList<>();
        String json = new String(Files.readAllBytes(sessionsFile), StandardCharsets.UTF_8).trim();
        if (json.isEmpty()) return new ArrayList<>();
        ONode root;
        try {
            root = ONode.ofJson(json);
        } catch (Exception e) {
            throw new IllegalStateException("共享会话文件格式无效: " + sessionsFile, e);
        }
        if (!root.isObject() || !root.hasKey("version") || root.get("version").getInt() != 1
                || !root.hasKey("sessions") || !root.get("sessions").isArray()) {
            throw new IllegalStateException("共享会话文件格式无效: " + sessionsFile);
        }
        List<Record> result = new ArrayList<>();
        for (ONode item : root.get("sessions").getArray()) {
            if (!item.isObject()) throw new IllegalStateException("共享会话记录格式无效: " + sessionsFile);
            Record record = new Record();
            record.tokenHash = required(item, "tokenHash");
            record.userId = required(item, "userId");
            record.username = required(item, "username");
            record.displayName = item.hasKey("displayName") ? item.get("displayName").getString() : record.username;
            record.role = item.hasKey("role") ? item.get("role").getString() : "user";
            record.createdAt = item.get("createdAt").getLong();
            record.lastAccessedAt = item.get("lastAccessedAt").getLong();
            record.expiresAt = item.get("expiresAt").getLong();
            result.add(record);
        }
        return result;
    }

    private static String required(ONode item, String key) {
        String value = item.hasKey(key) ? item.get(key).getString() : null;
        if (value == null || value.isEmpty()) throw new IllegalStateException("共享会话字段缺失: " + key);
        return value;
    }

    private void writeUnlocked(List<Record> records) throws IOException {
        Files.createDirectories(sessionsFile.getParent());
        ONode root = new ONode(Options.of(Feature.Write_PrettyFormat)).asObject();
        root.set("version", 1);
        ONode array = new ONode().asArray();
        for (Record record : records) {
            ONode item = new ONode().asObject();
            item.set("tokenHash", record.tokenHash);
            item.set("userId", record.userId);
            item.set("username", record.username);
            item.set("displayName", record.displayName);
            item.set("role", record.role);
            item.set("createdAt", record.createdAt);
            item.set("lastAccessedAt", record.lastAccessedAt);
            item.set("expiresAt", record.expiresAt);
            array.add(item);
        }
        root.set("sessions", array);
        Path tmp = Files.createTempFile(sessionsFile.getParent(), "sessions-", ".tmp");
        try {
            restrictOwnerOnly(tmp, false);
            Files.write(tmp, root.toJson().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            moveAtomically(tmp, sessionsFile);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private <T> T withLock(Work<T> work) {
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(lockFile, key -> new ReentrantLock());
        jvmLock.lock();
        try {
            Files.createDirectories(lockFile.getParent());
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = acquire(channel)) {
                restrictOwnerOnly(lockFile, false);
                return work.run();
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法访问共享会话文件: " + sessionsFile, e);
        } finally {
            jvmLock.unlock();
        }
    }

    private static FileLock acquire(FileChannel channel) throws IOException {
        for (;;) {
            try {
                return channel.lock();
            } catch (OverlappingFileLockException e) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("获取共享会话锁被中断", interrupted);
                }
            }
        }
    }

    private static void restrictOwnerOnly(Path path, boolean directory) throws IOException {
        try {
            Set<PosixFilePermission> permissions = directory
                    ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (Files.exists(path)) Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows 等不支持 POSIX 权限时依赖当前用户目录 ACL。
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    interface Work<T> { T run() throws IOException; }
    interface Updater<T> { T apply(List<Record> records); }
    interface Accessor<T> { AccessResult<T> apply(List<Record> records); }

    static final class AccessResult<T> {
        final T value;
        final boolean changed;

        private AccessResult(T value, boolean changed) {
            this.value = value;
            this.changed = changed;
        }

        static <T> AccessResult<T> of(T value, boolean changed) {
            return new AccessResult<>(value, changed);
        }

        static <T> AccessResult<T> unchanged(T value) {
            return new AccessResult<>(value, false);
        }

        static <T> AccessResult<T> changed(T value) {
            return new AccessResult<>(value, true);
        }
    }

    static final class Record {
        String tokenHash;
        String userId;
        String username;
        String displayName;
        String role;
        long createdAt;
        long lastAccessedAt;
        long expiresAt;
    }
}

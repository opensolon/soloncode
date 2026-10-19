package org.noear.solon.codecli.util;

import org.noear.solon.codecli.config.AgentFlags;
import org.noear.snack4.ONode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/** 本机 Web 实例注册表。只保存端口、进程号和 attach token，不保存用户凭据。 */
public final class WebInstanceRegistry {
    private static final String PREFIX = "web-instance-";
    private static final String SUFFIX = ".json";
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebInstanceRegistry() {
    }

    public static Path fileForPort(int port) {
        return Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessHome(), PREFIX + port + SUFFIX);
    }

    public static String register(int port) {
        return register(port, generateToken());
    }
        
    /** 发布已就绪实例持有的凭据；生成 token 本身不修改注册表。 */
    public static String register(int port, String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("Web 实例 token 不能为空");
        }
        Path file = fileForPort(port);
        Path tmp = null;
        try {
            Files.createDirectories(file.getParent());
            setOwnerOnlyPermissions(file.getParent(), true);
            ONode node = new ONode()
                    .set("product", "soloncode")
                    .set("protocol", 1)
                    .set("port", port)
                    .set("pid", currentPid())
                    .set("token", token)
                    .set("startedAt", System.currentTimeMillis());
            tmp = file.resolveSibling(file.getFileName() + ".tmp-" + System.nanoTime());
            Files.write(tmp, node.toJson().getBytes(StandardCharsets.UTF_8));
            setOwnerOnlyPermissions(tmp, false);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            setOwnerOnlyPermissions(file, false);
        } catch (Exception e) {
            throw new IllegalStateException("无法写入 Web 实例注册信息: " + e.getMessage(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }
        return token;
    }

    public static String readToken(int port) {
        Path file = fileForPort(port);
        if (!Files.isRegularFile(file)) return null;
        try {
            ONode node = ONode.ofJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (!"soloncode".equals(node.get("product").getString())) return null;
            return node.get("token").getString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 仅在注册信息仍属于当前实例时删除，避免旧实例的 shutdown hook 删除新实例记录。 */
    public static void remove(int port, String expectedToken) {
        if (expectedToken == null || !expectedToken.equals(readToken(port))) return;
        try {
            Files.deleteIfExists(fileForPort(port));
        } catch (Exception ignored) {
            // 进程退出阶段不能影响服务停止
        }
    }

    /** 测试和维护用途；生产实例应使用带 token 的条件删除。 */
    public static void remove(int port) {
        try {
            Files.deleteIfExists(fileForPort(port));
        } catch (Exception ignored) {
            // 进程退出阶段不能影响服务停止
        }
    }

    /** 只清理损坏的注册文件，不根据 PID 猜测其他 JVM 是否存活。 */
    public static void cleanupStale() {
        Path dir = Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessHome());
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
            for (Path file : stream) {
                try {
                    ONode node = ONode.ofJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                    if (!"soloncode".equals(node.get("product").getString())
                            || node.get("token").getString() == null
                            || node.get("token").getString().trim().isEmpty()) {
                        Files.deleteIfExists(file);
                    }
                } catch (Exception e) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void setOwnerOnlyPermissions(Path path, boolean directory) {
        try {
            if (Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
                Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE);
                if (directory) permissions.add(PosixFilePermission.OWNER_EXECUTE);
                Files.setPosixFilePermissions(path, permissions);
            }
        } catch (Exception ignored) {
            // Windows 或不支持 POSIX 权限的文件系统由系统 ACL 管理。
        }
    }

    public static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static long currentPid() {
        String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        try {
            return Long.parseLong(at > 0 ? name.substring(0, at) : name);
        } catch (Exception e) {
            return -1L;
        }
    }
}

package org.noear.solon.codecli.auth;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** 全局认证配置与初始化状态仓库；认证配置不接受工作区覆盖。 */
public final class AuthConfigRepository {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BOOTSTRAP_TOKEN_BYTES = 32;

    private AuthConfigRepository() { }

    public static Path path() {
        return Paths.get(System.getProperty("user.home"), ".soloncode", "auth", "config.json").toAbsolutePath();
    }

    public static Path statePath() {
        return path().getParent().resolve("state.json");
    }

    private static final String SECRET_DIGEST = "ldapAdminPasswordSha256";
    private static final Set<PosixFilePermission> OWNER_DIR = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> OWNER_FILE = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static boolean warnedNonPosix;

    private static Path secretPath() {
        return path().getParent().resolve("secrets").resolve("ldap-admin-password");
    }

    /** 新文件优先；只在新文件不存在时读取全局旧 settings 中的 userAuth。 */
    public static synchronized UserAuthConfig load() throws Exception {
        Path file = path();
        UserAuthConfig result;
        if (Files.exists(file)) {
            ONode node = object(read(file), file);
            result = parseNodeWithMode(node, file);
            if (node.hasKey("ldapAdminPassword") && node.get("ldapAdminPassword").getString() != null) {
                // 旧版 config.json 中的明文优先于遗留的秘密文件；提交成功前保留原配置。
                save(result);
            } else {
                resolveSecret(node, result);
            }
        } else {
            Path legacy = file.getParent().getParent().resolve("settings.json");
            if (Files.exists(legacy)) {
                String legacyJson = read(legacy);
                ONode root;
                try {
                    root = object(legacyJson, legacy);
                } catch (RuntimeException e) {
                    if (legacyJson.contains("\"userAuth\"")) throw e;
                    return new UserAuthConfig();
                }
                if (root.hasKey("userAuth")) {
                    UserAuthConfig migrated = parseNode(root.get("userAuth"), legacy);
                    save(migrated);
                    result = migrated;
                } else {
                    result = new UserAuthConfig();
                }
            } else {
                result = new UserAuthConfig();
            }
        }
        return result;
    }

    public static synchronized void save(UserAuthConfig config) throws Exception {
        Path file = path();
        ONode previous = Files.exists(file) ? object(read(file), file) : null;
        if (previous != null) {
            parseNodeWithMode(previous, file);
            if (!previous.hasKey("ldapAdminPassword")) resolveSecret(previous, new UserAuthConfig());
        }
        String password = config.getLdapAdminPassword();
        if ((password == null || password.isEmpty()) && previous != null && previous.hasKey(SECRET_DIGEST)) {
            password = readSecret(previous.get(SECRET_DIGEST).getString());
            config.setLdapAdminPassword(password); // 表单留空：保留内存中的现有凭证。
        }
        if ("ldap".equals(config.getMode()) && config.isEnabled()
                && hasText(config.getLdapAdminDn()) && !hasText(password)) {
            throw new IllegalStateException("LDAP 管理员密码缺失，拒绝启用 LDAP");
        }
        ONode node = new ONode(Options.of(Feature.Write_PrettyFormat));
        node.fill(config);
        node.remove("ldapAdminPassword");
        if (hasText(password)) node.set(SECRET_DIGEST, sha256(password));
        else node.remove(SECRET_DIGEST);

        Files.createDirectories(file.getParent());
        Path secret = secretPath();
        Path backup = secret.resolveSibling("ldap-admin-password.backup");
        String oldPassword = previous != null && previous.hasKey(SECRET_DIGEST)
                ? readSecret(previous.get(SECRET_DIGEST).getString()) : null;
        boolean change = hasText(password) && !password.equals(oldPassword);
        boolean hadSecret = Files.exists(secret, LinkOption.NOFOLLOW_LINKS);
        if (change) {
            secureDirectory(secret.getParent());
            if (hadSecret) {
                if (Files.isSymbolicLink(secret)) throw new IllegalStateException("LDAP 秘密文件不允许符号链接");
                atomicSecret(backup, Files.readAllBytes(secret));
            }
            try {
                atomicSecret(secret, password.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                if (hadSecret) Files.deleteIfExists(backup);
                throw e;
            }
        }
        try {
            writeConfig(node, file);
        } catch (Exception e) {
            if (change) {
                try {
                    if (hadSecret) {
                        moveAtomically(backup, secret);
                    } else {
                        Files.deleteIfExists(secret);
                    }
                } catch (Exception restore) {
                    e.addSuppressed(restore); // 下次读取将依据旧摘要及备份恢复；否则失败关闭。
                }
            }
            throw e;
        }
        if (change && hadSecret) {
            try {
                Files.deleteIfExists(backup);
            } catch (Exception ignored) {
                // 配置已提交：清理失败不得冒充事务失败；备份只可由本机用户访问。
            }
        }
    }

    private static void writeConfig(ONode node, Path file) throws Exception {
        Path tmp = Files.createTempFile(file.getParent(), "config-", ".tmp");
        try {
            Files.write(tmp, node.toJson().getBytes(StandardCharsets.UTF_8));
            moveAtomically(tmp, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void secureDirectory(Path dir) throws Exception {
        if (Files.isSymbolicLink(dir)) throw new IllegalStateException("LDAP 秘密目录不允许符号链接");
        Files.createDirectories(dir);
        try {
            Files.setPosixFilePermissions(dir, OWNER_DIR);
        } catch (UnsupportedOperationException e) {
            warnNonPosix();
            if (!dir.toFile().setReadable(false, false) || !dir.toFile().setWritable(false, false)
                    || !dir.toFile().setExecutable(false, false)
                    || !dir.toFile().setReadable(true, true) || !dir.toFile().setWritable(true, true)
                    || !dir.toFile().setExecutable(true, true)) {
                throw new IllegalStateException("无法限制 LDAP 秘密目录权限", e);
            }
        }
    }

    private static void atomicSecret(Path target, byte[] bytes) throws Exception {
        Path tmp = Files.createTempFile(target.getParent(), "ldap-secret-", ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(tmp, OWNER_FILE);
            } catch (UnsupportedOperationException e) {
                warnNonPosix();
                if (!tmp.toFile().setReadable(false, false) || !tmp.toFile().setWritable(false, false)
                        || !tmp.toFile().setExecutable(false, false)
                        || !tmp.toFile().setReadable(true, true) || !tmp.toFile().setWritable(true, true)) {
                    throw new IllegalStateException("无法限制 LDAP 秘密文件权限", e);
                }
            }
            Files.write(tmp, bytes);
            moveAtomically(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void warnNonPosix() {
        if (!warnedNonPosix) {
            warnedNonPosix = true;
            System.err.println("[Auth] 文件系统不支持 POSIX 权限；LDAP 秘密文件权限仅作尽力限制，请核查本机 ACL。");
        }
    }

    private static void restrictSecretFile(Path file) throws Exception {
        try {
            Files.setPosixFilePermissions(file, OWNER_FILE);
        } catch (UnsupportedOperationException e) {
            warnNonPosix();
            if (!file.toFile().setReadable(false, false) || !file.toFile().setWritable(false, false)
                    || !file.toFile().setExecutable(false, false)
                    || !file.toFile().setReadable(true, true) || !file.toFile().setWritable(true, true)) {
                throw new IllegalStateException("无法限制 LDAP 秘密文件权限", e);
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String readSecret(String digest) throws Exception {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) throw new IllegalStateException("LDAP 秘密引用无效");
        Path secret = secretPath();
        Path backup = secret.resolveSibling("ldap-admin-password.backup");
        if (Files.isSymbolicLink(secret.getParent()) || Files.isSymbolicLink(secret) || Files.isSymbolicLink(backup))
            throw new IllegalStateException("LDAP 秘密文件不允许符号链接");
        if (Files.exists(secret) && Files.isRegularFile(secret, LinkOption.NOFOLLOW_LINKS)) {
            secureDirectory(secret.getParent());
            restrictSecretFile(secret);
            String value = read(secret);
            if (hasText(value) && digest.equals(sha256(value))) return value;
        }
        // 密码先于配置提交时若发生进程崩溃，依据旧配置摘要恢复旧密码。
        if (Files.exists(backup) && digest.equals(sha256(read(backup))) && hasText(read(backup))) {
            secureDirectory(secret.getParent());
            moveAtomically(backup, secret);
            return read(secret);
        }
        throw new IllegalStateException("LDAP 秘密文件缺失或损坏，拒绝启用 LDAP");
    }

    private static void resolveSecret(ONode node, UserAuthConfig result) throws Exception {
        if (node.hasKey(SECRET_DIGEST)) {
            result.setLdapAdminPassword(readSecret(node.get(SECRET_DIGEST).getString()));
        } else if ("ldap".equals(result.getMode()) && result.isEnabled() && hasText(result.getLdapAdminDn())) {
            throw new IllegalStateException("LDAP 管理员密码缺失，拒绝启用 LDAP");
        }
    }

    private static UserAuthConfig parseNodeWithMode(ONode node, Path file) {
        if (!node.hasKey("mode")) throw new IllegalStateException("认证模式缺失: " + file);
        return parseNode(node, file);
    }

    /** 状态文件损坏时失败关闭，而不是重新开放匿名自举。 */
    public static synchronized BootstrapState loadState() throws Exception {
        Path file = statePath();
        if (!Files.exists(file)) return new BootstrapState(false, null);
        ONode node = object(read(file), file);
        if (!node.hasKey("initialized")) throw new IllegalStateException("初始化状态缺失: " + file);
        String initialized = node.get("initialized").getString();
        if (!"true".equals(initialized) && !"false".equals(initialized)) {
            throw new IllegalStateException("初始化状态无效: " + file);
        }
        String hash = node.hasKey("bootstrapTokenHash") ? node.get("bootstrapTokenHash").getString() : null;
        if (hash != null && !hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("初始化令牌状态无效: " + file);
        }
        return new BootstrapState(Boolean.parseBoolean(initialized), hash);
    }

    /** 首次启动生成一次性明文令牌；明文只由本机日志获得，绝不通过 API 返回。 */
    public static synchronized String ensureBootstrapToken() throws Exception {
        BootstrapState state = loadState();
        if (state.initialized || state.bootstrapTokenHash != null) return null;
        byte[] bytes = new byte[BOOTSTRAP_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        writeState(new BootstrapState(false, sha256(token)));
        return token;
    }

    public static synchronized boolean verifyBootstrapToken(String token) throws Exception {
        BootstrapState state = loadState();
        return !state.initialized && state.bootstrapTokenHash != null && token != null
                && MessageDigest.isEqual(hexBytes(state.bootstrapTokenHash), sha256Bytes(token));
    }

    /** 仅未初始化且空用户实例在启动时轮换一次令牌，避免错过首次日志后无法继续安装。 */
    public static synchronized String rotateBootstrapToken() throws Exception {
        BootstrapState state = loadState();
        if (state.initialized) return null;
        byte[] bytes = new byte[BOOTSTRAP_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        writeState(new BootstrapState(false, sha256(token)));
        return token;
    }

    /** 成功完成自举后提交不可逆状态，并清除令牌摘要，避免已消费凭据继续留存。 */
    public static synchronized void markInitialized() throws Exception {
        BootstrapState state = loadState();
        if (!state.initialized) writeState(new BootstrapState(true, null));
    }

    public static final class BootstrapState {
        private final boolean initialized;
        private final String bootstrapTokenHash;
        private BootstrapState(boolean initialized, String bootstrapTokenHash) {
            this.initialized = initialized;
            this.bootstrapTokenHash = bootstrapTokenHash;
        }
        public boolean isInitialized() { return initialized; }
        public String getBootstrapTokenHash() { return bootstrapTokenHash; }
    }

    private static void writeState(BootstrapState state) throws Exception {
        Path file = statePath();
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), "state-", ".tmp");
        try {
            ONode node = new ONode(Options.of(Feature.Write_PrettyFormat));
            node.set("initialized", state.initialized);
            if (state.bootstrapTokenHash != null) node.set("bootstrapTokenHash", state.bootstrapTokenHash);
            Files.write(tmp, node.toJson().getBytes(StandardCharsets.UTF_8));
            moveAtomically(tmp, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void moveAtomically(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(String value) {
        StringBuilder result = new StringBuilder(64);
        for (byte b : sha256Bytes(value)) result.append(String.format("%02x", b));
        return result.toString();
    }

    private static byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static byte[] hexBytes(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static ONode object(String json, Path file) {
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) throw new IllegalStateException("认证配置格式无效: " + file);
        try {
            ONode node = ONode.ofJson(trimmed);
            if (!node.isObject()) throw new IllegalStateException("认证配置格式无效: " + file);
            return node;
        } catch (Exception e) {
            throw new IllegalStateException("认证配置格式无效: " + file, e);
        }
    }

    private static UserAuthConfig parse(String json, Path file) {
        ONode node = object(json, file);
        if (!node.hasKey("mode")) throw new IllegalStateException("认证模式缺失: " + file);
        return parseNode(node, file);
    }

    private static UserAuthConfig parseNode(ONode node, Path file) {
        if (!node.isObject() || !node.hasKey("enabled")) throw new IllegalStateException("认证配置格式无效: " + file);
        String enabled = node.get("enabled").getString();
        if (!"true".equals(enabled) && !"false".equals(enabled)) throw new IllegalStateException("认证开关无效: " + file);
        UserAuthConfig config = new UserAuthConfig();
        try {
            node.bindTo(config);
            if (config.getMode() == null || !("file".equals(config.getMode()) || "database".equals(config.getMode()) || "ldap".equals(config.getMode()))) throw new IllegalStateException("认证模式无效: " + file);
            config.setMode(UserStoreFactory.normalizeMode(config.getMode()));
            if (config.getSessionTimeoutMinutes() < 0 || config.getSessionTokenLength() <= 0) throw new IllegalStateException("会话配置无效: " + file);
            config.setAdminAccessMode(AccessPolicy.normalizeMode(config.getAdminAccessMode(), true));
            config.setWorkspaceAccessMode(AccessPolicy.normalizeMode(config.getWorkspaceAccessMode(), false));
            config.setAdminIpAllowlist(AccessPolicy.normalizeAllowlist(config.getAdminIpAllowlist()));
            config.setWorkspaceIpAllowlist(AccessPolicy.normalizeAllowlist(config.getWorkspaceIpAllowlist()));
            if (AccessPolicy.ALLOWLIST.equals(config.getAdminAccessMode()) && config.getAdminIpAllowlist().isEmpty()) {
                throw new IllegalStateException("管理控制台 IP 白名单不能为空: " + file);
            }
            if (AccessPolicy.ALLOWLIST.equals(config.getWorkspaceAccessMode()) && config.getWorkspaceIpAllowlist().isEmpty()) {
                throw new IllegalStateException("工作台 IP 白名单不能为空: " + file);
            }
            return config;
        } catch (Exception e) {
            throw new IllegalStateException("认证配置无效: " + file, e);
        }
    }
}

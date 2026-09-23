package org.noear.solon.codecli.auth;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.logging.Logger;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于文件存储的用户管理（默认模式）
 * 用户数据存储在 ~/.soloncode/auth/users.json
 * 
 * @author noear 2026/8/23 created
 */
public class FileUserStore implements UserStore {
    private static final String USERS_FILE = "users.json";
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PBKDF2_PREFIX = "pbkdf2-sha256";
    private static final int PBKDF2_ITERATIONS = 120000;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger LOG = Logger.getLogger(FileUserStore.class.getName());
    private final ConcurrentMap<String, UserEntity> userMap = new ConcurrentHashMap<>();
    private final Path userHome;
    private Path usersFilePath;

    public FileUserStore() {
        this(Paths.get(System.getProperty("user.home")));
    }

    public FileUserStore(Path userHome) {
        if (userHome == null) throw new IllegalArgumentException("用户目录不能为空");
        this.userHome = userHome.toAbsolutePath().normalize();
    }
    private UserAuthConfig config;
    
    @Override
    public void init(UserAuthConfig config) throws Exception {
        this.config = config;
        Path root = userHome.resolve(".soloncode").toAbsolutePath();
        this.usersFilePath = root.resolve("auth").resolve(USERS_FILE);
        userMap.clear();
        if (Files.exists(usersFilePath)) {
            loadFromFile();
        } else if (Files.exists(root.resolve(USERS_FILE))) {
            // 旧文件损坏时不得视为空存储触发 bootstrap；验证后才迁移。
            Path legacy = root.resolve(USERS_FILE);
            loadUsers(legacy);
            saveToFile();
        }

        // 有效历史用户是已初始化证据；损坏文件会在上面直接失败关闭。
        if (!userMap.isEmpty()) {
            AuthConfigRepository.markInitialized();
        } else {
            String token = AuthConfigRepository.rotateBootstrapToken();
            if (token != null) {
                LOG.warning("[SolonCode] 首次初始化令牌（仅显示本机日志一次，请立即保存）: " + token);
            }
        }
        // 全新实例保持空存储，由 /web/admin/bootstrap 引导用户自行创建首个管理员。
    }
    
    @Override
    public UserEntity authenticate(String username, String password) {
        if (username == null || password == null) return null;

        for (UserEntity user : userMap.values()) {
            if (user.getUsername().equals(username) && user.isEnabled()) {
                String storedHash = user.getPasswordHash();
                if (verifyPassword(password, storedHash)) {
                    if (isLegacySha256(storedHash)) {
                        upgradeLegacyPassword(user, password, storedHash);
                    }
                    return user;
                }
            }
        }
        return null;
    }
    
    @Override
    public UserEntity findByUsername(String username) {
        for (UserEntity user : userMap.values()) {
            if (user.getUsername().equals(username)) {
                return user;
            }
        }
        return null;
    }
    
    @Override
    public UserEntity findById(String id) {
        return userMap.get(id);
    }
    
    @Override
    public List<UserEntity> listUsers() {
        List<UserEntity> list = new ArrayList<>(userMap.values());
        list.sort(Comparator.comparing(UserEntity::getCreatedAt));
        return list;
    }
    
    @Override
    public synchronized UserEntity createUser(UserEntity user) throws Exception {
        if (findByUsername(user.getUsername()) != null) {
            throw new IllegalArgumentException("用户名已存在");
        }
        if (user.getId() == null) {
            user.setId(UUID.randomUUID().toString());
        }
        user.setCreatedAt(System.currentTimeMillis());
        user.setUpdatedAt(System.currentTimeMillis());
        userMap.put(user.getId(), user);
        try {
            saveToFile();
        } catch (Exception e) {
            userMap.remove(user.getId());
            throw e;
        }
        return user;
    }
    
    @Override
    public synchronized UserEntity updateUser(UserEntity user) throws Exception {
        UserEntity existing = userMap.get(user.getId());
        if (existing == null) {
            throw new IllegalArgumentException("用户不存在: " + user.getId());
        }
        user.setCreatedAt(existing.getCreatedAt());
        user.setUpdatedAt(System.currentTimeMillis());
        userMap.put(user.getId(), user);
        try {
            saveToFile();
        } catch (Exception e) {
            userMap.put(existing.getId(), existing);
            throw e;
        }
        return user;
    }
    
    @Override
    public synchronized void deleteUser(String id) throws Exception {
        UserEntity removed = userMap.remove(id);
        try {
            saveToFile();
        } catch (Exception e) {
            if (removed != null) userMap.put(id, removed);
            throw e;
        }
    }
    
    @Override
    public String getType() {
        return "file";
    }
    
    /** 使用随机盐和至少 120K 次迭代生成 PBKDF2 密码哈希。 */
    public static String hashPassword(String password) {
        if (password == null) throw new IllegalArgumentException("密码不能为空");
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = pbkdf2(password, salt, PBKDF2_ITERATIONS);
        return PBKDF2_PREFIX + "$" + PBKDF2_ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derived);
    }

    static boolean verifyPassword(String password, String storedHash) {
        if (password == null || storedHash == null) return false;
        if (isLegacySha256(storedHash)) {
            byte[] expected = hexToBytes(storedHash);
            byte[] actual;
            try {
                actual = MessageDigest.getInstance("SHA-256")
                        .digest(password.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 不可用", e);
            }
            return MessageDigest.isEqual(actual, expected);
        }

        String[] parts = storedHash.split("\\$", -1);
        if (parts.length != 4 || !PBKDF2_PREFIX.equals(parts[0])) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (iterations < PBKDF2_ITERATIONS || salt.length == 0 || expected.length == 0) return false;
            byte[] actual = pbkdf2(password, salt, iterations);
            return MessageDigest.isEqual(actual, expected);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private synchronized void upgradeLegacyPassword(UserEntity user, String password, String legacyHash) {
        if (!legacyHash.equals(user.getPasswordHash())) return;
        String upgraded = hashPassword(password);
        user.setPasswordHash(upgraded);
        user.setUpdatedAt(System.currentTimeMillis());
        try {
            saveToFile();
        } catch (Exception e) {
            user.setPasswordHash(legacyHash);
            throw new IllegalStateException("无法保存升级后的密码哈希", e);
        }
    }

    private static boolean isLegacySha256(String hash) {
        if (hash == null || hash.length() != 64) return false;
        for (int i = 0; i < hash.length(); i++) {
            char ch = hash.charAt(i);
            if (!(ch >= '0' && ch <= '9') && !(ch >= 'a' && ch <= 'f')
                    && !(ch >= 'A' && ch <= 'F')) return false;
        }
        return true;
    }

    private static byte[] hexToBytes(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) ((Character.digit(value.charAt(i * 2), 16) << 4)
                    | Character.digit(value.charAt(i * 2 + 1), 16));
        }
        return result;
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, PBKDF2_KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 密码哈希失败", e);
        } finally {
            spec.clearPassword();
        }
    }
    
    private void loadFromFile() throws Exception {
        loadUsers(usersFilePath);
    }

    private void loadUsers(Path file) throws Exception {
        String json = new String(Files.readAllBytes(file), "UTF-8").trim();
        if (!json.startsWith("[") || !json.endsWith("]")) {
            throw new IllegalStateException("用户文件格式无效: " + file);
        }
        ONode root = ONode.ofJson(json);
        if (!root.isArray()) throw new IllegalStateException("用户文件格式无效: " + file);
        Map<String, UserEntity> loaded = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (ONode item : root.getArray()) {
            if (!item.isObject()) throw new IllegalStateException("用户记录格式无效: " + file);
            UserEntity user = new UserEntity();
            user.setId(item.get("id").getString());
            user.setUsername(item.get("username").getString());
            user.setDisplayName(item.get("displayName").getString());
            user.setPasswordHash(item.get("passwordHash").getString());
            user.setEmail(item.get("email").getString());
            user.setRole(item.get("role").getString());
            if (!item.hasKey("enabled")) throw new IllegalStateException("用户状态缺失: " + file);
            user.setEnabled(item.get("enabled").getBoolean());
            user.setCreatedAt(item.get("createdAt").getLong());
            user.setUpdatedAt(item.get("updatedAt").getLong());
            if (user.getId() == null || user.getId().isEmpty() || user.getUsername() == null
                    || user.getUsername().isEmpty() || user.getPasswordHash() == null
                    || user.getPasswordHash().isEmpty() || !names.add(user.getUsername())
                    || loaded.put(user.getId(), user) != null) {
                throw new IllegalStateException("用户记录损坏或重复: " + file);
            }
        }
        userMap.putAll(loaded);
    }
    
    private synchronized void saveToFile() throws Exception {
        ONode arr = new ONode(Options.of(Feature.Write_PrettyFormat)).asArray();
        for (UserEntity user : userMap.values()) {
            ONode item = new ONode().asObject();
            item.set("id", user.getId());
            item.set("username", user.getUsername());
            item.set("displayName", user.getDisplayName() != null ? user.getDisplayName() : user.getUsername());
            item.set("passwordHash", user.getPasswordHash());
            item.set("email", user.getEmail());
            item.set("role", user.getRole() != null ? user.getRole() : "user");
            item.set("enabled", user.isEnabled());
            item.set("createdAt", user.getCreatedAt());
            item.set("updatedAt", user.getUpdatedAt());
            arr.add(item);
        }

        Files.createDirectories(usersFilePath.getParent());
        Path tmp = Files.createTempFile(usersFilePath.getParent(), "users-", ".tmp");
        try {
            Files.write(tmp, arr.toJson().getBytes("UTF-8"));
            Files.move(tmp, usersFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}

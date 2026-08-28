package org.noear.solon.codecli.auth;

import org.noear.snack4.ONode;
import org.noear.solon.codecli.config.AgentFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于文件存储的用户管理（默认模式）
 * 用户数据存储在 ~/.soloncode/users.json
 * 
 * @author noear 2026/8/23 created
 */
public class FileUserStore implements UserStore {
    private static final Logger LOG = LoggerFactory.getLogger(FileUserStore.class);
    
    private static final String USERS_FILE = "users.json";
    private final ConcurrentMap<String, UserEntity> userMap = new ConcurrentHashMap<>();
    private Path usersFilePath;
    private UserAuthConfig config;
    
    @Override
    public void init(UserAuthConfig config) throws Exception {
        this.config = config;
        this.usersFilePath = Paths.get(AgentFlags.getUserHome(), ".soloncode", USERS_FILE).toAbsolutePath();
        
        if (Files.exists(usersFilePath)) {
            loadFromFile();
        }
        
        // 如果没有用户，创建一个默认管理员
        if (userMap.isEmpty()) {
            UserEntity admin = new UserEntity(UUID.randomUUID().toString(), "admin", "管理员");
            admin.setPasswordHash(hashPassword("admin123"));
            admin.setRole("admin");
            admin.setEmail("admin@localhost");
            userMap.put(admin.getId(), admin);
            saveToFile();
            LOG.info("[UserStore] Created default admin user (admin/admin123)");
        }
    }
    
    @Override
    public UserEntity authenticate(String username, String password) {
        if (username == null || password == null) return null;
        
        for (UserEntity user : userMap.values()) {
            if (user.getUsername().equals(username) && user.isEnabled()) {
                String hash = hashPassword(password);
                if (hash.equals(user.getPasswordHash())) {
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
    public UserEntity createUser(UserEntity user) throws Exception {
        if (user.getId() == null) {
            user.setId(UUID.randomUUID().toString());
        }
        user.setCreatedAt(System.currentTimeMillis());
        user.setUpdatedAt(System.currentTimeMillis());
        userMap.put(user.getId(), user);
        saveToFile();
        return user;
    }
    
    @Override
    public UserEntity updateUser(UserEntity user) throws Exception {
        UserEntity existing = userMap.get(user.getId());
        if (existing == null) {
            throw new IllegalArgumentException("用户不存在: " + user.getId());
        }
        user.setCreatedAt(existing.getCreatedAt());
        user.setUpdatedAt(System.currentTimeMillis());
        userMap.put(user.getId(), user);
        saveToFile();
        return user;
    }
    
    @Override
    public void deleteUser(String id) throws Exception {
        userMap.remove(id);
        saveToFile();
    }
    
    @Override
    public String getType() {
        return "file";
    }
    
    public static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Password hashing failed", e);
        }
    }
    
    private void loadFromFile() {
        try {
            String json = new String(Files.readAllBytes(usersFilePath), "UTF-8");
            ONode root = ONode.ofJson(json);
            if (root.isArray()) {
                for (ONode item : root.getArray()) {
                    UserEntity user = new UserEntity();
                    user.setId(item.get("id").getString());
                    user.setUsername(item.get("username").getString());
                    user.setDisplayName(item.get("displayName").getString());
                    user.setPasswordHash(item.get("passwordHash").getString());
                    user.setEmail(item.get("email").getString());
                    user.setRole(item.get("role").getString());
                    user.setEnabled(item.get("enabled").getBoolean());
                    user.setCreatedAt(item.get("createdAt").getLong());
                    user.setUpdatedAt(item.get("updatedAt").getLong());
                    userMap.put(user.getId(), user);
                }
            }
        } catch (Exception e) {
            LOG.warn("[UserStore] Failed to load users file: {}", e.getMessage());
        }
    }
    
    private synchronized void saveToFile() {
        try {
            ONode root = new ONode().asObject();
            // We'll store as an array
            ONode arr = new ONode().asArray();
            for (UserEntity user : userMap.values()) {
                if ("admin".equals(user.getUsername()) && user.getPasswordHash() == null) {
                    continue; // skip incomplete admin
                }
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
            Path tmp = usersFilePath.resolveSibling(usersFilePath.getFileName() + ".tmp");
            String json = arr.toJson();
            Files.write(tmp, json.getBytes("UTF-8"));
            Files.move(tmp, usersFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOG.warn("[UserStore] Failed to save users file: {}", e.getMessage());
        }
    }
}

package org.noear.solon.codecli.auth;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
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
        
        // 全新实例保持空存储，由 /web/admin/bootstrap 引导用户自行创建首个管理员。
        // 禁止生成固定默认口令。
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
        Path tmp = usersFilePath.resolveSibling(usersFilePath.getFileName() + ".tmp");
        String json = arr.toJson();
        Files.write(tmp, json.getBytes("UTF-8"));
        Files.move(tmp, usersFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}

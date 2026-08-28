package org.noear.solon.codecli.auth;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户实体 - 表示一个系统用户
 * 
 * @author noear 2026/8/23 created
 */
public class UserEntity implements Serializable {
    private String id;
    private String username;
    private String displayName;
    private String passwordHash; // 仅 file/database 模式使用
    private String email;
    private String role; // admin, user, readonly
    private boolean enabled = true;
    private long createdAt;
    private long updatedAt;
    private Map<String, Object> attributes = new LinkedHashMap<>();
    
    public UserEntity() {
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }
    
    public UserEntity(String id, String username, String displayName) {
        this();
        this.id = id;
        this.username = username;
        this.displayName = displayName;
    }
    
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    
    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> attributes) { this.attributes = attributes; }
}

package org.noear.solon.codecli.auth;

import java.util.List;

/**
 * 用户存储接口 - 抽象不同认证模式的用户管理
 * 
 * @author noear 2026/8/23 created
 */
public interface UserStore {
    /**
     * 初始化存储
     */
    void init(UserAuthConfig config) throws Exception;
    
    /**
     * 认证用户
     * @param username 用户名
     * @param password 密码（明文）
     * @return 认证成功返回用户实体，失败返回 null
     */
    UserEntity authenticate(String username, String password);
    
    /**
     * 根据用户名查找用户
     */
    UserEntity findByUsername(String username);
    
    /**
     * 根据 ID 查找用户
     */
    UserEntity findById(String id);
    
    /**
     * 获取所有用户
     */
    List<UserEntity> listUsers();
    
    /**
     * 创建用户
     */
    UserEntity createUser(UserEntity user) throws Exception;
    
    /**
     * 更新用户
     */
    UserEntity updateUser(UserEntity user) throws Exception;
    
    /**
     * 删除用户
     */
    void deleteUser(String id) throws Exception;
    
    /**
     * 获取存储类型
     */
    String getType();
}

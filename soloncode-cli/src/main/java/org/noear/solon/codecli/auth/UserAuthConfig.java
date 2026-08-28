package org.noear.solon.codecli.auth;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * 用户认证配置 - 用于用户管理和会话隔离
 * 独立于已有的管理员密码验证（WebAuthFilter）
 * 
 * 支持两种模式：
 * - database: 通过配置的数据库连接管理用户（支持 H2 内嵌或外部 JDBC）
 * - ldap: 通过 LDAP 服务器进行用户认证
 * - file: 基于文件存储的简单用户管理（默认）
 * 
 * @author noear 2026/8/23 created
 */
@Getter
@Setter
public class UserAuthConfig implements Serializable {
    /** 认证模式: file, database, ldap */
    private String mode = "file";
    
    /** 是否启用用户认证 */
    private boolean enabled = false;
    
    // ====== 数据库配置（database 模式） ======
    /** 数据库 JDBC URL（为空时使用内嵌 H2） */
    private String dbUrl;
    /** 数据库用户名 */
    private String dbUser;
    /** 数据库密码 */
    private String dbPassword;
    /** 数据库驱动类名 */
    private String dbDriverClass;
    
    // ====== LDAP 配置（ldap 模式） ======
    /** LDAP 服务器 URL，如 ldap://localhost:389 */
    private String ldapUrl;
    /** LDAP 管理员 DN */
    private String ldapAdminDn;
    /** LDAP 管理员密码 */
    private String ldapAdminPassword;
    /** LDAP 用户搜索基 DN，如 ou=users,dc=example,dc=com */
    private String ldapBaseDn;
    /** LDAP 用户搜索过滤器，如 (uid={0}) */
    private String ldapUserFilter = "(uid={0})";
    /** LDAP 是否使用 SSL */
    private boolean ldapSsl = false;
    
    // ====== 会话配置 ======
    /** 会话超时时间（分钟） */
    private int sessionTimeoutMinutes = 60;
    /** 会话 token 长度（字节） */
    private int sessionTokenLength = 32;
}

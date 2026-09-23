package org.noear.solon.codecli.auth;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * 用户认证配置 - 用于用户管理和会话隔离。
 * 独立于已有的管理员密码验证（WebAuthFilter）。
 *
 * <p>支持两种模式：</p>
 * <ul>
 *     <li>file：基于本地文件管理用户（默认）</li>
 *     <li>ldap：通过 LDAP 目录认证用户，用户资料和角色由目录服务管理</li>
 * </ul>
 *
 * @author noear 2026/8/23 created
 */
@Getter
@Setter
public class UserAuthConfig implements Serializable {
    /** 认证模式: file, ldap */
    private String mode = "file";

    /**
     * 是否启用用户认证。
     *
     * <p>该开关由配置保存请求线程写入、由认证过滤器请求线程读取，必须保证热更新
     * 对后续请求立即可见；否则关闭认证后刷新页面仍可能读到旧值并跳转登录页。</p>
     */
    private volatile boolean enabled = false;

    // ====== LDAP 配置（ldap 模式） ======
    /** LDAP 服务器 URL，如 ldap://localhost:389 */
    private String ldapUrl;
    /** LDAP 管理员 DN；为空时使用匿名搜索 */
    private String ldapAdminDn;
    /** LDAP 管理员密码 */
    private String ldapAdminPassword;
    /** LDAP 用户搜索基 DN，如 ou=users,dc=example,dc=com */
    private String ldapBaseDn;
    /** LDAP 用户搜索过滤器，必须包含 {0} 用户名占位符 */
    private String ldapUserFilter = "(uid={0})";
    /** LDAP 是否使用 SSL */
    private boolean ldapSsl = false;
    /** 显示名称属性 */
    private String ldapDisplayNameAttribute = "displayName";
    /** 邮箱属性 */
    private String ldapEmailAttribute = "mail";
    /** 用户组属性 */
    private String ldapGroupAttribute = "memberOf";
    /** 映射为管理员角色的组 DN */
    private String ldapAdminGroupDn;
    /** LDAP 连接超时（毫秒） */
    private int ldapConnectTimeoutMillis = 5000;
    /** LDAP 读取超时（毫秒） */
    private int ldapReadTimeoutMillis = 5000;

    // ====== 会话配置 ======
    /** 会话最长有效时间（分钟） */
    private int sessionTimeoutMinutes = 60;
    /** 会话 token 长度（字节） */
    private int sessionTokenLength = 32;
}

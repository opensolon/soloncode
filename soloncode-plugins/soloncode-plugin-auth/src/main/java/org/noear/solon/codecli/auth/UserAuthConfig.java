package org.noear.solon.codecli.auth;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * 是否启用用户对话隔离。仅控制对话数据和会话流的用户归属，不影响登录认证。
     * 默认关闭，兼容旧配置并保持认证开启时的共享对话行为。
     */
    private volatile boolean conversationIsolationEnabled = false;

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
    /** 会话空闲超时时间（分钟）；0 表示一直不过期（仅受进程重启/主动撤销影响） */
    private int sessionTimeoutMinutes = 60;
    /** 会话 token 长度（字节） */
    private int sessionTokenLength = 32;

    // ====== 访问范围 ======
    /** 管理控制台访问范围：local, allowlist。默认仅限本机。 */
    private volatile String adminAccessMode = AccessPolicy.LOCAL;
    /** 管理控制台 IP 白名单。 */
    private List<String> adminIpAllowlist = new ArrayList<>();
    /** 工作台访问范围：local, allowlist, any。默认不限制来源，是否登录由认证配置决定。 */
    private volatile String workspaceAccessMode = AccessPolicy.ANY;
    /** 工作台 IP 白名单。 */
    private List<String> workspaceIpAllowlist = new ArrayList<>();
}

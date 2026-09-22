package org.noear.solon.codecli.auth;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理控制台结构性调整契约测试。
 *
 * <p>覆盖两条主线：
 * (1) 后端角色鉴权地基：UserAuthFilter 的管理员路径判定与角色判定；
 * (2) 前端结构迁移：用户管理面板从设置移至 /admin，设置面板无残留，入口按角色显隐。</p>
 */
public class AdminPanelStructureTest {

    // ==================== 阶段一：后端角色鉴权地基 ====================

    @Test
    public void adminPathPrefixesAreGated() {
        // 用户管理 API 必须纳入管理员前缀
        assertTrue(UserAuthFilter.isAdminPath("/web/settings/user-auth/users"));
        assertTrue(UserAuthFilter.isAdminPath("/web/settings/user-auth/users/create"));
        assertTrue(UserAuthFilter.isAdminPath("/web/settings/user-auth/users/delete"));
        // 面板新命名空间与页面入口
        assertTrue(UserAuthFilter.isAdminPath("/web/admin/overview"));
        assertTrue(UserAuthFilter.isAdminPath("/web/admin/modules"));
        assertTrue(UserAuthFilter.isAdminPath("/admin"));
    }

    @Test
    public void nonAdminPathsAreNotGated() {
        assertFalse(UserAuthFilter.isAdminPath("/web/chat/meta"));
        assertFalse(UserAuthFilter.isAdminPath("/web/user/me"));
        assertFalse(UserAuthFilter.isAdminPath("/web/settings/general"));
        assertFalse(UserAuthFilter.isAdminPath("/"));
        assertFalse(UserAuthFilter.isAdminPath(null));
    }

    @Test
    public void onlyAdminRolePassesRoleCheck() {
        assertTrue(UserAuthFilter.isAdminRole("admin"));
        assertFalse(UserAuthFilter.isAdminRole("user"));
        assertFalse(UserAuthFilter.isAdminRole("readonly"));
        assertFalse(UserAuthFilter.isAdminRole(null));
    }

    // ==================== 阶段三：前端结构迁移 ====================

    @Test
    public void userManagementPanelRemovedFromSettings() throws IOException {
        String web = resourceText("/static/web.html");
        // 设置面板不再保留用户管理面板正文与 tab 入口
        assertFalse(web.contains("id=\"settingsTabUsers\""),
                "web.html 不应再保留用户管理面板 DOM");
        assertFalse(web.contains("data-tab=\"users\""),
                "设置侧栏不应再有用户管理 tab 入口");
        // 设置模块懒加载列表不再引入 users 脚本
        assertFalse(web.contains("/js/app-settings-users.js"),
                "设置面板不应再加载 app-settings-users.js");
    }

    @Test
    public void adminEntryIsRoleGatedInMainUi() throws IOException {
        String web = resourceText("/static/web.html");
        // 管理控制台入口存在，且指向 /admin
        assertTrue(web.contains("id=\"adminHeaderBtn\""),
                "主界面应有管理控制台入口按钮");
        assertTrue(web.contains("href=\"/admin\""),
                "管理控制台入口应指向 /admin");
        // 仅 role=admin 显示
        assertTrue(web.contains("data.role === 'admin') ? 'flex' : 'none'"),
                "管理控制台入口必须按 role=admin 显隐");
    }

    @Test
    public void adminPageReusesUserManagementApisVerbatim() throws IOException {
        String modules = resourceText("/static/js/admin-modules.js");
        // 用户管理模块原样迁入：DOM 标识保留
        assertTrue(modules.contains("id=\"settingsTabUsers\""),
                "admin 的 users 模块应原样保留用户管理面板结构");
        assertTrue(modules.contains("userAuthEnabled"));
        assertTrue(modules.contains("userAddBtn"));
        // 复用既有交互逻辑，不改接口
        assertTrue(modules.contains("window._settingsUsers"),
                "users 模块应复用 app-settings-users.js 的交互逻辑");
    }

    @Test
    public void adminPageGuardsAndModuleRegistry() throws IOException {
        String adminJs = resourceText("/static/js/admin.js");
        // 前端准入：未启用引导、未登录跳登录、非 admin 403 提示
        assertTrue(adminJs.contains("/login'"),
                "未登录应跳转登录页（/login 路由）");
        assertTrue(adminJs.contains("d.role !== 'admin'"),
                "非管理员应被前端拦截");
        assertTrue(adminJs.contains("/web/admin/modules"),
                "应从后端模块清单取交集");

        String html = resourceText("/static/admin.html");
        assertTrue(html.contains("/js/admin-modules.js") && html.contains("/js/admin.js"),
                "admin.html 应加载模块注册表与骨架脚本");
        assertTrue(html.contains("/js/app-settings-users.js"),
                "admin.html 应加载用户管理交互逻辑（原样复用）");
    }

    // ==================== 管理后台退出登录 ====================

    @Test
    public void adminPageProvidesLogout() throws IOException {
        String html = resourceText("/static/admin.html");
        // 顶栏应有退出登录按钮
        assertTrue(html.contains("id=\"adminLogoutBtn\""),
                "admin.html 顶栏应提供退出登录按钮");

        String adminJs = resourceText("/static/js/admin.js");
        // 退出应销毁后端会话并清本地 cookie，最终回到登录页
        assertTrue(adminJs.contains("/web/user/logout"),
                "退出登录应调用后端注销接口");
        assertTrue(adminJs.contains("user_token=; path=/; max-age=0"),
                "退出登录应清除本地 user_token cookie");
        assertTrue(adminJs.contains("adminLogoutBtn"),
                "退出按钮应在 admin.js 中绑定事件");
    }

    // ==================== 实例自举：解开认证死循环 ====================

    @Test
    public void adminPageRendersBootstrapWizardWhenAuthDisabled() throws IOException {
        String adminJs = resourceText("/static/js/admin.js");
        // 认证未启用时不再指向「设置 → 用户管理」死胡同，而是渲染自举向导
        assertFalse(adminJs.contains("请先在「设置 → 用户管理」中开启"),
                "不应再把用户指回正在废弃的设置入口");
        assertTrue(adminJs.contains("renderBootstrap"),
                "认证未启用时应渲染自举向导");
        assertTrue(adminJs.contains("/web/admin/bootstrap"),
                "自举向导应提交至 /web/admin/bootstrap");
    }

    private static String resourceText(String path) throws IOException {
        try (InputStream in = AdminPanelStructureTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing resource: " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) >= 0; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

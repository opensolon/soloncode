package org.noear.solon.codecli.auth;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import java.util.Collections;
import java.util.List;
import java.util.Map;

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
        assertTrue(UserAuthFilter.isAdminPath("/web/admin/auth/config"));
        assertTrue(UserAuthFilter.isAdminPath("/web/admin/users"));
        assertTrue(UserAuthFilter.isAdminPath("/web/admin/users/create"));
        assertTrue(UserAuthFilter.isAdminPath("/web/admin/users/delete"));
        assertFalse(UserAuthFilter.isAdminPath("/web/settings/user-auth/users"));
        // 面板新命名空间与页面入口
        assertTrue(UserAuthFilter.isAdminPath("/web/admin/overview"));
        assertTrue(UserAuthFilter.isAdminPath("/web/admin/modules"));
        assertTrue(UserAuthFilter.isAdminPath("/web/admin"));
        assertFalse(UserAuthFilter.isAdminPath("/admin"),
                "旧 /admin 页面路由已移除，不能继续作为管理台入口断言");
    }

    @Test
    public void nonAdminPathsAreNotGated() {
        assertFalse(UserAuthFilter.isAdminPath("/web/chat/meta"));
        assertFalse(UserAuthFilter.isAdminPath("/web/user/me"));
        assertFalse(UserAuthFilter.isAdminPath("/web/settings/general"));
        assertFalse(UserAuthFilter.isAdminPath("/web/adminish/users"));
        assertFalse(UserAuthFilter.isAdminPath("/"));
        assertFalse(UserAuthFilter.isAdminPath(null));
    }

    @Test
    public void homePathsRequireAuthAndRedirectServerSide() {
        // 首页与 web.html 不再是公开路径：未登录时将由服务端重定向到 /login，
        // 而不是先放行主界面再由前端引导。
        assertFalse(UserAuthFilter.isPublicPath("/"),
                "首页 / 不得为公开路径，未登录时必须重定向登录页");
        assertFalse(UserAuthFilter.isPublicPath("/web.html"),
                "/web.html 不得为公开路径，未登录时必须重定向登录页");
        // 登录页自身及其静态依赖保持公开，否则登录页无法渲染
        assertTrue(UserAuthFilter.isPublicPath("/login"),
                "登录页 /login 必须公开");
        assertTrue(UserAuthFilter.isPublicPath("/css/login.css"),
                "登录页依赖的静态资源必须公开");
        assertTrue(UserAuthFilter.isPublicPath("/js/i18n.js"),
                "登录页依赖的脚本必须公开");
        assertFalse(UserAuthFilter.isPublicPath("/web/workspace/list"),
                "工作区列表接口仍须受认证保护，不得向匿名用户泄露本机路径");
    }

    @Test
    public void onlyAdminRolePassesRoleCheck() {
        assertTrue(UserAuthFilter.isAdminRole("admin"));
        assertFalse(UserAuthFilter.isAdminRole("user"));
        assertFalse(UserAuthFilter.isAdminRole("readonly"));
        assertFalse(UserAuthFilter.isAdminRole(null));
    }

    @Test
    public void authEnabledFlagIsVisibleAcrossRequestThreads() throws NoSuchFieldException {
        assertTrue(Modifier.isVolatile(UserAuthConfig.class.getDeclaredField("enabled").getModifiers()),
                "认证开关由保存请求写、过滤器请求读，必须使用 volatile 保证关闭后立即生效");
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
        assertFalse(web.contains("/js/admin-auth-users.js"),
                "设置面板不应再加载 admin-auth-users.js");
    }

    @Test
    public void adminEntryIsRoleGatedInMainUi() throws IOException {
        String web = resourceText("/static/web.html");
        // 管理控制台入口存在，且指向新的 /web/admin 路由
        assertTrue(web.contains("id=\"adminHeaderBtn\""),
                "主界面应有管理控制台入口按钮");
        assertTrue(web.contains("href=\"/web/admin\""),
                "管理控制台入口应指向 /web/admin");
        // 仅 role=admin 显示
        assertTrue(web.contains("data.role === 'admin') ? 'flex' : 'none'"),
                "管理控制台入口必须按 role=admin 显隐");
        // 工作台是所有已登录用户的默认落点，不能因管理员身份再次强制跳到管理台。
        assertFalse(web.contains("window.location.replace('/web/admin')"),
                "工作台不应根据管理员身份自动跳转到 /web/admin");
    }

    @Test
    public void adminPageReusesUserManagementApisVerbatim() throws IOException {
        String modules = resourceText("/static/js/admin-modules.js");
        // 用户管理模块 DOM 标识保留
        assertTrue(modules.contains("id=\"settingsTabUsers\""),
                "admin 的 users 模块应保留用户管理面板结构");
        // 认证开关已拆到独立的 auth 模块，但仍在 admin-modules.js 内
        assertTrue(modules.contains("userAuthEnabled"));
        assertTrue(modules.contains("userAuthConversationIsolation"),
                "认证配置应提供独立的用户对话隔离开关");
        assertTrue(modules.contains("userAddBtn"));
        // 复用既有交互逻辑，不改接口
        assertTrue(modules.contains("window._settingsUsers"),
                "模块应复用 admin-auth-users.js 的交互逻辑");
    }

    @Test
    public void authConfigIsSeparateTabFromUsers() throws IOException {
        // 认证配置独立成 tab，与用户管理平级（对齐 web.html“一个功能一个区”）。
        String modules = resourceText("/static/js/admin-modules.js");
        // auth 模块：独立面板 + 注册项 + 渲染函数
        assertTrue(modules.contains("id=\"settingsTabAuth\""),
                "认证配置应有独立面板 settingsTabAuth");
        assertTrue(modules.contains("key: 'auth'"),
                "ADMIN_MODULES 应注册独立的 auth 模块");
        assertTrue(modules.contains("function renderAuth"),
                "auth 模块应有独立渲染函数 renderAuth");
        // 认证开关/模式已不在 users 面板的列表视图中（users 面板不再包含保存配置按钮）
        int usersPanelStart = modules.indexOf("var USERS_PANEL_HTML");
        assertTrue(usersPanelStart > 0, "应保留 USERS_PANEL_HTML");
        String usersPanel = modules.substring(usersPanelStart);
        assertFalse(usersPanel.contains("userAuthSaveConfigBtn"),
                "用户管理面板不应再内嵌认证配置保存按钮");
        assertFalse(usersPanel.contains("user-auth-mode-toggle"),
                "用户管理面板不应再内嵌认证模式切换");
        // 后端模块清单含 auth，否则前端取交集会过滤掉新 tab（实例方法，不依赖容器启动）
        // modules() 不依赖任何注入字段，传 null 构造即可直接验证清单
        assertTrue(new AdminController(null, null, null).modules().getData().contains("auth"),
                "后端 /web/admin/modules 应返回 auth，否则前端取交集会过滤掉新 tab");

        // 认证开关关闭时的二次确认
        String usersJs = resourceText("/static/js/admin-auth-users.js");
        assertTrue(usersJs.contains("loadAuthConfig") && usersJs.contains("loadUsers"),
                "交互逻辑应拆为 loadAuthConfig 与 loadUsers 两个独立入口");
        assertTrue(usersJs.contains("authEnabledInitial"),
                "关闭认证需基于初始值判断是否弹二次确认");
        assertTrue(usersJs.contains("doSaveConfig"),
                "二次确认后应走独立的 doSaveConfig 落地");
        assertTrue(usersJs.contains("data.conversationIsolationEnabled === true")
                        && usersJs.contains("conversationIsolationEnabled: $('#userAuthConversationIsolation').prop('checked')"),
                "对话隔离开关必须随认证配置加载和保存");
    }

    @Test
    public void adminGateIsIndependentFromWorkbenchAuthSwitch() {
        // 管理台路径不是普通工作台公开路径；认证开关关闭也不能使管理台匿名放行。
        assertTrue(UserAuthFilter.isAdminPath("/web/admin"));
        assertTrue(UserAuthFilter.isAdminPath("/web/admin/overview"));
        assertFalse(UserAuthFilter.isPublicPath("/web/admin"));
        assertFalse(UserAuthFilter.isPublicPath("/web/admin/overview"));
    }

    @Test
    public void databaseModeIsRemovedAndLdapHasOperationalControls() throws IOException {
        String modules = resourceText("/static/js/admin-modules.js");
        String usersJs = resourceText("/static/js/admin-auth-users.js");

        assertFalse(modules.contains("data-mode=\"database\""));
        assertFalse(modules.contains("userAuthConfigDb"));
        assertFalse(modules.contains("userAuthDbUrl"));
        assertFalse(usersJs.contains("database:"));
        assertFalse(usersJs.contains("userAuthDb"));

        assertTrue(modules.contains("userAuthLdapAdminGroupDn"));
        assertTrue(modules.contains("userAuthLdapGroupAttr"));
        assertTrue(modules.contains("userAuthLdapTestBtn"));
        assertTrue(modules.contains("userAuthLdapTestResult"));
        assertTrue(usersJs.contains("/web/admin/auth/ldap/test"));
        assertTrue(usersJs.contains("ldapTestUsername"));
        assertTrue(usersJs.contains("reloginRequired"));
        assertFalse(modules.contains("value=\"readonly\""));
    }

    @Test
    public void userManagementUsesTwoLayerListAndForm() throws IOException {
        // 对齐 web.html 模型设置：列表视图与表单视图两层切换，而非同一层内嵌。
        String modules = resourceText("/static/js/admin-modules.js");
        assertTrue(modules.contains("id=\"userListView\""),
                "用户管理应有独立列表视图 userListView");
        assertTrue(modules.contains("id=\"userFormView\""),
                "用户管理应有独立表单视图 userFormView");
        assertTrue(modules.contains("settings-view-list") && modules.contains("settings-view-form"),
                "应复用模型设置的 settings-view-list / settings-view-form 两层结构");
        assertTrue(modules.contains("settings-back-btn") && modules.contains("id=\"userFormCancelBtn\""),
                "表单视图应有返回按钮");
        // 删除操作从列表行移入表单视图（仅编辑态可见）
        assertTrue(modules.contains("id=\"userFormDeleteBtn\""),
                "删除按钮应移入表单视图");

        // 认证、用户列表、用户表单均直接置于内容区，不再套 general-card 外框。
        int authStart = modules.indexOf("var AUTH_PANEL_HTML");
        int authEnd = modules.indexOf("function renderAuth", authStart);
        String authPanel = modules.substring(authStart, authEnd);
        assertFalse(authPanel.contains("general-card"),
                "认证配置不应再套 general-card 外框");

        int usersStart = modules.indexOf("var USERS_PANEL_HTML");
        int usersEnd = modules.indexOf("function renderUsers", usersStart);
        String usersPanel = modules.substring(usersStart, usersEnd);
        assertFalse(usersPanel.contains("general-card"),
                "用户列表和用户表单不应再套 general-card 外框");
        assertTrue(usersPanel.contains("admin-user-list") && usersPanel.contains("admin-flat-form admin-user-form"),
                "用户列表与表单应使用独立的扁平内容容器");
        assertTrue(usersPanel.contains("form-group"),
                "用户编辑表单应复用模型表单的字段分组");
        assertFalse(usersPanel.contains("form-row-2col"),
                "用户编辑表单应采用单列布局，不应并排展示字段");
        assertFalse(usersPanel.contains("userFormEnabled"),
                "启用状态应移至用户列表，不应继续放在编辑表单");

        String adminCss = resourceText("/static/css/admin.css");
        assertTrue(adminCss.contains(".admin-auth-config.user-auth-config-section")
                        && adminCss.contains("border: 0")
                        && adminCss.contains("background: transparent"),
                "认证模式的动态配置区也应去除内层边框和底色");
        assertTrue(adminCss.contains("--admin-content-width: 900px")
                        && adminCss.contains("align-self: center")
                        && adminCss.contains("width: var(--admin-content-width)")
                        && adminCss.contains("max-width: calc(100% - 48px)")
                        && adminCss.contains("box-sizing: border-box")
                        && adminCss.contains("margin-left: auto")
                        && adminCss.contains("margin-right: auto")
                        && adminCss.contains(".admin-content")
                        && adminCss.contains("width: 100%"),
                "admin-main 应采用固定 900px 的整体居中布局，内容区应继承其宽度");
        assertTrue(adminCss.contains(".admin-nav")
                        && adminCss.contains("background: transparent")
                        && adminCss.contains("position: static")
                        && adminCss.contains("flex: 0 0 var(--admin-nav-width)"),
                "宽屏导航应作为 900px admin-main 的内部列布局，不能移到主容器之外");
        assertTrue(adminCss.contains("@media (max-width: 1260px)")
                        && adminCss.contains("flex-direction: row")
                        && adminCss.contains("overflow-x: auto"),
                "中小屏应将侧边 tab 收为横向滚动导航，避免覆盖居中内容");
        assertTrue(adminCss.contains(".admin-nav-item.active::before")
                        && adminCss.contains("position: absolute")
                        && adminCss.contains("pointer-events: none")
                        && adminCss.contains("background: var(--bg-hover")
                        && adminCss.contains("column-gap: 32px"),
                "admin tab 的选中标记应脱离 flex 排版，切换时不得挤动图标和文字");
        assertTrue(adminCss.contains(".admin-user-list .user-list-item")
                        && adminCss.contains("margin: 0 0 8px")
                        && adminCss.contains("padding: 10px 12px")
                        && adminCss.contains("box-sizing: border-box")
                        && adminCss.contains("border: 1px solid transparent")
                        && adminCss.contains("box-shadow: 0 1px 4px rgba(0,0,0,0.04)"),
                "admin 用户列表应对齐 web.html 模型列表的圆角行、内边距和 hover 反馈");
        assertTrue(adminCss.contains(".admin-content .settings-section")
                        && adminCss.contains("box-shadow: none")
                        && adminCss.contains(".admin-content .settings-section-title"),
                "admin 内容区应统一采用设置页标题层级并移除外层视觉卡片");
        assertTrue(adminCss.contains(".admin-auth-disabled")
                        && adminCss.contains("var(--color-danger"),
                "认证关闭状态应使用可被 skin 覆盖的危险色");

        String adminJs = resourceText("/static/js/admin.js");
        assertTrue(adminJs.contains("d.bootstrapRequired === true")
                        && adminJs.contains("window.location.href = loginUrl"),
                "管理台应区分首次初始化与关闭工作台认证状态");
        assertFalse(adminJs.contains("admin-nav-footer"),
                "导航底部不应再渲染重复的管理控制台文案");

        String usersJs = resourceText("/static/js/admin-auth-users.js");
        assertTrue(usersJs.contains("#userListView") && usersJs.contains("#userFormView"),
                "交互逻辑应在列表视图与表单视图间切换");
        assertTrue(usersJs.contains("slide-back"),
                "返回列表应复用 slide 动画");
        assertTrue(usersJs.contains("#userFormPasswordRequired") && usersJs.contains("留空则不修改密码"),
                "编辑用户时密码应明确为可选，避免表单产生错误的必填感");
        assertTrue(usersJs.contains("user-enabled-toggle")
                        && usersJs.contains("/web/admin/users/toggle"),
                "用户列表应像模型列表一样提供独立的启用开关");
        assertFalse(usersJs.contains("$('#userFormEnabled')"),
                "编辑交互不应再读取或写入表单内的启用开关");
    }

    @Test
    public void adminPageGuardsAndModuleRegistry() throws IOException {
        String adminJs = resourceText("/static/js/admin.js");
        // 前端准入：未启用引导、未登录跳登录、非 admin 403 提示
        assertTrue(adminJs.contains("loginUrl") && adminJs.contains("'/login?returnUrl=%2Fweb%2Fadmin"),
                "未登录应跳转管理台登录页（/login 路由）");
        assertTrue(adminJs.contains("d.role !== 'admin'"),
                "非管理员应被前端拦截");
        assertTrue(adminJs.contains("/web/admin/modules"),
                "应从后端模块清单取交集");

        String html = resourceText("/static/admin.html");
        assertTrue(html.contains("/js/admin-modules.js") && html.contains("/js/admin.js"),
                "admin.html 应加载模块注册表与骨架脚本");
        assertTrue(html.contains("/js/admin-auth-users.js"),
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
        // 退出由统一后端接口销毁会话并清除 HttpOnly Cookie，前端不得写 token Cookie。
        assertTrue(adminJs.contains("/web/logout")
                        && !adminJs.contains("/web/admin/session/logout"),
                "管理台退出登录应调用统一注销接口，不再维护管理员专用注销接口");
        assertFalse(adminJs.contains("document.cookie"),
                "管理台前端不得读写 HttpOnly user_token Cookie");
        assertTrue(adminJs.contains("credentials: 'same-origin'"),
                "管理台注销请求必须携带同源 Cookie");
        assertTrue(adminJs.contains("adminLogoutBtn"),
                "退出按钮应在 admin.js 中绑定事件");
    }

    @Test
    public void adminSecurityContractUsesCookieCsrfAndBearerCompatibility() throws IOException {
        String login = resourceText("/static/login-page.html");
        String adminJs = resourceText("/static/js/admin.js");
        String controller = readProjectFile("src/main/java/org/noear/solon/codecli/auth/UserLoginController.java");
        String filter = readProjectFile("src/main/java/org/noear/solon/codecli/auth/UserAuthFilter.java");
        assertTrue(login.contains("var loginEndpoint = '/web/login'")
                        && !login.contains("/web/user/login")
                        && !login.contains("/web/admin/session/login"),
                "工作台和管理台应共用统一登录接口");
        assertTrue(login.contains("safeReturnUrl(returnUrl) || '/'")
                        && login.contains("window.location.replace(safeTarget);")
                        && !login.contains("resp.data.role === 'admin'")
                        && !login.contains("safeReturnUrl(returnUrl) || (adminScope ? '/web/admin' : '/')"),
                "登录未指定 returnUrl 时应默认进入工作台，管理员账号也不能固定跳回 /web/admin");
        assertFalse(adminJs.contains("document.cookie"), "管理台不得写入 token Cookie");
        assertTrue(controller.contains("@Mapping(\"/web/login\")")
                        && controller.contains("@Mapping(\"/web/logout\")")
                        && !controller.contains("/web/user/login")
                        && !controller.contains("/web/admin/session/login")
                        && !controller.contains("/web/user/logout")
                        && !controller.contains("/web/admin/session/logout"),
                "后端应只暴露一套登录和退出接口");
        assertTrue(controller.contains("HttpOnly; SameSite=Lax") && controller.contains("Max-Age=0; Path=/"),
                "登录和注销必须由服务端设置/清除安全 Cookie");
        assertTrue(filter.contains("/web/login") && filter.contains("/web/logout")
                        && !filter.contains("/web/user/login")
                        && !filter.contains("/web/admin/session/login"),
                "认证过滤器应放行统一登录/退出接口");
        assertTrue(filter.contains("isSameOrigin(ctx)") && filter.contains("Origin") && filter.contains("Referer"),
                "Cookie 管理 POST 必须校验 Origin 或严格同源 Referer");
        assertTrue(filter.contains("Bearer ") && filter.contains("X-User-Token"),
                "Bearer 和显式 X-User-Token API 请求必须保持兼容");
    }

    // ==================== 实例自举：解开认证死循环 ====================

    @Test
    public void adminPageOnlyRendersBootstrapForFreshInstance() throws Exception {
        String oldHome = System.getProperty("user.home");
        Path tempHome = Files.createTempDirectory("admin-structure-");
        try {
            System.setProperty("user.home", tempHome.toString());
            String adminJs = resourceText("/static/js/admin.js");
            assertFalse(adminJs.contains("请先在「设置 → 用户管理」中开启"),
                    "不应再把用户指回正在废弃的设置入口");
            AuthConfigRepository.ensureBootstrapToken();
            UserAuthConfig freshConfig = new UserAuthConfig();
            Map<String, Object> fresh = new UserLoginController(userStoreWithUsers(false), null, freshConfig)
                    .me(null).getData();
            Map<String, Object> configured = new UserLoginController(userStoreWithUsers(true), null, new UserAuthConfig())
                    .me(null).getData();
            UserAuthConfig ldapConfig = new UserAuthConfig();
            ldapConfig.setMode("ldap");
            Map<String, Object> ldap = new UserLoginController(userStoreWithUsers(false), null, ldapConfig)
                    .me(null).getData();
            assertTrue((Boolean) fresh.get("bootstrapRequired"), "全新文件实例应要求自举");
            assertFalse((Boolean) configured.get("bootstrapRequired"), "主动关闭认证的既有实例不应再次自举");
            assertFalse((Boolean) ldap.get("bootstrapRequired"), "LDAP 不支持创建本地用户，关闭认证后不应进入自举");
            assertTrue(adminJs.contains("d.bootstrapRequired === true") && adminJs.contains("renderBootstrap()"),
                    "只有尚无用户的全新实例才应渲染自举向导");
            assertTrue(adminJs.contains("if (!d.authenticated)")
                            && adminJs.contains("window.location.href = loginUrl"),
                    "已有用户后关闭认证，管理台仍必须要求管理员登录");
            assertTrue(adminJs.contains("/web/admin/bootstrap"),
                    "自举向导应提交至 /web/admin/bootstrap");
        } finally {
            if (oldHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", oldHome);
            deleteTree(tempHome);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
            try { Files.deleteIfExists(path); } catch (IOException ignored) { }
        });
    }

    private static String readProjectFile(String path) throws IOException {
        java.nio.file.Path direct = java.nio.file.Paths.get(path);
        if (Files.exists(direct)) {
            return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
        }
        // 认证源码已从 soloncode-auth 迁移到 soloncode-plugins/soloncode-plugin-auth，
        // 兼容按模块或按反应堆根目录运行两种执行位置。
        String[] candidates = {
                "soloncode-auth/" + path,
                "../soloncode-auth/" + path,
                "soloncode-plugins/soloncode-plugin-auth/" + path,
                "../soloncode-plugins/soloncode-plugin-auth/" + path
        };
        for (String candidate : candidates) {
            java.nio.file.Path p = java.nio.file.Paths.get(candidate);
            if (Files.exists(p)) {
                return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("Missing project file: " + path);
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

    private static UserStore userStoreWithUsers(final boolean hasUsers) {
        return new UserStore() {
            @Override public void init(UserAuthConfig config) { }
            @Override public UserEntity authenticate(String username, String password) { return null; }
            @Override public UserEntity findByUsername(String username) { return null; }
            @Override public UserEntity findById(String id) { return null; }
            @Override public List<UserEntity> listUsers() {
                return hasUsers
                        ? Collections.singletonList(new UserEntity("1", "admin", "管理员"))
                        : Collections.<UserEntity>emptyList();
            }
            @Override public UserEntity createUser(UserEntity user) { return user; }
            @Override public UserEntity updateUser(UserEntity user) { return user; }
            @Override public void deleteUser(String id) { }
            @Override public String getType() { return "test"; }
        };
    }
}

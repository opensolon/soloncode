/**
 * admin-modules.js — 管理员控制台模块注册表
 *
 * 这是控制台唯一的扩展点：未来新增管理功能，只需向 ADMIN_MODULES 追加一项
 * （key/title/icon/order/render），导航与路由会自动生成，无需改动骨架。
 *
 * 每个模块自包含：一个 render(container) 函数 + 对应的一组后端 API。
 * 后端 /web/admin/modules 返回本实例启用的模块 key，前端据此过滤显隐。
 */
(function () {
    'use strict';

    var ICON = {
        overview: '<svg class="admin-nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg>',
        auth: '<svg class="admin-nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><path d="M9 12l2 2 4-4"/></svg>',
        users: '<svg class="admin-nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>'
    };

    // ============== 概览模块 ==============
    function renderOverview(container) {
        container.innerHTML =
            '<h1 class="admin-content-title">概览</h1>' +
            '<div class="admin-content-desc">实例运行概况</div>' +
            '<div class="admin-overview-cards" id="adminOverviewCards">' +
                '<div class="admin-stat-card"><div class="admin-stat-card-label">用户总数</div><div class="admin-stat-card-value" id="ovUserCount">—</div></div>' +
                '<div class="admin-stat-card"><div class="admin-stat-card-label">活跃会话</div><div class="admin-stat-card-value" id="ovSessionCount">—</div></div>' +
                '<div class="admin-stat-card"><div class="admin-stat-card-label">认证模式</div><div class="admin-stat-card-value" id="ovAuthMode">—</div></div>' +
                '<div class="admin-stat-card"><div class="admin-stat-card-label">存储类型</div><div class="admin-stat-card-value" id="ovStoreType">—</div></div>' +
            '</div>';

        fetch('/web/admin/overview')
            .then(function (r) { return r.json(); })
            .then(function (resp) {
                if (resp.code !== 200 || !resp.data) return;
                var d = resp.data;
                setText('ovUserCount', d.userCount);
                setText('ovSessionCount', d.activeSessionCount);
                setText('ovAuthMode', d.authEnabled ? (d.authMode || 'file') : '未启用');
                setText('ovStoreType', d.storeType || 'file');
            })
            .catch(function () {});
    }

    function setText(id, val) {
        var el = document.getElementById(id);
        if (el) el.textContent = (val == null ? '—' : val);
    }

    // ============== 认证配置模块 ==============
    // 独立 tab（对齐 web.html 设置的「一个功能一个区」组织方式）。
    // 认证配置属于实例级配置（几乎一次性），与日常的用户 CRUD 分离。
    // 交互逻辑复用 app-settings-users.js（window._settingsUsers.loadAuthConfig）。
    var AUTH_PANEL_HTML =
        '<div class="settings-tab-content active" id="settingsTabAuth">' +
        '  <div class="settings-section">' +
        '    <div class="settings-section-header">' +
        '      <div>' +
        '        <span class="settings-section-title" data-i18n="users.auth.title">认证配置</span>' +
        '        <div class="settings-section-desc" data-i18n="users.auth.panelDesc">配置用户认证方式与会话策略，实现用户会话隔离</div>' +
        '      </div>' +
        '    </div>' +
        '    <div class="admin-flat-form admin-auth-form">' +
        '      <div class="admin-setting-row admin-auth-enabled-row">' +
        '        <div class="general-toggle-info"><span class="admin-setting-label">启用用户认证</span><span class="general-toggle-desc" data-i18n="users.auth.enabledDesc">启用用户认证后，用户需要登录才能使用系统</span></div>' +
        '        <label class="toggle-switch"><input type="checkbox" id="userAuthEnabled"/><span class="toggle-slider"></span></label>' +
        '      </div>' +
        '      <div class="form-group">' +
        '        <label data-i18n="users.auth.mode">认证模式</label>' +
        '        <div class="user-auth-mode-toggle">' +
        '          <button class="user-auth-mode-btn active" data-mode="file" type="button"><svg class="mode-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg> <span data-i18n="users.auth.modeFile">文件存储</span></button>' +
        '          <button class="user-auth-mode-btn" data-mode="database" type="button"><svg class="mode-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg> <span data-i18n="users.auth.modeDb">数据库</span></button>' +
        '          <button class="user-auth-mode-btn" data-mode="ldap" type="button"><svg class="mode-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/><circle cx="12" cy="16.5" r="1.5"/></svg> <span data-i18n="users.auth.modeLdap">LDAP</span></button>' +
        '        </div>' +
        '      </div>' +
        '      <div class="user-auth-config-section admin-auth-config" id="userAuthConfigFile">' +
        '        <div class="general-toggle-desc"><span data-i18n="users.auth.fileDesc">用户信息存储在本地文件中，适合单机使用</span><span data-i18n="users.auth.storeType">。当前存储：</span><span class="user-auth-store-type" id="userAuthStoreType">file</span></div>' +
        '      </div>' +
        '      <div class="user-auth-config-section admin-auth-config" id="userAuthConfigDb" style="display:none">' +
        '        <div class="form-group"><label data-i18n="users.auth.dbUrl">JDBC URL</label><input type="text" id="userAuthDbUrl" placeholder="jdbc:h2:file:~/.soloncode/users"/></div>' +
        '        <div class="form-row-2col"><div class="form-group"><label data-i18n="users.auth.dbUser">数据库用户名</label><input type="text" id="userAuthDbUser" placeholder="sa"/></div><div class="form-group"><label data-i18n="users.auth.dbPassword">数据库密码</label><input type="password" id="userAuthDbPassword" autocomplete="new-password"/></div></div>' +
        '        <div class="form-group"><label data-i18n="users.auth.dbDriver">JDBC 驱动类</label><input type="text" id="userAuthDbDriver" placeholder="org.h2.Driver"/></div>' +
        '      </div>' +
        '      <div class="user-auth-config-section admin-auth-config" id="userAuthConfigLdap" style="display:none">' +
        '        <div class="form-group"><label data-i18n="users.auth.ldapUrl">LDAP 服务器 URL <span class="required">*</span></label><input type="text" id="userAuthLdapUrl" placeholder="ldap://localhost:389"/></div>' +
        '        <div class="form-row-2col"><div class="form-group"><label data-i18n="users.auth.ldapAdminDn">管理员 DN</label><input type="text" id="userAuthLdapAdminDn" placeholder="cn=admin,dc=example,dc=com"/></div><div class="form-group"><label data-i18n="users.auth.ldapPassword">管理员密码</label><input type="password" id="userAuthLdapPassword" autocomplete="new-password"/></div></div>' +
        '        <div class="form-row-2col"><div class="form-group"><label data-i18n="users.auth.ldapBaseDn">搜索基 DN</label><input type="text" id="userAuthLdapBaseDn" placeholder="ou=users,dc=example,dc=com"/></div><div class="form-group"><label data-i18n="users.auth.ldapFilter">用户过滤器</label><input type="text" id="userAuthLdapFilter" placeholder="(uid={0})"/></div></div>' +
        '        <div class="admin-setting-row"><div class="general-toggle-info"><span class="admin-setting-label" data-i18n="users.auth.ldapSslDesc">使用 SSL 连接 LDAP 服务器</span></div><label class="toggle-switch"><input type="checkbox" id="userAuthLdapSsl"/><span class="toggle-slider"></span></label></div>' +
        '      </div>' +
        '      <div class="form-group admin-short-field"><label data-i18n="users.auth.sessionTimeout">会话超时（分钟）</label><input type="text" inputmode="numeric" id="userAuthSessionTimeout" placeholder="60"/></div>' +
        '      <div class="form-actions form-actions-end"><button class="btn-primary" id="userAuthSaveConfigBtn" data-i18n="users.auth.saveBtn">保存配置</button></div>' +
        '    </div>' +
        '  </div>' +
        '</div>';

    function renderAuth(container) {
        container.innerHTML = AUTH_PANEL_HTML;
        if (window.I18n && typeof window.I18n.apply === 'function') {
            try { window.I18n.apply(container); } catch (e) {}
        }
        if (window._settingsUsers && typeof window._settingsUsers.loadAuthConfig === 'function') {
            window._settingsUsers.loadAuthConfig();
        }
    }

    // ============== 用户管理模块 ==============
    // 两层视图（对齐 web.html 模型设置）：列表视图（用户列表）与表单视图。
    // 认证配置已拆分到独立的 auth 模块。
    // 通过 settings-view-list / settings-view-form 切换，带返回按钮与 slide 动画。
    // 交互逻辑复用 app-settings-users.js（window._settingsUsers），接口 URL 不变。
    var USERS_PANEL_HTML =
        '<div class="settings-tab-content active" id="settingsTabUsers">' +
        '  <!-- 列表视图：标题操作区 + 扁平列表，与模型设置一致 -->' +
        '  <div class="settings-view settings-view-list" id="userListView">' +
        '    <div class="settings-section admin-user-section">' +
        '      <div class="settings-section-header">' +
        '        <div>' +
        '          <span class="settings-section-title" data-i18n="users.title">用户管理</span>' +
        '          <div class="settings-section-desc" data-i18n="users.desc">管理用户账户，实现用户会话隔离</div>' +
        '        </div>' +
        '        <div id="userListActions"><button class="settings-add-btn" id="userAddBtn" data-i18n="users.list.addBtn">+ 添加用户</button></div>' +
        '      </div>' +
        '      <div class="admin-user-list" id="userList"></div>' +
        '    </div>' +
        '  </div>' +
        '  <!-- 表单视图：使用模型编辑表单相同的标题栏与单列表单节奏 -->' +
        '  <div class="settings-view settings-view-form" id="userFormView" style="display:none">' +
        '    <div class="settings-section">' +
        '      <div class="settings-section-header">' +
        '        <div class="settings-title-row">' +
        '          <button class="settings-back-btn" id="userFormCancelBtn" title="返回" data-i18n-title="common.back"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg></button>' +
        '          <span class="settings-section-title" id="userFormTitle" data-i18n="users.form.title">添加用户</span>' +
        '        </div>' +
        '        <div class="settings-form-actions" id="userFormActions" style="display:none"><button class="settings-form-action-btn danger" id="userFormDeleteBtn" title="删除" data-i18n-title="common.delete"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg> <span data-i18n="common.delete">删除</span></button></div>' +
        '      </div>' +
        '      <div class="admin-flat-form admin-user-form">' +
        '        <input type="hidden" id="userFormId"/>' +
        '        <div class="form-group"><label data-i18n="users.form.username">用户名 <span class="required">*</span></label><input type="text" id="userFormUsername" placeholder="username"/></div>' +
        '        <div class="form-group"><label data-i18n="users.form.displayName">显示名称</label><input type="text" id="userFormDisplayName" placeholder="显示名称"/></div>' +
        '        <div class="form-group"><label data-i18n="users.form.email">邮箱</label><input type="email" id="userFormEmail" placeholder="user@example.com"/></div>' +
        '        <div class="form-group"><label data-i18n="users.form.role">角色</label><select class="form-select-custom" id="userFormRole"><option value="user" data-i18n="users.role.user">普通用户</option><option value="admin" data-i18n="users.role.admin">管理员</option><option value="readonly" data-i18n="users.role.readonly">只读用户</option></select></div>' +
        '        <div class="form-group"><label data-i18n="users.form.password">密码 <span class="required" id="userFormPasswordRequired">*</span></label><input type="password" id="userFormPassword" autocomplete="new-password"/></div>' +
        '        <div class="form-actions form-actions-end"><button class="btn-primary" id="userFormSaveBtn" data-i18n="users.form.saveBtn">保存</button></div>' +
        '      </div>' +
        '    </div>' +
        '  </div>' +
        '</div>';

    function renderUsers(container) {
        container.innerHTML = USERS_PANEL_HTML;
        // 应用 i18n（若可用）
        if (window.I18n && typeof window.I18n.apply === 'function') {
            try { window.I18n.apply(container); } catch (e) {}
        }
        // 交互逻辑复用 app-settings-users.js（仅拉用户列表）
        if (window._settingsUsers && typeof window._settingsUsers.loadUsers === 'function') {
            window._settingsUsers.loadUsers();
        }
    }

    window.ADMIN_MODULES = [
        { key: 'overview', title: '概览', icon: ICON.overview, order: 0, render: renderOverview },
        { key: 'auth', title: '认证配置', icon: ICON.auth, order: 10, render: renderAuth },
        { key: 'users', title: '用户管理', icon: ICON.users, order: 20, render: renderUsers }
        // 未来新增：直接 push 一项即可，导航与路由自动生成
        // { key: 'audit', title: '审计日志', icon: ICON.audit, order: 30, render: renderAudit }
    ];
})();

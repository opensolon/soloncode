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

    // ============== 用户管理模块 ==============
    // HTML 从 web.html 的用户管理面板原样迁入（结构、id、data-i18n 全部照搬），
    // 交互逻辑复用 app-settings-users.js（window._settingsUsers），接口 URL 不变。
    var USERS_PANEL_HTML =
        '<div class="settings-tab-content active" id="settingsTabUsers">' +
        '  <div class="settings-section">' +
        '    <div class="settings-section-header">' +
        '      <div>' +
        '        <span class="settings-section-title" data-i18n="users.title">用户管理</span>' +
        '        <div class="settings-section-desc" data-i18n="users.desc">管理用户认证方式和用户账户，实现用户会话隔离</div>' +
        '      </div>' +
        '    </div>' +
        '    <div class="general-card">' +
        '      <div class="general-card-header"><div class="general-card-text"><div class="general-card-title" data-i18n="users.auth.title">认证配置</div></div></div>' +
        '      <div class="general-card-body">' +
        '        <div class="general-toggle-row">' +
        '          <div class="general-toggle-info"><span class="general-toggle-desc" data-i18n="users.auth.enabledDesc">启用用户认证后，用户需要登录才能使用系统</span></div>' +
        '          <label class="toggle-switch"><input type="checkbox" id="userAuthEnabled"/><span class="toggle-slider"></span></label>' +
        '        </div>' +
        '        <div class="settings-gap-top-md">' +
        '          <div class="general-field-label" style="margin-bottom:8px" data-i18n="users.auth.mode">认证模式</div>' +
        '          <div class="user-auth-mode-toggle">' +
        '            <button class="user-auth-mode-btn active" data-mode="file" type="button"><svg class="mode-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg> <span data-i18n="users.auth.modeFile">文件存储</span></button>' +
        '            <button class="user-auth-mode-btn" data-mode="database" type="button"><svg class="mode-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg> <span data-i18n="users.auth.modeDb">数据库</span></button>' +
        '            <button class="user-auth-mode-btn" data-mode="ldap" type="button"><svg class="mode-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/><circle cx="12" cy="16.5" r="1.5"/></svg> <span data-i18n="users.auth.modeLdap">LDAP</span></button>' +
        '          </div>' +
        '        </div>' +
        '        <div class="user-auth-config-section" id="userAuthConfigFile">' +
        '          <div class="general-toggle-desc settings-gap-top-md"><span data-i18n="users.auth.fileDesc">用户信息存储在本地文件中，适合单机使用</span><span data-i18n="users.auth.storeType">。当前存储：</span><span class="user-auth-store-type" id="userAuthStoreType">file</span></div>' +
        '        </div>' +
        '        <div class="user-auth-config-section" id="userAuthConfigDb" style="display:none">' +
        '          <div class="general-field-row settings-gap-top-md"><div class="general-field" style="flex:2"><label class="general-field-label" data-i18n="users.auth.dbUrl">JDBC URL</label><input type="text" class="general-input" id="userAuthDbUrl" placeholder="jdbc:h2:file:~/.soloncode/users"/></div></div>' +
        '          <div class="general-field-row"><div class="general-field" style="flex:1"><label class="general-field-label" data-i18n="users.auth.dbUser">数据库用户名</label><input type="text" class="general-input" id="userAuthDbUser" placeholder="sa"/></div><div class="general-field" style="flex:1"><label class="general-field-label" data-i18n="users.auth.dbPassword">数据库密码</label><input type="password" class="general-input" id="userAuthDbPassword" autocomplete="new-password"/></div></div>' +
        '          <div class="general-field-row"><div class="general-field" style="flex:2"><label class="general-field-label" data-i18n="users.auth.dbDriver">JDBC 驱动类</label><input type="text" class="general-input" id="userAuthDbDriver" placeholder="org.h2.Driver"/></div></div>' +
        '        </div>' +
        '        <div class="user-auth-config-section" id="userAuthConfigLdap" style="display:none">' +
        '          <div class="general-field-row settings-gap-top-md"><div class="general-field" style="flex:2"><label class="general-field-label" data-i18n="users.auth.ldapUrl">LDAP 服务器 URL <span class="required">*</span></label><input type="text" class="general-input" id="userAuthLdapUrl" placeholder="ldap://localhost:389"/></div></div>' +
        '          <div class="general-field-row"><div class="general-field" style="flex:1"><label class="general-field-label" data-i18n="users.auth.ldapAdminDn">管理员 DN</label><input type="text" class="general-input" id="userAuthLdapAdminDn" placeholder="cn=admin,dc=example,dc=com"/></div><div class="general-field" style="flex:1"><label class="general-field-label" data-i18n="users.auth.ldapPassword">管理员密码</label><input type="password" class="general-input" id="userAuthLdapPassword" autocomplete="new-password"/></div></div>' +
        '          <div class="general-field-row"><div class="general-field" style="flex:1"><label class="general-field-label" data-i18n="users.auth.ldapBaseDn">搜索基 DN</label><input type="text" class="general-input" id="userAuthLdapBaseDn" placeholder="ou=users,dc=example,dc=com"/></div><div class="general-field" style="flex:1"><label class="general-field-label" data-i18n="users.auth.ldapFilter">用户过滤器</label><input type="text" class="general-input" id="userAuthLdapFilter" placeholder="(uid={0})"/></div></div>' +
        '          <div class="general-toggle-row settings-gap-top-md"><div class="general-toggle-info"><span class="general-toggle-desc" data-i18n="users.auth.ldapSslDesc">使用 SSL 连接 LDAP 服务器</span></div><label class="toggle-switch"><input type="checkbox" id="userAuthLdapSsl"/><span class="toggle-slider"></span></label></div>' +
        '        </div>' +
        '        <div class="general-field-row settings-gap-top-md"><div class="general-field" style="flex:1;max-width:200px"><label class="general-field-label" data-i18n="users.auth.sessionTimeout">会话超时（分钟）</label><input type="text" inputmode="numeric" class="general-input" id="userAuthSessionTimeout" placeholder="60"/></div></div>' +
        '        <div class="form-actions settings-gap-top-md"><button class="btn-primary" id="userAuthSaveConfigBtn" data-i18n="users.auth.saveBtn">保存配置</button></div>' +
        '      </div>' +
        '    </div>' +
        '    <div class="general-card settings-gap-top-md">' +
        '      <div class="general-card-header"><div class="general-card-text"><div class="general-card-title" data-i18n="users.list.title">用户列表</div></div><div class="openapi-header-actions" id="userListActions"><button class="settings-add-btn" id="userAddBtn" data-i18n="users.list.addBtn">+ 添加用户</button></div></div>' +
        '      <div class="general-card-body">' +
        '        <div id="userList"></div>' +
        '        <div id="userForm" style="display:none">' +
        '          <div class="settings-section">' +
        '            <div class="settings-section-header"><div><span class="settings-section-title" id="userFormTitle" data-i18n="users.form.title">添加用户</span></div></div>' +
        '            <input type="hidden" id="userFormId"/>' +
        '            <div class="general-field-row"><div class="general-field" style="flex:1"><label class="general-field-label" data-i18n="users.form.username">用户名 <span class="required">*</span></label><input type="text" class="general-input" id="userFormUsername" placeholder="username"/></div><div class="general-field" style="flex:1"><label class="general-field-label" data-i18n="users.form.displayName">显示名称</label><input type="text" class="general-input" id="userFormDisplayName" placeholder="显示名称"/></div></div>' +
        '            <div class="general-field-row"><div class="general-field" style="flex:1"><label class="general-field-label" data-i18n="users.form.email">邮箱</label><input type="text" class="general-input" id="userFormEmail" placeholder="user@example.com"/></div><div class="general-field" style="flex:1"><label class="general-field-label" data-i18n="users.form.password">密码 <span class="required" id="userFormPasswordRequired">*</span></label><input type="password" class="general-input" id="userFormPassword" autocomplete="new-password"/></div></div>' +
        '            <div class="general-field-row"><div class="general-field" style="flex:1"><label class="general-field-label" data-i18n="users.form.role">角色</label><select class="form-select-custom" id="userFormRole"><option value="user" data-i18n="users.role.user">普通用户</option><option value="admin" data-i18n="users.role.admin">管理员</option><option value="readonly" data-i18n="users.role.readonly">只读用户</option></select></div><div class="general-field" style="flex:1;display:flex;align-items:flex-end;padding-bottom:8px"><label class="toggle-label"><span data-i18n="users.form.enabled">启用</span><label class="toggle-switch" style="margin-left:8px"><input type="checkbox" id="userFormEnabled" checked/><span class="toggle-slider"></span></label></label></div></div>' +
        '            <div class="form-actions"><button class="btn-secondary" id="userFormCancelBtn" data-i18n="common.cancel">取消</button><button class="btn-primary" id="userFormSaveBtn" data-i18n="users.form.saveBtn">保存</button></div>' +
        '          </div>' +
        '        </div>' +
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
        // 交互逻辑复用 app-settings-users.js
        if (window._settingsUsers && typeof window._settingsUsers.load === 'function') {
            window._settingsUsers.load();
        }
    }

    window.ADMIN_MODULES = [
        { key: 'overview', title: '概览', icon: ICON.overview, order: 0, render: renderOverview },
        { key: 'users', title: '用户管理', icon: ICON.users, order: 10, render: renderUsers }
        // 未来新增：直接 push 一项即可，导航与路由自动生成
        // { key: 'audit', title: '审计日志', icon: ICON.audit, order: 20, render: renderAudit }
    ];
})();

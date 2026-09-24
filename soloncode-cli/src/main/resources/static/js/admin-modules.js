/**
 * admin-modules.js — 管理员控制台模块注册表
 *
 * 这是控制台唯一的扩展点：未来新增管理功能，只需向 ADMIN_MODULES 追加一项
 * （key/title/icon/order/render），导航与路由会自动生成，无需改动骨架。
 *
 * 每个模块自包含：一个 render(container) 函数 + 对应的一组后端 API。
 * 后端 /web/admin/modules 返回本实例启用的模块 key，前端据此过滤显隐；
 * 审计与会话模块即使暂时不出现在导航中，也保留实现供后续重新开放。
 */
(function () {
    'use strict';

    var ICON = {
        auth: '<svg class="admin-nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><path d="M9 12l2 2 4-4"/></svg>',
        users: '<svg class="admin-nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>',
        audit: '<svg class="admin-nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 3h9l4 4v14H3V3h5z"/><path d="M17 3v5h4M7 12h10M7 16h10"/></svg>',
        sessions: '<svg class="admin-nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 9h18M8 14h4"/></svg>'
    };

    // ============== 认证配置模块 ==============
    // 独立 tab（对齐 web.html 设置的「一个功能一个区」组织方式）。
    // 认证配置属于实例级配置（几乎一次性），与日常的用户 CRUD 分离。
    // 交互逻辑复用 admin-auth-users.js（window._settingsUsers.loadAuthConfig）。
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
        '        <div class="general-toggle-info"><span class="admin-setting-label" data-i18n="users.auth.enabled">启用用户认证</span><span class="general-toggle-desc" data-i18n="users.auth.enabledDesc">启用用户认证后，用户需要登录才能使用系统</span></div>' +
        '        <label class="toggle-switch"><input type="checkbox" id="userAuthEnabled"/><span class="toggle-slider"></span></label>' +
        '      </div>' +
        '      <div class="admin-setting-row">' +
        '        <div class="general-toggle-info"><span class="admin-setting-label" data-i18n="users.auth.conversationIsolation">用户对话隔离</span><span class="general-toggle-desc" data-i18n="users.auth.conversationIsolationDesc">启用后，不同登录用户只能查看和使用自己的对话</span></div>' +
        '        <label class="toggle-switch"><input type="checkbox" id="userAuthConversationIsolation"/><span class="toggle-slider"></span></label>' +
        '      </div>' +
        '      <div class="form-group">' +
        '        <label data-i18n="users.auth.mode">认证模式</label>' +
        '        <div class="user-auth-mode-toggle">' +
        '          <button class="user-auth-mode-btn active" data-mode="file" type="button"><svg class="mode-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg> <span data-i18n="users.auth.modeFile">文件存储</span></button>' +
        '          <button class="user-auth-mode-btn" data-mode="ldap" type="button"><svg class="mode-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/><circle cx="12" cy="16.5" r="1.5"/></svg> <span data-i18n="users.auth.modeLdap">LDAP</span></button>' +
        '        </div>' +
        '      </div>' +
        '      <div class="user-auth-config-section admin-auth-config" id="userAuthConfigFile">' +
        '        <div class="general-toggle-desc"><span data-i18n="users.auth.fileDesc">用户信息存储在本地文件中，适合单机使用</span><span data-i18n="users.auth.storeType">。当前存储：</span><span class="user-auth-store-type" id="userAuthStoreType">file</span></div>' +
        '      </div>' +
        '      <div class="user-auth-config-section admin-auth-config" id="userAuthConfigLdap" style="display:none">' +
        '        <div class="admin-ldap-notice">LDAP 用户、密码和组关系由目录服务统一管理。启用前必须用管理员组成员完成测试。</div>' +
        '        <div class="form-group"><label>LDAP 服务器 URL <span class="required">*</span></label><input type="text" id="userAuthLdapUrl" placeholder="ldap://localhost:389"/></div>' +
        '        <div class="form-row-2col"><div class="form-group"><label>目录管理 DN</label><input type="text" id="userAuthLdapAdminDn" placeholder="cn=admin,dc=example,dc=com"/></div><div class="form-group"><label>目录管理密码</label><input type="password" id="userAuthLdapPassword" autocomplete="new-password" placeholder="留空则保留原密码"/></div></div>' +
        '        <div class="form-row-2col"><div class="form-group"><label>搜索基 DN <span class="required">*</span></label><input type="text" id="userAuthLdapBaseDn" placeholder="ou=users,dc=example,dc=com"/></div><div class="form-group"><label>用户过滤器 <span class="required">*</span></label><input type="text" id="userAuthLdapFilter" placeholder="(uid={0})"/></div></div>' +
        '        <div class="form-row-2col"><div class="form-group"><label>显示名称属性</label><input type="text" id="userAuthLdapDisplayAttr" placeholder="displayName"/></div><div class="form-group"><label>邮箱属性</label><input type="text" id="userAuthLdapEmailAttr" placeholder="mail"/></div></div>' +
        '        <div class="form-row-2col"><div class="form-group"><label>用户组属性</label><input type="text" id="userAuthLdapGroupAttr" placeholder="memberOf"/></div><div class="form-group"><label>管理员组 DN <span class="required">*</span></label><input type="text" id="userAuthLdapAdminGroupDn" placeholder="cn=soloncode-admins,ou=groups,dc=example,dc=com"/></div></div>' +
        '        <div class="form-row-2col"><div class="form-group"><label>连接超时（毫秒）</label><input type="text" inputmode="numeric" id="userAuthLdapConnectTimeout" placeholder="5000"/></div><div class="form-group"><label>读取超时（毫秒）</label><input type="text" inputmode="numeric" id="userAuthLdapReadTimeout" placeholder="5000"/></div></div>' +
        '        <div class="admin-setting-row"><div class="general-toggle-info"><span class="admin-setting-label">使用 SSL 连接 LDAP 服务器</span></div><label class="toggle-switch"><input type="checkbox" id="userAuthLdapSsl"/><span class="toggle-slider"></span></label></div>' +
        '        <div class="admin-ldap-test">' +
        '          <div class="form-row-2col"><div class="form-group"><label>测试用户名</label><input type="text" id="userAuthLdapTestUsername" autocomplete="username" placeholder="LDAP 管理员组成员"/></div><div class="form-group"><label>测试用户密码</label><input type="password" id="userAuthLdapTestPassword" autocomplete="current-password"/></div></div>' +
        '          <div class="admin-ldap-test-actions"><button type="button" class="btn-secondary" id="userAuthLdapTestBtn">测试连接与角色</button><span class="admin-ldap-test-result" id="userAuthLdapTestResult"></span></div>' +
        '        </div>' +
        '      </div>' +
        '      <div class="form-group admin-short-field"><label>会话最长有效期（分钟）</label><input type="text" inputmode="numeric" id="userAuthSessionTimeout" placeholder="60"/></div>' +
        '      <div class="form-actions form-actions-end"><button type="button" class="btn-primary" id="userAuthSaveConfigBtn" data-i18n="users.auth.saveBtn">保存配置</button></div>' +
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
    // 交互逻辑复用 admin-auth-users.js（window._settingsUsers），接口 URL 不变。
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
        '        <div class="form-group"><label data-i18n="users.form.role">角色</label><select class="form-select-custom" id="userFormRole"><option value="user" data-i18n="users.role.user">普通用户</option><option value="admin" data-i18n="users.role.admin">管理员</option></select></div>' +
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
        // 交互逻辑复用 admin-auth-users.js（仅拉用户列表）
        if (window._settingsUsers && typeof window._settingsUsers.loadUsers === 'function') {
            window._settingsUsers.loadUsers();
        }
    }

    // ============== 审计与会话模块 ==============
    // 只有 /web/admin/modules 显式启用对应 key 时才渲染并请求接口。
    function adminElement(tag, className, text) {
        var el = document.createElement(tag);
        if (className) el.className = className;
        if (text != null) el.textContent = String(text);
        return el;
    }

    function adminButton(text, onClick) {
        var btn = adminElement('button', 'btn-secondary', text);
        btn.type = 'button';
        btn.addEventListener('click', onClick);
        return btn;
    }

    function adminPanel(container, title, description) {
        var root = adminElement('div', 'admin-data-panel');
        root.appendChild(adminElement('h1', 'admin-content-title', title));
        root.appendChild(adminElement('div', 'admin-content-desc', description));
        container.replaceChildren(root);
        return root;
    }

    function adminStatus(root, message, error) {
        var status = root.querySelector('.admin-data-status');
        status.textContent = message || '';
        status.classList.toggle('admin-inline-error', !!error);
    }

    function adminPage(root, data, load) {
        var pager = root.querySelector('.admin-data-pager');
        pager.replaceChildren();
        var page = Number(data.page);
        var size = Number(data.pageSize);
        var total = Number(data.total);
        if (!Number.isSafeInteger(page) || page < 1 || !Number.isSafeInteger(size) || size < 1 ||
                !Number.isSafeInteger(total) || total < 0) throw new Error('Invalid pagination');
        var pages = Math.max(1, Math.ceil(total / size));
        var prev = adminButton('上一页', function () { load(page - 1); });
        prev.disabled = page <= 1;
        var next = adminButton('下一页', function () { load(page + 1); });
        next.disabled = page >= pages;
        pager.appendChild(prev);
        pager.appendChild(adminElement('span', 'admin-data-page-label', '第 ' + page + ' / ' + pages + ' 页 · 共 ' + total + ' 条'));
        pager.appendChild(next);
    }

    function adminRows(root, items, columns, actions) {
        var list = root.querySelector('.admin-data-list');
        list.replaceChildren();
        if (!items.length) {
            list.appendChild(adminElement('div', 'admin-data-empty', '暂无记录'));
            return;
        }
        items.forEach(function (item) {
            var row = adminElement('div', 'admin-data-row');
            columns.forEach(function (column) {
                var cell = adminElement('div', 'admin-data-cell');
                cell.appendChild(adminElement('span', 'admin-data-label', column[0]));
                var value = item[column[1]];
                cell.appendChild(adminElement('span', 'admin-data-value', value == null || value === '' ? '—' : value));
                row.appendChild(cell);
            });
            if (actions) actions(row, item);
            list.appendChild(row);
        });
    }

    function adminRequest(url, options) {
        return fetch(url, options).then(function (response) {
            if (window.adminJsonResponse) return window.adminJsonResponse(response);
            if (response.status === 401) {
                window.location.replace('/login?returnUrl=%2Fweb%2Fadmin');
                throw new Error('401');
            }
            if (response.status === 403) throw new Error('403');
            if (!response.ok) throw new Error('HTTP ' + response.status);
            return response.json();
        }).then(function (response) {
            if (!response || response.code !== 200) throw new Error('Request failed');
            return response.data;
        });
    }

    function renderAudit(container) {
        var root = adminPanel(container, '审计日志', '查询管理操作记录');
        var form = adminElement('form', 'admin-data-filter');
        var eventInput = adminElement('input');
        eventInput.type = 'search';
        eventInput.placeholder = '事件名称（精确匹配）';
        eventInput.setAttribute('aria-label', '事件名称');
        form.appendChild(eventInput);
        var search = adminElement('button', 'btn-secondary', '查询');
        search.type = 'submit';
        form.appendChild(search);
        root.appendChild(form);
        root.appendChild(adminElement('div', 'admin-data-status'));
        root.appendChild(adminElement('div', 'admin-data-list'));
        root.appendChild(adminElement('div', 'admin-data-pager'));
        var event = '';
        var sequence = 0;
        function load(page) {
            var current = ++sequence;
            adminStatus(root, '正在加载…');
            adminRequest('/web/admin/audit?page=' + page + '&pageSize=20' + (event ? '&event=' + encodeURIComponent(event) : ''))
                .then(function (data) {
                    if (!root.isConnected || current !== sequence) return;
                    if (!data || !Array.isArray(data.items)) throw new Error('Invalid audit response');
                    adminPage(root, data, load);
                    adminRows(root, data.items, [['时间', 'timestamp'], ['事件', 'event'], ['用户名', 'username'], ['用户 ID', 'userId']]);
                    adminStatus(root, '');
                }).catch(function (error) {
                    if (!root.isConnected || current !== sequence || error.message === '401' || error.message === '403') return;
                    root.querySelector('.admin-data-list').replaceChildren();
                    root.querySelector('.admin-data-pager').replaceChildren();
                    adminStatus(root, '审计日志加载失败，请重试。', true);
                });
        }
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            event = eventInput.value.trim();
            load(1);
        });
        load(1);
    }

    function renderSessions(container) {
        var root = adminPanel(container, '会话管理', '查询活跃会话并撤销指定会话或用户的全部会话');
        root.appendChild(adminElement('div', 'admin-data-status'));
        root.appendChild(adminButton('刷新', function () { load(page); }));
        root.appendChild(adminElement('div', 'admin-data-list'));
        root.appendChild(adminElement('div', 'admin-data-pager'));
        var page = 1;
        var sequence = 0;
        var busy = false;
        function load(targetPage) {
            var current = ++sequence;
            adminStatus(root, '正在加载…');
            adminRequest('/web/admin/sessions?page=' + targetPage + '&pageSize=20')
                .then(function (data) {
                    if (!root.isConnected || current !== sequence) return;
                    if (!data || !Array.isArray(data.items)) throw new Error('Invalid sessions response');
                    adminPage(root, data, load);
                    page = targetPage;
                    adminRows(root, data.items, [['用户名', 'username'], ['创建时间', 'createdAt'], ['过期时间', 'expiresAt']], function (row, item) {
                        var actions = adminElement('div', 'admin-data-actions');
                        if (typeof item.id === 'string' && item.id) {
                            actions.appendChild(adminButton('撤销会话', function () {
                                revoke('/web/admin/sessions/revoke', { id: item.id }, '确定撤销该会话？');
                            }));
                        }
                        if (typeof item.userId === 'string' && item.userId) {
                            actions.appendChild(adminButton('撤销该用户全部会话', function () {
                                revoke('/web/admin/sessions/revoke-user', { userId: item.userId }, '确定撤销该用户的全部会话？');
                            }));
                        }
                        row.appendChild(actions);
                    });
                    adminStatus(root, '');
                }).catch(function (error) {
                    if (!root.isConnected || current !== sequence || error.message === '401' || error.message === '403') return;
                    root.querySelector('.admin-data-list').replaceChildren();
                    root.querySelector('.admin-data-pager').replaceChildren();
                    adminStatus(root, '会话加载失败，请重试。', true);
                });
        }
        function revoke(url, body, prompt) {
            if (busy || !window.confirm(prompt)) return;
            busy = true;
            adminStatus(root, '正在撤销…');
            adminRequest(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
                .then(function () { if (root.isConnected) load(page); })
                .catch(function (error) {
                    if (root.isConnected && error.message !== '401' && error.message !== '403') {
                        adminStatus(root, '撤销失败，请重试。', true);
                    }
                }).then(function () { busy = false; });
        }
        load(1);
    }

    // 导航仅保留「认证配置 → 用户管理」两项（顺序由 order 决定）。
    // 审计与会话模块保留渲染实现，供后续重新开放，但不参与当前导航（后端 modules 不再返回其 key）。
    window.ADMIN_MODULES = [
        { key: 'auth', title: '认证配置', icon: ICON.auth, order: 10, render: renderAuth },
        { key: 'users', title: '用户管理', icon: ICON.users, order: 20, render: renderUsers },
        { key: 'audit', title: '审计日志', icon: ICON.audit, order: 30, render: renderAudit },
        { key: 'sessions', title: '会话管理', icon: ICON.sessions, order: 40, render: renderSessions }
    ];
})();

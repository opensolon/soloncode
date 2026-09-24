/**
 * admin.js — 管理员控制台骨架
 *
 * 职责：
 *  1. 前端准入检查（体验层）：未登录跳登录页；非 admin 显示 403 提示。
 *     认证未启用且尚无用户时显示「自举向导」；已有用户后主动关闭认证时，
 *     允许匿名进入管理台，避免刷新后再次落入登录/初始化流程。
 *     真正的拦截由后端 UserAuthFilter 的 403 负责，此处仅优化体验。
 *  2. 从 ADMIN_MODULES（admin-modules.js）与后端 /web/admin/modules 取交集，生成导航。
 *  3. hash 路由（/web/admin#users）驱动模块 render。
 */
(function () {
    'use strict';

    var navEl = document.getElementById('adminNav');
    var contentEl = document.getElementById('adminContent');
    var userInfoEl = document.getElementById('adminUserInfo');
    var logoutBtn = document.getElementById('adminLogoutBtn');

    var modules = [];      // 最终启用并排序后的模块
    var moduleMap = {};
    // 进入管理台时保留来源页；退出后回到工作台，不把管理台地址带回登录流程。
    var loginUrl = '/login?returnUrl=%2Fweb%2Fadmin';
    var workbenchLoginUrl = '/login';
    // 审计日志与会话撤销仍保留在模块注册表中，待后续开放前端入口；
    // 这里仅控制当前导航显隐，不影响后端写入、查询和撤销实现。
    var hiddenModuleKeys = { audit: true, sessions: true };

    function jsonResponse(r) {
        if (r.status === 401) { window.location.replace(loginUrl); throw new Error('401'); }
        if (r.status === 403) { showGuard('无访问权限', '当前账户没有管理权限。', '返回工作台', '/'); throw new Error('403'); }
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
    }
    window.adminJsonResponse = jsonResponse;

    function roleLabel(role) {
        return role === 'admin' ? '管理员' : '普通用户';
    }

    // 退出登录：统一接口销毁当前会话，并通过 Set-Cookie 清除 HttpOnly Cookie。
    function doLogout() {
        if (!window.confirm('确定要退出登录吗？')) return;
        fetch('/web/logout', { method: 'POST', credentials: 'same-origin' })
            .then(function () { window.location.replace(workbenchLoginUrl); })
            .catch(function () { window.location.replace(workbenchLoginUrl); });
    }

    if (logoutBtn) {
        logoutBtn.addEventListener('click', doLogout);
    }

    function showGuard(title, desc, btnText, btnHref) {
        navEl.replaceChildren();
        var state = document.createElement('div');
        state.className = 'admin-guard-state';
        var heading = document.createElement('h2');
        heading.textContent = title;
        var message = document.createElement('p');
        message.textContent = desc;
        state.appendChild(heading);
        state.appendChild(message);
        if (btnHref) {
            var link = document.createElement('a');
            link.className = 'admin-topbar-action admin-topbar-workbench';
            link.href = btnHref;
            link.textContent = btnText;
            state.appendChild(link);
        }
        contentEl.replaceChildren(state);
    }
    window.showAdminGuard = showGuard;

    // ============== 实例自举向导 ==============
    // 认证未启用时，系统里还没有用户/角色体系。“是否启用认证”本质是实例级
    // 自举决策，而非普通用户设置，故入口归到 admin，一步完成启用 + 创建首个管理员。
    function renderBootstrap() {
        // 独立的全屏居中初始化页：隐藏导航骨架与顶栏，
        // 让用户直接完成管理员初始化。
        document.body.classList.add('admin-setup-mode');
        contentEl.innerHTML =
            '<div class="admin-setup">' +
            '  <div class="admin-setup-card">' +
            '    <div class="admin-setup-head">' +
            '      <h1 class="admin-setup-title">初始化用户体系</h1>' +
            '    </div>' +
            '    <div class="admin-setup-body">' +
            '      <div class="general-field"><label class="general-field-label">管理员用户名 <span class="required">*</span></label><input type="text" class="general-input" id="bsUsername" value="admin" placeholder="admin"/></div>' +
            '      <div class="general-field"><label class="general-field-label">显示名称</label><input type="text" class="general-input" id="bsDisplayName" placeholder="管理员"/></div>' +
            '      <div class="general-field"><label class="general-field-label">邮箱</label><input type="email" class="general-input" id="bsEmail" placeholder="admin@localhost"/></div>' +
            '      <div class="general-field"><label class="general-field-label">密码 <span class="required">*</span></label><input type="password" class="general-input" id="bsPassword" autocomplete="new-password"/></div>' +
            '      <div class="general-field"><label class="general-field-label">确认密码 <span class="required">*</span></label><input type="password" class="general-input" id="bsPassword2" autocomplete="new-password"/></div>' +
            '      <div class="admin-setup-error" id="bsError" style="display:none"></div>' +
            '      <button class="btn-primary admin-setup-submit" id="bsSubmitBtn">启用并创建管理员</button>' +
            '    </div>' +
            '  </div>' +
            '</div>';

        var submitBtn = document.getElementById('bsSubmitBtn');
        submitBtn.addEventListener('click', submitBootstrap);
    }

    function submitBootstrap() {
        var errEl = document.getElementById('bsError');
        function fail(msg) {
            if (errEl) { errEl.textContent = msg; errEl.style.display = 'block'; }
        }

        var username = (document.getElementById('bsUsername').value || '').trim();
        var displayName = (document.getElementById('bsDisplayName').value || '').trim();
        var email = (document.getElementById('bsEmail').value || '').trim();
        var password = document.getElementById('bsPassword').value || '';
        var password2 = document.getElementById('bsPassword2').value || '';
        if (!username) { fail('管理员用户名不能为空'); return; }
        if (!password) { fail('密码不能为空'); return; }
        if (password !== password2) { fail('两次输入的密码不一致'); return; }

        var btn = document.getElementById('bsSubmitBtn');
        if (btn) { btn.disabled = true; btn.textContent = '正在初始化...'; }

        fetch('/web/admin/bootstrap', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: username, displayName: displayName, email: email, password: password })
        })
            .then(jsonResponse)
            .then(function (resp) {
                if (resp && resp.code === 200) {
                    // 开启成功：此后访问需登录，跳登录页
                    window.location.href = loginUrl;
                } else {
                    if (btn) { btn.disabled = false; btn.textContent = '启用并创建管理员'; }
                    fail((resp && (resp.description || resp.message)) || '初始化失败');
                }
            })
            .catch(function () {
                if (btn) { btn.disabled = false; btn.textContent = '启用并创建管理员'; }
                fail('请求失败，请稍后重试');
            });
    }

    // ============== 准入检查 ==============
    function bootstrap() {
        fetch('/web/user/me')
            .then(jsonResponse)
            .then(function (resp) {
                if (!resp || resp.code !== 200 || !resp.data) throw new Error('Invalid user response');
                var d = resp.data;
                if (d.bootstrapRequired === true && d.authEnabled === false) {
                    renderBootstrap();
                    return;
                }
                if (!d.authenticated) {
                    window.location.href = loginUrl;
                    return;
                }
                if (d.role !== 'admin') {
                    showGuard('无访问权限',
                        '你当前的角色是「' + roleLabel(d.role) + '」，管理控制台仅对管理员开放。',
                        '返回工作台', '/');
                    return;
                }
                // 已是管理员：渲染用户信息 + 加载模块
                if (userInfoEl) {
                    userInfoEl.classList.remove('admin-auth-disabled');
                    userInfoEl.textContent = '';
                    userInfoEl.appendChild(document.createTextNode((d.displayName || d.username) + ' '));
                    var roleEl = document.createElement('span');
                    roleEl.className = 'user-role-tag user-role-admin';
                    roleEl.textContent = roleLabel(d.role);
                    userInfoEl.appendChild(roleEl);
                }
                loadModules();
            })
            .catch(function () {
                showGuard('加载失败', '无法获取用户信息，请稍后重试。', '重试', '/web/admin');
            });
    }

    // ============== 模块加载 ==============
    function loadModules() {
        var all = window.ADMIN_MODULES || [];
        fetch('/web/admin/modules')
            .then(jsonResponse)
            .then(function (resp) {
                if (!resp || resp.code !== 200 || !Array.isArray(resp.data)) throw new Error('Invalid modules response');
                buildModules(all, resp.data);
            })
            .catch(function () {
                buildModules(all, []);
                if (location.pathname === '/web/admin') showGuard('模块加载失败', '无法确认可用管理模块，已关闭管理操作。', '重试', '/web/admin');
            });
    }

    function buildModules(all, enabledKeys) {
        modules = all.filter(function (m) {
            return !hiddenModuleKeys[m.key] && enabledKeys.indexOf(m.key) >= 0;
        }).sort(function (a, b) {
            return (a.order || 0) - (b.order || 0);
        });
        moduleMap = {};
        modules.forEach(function (m) { moduleMap[m.key] = m; });

        renderNav();
        // 首次路由：hash 优先，否则首个模块
        route();
    }

    function renderNav() {
        var html = '<div class="admin-nav-group-title">管理</div>';
        modules.forEach(function (m) {
            html += '<button class="admin-nav-item" data-key="' + m.key + '">' +
                (m.icon || '') + '<span>' + m.title + '</span></button>';
        });
        navEl.innerHTML = html;

        navEl.querySelectorAll('.admin-nav-item').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var key = btn.getAttribute('data-key');
                if (location.hash !== '#' + key) {
                    location.hash = '#' + key;
                } else {
                    activate(key);
                }
            });
        });
    }

    function activate(key) {
        var m = moduleMap[key] || modules[0];
        if (!m) return;
        navEl.querySelectorAll('.admin-nav-item').forEach(function (btn) {
            btn.classList.toggle('active', btn.getAttribute('data-key') === m.key);
        });
        try {
            m.render(contentEl);
        } catch (e) {
            showGuard('模块加载出错', '无法显示此管理模块，请刷新后重试。', '重试', '/web/admin');
        }
    }

    function route() {
        var key = (location.hash || '').replace(/^#/, '');
        if (!key || !moduleMap[key]) {
            key = modules.length ? modules[0].key : null;
        }
        if (key) activate(key);
    }

    window.addEventListener('hashchange', route);

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', bootstrap);
    } else {
        bootstrap();
    }
})();

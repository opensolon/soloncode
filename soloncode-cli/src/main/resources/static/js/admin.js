/**
 * admin.js — 管理员控制台骨架
 *
 * 职责：
 *  1. 前端准入检查（体验层）：未登录跳登录页；非 admin 显示 403 提示。
 *     认证未启用且尚无用户时显示「自举向导」；已有用户后主动关闭认证时，
 *     允许匿名进入管理台，避免刷新后再次落入登录/初始化流程。
 *     真正的拦截由后端 UserAuthFilter 的 403 负责，此处仅优化体验。
 *  2. 从 ADMIN_MODULES（admin-modules.js）与后端 /web/admin/modules 取交集，生成导航。
 *  3. hash 路由（/admin#users）驱动模块 render。
 */
(function () {
    'use strict';

    var navEl = document.getElementById('adminNav');
    var contentEl = document.getElementById('adminContent');
    var userInfoEl = document.getElementById('adminUserInfo');
    var logoutBtn = document.getElementById('adminLogoutBtn');

    var modules = [];      // 最终启用并排序后的模块
    var moduleMap = {};

    function roleLabel(role) {
        return role === 'admin' ? '管理员' : (role === 'readonly' ? '只读' : '普通用户');
    }

    // 退出登录：销毁后端会话 + 清本地 cookie，回到登录页。
    // 与工作台 web.html 的登出逻辑一致，确保 token 两端都失效。
    function doLogout() {
        if (!window.confirm('确定要退出登录吗？')) return;
        fetch('/web/user/logout', { method: 'POST' })
            .then(function () {
                document.cookie = 'user_token=; path=/; max-age=0';
                window.location.href = '/login';
            })
            .catch(function () {
                // 后端不可达也强制清本地态并回登录页，避免卡在后台
                document.cookie = 'user_token=; path=/; max-age=0';
                window.location.href = '/login';
            });
    }

    if (logoutBtn) {
        logoutBtn.addEventListener('click', doLogout);
    }

    function showGuard(title, desc, btnText, btnHref) {
        var btn = btnHref ? '<a class="admin-topbar-back" href="' + btnHref + '">' + btnText + '</a>' : '';
        contentEl.innerHTML =
            '<div class="admin-guard-state"><h2>' + title + '</h2><p>' + desc + '</p>' + btn + '</div>';
    }

    // ============== 实例自举向导 ==============
    // 认证未启用时，系统里还没有用户/角色体系。“是否启用认证”本质是实例级
    // 自举决策，而非普通用户设置，故入口归到 admin，一步完成启用 + 创建首个管理员。
    function renderBootstrap() {
        // 独立的全屏居中初始化页：隐藏导航骨架与顶栏多余元素，
        // 避免空侧栏/无对比卡片造成的“没法看”的观感。
        document.body.classList.add('admin-setup-mode');
        contentEl.innerHTML =
            '<div class="admin-setup">' +
            '  <div class="admin-setup-card">' +
            '    <div class="admin-setup-head">' +
            '      <h1 class="admin-setup-title">初始化用户体系</h1>' +
            '      <p class="admin-setup-desc">当前还未启用用户认证。创建首个管理员账户，即可开启认证并进入控制台。</p>' +
            '    </div>' +
            '    <div class="admin-setup-body">' +
            '      <div class="general-field"><label class="general-field-label">管理员用户名 <span class="required">*</span></label><input type="text" class="general-input" id="bsUsername" value="admin" placeholder="admin"/></div>' +
            '      <div class="general-field"><label class="general-field-label">显示名称</label><input type="text" class="general-input" id="bsDisplayName" placeholder="管理员"/></div>' +
            '      <div class="general-field"><label class="general-field-label">邮箱</label><input type="text" class="general-input" id="bsEmail" placeholder="admin@localhost"/></div>' +
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
            .then(function (r) { return r.json(); })
            .then(function (resp) {
                if (resp && resp.code === 200) {
                    // 开启成功：此后访问需登录，跳登录页
                    window.location.href = '/login';
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
            .then(function (r) { return r.json(); })
            .then(function (resp) {
                var d = (resp && resp.code === 200 && resp.data) ? resp.data : {};
                if (!d.authEnabled) {
                    if (d.bootstrapRequired) {
                        // 全新实例尚无用户：通过自举向导创建首个管理员并开启认证。
                        renderBootstrap();
                        return;
                    }
                    // 已有用户后主动关闭认证：此时服务端已按“无需认证”放行，
                    // 管理台也应直接可用，不能因没有登录态再次跳登录或要求初始化。
                    document.body.classList.remove('admin-setup-mode');
                    if (userInfoEl) {
                        userInfoEl.classList.add('admin-auth-disabled');
                        userInfoEl.textContent = '认证已关闭';
                    }
                    if (logoutBtn) logoutBtn.style.display = 'none';
                    loadModules();
                    return;
                }
                if (!d.authenticated) {
                    window.location.href = '/login';
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
                    userInfoEl.innerHTML =
                        (d.displayName || d.username) +
                        ' <span class="user-role-tag user-role-admin">' + roleLabel(d.role) + '</span>';
                }
                loadModules();
            })
            .catch(function () {
                showGuard('加载失败', '无法获取用户信息，请稍后重试。', '返回工作台', '/');
            });
    }

    // ============== 模块加载 ==============
    function loadModules() {
        var all = window.ADMIN_MODULES || [];
        fetch('/web/admin/modules')
            .then(function (r) { return r.json(); })
            .then(function (resp) {
                var enabled = (resp && resp.code === 200 && Array.isArray(resp.data)) ? resp.data : null;
                buildModules(all, enabled);
            })
            .catch(function () {
                // 接口失败时降级：使用前端注册表全集
                buildModules(all, null);
            });
    }

    function buildModules(all, enabledKeys) {
        modules = all.filter(function (m) {
            return !enabledKeys || enabledKeys.indexOf(m.key) >= 0;
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
            contentEl.innerHTML = '<div class="admin-guard-state"><h2>模块加载出错</h2><p>' +
                (e && e.message ? e.message : '未知错误') + '</p></div>';
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

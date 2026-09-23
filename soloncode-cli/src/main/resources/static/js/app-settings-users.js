/**
 * app-settings-users.js — 管理控制台的认证配置与本地用户管理。
 */
(function () {
    'use strict';

    var esc = window._settingsCore ? window._settingsCore.escapeHtml : function(s) { return String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'); };
    var escAttr = window._settingsCore ? window._settingsCore.escapeAttr : esc;
    var showToast = window.showToast || (window._settingsCore ? window._settingsCore.showToast : function(msg, type) { if (typeof layer !== 'undefined' && layer.msg) layer.msg(msg, { icon: type === 'error' ? 2 : 1, time: 2500, offset: '120px' }); else alert(msg); });

    function handleAuthFailure(xhr) {
        if (!xhr || (xhr.status !== 401 && xhr.status !== 403)) return false;
        if (xhr.status === 401) {
            window.location.replace('/login?scope=admin&returnUrl=%2Fweb%2Fadmin');
        } else if (window.showAdminGuard) {
            window.showAdminGuard('无访问权限', '当前账户没有管理权限。');
        } else {
            showToast('当前账户没有管理权限', 'error');
        }
        return true;
    }

    function postJson(url, data, done) {
        var request = $.ajax({
            url: url,
            method: 'POST',
            data: JSON.stringify(data),
            contentType: 'application/json',
            dataType: 'json'
        });
        if (typeof done === 'function') request.done(done);
        request.fail(handleAuthFailure);
        return request;
    }

    var currentMode = 'file';
    var currentStoreType = 'file';
    var users = [];
    var editingUserId = null;
    var authEnabledInitial = false;
    var userListRequestId = 0;
    var authConfigLoaded = false;

    function setInlineState(selector, message, state) {
        var el = $(selector);
        if (!el.length) return;
        el.removeClass('is-success is-error is-loading');
        if (state) el.addClass('is-' + state);
        el.text(message || '');
    }

    function loadAuthConfig() {
        setInlineState('#userAuthLdapTestResult', '', null);
        authConfigLoaded = false;
        $('#userAuthSaveConfigBtn').prop('disabled', true);
        $('#userAuthConfigRetryBtn').remove();
        $.ajax({ url: '/web/admin/auth/config', dataType: 'json' })
            .done(function (resp) {
                if (resp.code !== 200) {
                    showToast(resp.description || '认证配置加载失败', 'error');
                    $('#userAuthSaveConfigBtn').after('<button type="button" class="btn-secondary" id="userAuthConfigRetryBtn">重试加载</button>');
                    return;
                }
                var data = resp.data || {};
                currentMode = data.mode === 'ldap' ? 'ldap' : 'file';
                currentStoreType = data.storeType || currentMode;
                authEnabledInitial = !!data.enabled;
                $('#userAuthEnabled').prop('checked', data.enabled);
                setMode(currentMode);
                $('#userAuthSessionTimeout').val(data.sessionTimeoutMinutes || 60);

                var ldap = data.ldap || {};
                $('#userAuthLdapUrl').val(ldap.ldapUrl || '');
                $('#userAuthLdapAdminDn').val(ldap.ldapAdminDn || '');
                $('#userAuthLdapPassword').val('');
                $('#userAuthLdapBaseDn').val(ldap.ldapBaseDn || '');
                $('#userAuthLdapFilter').val(ldap.ldapUserFilter || '(uid={0})');
                $('#userAuthLdapSsl').prop('checked', !!ldap.ldapSsl);
                $('#userAuthLdapDisplayAttr').val(ldap.ldapDisplayNameAttribute || 'displayName');
                $('#userAuthLdapEmailAttr').val(ldap.ldapEmailAttribute || 'mail');
                $('#userAuthLdapGroupAttr').val(ldap.ldapGroupAttribute || 'memberOf');
                $('#userAuthLdapAdminGroupDn').val(ldap.ldapAdminGroupDn || '');
                $('#userAuthLdapConnectTimeout').val(ldap.ldapConnectTimeoutMillis || 5000);
                $('#userAuthLdapReadTimeout').val(ldap.ldapReadTimeoutMillis || 5000);
                $('#userAuthStoreType').text(currentStoreType);
                authConfigLoaded = true;
            })
            .fail(function (xhr) {
                if (handleAuthFailure(xhr)) return;
                showToast('认证配置加载失败，请检查网络后重试', 'error');
                $('#userAuthSaveConfigBtn').after('<button type="button" class="btn-secondary" id="userAuthConfigRetryBtn">重试加载</button>');
            })
            .done(function () {
                $('#userAuthSaveConfigBtn').prop('disabled', !authConfigLoaded);
            });
    }

    function setMode(mode) {
        currentMode = mode === 'ldap' ? 'ldap' : 'file';
        $('.user-auth-mode-btn').removeClass('active').attr('aria-pressed', 'false');
        $('.user-auth-mode-btn[data-mode="' + currentMode + '"]').addClass('active').attr('aria-pressed', 'true');
        $('.user-auth-config-section').hide();
        $('#userAuthConfigLdap').toggle(currentMode === 'ldap');
        $('#userAuthConfigFile').toggle(currentMode === 'file');
        setInlineState('#userAuthLdapTestResult', '', null);
    }

    function loadUsers() {
        var requestId = ++userListRequestId;
        $('#userList').html('<div class="user-empty-state">正在加载用户...</div>');
        $.ajax({ url: '/web/admin/users', dataType: 'json' })
            .done(function (resp) {
                if (requestId !== userListRequestId) return;
                if (resp.code !== 200) {
                    renderUserListError(resp.description || '用户列表加载失败');
                    return;
                }
                users = resp.data || [];
                renderUsers();
            })
            .fail(function () {
                if (requestId === userListRequestId) renderUserListError('用户列表加载失败，请稍后重试');
            });
    }

    function renderUserListError(message) {
        $('#userList').html('<div class="user-empty-state user-list-error">' + esc(message) + '<button type="button" class="btn-secondary" id="userListRetryBtn">重试</button></div>');
    }

    function renderUsers() {
        var html = '';
        if (users.length === 0) {
            html = '<div class="user-empty-state">暂无用户，请添加</div>';
        } else {
            users.forEach(function (u) {
                var roleLabel = u.role === 'admin' ? '管理员' : '普通用户';
                var enabled = u.enabled !== false;
                html += '<div class="settings-list-item user-list-item' + (enabled ? '' : ' disabled') + '" data-id="' + escAttr(u.id) + '">' +
                    '<div class="settings-list-icon user-list-avatar">' + esc((u.displayName || u.username).charAt(0).toUpperCase()) + '</div>' +
                    '<div class="settings-list-info user-list-info">' +
                        '<div class="user-list-name">' + esc(u.displayName || u.username) +
                        ' <span class="user-list-username">@' + esc(u.username) + '</span></div>' +
                        '<div class="user-list-meta">' +
                            '<span class="user-role-tag user-role-' + escAttr(u.role) + '">' + roleLabel + '</span>' +
                            (u.email ? '<span class="user-list-email">' + esc(u.email) + '</span>' : '') +
                        '</div>' +
                    '</div>' +
                    '<div class="settings-list-actions user-list-actions">' +
                        '<button class="settings-action-btn edit user-edit-btn" type="button" title="编辑" data-id="' + escAttr(u.id) + '"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>' +
                        '<label class="toggle-switch" title="' + (enabled ? '停用用户' : '启用用户') + '">' +
                            '<input type="checkbox" class="user-enabled-toggle" data-id="' + escAttr(u.id) + '" ' + (enabled ? 'checked' : '') + '/>' +
                            '<span class="toggle-slider"></span>' +
                        '</label>' +
                    '</div>' +
                '</div>';
            });
        }
        $('#userList').html(html);
    }

    function positiveInt(selector, label, min, max, fallback) {
        var raw = String($(selector).val() || '').trim();
        if (!raw) return fallback;
        if (!/^\d+$/.test(raw)) { showToast(label + '必须是整数', 'error'); return null; }
        var value = Number(raw);
        if (!Number.isSafeInteger(value) || value < min || value > max) { showToast(label + '范围应为 ' + min + ' 至 ' + max, 'error'); return null; }
        return value;
    }

    function ldapPayload() {
        var connectTimeout = positiveInt('#userAuthLdapConnectTimeout', '连接超时', 100, 60000, 5000);
        var readTimeout = positiveInt('#userAuthLdapReadTimeout', '读取超时', 100, 60000, 5000);
        if (connectTimeout === null || readTimeout === null) return null;
        return {
            ldapUrl: ($('#userAuthLdapUrl').val() || '').trim(),
            ldapAdminDn: ($('#userAuthLdapAdminDn').val() || '').trim(),
            ldapAdminPassword: $('#userAuthLdapPassword').val() || '',
            ldapBaseDn: ($('#userAuthLdapBaseDn').val() || '').trim(),
            ldapUserFilter: ($('#userAuthLdapFilter').val() || '').trim(),
            ldapSsl: $('#userAuthLdapSsl').prop('checked'),
            ldapDisplayNameAttribute: ($('#userAuthLdapDisplayAttr').val() || '').trim(),
            ldapEmailAttribute: ($('#userAuthLdapEmailAttr').val() || '').trim(),
            ldapGroupAttribute: ($('#userAuthLdapGroupAttr').val() || '').trim(),
            ldapAdminGroupDn: ($('#userAuthLdapAdminGroupDn').val() || '').trim(),
            ldapConnectTimeoutMillis: connectTimeout,
            ldapReadTimeoutMillis: readTimeout
        };
    }

    function testLdap() {
        var btn = $('#userAuthLdapTestBtn');
        if (btn.prop('disabled')) return;
        btn.prop('disabled', true).text('正在测试...');
        setInlineState('#userAuthLdapTestResult', '正在连接 LDAP...', 'loading');
        var ldap = ldapPayload();
        if (!ldap) { btn.prop('disabled', false).text('测试连接与角色'); return; }
        postJson('/web/admin/auth/ldap/test', {
            ldap: ldap,
            ldapTestUsername: ($('#userAuthLdapTestUsername').val() || '').trim(),
            ldapTestPassword: $('#userAuthLdapTestPassword').val() || ''
        }).done(function (resp) {
            if (resp.code === 200) {
                var data = resp.data || {};
                var detail = data.username ? ('用户 ' + data.username + '，角色：' + (data.role === 'admin' ? '管理员' : '普通用户')) : 'LDAP 连接成功';
                setInlineState('#userAuthLdapTestResult', detail, 'success');
            } else {
                setInlineState('#userAuthLdapTestResult', resp.description || 'LDAP 测试失败', 'error');
            }
        }).fail(function () {
            setInlineState('#userAuthLdapTestResult', 'LDAP 测试请求失败', 'error');
        }).always(function () {
            btn.prop('disabled', false).text('测试连接与角色');
        });
    }

    function saveConfig() {
        var willDisable = authEnabledInitial && !$('#userAuthEnabled').prop('checked');
        if (willDisable) {
            var msg = '关闭用户认证后，所有人无需登录即可使用系统。确定要关闭吗？';
            if (typeof layer !== 'undefined' && layer.confirm) {
                layer.confirm(msg, { title: '确认关闭认证', btn: ['关闭认证', '取消'], icon: 3, offset: '120px' }, function (index) {
                    layer.close(index);
                    doSaveConfig();
                }, function () { $('#userAuthEnabled').prop('checked', true); });
            } else if (window.confirm(msg)) {
                doSaveConfig();
            } else {
                $('#userAuthEnabled').prop('checked', true);
            }
            return;
        }
        doSaveConfig();
    }

    function doSaveConfig() {
        var btn = $('#userAuthSaveConfigBtn');
        if (btn.prop('disabled') || !authConfigLoaded) { showToast('认证配置尚未加载完成，暂不可保存', 'error'); return; }
        var sessionTimeout = positiveInt('#userAuthSessionTimeout', '会话最长有效期', 1, 10080, 60);
        var ldap = ldapPayload();
        if (sessionTimeout === null || !ldap) return;
        var data = {
            enabled: $('#userAuthEnabled').prop('checked'),
            mode: currentMode,
            sessionTimeoutMinutes: sessionTimeout,
            ldap: ldap,
            ldapTestUsername: ($('#userAuthLdapTestUsername').val() || '').trim(),
            ldapTestPassword: $('#userAuthLdapTestPassword').val() || ''
        };
        btn.prop('disabled', true).text('保存中...');
        postJson('/web/admin/auth/config/save', data).done(function (resp) {
            if (resp.code === 200) {
                var oldStoreType = currentStoreType;
                authEnabledInitial = !!data.enabled;
                currentStoreType = (resp.data && resp.data.storeType) || currentMode;
                $('#userAuthLdapPassword, #userAuthLdapTestPassword').val('');
                showToast('配置已保存并生效');
                if (resp.data && resp.data.reloginRequired) {
                    document.cookie = 'user_token=; path=/; max-age=0';
                    window.location.href = '/login';
                    return;
                }
                if (oldStoreType !== currentStoreType) {
                    window.location.reload();
                    return;
                }
                loadAuthConfig();
            } else {
                showToast(resp.description || '保存失败', 'error');
            }
        }).fail(function () {
            showToast('保存失败，请检查网络后重试', 'error');
        }).always(function () {
            btn.prop('disabled', false).text('保存配置');
        });
    }

    function showUserForm(user) {
        editingUserId = user ? user.id : null;
        $('#userFormId').val(user ? user.id : '');
        $('#userFormUsername').val(user ? user.username : '').prop('readonly', !!user).toggleClass('readonly-gray', !!user);
        $('#userFormDisplayName').val(user ? user.displayName : '');
        $('#userFormEmail').val(user ? user.email : '');
        $('#userFormPassword').val('').prop('required', !user).attr('placeholder', user ? '留空则不修改密码' : '请输入密码');
        $('#userFormPasswordRequired').toggle(!user);
        $('#userFormRole').val(user && user.role === 'admin' ? 'admin' : 'user');
        $('#userFormTitle').text(user ? '编辑用户' : '添加用户');
        $('#userFormActions').toggle(!!user);
        $('#userListView').hide();
        $('#userFormView').show();
    }

    function hideUserForm() {
        editingUserId = null;
        $('#userFormView').hide();
        $('#userListView').addClass('slide-back').show();
        setTimeout(function () { $('#userListView').removeClass('slide-back'); }, 260);
        loadUsers();
    }

    function saveUser() {
        var btn = $('#userFormSaveBtn');
        if (btn.prop('disabled')) return;
        var id = $('#userFormId').val();
        var username = ($('#userFormUsername').val() || '').trim();
        var password = $('#userFormPassword').val();
        if (!username) { showToast('用户名不能为空', 'error'); return; }
        if (!id && !password) { showToast('密码不能为空', 'error'); return; }

        var data = {
            username: username,
            displayName: ($('#userFormDisplayName').val() || '').trim(),
            email: ($('#userFormEmail').val() || '').trim(),
            role: $('#userFormRole').val()
        };
        var url = '/web/admin/users/create';
        if (id) {
            url = '/web/admin/users/update';
            data.id = id;
        }
        if (password) data.password = password;

        btn.prop('disabled', true).text(id ? '保存中...' : '创建中...');
        postJson(url, data).done(function (resp) {
            if (resp.code === 200) {
                showToast(id ? '用户已更新' : '用户已创建');
                hideUserForm();
            } else {
                showToast(resp.description || (id ? '更新失败' : '创建失败'), 'error');
            }
        }).fail(function () {
            showToast('请求失败，请稍后重试', 'error');
        }).always(function () {
            btn.prop('disabled', false).text('保存');
        });
    }

    function toggleUser(id, enabled, input) {
        if (input) input.disabled = true;
        postJson('/web/admin/users/toggle', { id: id, enabled: enabled }).done(function (resp) {
            if (resp.code === 200) {
                loadUsers();
            } else {
                if (input) input.checked = !enabled;
                showToast(resp.description || '操作失败', 'error');
            }
        }).fail(function () {
            if (input) input.checked = !enabled;
            showToast('操作失败，请稍后重试', 'error');
        }).always(function () {
            if (input) input.disabled = false;
        });
    }

    function deleteUser(id, username) {
        var confirmDelete = function () { doDeleteUser(id); };
        if (typeof layer !== 'undefined' && layer.confirm) {
            layer.confirm('确定要删除用户 "' + username + '" 吗？', { title: '确认删除', btn: ['删除', '取消'], icon: 3, offset: '120px' }, function(index) {
                layer.close(index);
                confirmDelete();
            });
        } else if (window.confirm('确定要删除用户 "' + username + '" 吗？')) {
            confirmDelete();
        }
    }

    function doDeleteUser(id) {
        var btn = $('#userFormDeleteBtn');
        btn.prop('disabled', true);
        postJson('/web/admin/users/delete', { id: id }).done(function (resp) {
            if (resp.code === 200) {
                showToast('用户已删除');
                hideUserForm();
            } else {
                showToast(resp.description || '删除失败', 'error');
            }
        }).fail(function () {
            showToast('删除失败，请稍后重试', 'error');
        }).always(function () {
            btn.prop('disabled', false);
        });
    }

    $(document).on('settings:tab:users', function() { loadAuthConfig(); loadUsers(); });
    $(document).on('click', '.user-auth-mode-btn', function() { setMode($(this).attr('data-mode')); });
    $(document).on('click', '#userAuthLdapTestBtn', testLdap);
    $(document).on('click', '#userAuthSaveConfigBtn', saveConfig);
    $(document).on('click', '#userAddBtn', function() { showUserForm(null); });
    $(document).on('click', '#userListRetryBtn', loadUsers);
    $(document).on('click', '#userAuthConfigRetryBtn', loadAuthConfig);
    $(document).on('click', '.user-edit-btn', function(e) {
        e.stopPropagation();
        var id = $(this).attr('data-id');
        for (var i = 0; i < users.length; i++) {
            if (users[i].id === id) { showUserForm(users[i]); break; }
        }
    });
    $(document).on('change', '.user-enabled-toggle', function(e) {
        e.stopPropagation();
        toggleUser($(this).attr('data-id'), this.checked, this);
    });
    $(document).on('click', '#userFormDeleteBtn', function() {
        if (editingUserId) deleteUser(editingUserId, ($('#userFormUsername').val() || '').trim());
    });
    $(document).on('click', '#userFormCancelBtn', hideUserForm);
    $(document).on('click', '#userFormSaveBtn', saveUser);
    $(document).on('input change', '#userAuthConfigLdap input', function() {
        setInlineState('#userAuthLdapTestResult', '配置已变化，请重新测试', 'loading');
    });

    window._settingsUsers = {
        loadAuthConfig: loadAuthConfig,
        loadUsers: loadUsers,
        load: function() { loadAuthConfig(); loadUsers(); },
        showList: function() { hideUserForm(); },
        reset: function() { hideUserForm(); }
    };
})();

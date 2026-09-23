/**
 * app-settings-users.js — 用户管理设置面板
 * 
 * 提供用户认证配置（模式选择、数据库/LDAP 配置）和用户 CRUD 管理
 */
(function () {
    'use strict';

    var esc = window._settingsCore ? window._settingsCore.escapeHtml : function(s) { return String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); };
    var escAttr = window._settingsCore ? window._settingsCore.escapeAttr : esc;
    var postJson = window._settingsCore ? window._settingsCore.postJson : function(url, data, done) { $.ajax({ url: url, method: 'POST', data: JSON.stringify(data), contentType: 'application/json', dataType: 'json' }).done(done); };
    var showToast = window.showToast || (window._settingsCore ? window._settingsCore.showToast : function(msg, type) { if (typeof layer !== 'undefined' && layer.msg) layer.msg(msg, { icon: type === 'error' ? 2 : 1, time: 2500, offset: '120px' }); else alert(msg); });

    // ============== 状态 ==============
    var currentMode = 'file';
    var users = [];
    var editingUserId = null;

    // ============== 加载 ==============
    // 认证配置与用户列表已拆为两个独立 tab，各自按需加载。
    // 记录后端返回的 enabled 初始值，用于关闭认证时的二次确认与回退。
    var authEnabledInitial = false;

    function loadAuthConfig() {
        $.ajax({ url: '/web/settings/user-auth/config', dataType: 'json' })
            .done(function (resp) {
                if (resp.code !== 200) return;
                var data = resp.data || {};
                currentMode = data.mode || 'file';

                // 填充表单
                authEnabledInitial = !!data.enabled;
                $('#userAuthEnabled').prop('checked', data.enabled);
                setMode(currentMode);
                $('#userAuthSessionTimeout').val(data.sessionTimeoutMinutes || 60);

                // 数据库配置
                var db = data.database || {};
                $('#userAuthDbUrl').val(db.dbUrl || '');
                $('#userAuthDbUser').val(db.dbUser || '');
                $('#userAuthDbDriver').val(db.dbDriverClass || '');

                // LDAP 配置
                var ldap = data.ldap || {};
                $('#userAuthLdapUrl').val(ldap.ldapUrl || '');
                $('#userAuthLdapAdminDn').val(ldap.ldapAdminDn || '');
                $('#userAuthLdapBaseDn').val(ldap.ldapBaseDn || '');
                $('#userAuthLdapFilter').val(ldap.ldapUserFilter || '(uid={0})');
                $('#userAuthLdapSsl').prop('checked', ldap.ldapSsl);

                // 存储类型提示
                $('#userAuthStoreType').text(data.storeType || 'file');
            });
    }

    function setMode(mode) {
        currentMode = mode;
        $('.user-auth-mode-btn').removeClass('active');
        $('.user-auth-mode-btn[data-mode="' + mode + '"]').addClass('active');
        
        $('.user-auth-config-section').hide();
        $('#userAuthConfigDb').toggle(mode === 'database');
        $('#userAuthConfigLdap').toggle(mode === 'ldap');
        $('#userAuthConfigFile').toggle(mode === 'file');
    }

    function loadUsers() {
        $.ajax({ url: '/web/settings/user-auth/users', dataType: 'json' })
            .done(function (resp) {
                if (resp.code !== 200) return;
                users = resp.data || [];
                renderUsers();
            });
    }

    function renderUsers() {
        var html = '';
        if (users.length === 0) {
            html = '<div class="user-empty-state">暂无用户，请添加</div>';
        } else {
            users.forEach(function (u) {
                var roleLabel = u.role === 'admin' ? '管理员' : (u.role === 'readonly' ? '只读' : '普通用户');
                var enabled = u.enabled !== false;
                // 与模型管理一致：列表行提供独立的编辑按钮和启用开关。
                html += '<div class="user-list-item' + (enabled ? '' : ' disabled') + '" data-id="' + escAttr(u.id) + '">' +
                    '<div class="user-list-avatar">' + esc((u.displayName || u.username).charAt(0).toUpperCase()) + '</div>' +
                    '<div class="user-list-info">' +
                        '<div class="user-list-name">' + esc(u.displayName || u.username) +
                        ' <span class="user-list-username">@' + esc(u.username) + '</span></div>' +
                        '<div class="user-list-meta">' +
                            '<span class="user-role-tag user-role-' + escAttr(u.role) + '">' + roleLabel + '</span>' +
                            (u.email ? '<span class="user-list-email">' + esc(u.email) + '</span>' : '') +
                        '</div>' +
                    '</div>' +
                    '<div class="user-list-actions">' +
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

    // ============== 保存配置 ==============
    // 关闭认证是实例级重决策（关掉 = 整个登录体系失效，下次进入回到自举向导），
    // 独立成 tab 后入口更显眼，因此在真正落地前加二次确认。
    function saveConfig() {
        var willDisable = authEnabledInitial && !$('#userAuthEnabled').prop('checked');
        if (willDisable) {
            var msg = '关闭用户认证后，所有人无需登录即可使用系统，登录体系将整体失效。确定要关闭吗？';
            if (typeof layer !== 'undefined' && layer.confirm) {
                layer.confirm(msg, { title: '确认关闭认证', btn: ['关闭认证', '取消'], icon: 3, offset: '120px' }, function (index) {
                    layer.close(index);
                    doSaveConfig();
                }, function () {
                    // 取消：恢复开关勾选，避免误关
                    $('#userAuthEnabled').prop('checked', true);
                });
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
        var data = {
            enabled: $('#userAuthEnabled').prop('checked'),
            mode: currentMode,
            sessionTimeoutMinutes: parseInt($('#userAuthSessionTimeout').val()) || 60,
            database: {
                dbUrl: $('#userAuthDbUrl').val(),
                dbUser: $('#userAuthDbUser').val(),
                dbPassword: $('#userAuthDbPassword').val(),
                dbDriverClass: $('#userAuthDbDriver').val()
            },
            ldap: {
                ldapUrl: $('#userAuthLdapUrl').val(),
                ldapAdminDn: $('#userAuthLdapAdminDn').val(),
                ldapAdminPassword: $('#userAuthLdapPassword').val(),
                ldapBaseDn: $('#userAuthLdapBaseDn').val(),
                ldapUserFilter: $('#userAuthLdapFilter').val(),
                ldapSsl: $('#userAuthLdapSsl').prop('checked')
            }
        };
        
        postJson('/web/settings/user-auth/config/save', data, function (resp) {
            if (resp.code === 200) {
                // 保存成功后同步初始值，使后续二次确认判断基于最新状态
                authEnabledInitial = !!data.enabled;
                showToast('配置已保存');
            } else {
                showToast(resp.description || '保存失败', 'error');
            }
        });
    }

    // ============== 用户表单（两层视图切换，对齐模型设置） ==============
    // 列表视图 #userListView 与表单视图 #userFormView 互斥，带 slide 动画。
    // #userFormActions 仅在编辑态显示（含删除）；添加态隐藏。
    function showUserForm(user) {
        editingUserId = user ? user.id : null;
        $('#userFormId').val(user ? user.id : '');
        $('#userFormUsername').val(user ? user.username : '').prop('readonly', !!user);
        $('#userFormDisplayName').val(user ? user.displayName : '');
        $('#userFormEmail').val(user ? user.email : '');
        $('#userFormPassword').val('').prop('required', !user)
            .attr('placeholder', user ? '留空则不修改密码' : '请输入密码');
        $('#userFormPasswordRequired').toggle(!user);
        $('#userFormRole').val(user ? user.role : 'user');
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
        var id = $('#userFormId').val();
        var username = $('#userFormUsername').val().trim();
        var displayName = $('#userFormDisplayName').val().trim();
        var email = $('#userFormEmail').val().trim();
        var password = $('#userFormPassword').val();
        var role = $('#userFormRole').val();
        
        if (!username) { showToast('用户名不能为空', 'error'); return; }
        if (!id && !password) { showToast('密码不能为空', 'error'); return; }
        
        if (id) {
            // 更新资料；启用状态由列表开关独立管理。
            var data = { id: id, displayName: displayName, email: email, role: role };
            if (password) data.password = password;
            postJson('/web/settings/user-auth/users/update', data, function (resp) {
                if (resp.code === 200) {
                    showToast('用户已更新');
                    hideUserForm();
                } else {
                    showToast(resp.description || '更新失败', 'error');
                }
            });
        } else {
            // 创建
            postJson('/web/settings/user-auth/users/create', {
                username: username,
                password: password,
                displayName: displayName,
                email: email,
                role: role
            }, function (resp) {
                if (resp.code === 200) {
                    showToast('用户已创建');
                    hideUserForm();
                } else {
                    showToast(resp.description || '创建失败', 'error');
                }
            });
        }
    }

    function toggleUser(id, enabled, input) {
        postJson('/web/settings/user-auth/users/toggle', { id: id, enabled: enabled }, function (resp) {
            if (resp.code === 200) {
                loadUsers();
            } else {
                if (input) input.checked = !enabled;
                showToast(resp.description || '操作失败', 'error');
            }
        });
    }

    function deleteUser(id, username) {
        if (username === 'admin') {
            showToast('不能删除管理员账户', 'error');
            return;
        }
        if (typeof layer !== 'undefined' && layer.confirm) {
            layer.confirm('确定要删除用户 "' + username + '" 吗？', {
                title: '确认删除',
                btn: ['删除', '取消'],
                icon: 3,
                offset: '120px'
            }, function(index) {
                layer.close(index);
                doDeleteUser(id);
            });
        } else {
            if (window.confirm('确定要删除用户 "' + username + '" 吗？')) {
                doDeleteUser(id);
            }
        }
    }

    function doDeleteUser(id) {
        postJson('/web/settings/user-auth/users/delete', { id: id }, function (resp) {
            if (resp.code === 200) {
                showToast('用户已删除');
                // 删除发生在表单视图，完成后回列表（hideUserForm 内部会重新 loadUsers）
                hideUserForm();
            } else {
                showToast(resp.description || '删除失败', 'error');
            }
        });
    }

    // ============== 事件绑定 ==============
    // 首次加载（兼容：若仍有旧的合并 tab 事件，两块都加载）
    $(document).on('settings:tab:users', function() {
        loadAuthConfig();
        loadUsers();
    });

    // 模式选择
    $(document).on('click', '.user-auth-mode-btn', function() {
        setMode($(this).attr('data-mode'));
    });

    // 保存配置
    $(document).on('click', '#userAuthSaveConfigBtn', saveConfig);

    // 添加用户
    $(document).on('click', '#userAddBtn', function() {
        showUserForm(null);
    });

    // 编辑用户
    $(document).on('click', '.user-edit-btn', function(e) {
        e.stopPropagation();
        var id = $(this).attr('data-id');
        var user = null;
        for (var i = 0; i < users.length; i++) {
            if (users[i].id === id) { user = users[i]; break; }
        }
        if (user) showUserForm(user);
    });

    // 启用状态直接在列表切换（对齐模型管理）。
    $(document).on('change', '.user-enabled-toggle', function(e) {
        e.stopPropagation();
        toggleUser($(this).attr('data-id'), this.checked, this);
    });

    // 删除用户（表单视图内，仅编辑态可见）
    $(document).on('click', '#userFormDeleteBtn', function() {
        if (!editingUserId) return;
        deleteUser(editingUserId, ($('#userFormUsername').val() || '').trim());
    });

    // 返回/取消：回列表视图
    $(document).on('click', '#userFormCancelBtn', hideUserForm);

    // 保存用户
    $(document).on('click', '#userFormSaveBtn', saveUser);

    window._settingsUsers = {
        // 两个独立入口：认证配置 tab 与用户管理 tab 各自按需加载
        loadAuthConfig: loadAuthConfig,
        loadUsers: loadUsers,
        // 向后兼容：旧调用方仍可一次性加载两块
        load: function() { loadAuthConfig(); loadUsers(); },
        showList: function() { hideUserForm(); },
        reset: function() { hideUserForm(); }
    };
})();

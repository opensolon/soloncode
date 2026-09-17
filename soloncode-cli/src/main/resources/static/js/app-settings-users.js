/**
 * app-settings-users.js — 用户管理设置面板
 * 
 * 提供用户认证配置（模式选择、数据库/LDAP 配置）和用户 CRUD 管理
 */
(function () {
    'use strict';

    var esc = window._settingsCore ? window._settingsCore.escapeHtml : function(s) { return String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); };
    var postJson = window._settingsCore ? window._settingsCore.postJson : function(url, data, done) { $.ajax({ url: url, method: 'POST', data: JSON.stringify(data), contentType: 'application/json', dataType: 'json' }).done(done); };
    var showToast = window.showToast || (window._settingsCore ? window._settingsCore.showToast : function(msg, type) { if (typeof layer !== 'undefined' && layer.msg) layer.msg(msg, { icon: type === 'error' ? 2 : 1, time: 2500, offset: '120px' }); else alert(msg); });

    // ============== 状态 ==============
    var currentMode = 'file';
    var users = [];
    var editingUserId = null;

    // ============== 加载 ==============
    function load() {
        $.ajax({ url: '/web/settings/user-auth/config', dataType: 'json' })
            .done(function (resp) {
                if (resp.code !== 200) return;
                var data = resp.data || {};
                currentMode = data.mode || 'file';
                
                // 填充表单
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
        
        loadUsers();
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
                html += '<div class="user-list-item' + (u.enabled ? '' : ' disabled') + '">' +
                    '<div class="user-list-avatar">' + esc((u.displayName || u.username).charAt(0).toUpperCase()) + '</div>' +
                    '<div class="user-list-info">' +
                        '<div class="user-list-name">' + esc(u.displayName || u.username) + 
                        ' <span class="user-list-username">@' + esc(u.username) + '</span></div>' +
                        '<div class="user-list-meta">' +
                            '<span class="user-role-tag user-role-' + esc(u.role) + '">' + roleLabel + '</span>' +
                            (u.email ? '<span class="user-list-email">' + esc(u.email) + '</span>' : '') +
                        '</div>' +
                    '</div>' +
                    '<div class="user-list-actions">' +
                        '<button class="settings-action-btn edit user-edit-btn" data-id="' + esc(u.id) + '" title="编辑"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>' +
                        '<button class="settings-action-btn delete user-delete-btn" data-id="' + esc(u.id) + '" data-username="' + esc(u.username) + '" title="删除">' + SVG_TRASH + '</button>' +
                    '</div>' +
                '</div>';
            });
        }
        $('#userList').html(html);
    }

    // ============== 保存配置 ==============
    function saveConfig() {
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
                showToast('配置已保存');
            } else {
                showToast(resp.description || '保存失败', 'error');
            }
        });
    }

    // ============== 用户表单 ==============
    function showUserForm(user) {
        editingUserId = user ? user.id : null;
        $('#userFormId').val(user ? user.id : '');
        $('#userFormUsername').val(user ? user.username : '').prop('readonly', !!user);
        $('#userFormDisplayName').val(user ? user.displayName : '');
        $('#userFormEmail').val(user ? user.email : '');
        $('#userFormPassword').val('').prop('required', !user);
        $('#userFormRole').val(user ? user.role : 'user');
        $('#userFormEnabled').prop('checked', user ? user.enabled : true);
        $('#userFormTitle').text(user ? '编辑用户' : '添加用户');
        $('#userForm').show();
        $('#userList').hide();
        $('#userListActions').hide();
        $('#userFormActions').show();
    }

    function hideUserForm() {
        $('#userForm').hide();
        $('#userList').show();
        $('#userListActions').show();
        $('#userFormActions').hide();
        editingUserId = null;
        loadUsers();
    }

    function saveUser() {
        var id = $('#userFormId').val();
        var username = $('#userFormUsername').val().trim();
        var displayName = $('#userFormDisplayName').val().trim();
        var email = $('#userFormEmail').val().trim();
        var password = $('#userFormPassword').val();
        var role = $('#userFormRole').val();
        var enabled = $('#userFormEnabled').prop('checked');
        
        if (!username) { showToast('用户名不能为空', 'error'); return; }
        if (!id && !password) { showToast('密码不能为空', 'error'); return; }
        
        if (id) {
            // 更新
            var data = { id: id, displayName: displayName, email: email, role: role, enabled: enabled };
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
                loadUsers();
            } else {
                showToast(resp.description || '删除失败', 'error');
            }
        });
    }

    // ============== 事件绑定 ==============
    // 首次加载
    $(document).on('settings:tab:users', function() {
        load();
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
    $(document).on('click', '.user-edit-btn', function() {
        var id = $(this).attr('data-id');
        var user = null;
        for (var i = 0; i < users.length; i++) {
            if (users[i].id === id) { user = users[i]; break; }
        }
        if (user) showUserForm(user);
    });

    // 删除用户
    $(document).on('click', '.user-delete-btn', function() {
        deleteUser($(this).attr('data-id'), $(this).attr('data-username'));
    });

    // 取消表单
    $(document).on('click', '#userFormCancelBtn', hideUserForm);

    // 保存用户
    $(document).on('click', '#userFormSaveBtn', saveUser);

    window._settingsUsers = {
        load: load,
        showList: function() { hideUserForm(); },
        reset: function() { hideUserForm(); }
    };
})();

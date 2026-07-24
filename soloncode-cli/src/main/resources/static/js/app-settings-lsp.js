/**
 * app-settings-lsp.js — 设置面板子模块
 */
(function () {
    'use strict';

    var core = window._settingsCore;
    var escapeHtml = core.escapeHtml;
    var escapeAttr = core.escapeAttr;
    var parseKvLines = core.parseKvLines;
    var postJson = core.postJson;
    var showToast = core.showToast;
    var setScopeValue = core.setScopeValue;
    var setScopeReadonly = core.setScopeReadonly;

    var $lspServerList = $('#lspServerList');
    var $lspSaveBtn = $('#lspSaveBtn');
    var $lspFormTitle = $('#lspFormTitle');
    var $lspListView = $('#lspListView');
    var $lspFormView = $('#lspFormView');
    var lspEditName = null;
    var lspCachedList = [];

    function showLspListView() { $lspFormView.hide(); $lspListView.addClass('slide-back').show(); setTimeout(function(){ $lspListView.removeClass('slide-back'); }, 260); }
    function showLspFormView(title, isEdit) { $lspFormTitle.text(title || '添加服务器'); $lspListView.hide(); $lspFormView.show(); $('#lspFormActions').toggle(!!isEdit); }

    // ==================== LSP 服务器管理 ====================

    function loadLspList() {
        $.get('/web/settings/lsp/servers', function (resp) {
            if (resp.code === 200 && resp.data) {
                lspCachedList = resp.data;
                renderLspList(resp.data);
            }
        }).fail(function () { console.error('[Settings] Failed to load LSP servers'); });
    }

    function renderLspList(list) {
        var html = '';
        if (!list || list.length === 0) {
            html = '<div class="settings-empty-state">'
                + '<div class="settings-empty-icon"><i class="fa-solid fa-inbox fa-3x"></i></div>'
                + '<div class="settings-empty-title">暂无 LSP 服务器</div>'
                + '<div class="settings-empty-desc">LSP 服务器可提供代码补全、诊断等智能编辑能力</div>'
                + '</div>';
        } else {
            list.forEach(function (item) {
                var name = item.name || '';
                var command = (item.command && item.command.length > 0) ? item.command.join(' ') : '';
                var extensions = (item.extensions && item.extensions.length > 0) ? item.extensions.join(', ') : '';
                var enabled = item.enabled !== false;
                var installed = item.installed !== false;
                var badges = '<span class="settings-inline-tag">[lsp]</span>';
                if (item.scope === 'workspace') badges += ' <span class="mounts-scope-badge scope-workspace">工作区</span>';
                if (installed) badges += ' <span class="skill-installed-badge">已安装</span>';
                html += '<div class="settings-list-item' + (item.enabled === false ? ' disabled' : '') + '" data-name="' + escapeAttr(name) + '">'
                    + '<div class="settings-list-icon">L</div>'
                    + '<div class="settings-list-info">'
                    + '<div class="settings-list-title">' + escapeHtml(name) + ' ' + badges + '</div>'
                    + (command ? '<div class="settings-list-desc">' + escapeHtml(command) + '</div>' : '')
                    + (extensions ? '<div class="settings-list-desc settings-accent-text">' + escapeHtml(extensions) + '</div>' : '')
                    + '</div><div class="settings-list-actions">'
                    + '<button class="settings-action-btn edit" data-name="' + escapeAttr(name) + '" title="编辑"><i class="fa-solid fa-pen-to-square"></i></button>'
                    + '<label class="toggle-switch" title="' + (enabled ? '停用' : '启用') + '">'
                    + '<input type="checkbox" ' + (enabled ? 'checked' : '') + ' data-name="' + escapeAttr(name) + '" class="lsp-toggle"/>'
                    + '<span class="toggle-slider"></span>'
                    + '</label>'
                    + '</div></div>';
            });
        }
        $lspServerList.html(html);
    }

    // LSP 列表事件委托
    $lspServerList
        .on('click', '.settings-list-item', function (e) {
            if ($(e.target).closest('.settings-list-actions').length) return;
            var name = $(this).attr('data-name');
            if (name) lspEditServer(name);
        })
        .on('click', '.settings-action-btn.edit', function (e) {
            e.stopPropagation();
            var name = $(this).attr('data-name');
            if (name) lspEditServer(name);
        })
        .on('change', '.lsp-toggle', function () {
            lspToggleServer($(this).attr('data-name'), this.checked);
        });

    // ==================== LSP 表单 ====================

    function resetLspForm() {
        lspEditName = null;
        $lspSaveBtn.text('保存');
        $('#lspName').val('').prop('readOnly', false).removeClass('readonly-gray');
        $('#lspCommand, #lspExtensions, #lspEnv').val('');
        setScopeValue('lspScope', 'user');
        setScopeReadonly('lspScope', false);
        $('#lspFormDeleteBtn').hide();
    }

    function fillLspForm(server) {
        setScopeValue('lspScope', server.scope || 'user');
        var command = (server.command && server.command.length > 0) ? server.command.join(' ') : '';
        $('#lspCommand').val(command);
        var extensions = (server.extensions && server.extensions.length > 0) ? server.extensions.join(', ') : '';
        $('#lspExtensions').val(extensions);
        var envLines = [];
        if (server.env) Object.keys(server.env).forEach(function (k) { envLines.push(k + '=' + server.env[k]); });
        $('#lspEnv').val(envLines.join('\n'));
    }

    function buildLspBodyObj() {
        var name = $('#lspName').val().trim();
        var command = $('#lspCommand').val().trim();
        var extensions = $('#lspExtensions').val().trim();
        var envText = $('#lspEnv').val().trim();
        if (!name) { showToast('名称为必填项', 'error'); return null; }
        if (!/^[a-zA-Z0-9_-]+$/.test(name)) { showToast('名称仅允许字母、数字、下划线和连字符', 'error'); return null; }
        if (!command) { showToast('启动命令为必填项', 'error'); return null; }
        var bodyObj = { name: name, enabled: true, scope: $('#lspScope').val() || 'user' };
        // command as string (backend handles split)
        bodyObj.command = command;
        // extensions as array
        if (extensions) {
            bodyObj.extensions = extensions.split(',').map(function (s) { return s.trim(); }).filter(function (s) { return s.length > 0; });
        }
        var env = parseKvLines(envText);
        if (Object.keys(env).length > 0) bodyObj.env = env;
        return bodyObj;
    }

    function lspEditServer(name) {
        var server = lspCachedList.find(function (s) { return s.name === name; });
        if (!server) return;
        lspEditName = name;
        showLspFormView('编辑服务器', true);
        $lspSaveBtn.text('更新');
        $('#lspName').val(server.name).prop('readOnly', true).addClass('readonly-gray');
        setScopeValue('lspScope', server.scope || 'user');
        $('#lspFormDeleteBtn').show();
        fillLspForm(server);
    }

    function lspToggleServer(name, enabled) {
        postJson('/web/settings/lsp/servers/toggle', { name: name, enabled: enabled }, function (resp) {
            if (resp.code !== 200) { showToast('操作失败: ' + (resp.message || '未知错误'), 'error'); loadLspList(); }
            else { loadLspList(); }
        });
    }

    // LSP 按钮事件
    $('#lspAddBtn').on('click', function () { resetLspForm(); showLspFormView('添加服务器', false); });
    $('#lspBackBtn').on('click', function () { showLspListView(); resetLspForm(); });

    // LSP 表单 - 删除按钮
    $('#lspFormDeleteBtn').on('click', function () {
        var name = lspEditName;
        if (!name) return;
        layer.confirm('确定删除 LSP 服务器 "' + name + '"？', { title: '确认删除', btn: ['删除', '取消'], icon: 3, offset: '120px' }, function(index) {
            layer.close(index);
            postJson('/web/settings/lsp/servers/remove', { name: name }, function (resp) {
                if (resp.code === 200) { showLspListView(); loadLspList(); }
                else showToast('删除失败: ' + (resp.message || '未知错误'), 'error');
            });
        });
    });

    $lspSaveBtn.on('click', function () {
        var bodyObj = buildLspBodyObj();
        if (!bodyObj) return;
        var isEdit = !!lspEditName;
        var url = isEdit ? '/web/settings/lsp/servers/update' : '/web/settings/lsp/servers/add';
        var actionText = isEdit ? '更新' : '添加';
        if (isEdit) bodyObj.originalName = lspEditName;

        $lspSaveBtn.prop('disabled', true);
        $.ajax({ url: url, method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json' })
            .done(function (resp) {
                if (resp.code === 200) { showToast(actionText + '成功'); loadLspList(); showLspListView(); resetLspForm(); }
                else showToast(actionText + '失败: ' + (resp.message || '未知错误'), 'error');
            })
            .fail(function () { showToast('网络错误', 'error'); })
            .always(function () { $lspSaveBtn.prop('disabled', false); });
    });



    window._settingsLsp = { load: loadLspList, reset: resetLspForm, showList: showLspListView };
})();

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
    function showLspFormView(title, isEdit) { $lspFormTitle.text(title || I18n.t('lsp.addTitle')); $lspListView.hide(); $lspFormView.show(); $('#lspFormActions').toggle(!!isEdit); }

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
            html = '<div class="mcp-empty-state">'
                + '<div class="mcp-empty-icon"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg></div>'
                + '<div class="mcp-empty-title">' + I18n.t('lsp.empty') + '</div>'
                + '<div class="mcp-empty-desc">' + I18n.t('lsp.emptyDesc') + '</div>'
                + '</div>';
        } else {
            list.forEach(function (item) {
                var name = item.name || '';
                var command = (item.command && item.command.length > 0) ? item.command.join(' ') : '';
                var extensions = (item.extensions && item.extensions.length > 0) ? item.extensions.join(', ') : '';
                var enabled = item.enabled !== false;
                var installed = item.installed !== false;
                var badges = '<span class="settings-inline-tag">[lsp]</span>';
                if (item.scope === 'workspace') badges += ' <span class="mounts-scope-badge scope-workspace">' + I18n.t('lsp.scope.workspace') + '</span>';
                else if (item.scope === 'builtin') badges += ' <span class="mounts-scope-badge">' + I18n.t('lsp.scope.builtin') + '</span>';
                if (installed) badges += ' <span class="skill-installed-badge">' + I18n.t('lsp.installed') + '</span>';
                html += '<div class="settings-list-item' + (item.enabled === false ? ' disabled' : '') + '" data-name="' + escapeAttr(name) + '">'
                    + '<div class="settings-list-icon">L</div>'
                    + '<div class="settings-list-info">'
                    + '<div class="settings-list-title">' + escapeHtml(name) + ' ' + badges + '</div>'
                    + (command ? '<div class="settings-list-desc">' + escapeHtml(command) + '</div>' : '')
                    + (extensions ? '<div class="settings-list-desc settings-accent-text">' + escapeHtml(extensions) + '</div>' : '')
                    + '</div><div class="settings-list-actions">'
                    + '<button class="settings-action-btn edit" data-name="' + escapeAttr(name) + '" title="' + I18n.t('lsp.edit') + '"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>'
                    + '<label class="toggle-switch" title="' + (enabled ? I18n.t('lsp.toggle.disable') : I18n.t('lsp.toggle.enable')) + '">'
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
        $lspSaveBtn.text(I18n.t('common.save'));
        $('#lspName').val('').prop('readOnly', false).removeClass('readonly-gray');
        $('#lspCommand, #lspExtensions, #lspEnv').val('');
        setScopeValue('lspScope', 'user');
        setScopeReadonly('lspScope', false);
        $('#lspFormDeleteBtn').hide();
    }

    function fillLspForm(server) {
        //内置服务器本身无作用域：编辑它等于为当前工作区新建一条覆盖
        setScopeValue('lspScope', server.scope === 'builtin' ? 'workspace' : (server.scope || 'user'));
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
        if (!name) { showToast(I18n.t('lsp.nameRequired'), 'error'); return null; }
        if (!/^[a-zA-Z0-9_-]+$/.test(name)) { showToast(I18n.t('lsp.nameInvalid'), 'error'); return null; }
        if (!command) { showToast(I18n.t('lsp.commandRequired'), 'error'); return null; }
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
        showLspFormView(I18n.t('lsp.editTitle'), true);
        $lspSaveBtn.text(I18n.t('lsp.updateBtn'));
        $('#lspName').val(server.name).prop('readOnly', true).addClass('readonly-gray');
        setScopeValue('lspScope', server.scope === 'builtin' ? 'workspace' : (server.scope || 'user'));
        //内置服务器不存在于配置文件，没有可删除的东西
        if (server.scope === 'builtin') { $('#lspFormDeleteBtn').hide(); } else { $('#lspFormDeleteBtn').show(); }
        fillLspForm(server);
    }

    function lspToggleServer(name, enabled) {
        postJson('/web/settings/lsp/servers/toggle', { name: name, enabled: enabled }, function (resp) {
            if (resp.code !== 200) { showToast(I18n.t('toast.operateFailed') + ': ' + (resp.message || I18n.t('toast.unknownError')), 'error'); loadLspList(); }
            else { loadLspList(); }
        });
    }

    // LSP 按钮事件
    $('#lspAddBtn').on('click', function () { resetLspForm(); showLspFormView(I18n.t('lsp.addTitle'), false); });
    $('#lspBackBtn').on('click', function () { showLspListView(); resetLspForm(); });

    // LSP 表单 - 删除按钮
    $('#lspFormDeleteBtn').on('click', function () {
        var name = lspEditName;
        if (!name) return;
        layer.confirm(I18n.t('lsp.deleteConfirm', { name: name }), { title: I18n.t('lsp.deleteConfirmTitle'), btn: [I18n.t('lsp.deleteBtn'), I18n.t('common.cancel')], icon: 3, offset: '120px' }, function(index) {
            layer.close(index);
            postJson('/web/settings/lsp/servers/remove', { name: name }, function (resp) {
                if (resp.code === 200) { showLspListView(); loadLspList(); }
                else showToast(I18n.t('lsp.deleteFailed') + ': ' + (resp.message || I18n.t('toast.unknownError')), 'error');
            });
        });
    });

    $lspSaveBtn.on('click', function () {
        var bodyObj = buildLspBodyObj();
        if (!bodyObj) return;
        var isEdit = !!lspEditName;
        var url = isEdit ? '/web/settings/lsp/servers/update' : '/web/settings/lsp/servers/add';
        if (isEdit) bodyObj.originalName = lspEditName;

        $lspSaveBtn.prop('disabled', true);
        $.ajax({ url: url, method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json' })
            .done(function (resp) {
                if (resp.code === 200) { showToast(isEdit ? I18n.t('lsp.updated') : I18n.t('lsp.added')); loadLspList(); showLspListView(); resetLspForm(); }
                else showToast((isEdit ? I18n.t('lsp.updateFailed') : I18n.t('lsp.addFailed')) + ': ' + (resp.message || I18n.t('toast.unknownError')), 'error');
            })
            .fail(function () { showToast(I18n.t('toast.networkError'), 'error'); })
            .always(function () { $lspSaveBtn.prop('disabled', false); });
    });



    window._settingsLsp = { load: loadLspList, reset: resetLspForm, showList: showLspListView };
})();

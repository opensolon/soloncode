/**
 * app-settings-openapi.js — 设置面板子模块
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

    var $openapiServerList = $('#openapiServerList');
    var $openapiSaveBtn = $('#openapiSaveBtn');
    var $openapiFormTitle = $('#openapiFormTitle');
    var $openapiListView = $('#openapiListView');
    var $openapiFormView = $('#openapiFormView');
    var $openapiCheckResult = $('#openapiCheckResult');
    var $openapiApisView = $('#openapiApisView');
    var $openapiApisList = $('#openapiApisList');
    var $openapiApisTitle = $('#openapiApisTitle');
    var openapiApisCurrentName = null;
    var openapiEditName = null;
    var openapiCachedList = [];

    function showOpenapiListView() { $openapiFormView.hide(); $openapiApisView.hide(); $openapiListView.addClass('slide-back').show(); setTimeout(function(){ $openapiListView.removeClass('slide-back'); }, 260); }
    function showOpenapiApisView(title) { $openapiListView.hide(); $openapiFormView.hide(); $openapiApisTitle.text(title || I18n.t('openapi.apiListTitleFallback')); $openapiApisView.show(); }
    function showOpenapiFormView(title, isEdit) { $openapiApisView.hide(); $openapiFormTitle.text(title || I18n.t('openapi.addTitle')); $openapiListView.hide(); $openapiFormView.show(); $('#openapiFormActions').toggle(!!isEdit); }

    // ==================== OpenApi 管理 ====================

    function loadOpenapiList() {
        $.get('/web/settings/openapi/servers', function (resp) {
            if (resp.code === 200 && resp.data) {
                openapiCachedList = resp.data;
                renderOpenapiList(resp.data);
            }
        }).fail(function () { console.error('[Settings] Failed to load OpenApi servers'); });
    }

    function renderOpenapiList(list) {
        var html = '';
        if (!list || list.length === 0) {
            html = '<div class="mcp-empty-state">'
                + '<div class="mcp-empty-icon"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg></div>'
                + '<div class="mcp-empty-title">' + I18n.t('openapi.empty') + '</div>'
                + '<div class="mcp-empty-desc">' + I18n.t('openapi.emptyDesc') + '</div>'
                + '</div>';
        } else {
            list.forEach(function (item) {
                var name = item.name || '';
                var baseUrl = item.apiBaseUrl || '';
                var docUrl = item.docUrl || '';
                var enabled = item.enabled !== false;
                html += '<div class="settings-list-item' + (item.enabled === false ? ' disabled' : '') + '" data-name="' + escapeAttr(name) + '">'
                    + '<div class="settings-list-icon">A</div>'
                    + '<div class="settings-list-info">'
                    + '<div class="settings-list-title">' + escapeHtml(name) + ' <span class="settings-inline-tag">[openapi]</span>' + (item.scope === 'workspace' ? ' <span class="mounts-scope-badge scope-workspace">' + I18n.t('openapi.scope.workspace') + '</span>' : '') + '</div>'
                    + (baseUrl ? '<div class="settings-list-desc">' + escapeHtml(baseUrl) + '</div>' : '')
                    + (docUrl ? '<div class="settings-list-desc settings-accent-text">' + escapeHtml(docUrl) + '</div>' : '')
                    + '</div><div class="settings-list-actions">'
                    + '<button class="settings-action-btn edit" data-name="' + escapeAttr(name) + '" title="' + I18n.t('openapi.edit') + '"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>'
                    + '<label class="toggle-switch" title="' + (enabled ? I18n.t('openapi.toggle.disable') : I18n.t('openapi.toggle.enable')) + '">'
                    + '<input type="checkbox" ' + (enabled ? 'checked' : '') + ' data-name="' + escapeAttr(name) + '" class="openapi-toggle"/>'
                    + '<span class="toggle-slider"></span>'
                    + '</label>'
                    + '</div></div>';
            });
        }
        $openapiServerList.html(html);
    }

    // OpenApi 列表事件委托
    $openapiServerList
        .on('click', '.settings-action-btn.edit', function (e) {
            e.stopPropagation();
            var name = $(this).attr('data-name');
            if (name) openapiEditServer(name);
        })
        .on('click', '.settings-list-item', function (e) {
            if ($(e.target).closest('.settings-action-btn').length) return;
            if ($(e.target).closest('.toggle-switch').length) return;
            var name = $(this).attr('data-name');
            if (name) loadOpenapiApis(name);
        })
        .on('change', '.openapi-toggle', function () {
            openapiToggleServer($(this).attr('data-name'), this.checked);
        });

    // ==================== OpenApi API 列表查看 ====================

    function loadOpenapiApis(name) {
        openapiApisCurrentName = name;
        showOpenapiApisView(name + I18n.t('openapi.apiListTitle'));
        $openapiApisList.html('<div class="mcp-empty-state"><div class="skills-loading" style="display:block"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="animation:spin 1s linear infinite"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg><span>' + I18n.t('common.loading') + '</span></div></div>');

        $.get('/web/settings/openapi/servers/apis?name=' + encodeURIComponent(name), function (resp) {
            if (resp.code === 200 && resp.data) renderOpenapiApis(resp.data);
            else {
                $openapiApisList.html('<div class="mcp-empty-state"><div class="mcp-empty-title">' + escapeHtml(resp.message || I18n.t('openapi.loadFailed')) + '</div></div>');
            }
        }).fail(function () {
            $openapiApisList.html('<div class="mcp-empty-state"><div class="mcp-empty-title">' + I18n.t('openapi.loadFailed') + '</div></div>');
        });
    }

    /** 更新 OpenAPI 工具栏计数和全选状态 */
    function updateOpenapiApisToolbar() {
        var $toggles = $openapiApisList.find('.openapi-api-toggle');
        var total = $toggles.length;
        var checked = $toggles.filter(':checked').length;
        $('#openapiApisCount').text(I18n.t('openapi.toolsEnabledPart', { n: checked, m: total }));
        $('#openapiApisSelectAll').prop('checked', total > 0 && checked === total);
    }

    function renderOpenapiApis(data) {
        var connected = data.connected !== false;
        var apis = data.apis || [];
        var $toolbar = $('#openapiApisToolbar');
        var html = '';
        if (!connected) {
            $toolbar.hide();
            html = '<div class="mcp-empty-state">'
                + '<div class="mcp-empty-icon"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg></div>'
                + '<div class="mcp-empty-title">' + I18n.t('openapi.serverOffline') + '</div>'
                + '<div class="mcp-empty-desc">' + I18n.t('openapi.serverOfflineHint') + '</div></div>';
        } else if (apis.length === 0) {
            $toolbar.hide();
            html = '<div class="mcp-empty-state">'
                + '<div class="mcp-empty-icon"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg></div>'
                + '<div class="mcp-empty-title">' + I18n.t('openapi.noApi') + '</div>'
                + '<div class="mcp-empty-desc">' + I18n.t('openapi.noApiDesc') + '</div></div>';
        } else {
            // 获取已禁用的 API 列表
            var disallowedTools = data.disallowedTools || [];
            var disallowedMap = {};
            disallowedTools.forEach(function (t) { disallowedMap[t] = true; });

            // 显示工具栏
            $toolbar.show();
            var checkedCount = apis.filter(function (api) { return !disallowedMap[api.name]; }).length;
            $('#openapiApisCount').text(I18n.t('openapi.toolsEnabledPart', { n: checkedCount, m: apis.length }));
            $('#openapiApisSelectAll').prop('checked', checkedCount === apis.length);

            apis.forEach(function (api) {
                var method = (api.method || 'GET').toUpperCase();
                var apiName = api.name || '';
                var isEnabled = !disallowedMap[apiName];
                html += '<div class="openapi-api-item" data-name="' + escapeAttr(apiName) + '">'
                    + '<div class="openapi-api-checkbox">'
                    + '<input type="checkbox" ' + (isEnabled ? 'checked' : '') + ' data-api="' + escapeAttr(apiName) + '" class="openapi-api-toggle" title="' + (isEnabled ? I18n.t('openapi.toolDisable') : I18n.t('openapi.toolEnable')) + '"/>'
                    + '</div>'
                    + '<div class="openapi-api-method">' + escapeHtml(method) + '</div>'
                    + '<div class="openapi-api-info">'
                    + '<div class="openapi-api-path">' + escapeHtml(api.path || apiName) + '</div>'
                    + (api.description ? '<div class="openapi-api-desc">' + escapeHtml(api.description) + '</div>' : '')
                    + '</div>'
                    + '</div>';
            });
        }
        $openapiApisList.html(html);
    }

    // ==================== OpenApi 表单 ====================

    function resetOpenapiForm() {
        openapiEditName = null;
        $openapiSaveBtn.text(I18n.t('common.save'));
        $('#openapiName').val('').prop('readOnly', false).removeClass('readonly-gray');
        $('#openapiBaseUrl, #openapiDocUrl, #openapiHeaders').val('');
        setScopeValue('openapiScope', 'user');
        setScopeReadonly('openapiScope', false);
    }

    function fillOpenapiForm(server) {
        setScopeValue('openapiScope', server.scope || 'user');
        $('#openapiBaseUrl').val(server.apiBaseUrl || '');
        $('#openapiDocUrl').val(server.docUrl || '');
        var headerLines = [];
        if (server.headers) Object.keys(server.headers).forEach(function (k) { headerLines.push(k + '=' + server.headers[k]); });
        $('#openapiHeaders').val(headerLines.join('\n'));
    }

    function buildOpenapiBodyObj() {
        var name = $('#openapiName').val().trim();
        var baseUrl = $('#openapiBaseUrl').val().trim();
        var docUrl = $('#openapiDocUrl').val().trim();
        var headersText = $('#openapiHeaders').val().trim();
        if (!name) { showToast(I18n.t('openapi.nameRequired'), 'error'); return null; }
        if (!/^[a-zA-Z0-9_-]+$/.test(name)) { showToast(I18n.t('openapi.nameInvalid'), 'error'); return null; }
        if (!baseUrl) { showToast(I18n.t('openapi.baseUrlRequired'), 'error'); return null; }
        if (!docUrl) { showToast(I18n.t('openapi.docUrlRequired'), 'error'); return null; }
        var bodyObj = { name: name, apiBaseUrl: baseUrl, docUrl: docUrl, enabled: true, scope: $('#openapiScope').val() || 'user' };
        var headers = parseKvLines(headersText);
        if (Object.keys(headers).length > 0) bodyObj.headers = headers;
        return bodyObj;
    }

    function openapiEditServer(name) {
        var server = openapiCachedList.find(function (s) { return s.name === name; });
        if (!server) return;
        openapiEditName = name;
        showOpenapiFormView(I18n.t('openapi.editTitle'), true);
        $openapiSaveBtn.text(I18n.t('openapi.updateBtn'));
        $('#openapiName').val(server.name).prop('readOnly', true).addClass('readonly-gray');
        fillOpenapiForm(server);
    }

    function openapiCopyServer(name) {
        var server = openapiCachedList.find(function (s) { return s.name === name; });
        if (!server) return;
        openapiEditName = null;
        showOpenapiFormView(I18n.t('openapi.addTitle'), false);
        $openapiSaveBtn.text(I18n.t('common.save'));
        $('#openapiName').val(server.name + '-copy').prop('readOnly', false).removeClass('readonly-gray');
        fillOpenapiForm(server);
    }

    function openapiRemoveServer(name) {
        postJson('/web/settings/openapi/servers/remove', { name: name }, function (resp) {
            if (resp.code === 200) { showOpenapiListView(); loadOpenapiList(); }
            else showToast(I18n.t('openapi.deleteFailed') + ': ' + (resp.message || I18n.t('toast.unknownError')), 'error');
        });
    }

    function openapiToggleServer(name, enabled) {
        postJson('/web/settings/openapi/servers/toggle', { name: name, enabled: enabled }, function (resp) {
            if (resp.code !== 200) { showToast(I18n.t('toast.operateFailed') + ': ' + (resp.message || I18n.t('toast.unknownError')), 'error'); loadOpenapiList(); }
            else { loadOpenapiList(); }
        });
    }

    // OpenApi 按钮事件
    $('#openapiAddBtn').on('click', function () { resetOpenapiForm(); showOpenapiFormView(I18n.t('openapi.addTitle'), false); });
    $('#openapiBackBtn').on('click', function () { showOpenapiListView(); resetOpenapiForm(); });
    $('#openapiApisBackBtn').on('click', function () { showOpenapiListView(); loadOpenapiList(); });

    // OpenAPI API 开关变化 → 实时更新计数和全选状态
    $openapiApisList.on('change', '.openapi-api-toggle', function () {
        updateOpenapiApisToolbar();
    });

    // OpenAPI API 全选/取消全选
    $('#openapiApisSelectAll').on('change', function () {
        var checked = this.checked;
        $openapiApisList.find('.openapi-api-toggle').prop('checked', checked);
        updateOpenapiApisToolbar();
    });

    // OpenAPI API 保存权限（提交未勾选的作为 disallowedTools）
    $('#openapiApisSaveBtn').on('click', function () {
        if (!openapiApisCurrentName) return;
        var disallowedTools = [];
        $openapiApisList.find('.openapi-api-toggle:not(:checked)').each(function () {
            disallowedTools.push($(this).attr('data-api'));
        });
        var $btn = $(this);
        $btn.prop('disabled', true);
        postJson('/web/settings/openapi/servers/apis/save',
            { serverName: openapiApisCurrentName, disallowedTools: disallowedTools },
            function (resp) {
                if (resp.code === 200) showToast(I18n.t('openapi.permissionsSaved'));
                else showToast(I18n.t('toast.saveFailed') + ': ' + (resp.message || I18n.t('toast.unknownError')), 'error');
            },
            function () { $btn.prop('disabled', false); }
        );
    });

    // OpenApi 测试连接
    $('#openapiTestBtn').on('click', function () {
        var bodyObj = buildOpenapiBodyObj();
        if (!bodyObj) return;
        var $btn = $(this);
        var btnOriginal = $btn.html();
        $btn.prop('disabled', true).html('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="animation:spin 1s linear infinite"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg> ' + I18n.t('openapi.testing'));
        $openapiCheckResult.hide();

        $.ajax({ url: '/web/settings/openapi/servers/check', method: 'POST', data: JSON.stringify({ apiBaseUrl: bodyObj.apiBaseUrl, docUrl: bodyObj.docUrl, headers: bodyObj.headers || {} }), contentType: 'application/json', dataType: 'json', timeout: 15000 })
            .done(function (resp) {
                var ok = resp.code === 200;
                var svg = ok
                    ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg> ' + I18n.t('openapi.testOk')
                    : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg> ' + (resp.message || I18n.t('openapi.testFail'));
                $openapiCheckResult.attr('class', 'llm-check-result ' + (ok ? 'success' : 'error')).html(svg).css('display', 'flex');
            })
            .fail(function (jqXHR, textStatus) {
                var msg = textStatus === 'timeout' ? I18n.t('openapi.testTimeout') : I18n.t('openapi.testNetworkError');
                $openapiCheckResult.attr('class', 'llm-check-result error').html(msg).css('display', 'flex');
            })
            .always(function () { $btn.prop('disabled', false).html(btnOriginal); });
    });

    $openapiSaveBtn.on('click', function () {
        var bodyObj = buildOpenapiBodyObj();
        if (!bodyObj) return;
        var isEdit = !!openapiEditName;
        var url = isEdit ? '/web/settings/openapi/servers/update' : '/web/settings/openapi/servers/add';
        if (isEdit) bodyObj.originalName = openapiEditName;

        $openapiSaveBtn.prop('disabled', true);
        $.ajax({ url: url, method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json' })
            .done(function (resp) {
                if (resp.code === 200) { showToast(isEdit ? I18n.t('openapi.updated') : I18n.t('openapi.added')); loadOpenapiList(); showOpenapiListView(); resetOpenapiForm(); }
                else showToast((isEdit ? I18n.t('openapi.updateFailed') : I18n.t('openapi.addFailed')) + ': ' + (resp.message || I18n.t('toast.unknownError')), 'error');
            })
            .fail(function () { showToast(I18n.t('toast.networkError'), 'error'); })
            .always(function () { $openapiSaveBtn.prop('disabled', false); });
    });

    // OpenApi 表单 - 复制按钮
    $('#openapiFormCopyBtn').on('click', function () {
        var name = openapiEditName;
        if (!name) return;
        openapiCopyServer(name);
    });
    // OpenApi 表单 - 删除按钮
    $('#openapiFormDeleteBtn').on('click', function () {
        var name = openapiEditName;
        if (!name) return;
        layer.confirm(I18n.t('openapi.deleteConfirm', { name: name }), { title: I18n.t('openapi.deleteConfirmTitle'), btn: [I18n.t('openapi.deleteBtn'), I18n.t('common.cancel')], icon: 3, offset: '120px' }, function(index) {
            layer.close(index);
            openapiRemoveServer(name);
        });
    });



    window._settingsOpenapi = { load: loadOpenapiList, reset: resetOpenapiForm, showList: showOpenapiListView };
})();

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
    function showOpenapiApisView(title) { $openapiListView.hide(); $openapiFormView.hide(); $openapiApisTitle.text(title || 'API 列表'); $openapiApisView.show(); }
    function showOpenapiFormView(title, isEdit) { $openapiApisView.hide(); $openapiFormTitle.text(title || '添加服务器'); $openapiListView.hide(); $openapiFormView.show(); $('#openapiFormActions').toggle(!!isEdit); }

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
                + '<div class="mcp-empty-title">暂无 OpenApi 服务器</div>'
                + '<div class="mcp-empty-desc">OpenApi 服务器可扩展 AI 的 API 调用能力，对接外部 RESTful 接口</div>'
                + '</div>';
        } else {
            list.forEach(function (item) {
                var name = item.name || '';
                var baseUrl = item.apiBaseUrl || '';
                var docUrl = item.docUrl || '';
                var enabled = item.enabled !== false;
                html += '<div class="mcp-server-item" data-name="' + escapeAttr(name) + '">'
                    + '<div class="mcp-server-icon">A</div>'
                    + '<div class="mcp-server-info">'
                    + '<div class="mcp-server-name">' + escapeHtml(name) + ' <span class="settings-inline-tag">[openapi]</span>' + (item.scope === 'workspace' ? ' <span class="mounts-scope-badge scope-workspace">工作区</span>' : '') + '</div>'
                    + (baseUrl ? '<div class="mcp-server-detail">' + escapeHtml(baseUrl) + '</div>' : '')
                    + (docUrl ? '<div class="mcp-server-detail settings-accent-text">' + escapeHtml(docUrl) + '</div>' : '')
                    + '</div><div class="mcp-server-actions">'
                    + '<button class="mcp-action-btn edit" data-name="' + escapeAttr(name) + '" title="编辑"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>'
                    + '<label class="toggle-switch" title="' + (enabled ? '停用' : '启用') + '">'
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
        .on('click', '.mcp-action-btn.edit', function (e) {
            e.stopPropagation();
            var name = $(this).attr('data-name');
            if (name) openapiEditServer(name);
        })
        .on('click', '.mcp-server-item', function (e) {
            if ($(e.target).closest('.mcp-action-btn').length) return;
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
        showOpenapiApisView(name + ' - 接口列表');
        $openapiApisList.html('<div class="mcp-empty-state"><div class="skills-loading" style="display:block"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="animation:spin 1s linear infinite"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg><span>加载中...</span></div></div>');

        $.get('/web/settings/openapi/servers/apis?name=' + encodeURIComponent(name), function (resp) {
            if (resp.code === 200 && resp.data) renderOpenapiApis(resp.data);
            else {
                $openapiApisList.html('<div class="mcp-empty-state"><div class="mcp-empty-title">' + escapeHtml(resp.message || '加载失败') + '</div></div>');
            }
        }).fail(function () {
            $openapiApisList.html('<div class="mcp-empty-state"><div class="mcp-empty-title">加载失败，请检查网络</div></div>');
        });
    }

    /** 更新 OpenAPI 工具栏计数和全选状态 */
    function updateOpenapiApisToolbar() {
        var $toggles = $openapiApisList.find('.openapi-api-toggle');
        var total = $toggles.length;
        var checked = $toggles.filter(':checked').length;
        $('#openapiApisCount').text(checked + ' / ' + total + ' 已启用');
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
                + '<div class="mcp-empty-title">未连接</div>'
                + '<div class="mcp-empty-desc">服务器未启用或文档未加载，请先启用服务器</div></div>';
        } else if (apis.length === 0) {
            $toolbar.hide();
            html = '<div class="mcp-empty-state">'
                + '<div class="mcp-empty-icon"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg></div>'
                + '<div class="mcp-empty-title">暂无 API</div>'
                + '<div class="mcp-empty-desc">该服务器未解析到任何 API 接口</div></div>';
        } else {
            // 获取已禁用的 API 列表
            var disallowedTools = data.disallowedTools || [];
            var disallowedMap = {};
            disallowedTools.forEach(function (t) { disallowedMap[t] = true; });

            // 显示工具栏
            $toolbar.show();
            var checkedCount = apis.filter(function (api) { return !disallowedMap[api.name]; }).length;
            $('#openapiApisCount').text(checkedCount + ' / ' + apis.length + ' 已启用');
            $('#openapiApisSelectAll').prop('checked', checkedCount === apis.length);

            apis.forEach(function (api) {
                var method = (api.method || 'GET').toUpperCase();
                var apiName = api.name || '';
                var isEnabled = !disallowedMap[apiName];
                html += '<div class="openapi-api-item" data-name="' + escapeAttr(apiName) + '">'
                    + '<div class="openapi-api-checkbox">'
                    + '<input type="checkbox" ' + (isEnabled ? 'checked' : '') + ' data-api="' + escapeAttr(apiName) + '" class="openapi-api-toggle" title="' + (isEnabled ? '禁用' : '启用') + '"/>'
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
        $openapiSaveBtn.text('保存');
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
        if (!name) { showToast('名称为必填项', 'error'); return null; }
        if (!/^[a-zA-Z0-9_-]+$/.test(name)) { showToast('名称仅允许字母、数字、下划线和连字符', 'error'); return null; }
        if (!baseUrl) { showToast('API 基地址为必填项', 'error'); return null; }
        if (!docUrl) { showToast('文档地址为必填项', 'error'); return null; }
        var bodyObj = { name: name, apiBaseUrl: baseUrl, docUrl: docUrl, enabled: true, scope: $('#openapiScope').val() || 'user' };
        var headers = parseKvLines(headersText);
        if (Object.keys(headers).length > 0) bodyObj.headers = headers;
        return bodyObj;
    }

    function openapiEditServer(name) {
        var server = openapiCachedList.find(function (s) { return s.name === name; });
        if (!server) return;
        openapiEditName = name;
        showOpenapiFormView('编辑服务器', true);
        $openapiSaveBtn.text('更新');
        $('#openapiName').val(server.name).prop('readOnly', true).addClass('readonly-gray');
        fillOpenapiForm(server);
    }

    function openapiCopyServer(name) {
        var server = openapiCachedList.find(function (s) { return s.name === name; });
        if (!server) return;
        openapiEditName = null;
        showOpenapiFormView('添加服务器', false);
        $openapiSaveBtn.text('保存');
        $('#openapiName').val(server.name + '-copy').prop('readOnly', false).removeClass('readonly-gray');
        fillOpenapiForm(server);
    }

    function openapiRemoveServer(name) {
        postJson('/web/settings/openapi/servers/remove', { name: name }, function (resp) {
            if (resp.code === 200) { showOpenapiListView(); loadOpenapiList(); }
            else showToast('删除失败: ' + (resp.message || '未知错误'), 'error');
        });
    }

    function openapiToggleServer(name, enabled) {
        postJson('/web/settings/openapi/servers/toggle', { name: name, enabled: enabled }, function (resp) {
            if (resp.code !== 200) { showToast('操作失败: ' + (resp.message || '未知错误'), 'error'); loadOpenapiList(); }
        });
    }

    // OpenApi 按钮事件
    $('#openapiAddBtn').on('click', function () { resetOpenapiForm(); showOpenapiFormView('添加服务器', false); });
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
                if (resp.code === 200) showToast('API 权限已保存');
                else showToast('保存失败: ' + (resp.message || '未知错误'), 'error');
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
        $btn.prop('disabled', true).html('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="animation:spin 1s linear infinite"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg> 测试中...');
        $openapiCheckResult.hide();

        $.ajax({ url: '/web/settings/openapi/servers/check', method: 'POST', data: JSON.stringify({ baseUrl: bodyObj.apiBaseUrl, docUrl: bodyObj.docUrl, headers: bodyObj.headers || {} }), contentType: 'application/json', dataType: 'json', timeout: 15000 })
            .done(function (resp) {
                var ok = resp.code === 200;
                var svg = ok
                    ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg> 连接成功'
                    : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg> ' + (resp.message || '连接失败');
                $openapiCheckResult.attr('class', 'llm-check-result ' + (ok ? 'success' : 'error')).html(svg).css('display', 'flex');
            })
            .fail(function (jqXHR, textStatus) {
                var msg = textStatus === 'timeout' ? '连接超时（15秒），请检查地址是否正确' : '网络错误，请重试';
                $openapiCheckResult.attr('class', 'llm-check-result error').html(msg).css('display', 'flex');
            })
            .always(function () { $btn.prop('disabled', false).html(btnOriginal); });
    });

    $openapiSaveBtn.on('click', function () {
        var bodyObj = buildOpenapiBodyObj();
        if (!bodyObj) return;
        var isEdit = !!openapiEditName;
        var url = isEdit ? '/web/settings/openapi/servers/update' : '/web/settings/openapi/servers/add';
        var actionText = isEdit ? '更新' : '添加';
        if (isEdit) bodyObj.originalName = openapiEditName;

        $openapiSaveBtn.prop('disabled', true);
        $.ajax({ url: url, method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json' })
            .done(function (resp) {
                if (resp.code === 200) { showToast(actionText + '成功'); loadOpenapiList(); showOpenapiListView(); resetOpenapiForm(); }
                else showToast(actionText + '失败: ' + (resp.message || '未知错误'), 'error');
            })
            .fail(function () { showToast('网络错误', 'error'); })
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
        if (confirm('确定删除 OpenApi 服务器 "' + name + '"？')) {
            openapiRemoveServer(name);
        }
    });



    window._settingsOpenapi = { load: loadOpenapiList, reset: resetOpenapiForm, showList: showOpenapiListView };
})();

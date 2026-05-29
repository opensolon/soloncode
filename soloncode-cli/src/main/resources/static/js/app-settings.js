/**
 * app-settings.js — 设置面板交互逻辑（LLM 模型 + MCP 服务器 + OpenApi）
 *
 * 依赖：layui.js（jQuery）、app-base.js
 * 协同：app-history.js（modelsLoaded / commandList / loadCommands）、app-settings-skill.js（技能市场）
 */

(function () {
    'use strict';

    // ==================== 工具函数 ====================

    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function escapeAttr(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/'/g, '&#39;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    /** 解析 key=value 文本为对象 */
    function parseKvLines(text) {
        var obj = {};
        (text || '').split('\n').forEach(function (line) {
            var idx = line.indexOf('=');
            if (idx > 0) obj[line.substring(0, idx).trim()] = line.substring(idx + 1).trim();
        });
        return obj;
    }

    /** 下载 JSON 文件 */
    function downloadJson(obj, filename) {
        var blob = new Blob([JSON.stringify(obj, null, 2)], { type: 'application/json' });
        var a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = filename;
        a.click();
        URL.revokeObjectURL(a.href);
    }

    /** 通用 POST JSON */
    function postJson(url, data, doneFn, alwaysFn) {
        return $.ajax({ url: url, method: 'POST', data: JSON.stringify(data), contentType: 'application/json', dataType: 'json' })
            .done(function (resp) { doneFn(resp); })
            .fail(function () { showToast('网络错误', 'error'); })
            .always(function () { if (alwaysFn) alwaysFn(); });
    }

    /** 带 loading 状态的检测按钮请求 */
    function checkWithLoading(opts) {
        var $btn = opts.$btn;
        var btnOriginal = $btn.html();
        var loadingSvg = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="animation:spin 1s linear infinite"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg> ' + opts.loadingText;
        var okSvg = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg> ';
        var failSvg = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg> ';

        $btn.prop('disabled', true).html(loadingSvg);
        opts.$result.hide();

        $.ajax($.extend({ timeout: 15000, dataType: 'json' }, opts.ajax))
            .done(function (resp) {
                var ok = resp.code === 200;
                if (opts.isList && ok) {
                    ok = resp.data && resp.data.length > 0;
                }
                opts.$result.attr('class', opts.cls + ' ' + (ok ? 'success' : 'error'))
                    .html((ok ? okSvg : failSvg) + escapeHtml(ok ? (opts.successMsg || '连接成功') : (resp.message || opts.failMsg || '连接失败')))
                    .css('display', 'flex');
            })
            .fail(function (jqXHR, textStatus) {
                var msg = textStatus === 'timeout' ? '连接超时（15秒），请检查地址是否正确' : '网络错误，请重试';
                opts.$result.attr('class', opts.cls + ' error').html(msg).css('display', 'flex');
            })
            .always(function () { $btn.prop('disabled', false).html(btnOriginal); });
    }

    // ==================== 统一提示 ====================

    function showToast(msg, type) {
        if (typeof layer !== 'undefined' && layer.msg) {
            layer.msg(msg, { icon: type === 'error' ? 2 : 1, time: 2500, offset: '120px' });
        } else {
            alert(msg);
        }
    }

    // ==================== DOM 引用 ====================

    var $overlay = $('#settingsOverlay');
    var $settingsBtn = $('#settingsBtn');
    var $tabs = $('.settings-tab');
    var $tabContents = $('.settings-tab-content');

    // LLM
    var $llmModelList = $('#llmModelList');
    var $llmCheckResult = $('#llmCheckResult');
    var $llmSaveBtn = $('#llmSaveBtn');
    var $llmFormTitle = $('#llmFormTitle');
    var $llmListView = $('#llmListView');
    var $llmFormView = $('#llmFormView');

    // MCP
    var $mcpServerList = $('#mcpServerList');
    var $mcpSaveBtn = $('#mcpSaveBtn');
    var $mcpFormTitle = $('#mcpFormTitle');
    var $mcpListView = $('#mcpListView');
    var $mcpFormView = $('#mcpFormView');
    var $mcpTypeBtns = $('.mcp-type-btn');
    var $mcpCheckResult = $('#mcpCheckResult');
    var $mcpImportDialog = $('#mcpImportDialog');

    // OpenApi
    var $webapiServerList = $('#webapiServerList');
    var $webapiSaveBtn = $('#webapiSaveBtn');
    var $webapiFormTitle = $('#webapiFormTitle');
    var $webapiListView = $('#webapiListView');
    var $webapiFormView = $('#webapiFormView');
    var $webapiCheckResult = $('#webapiCheckResult');
    var $webapiImportDialog = $('#webapiImportDialog');

    // ==================== 状态 ====================

    var llmEditModel = null;
    var llmCachedList = [];
    var mcpEditName = null;
    var mcpCachedList = [];
    var webapiEditName = null;
    var webapiCachedList = [];

    // ==================== 视图切换 ====================

    function showLlmListView() { $llmFormView.hide(); $llmListView.addClass('slide-back').show(); setTimeout(function(){ $llmListView.removeClass('slide-back'); }, 260); }
    function showLlmFormView(title) { $llmFormTitle.text(title || '添加模型'); $llmListView.hide(); $llmFormView.show(); }
    function showMcpListView() { $mcpFormView.hide(); $mcpListView.addClass('slide-back').show(); setTimeout(function(){ $mcpListView.removeClass('slide-back'); }, 260); }
    function showMcpFormView(title) { $mcpFormTitle.text(title || '添加服务器'); $mcpListView.hide(); $mcpFormView.show(); }
    function showWebapiListView() { $webapiFormView.hide(); $webapiListView.addClass('slide-back').show(); setTimeout(function(){ $webapiListView.removeClass('slide-back'); }, 260); }
    function showWebapiFormView(title) { $webapiFormTitle.text(title || '添加服务器'); $webapiListView.hide(); $webapiFormView.show(); }

    function setMcpType(type) {
        $mcpTypeBtns.removeClass('active');
        $('.mcp-type-btn[data-type="' + type + '"]').addClass('active');
        $('#mcpConfigStdio').toggle(type === 'stdio');
        $('#mcpConfigRemote').toggle(type === 'sse' || type === 'streamable');
    }

    // ==================== 面板开关 ====================

    function openSettings() {
        $overlay.css('display', 'flex');
        loadActiveTabData();
    }

    function closeSettings() {
        $overlay.hide();
        showLlmListView();
        showMcpListView();
        showWebapiListView();
        $llmCheckResult.hide();
        if ($('#skillsSearchInput').length) {
            $('#skillsSearchInput').val('');
            $('#skillsSearchClear').hide();
        }
    }

    $settingsBtn.on('click', openSettings);
    $overlay.on('click', function (e) { if (e.target === $overlay[0]) closeSettings(); });
    $(document).on('keydown', function (e) { if (e.key === 'Escape' && $overlay.is(':visible')) closeSettings(); });

    // ==================== Tab 切换（事件委托，统一管理） ====================

    $('.settings-tabs').on('click', '.settings-tab', function () {
        var $tab = $(this);
        $tabs.removeClass('active');
        $tabContents.removeClass('active');
        $tab.addClass('active');

        var targetTab = $tab.attr('data-tab');
        if (targetTab === 'llm') {
            $('#settingsTabLlm').addClass('active');
            loadLlmList();
        } else if (targetTab === 'skills') {
            $('#settingsTabSkills').addClass('active');
            if (window._skillModule) window._skillModule.resetAndLoad();
        } else if (targetTab === 'mcp') {
            $('#settingsTabMcp').addClass('active');
            loadMcpList();
        } else if (targetTab === 'webapi') {
            $('#settingsTabWebapi').addClass('active');
            loadWebapiList();
        }
    });

    function loadActiveTabData() {
        var $active = $('.settings-tab.active');
        if (!$active.length) return;
        var targetTab = $active.attr('data-tab');
        if (targetTab === 'llm') loadLlmList();
        else if (targetTab === 'skills') { if (window._skillModule) window._skillModule.resetAndLoad(); }
        else if (targetTab === 'mcp') loadMcpList();
        else if (targetTab === 'webapi') loadWebapiList();
    }

    // ==================== LLM 管理 ====================

    function loadLlmList() {
        $.get('/web/chat/models', function (resp) {
            if (resp.code === 200 && resp.data) {
                renderLlmList(resp.data.list || [], resp.data.selected || '');
            }
        }).fail(function () { console.error('[Settings] Failed to load models'); });
    }

    function renderLlmList(list, selected) {
        llmCachedList = list || [];
        var html = '';
        if (!list || list.length === 0) {
            html = '<div class="llm-empty-state">'
                + '<div class="llm-empty-icon"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg></div>'
                + '<div class="llm-empty-title">暂无大模型配置</div>'
                + '<div class="llm-empty-desc">添加至少一个大模型以开始 AI 对话</div>'
                + '<div class="llm-empty-templates">'
                + '<button class="llm-template-btn" data-api-url="https://api.openai.com/v1" data-provider="openai">OpenAI</button>'
                + '<button class="llm-template-btn" data-api-url="http://localhost:11434" data-provider="ollama">Ollama (本地)</button>'
                + '<button class="llm-template-btn" data-api-url="https://api.deepseek.com/v1" data-provider="deepseek">DeepSeek</button>'
                + '</div></div>';
        } else {
            var providerIcons = { 'openai': 'OAI', 'ollama': 'OLA', 'zhipu': 'ZP', 'deepseek': 'DS', 'baidu-qianfan': 'BD', 'ali-tongyi': 'ALI', 'moonshot': 'MS', 'minimax': 'MM' };
            list.forEach(function (item) {
                var modelName = item.model || '';
                var provider = item.provider || '';
                var name = item.name || '';
                var apiUrl = item.apiUrl || '';
                var isActive = (modelName === selected);
                var icon = providerIcons[provider] || modelName.substring(0, 2).toUpperCase();
                var apiUrlShort = apiUrl ? apiUrl.replace(/^https?:\/\//, '').split('/')[0] : '';

                html += '<div class="llm-model-item' + (isActive ? ' active' : '') + '" data-model="' + escapeAttr(modelName) + '">'
                    + '<div class="llm-model-icon">' + escapeHtml(icon) + '</div>'
                    + '<div class="llm-model-info"><div class="llm-model-name">' + escapeHtml(modelName) + '</div><div class="llm-model-meta">'
                    + (provider ? '<span class="llm-provider-tag">' + escapeHtml(provider) + '</span>' : '')
                    + (name && name !== modelName ? '<span class="llm-alias-hint">' + escapeHtml(name) + '</span>' : '')
                    + (apiUrlShort ? '<span class="llm-api-hint">' + escapeHtml(apiUrlShort) + '</span>' : '')
                    + '</div></div><div class="llm-model-actions">'
                    + (isActive ? '<span class="llm-active-badge">活跃</span>' : '')
                    + (!isActive ? '<button class="llm-action-btn set-default" data-model="' + escapeAttr(modelName) + '" title="设为默认"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg></button>' : '')
                    + '<button class="llm-action-btn copy" data-model="' + escapeAttr(modelName) + '" title="复制"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg></button>'
                    + '<button class="llm-action-btn edit" data-model="' + escapeAttr(modelName) + '" title="编辑"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>'
                    + (!isActive ? '<button class="llm-action-btn delete" data-model="' + escapeAttr(modelName) + '" title="删除"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg></button>' : '')
                    + '</div></div>';
            });
        }
        $llmModelList.html(html);
    }

    // LLM 列表事件委托（一次绑定，无需每次 render 后重绑）
    $llmModelList
        .on('click', '.llm-template-btn', function () {
            resetLlmForm();
            showLlmFormView('添加模型');
            $('#llmApiUrl').val($(this).attr('data-api-url'));
            $('#llmProvider').val($(this).attr('data-provider'));
            $('#llmModel').focus();
        })
        .on('click', '.llm-action-btn.set-default', function () {
            llmSetDefaultModel($(this).attr('data-model'));
        })
        .on('click', '.llm-action-btn.copy', function () {
            llmCopyModel($(this).attr('data-model'));
        })
        .on('click', '.llm-action-btn.edit', function () {
            llmEditModelFunc($(this).attr('data-model'));
        })
        .on('click', '.llm-action-btn.delete', function () {
            var model = $(this).attr('data-model');
            if (confirm('确定删除模型 "' + model + '"？')) llmRemoveModel(model);
        });

    // ==================== LLM 表单 ====================

    function resetLlmForm() {
        llmEditModel = null;
        $llmSaveBtn.text('保存');
        $('#llmProvider, #llmApiUrl, #llmApiKey, #llmModel, #llmName, #llmTimeout, #llmContextLength, #llmDefaultOptions').val('');
        $('#llmApiKey').attr('placeholder', 'sk-...');
        $llmCheckResult.hide();
    }

    function fillLlmForm(item) {
        if (item.provider) $('#llmProvider').val(item.provider);
        if (item.apiUrl) $('#llmApiUrl').val(item.apiUrl);
        $('#llmApiKey').val('').attr('placeholder', item.apiKey ? '已配置（留空保持不变）' : 'sk-...');
        if (item.model) $('#llmModel').val(item.model);
        if (item.name && item.name !== item.model) $('#llmName').val(item.name);
        if (item.contextLength) $('#llmContextLength').val(item.contextLength);
        if (item.defaultOptions) $('#llmDefaultOptions').val(JSON.stringify(item.defaultOptions, null, 2));
    }

    function buildLlmBodyObj() {
        var apiUrl = $('#llmApiUrl').val().trim();
        var apiKey = $('#llmApiKey').val().trim();
        var model = $('#llmModel').val().trim();
        var alias = $('#llmName').val().trim();
        var provider = $('#llmProvider').val();
        var timeout = $('#llmTimeout').val().trim();
        if (!apiUrl || !model) { showToast('API 地址和模型名称为必填项', 'error'); return null; }
        var bodyObj = { apiUrl: apiUrl, model: model, name: alias || model, provider: provider };
        if (apiKey) bodyObj.apiKey = apiKey;
        if (timeout) bodyObj.timeout = timeout;
        var contextLength = $('#llmContextLength').val().trim();
        if (contextLength) bodyObj.contextLength = parseInt(contextLength, 10);
        var optionsText = $('#llmDefaultOptions').val().trim();
        if (optionsText) {
            try { bodyObj.defaultOptions = JSON.parse(optionsText); } catch (e) { /* 忽略无效 JSON */ }
        }
        return bodyObj;
    }

    function llmEditModelFunc(modelName) {
        var item = llmCachedList.find(function (m) { return m.model === modelName; });
        if (!item) return;
        llmEditModel = modelName;
        showLlmFormView('编辑模型');
        $llmSaveBtn.text('更新');
        fillLlmForm(item);
    }

    function llmCopyModel(modelName) {
        var item = llmCachedList.find(function (m) { return m.model === modelName; });
        if (!item) return;
        llmEditModel = null;
        showLlmFormView('添加模型');
        $llmSaveBtn.text('保存');
        fillLlmForm(item);
        $('#llmName').val((item.name || item.model) + '-copy');
    }

    function llmSetDefaultModel(modelName) {
        $.post('/web/settings/llm/models/setDefault?modelName=' + encodeURIComponent(modelName), function (resp) {
            if (resp.code === 200) {
                if (typeof modelsLoaded !== 'undefined') modelsLoaded = false;
                loadLlmList();
            } else { showToast('设置失败: ' + (resp.message || '未知错误'), 'error'); }
        });
    }

    function llmRemoveModel(modelName) {
        $.post('/web/settings/llm/models/remove?modelName=' + encodeURIComponent(modelName), function (resp) {
            if (resp.code === 200) {
                if (typeof modelsLoaded !== 'undefined') modelsLoaded = false;
                loadLlmList();
            } else { showToast('删除失败: ' + (resp.message || '未知错误'), 'error'); }
        });
    }

    // LLM 按钮事件
    $('#llmAddBtn').on('click', function () { llmEditModel = null; resetLlmForm(); showLlmFormView('添加模型'); });
    $('#llmBackBtn').on('click', function () { showLlmListView(); resetLlmForm(); });

    $llmSaveBtn.on('click', function () {
        var bodyObj = buildLlmBodyObj();
        if (!bodyObj) return;
        var isEdit = !!llmEditModel;
        var url = isEdit ? '/web/settings/llm/models/update' : '/web/settings/llm/models/add';
        var actionText = isEdit ? '更新' : '添加';
        if (isEdit) bodyObj.originalModel = llmEditModel;

        $llmSaveBtn.prop('disabled', true);
        $.ajax({ url: url, method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json' })
            .done(function (resp) {
                if (resp.code === 200) {
                    if (typeof modelsLoaded !== 'undefined') modelsLoaded = false;
                    loadLlmList();
                    showLlmListView();
                    resetLlmForm();
                } else { showToast(actionText + '失败: ' + (resp.message || '未知错误'), 'error'); }
            })
            .fail(function () { showToast('网络错误', 'error'); })
            .always(function () { $llmSaveBtn.prop('disabled', false); });
    });

    // LLM 测试连接（通过 ChatModel hello 检测）
    $('#llmTestBtn').on('click', function () {
        var apiUrl = $('#llmApiUrl').val().trim();
        if (!apiUrl) { showToast('请先填写 API 地址', 'error'); return; }
        var $btn = $(this);
        var btnOriginal = $btn.html();
        $btn.prop('disabled', true).html('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="animation:spin 1s linear infinite"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg> 测试中...');
        $llmCheckResult.hide();

        $.ajax({ url: '/web/settings/llm/models/fetch', type: 'POST', contentType: 'application/json', data: JSON.stringify({ apiUrl: apiUrl, apiKey: $('#llmApiKey').val().trim(), provider: $('#llmProvider').val(), model: ($('#llmModel').val() || '').trim() }), timeout: 30000, dataType: 'json' })
            .done(function (resp) {
                var ok = resp.code === 200;
                var msg = ok ? resp.data : ('连接失败: ' + (resp.description || '未知错误'));
                var svg = ok
                    ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg> '
                    : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg> ';
                $llmCheckResult.attr('class', 'llm-check-result ' + (ok ? 'success' : 'error')).html(svg + msg).css('display', 'flex');
            })
            .fail(function (jqXHR, textStatus) {
                var msg = textStatus === 'timeout' ? '连接超时，请检查 API 地址是否正确' : '网络错误，请重试';
                $llmCheckResult.attr('class', 'llm-check-result error').html(msg).css('display', 'flex');
            })
            .always(function () { $btn.prop('disabled', false).html(btnOriginal); });
    });

    // LLM 导出
    $('#llmExportBtn').on('click', function () {
        $.get('/web/settings/llm/models/export', function (resp) {
            downloadJson({ models: resp.data || [] }, 'llm-models.json');
        });
    });


    // LLM API Key 显示切换
    $('#llmApiKeyToggle').on('click', function () {
        var $input = $('#llmApiKey');
        if ($input.attr('type') === 'password') {
            $input.attr('type', 'text');
            $(this).html('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>');
        } else {
            $input.attr('type', 'password');
            $(this).html('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>');
        }
    });

    // ==================== MCP 管理 ====================

    function loadMcpList() {
        $.get('/web/settings/mcp/servers', function (resp) {
            if (resp.code === 200 && resp.data) {
                mcpCachedList = resp.data;
                renderMcpList(resp.data);
            }
        }).fail(function () { console.error('[Settings] Failed to load MCP servers'); });
    }

    function renderMcpList(list) {
        var html = '';
        if (!list || list.length === 0) {
            html = '<div class="mcp-empty-state">'
                + '<div class="mcp-empty-icon"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5"><rect x="2" y="2" width="20" height="20" rx="3"/><path d="M7 8h10M7 12h6M7 16h8"/></svg></div>'
                + '<div class="mcp-empty-title">暂无 MCP 服务器</div>'
                + '<div class="mcp-empty-desc">MCP 服务器可扩展 AI 的工具能力，如文件系统访问、数据库查询、API 调用等</div>'
                + '</div>';
        } else {
            var iconMap = { stdio: 'S', sse: 'R', streamable: 'H' };
            list.forEach(function (item) {
                var name = item.name || '';
                var type = item.type || 'stdio';
                var detail = type === 'stdio' ? (item.command || '') : (item.url || '');
                var icon = iconMap[type] || 'M';
                html += '<div class="mcp-server-item">'
                    + '<div class="mcp-server-icon">' + escapeHtml(icon) + '</div>'
                    + '<div class="mcp-server-info">'
                    + '<div class="mcp-server-name">' + escapeHtml(name) + ' <span style="font-size:10px;color:var(--text-secondary);font-weight:400;">[' + escapeHtml(type) + ']</span></div>'
                    + (detail ? '<div class="mcp-server-detail">' + escapeHtml(detail) + '</div>' : '')
                    + '</div><div class="mcp-server-actions">'
                    + '<label style="display:flex;align-items:center;gap:4px;cursor:pointer;font-size:11px;color:var(--text-secondary);">'
                    + '<input type="checkbox" ' + (item.enabled !== false ? 'checked' : '') + ' data-name="' + escapeAttr(name) + '" class="mcp-toggle"/>'
                    + '</label>'
                    + '<button class="mcp-action-btn copy" data-name="' + escapeAttr(name) + '" title="复制"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg></button>'
                    + '<button class="mcp-action-btn edit" data-name="' + escapeAttr(name) + '" title="编辑"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>'
                    + '<button class="mcp-action-btn delete" data-name="' + escapeAttr(name) + '" title="删除"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg></button>'
                    + '</div></div>';
            });
        }
        $mcpServerList.html(html);
    }

    // MCP 列表事件委托
    $mcpServerList
        .on('change', '.mcp-toggle', function () {
            mcpToggleServer($(this).attr('data-name'), this.checked);
        })
        .on('click', '.mcp-action-btn.copy', function () {
            mcpCopyServer($(this).attr('data-name'));
        })
        .on('click', '.mcp-action-btn.edit', function () {
            mcpEditServer($(this).attr('data-name'));
        })
        .on('click', '.mcp-action-btn.delete', function () {
            var name = $(this).attr('data-name');
            if (confirm('确定删除 MCP 服务器 "' + name + '"？')) mcpRemoveServer(name);
        });

    // ==================== MCP 表单 ====================

    function resetMcpForm() {
        mcpEditName = null;
        $mcpSaveBtn.text('保存');
        $('#mcpName').val('').prop('readOnly', false);
        $('#mcpCommand, #mcpArgs, #mcpEnv, #mcpRemoteUrl, #mcpHeaders, #mcpTimeout').val('');
        setMcpType('stdio');
    }

    function fillMcpForm(server) {
        var type = server.type || 'stdio';
        setMcpType(type);

        if (type === 'stdio') {
            $('#mcpCommand').val(server.command || '');
            $('#mcpArgs').val((server.args || []).join('\n'));
            var envLines = [];
            if (server.env) Object.keys(server.env).forEach(function (k) { envLines.push(k + '=' + server.env[k]); });
            $('#mcpEnv').val(envLines.join('\n'));
        } else {
            $('#mcpRemoteUrl').val(server.url || '');
            var headerLines = [];
            if (server.headers) Object.keys(server.headers).forEach(function (k) { headerLines.push(k + '=' + server.headers[k]); });
            $('#mcpHeaders').val(headerLines.join('\n'));
            $('#mcpTimeout').val(server.timeout || '');
        }
    }

    function buildMcpBodyObj() {
        var name = $('#mcpName').val().trim();
        var type = $('.mcp-type-btn.active').attr('data-type') || 'stdio';
        if (!name) { showToast('名称为必填项', 'error'); return null; }
        if (!/^[a-zA-Z0-9_-]+$/.test(name)) { showToast('名称仅允许字母、数字、下划线和连字符', 'error'); return null; }

        var bodyObj = { name: name, type: type, enabled: true };

        if (type === 'stdio') {
            var command = $('#mcpCommand').val().trim();
            if (!command) { showToast('命令为必填项', 'error'); return null; }
            bodyObj.command = command;
            var argsText = $('#mcpArgs').val().trim();
            if (argsText) bodyObj.args = argsText.split('\n').filter(function (l) { return l.trim() !== ''; });
            var env = parseKvLines($('#mcpEnv').val().trim());
            if (Object.keys(env).length > 0) bodyObj.env = env;
        } else if (type === 'sse' || type === 'streamable') {
            var url = $('#mcpRemoteUrl').val().trim();
            if (!url) { showToast('URL 为必填项', 'error'); return null; }
            if (!/^https?:\/\/.+/.test(url)) { showToast('URL 必须以 http:// 或 https:// 开头', 'error'); return null; }
            bodyObj.url = url;
            var headers = parseKvLines($('#mcpHeaders').val().trim());
            if (Object.keys(headers).length > 0) bodyObj.headers = headers;
            var timeout = $('#mcpTimeout').val().trim();
            if (timeout) bodyObj.timeout = timeout;
        }
        return bodyObj;
    }

    function mcpEditServer(name) {
        var server = mcpCachedList.find(function (s) { return s.name === name; });
        if (!server) return;
        mcpEditName = name;
        showMcpFormView('编辑服务器');
        $mcpSaveBtn.text('更新');
        $('#mcpName').val(server.name).prop('readOnly', true);
        fillMcpForm(server);
    }

    function mcpCopyServer(name) {
        var server = mcpCachedList.find(function (s) { return s.name === name; });
        if (!server) return;
        mcpEditName = null;
        showMcpFormView('添加服务器');
        $mcpSaveBtn.text('保存');
        $('#mcpName').val(server.name + '-copy').prop('readOnly', false);
        fillMcpForm(server);
    }

    function mcpRemoveServer(name) {
        postJson('/web/settings/mcp/servers/remove', { name: name }, function (resp) {
            if (resp.code === 200) loadMcpList();
            else showToast('删除失败: ' + (resp.message || '未知错误'), 'error');
        });
    }

    function mcpToggleServer(name, enabled) {
        postJson('/web/settings/mcp/servers/toggle', { name: name, enabled: enabled }, function (resp) {
            if (resp.code !== 200) { showToast('操作失败: ' + (resp.message || '未知错误'), 'error'); loadMcpList(); }
        });
    }

    // MCP 按钮事件
    $('#mcpAddBtn').on('click', function () { resetMcpForm(); showMcpFormView('添加服务器'); });
    $('#mcpBackBtn').on('click', function () { showMcpListView(); resetMcpForm(); });

    $mcpTypeBtns.on('click', function () { setMcpType($(this).attr('data-type')); });

    $mcpSaveBtn.on('click', function () {
        var bodyObj = buildMcpBodyObj();
        if (!bodyObj) return;
        var isEdit = !!mcpEditName;
        var url = isEdit ? '/web/settings/mcp/servers/update' : '/web/settings/mcp/servers/add';
        var actionText = isEdit ? '更新' : '添加';

        $mcpSaveBtn.prop('disabled', true);
        $.ajax({ url: url, method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json' })
            .done(function (resp) {
                if (resp.code === 200) { loadMcpList(); showMcpListView(); resetMcpForm(); }
                else showToast(actionText + '失败: ' + (resp.message || '未知错误'), 'error');
            })
            .fail(function () { showToast('网络错误', 'error'); })
            .always(function () { $mcpSaveBtn.prop('disabled', false); });
    });

    // MCP 检测连接
    $('#mcpCheckBtn').on('click', function () {
        var bodyObj = buildMcpBodyObj();
        if (!bodyObj) return;
        var $btn = $(this);
        var btnOriginal = $btn.html();
        $btn.prop('disabled', true).html('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="animation:spin 1s linear infinite"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg> 检测中...');
        $mcpCheckResult.hide();

        $.ajax({ url: '/web/settings/mcp/servers/check', method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json', timeout: 15000 })
            .done(function (resp) {
                var ok = resp.code === 200;
                var svg = ok
                    ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg> '
                    : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg> ';
                $mcpCheckResult.attr('class', 'mcp-check-result ' + (ok ? 'success' : 'error'))
                    .html(svg + escapeHtml(resp.message || (ok ? '连接成功' : '连接失败')))
                    .css('display', 'flex');
            })
            .fail(function (jqXHR, textStatus) {
                var msg = textStatus === 'timeout' ? '检测超时（15秒），请检查服务器是否可达' : '网络错误，请重试';
                $mcpCheckResult.attr('class', 'mcp-check-result error').html(msg).css('display', 'flex');
            })
            .always(function () { $btn.prop('disabled', false).html(btnOriginal); });
    });

    // MCP 导入导出
    $('#mcpExportBtn').on('click', function () {
        $.get('/web/settings/mcp/servers', function (resp) {
            var servers = resp.data || [];
            var exportObj = { mcpServers: {} };
            servers.forEach(function (s) {
                var entry = { type: s.type };
                if (s.type === 'stdio') {
                    if (s.command) entry.command = s.command;
                    if (s.args) entry.args = s.args;
                    if (s.env) entry.env = s.env;
                } else {
                    if (s.url) entry.url = s.url;
                    if (s.headers) entry.headers = s.headers;
                    if (s.timeout) entry.timeout = s.timeout;
                }
                exportObj.mcpServers[s.name] = entry;
            });
            downloadJson(exportObj, 'mcp-servers.json');
        });
    });

    $('#mcpImportBtn').on('click', function () { $mcpImportDialog.css('display', 'flex'); $('#mcpImportText').val(''); });
    $('#mcpImportCancelBtn').on('click', function () { $mcpImportDialog.hide(); });
    $('#mcpImportConfirmBtn').on('click', function () {
        var text = $('#mcpImportText').val().trim();
        if (!text) { showToast('请粘贴 JSON 配置', 'error'); return; }
        try { JSON.parse(text); } catch (e) { showToast('JSON 格式无效', 'error'); return; }
        var $btn = $(this).prop('disabled', true);
        $.ajax({ url: '/web/settings/mcp/servers/import', method: 'POST', data: text, contentType: 'application/json', dataType: 'json' })
            .done(function (resp) {
                if (resp.code === 200) { loadMcpList(); $mcpImportDialog.hide(); }
                else showToast('导入失败: ' + (resp.message || '未知错误'), 'error');
            })
            .fail(function () { showToast('网络错误', 'error'); })
            .always(function () { $btn.prop('disabled', false); });
    });

    // ==================== OpenApi 管理 ====================

    function loadWebapiList() {
        $.get('/web/settings/webapi/servers', function (resp) {
            if (resp.code === 200 && resp.data) {
                webapiCachedList = resp.data;
                renderWebapiList(resp.data);
            }
        }).fail(function () { console.error('[Settings] Failed to load OpenApi servers'); });
    }

    function renderWebapiList(list) {
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
                html += '<div class="mcp-server-item">'
                    + '<div class="mcp-server-icon">A</div>'
                    + '<div class="mcp-server-info">'
                    + '<div class="mcp-server-name">' + escapeHtml(name) + ' <span style="font-size:10px;color:var(--text-secondary);font-weight:400;">[openapi]</span></div>'
                    + (baseUrl ? '<div class="mcp-server-detail">' + escapeHtml(baseUrl) + '</div>' : '')
                    + (docUrl ? '<div class="mcp-server-detail" style="color:var(--accent);">' + escapeHtml(docUrl) + '</div>' : '')
                    + '</div><div class="mcp-server-actions">'
                    + '<label style="display:flex;align-items:center;gap:4px;cursor:pointer;font-size:11px;color:var(--text-secondary);">'
                    + '<input type="checkbox" ' + (enabled ? 'checked' : '') + ' data-name="' + escapeAttr(name) + '" class="webapi-toggle"/>'
                    + '</label>'
                    + '<button class="mcp-action-btn edit" data-name="' + escapeAttr(name) + '" title="编辑"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>'
                    + '<button class="mcp-action-btn delete" data-name="' + escapeAttr(name) + '" title="删除"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg></button>'
                    + '</div></div>';
            });
        }
        $webapiServerList.html(html);
    }

    // OpenApi 列表事件委托
    $webapiServerList
        .on('change', '.webapi-toggle', function () {
            webapiToggleServer($(this).attr('data-name'), this.checked);
        })
        .on('click', '.mcp-action-btn.edit', function () {
            webapiEditServer($(this).attr('data-name'));
        })
        .on('click', '.mcp-action-btn.delete', function () {
            var name = $(this).attr('data-name');
            if (confirm('确定删除 OpenApi 服务器 "' + name + '"？')) webapiRemoveServer(name);
        });

    // ==================== OpenApi 表单 ====================

    function resetWebapiForm() {
        webapiEditName = null;
        $webapiSaveBtn.text('保存');
        $('#webapiName').val('').prop('readOnly', false);
        $('#webapiBaseUrl, #webapiDocUrl, #webapiHeaders').val('');
    }

    function fillWebapiForm(server) {
        $('#webapiBaseUrl').val(server.apiBaseUrl || '');
        $('#webapiDocUrl').val(server.docUrl || '');
        var headerLines = [];
        if (server.headers) Object.keys(server.headers).forEach(function (k) { headerLines.push(k + '=' + server.headers[k]); });
        $('#webapiHeaders').val(headerLines.join('\n'));
    }

    function buildWebapiBodyObj() {
        var name = $('#webapiName').val().trim();
        var baseUrl = $('#webapiBaseUrl').val().trim();
        var docUrl = $('#webapiDocUrl').val().trim();
        var headersText = $('#webapiHeaders').val().trim();
        if (!name) { showToast('名称为必填项', 'error'); return null; }
        if (!/^[a-zA-Z0-9_-]+$/.test(name)) { showToast('名称仅允许字母、数字、下划线和连字符', 'error'); return null; }
        if (!baseUrl) { showToast('API 基地址为必填项', 'error'); return null; }
        var bodyObj = { name: name, apiBaseUrl: baseUrl, enabled: true };
        if (docUrl) bodyObj.docUrl = docUrl;
        var headers = parseKvLines(headersText);
        if (Object.keys(headers).length > 0) bodyObj.headers = headers;
        return bodyObj;
    }

    function webapiEditServer(name) {
        var server = webapiCachedList.find(function (s) { return s.name === name; });
        if (!server) return;
        webapiEditName = name;
        showWebapiFormView('编辑服务器');
        $webapiSaveBtn.text('更新');
        $('#webapiName').val(server.name).prop('readOnly', true);
        fillWebapiForm(server);
    }

    function webapiRemoveServer(name) {
        postJson('/web/settings/webapi/servers/remove', { name: name }, function (resp) {
            if (resp.code === 200) loadWebapiList();
            else showToast('删除失败: ' + (resp.message || '未知错误'), 'error');
        });
    }

    function webapiToggleServer(name, enabled) {
        postJson('/web/settings/webapi/servers/toggle', { name: name, enabled: enabled }, function (resp) {
            if (resp.code !== 200) { showToast('操作失败: ' + (resp.message || '未知错误'), 'error'); loadWebapiList(); }
        });
    }

    // OpenApi 按钮事件
    $('#webapiAddBtn').on('click', function () { resetWebapiForm(); showWebapiFormView('添加服务器'); });
    $('#webapiBackBtn').on('click', function () { showWebapiListView(); resetWebapiForm(); });

    // OpenApi 测试连接
    $('#webapiTestBtn').on('click', function () {
        var baseUrl = $('#webapiBaseUrl').val().trim();
        if (!baseUrl) { showToast('请先填写 API 基地址', 'error'); return; }
        var headers = parseKvLines($('#webapiHeaders').val().trim());
        var $btn = $(this);
        var btnOriginal = $btn.html();
        $btn.prop('disabled', true).html('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="animation:spin 1s linear infinite"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg> 测试中...');
        $webapiCheckResult.hide();

        $.ajax({ url: '/web/settings/webapi/servers/check', method: 'POST', data: JSON.stringify({ baseUrl: baseUrl, headers: headers }), contentType: 'application/json', dataType: 'json', timeout: 15000 })
            .done(function (resp) {
                var ok = resp.code === 200;
                var svg = ok
                    ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg> 连接成功'
                    : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg> ' + (resp.message || '连接失败');
                $webapiCheckResult.attr('class', 'llm-check-result ' + (ok ? 'success' : 'error')).html(svg).css('display', 'flex');
            })
            .fail(function (jqXHR, textStatus) {
                var msg = textStatus === 'timeout' ? '连接超时（15秒），请检查地址是否正确' : '网络错误，请重试';
                $webapiCheckResult.attr('class', 'llm-check-result error').html(msg).css('display', 'flex');
            })
            .always(function () { $btn.prop('disabled', false).html(btnOriginal); });
    });

    $webapiSaveBtn.on('click', function () {
        var bodyObj = buildWebapiBodyObj();
        if (!bodyObj) return;
        var isEdit = !!webapiEditName;
        var url = isEdit ? '/web/settings/webapi/servers/update' : '/web/settings/webapi/servers/add';
        var actionText = isEdit ? '更新' : '添加';
        if (isEdit) bodyObj.originalName = webapiEditName;

        $webapiSaveBtn.prop('disabled', true);
        $.ajax({ url: url, method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json' })
            .done(function (resp) {
                if (resp.code === 200) { showToast(actionText + '成功'); loadWebapiList(); showWebapiListView(); resetWebapiForm(); }
                else showToast(actionText + '失败: ' + (resp.message || '未知错误'), 'error');
            })
            .fail(function () { showToast('网络错误', 'error'); })
            .always(function () { $webapiSaveBtn.prop('disabled', false); });
    });

    // OpenApi 导入导出
    $('#webapiExportBtn').on('click', function () {
        $.get('/web/settings/webapi/servers', function (resp) {
            var servers = resp.data || [];
            var exportObj = { apiServers: {} };
            servers.forEach(function (s) {
                var entry = {};
                if (s.apiBaseUrl) entry.apiBaseUrl = s.apiBaseUrl;
                if (s.docUrl) entry.docUrl = s.docUrl;
                if (s.headers) entry.headers = s.headers;
                exportObj.apiServers[s.name] = entry;
            });
            downloadJson(exportObj, 'openapi-servers.json');
        });
    });

    $('#webapiImportBtn').on('click', function () { $webapiImportDialog.css('display', 'flex'); $('#webapiImportText').val(''); });
    $('#webapiImportCancelBtn').on('click', function () { $webapiImportDialog.hide(); });
    $('#webapiImportConfirmBtn').on('click', function () {
        var text = $('#webapiImportText').val().trim();
        if (!text) { showToast('请粘贴 JSON 配置', 'error'); return; }
        try { JSON.parse(text); } catch (e) { showToast('JSON 格式无效', 'error'); return; }
        var $btn = $(this).prop('disabled', true);
        $.ajax({ url: '/web/settings/webapi/servers/import', method: 'POST', data: text, contentType: 'application/json', dataType: 'json' })
            .done(function (resp) {
                if (resp.code === 200) { loadWebapiList(); $webapiImportDialog.hide(); }
                else showToast('导入失败: ' + (resp.message || '未知错误'), 'error');
            })
            .fail(function () { showToast('网络错误', 'error'); })
            .always(function () { $btn.prop('disabled', false); });
    });

})();

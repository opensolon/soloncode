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
    // OpenApi
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

    // ==================== 状态 ====================

    var llmEditName = null;
    var llmCachedList = [];
    var mcpEditName = null;
    var mcpCachedList = [];
    var $mcpToolsView = $('#mcpToolsView');
    var $mcpToolsList = $('#mcpToolsList');
    var $mcpToolsTitle = $('#mcpToolsTitle');
    var openapiEditName = null;
    var openapiCachedList = [];

    // MountPools
    var $mountsList = $('#mountsList');
    var $mountsListView = $('#mountsListView');
    var $mountsFormView = $('#mountsFormView');
    var $mountsSkillsView = $('#mountsSkillsView');
    var $mountsSkillsList = $('#mountsSkillsList');
    var $mountsFormTitle = $('#mountsFormTitle');
    var $mountsSkillsTitle = $('#mountsSkillsTitle');
    var $mountsSaveBtn = $('#mountsSaveBtn');
    var mountsCachedList = [];
    var mountsCurrentAlias = null;
    var mountsCurrentType = null;
    var mountsCurrentRealPath = null;
    var mountsEditAlias = null;

    // General
    var $generalSaveBtn = $('#generalSaveBtn');

    // ==================== 视图切换 ====================

    function showLlmListView() { $llmFormView.hide(); $llmListView.addClass('slide-back').show(); setTimeout(function(){ $llmListView.removeClass('slide-back'); }, 260); }
    function showLlmFormView(title, isEdit) { $llmFormTitle.text(title || '添加模型'); $llmListView.hide(); $llmFormView.show(); $('#llmFormActions').toggle(!!isEdit); }
    function showMcpListView() { $mcpFormView.hide(); $mcpListView.addClass('slide-back').show(); setTimeout(function(){ $mcpListView.removeClass('slide-back'); }, 260); }
    function showMcpFormView(title, isEdit) { $mcpFormTitle.text(title || '添加服务器'); $mcpListView.hide(); $mcpFormView.show(); $('#mcpFormActions').toggle(!!isEdit); }
    function showOpenapiListView() { $openapiFormView.hide(); $openapiApisView.hide(); $openapiListView.addClass('slide-back').show(); setTimeout(function(){ $openapiListView.removeClass('slide-back'); }, 260); }
    function showOpenapiApisView(title) { $openapiListView.hide(); $openapiFormView.hide(); $openapiApisTitle.text(title || 'API 列表'); $openapiApisView.show(); }
    function showOpenapiFormView(title, isEdit) { $openapiFormTitle.text(title || '添加服务器'); $openapiListView.hide(); $openapiFormView.show(); $('#openapiFormActions').toggle(!!isEdit); }
    function showMountsListView() { $mountsFormView.hide(); $mountsSkillsView.hide(); $mountsListView.addClass('slide-back').show(); setTimeout(function(){ $mountsListView.removeClass('slide-back'); }, 260); }
    function showMountsFormView(title) { $mountsFormTitle.text(title || '添加挂载'); $mountsListView.hide(); $mountsSkillsView.hide(); $mountsFormView.show(); }
    function showMountsSkillsView() { $mountsListView.hide(); $mountsFormView.hide(); $mountsSkillsView.show(); }

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
        showOpenapiListView();
        showMountsListView();
        $llmCheckResult.hide();
        $('#llmFormActions, #mcpFormActions, #openapiFormActions').hide();
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
        if (targetTab === 'general') {
            $('#settingsTabGeneral').addClass('active');
            loadGeneralSettings();
        } else if (targetTab === 'llm') {
            $('#settingsTabLlm').addClass('active');
            loadLlmList();
        } else if (targetTab === 'skills') {
            $('#settingsTabSkills').addClass('active');
            if (window._skillModule) window._skillModule.resetAndLoad();
        } else if (targetTab === 'mounts') {
            $('#settingsTabMounts').addClass('active');
            loadMountsList();
        } else if (targetTab === 'mcp') {
            $('#settingsTabMcp').addClass('active');
            loadMcpList();
        } else if (targetTab === 'openapi') {
            $('#settingsTabOpenapi').addClass('active');
            loadOpenapiList();
        }
    });

    function loadActiveTabData() {
        var $active = $('.settings-tab.active');
        if (!$active.length) return;
        var targetTab = $active.attr('data-tab');
        if (targetTab === 'general') loadGeneralSettings();
        else if (targetTab === 'llm') loadLlmList();
        else if (targetTab === 'skills') { if (window._skillModule) window._skillModule.resetAndLoad(); }
        else if (targetTab === 'mounts') loadMountsList();
        else if (targetTab === 'mcp') loadMcpList();
        else if (targetTab === 'openapi') loadOpenapiList();
    }

    // ==================== LLM 管理 ====================

    function loadLlmList() {
        $.get('/web/settings/llm/models', function (resp) {
            if (resp.code === 200 && resp.data) {
                renderLlmList(resp.data || [], '');
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
                + '</div>';
        } else {
            var providerIcons = { 'openai': 'OAI', 'ollama': 'OLA', 'zhipu': 'ZP', 'deepseek': 'DS', 'baidu-qianfan': 'BD', 'ali-tongyi': 'ALI', 'moonshot': 'MS', 'minimax': 'MM' };
            list.forEach(function (item) {
                var model = item.model || '';
                var provider = item.provider || '';
                var name = item.name || '';
                var apiUrl = item.apiUrl || '';
                var enabled = item.enabled !== false;
                var icon = providerIcons[provider] || model.substring(0, 2).toUpperCase();
                var apiUrlShort = apiUrl ? apiUrl.replace(/^https?:\/\//, '').split('/')[0] : '';

                var displayName = name || model;
                var metaLine = '';
                if (apiUrlShort && model) {
                    metaLine = escapeHtml(apiUrlShort) + ' / ' + escapeHtml(model);
                } else if (apiUrlShort) {
                    metaLine = escapeHtml(apiUrlShort);
                } else if (model) {
                    metaLine = escapeHtml(model);
                }

                html += '<div class="llm-model-item' + (!enabled ? ' disabled' : '') + '" data-model="' + escapeAttr(name) + '">'
                    + '<div class="llm-model-icon">' + escapeHtml(icon) + '</div>'
                    + '<div class="llm-model-info"><div class="llm-model-name">' + escapeHtml(displayName) + '</div><div class="llm-model-meta">'
                    + '<span class="llm-api-hint">' + metaLine + '</span>'
                    + '</div></div><div class="llm-model-actions">'
                    + '<button class="llm-action-btn edit llm-edit-btn" title="编辑"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>'
                    + '<label class="toggle-switch" title="' + (enabled ? '停用' : '启用') + '">'
                    + '<input type="checkbox" ' + (enabled ? 'checked' : '') + ' data-name="' + escapeAttr(name) + '" class="llm-toggle"/>'
                    + '<span class="toggle-slider"></span>'
                    + '</label>'
                    + '</div></div>';
            });
        }
        $llmModelList.html(html);
    }

    // LLM 列表事件委托（一次绑定，无需每次 render 后重绑）
    $llmModelList
        .on('click', '.llm-edit-btn', function (e) {
            e.stopPropagation();
            var model = $(this).closest('.llm-model-item').attr('data-model');
            if (model) llmEditNameFunc(model);
        })
        .on('change', '.llm-toggle', function () {
            var name = $(this).attr('data-name');
            if (!name) name = $(this).closest('.llm-model-item').attr('data-model');
            llmToggleModel(name, this.checked);
        });

    // ==================== LLM 表单 ====================

    function resetLlmForm() {
        llmEditName = null;
        $llmSaveBtn.text('保存');
        $('#llmProvider, #llmApiUrl, #llmApiKey, #llmModel, #llmName, #llmTimeout, #llmContextLength, #llmDefaultOptions').val('');
        $('#llmApiKey').attr('placeholder', 'sk-...');
        $llmCheckResult.hide();
    }

    function fillLlmForm(item) {
        if (item.provider) $('#llmProvider').val(item.provider);
        if (item.apiUrl) $('#llmApiUrl').val(item.apiUrl);
        if (item.apiKey) {
            $('#llmApiKey').val(item.apiKey);
        } else {
            $('#llmApiKey').val('');
        }
        $('#llmApiKey').attr('placeholder', item.apiKey ? '已配置（留空保持不变）' : 'sk-...');
        if (item.model) $('#llmModel').val(item.model);
        if (item.name) $('#llmName').val(item.name);
        if (item.timeout) $('#llmTimeout').val(item.timeout);
        if (item.contextLength) $('#llmContextLength').val(item.contextLength);
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

    function llmEditNameFunc(name) {
        showLlmFormView('编辑模型', true);
        $llmSaveBtn.text('更新');
        resetLlmForm();
        llmEditName = name;

        $.get('/web/settings/llm/models/get?name=' + encodeURIComponent(name), function (resp) {
            if (resp.code === 200 && resp.data) {
                fillLlmForm(resp.data);
            } else {
                showToast('获取模型详情失败: ' + (resp.message || '未知错误'), 'error');
            }
        }).fail(function () { showToast('网络错误', 'error'); });
    }

    function llmCopyModel(name) {
        llmEditName = null;
        showLlmFormView('添加模型', false);
        $llmSaveBtn.text('保存');
        resetLlmForm();

        $.get('/web/settings/llm/models/get?name=' + encodeURIComponent(name), function (resp) {
            if (resp.code === 200 && resp.data) {
                fillLlmForm(resp.data);
                $('#llmName').val((resp.data.name || resp.data.model) + '-copy');
            } else {
                showToast('获取模型详情失败: ' + (resp.message || '未知错误'), 'error');
            }
        }).fail(function () { showToast('网络错误', 'error'); });
    }

    function llmToggleModel(name, enabled) {
        postJson('/web/settings/llm/models/toggle', { name: name, enabled: enabled }, function (resp) {
            if (resp.code !== 200) { showToast('操作失败: ' + (resp.message || '未知错误'), 'error'); loadLlmList(); }
            else { if (typeof modelsLoaded !== 'undefined') modelsLoaded = false; }
        });
    }

    function llmRemoveModel(name) {
        $.post('/web/settings/llm/models/remove?name=' + encodeURIComponent(name), function (resp) {
            if (resp.code === 200) {
                if (typeof modelsLoaded !== 'undefined') modelsLoaded = false;
                showLlmListView();
                loadLlmList();
            } else { showToast('删除失败: ' + (resp.message || '未知错误'), 'error'); }
        });
    }

    // LLM 按钮事件
    $('#llmAddBtn').on('click', function () { llmEditName = null; resetLlmForm(); showLlmFormView('添加模型', false); });
    $('#llmBackBtn').on('click', function () { showLlmListView(); resetLlmForm(); });

    $llmSaveBtn.on('click', function () {
        var bodyObj = buildLlmBodyObj();
        if (!bodyObj) return;
        var isEdit = !!llmEditName;
        var url = isEdit ? '/web/settings/llm/models/update' : '/web/settings/llm/models/add';
        var actionText = isEdit ? '更新' : '添加';
        if (isEdit) bodyObj.originalName = llmEditName;

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

    // LLM 表单 - 复制按钮
    $('#llmFormCopyBtn').on('click', function () {
        var currentName = llmEditName;
        if (!currentName) return;
        llmCopyModel(currentName);
    });
    // LLM 表单 - 删除按钮
    $('#llmFormDeleteBtn').on('click', function () {
        var currentName = llmEditName;
        if (!currentName) return;
        if (confirm('确定删除模型 "' + currentName + '"？')) {
            llmRemoveModel(currentName);
        }
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
        $mcpToolsView.hide();
        $mcpFormView.hide();
        $mcpListView.show();
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
                + '<div class="mcp-empty-icon"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="2" width="20" height="8" rx="2" ry="2"/><rect x="2" y="14" width="20" height="8" rx="2" ry="2"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/></svg></div>'
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
                html += '<div class="mcp-server-item" data-name="' + escapeAttr(name) + '">'
                    + '<div class="mcp-server-icon">' + escapeHtml(icon) + '</div>'
                    + '<div class="mcp-server-info">'
                    + '<div class="mcp-server-name">' + escapeHtml(name) + ' <span style="font-size:10px;color:var(--text-secondary);font-weight:400;">[' + escapeHtml(type) + ']</span></div>'
                    + (detail ? '<div class="mcp-server-detail">' + escapeHtml(detail) + '</div>' : '')
                    + '</div><div class="mcp-server-actions">'
                    + '<button class="mcp-action-btn edit mcp-edit-btn" data-name="' + escapeAttr(name) + '" title="编辑"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>'
                    + '<label class="toggle-switch" title="' + ((item.enabled !== false) ? '停用' : '启用') + '">'
                    + '<input type="checkbox" ' + (item.enabled !== false ? 'checked' : '') + ' data-name="' + escapeAttr(name) + '" class="mcp-toggle"/>'
                    + '<span class="toggle-slider"></span>'
                    + '</label>'
                    + '</div></div>';
            });
        }
        $mcpServerList.html(html);
    }

    // MCP 列表事件委托
    $mcpServerList
        .on('click', '.mcp-action-btn.edit.mcp-edit-btn', function (e) {
            e.stopPropagation();
            var name = $(this).attr('data-name');
            if (name) mcpEditServer(name);
        })
        .on('click', '.mcp-server-item', function (e) {
            if ($(e.target).closest('.toggle-switch').length) return;
            if ($(e.target).closest('.mcp-action-btn').length) return;
            var name = $(this).attr('data-name');
            if (name) showMcpTools(name);
        })
        .on('change', '.mcp-toggle', function () {
            mcpToggleServer($(this).attr('data-name'), this.checked);
        });

    // MCP 工具列表查看
    function showMcpTools(name) {
        $mcpListView.hide();
        $mcpFormView.hide();
        $mcpToolsView.show();
        $mcpToolsTitle.text(name + ' - 工具列表');
        $mcpToolsList.html('<div class="mcp-empty-state"><div class="skills-loading" style="display:block"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="animation:spin 1s linear infinite"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg><span>加载中...</span></div></div>');
        $.get('/web/settings/mcp/servers/tools?name=' + encodeURIComponent(name), function (resp) {
            if (resp.code === 200 && resp.data) {
                renderMcpTools(resp.data, name);
            } else {
                $mcpToolsList.html('<div class="mcp-empty-state"><div class="mcp-empty-title">' + escapeHtml(resp.message || '加载失败') + '</div></div>');
            }
        }).fail(function () {
            $mcpToolsList.html('<div class="mcp-empty-state"><div class="mcp-empty-title">加载失败</div></div>');
        });
    }

    // 当前工具列表所在的 serverName
    var mcpToolsServerName = '';

    /** 更新工具栏计数和全选状态 */
    function updateMcpToolsToolbar() {
        var $toggles = $mcpToolsList.find('.mcp-tool-toggle');
        var total = $toggles.length;
        var checked = $toggles.filter(':checked').length;
        $('#mcpToolsCount').text(checked + ' / ' + total + ' 已启用');
        $('#mcpToolsSelectAll').prop('checked', total > 0 && checked === total);
    }

    function renderMcpTools(data, name) {
        mcpToolsServerName = name;
        var connected = data.connected !== false;
        var $toolbar = $('#mcpToolsToolbar');

        if (!connected) {
            $toolbar.hide();
            $mcpToolsList.html('<div class="mcp-empty-state">'
                + '<div class="mcp-empty-icon"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/></svg></div>'
                + '<div class="mcp-empty-title">服务器未连接</div>'
                + '<div class="mcp-empty-desc">请先启用并确保该 MCP 服务器可正常连接</div></div>');
            return;
        }
        var tools = data.tools || [];
        if (tools.length === 0) {
            $toolbar.hide();
            $mcpToolsList.html('<div class="mcp-empty-state">'
                + '<div class="mcp-empty-icon"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5"><rect x="2" y="2" width="20" height="20" rx="3"/><path d="M7 8h10M7 12h6M7 16h8"/></svg></div>'
                + '<div class="mcp-empty-title">暂无工具</div>'
                + '<div class="mcp-empty-desc">该 MCP 服务器未提供任何工具</div></div>');
            return;
        }

        // 获取已禁用的工具列表
        var disallowedTools = data.disallowedTools || [];
        var disallowedMap = {};
        disallowedTools.forEach(function (t) { disallowedMap[t] = true; });

        // 显示工具栏
        $toolbar.show();
        var checkedCount = tools.filter(function (t) { return !disallowedMap[t.name]; }).length;
        $('#mcpToolsCount').text(checkedCount + ' / ' + tools.length + ' 已启用');
        $('#mcpToolsSelectAll').prop('checked', checkedCount === tools.length);

        var html = '';
        tools.forEach(function (tool) {
            var toolName = tool.name || '';
            var isEnabled = !disallowedMap[toolName];
            html += '<div class="mcp-server-item mcp-tool-item" style="cursor:default" data-tool="' + escapeAttr(toolName) + '">'
                + '<label class="mcp-tool-checkbox" title="' + (isEnabled ? '禁用' : '启用') + '">'
                + '<input type="checkbox" ' + (isEnabled ? 'checked' : '') + ' data-tool="' + escapeAttr(toolName) + '" class="mcp-tool-toggle"/>'
                + '<span class="mcp-tool-checkmark"></span>'
                + '</label>'
                + '<div class="mcp-server-icon">T</div>'
                + '<div class="mcp-server-info">'
                + '<div class="mcp-server-name">' + escapeHtml(toolName) + '</div>'
                + (tool.description ? '<div class="mcp-server-detail">' + escapeHtml(tool.description) + '</div>' : '')
                + '</div></div>';
        });
        $mcpToolsList.html(html);
    }

    $('#mcpToolsBackBtn').on('click', function () {
        $mcpToolsView.hide();
        $('#mcpToolsToolbar').hide();
        $mcpListView.addClass('slide-back').show();
        setTimeout(function(){ $mcpListView.removeClass('slide-back'); }, 260);
    });

    // 工具开关变化 → 实时更新计数和全选状态
    $mcpToolsList.on('change', '.mcp-tool-toggle', function () {
        updateMcpToolsToolbar();
    });

    // 全选/取消全选
    $('#mcpToolsSelectAll').on('change', function () {
        var checked = this.checked;
        $mcpToolsList.find('.mcp-tool-toggle').prop('checked', checked);
        updateMcpToolsToolbar();
    });

    // 保存工具权限（提交未勾选的作为 disallowedTools）
    $('#mcpToolsSaveBtn').on('click', function () {
        if (!mcpToolsServerName) return;
        var disallowedTools = [];
        $mcpToolsList.find('.mcp-tool-toggle:not(:checked)').each(function () {
            disallowedTools.push($(this).attr('data-tool'));
        });
        var $btn = $(this);
        $btn.prop('disabled', true);
        postJson('/web/settings/mcp/servers/tools/save',
            { serverName: mcpToolsServerName, disallowedTools: disallowedTools },
            function (resp) {
                if (resp.code === 200) showToast('工具权限已保存');
                else showToast('保存失败: ' + (resp.message || '未知错误'), 'error');
            },
            function () { $btn.prop('disabled', false); }
        );
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
        showMcpFormView('编辑服务器', true);
        $mcpSaveBtn.text('更新');
        $('#mcpName').val(server.name).prop('readOnly', true);
        fillMcpForm(server);
    }

    function mcpCopyServer(name) {
        var server = mcpCachedList.find(function (s) { return s.name === name; });
        if (!server) return;
        mcpEditName = null;
        showMcpFormView('添加服务器', false);
        $mcpSaveBtn.text('保存');
        $('#mcpName').val(server.name + '-copy').prop('readOnly', false);
        fillMcpForm(server);
    }

    function mcpRemoveServer(name) {
        postJson('/web/settings/mcp/servers/remove', { name: name }, function (resp) {
            if (resp.code === 200) { showMcpListView(); loadMcpList(); }
            else showToast('删除失败: ' + (resp.message || '未知错误'), 'error');
        });
    }

    function mcpToggleServer(name, enabled) {
        postJson('/web/settings/mcp/servers/toggle', { name: name, enabled: enabled }, function (resp) {
            if (resp.code !== 200) { showToast('操作失败: ' + (resp.message || '未知错误'), 'error'); loadMcpList(); }
        });
    }

    // MCP 按钮事件
    $('#mcpAddBtn').on('click', function () { resetMcpForm(); showMcpFormView('添加服务器', false); });
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

    // MCP 表单 - 复制按钮
    $('#mcpFormCopyBtn').on('click', function () {
        var name = mcpEditName;
        if (!name) return;
        mcpCopyServer(name);
    });
    // MCP 表单 - 删除按钮
    $('#mcpFormDeleteBtn').on('click', function () {
        var name = mcpEditName;
        if (!name) return;
        if (confirm('确定删除 MCP 服务器 "' + name + '"？')) {
            mcpRemoveServer(name);
        }
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
                    + '<div class="mcp-server-name">' + escapeHtml(name) + ' <span style="font-size:10px;color:var(--text-secondary);font-weight:400;">[openapi]</span></div>'
                    + (baseUrl ? '<div class="mcp-server-detail">' + escapeHtml(baseUrl) + '</div>' : '')
                    + (docUrl ? '<div class="mcp-server-detail" style="color:var(--accent);">' + escapeHtml(docUrl) + '</div>' : '')
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
        $('#openapiName').val('').prop('readOnly', false);
        $('#openapiBaseUrl, #openapiDocUrl, #openapiHeaders').val('');
    }

    function fillOpenapiForm(server) {
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
        var bodyObj = { name: name, apiBaseUrl: baseUrl, docUrl: docUrl, enabled: true };
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
        $('#openapiName').val(server.name).prop('readOnly', true);
        fillOpenapiForm(server);
    }

    function openapiCopyServer(name) {
        var server = openapiCachedList.find(function (s) { return s.name === name; });
        if (!server) return;
        openapiEditName = null;
        showOpenapiFormView('添加服务器', false);
        $openapiSaveBtn.text('保存');
        $('#openapiName').val(server.name + '-copy').prop('readOnly', false);
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
        var baseUrl = $('#openapiBaseUrl').val().trim();
        if (!baseUrl) { showToast('请先填写 API 基地址', 'error'); return; }
        var headers = parseKvLines($('#openapiHeaders').val().trim());
        var $btn = $(this);
        var btnOriginal = $btn.html();
        $btn.prop('disabled', true).html('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="animation:spin 1s linear infinite"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg> 测试中...');
        $openapiCheckResult.hide();

        $.ajax({ url: '/web/settings/openapi/servers/check', method: 'POST', data: JSON.stringify({ baseUrl: baseUrl, headers: headers }), contentType: 'application/json', dataType: 'json', timeout: 15000 })
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


    // ==================== 挂载管理 ====================

    function loadMountsList() {
        $.get('/web/settings/mounts', function (resp) {
            if (resp.code === 200 && resp.data) {
                mountsCachedList = resp.data;
                renderMountsList(resp.data);
            }
        });
    }

    function renderMountsList(list) {
        var html = '';
        if (!list || list.length === 0) {
            html = '<div class="mcp-empty-state">'
                + '<div class="mcp-empty-icon"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/></svg></div>'
                + '<div class="mcp-empty-title">暂无挂载</div>'
                + '<div class="mcp-empty-desc">挂载是本地目录映射，为 AI 提供技能加载路径</div></div>';
        } else {
            // 系统挂载排前面
            var sorted = list.slice().sort(function (a, b) {
                var as = a.system === true ? 0 : 1;
                var bs = b.system === true ? 0 : 1;
                return as - bs;
            });
            sorted.forEach(function (item) {
                var alias = item.alias || '';
                var path = item.path || '';
                var isSystem = item.system === true;
                var typeMap = { SKILLS: 'S', FILES: 'F', AGENTS: 'A' };
                var iconText = typeMap[item.type] || (item.type ? item.type.charAt(0).toUpperCase() : 'M');
                html += '<div class="mcp-server-item mounts-pool-item' + (isSystem ? ' mounts-system' : '') + '" data-alias="' + escapeAttr(alias) + '">'
                    + '<div class="mcp-server-icon">' + escapeHtml(iconText) + '</div>'
                    + '<div class="mcp-server-info">'
                    + '<div class="mcp-server-name">' + escapeHtml(alias)
                    + (isSystem ? ' <span class="mounts-system-badge">系统</span>' : '')
                    + (item.writeable ? ' <span class="mounts-writeable-badge">可写</span>' : '')
                    + '</div>'
                    + (item.description ? '<div class="mcp-server-detail" style="color:#999">' + escapeHtml(item.description) + '</div>' : '')
                    + (path ? '<div class="mcp-server-detail">' + escapeHtml(path) + '</div>' : '')
                    + '</div><div class="mcp-server-actions">'
                    + '<button class="mcp-action-btn edit mounts-edit-btn" data-alias="' + escapeAttr(alias) + '" title="编辑"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>'
                    + '<label class="toggle-switch" title="' + ((item.enabled !== false) ? '停用' : '启用') + '">'
                    + '<input type="checkbox" ' + (item.enabled !== false ? 'checked' : '') + ' data-alias="' + escapeAttr(alias) + '" class="mounts-toggle"/> '
                    + '<span class="toggle-slider"></span>'
                    + '</label>'
                    + '</div></div>';
            });
        }
        $mountsList.html(html);
    }

    // 从缓存查找挂载类型
    function getMountType(alias) {
        var item = mountsCachedList.find(function (m) { return m.alias === alias; });
        return item ? (item.type || 'SKILLS') : 'SKILLS';
    }

    // 编辑挂载（只允许编辑描述和可写）
    function mountsEditPool(alias) {
        var item = mountsCachedList.find(function (m) { return m.alias === alias; });
        if (!item) return;
        mountsEditAlias = alias;
        var isSystem = item.system === true;

        // 填充表单（别名和路径只读）
        $('#mountsAlias').val(item.alias || '').prop('readOnly', true);
        $('#mountsPath').val(item.path || '').prop('readOnly', true);
        $('#mountsType').val(item.type || 'SKILLS').prop('disabled', true).addClass('readonly-gray');
        $('#mountsWriteable').prop('checked', !!item.writeable).prop('disabled', isSystem);
        $('#mountsDescription').val(item.description || '').prop('readOnly', isSystem);

        // 编辑模式：隐藏保存按钮（系统挂载）、预设区；非系统：显示删除按钮
        $mountsSaveBtn.toggle(!isSystem);
        $('#mountsFormActions').toggle(!isSystem);
        $('#mountsPresetsDivider, .mounts-presets').hide(); // 编辑时始终隐藏预设区

        // 只读输入控件浅灰底色
        $('#mountsAlias, #mountsPath').addClass('readonly-gray');
        if (isSystem) { $('#mountsDescription').addClass('readonly-gray'); }
        else { $('#mountsType, #mountsDescription').removeClass('readonly-gray'); }

        $mountsSaveBtn.text('更新');
        showMountsFormView('编辑挂载');
    }

    // 池列表事件委托
    $mountsList
        .on('click', '.mcp-action-btn.edit.mounts-edit-btn', function (e) {
            e.stopPropagation();
            var alias = $(this).attr('data-alias');
            mountsEditPool(alias);
        })
        .on('click', '.mounts-pool-item', function (e) {
            if ($(e.target).closest('.toggle-switch').length) return;
            if ($(e.target).closest('.mcp-action-btn').length) return;
            var alias = $(this).attr('data-alias');
            loadMountsContent(alias, getMountType(alias));
        })
        .on('change', '.mounts-toggle', function () {
            var alias = $(this).attr('data-alias');
            var enabled = this.checked;
            $.ajax({
                url: '/web/settings/mounts/toggle',
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({ alias: alias, enabled: enabled }),
                success: function (resp) {
                    if (resp.code === 200) {
                        layer.msg(enabled ? '已启用' : '已停用', { icon: 1, time: 1500, offset: '120px' });
                    } else {
                        layer.msg(resp.message || '操作失败', { icon: 2, time: 3000, offset: '120px' });
                    }
                },
                error: function () {
                    layer.msg('操作失败，请检查网络', { icon: 2, time: 3000, offset: '120px' });
                }
            });
        });

    // 池内容加载与渲染（按类型分发）
    function loadMountsContent(alias, type) {
        mountsCurrentAlias = alias;
        mountsCurrentType = type || 'SKILLS';

        // 从缓存列表中查找 realPath
        var mountItem = mountsCachedList.find(function (m) { return m.alias === alias; });
        mountsCurrentRealPath = mountItem ? (mountItem.realPath || '') : '';

        var titleMap = { SKILLS: '技能包列表', AGENTS: '子代理列表', FILES: '文件列表' };
        $mountsSkillsTitle.text(alias + ' - ' + (titleMap[mountsCurrentType] || '内容列表'));
        showMountsSkillsView();
        $mountsSkillsList.html('<div class="mcp-empty-state"><div class="skills-loading" style="display:block"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="animation:spin 1s linear infinite"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg><span>加载中...</span></div></div>');

        $.get('/web/settings/mounts/content', { alias: alias, type: mountsCurrentType }, function (resp) {
            if (resp.code === 200 && resp.data) renderMountsContent(resp.data, mountsCurrentType);
            else {
                $mountsSkillsList.html('<div class="mcp-empty-state"><div class="mcp-empty-title">' + escapeHtml(resp.message || '加载失败') + '</div></div>');
                layer.msg(resp.message || '加载失败', { icon: 2, time: 3000, offset: '120px' });
            }
        }).fail(function () {
            $mountsSkillsList.html('<div class="mcp-empty-state"><div class="mcp-empty-title">加载失败</div></div>');
            layer.msg('加载失败，请检查网络', { icon: 2, time: 3000, offset: '120px' });
        });
    }

    function renderMountsContent(list, type) {
        if (type === 'AGENTS') { renderAgentsList(list); return; }
        if (type === 'FILES') {
            $mountsSkillsList.html('<div class="mcp-empty-state">'
                + '<div class="mcp-empty-icon"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/></svg></div>'
                + '<div class="mcp-empty-title">暂不展示文件</div>'
                + '<div class="mcp-empty-desc">文件浏览功能将在后续版本支持</div></div>');
            return;
        }
        renderSkillsList(list);
    }

    function renderSkillsList(list) {
        var html = '';
        if (!list || list.length === 0) {
            html = '<div class="mcp-empty-state">'
                + '<div class="mcp-empty-icon"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg></div>'
                + '<div class="mcp-empty-title">该池暂无技能包</div>'
                + '<div class="mcp-empty-desc">通过技能市场安装，或手动将技能文件放入池目录</div></div>';
        } else {
            html += '<div class="mounts-skills-count">' + list.length + ' 个技能包</div>';
            list.forEach(function (skill) {
                html += '<div class="mcp-server-item mounts-skill-item" data-real-path="' + escapeAttr(skill.realPath || '') + '" style="cursor:pointer">'
                    + '<div class="mcp-server-info">'
                    + '<div class="mcp-server-name">' + escapeHtml(skill.name) + '</div>'
                    + (skill.realPath ? '<div class="mcp-server-detail">' + escapeHtml(skill.realPath) + '</div>' : '')
                    + (skill.description ? '<div class="mcp-server-detail">' + escapeHtml(skill.description) + '</div>' : '')
                    + '</div><div class="mcp-server-actions">'
                    + '<button class="mcp-action-btn delete" data-skill="' + escapeAttr(skill.name) + '" title="删除技能包"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg></button>'
                    + '</div></div>';
            });
        }
        $mountsSkillsList.html(html);
    }

    function renderAgentsList(list) {
        var html = '';
        if (!list || list.length === 0) {
            html = '<div class="mcp-empty-state">'
                + '<div class="mcp-empty-icon"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg></div>'
                + '<div class="mcp-empty-title">该池暂无子代理</div>'
                + '<div class="mcp-empty-desc">将代理配置文件放入池目录即可自动加载</div></div>';
        } else {
            html += '<div class="mounts-skills-count">' + list.length + ' 个子代理</div>';
            list.forEach(function (agent) {
                var name = agent.name || '';
                var filePath = agent.filePath || '';
                html += '<div class="mcp-server-item mounts-skill-item" data-real-path="' + escapeAttr(filePath) + '" style="cursor:pointer">'
                    + '<div class="mcp-server-info">'
                    + '<div class="mcp-server-name">' + escapeHtml(name) + '</div>'
                    + (filePath ? '<div class="mcp-server-detail">' + escapeHtml(filePath) + '</div>' : '')
                    + '</div></div>';
            });
        }
        $mountsSkillsList.html(html);
    }

    // 技能包删除事件
    $mountsSkillsList.on('click', '.mcp-action-btn.delete', function (e) {
        e.stopPropagation();
        var skillName = $(this).attr('data-skill');
        if (confirm('确定删除技能包 "' + skillName + '"？此操作不可恢复。')) {
            postJson('/web/settings/mounts/skills/remove', { alias: mountsCurrentAlias, skillName: skillName }, function (resp) {
                if (resp.code === 200) { showToast('删除成功'); loadMountsContent(mountsCurrentAlias, mountsCurrentType); }
                else showToast('删除失败: ' + (resp.message || ''), 'error');
            });
        }
    });

    // 点击技能/子代理条目 → 打开其所在目录
    $mountsSkillsList.on('click', '.mounts-skill-item', function (e) {
        if ($(e.target).closest('.mcp-action-btn').length) return;
        var realPath = $(this).data('real-path') || '';
        if (realPath) {
            $.get('/web/settings/mounts/open', { path: realPath }, function (resp) {
                if (resp && resp.code !== 200) {
                    layer.msg(resp.message || '打开目录失败', { icon: 2, time: 3000, offset: '120px' });
                }
            }).fail(function () {
                layer.msg('打开目录失败', { icon: 2, time: 3000, offset: '120px' });
            });
        }
    });

    // 打开挂载根目录按钮
    $('#mountsOpenDirBtn').on('click', function () {
        if (mountsCurrentRealPath) {
            $.get('/web/settings/mounts/open', { path: mountsCurrentRealPath }, function (resp) {
                if (resp && resp.code !== 200) {
                    layer.msg(resp.message || '打开目录失败', { icon: 2, time: 3000, offset: '120px' });
                }
            }).fail(function () {
                layer.msg('打开目录失败', { icon: 2, time: 3000, offset: '120px' });
            });
        }
    });

    // 刷新挂载内容按钮
    $('#mountsRefreshBtn').on('click', function () {
        if (mountsCurrentAlias) {
            var alias = mountsCurrentAlias;
            var type = mountsCurrentType;
            mountsCurrentAlias = null; // 强制重新加载
            loadMountsContent(alias, type);
        }
    });

    // ==================== General 通用设置 ====================

    function loadGeneralSettings() {
        $.get('/web/settings/general', function (resp) {
            if (resp.code === 200 && resp.data) {
                var d = resp.data;
                $('#generalSessionWindowSize').val(d.sessionWindowSize != null ? d.sessionWindowSize : '');
                $('#generalSummaryWindowSize').val(d.summaryWindowSize || '');
                $('#generalSummaryWindowToken').val(d.summaryWindowToken || '');
                $('#generalSandboxMode').prop('checked', !!d.sandboxMode);
                $('#generalApiRetries').val(d.apiRetries != null ? d.apiRetries : '');
                $('#generalMcpRetries').val(d.mcpRetries != null ? d.mcpRetries : '');
                $('#generalModelRetries').val(d.modelRetries != null ? d.modelRetries : '');
            }
        }).fail(function () { console.error('[Settings] Failed to load general settings'); });
    }

    $generalSaveBtn.on('click', function () {
        var bodyObj = {
            sessionWindowSize: $('#generalSessionWindowSize').val().trim() ? parseInt($('#generalSessionWindowSize').val().trim(), 10) : null,
            summaryWindowSize: $('#generalSummaryWindowSize').val().trim() ? parseInt($('#generalSummaryWindowSize').val().trim(), 10) : null,
            summaryWindowToken: $('#generalSummaryWindowToken').val().trim() ? parseInt($('#generalSummaryWindowToken').val().trim(), 10) : null,
            sandboxMode: $('#generalSandboxMode').is(':checked'),
            apiRetries: $('#generalApiRetries').val().trim() ? parseInt($('#generalApiRetries').val().trim(), 10) : null,
            mcpRetries: $('#generalMcpRetries').val().trim() ? parseInt($('#generalMcpRetries').val().trim(), 10) : null,
            modelRetries: $('#generalModelRetries').val().trim() ? parseInt($('#generalModelRetries').val().trim(), 10) : null
        };

        $generalSaveBtn.prop('disabled', true);
        $.ajax({ url: '/web/settings/general/save', method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json' })
            .done(function (resp) {
                if (resp.code === 200) showToast('保存成功');
                else showToast('保存失败: ' + (resp.message || '未知错误'), 'error');
            })
            .fail(function () { showToast('网络错误', 'error'); })
            .always(function () { $generalSaveBtn.prop('disabled', false); });
    });

    // 删除按钮（在二次编辑页）
    $('#mountsFormDeleteBtn').on('click', function () {
        var alias = mountsEditAlias;
        if (!alias) return;
        if (confirm('确定移除挂载 "' + alias + '"？（磁盘文件不会被删除）')) {
            $.post('/web/settings/mounts/remove', { alias: alias }, function (resp) {
                if (resp.code === 200) { showToast('已移除'); mountsEditAlias = null; showMountsListView(); loadMountsList(); }
                else showToast('移除失败: ' + (resp.message || ''), 'error');
            }, 'json').fail(function () { showToast('网络错误', 'error'); });
        }
    });

    // 添加/返回/保存按钮
    $('#mountsAddBtn').on('click', function () {
        mountsEditAlias = null;
        $('#mountsAlias').val('').prop('readOnly', false).removeClass('readonly-gray');
        $('#mountsPath').val('').prop('readOnly', false).removeClass('readonly-gray');
        $('#mountsType').val('SKILLS').prop('disabled', false).removeClass('readonly-gray');
        $('#mountsWriteable').prop('checked', false).prop('disabled', false);
        $('#mountsDescription').val('').prop('readOnly', false).removeClass('readonly-gray');
        $('#mountsFormActions').hide();
        $('#mountsPresetsDivider, .mounts-presets').show();
        $mountsSaveBtn.text('保存').show();
        showMountsFormView('添加挂载');
    });
    $('#mountsBackBtn').on('click', function () { mountsEditAlias = null; showMountsListView(); });
    $('#mountsSkillsBackBtn').on('click', function () { showMountsListView(); loadMountsList(); });

    $mountsSaveBtn.on('click', function () {
        var alias = $('#mountsAlias').val().trim();
        var path = $('#mountsPath').val().trim();
        if (!alias) { showToast('别名为必填项', 'error'); return; }
        if (!/^@/.test(alias)) { showToast('别名必须以 @ 开头', 'error'); return; }
        if (!path) { showToast('路径为必填项', 'error'); return; }

        var type = $('#mountsType').val();
        var writeable = $('#mountsWriteable').is(':checked');
        var description = $('#mountsDescription').val().trim();

        var isEdit = !!mountsEditAlias;
        var url = isEdit ? '/web/settings/mounts/update' : '/web/settings/mounts/add';
        var actionText = isEdit ? '更新' : '添加';

        var bodyObj = { alias: alias, path: path, type: type, writeable: writeable, description: description };

        $mountsSaveBtn.prop('disabled', true);
        $.ajax({ url: url, method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json' })
            .done(function (resp) {
                if (resp.code === 200) { showToast(actionText + '成功'); mountsEditAlias = null; loadMountsList(); showMountsListView(); }
                else showToast(actionText + '失败: ' + (resp.message || ''), 'error');
            })
            .fail(function () { showToast('网络错误', 'error'); })
            .always(function () { $mountsSaveBtn.prop('disabled', false); });
    });

    // 常见挂载预设按钮 - 仅在添加模式下可用，编辑模式下禁止点击
    $(document).on('click', '.mounts-preset-btn', function (e) {
        if (mountsEditAlias) { e.preventDefault(); return; }
        var alias = $(this).data('alias');
        var path = $(this).data('path');
        $('#mountsAlias').val(alias);
        $('#mountsPath').val(path);
        $('#mountsType').val('SKILLS');
        $('#mountsWriteable').prop('checked', false);
        $('#mountsDescription').val('');
    });

    // 类型联动
    $('#mountsType').on('change', function () {
        // writeable 对所有类型有效，不再隐藏
    });

})();

/**
 * app-settings-llm.js — 设置面板子模块
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

    var $llmModelList = $('#llmModelList');
    var $llmCheckResult = $('#llmCheckResult');
    var $llmSaveBtn = $('#llmSaveBtn');
    var $llmFormTitle = $('#llmFormTitle');
    var $llmListView = $('#llmListView');
    var $llmFormView = $('#llmFormView');
    var llmEditName = null;
    var llmCachedList = [];

    function showLlmListView() { $llmFormView.hide(); $llmListView.addClass('slide-back').show(); setTimeout(function(){ $llmListView.removeClass('slide-back'); }, 260); }
    function showLlmFormView(title, isEdit) { $llmFormTitle.text(title || '添加模型'); $llmListView.hide(); $llmFormView.show(); $('#llmFormActions').toggle(!!isEdit); }

    // ==================== LLM 管理 ====================

    function loadLlmList() {
        $.get('/web/settings/llm/models', function (resp) {
            if (resp.code === 200 && resp.data) {
                var data = resp.data;
                var list = data.list || (Array.isArray(data) ? data : []);
                var defaultModel = data.default || '';
                renderLlmList(list, defaultModel);
            }
        }).fail(function () { console.error('[Settings] Failed to load models'); });
    }

    function syncChatModelList() {
        if (typeof window.reloadModels === 'function') {
            window.reloadModels();
        } else if (typeof modelsLoaded !== 'undefined') {
            modelsLoaded = false;
        }
        // Also reload the global default model so new sessions pick it up
        if (typeof window.loadModels === 'function') {
            window.loadModels(null);
        }
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
                var standard = item.standard || '';
                var name = item.name || '';
                var apiUrl = item.apiUrl || '';
                var enabled = item.enabled !== false;
                var icon = providerIcons[standard] || model.substring(0, 2).toUpperCase();
                var apiUrlShort = apiUrl ? apiUrl.replace(/^https?:\/\//, '').split('/')[0] : '';

                var displayName = name || model;
                var metaLine = '';
                if (item.provider) {
                    metaLine = escapeHtml(item.provider);
                }
                if (apiUrlShort && model) {
                    metaLine += (metaLine ? ' · ' : '') + escapeHtml(apiUrlShort) + ' / ' + escapeHtml(model);
                } else if (apiUrlShort) {
                    metaLine += (metaLine ? ' · ' : '') + escapeHtml(apiUrlShort);
                } else if (model) {
                    metaLine += (metaLine ? ' · ' : '') + escapeHtml(model);
                }
                if (item.contextLength) {
                    var cl = item.contextLength;
                    metaLine += ' / ' + (cl >= 1000 ? (cl % 1000 === 0 ? (cl / 1000) + 'k' : (cl / 1000).toFixed(1).replace(/\.0$/, '') + 'k') : cl);
                }

                var isDefault = name === selected;
                html += '<div class="llm-model-item' + (!enabled ? ' disabled' : '') + '" data-model="' + escapeAttr(name) + '">'
                    + '<div class="llm-model-icon">' + escapeHtml(icon) + '</div>'
                    + '<div class="llm-model-info"><div class="llm-model-name">' + escapeHtml(displayName) + (isDefault ? ' <span class="llm-default-badge">默认</span>' : '') + (item.scope === 'workspace' ? ' <span class="mounts-scope-badge scope-workspace">工作区</span>' : '') + '</div><div class="llm-model-meta">'
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
        $('#llmStandard, #llmApiUrl, #llmApiKey, #llmModel, #llmName, #llmTimeout, #llmContextLength, #llmDefaultOptions').val('');
        $('#llmIsDefaultModel').prop('checked', false);
        setScopeValue('llmScope', 'user');
        $('#llmApiKey').attr('placeholder', 'sk-...');
        $llmCheckResult.hide();
    }

    function fillLlmForm(item) {
        if (item.standard) $('#llmStandard').val(item.standard);
        if (item.apiUrl) $('#llmApiUrl').val(item.apiUrl);
        if (item.apiKey) {
            $('#llmApiKey').val(item.apiKey);
        } else {
            $('#llmApiKey').val('');
        }
        $('#llmApiKey').attr('placeholder', item.apiKey ? '已配置（留空保持不变）' : 'sk-...');
        if (item.model) $('#llmModel').val(item.model);
        if (item.name) $('#llmName').val(item.name);
        if (item.scope) setScopeValue('llmScope', item.scope);
        if (item.timeout) $('#llmTimeout').val(item.timeout);
        if (item.contextLength) {
            var cl2 = item.contextLength;
            $('#llmContextLength').val(cl2 >= 1000 ? (cl2 % 1000 === 0 ? (cl2 / 1000) + 'k' : (cl2 / 1000).toFixed(1).replace(/\.0$/, '') + 'k') : cl2);
        }
        if (item.defaultOptions) $('#llmDefaultOptions').val(JSON.stringify(item.defaultOptions, null, 2));
        // 回显“设为默认模型”勾选状态
        $("#llmIsDefaultModel").prop("checked", !!item.isDefault);
    }

    function buildLlmBodyObj() {
        var apiUrl = $('#llmApiUrl').val().trim();
        var apiKey = $('#llmApiKey').val().trim();
        var model = $('#llmModel').val().trim();
        var alias = $('#llmName').val().trim();
        var standard = $('#llmStandard').val();
        var timeout = $('#llmTimeout').val().trim();
        if (!apiUrl || !model || !alias) { showToast('API 地址、模型和名称为必填项', 'error'); return null; }
        var bodyObj = { apiUrl: apiUrl, model: model, name: alias, standard: standard, scope: $('#llmScope').val() || 'user' };
        if (apiKey) bodyObj.apiKey = apiKey;
        if (timeout) bodyObj.timeout = timeout;
        var contextLengthRaw = $('#llmContextLength').val().trim().replace(/[, _]/g, '');
        var contextLength = contextLengthRaw;
        if (contextLengthRaw) {
            var match = contextLengthRaw.match(/^(\d+\.?\d*)k$/i);
            contextLength = match ? Math.round(parseFloat(match[1]) * 1000) : parseInt(contextLengthRaw, 10);
        }
        if (contextLength) bodyObj.contextLength = contextLength;
        var optionsText = $('#llmDefaultOptions').val().trim();
        if (optionsText) {
            try { bodyObj.defaultOptions = JSON.parse(optionsText); } catch (e) { showToast('默认选项 JSON 格式无效', 'error'); return null; }
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
            else { syncChatModelList(); }
        });
    }

    function llmRemoveModel(name) {
        $.post('/web/settings/llm/models/remove?name=' + encodeURIComponent(name), function (resp) {
            if (resp.code === 200) {
                syncChatModelList();
                showLlmListView();
                loadLlmList();
            } else { showToast('删除失败: ' + (resp.message || '未知错误'), 'error'); }
        }, 'json').fail(function () { showToast('网络错误，删除失败', 'error'); });
    }

    // LLM 按钮事件
    $('#llmAddBtn').on('click', function () { llmEditName = null; resetLlmForm(); showLlmFormView('添加模型', false); });
    $('#llmBackBtn').on('click', function () { showLlmListView(); resetLlmForm(); });

    $llmSaveBtn.on('click', function () {
        var bodyObj = buildLlmBodyObj();
        if (!bodyObj) return;
        var isEdit = !!llmEditName;
        var isDefaultModel = $('#llmIsDefaultModel').is(':checked');
        var url = isEdit ? '/web/settings/llm/models/update' : '/web/settings/llm/models/add';
        if (isDefaultModel) {
            url += (url.indexOf('?') === -1 ? '?' : '&') + 'isDefaultModel=true';
        }
        var actionText = isEdit ? '更新' : '添加';
        if (isEdit) bodyObj.originalName = llmEditName;

        $llmSaveBtn.prop('disabled', true);
        $.ajax({ url: url, method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json' })
            .done(function (resp) {
                if (resp.code === 200) {
                    syncChatModelList();
                    showToast(actionText + '成功');
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
        var model = $('#llmModel').val().trim();
        if (!apiUrl || !model) { showToast('请先填写 API 地址和模型', 'error'); return; }
        var optionsText = $('#llmDefaultOptions').val().trim();
        if (optionsText) {
            try { JSON.parse(optionsText); } catch (e) { showToast('默认选项 JSON 格式无效', 'error'); return; }
        }
        var $btn = $(this);
        var btnOriginal = $btn.html();
        $btn.prop('disabled', true).html('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="animation:spin 1s linear infinite"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg> 测试中...');
        $llmCheckResult.hide();

        $.ajax({ url: '/web/settings/llm/models/fetch', type: 'POST', contentType: 'application/json', data: JSON.stringify({ apiUrl: apiUrl, apiKey: $('#llmApiKey').val().trim(), standard: $('#llmStandard').val(), model: ($('#llmModel').val() || '').trim() }), timeout: 30000, dataType: 'json' })
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


    $('#llmFormatJsonBtn').on('click', function () {
        var $input = $('#llmDefaultOptions');
        var text = $input.val().trim();
        if (!text) { showToast('请先填写 JSON 内容', 'error'); return; }
        try {
            $input.val(JSON.stringify(JSON.parse(text), null, 2));
            showToast('JSON 格式化成功');
        } catch (e) {
            showToast('默认选项 JSON 格式无效', 'error');
        }
    });

    $('#llmDefaultOptions').on('blur', function () {
        var text = $(this).val().trim();
        if (!text) return;
        try { JSON.parse(text); }
        catch (e) { showToast('默认选项 JSON 格式无效', 'error'); }
    });

    // LLM Provider 切换时更新 API 地址 placeholder
    $('#llmStandard').on('change', function () {
        var selectedValue = $(this).val();
        var $ApiUrl = $('#llmApiUrl');
        switch (selectedValue) {
            case 'openai':
                $ApiUrl.attr('placeholder', 'https://api.openai.com');
                break;
            case 'openai-responses':
                $ApiUrl.attr('placeholder', 'https://api.openai.com');
                break;
            case 'anthropic':
                $ApiUrl.attr('placeholder', 'https://api.anthropic.com');
                break;
            case 'gemini':
                $ApiUrl.attr('placeholder', 'https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent');
                break;
            case 'ollama':
                $ApiUrl.attr('placeholder', 'http://127.0.0.1:11434/api/chat');
                break;
            default:
                $ApiUrl.attr('placeholder', 'https://api.deepseek.com/v1/chat/completions');
        }
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

    window._settingsLlm = { load: loadLlmList, reset: resetLlmForm, showList: showLlmListView, editModel: llmEditNameFunc };
})();

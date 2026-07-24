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

    var modelIconRules = [
        { pattern: /(agnes[-_/ ]?ai|agnesai|\bagnes\b)/, icon: 'agnesai' },
        { pattern: /(claude|sonnet|opus|haiku)/, icon: 'claude' },
        { pattern: /anthropic/, icon: 'anthropic' },
        { pattern: /(deepseek|deep-seek)/, icon: 'deepseek' },
        { pattern: /gemini/, icon: 'gemini' },
        { pattern: /gemma/, icon: 'gemma' },
        { pattern: /(mimo|xiaomi[-_/ ]?mimo)/, icon: 'xiaomimimo' },
        { pattern: /(longcat|meituan[-_/ ]?longcat)/, icon: 'longcat' },
        { pattern: /(qwen|tongyi|qwq)/, icon: 'qwen' },
        { pattern: /(internlm|书生浦语)/, icon: 'internlm' },
        { pattern: /(chatglm|\bglm\d*)/, icon: 'chatglm' },
        { pattern: /(zhipu|智谱|z\.ai)/, icon: 'zhipu' },
        { pattern: /(codegeex)/, icon: 'codegeex' },
        { pattern: /(cogview)/, icon: 'cogview' },
        { pattern: /kimi/, icon: 'kimi' },
        { pattern: /moonshot/, icon: 'moonshot' },
        { pattern: /hailuo/, icon: 'hailuo' },
        { pattern: /minimax/, icon: 'minimax' },
        { pattern: /(kwaikat|kat-coder|kwai[-_/ ]?kat)/, icon: 'kwaikat' },
        { pattern: /(kwaipilot|kwai[-_/ ]?pilot)/, icon: 'kwaipilot' },
        { pattern: /(kolors|可图)/, icon: 'kolors' },
        { pattern: /(sensenova|日日新)/, icon: 'sensenova' },
        { pattern: /(skywork|天工)/, icon: 'skywork' },
        { pattern: /(xuanyuan|轩辕)/, icon: 'xuanyuan' },
        { pattern: /(mistral|mixtral|codestral)/, icon: 'mistral' },
        { pattern: /(llama|meta-ai|meta ai)/, icon: 'meta' },
        { pattern: /(grok|\bxai\b)/, icon: 'grok' },
        { pattern: /ollama/, icon: 'ollama' },
        { pattern: /(doubao|豆包)/, icon: 'doubao' },
        { pattern: /(hunyuan|混元)/, icon: 'hunyuan' },
        { pattern: /(wenxin|ernie|文心)/, icon: 'wenxin' },
        { pattern: /(baichuan|百川)/, icon: 'baichuan' },
        { pattern: /(stepfun|阶跃|\bstep[-\s]?\d)/, icon: 'stepfun' },
        { pattern: /(spark|星火)/, icon: 'spark' },
        { pattern: /(cohere|command-r?)/, icon: 'cohere' },
        { pattern: /(\byi\b|零一万物)/, icon: 'yi' },
        { pattern: /(chatgpt|openai|codex|\bgpt|\bo[134](?:-|\b))/, icon: 'openai' }
    ];

    function renderModelIcon(model) {
        var keywords = (model || '').toLowerCase();
        for (var i = 0; i < modelIconRules.length; i++) {
            if (modelIconRules[i].pattern.test(keywords)) {
                var icon = modelIconRules[i].icon;
                return '<span class="lobe-model-icon" style="--lobe-icon:url(\'/lobe-icons/' + icon + '.svg\')" aria-hidden="true"></span>';
            }
        }
        var fallback = (model || '?').trim().substring(0, 2).toUpperCase();
        return '<span>' + escapeHtml(fallback) + '</span>';
    }

    function renderLlmList(list, selected) {
        llmCachedList = list || [];
        var html = '';
        if (!list || list.length === 0) {
            html = '<div class="llm-empty-state">'
                + '<div class="llm-empty-icon"><i class="fa-solid fa-inbox fa-3x"></i></div>'
                + '<div class="llm-empty-title">暂无大模型配置</div>'
                + '<div class="llm-empty-desc">添加至少一个大模型以开始 AI 对话</div>'
                + '</div>';
        } else {
            list.forEach(function (item) {
                var model = item.model || '';
                var standard = item.standard || '';
                var name = item.name || '';
                var apiUrl = item.apiUrl || '';
                var enabled = item.enabled !== false;
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
                    if (cl >= 1000000 && cl % 1000000 === 0) {
                        metaLine += ' / ' + (cl / 1000000) + 'm';
                    } else if (cl >= 1000) {
                        metaLine += ' / ' + (cl % 1000 === 0 ? (cl / 1000) + 'k' : (cl / 1000).toFixed(1).replace(/\.0$/, '') + 'k');
                    } else {
                        metaLine += ' / ' + cl;
                    }
                }

                var isDefault = name === selected;
                var standardTag = standard ? ' <span class="settings-inline-tag">[' + escapeHtml(standard) + ']</span>' : '';
                html += '<div class="llm-model-item' + (!enabled ? ' disabled' : '') + '" data-model="' + escapeAttr(name) + '">'
                    + '<div class="llm-model-icon">' + renderModelIcon(model) + '</div>'
                    + '<div class="llm-model-info"><div class="llm-model-name">' + escapeHtml(displayName) + standardTag + (isDefault ? ' <span class="llm-default-badge">默认</span>' : '') + (item.scope === 'workspace' ? ' <span class="mounts-scope-badge scope-workspace">工作区</span>' : '') + '</div><div class="llm-model-meta">'
                    + '<span class="llm-api-hint">' + metaLine + '</span>'
                    + '</div></div><div class="llm-model-actions">'
                    + '<button class="llm-action-btn edit llm-edit-btn" title="编辑"><i class="fa-solid fa-pen-to-square"></i></button>'
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
        .on('click', '.llm-model-item', function (e) {
            if ($(e.target).closest('.llm-model-actions').length) return;
            var model = $(this).attr('data-model');
            if (model) llmEditNameFunc(model);
        })
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
        $('#llmProvider').val('');
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
            if (cl2 >= 1000000 && cl2 % 1000000 === 0) {
                $('#llmContextLength').val((cl2 / 1000000) + 'm');
            } else if (cl2 >= 1000) {
                $('#llmContextLength').val(cl2 % 1000 === 0 ? (cl2 / 1000) + 'k' : (cl2 / 1000).toFixed(1).replace(/\.0$/, '') + 'k');
            } else {
                $('#llmContextLength').val(cl2);
            }
        }
        if (item.defaultOptions) $('#llmDefaultOptions').val(JSON.stringify(item.defaultOptions, null, 2));
        // 回显“设为默认模型”勾选状态
        $("#llmIsDefaultModel").prop("checked", !!item.isDefault);
        // 回显 provider（隐藏表单，编辑时保持关联）
        $('#llmProvider').val(item.provider || '');
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
            var matchK = contextLengthRaw.match(/^(\d+\.?\d*)k$/i);
            var matchM = contextLengthRaw.match(/^(\d+\.?\d*)m$/i);
            contextLength = matchK ? Math.round(parseFloat(matchK[1]) * 1000) : matchM ? Math.round(parseFloat(matchM[1]) * 1000000) : parseInt(contextLengthRaw, 10);
        }
        if (contextLength) bodyObj.contextLength = contextLength;
        var optionsText = $('#llmDefaultOptions').val().trim();
        if (optionsText) {
            try { bodyObj.defaultOptions = JSON.parse(optionsText); } catch (e) { showToast('默认选项 JSON 格式无效', 'error'); return null; }
        }
        // 编辑时保持 provider 关联（隐藏表单）
        var providerVal = $('#llmProvider').val();
        if (providerVal) {
            bodyObj.provider = providerVal;
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
            else { syncChatModelList(); loadLlmList(); }
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
        layer.confirm('确定删除模型 "' + currentName + '"？', { title: '确认删除', btn: ['删除', '取消'], icon: 3, offset: '120px' }, function(index) {
            layer.close(index);
            llmRemoveModel(currentName);
        });
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
        $btn.prop('disabled', true).html('<i class="fa-solid fa-circle-notch" style="animation:spin 1s linear infinite"></i> 测试中...');
        $llmCheckResult.hide();

        $.ajax({ url: '/web/settings/llm/models/fetch', type: 'POST', contentType: 'application/json', data: JSON.stringify({ apiUrl: apiUrl, apiKey: $('#llmApiKey').val().trim(), standard: $('#llmStandard').val(), model: ($('#llmModel').val() || '').trim() }), timeout: 30000, dataType: 'json' })
            .done(function (resp) {
                var ok = resp.code === 200;
                var msg = ok ? resp.data : ('连接失败: ' + (resp.description || '未知错误'));
                var svg = ok
                    ? '<i class="fa-regular fa-circle-check" style="color: var(--color-success)"></i> '
                    : '<i class="fa-regular fa-circle-xmark" style="color: var(--color-danger)"></i> ';
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
            $(this).html('<i class="fa-regular fa-eye-slash"></i>');
        } else {
            $input.attr('type', 'password');
            $(this).html('<i class="fa-regular fa-eye"></i>');
        }
    });

    window._settingsLlm = { load: loadLlmList, reset: resetLlmForm, showList: showLlmListView, editModel: llmEditNameFunc };
})();

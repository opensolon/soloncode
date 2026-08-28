/**
 * 供应商设置管理模块
 *
 * 负责供应商的增删改查、模型列表拉取等功能
 */
;(function () {
    'use strict';

    var core = window._settingsCore;
    var postJson = core.postJson;
    var escapeHtml = core.escapeHtml;
    var escapeAttr = core.escapeAttr;

    // ==================== 状态管理 ====================
    var providers = [];
    var currentProvider = null; // 当前编辑的供应商（null 表示新增）
    var fetchedModels = []; // 已拉取的模型列表

    // ==================== DOM 元素 ====================
    var $listView = $('#providersListView');
    var $formView = $('#providersFormView');
    var $providerList = $('#providerList');
    var $formTitle = $('#providerFormTitle');
    var $formActions = $('#providerFormActions');
    var $modelsList = $('#providerModelsList');
    var $modelsEmpty = $('#providerModelsEmpty');

    // ==================== 初始化 ====================
    function init() {
        bindEvents();
        loadProvidersList();
    }

    function bindEvents() {
        // 添加供应商按钮
        $('#providerAddBtn').on('click', function () {
            showForm(null);
        });

        // 返回按钮
        $('#providerBackBtn').on('click', function () {
            showList();
        });

        // 拉取模型列表
        $('#providerFetchModelsBtn').on('click', function () {
            fetchModels();
        });

        // 清空模型列表
        $('#providerClearModelsBtn').on('click', function () {
            if (fetchedModels.length === 0) return;
            layui.layer.confirm(I18n.t('provider.clearConfirm'), {
                btn: [I18n.t('provider.clearBtn'), I18n.t('common.cancel')],
                icon: 3
            }, function (index) {
                layui.layer.close(index);
                fetchedModels = [];
                renderModelsList();
            });
        });

        // 手动添加模型
        $('#providerAddModelBtn').on('click', function () {
            addManualModel();
        });

        // 模型列表 - 删除手动模型（阻止冒泡触发 label 勾选）
        $modelsList.on('click', '.provider-model-remove-btn', function (e) {
            e.preventDefault();
            e.stopPropagation();
            var modelId = $(this).closest('.provider-model-item').data('model-id');
            removeManualModel(modelId);
        });

        // 模型列表 - 点击编辑按钮就地编辑上下文长度（阻止冒泡触发 label 勾选）
        $modelsList.on('click', '.provider-model-ctx-btn', function (e) {
            e.preventDefault();
            e.stopPropagation();
            var modelId = $(this).closest('.provider-model-item').data('model-id');
            editModelContextLength(modelId);
        });

        // 保存按钮
        $('#providerSaveBtn').on('click', function () {
            saveProvider();
        });

        // 删除按钮
        $('#providerFormDeleteBtn').on('click', function () {
            deleteProvider();
        });

        // 编辑按钮（供应商列表项）
        $providerList.on('click', '.provider-edit-btn', function (e) {
            e.stopPropagation();
            var name = $(this).closest('.settings-list-item').data('name');
            editProvider(name);
        });

        // 点击列表项整行进入编辑
        $providerList.on('click', '.settings-list-item', function (e) {
            if ($(e.target).closest('.toggle-switch, .settings-action-btn, input').length) {
                return;
            }
            var name = $(this).data('name');
            editProvider(name);
        });

        // 启用/禁用开关
        $providerList.on('change', '.provider-toggle', function () {
            var name = $(this).closest('.settings-list-item').data('name');
            var enabled = $(this).prop('checked');
            toggleProvider(name, enabled);
        });

        // 模型列表 - 选中/取消复选框（纯前端状态，保存供应商时统一提交）
        $modelsList.on('change', '.provider-model-check', function () {
            var modelId = $(this).closest('.provider-model-item').data('model-id');
            var selected = $(this).prop('checked');
            setModelSelected(modelId, selected);
            $(this).closest('.provider-model-item').toggleClass('unselected', !selected);
        });

        // 批量选择：全选 / 反选（纯前端操作，保存时统一提交）
        $('#providerModelsSelectAll, #providerModelsInvert').on('click', function () {
            var action = this.id;
            fetchedModels.forEach(function (m) {
                var cur = m.selected !== false;
                if (action === 'providerModelsSelectAll') {
                    m.selected = true;
                } else if (action === 'providerModelsInvert') {
                    m.selected = !cur;
                }
            });
            renderModelsList();
        });

        // 作用域切换
        $('.settings-scope-toggle').on('click', '.settings-scope-btn', function () {
            var $toggle = $(this).closest('.settings-scope-toggle');
            var target = $toggle.data('target');
            var scope = $(this).data('scope');
            $toggle.find('.settings-scope-btn').removeClass('active');
            $(this).addClass('active');
            $('#' + target).val(scope);
        });
    }

    // ==================== 列表视图 ====================

    function getStandardAbbr(standard) {
        if (!standard) return '';
        return standard.split('-').map(function(p) { return p.charAt(0).toUpperCase(); }).join('');
    }

    function loadProvidersList() {
        $.ajax({
            url: '/web/settings/llm/providers',
            method: 'GET',
            success: function (res) {
                if (res.code === 200) {
                    providers = res.data || [];
                    renderProvidersList();
                }
            },
            error: function () {
                layui.layer.msg(I18n.t('provider.loadFailed'), { icon: 2 });
            }
        });
    }

    function renderProvidersList() {
        var html = '';
        if (providers.length === 0) {
            html = '<div class="mcp-empty-state"><div class="mcp-empty-icon"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5"><circle cx="12" cy="12" r="3"/><path d="M12 1v6M12 17v6M4.22 4.22l4.24 4.24M15.54 15.54l4.24 4.24M1 12h6M17 12h6M4.22 19.78l4.24-4.24M15.54 8.46l4.24-4.24"/></svg></div><div class="mcp-empty-title">' + I18n.t('provider.empty') + '</div><div class="mcp-empty-desc">' + I18n.t('provider.emptyDesc') + '</div></div>';
        } else {
            providers.forEach(function (provider) {
                html += renderProviderItem(provider);
            });
        }
        $providerList.html(html);
    }

    function renderProviderItem(provider) {
        var models = provider.models || [];
        var n = models.length;
        var m = 0;
        // m 表示已勾选（selected）的模型数，直接读 provider 数据自带的 selected 字段
        models.forEach(function (model) {
            if (model.selected !== false) {
                m++;
            }
        });

        var countClass = 'provider-models-count';
        if (m === 0 && n > 0) {
            countClass += ' muted';
        }

        var modelsCountHtml = '<span class="' + countClass + '">' + I18n.t('provider.modelCount', {m: m, n: n}) + '</span>';
        var scopeBadge = provider.scope === 'workspace' ? '<span class="mounts-scope-badge scope-workspace">' + I18n.t('provider.scope.workspace') + '</span>' : '';

        return '<div class="settings-list-item' + (provider.enabled === false ? ' disabled' : '') + '" data-name="' + escapeAttr(provider.name) + '">' +
            '<div class="settings-list-icon">' + escapeHtml(getStandardAbbr(provider.standard) || 'F') + '</div>' +
            '<div class="settings-list-info">' +
                '<div class="settings-list-title">' +
                    '<span class="provider-title-name">' + escapeHtml(provider.name) + '</span>' +
                    '<span class="settings-inline-tag">[' + escapeHtml(provider.standard || 'openai') + ']</span>' +
                    modelsCountHtml +
                    scopeBadge +
                '</div>' +
                '<div class="settings-list-desc">' + escapeHtml(provider.apiUrl || I18n.t('provider.notConfigured')) + '</div>' +
            '</div>' +
            '<div class="settings-list-actions">' +
                '<button class="settings-action-btn edit provider-edit-btn" title="' + I18n.t('provider.edit') + '"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>' +
                '<label class="toggle-switch" title="' + (provider.enabled ? I18n.t('provider.toggle.disable') : I18n.t('provider.toggle.enable')) + '">' +
                    '<input type="checkbox" ' + (provider.enabled ? 'checked' : '') + ' data-name="' + escapeAttr(provider.name) + '" class="provider-toggle"/>' +
                    '<span class="toggle-slider"></span>' +
                '</label>' +
            '</div>' +
        '</div>';
    }

    // ==================== 表单视图 ====================
    function showForm(provider) {
        currentProvider = provider;
        fetchedModels = (provider && provider.models) ? provider.models.slice() : [];

        // 切换视图
        $listView.hide();
        $formView.show();

        // 设置标题
        $formTitle.text(I18n.t(provider ? 'provider.editTitle' : 'provider.addTitle'));
        $formActions.toggle(!!provider);

        // 填充表单
        $('#providerName').val(provider ? provider.name : '').prop('readonly', !!provider);
        $('#providerStandard').val(provider ? provider.standard : 'openai');
        $('#providerApiUrl').val(provider ? provider.apiUrl : '');
        $('#providerApiKey').val(provider ? provider.apiKey : '');
        $('#providerScope').val(provider ? (provider.scope || 'user') : 'user');

        // 设置作用域按钮状态
        var scope = provider ? (provider.scope || 'user') : 'user';
        $('.settings-scope-toggle[data-target="providerScope"] .settings-scope-btn').removeClass('active');
        $('.settings-scope-toggle[data-target="providerScope"] .settings-scope-btn[data-scope="' + scope + '"]').addClass('active');

        // 渲染模型列表（同步状态基于 currentProvider.models，无需额外拉取）
        renderModelsList();
    }

    function showList() {
        $formView.hide();
        $listView.addClass('slide-back').show();
        setTimeout(function(){ $listView.removeClass('slide-back'); }, 260);
        currentProvider = null;
        fetchedModels = [];
        loadProvidersList();
    }

    // ==================== 模型列表 ====================
    function addManualModel() {
        var dialogHtml = '<div class="model-add-overlay" id="modelAddOverlay">'
            + '<div class="model-add-dialog">'
            + '<div class="model-add-header">'
            + '<span class="model-add-title">' + I18n.t('provider.models.addTitle') + '</span>'
            + '<button class="model-add-close" id="modelAddClose">&times;</button>'
            + '</div>'
            + '<div class="model-add-body">'
            + '<div class="form-group">'
            + '<label>' + I18n.t('provider.modelName') + ' <span class="required">*</span></label>'
            + '<input type="text" id="manualModelName" placeholder="' + I18n.t('provider.modelNamePlaceholder') + '">'
            + '</div>'
            + '<div class="form-group">'
            + '<label>' + I18n.t('provider.contextLength') + '</label>'
            + '<input type="text" id="manualModelTokens" inputmode="numeric" placeholder="' + I18n.t('provider.contextLengthPlaceholder') + '" list="manualContextLengthList" autocomplete="off">'
            + '<datalist id="manualContextLengthList">'
            + '<option value="128k">'
            + '<option value="256k">'
            + '<option value="512k">'
+ '<option value="1m">'
            + '</datalist>'
            + '</div>'
            + '</div>'
            + '<div class="model-add-footer">'
            + '<button class="btn-secondary" id="modelAddCancel">' + I18n.t('common.cancel') + '</button>'
            + '<button class="btn-primary" id="modelAddConfirm">' + I18n.t('provider.confirmAdd') + '</button>'
            + '</div>'
            + '</div>'
            + '</div>';

        $('body').append(dialogHtml);

        var $overlay = $('#modelAddOverlay');

        function doAdd() {
            // 模型名原样保留（含 [1m]/[256k] 后缀），后缀解析统一由服务端处理
            var modelId = $overlay.find('#manualModelName').val().trim();
            var maxTokens = $overlay.find('#manualModelTokens').val().trim();

            if (!modelId) {
                layui.layer.msg(I18n.t('provider.modelNameRequired'), { icon: 0 });
                return;
            }

            var exists = fetchedModels.some(function (m) {
                return m.id === modelId;
            });
            if (exists) {
                layui.layer.msg(I18n.t('provider.modelExists', {name: modelId}), { icon: 0 });
                return;
            }

            var newModel = { id: modelId, manual: true };
            // 解析用户单独填写的上下文长度（模型名后缀由服务端解析，优先级更高）
            if (maxTokens) {
                var trimmed = maxTokens.replace(/[, _]/g, '');
                var matchK = trimmed.match(/^(\d+\.?\d*)k$/i);
                var matchM = trimmed.match(/^(\d+\.?\d*)m$/i);
                if (matchK) {
                    newModel.maxInputTokens = Math.round(parseFloat(matchK[1]) * 1000);
                } else if (matchM) {
                    newModel.maxInputTokens = Math.round(parseFloat(matchM[1]) * 1000000);
                } else if (parseInt(trimmed) > 0) {
                    newModel.maxInputTokens = parseInt(trimmed);
                }
            }
            fetchedModels.push(newModel);
            renderModelsList();
            $overlay.remove();
        }

        $('#modelAddConfirm').on('click', doAdd);
        $('#modelAddCancel, #modelAddClose').on('click', function() {
            $overlay.remove();
        });
        $overlay.on('click', function(e) {
            if (e.target === this) $overlay.remove();
        });
        $overlay.on('keydown', 'input', function(e) {
            if (e.key === 'Enter' && !isInputComposing(e)) {
                e.preventDefault();
                doAdd();
            }
        });
        setTimeout(function() {
            $overlay.find('#manualModelName').focus();
        }, 100);
    }

    function removeManualModel(modelId) {
        fetchedModels = fetchedModels.filter(function (m) {
            return m.id !== modelId;
        });
        renderModelsList();
    }

    function fetchModels() {
        var apiUrl = $('#providerApiUrl').val();
        var apiKey = $('#providerApiKey').val();
        var standard = $('#providerStandard').val();

        if (!apiUrl) {
            layui.layer.msg(I18n.t('provider.apiUrlRequired'), { icon: 0 });
            return;
        }

        var $btn = $('#providerFetchModelsBtn');
        $btn.prop('disabled', true).html('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="animation:spin 1s linear infinite"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>');

        $.ajax({
            url: '/web/settings/llm/providers/fetch',
            method: 'POST',
            data: {
                apiUrl: apiUrl,
                apiKey: apiKey,
                standard: standard
            },
            success: function (res) {
                $btn.prop('disabled', false).html('<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>');
                if (res.code === 200) {
                    try {
                        var data = typeof res.data === 'string' ? JSON.parse(res.data) : res.data;
                        var models = data.data || data.models || data || [];
                        // 保留手动添加的模型，合并拉取的模型
                        var manualModels = fetchedModels.filter(function (m) {
                            return m.manual === true;
                        });
                        // 旧模型索引：拉取时继承用户已设置的值（上下文长度手填值、勾选状态）
                        var prevById = {};
                        fetchedModels.forEach(function (m) { prevById[m.id] = m; });
                        var fetchedMapped = models.map(function (m) {
                            var id = m.id || m.name || m;
                            var incomingMax = m.maxInputTokens || m.max_input_tokens || m.contextLength || m.context_length || 0;
                            var incomingMaxOut = m.maxTokens || m.max_tokens || 0;
                            var prev = prevById[id];
                            return {
                                id: id,
                                displayName: m.displayName || m.display_name || '',
                                ownedBy: m.ownedBy || m.owned_by || '',
                                type: m.type || '',
                                // 拉来的值为空则保留用户此前设置的值，避免手填的上下文长度被清空
                                maxInputTokens: incomingMax || (prev ? prev.maxInputTokens : 0) || 0,
                                maxTokens: incomingMaxOut || (prev ? prev.maxTokens : 0) || 0,
                                selected: prev ? prev.selected : undefined,
                                manual: false
                            };
                        });
                        // 手动模型去重：如果手动模型 id 已在拉取列表中，保留手动标记
                        var fetchedIds = {};
                        fetchedMapped.forEach(function (m) { fetchedIds[m.id] = m; });
                        manualModels.forEach(function (mm) {
                            if (fetchedIds[mm.id]) {
                                fetchedIds[mm.id].manual = true;
                                if (mm.displayName) {
                                    fetchedIds[mm.id].displayName = mm.displayName;
                                }
                                if (mm.ownedBy) {
                                    fetchedIds[mm.id].ownedBy = mm.ownedBy;
                                }
                                if (mm.maxInputTokens) {
                                    fetchedIds[mm.id].maxInputTokens = mm.maxInputTokens;
                                }
                                if (mm.maxTokens) {
                                    fetchedIds[mm.id].maxTokens = mm.maxTokens;
                                }
                            } else {
                                fetchedMapped.push(mm);
                            }
                        });
                        fetchedModels = fetchedMapped;
                        renderModelsList();
                        layui.layer.msg(I18n.t('provider.fetchOk', {n: fetchedModels.length}), { icon: 1 });
                    } catch (e) {
                        layui.layer.msg(I18n.t('provider.fetchParseFailed'), { icon: 2 });
                    }
                } else {
                    layui.layer.msg(res.msg || I18n.t('provider.fetchFailed'), { icon: 2 });
                }
            },
            error: function (xhr) {
                $btn.prop('disabled', false).html('<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>');
                layui.layer.msg(I18n.t('provider.fetchListFailed') + ': ' + (xhr.responseText || I18n.t('toast.networkError')), { icon: 2 });
            }
        });
    }


    function renderModelsList() {
        if (fetchedModels.length === 0) {
            $modelsEmpty.show();
            $modelsList.hide();
            return;
        }

        $modelsEmpty.hide();
        $modelsList.show();

        var html = '';
        fetchedModels.forEach(function (model) {
            // 选中状态为前端内存状态（model.selected），默认选中
            var selected = model.selected !== false;

            var manualTag = model.manual ? '<span class="provider-model-manual-tag">' + I18n.t('provider.manualTag') + '</span>' : '';
            var removeBtn = model.manual
                ? '<button class="provider-model-remove-btn" title="' + I18n.t('provider.remove') + '"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>'
                : '';
            // 上下文长度：只读显示（info 区的模型属性字段），有值显示如 1m/256k，无值显示占位
            var contextView = (model.maxInputTokens && model.maxInputTokens > 0)
                ? '<span class="provider-model-ctx-view">' + escapeHtml(formatContextLength(model.maxInputTokens)) + '</span>'
                : '<span class="provider-model-ctx-view provider-model-ctx-empty">—</span>';
            // 上下文长度：编辑按钮（actions 区的纯操作动作）
            var ctxEditBtn = '<button class="provider-model-ctx-btn" title="' + escapeAttr(I18n.t('provider.contextEditTip')) + '"><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>';
            // actions 区回归纯按钮：编辑长度 + 删除
            var actionsHtml = '<div class="provider-model-actions">' + ctxEditBtn + removeBtn + '</div>';
            html += '<label class="provider-model-item' + (!selected ? ' unselected' : '') + '" data-model-id="' + escapeAttr(model.id) + '">' +
                '<input type="checkbox" ' + (selected ? 'checked' : '') + ' class="provider-model-check"/>' +
                '<div class="provider-model-info">' +
                    '<span class="provider-model-name">' + escapeHtml(model.id) + '</span>' +
                    manualTag +
                    contextView +
                '</div>' +
                actionsHtml +
            '</label>';
        });
        $modelsList.html(html);
    }

    // 更新指定模型的选中状态（仅内存，保存供应商时随模型列表一并提交）
    function setModelSelected(modelId, selected) {
        for (var i = 0; i < fetchedModels.length; i++) {
            if (fetchedModels[i].id === modelId) {
                fetchedModels[i].selected = selected;
                break;
            }
        }
    }

    // 将 token 数格式化为紧凑显示（如 1000000 -> 1m，256000 -> 256k）
    function formatContextLength(cl) {
        if (cl >= 1000000 && cl % 1000000 === 0) {
            return (cl / 1000000) + 'm';
        } else if (cl >= 1000) {
            return (cl % 1000 === 0 ? (cl / 1000) + 'k' : (cl / 1000).toFixed(1).replace(/\.0$/, '') + 'k');
        }
        return String(cl);
    }

    // 解析用户输入的上下文长度，支持 128k / 1m / 1M / 32000 等写法，返回 token 数（非法返回 null）
    function parseContextLength(input) {
        if (input == null) return null;
        var s = String(input).trim().toLowerCase().replace(/[,_\s]/g, '');
        if (s === '') return 0; // 空 = 清除
        var m = s.match(/^(\d+(?:\.\d+)?)(k|m)?$/);
        if (!m) return null;
        var num = parseFloat(m[1]);
        if (isNaN(num)) return null;
        if (m[2] === 'k') num *= 1000;
        else if (m[2] === 'm') num *= 1000000;
        num = Math.round(num);
        return num < 0 ? null : num;
    }

    // 更新指定模型的上下文长度（0 或 null 视为清除）
    function setModelContextLength(modelId, tokens) {
        for (var i = 0; i < fetchedModels.length; i++) {
            if (fetchedModels[i].id === modelId) {
                if (tokens && tokens > 0) {
                    fetchedModels[i].maxInputTokens = tokens;
                } else {
                    delete fetchedModels[i].maxInputTokens;
                }
                break;
            }
        }
    }

    // 弹出上下文长度编辑框（支持 128k / 1m 等简写，空值清除，内置常用预设）
    function editModelContextLength(modelId) {
        var cur = null;
        for (var i = 0; i < fetchedModels.length; i++) {
            if (fetchedModels[i].id === modelId) {
                cur = fetchedModels[i].maxInputTokens || null;
                break;
            }
        }
        var curStr = cur ? formatContextLength(cur) : '';
        var presets = ['128k', '256k', '512k', '1m'];
        var presetHtml = presets.map(function (p) {
            return '<button type="button" class="provider-ctx-preset" data-val="' + p + '">' + p + '</button>';
        }).join('');
        var content = '<div class="provider-ctx-editor">' +
            '<input type="text" id="providerCtxInput" class="provider-ctx-input" value="' + escapeAttr(curStr) + '" placeholder="' + escapeAttr(I18n.t('provider.contextPlaceholder')) + '" autocomplete="off"/>' +
            '<div class="provider-ctx-presets">' + presetHtml + '</div>' +
            '<div class="provider-ctx-hint">' + escapeHtml(I18n.t('provider.contextHint')) + '</div>' +
        '</div>';
        layui.layer.open({
            type: 1,
            title: I18n.t('provider.contextEditTitle'),
            content: content,
            area: ['320px', 'auto'],
            btn: [I18n.t('common.confirm'), I18n.t('common.cancel')],
            success: function (layero) {
                var $input = layero.find('#providerCtxInput');
                $input.focus();
                layero.find('.provider-ctx-preset').on('click', function () {
                    $input.val($(this).data('val'));
                });
                $input.on('keydown', function (e) {
                    if (e.keyCode === 13) {
                        layero.find('.layui-layer-btn0').trigger('click');
                    }
                });
            },
            yes: function (index, layero) {
                var raw = layero.find('#providerCtxInput').val();
                var tokens = parseContextLength(raw);
                if (tokens === null) {
                    layui.layer.msg(I18n.t('provider.contextInvalid'), { icon: 2 });
                    return;
                }
                setModelContextLength(modelId, tokens);
                layui.layer.close(index);
                renderModelsList();
            }
        });
    }

    // ==================== CRUD 操作 ====================
    function editProvider(name) {
        $.ajax({
            url: '/web/settings/llm/providers/get',
            method: 'GET',
            data: { name: name },
            success: function (res) {
                if (res.code === 200) {
                    showForm(res.data);
                } else {
                    layui.layer.msg(res.msg || I18n.t('provider.loadDetailFailed'), { icon: 2 });
                }
            },
            error: function () {
                layui.layer.msg(I18n.t('provider.loadDetailFailed'), { icon: 2 });
            }
        });
    }

    function saveProvider() {
        var name = $('#providerName').val();
        var standard = $('#providerStandard').val();
        var apiUrl = $('#providerApiUrl').val();
        var apiKey = $('#providerApiKey').val();
        var scope = $('#providerScope').val();
        var models = fetchedModels.map(function (m) {
            var model = { id: m.id, manual: m.manual || false };
            if (m.displayName) {
                model.displayName = m.displayName;
            }
            if (m.ownedBy) {
                model.ownedBy = m.ownedBy;
            }
            if (m.type) {
                model.type = m.type;
            }
            if (m.maxInputTokens) {
                model.maxInputTokens = m.maxInputTokens;
            }
            if (m.maxTokens) {
                model.maxTokens = m.maxTokens;
            }
            model.selected = m.selected !== false;
            return model;
        });

        if (!name) {
            layui.layer.msg(I18n.t('provider.nameRequired'), { icon: 0 });
            return;
        }
        if (!apiUrl) {
            layui.layer.msg(I18n.t('provider.apiUrlRequired'), { icon: 0 });
            return;
        }

        var data = {
            name: name,
            standard: standard,
            apiUrl: apiUrl,
            apiKey: apiKey,
            scope: scope,
            models: models,
            enabled: true
        };

        // 如果是编辑模式，添加 originalName
        if (currentProvider) {
            data.originalName = currentProvider.name;
        }

        var url = currentProvider ? '/web/settings/llm/providers/update' : '/web/settings/llm/providers/add';

        $.ajax({
            url: url,
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(data),
            success: function (res) {
                if (res.code === 200) {
                    layui.layer.msg(I18n.t(currentProvider ? 'provider.updated' : 'provider.added'), { icon: 1 });
                    // 同步模型到 LLM 模型列表
                    syncModelsToLlm(data);
                    showList();
                } else {
                    layui.layer.msg(res.msg || I18n.t('toast.saveFailed'), { icon: 2 });
                }
            },
            error: function () {
                layui.layer.msg(I18n.t('toast.saveFailed'), { icon: 2 });
            }
        });
    }

    function syncModelsToLlm(providerData) {
        // 调用后端接口同步模型
        $.ajax({
            url: '/web/settings/llm/providers/sync-models',
            method: 'POST',
            contentType: 'application/json',
            // 后端从已落库的 provider.getModels() 重新读取模型（含 selected），此处无需重复传 models
            data: JSON.stringify({
                providerName: providerData.name
            }),
            success: function (res) {
                // res.data 为变更总数（新增/更新 + 取消勾选），＞0 则刷新列表
                if (res.code === 200 && res.data > 0) {
                    // 刷新 LLM 模型列表
                    if (window._settingsLlm) {
                        window._settingsLlm.load();
                    }
                    // 通知聊天组件刷新模型下拉列表
                    if (typeof window.reloadModels === 'function') {
                        window.reloadModels();
                    }
                }
            }
        });
    }

    function deleteProvider() {
        if (!currentProvider) return;

        layui.layer.confirm(I18n.t('provider.deleteConfirm', {name: currentProvider.name}), {
            btn: [I18n.t('common.delete'), I18n.t('common.cancel')],
            icon: 3
        }, function (index) {
            layui.layer.close(index);
            $.ajax({
                url: '/web/settings/llm/providers/remove',
                method: 'POST',
                data: { name: currentProvider.name },
                success: function (res) {
                    if (res.code === 200) {
                        layui.layer.msg(I18n.t('provider.deleted'), { icon: 1 });
                        showList();
                        // 刷新 LLM 模型列表（供应商删除时关联模型也会删除）
                        if (window._settingsLlm) {
                            window._settingsLlm.load();
                        }
                        // 通知聊天组件刷新模型下拉列表
                        if (typeof window.reloadModels === 'function') {
                            window.reloadModels();
                        }
                    } else {
                        layui.layer.msg(res.msg || I18n.t('provider.deleteFailed'), { icon: 2 });
                    }
                },
                error: function () {
                    layui.layer.msg(I18n.t('provider.deleteFailed'), { icon: 2 });
                }
            });
        });
    }

    function toggleProvider(name, enabled) {
        $.ajax({
            url: '/web/settings/llm/providers/toggle',
            method: 'POST',
            data: { name: name, enabled: enabled },
            success: function (res) {
                if (res.code === 200) {
                    layui.layer.msg(I18n.t(enabled ? 'provider.enableOk' : 'provider.disableOk'), { icon: 1 });
                    // 刷新供应商列表 UI（更新 disabled 样式）
                    loadProvidersList();
                    // 刷新 LLM 模型列表（供应商禁用时关联模型会禁用）
                    if (window._settingsLlm) {
                        window._settingsLlm.load();
                    }
                    // 通知聊天组件刷新模型下拉列表
                    if (typeof window.reloadModels === 'function') {
                        window.reloadModels();
                    }
                } else {
                    layui.layer.msg(res.msg || I18n.t('toast.operateFailed'), { icon: 2 });
                    loadProvidersList();
                }
            },
            error: function () {
                layui.layer.msg(I18n.t('toast.operateFailed'), { icon: 2 });
                loadProvidersList();
            }
        });
    }

    // ==================== 暴露全局接口 ====================
    window.settingsProviders = {
        init: init,
        loadList: loadProvidersList,
        showList: showList
    };

    // Provider API Key 显示切换
    $(document).on('click', '#providerApiKeyToggle', function () {
        var $input = $('#providerApiKey');
        if ($input.attr('type') === 'password') {
            $input.attr('type', 'text');
            $(this).html('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>');
        } else {
            $input.attr('type', 'password');
            $(this).html('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>');
        }
    });

    // 自动初始化
    $(document).ready(function () {
        init();
    });
})();

/**
 * 供应商设置管理模块
 *
 * 负责供应商的增删改查、模型列表拉取等功能
 */
;(function () {
    'use strict';

    var core = window._settingsCore;
    var postJson = core.postJson;

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

        // 手动添加模型
        $('#providerAddModelBtn').on('click', function () {
            addManualModel();
        });

        // 模型列表 - 删除手动模型
        $modelsList.on('click', '.provider-model-remove-btn', function () {
            var modelId = $(this).closest('.provider-model-item').data('model-id');
            removeManualModel(modelId);
        });

        // 保存按钮
        $('#providerSaveBtn').on('click', function () {
            saveProvider();
        });

        // 删除按钮
        $('#providerFormDeleteBtn').on('click', function () {
            deleteProvider();
        });

        // 列表项点击（编辑）
        $providerList.on('click', '.mcp-server-item', function (e) {
            // 忽略开关点击
            if ($(e.target).closest('.toggle-switch').length) return;
            if ($(e.target).closest('.mcp-action-btn').length) return;
            var name = $(this).data('name');
            editProvider(name);
        });

        // 启用/禁用开关
        $providerList.on('change', '.provider-toggle', function () {
            var name = $(this).closest('.mcp-server-item').data('name');
            var enabled = $(this).prop('checked');
            toggleProvider(name, enabled);
        });

        // 模型列表 - 启用/禁用开关
        $modelsList.on('change', '.provider-model-toggle', function () {
            var modelId = $(this).closest('.provider-model-item').data('model-id');
            var enabled = $(this).prop('checked');
            var llmName = $(this).data('llm-name');
            var isSynced = $(this).data('synced') === true || $(this).data('synced') === 'true';
            toggleProviderModel(modelId, enabled, llmName, isSynced);
        });

        // 批量选择菜单
        $('#providerModelsSelectToggle').on('click', function (e) {
            e.stopPropagation();
            $('#providerModelsActionMenu').toggleClass('show');
        });

        $(document).on('click', function (e) {
            if ($(e.target).closest('.provider-model-menu-wrap').length === 0) {
                $('#providerModelsActionMenu').removeClass('show');
            }
        });

        $('#providerModelsSelectAll, #providerModelsSelectNone, #providerModelsInvert').on('click', function () {
            var action = this.id;
            var changed = false;

            $modelsList.find('.provider-model-toggle').each(function () {
                var $toggle = $(this);
                var nextChecked = $toggle.prop('checked');

                if (action === 'providerModelsSelectAll') {
                    nextChecked = true;
                } else if (action === 'providerModelsSelectNone') {
                    nextChecked = false;
                } else if (action === 'providerModelsInvert') {
                    nextChecked = !$toggle.prop('checked');
                }

                if ($toggle.prop('checked') !== nextChecked) {
                    changed = true;
                    $toggle.prop('checked', nextChecked).trigger('change');
                }
            });

            $('#providerModelsActionMenu').removeClass('show');
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
    function loadProvidersList() {
        $.ajax({
            url: '/web/settings/providers',
            method: 'GET',
            success: function (res) {
                if (res.code === 200) {
                    providers = res.data || [];
                    renderProvidersList();
                }
            },
            error: function () {
                layui.layer.msg('加载供应商列表失败', { icon: 2 });
            }
        });
    }

    function renderProvidersList() {
        var html = '';
        if (providers.length === 0) {
            html = '<div class="mcp-empty-state"><div class="mcp-empty-icon"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5"><circle cx="12" cy="12" r="3"/><path d="M12 1v6M12 17v6M4.22 4.22l4.24 4.24M15.54 15.54l4.24 4.24M1 12h6M17 12h6M4.22 19.78l4.24-4.24M15.54 8.46l4.24-4.24"/></svg></div><div class="mcp-empty-title">暂无供应商配置</div><div class="mcp-empty-desc">管理 AI 模型供应商配置，支持自动拉取模型列表</div></div>';
        } else {
            providers.forEach(function (provider) {
                html += renderProviderItem(provider);
            });
        }
        $providerList.html(html);
    }

    function renderProviderItem(provider) {
        var modelsCount = (provider.models || []).length;

        return '<div class="mcp-server-item" data-name="' + provider.name + '">' +
            '<div class="mcp-server-icon">P</div>' +
            '<div class="mcp-server-info">' +
                '<div class="mcp-server-name">' + provider.name + ' <span class="settings-inline-tag">[' + (provider.standard || 'openai') + ']</span></div>' +
                '<div class="mcp-server-detail">' + (provider.apiUrl || '未配置') + '</div>' +
            '</div>' +
            '<div class="mcp-server-actions">' +
                '<span class="mcp-server-detail">' + modelsCount + ' 模型</span>' +
                '<label class="toggle-switch" title="' + (provider.enabled ? '停用' : '启用') + '">' +
                    '<input type="checkbox" ' + (provider.enabled ? 'checked' : '') + ' data-name="' + provider.name + '" class="provider-toggle"/>' +
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
        $formTitle.text(provider ? '编辑供应商' : '添加供应商');
        $formActions.toggle(!!provider);

        // 填充表单
        $('#providerName').val(provider ? provider.name : '').prop('readonly', !!provider);
        $('#providerStandard').val(provider ? provider.standard : 'openai');
        $('#providerApiUrl').val(provider ? provider.apiUrl : '');
        $('#providerApiKey').val(provider ? provider.apiKey : '');
        $('#providerScope').val(provider ? (provider.scope || 'global') : 'global');

        // 设置作用域按钮状态
        var scope = provider ? (provider.scope || 'global') : 'global';
        $('.settings-scope-toggle[data-target="providerScope"] .settings-scope-btn').removeClass('active');
        $('.settings-scope-toggle[data-target="providerScope"] .settings-scope-btn[data-scope="' + scope + '"]').addClass('active');

        // 加载 LLM 模型缓存后渲染模型列表
        loadLlmModelsCache(function () {
            renderModelsList();
        });
    }

    function showList() {
        $formView.hide();
        $listView.show();
        currentProvider = null;
        fetchedModels = [];
        loadProvidersList();
    }

    // ==================== 模型列表 ====================
    var llmModelsCache = {}; // 缓存 LLM 模型列表，用于判断是否已同步

    function addManualModel() {
        var dialogHtml = '<div class="model-add-overlay" id="modelAddOverlay">'
            + '<div class="model-add-dialog">'
            + '<div class="model-add-header">'
            + '<span class="model-add-title">手动添加模型</span>'
            + '<button class="model-add-close" id="modelAddClose">&times;</button>'
            + '</div>'
            + '<div class="model-add-body">'
            + '<div class="form-group">'
            + '<label>模型名称 <span class="required">*</span></label>'
            + '<input type="text" id="manualModelName" placeholder="例如 gpt-4o-mini">'
            + '</div>'
            + '<div class="form-group">'
            + '<label>上下文长度（可选）</label>'
            + '<input type="text" id="manualModelTokens" inputmode="numeric" placeholder="1000k（模型上下文长度）" list="manualContextLengthList" autocomplete="off">'
            + '<datalist id="manualContextLengthList">'
            + '<option value="128k">'
            + '<option value="256k">'
            + '<option value="512k">'
+ '<option value="1m">'
            + '</datalist>'
            + '</div>'
            + '</div>'
            + '<div class="model-add-footer">'
            + '<button class="btn-secondary" id="modelAddCancel">取消</button>'
            + '<button class="btn-primary" id="modelAddConfirm">确认添加</button>'
            + '</div>'
            + '</div>'
            + '</div>';

        $('body').append(dialogHtml);

        var $overlay = $('#modelAddOverlay');

        function doAdd() {
            var modelId = $overlay.find('#manualModelName').val().trim();
            var maxTokens = $overlay.find('#manualModelTokens').val().trim();

            if (!modelId) {
                layui.layer.msg('请输入模型名称', { icon: 0 });
                return;
            }

            var exists = fetchedModels.some(function (m) {
                return m.id === modelId;
            });
            if (exists) {
                layui.layer.msg('模型 "' + modelId + '" 已存在', { icon: 0 });
                return;
            }

            var newModel = { id: modelId, manual: true };
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
        $overlay.on('keypress', 'input', function(e) {
            if (e.which === 13) doAdd();
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
            layui.layer.msg('请先填写 API 地址', { icon: 0 });
            return;
        }

        var $btn = $('#providerFetchModelsBtn');
        $btn.prop('disabled', true).html('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="animation:spin 1s linear infinite"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>');

        $.ajax({
            url: '/web/settings/providers/fetch',
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
                        var fetchedMapped = models.map(function (m) {
                            return { id: m.id || m.name || m, manual: false };
                        });
                        // 手动模型去重：如果手动模型 id 已在拉取列表中，保留手动标记
                        var fetchedIds = {};
                        fetchedMapped.forEach(function (m) { fetchedIds[m.id] = m; });
                        manualModels.forEach(function (mm) {
                            if (fetchedIds[mm.id]) {
                                fetchedIds[mm.id].manual = true;
                                if (mm.maxInputTokens) {
                                    fetchedIds[mm.id].maxInputTokens = mm.maxInputTokens;
                                }
                            } else {
                                fetchedMapped.push(mm);
                            }
                        });
                        fetchedModels = fetchedMapped;
                        // 加载 LLM 模型列表缓存，用于判断同步状态
                        loadLlmModelsCache(function () {
                            renderModelsList();
                        });
                        layui.layer.msg('成功拉取 ' + fetchedModels.length + ' 个模型', { icon: 1 });
                    } catch (e) {
                        layui.layer.msg('解析模型列表失败', { icon: 2 });
                    }
                } else {
                    layui.layer.msg(res.msg || '拉取失败', { icon: 2 });
                }
            },
            error: function (xhr) {
                $btn.prop('disabled', false).html('<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>');
                layui.layer.msg('拉取模型列表失败: ' + (xhr.responseText || '网络错误'), { icon: 2 });
            }
        });
    }

    // 加载 LLM 模型列表缓存
    function loadLlmModelsCache(callback) {
        $.get('/web/settings/llm/models', function (res) {
            if (res.code === 200 && res.data) {
                var list = res.data.list || (Array.isArray(res.data) ? res.data : []);
                llmModelsCache = {};
                list.forEach(function (item) {
                    if (item.name) {
                        llmModelsCache[item.name] = item;
                    }
                });
            }
            if (callback) callback();
        }).fail(function () {
            if (callback) callback();
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

        var providerName = $('#providerName').val() || '';
        var providerEnabled = $('#providerEnabled').val() === 'true' || currentProvider && currentProvider.enabled !== false;
        var html = '';
        fetchedModels.forEach(function (model) {
            // 检查是否已同步到 LLM
            var llmName = providerName ? providerName + '-' + model.id : model.id;
            var syncedModel = llmModelsCache[llmName];
            var isSynced = !!syncedModel;
            // 使用 LLM 缓存的启用状态，如果未同步则使用供应商的启用状态
            var enabled = isSynced ? (syncedModel.enabled !== false && syncedModel.visibled !== false) : providerEnabled;

            var manualTag = model.manual ? ' <span class="provider-model-manual-tag">手动</span>' : '';
            var removeBtn = model.manual
                ? '<button class="provider-model-remove-btn" title="移除"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>'
                : '';
            html += '<div class="provider-model-item' + (!enabled ? ' disabled' : '') + '" data-model-id="' + model.id + '">' +
                '<div class="provider-model-info">' +
                    '<div class="provider-model-name">' + model.id + manualTag + (isSynced ? ' <span class="provider-model-synced">已同步</span>' : '') + '</div>' +
                '</div>' +
                '<div class="provider-model-actions">' +
                    removeBtn +
                    '<label class="toggle-switch" title="' + (enabled ? '停用' : '启用') + '">' +
                        '<input type="checkbox" ' + (enabled ? 'checked' : '') + ' class="provider-model-toggle" data-synced="' + isSynced + '" data-llm-name="' + llmName + '"/>' +
                        '<span class="toggle-slider"></span>' +
                    '</label>' +
                '</div>' +
            '</div>';
        });
        $modelsList.html(html);
    }

    function toggleProviderModel(modelId, enabled, llmName, isSynced) {
        // 如果已同步到 LLM，直接调用 LLM 接口更新
        if (isSynced && llmName) {
            postJson('/web/settings/llm/models/toggle', { name: llmName, enabled: enabled }, function (resp) {
                if (resp.code === 200) {
                    // 更新缓存
                    if (llmModelsCache[llmName]) {
                        llmModelsCache[llmName].enabled = enabled;
                    }
                    // 刷新 LLM 模型列表
                    if (window._settingsLlm) {
                        window._settingsLlm.load();
                    }
                } else {
                    layui.layer.msg('操作失败: ' + (resp.message || '未知错误'), { icon: 2 });
                    // 回滚状态
                    renderModelsList();
                }
            });
        }
    }

    // ==================== CRUD 操作 ====================
    function editProvider(name) {
        $.ajax({
            url: '/web/settings/providers/get',
            method: 'GET',
            data: { name: name },
            success: function (res) {
                if (res.code === 200) {
                    showForm(res.data);
                } else {
                    layui.layer.msg(res.msg || '获取供应商详情失败', { icon: 2 });
                }
            },
            error: function () {
                layui.layer.msg('获取供应商详情失败', { icon: 2 });
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
            if (m.maxInputTokens) {
                model.maxInputTokens = m.maxInputTokens;
            }
            return model;
        });

        if (!name) {
            layui.layer.msg('请填写供应商名称', { icon: 0 });
            return;
        }
        if (!apiUrl) {
            layui.layer.msg('请填写 API 地址', { icon: 0 });
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

        var url = currentProvider ? '/web/settings/providers/update' : '/web/settings/providers/add';

        $.ajax({
            url: url,
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(data),
            success: function (res) {
                if (res.code === 200) {
                    layui.layer.msg(currentProvider ? '供应商已更新' : '供应商已添加', { icon: 1 });
                    // 同步模型到 LLM 模型列表
                    syncModelsToLlm(data);
                    showList();
                } else {
                    layui.layer.msg(res.msg || '保存失败', { icon: 2 });
                }
            },
            error: function () {
                layui.layer.msg('保存失败', { icon: 2 });
            }
        });
    }

    function syncModelsToLlm(providerData) {
        // 调用后端接口同步模型
        $.ajax({
            url: '/web/settings/providers/sync-models',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({
                providerName: providerData.name,
                models: providerData.models || []
            }),
            success: function (res) {
                if (res.code === 200 && res.data > 0) {
                    layui.layer.msg('已同步 ' + res.data + ' 个模型到模型列表', { icon: 1 });
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

        layui.layer.confirm('确定要删除供应商 "' + currentProvider.name + '" 吗？', {
            btn: ['删除', '取消'],
            icon: 3
        }, function (index) {
            layui.layer.close(index);
            $.ajax({
                url: '/web/settings/providers/remove',
                method: 'POST',
                data: { name: currentProvider.name },
                success: function (res) {
                    if (res.code === 200) {
                        layui.layer.msg('供应商已删除', { icon: 1 });
                        showList();
                    } else {
                        layui.layer.msg(res.msg || '删除失败', { icon: 2 });
                    }
                },
                error: function () {
                    layui.layer.msg('删除失败', { icon: 2 });
                }
            });
        });
    }

    function toggleProvider(name, enabled) {
        $.ajax({
            url: '/web/settings/providers/toggle',
            method: 'POST',
            data: { name: name, enabled: enabled },
            success: function (res) {
                if (res.code === 200) {
                    layui.layer.msg(enabled ? '供应商已启用' : '供应商已禁用', { icon: 1 });
                    // 刷新 LLM 模型列表（供应商禁用时关联模型会禁用）
                    if (window._settingsLlm) {
                        window._settingsLlm.load();
                    }
                    // 通知聊天组件刷新模型下拉列表
                    if (typeof window.reloadModels === 'function') {
                        window.reloadModels();
                    }
                } else {
                    layui.layer.msg(res.msg || '操作失败', { icon: 2 });
                    loadProvidersList();
                }
            },
            error: function () {
                layui.layer.msg('操作失败', { icon: 2 });
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

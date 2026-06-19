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
        var enabledModelsCount = (provider.models || []).filter(function (m) { return m.enabled; }).length;
        
        return '<div class="mcp-server-item" data-name="' + provider.name + '">' +
            '<div class="mcp-server-icon">P</div>' +
            '<div class="mcp-server-info">' +
                '<div class="mcp-server-name">' + provider.name + ' <span class="settings-inline-tag">[' + (provider.standard || 'openai') + ']</span></div>' +
                '<div class="mcp-server-detail">' + (provider.apiUrl || '未配置') + '</div>' +
            '</div>' +
            '<div class="mcp-server-actions">' +
                '<span class="mcp-server-detail">' + enabledModelsCount + '/' + modelsCount + ' 模型</span>' +
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
                $btn.prop('disabled', false).html('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg> 拉取');
                if (res.code === 200) {
                    try {
                        var data = typeof res.data === 'string' ? JSON.parse(res.data) : res.data;
                        var models = data.data || data.models || data || [];
                        fetchedModels = models.map(function (m) {
                            return {
                                id: m.id || m.name || m,
                                enabled: true
                            };
                        });
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
                $btn.prop('disabled', false).html('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg> 拉取');
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
        var html = '';
        fetchedModels.forEach(function (model) {
            var enabled = model.enabled !== false;
            // 检查是否已同步到 LLM
            var llmName = providerName ? providerName + '-' + model.id : model.id;
            var syncedModel = llmModelsCache[llmName];
            var isSynced = !!syncedModel;
            var llmEnabled = syncedModel ? syncedModel.enabled !== false : true;

            html += '<div class="provider-model-item' + (!enabled || (isSynced && !llmEnabled) ? ' disabled' : '') + '" data-model-id="' + model.id + '">' +
                '<div class="provider-model-info">' +
                    '<div class="provider-model-name">' + model.id + (isSynced ? ' <span class="provider-model-synced">已同步</span>' : '') + '</div>' +
                '</div>' +
                '<div class="provider-model-actions">' +
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
        // 在本地状态中更新
        fetchedModels.forEach(function (m) {
            if (m.id === modelId) {
                m.enabled = enabled;
            }
        });

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
                    loadProvidersList();
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
            return { id: m.id, enabled: m.enabled !== false };
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

    // 自动初始化
    $(document).ready(function () {
        init();
    });
})();

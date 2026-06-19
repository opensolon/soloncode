/**
 * 供应商设置管理模块
 * 
 * 负责供应商的增删改查、模型列表拉取等功能
 */
;(function () {
    'use strict';

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
    var $modelsGrid = $('#providerModelsGrid');
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

        // 模型选择
        $modelsGrid.on('click', '.provider-model-chip', function () {
            $(this).toggleClass('selected');
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

        // 渲染模型列表
        renderModelsList();
    }

    function showList() {
        $formView.hide();
        $listView.show();
        currentProvider = null;
        fetchedModels = [];
        loadProvidersList();
    }

    // ==================== 模型列表 ====================
    function fetchModels() {
        var apiUrl = $('#providerApiUrl').val();
        var apiKey = $('#providerApiKey').val();
        var standard = $('#providerStandard').val();

        if (!apiUrl) {
            layui.layer.msg('请先填写 API 地址', { icon: 0 });
            return;
        }

        var $btn = $('#providerFetchModelsBtn');
        $btn.prop('disabled', true).text('拉取中...');

        $.ajax({
            url: '/web/settings/providers/fetch',
            method: 'POST',
            data: {
                apiUrl: apiUrl,
                apiKey: apiKey,
                standard: standard
            },
            success: function (res) {
                $btn.prop('disabled', false).text('拉取模型列表');
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
                        renderModelsList();
                        layui.layer.msg('成功拉取 ' + fetchedModels.length + ' 个模型', { icon: 1 });
                    } catch (e) {
                        layui.layer.msg('解析模型列表失败', { icon: 2 });
                    }
                } else {
                    layui.layer.msg(res.msg || '拉取失败', { icon: 2 });
                }
            },
            error: function (xhr) {
                $btn.prop('disabled', false).text('拉取模型列表');
                layui.layer.msg('拉取模型列表失败: ' + (xhr.responseText || '网络错误'), { icon: 2 });
            }
        });
    }

    function renderModelsList() {
        if (fetchedModels.length === 0) {
            $modelsEmpty.show();
            $modelsGrid.hide();
            return;
        }

        $modelsEmpty.hide();
        $modelsGrid.show();

        var html = '';
        fetchedModels.forEach(function (model) {
            var selected = model.enabled ? ' selected' : '';
            html += '<div class="provider-model-chip' + selected + '" data-id="' + model.id + '">' +
                '<span class="provider-model-name">' + model.id + '</span>' +
                '<span class="provider-model-check">✓</span>' +
            '</div>';
        });
        $modelsGrid.html(html);
    }

    function getSelectedModels() {
        var models = [];
        $modelsGrid.find('.provider-model-chip.selected').each(function () {
            models.push({
                id: $(this).data('id'),
                enabled: true
            });
        });
        return models;
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
        var models = getSelectedModels();

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

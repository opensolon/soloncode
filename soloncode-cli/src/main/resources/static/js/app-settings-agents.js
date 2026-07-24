/**
 * app-settings-agents.js — 智能体定义管理
 */
(function () {
    'use strict';

    var core = window._settingsCore;
    var escapeHtml = core.escapeHtml;
    var escapeAttr = core.escapeAttr;
    var postJson = core.postJson;
    var showToast = core.showToast;
    var setScopeValue = core.setScopeValue;
    var setScopeReadonly = core.setScopeReadonly;

    // 与“设置 / 工具权限”保持一致，避免两处可选工具内容不一致。
    var TOOLS_COLUMNS = [
        [
            { id: 'bash', name: 'bash', desc: 'Shell 命令执行' },
            { id: 'read', name: 'read', desc: '读取文件内容' },
            { id: 'write', name: 'write', desc: '写入文件内容' },
            { id: 'edit', name: 'edit', desc: '文件编辑' },
            { id: 'grep', name: 'grep', desc: '递归内容搜索' },
            { id: 'glob', name: 'glob', desc: '通配符文件搜索' },
            { id: 'ls', name: 'ls', desc: '列出目录内容' }
        ],
        [
            { id: 'codesearch', name: 'codesearch', desc: '网络代码搜索' },
            { id: 'websearch', name: 'websearch', desc: '网络搜索' },
            { id: 'webfetch', name: 'webfetch', desc: '网页内容抓取' }
        ],
        [
            { id: 'code', name: 'code', desc: '编码环境识别与引导' },
            { id: 'todo', name: 'todo', desc: '任务清单管理' },
            { id: 'skill', name: 'skill', desc: '专家技能调用' }
        ]
    ];

    var $list = $('#agentsList');
    var $listView = $('#agentsListView');
    var $formView = $('#agentsFormView');
    var $saveBtn = $('#agentsSaveBtn');
    var editName = null;
    var editScope = null;
    var builtinSource = false;
    var sourceName = null;
    var sourceScope = null;
    var selectedTools = [];

    function showListView() {
        $('#agentsToolsSelector').hide();
        $formView.hide();
        $listView.addClass('slide-back').show();
        setTimeout(function () { $listView.removeClass('slide-back'); }, 260);
    }

    function showFormView(title, editing) {
        $('#agentsFormTitle').text(title || '添加智能体');
        $listView.hide();
        $formView.show();
        $('#agentsFormActions').toggle(!!editing);
    }

    function loadList() {
        $.get('/web/settings/agents', function (resp) {
            if (resp.code === 200) renderList(resp.data || []);
            else showToast(resp.message || '智能体加载失败', 'error');
        }).fail(function () { showToast('智能体加载失败', 'error'); });
    }

    function renderList(items) {
        if (!items.length) {
            $list.html('<div class="mcp-empty-state"><div class="mcp-empty-title">暂无智能体</div><div class="mcp-empty-desc">添加用户全局或工作区智能体定义</div></div>');
            return;
        }
        var html = '';
        items.forEach(function (item) {
            var scope = item.scope || 'user';
            var sourceScope = item.sourceScope || scope;
            var badge = scope === 'workspace'
                ? '<span class="mounts-scope-badge scope-workspace">工作区</span>'
                : '';
            if (item.valid === false) badge += '<span class="agent-status-badge invalid">配置无效</span>';
            var tools = item.tools && item.tools.length ? item.tools.join(', ') : '未授予工具权限';
            html += '<div class="mcp-server-item agent-item" data-name="' + escapeAttr(item.name) + '" data-scope="' + escapeAttr(sourceScope) + '">'
                + '<div class="mcp-server-icon">A</div><div class="mcp-server-info">'
                + '<div class="mcp-server-name">' + escapeHtml(item.name) + ' ' + badge + '</div>'
                + '<div class="mcp-server-detail">' + escapeHtml(item.description || '') + '</div>'
                + '<div class="mcp-server-detail settings-accent-text">' + escapeHtml(tools) + '</div>'
                + '</div><div class="mcp-server-actions"><button class="mcp-action-btn edit" title="编辑">'
                + '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button></div></div>';
        });
        $list.html(html);
    }

    function allToolIds() {
        var ids = [];
        TOOLS_COLUMNS.forEach(function (column) {
            column.forEach(function (tool) { ids.push(tool.id); });
        });
        return ids;
    }

    function setSelectedTools(tools) {
        selectedTools = [];
        (tools || []).forEach(function (tool) {
            if (tool && selectedTools.indexOf(tool) < 0) selectedTools.push(tool);
        });
        renderToolsSelector();
        updateToolsSummary();
    }

    function renderToolsSelector() {
        var selectedMap = {};
        selectedTools.forEach(function (tool) { selectedMap[tool] = true; });
        var html = '';
        TOOLS_COLUMNS.forEach(function (column) {
            html += '<div class="permission-tools-column">';
            column.forEach(function (tool) {
                html += '<label class="permission-tool-item agent-tool-option">'
                    + '<span class="permission-tool-checkbox">'
                    + '<input type="checkbox" class="agent-tool-toggle" data-tool="' + escapeAttr(tool.id) + '" ' + (selectedMap[tool.id] ? 'checked' : '') + '/>'
                    + '<span class="permission-tool-checkmark"></span></span><span class="permission-tool-info">'
                    + '<span class="permission-tool-name">' + escapeHtml(tool.name) + '</span>'
                    + '<span class="permission-tool-desc">' + escapeHtml(tool.desc) + '</span></span></label>';
            });
            html += '</div>';
        });
        $('#agentsToolsList').html(html);
    }

    function updateToolsSummary() {
        var text;
        if (!selectedTools.length) text = '未授予工具权限';
        else if (selectedTools.length === allToolIds().length) text = '已选择全部工具';
        else text = '已选择 ' + selectedTools.length + ' 个工具：' + selectedTools.join(', ');
        $('#agentsToolsSummary').text(text);
    }

    function resetForm() {
        editName = null;
        editScope = null;
        builtinSource = false;
        sourceName = null;
        sourceScope = null;
        $('#agentsName').val('').prop('readOnly', false).removeClass('readonly-gray');
        $('#agentsDescription').val('').prop('readOnly', false).removeClass('readonly-gray');
        $('#agentsSystemPrompt').val('').prop('readOnly', false).removeClass('readonly-gray');
        $('#agentsToolsBtn').prop('disabled', false);
        $('#agentsToolsSelector').hide();
        setSelectedTools([]);
        setScopeValue('agentsScope', 'user');
        setScopeReadonly('agentsScope', false);
        $('#agentsFormActions, #agentsFormDeleteBtn').hide();
        $('#agentsFormCopyBtn').show();
        $saveBtn.show().text('保存');
    }

    function openAgent(name, scope) {
        $.get('/web/settings/agents/get', { name: name, scope: scope }, function (resp) {
            if (resp.code !== 200 || !resp.data) {
                showToast(resp.message || '读取失败', 'error');
                return;
            }
            var data = resp.data;
            editName = name;
            editScope = scope;
            builtinSource = data.builtin === true;
            sourceName = name;
            sourceScope = scope;
            showFormView(data.valid === false ? '修复智能体配置' : '编辑智能体', true);
            $('#agentsName').val(name).prop('readOnly', true).addClass('readonly-gray');
            $('#agentsDescription').val(data.description || '').prop('readOnly', false).removeClass('readonly-gray');
            $('#agentsSystemPrompt').val(data.systemPrompt || '').prop('readOnly', false).removeClass('readonly-gray');
            $('#agentsToolsBtn').prop('disabled', false);
            $('#agentsToolsSelector').hide();
            setSelectedTools(data.tools || []);
            if (data.valid === false) showToast('配置解析失败，请用当前表单重新保存修复：' + (data.parseError || ''), 'error');
            setScopeValue('agentsScope', scope);
            setScopeReadonly('agentsScope', false);
            $('#agentsFormDeleteBtn').toggle(!builtinSource);
            $('#agentsFormCopyBtn').show();
            $saveBtn.show().text('更新');
        });
    }

    function prepareCopy() {
        editName = null;
        editScope = null;
        builtinSource = false;
        showFormView('复制智能体', false);
        $('#agentsFormActions').hide();
        $('#agentsName').prop('readOnly', false).removeClass('readonly-gray').focus().select();
        $('#agentsDescription, #agentsSystemPrompt').prop('readOnly', false).removeClass('readonly-gray');
        $('#agentsToolsBtn').prop('disabled', false);
        setScopeReadonly('agentsScope', false);
        $saveBtn.show().text('保存副本');
    }

    $list.on('click', '.mcp-action-btn.edit', function (e) {
        e.stopPropagation();
        var $item = $(this).closest('.agent-item');
        openAgent($item.attr('data-name'), $item.attr('data-scope'));
    });

    $('#agentsAddBtn').on('click', function () {
        resetForm();
        setSelectedTools(allToolIds());
        showFormView('添加智能体', false);
        $('#agentsName').focus();
    });
    $('#agentsBackBtn').on('click', function () { showListView(); resetForm(); });
    $('#agentsFormCopyBtn').on('click', prepareCopy);

    $('#agentsToolsBtn').on('click', function () {
        if ($(this).prop('disabled')) return;
        $('#agentsToolsSelector').stop(true, true).slideToggle(140);
    });
    $('#agentsToolsList').on('change', '.agent-tool-toggle', function () {
        var tool = $(this).attr('data-tool');
        if ($(this).prop('checked')) {
            if (selectedTools.indexOf(tool) < 0) selectedTools.push(tool);
        } else {
            selectedTools = selectedTools.filter(function (item) { return item !== tool; });
        }
        updateToolsSummary();
    });

    $('#agentsRefreshBtn').on('click', function () {
        postJson('/web/settings/agents/refresh', {}, function (resp) {
            if (resp.code === 200) { showToast('刷新成功'); loadList(); if (typeof window.reloadCommandHints === 'function') window.reloadCommandHints(); }
            else showToast(resp.message || '刷新失败', 'error');
        });
    });

    $('#agentsFormDeleteBtn').on('click', function () {
        if (!editName || !editScope) return;
        layer.confirm('确定删除智能体“' + editName + '”的' + (editScope === 'workspace' ? '工作区' : '用户') + '定义？删除覆盖后会恢复下一级定义。',
            { title: '确认删除', btn: ['删除', '取消'], icon: 3, offset: '120px' }, function (index) {
                layer.close(index);
                postJson('/web/settings/agents/remove', { name: editName, scope: editScope }, function (resp) {
                    if (resp.code === 200) { showToast('删除成功'); showListView(); resetForm(); loadList(); if (typeof window.reloadCommandHints === 'function') window.reloadCommandHints(); }
                    else showToast(resp.message || '删除失败', 'error');
                });
            });
    });

    $saveBtn.on('click', function () {
        var name = $('#agentsName').val().trim();
        var scope = $('#agentsScope').val() || 'user';
        var description = $('#agentsDescription').val().trim();
        var systemPrompt = $('#agentsSystemPrompt').val().trim();
        if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(name)) { showToast('名称仅允许字母、数字、下划线和连字符，最长 64 个字符', 'error'); return; }
        if (!description) { showToast('请填写描述', 'error'); return; }
        if (!systemPrompt) { showToast('请填写系统提示词', 'error'); return; }
        var body = { name: name, scope: scope, description: description, tools: selectedTools, systemPrompt: systemPrompt };
        if (sourceName && sourceScope) { body.sourceName = sourceName; body.sourceScope = sourceScope; body.sourceBuiltin = builtinSource; }
        var isEdit = !!editName && !builtinSource;
        if (isEdit) { body.originalName = editName; body.originalScope = editScope; }
        $saveBtn.prop('disabled', true);
        postJson(isEdit ? '/web/settings/agents/update' : '/web/settings/agents/add', body, function (resp) {
            if (resp.code === 200) {
                showToast(builtinSource ? '已保存到用户（全局）' : (isEdit ? '更新成功' : '添加成功'));
                showListView(); resetForm(); loadList();
                if (typeof window.reloadCommandHints === 'function') window.reloadCommandHints();
            } else showToast(resp.message || '保存失败', 'error');
        }, function () { $saveBtn.prop('disabled', false); });
    });

    window._settingsAgents = { load: loadList, reset: resetForm, showList: showListView };
})();

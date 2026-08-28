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
            { id: 'bash', name: 'bash', get desc() { return I18n.t('agents.tool.bash'); } },
            { id: 'read', name: 'read', get desc() { return I18n.t('agents.tool.read'); } },
            { id: 'write', name: 'write', get desc() { return I18n.t('agents.tool.write'); } },
            { id: 'edit', name: 'edit', get desc() { return I18n.t('agents.tool.edit'); } },
            { id: 'grep', name: 'grep', get desc() { return I18n.t('agents.tool.grep'); } },
            { id: 'glob', name: 'glob', get desc() { return I18n.t('agents.tool.glob'); } },
            { id: 'ls', name: 'ls', get desc() { return I18n.t('agents.tool.ls'); } }
        ],
        [
            { id: 'codesearch', name: 'codesearch', get desc() { return I18n.t('agents.tool.codesearch'); } },
            { id: 'websearch', name: 'websearch', get desc() { return I18n.t('agents.tool.websearch'); } },
            { id: 'webfetch', name: 'webfetch', get desc() { return I18n.t('agents.tool.webfetch'); } }
        ],
        [
            { id: 'code', name: 'code', get desc() { return I18n.t('agents.tool.code'); } },
            { id: 'todo', name: 'todo', get desc() { return I18n.t('agents.tool.todo'); } },
            { id: 'skill', name: 'skill', get desc() { return I18n.t('agents.tool.skill'); } }
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
    var modelOptions = null;
    var currentModelValue = '';

    function fmtCtxLen(len) {
        if (!len) return '';
        if (len >= 1000000 && len % 1000000 === 0) return (len / 1000000) + 'm';
        if (len >= 1000) return (len / 1000) + 'k';
        return '' + len;
    }

    // 同步触发器文案：留空显示“跟随对话”，否则显示模型名。
    function renderModelTrigger() {
        var $name = $('#agentsModelName');
        if (currentModelValue) {
            $name.text(currentModelValue).removeAttr('data-i18n');
        } else {
            $name.text(I18n.t('agents.modelFollowDefault')).attr('data-i18n', 'agents.modelFollowDefault');
        }
    }

    // 渲染下拉项：首项“跟随对话”（空值）+ 已配置模型列表；保留当前选中值即使不在列表内。
    function renderModelDropdown(list) {
        var seen = {};
        var html = '<div class="model-dropdown-item' + (currentModelValue ? '' : ' active') + '" data-model="">'
            + '<span class="model-item-name">' + escapeHtml(I18n.t('agents.modelFollowDefault')) + '</span>'
            + '<span class="model-item-desc">' + escapeHtml(I18n.t('agents.modelHint')) + '</span>'
            + '</div>';
        (list || []).forEach(function (item) {
            var name = (item && (item.name || item.model)) || '';
            if (!name || seen[name]) return;
            seen[name] = true;
            var standard = (item && item.standard) || 'openai';
            var ctxLen = fmtCtxLen(item && item.contextLength);
            var descParts = [];
            if (item && item.desc) descParts.push(escapeHtml(item.desc));
            descParts.push('<span class="model-item-standard">[' + escapeHtml(standard) + ']</span>');
            var cls = name === currentModelValue ? ' active' : '';
            html += '<div class="model-dropdown-item' + cls + '" data-model="' + escapeAttr(name) + '">'
                + '<span class="model-item-name">' + escapeHtml(name) + (ctxLen ? '<span class="model-item-ctx">' + ctxLen + '</span>' : '') + '</span>'
                + '<span class="model-item-desc">' + descParts.join(' ') + '</span>'
                + '</div>';
        });
        if (currentModelValue && !seen[currentModelValue]) {
            html += '<div class="model-dropdown-item active" data-model="' + escapeAttr(currentModelValue) + '">'
                + '<span class="model-item-name">' + escapeHtml(currentModelValue) + '</span>'
                + '<span class="model-item-desc"><span class="model-item-standard">[?]</span></span>'
                + '</div>';
        }
        var $dropdown = $('#agentsModelDropdown');
        $dropdown.find('.model-dropdown-items').html(html);
        $dropdown.find('.model-search-input').val('');
        $dropdown.find('.model-dropdown-items').children().show();
    }

    // 填充“运行模型”选择器；保留当前选中值即使不在列表内。
    function populateModelOptions(current) {
        currentModelValue = current || '';
        $('#agentsModel').val(currentModelValue);
        renderModelTrigger();
        function render(list) { renderModelDropdown(list); }
        if (modelOptions) { render(modelOptions); return; }
        $.get('/web/settings/llm/models', function (resp) {
            var list = [];
            if (resp.code === 200 && resp.data) {
                var data = resp.data;
                list = data.list || (Array.isArray(data) ? data : []);
            }
            modelOptions = list;
            render(list);
        }).fail(function () { render([]); });
    }

    function selectModelValue(name) {
        currentModelValue = name || '';
        $('#agentsModel').val(currentModelValue);
        renderModelTrigger();
        // 重置 active 样式
        var $items = $('#agentsModelDropdown').find('.model-dropdown-item');
        $items.removeClass('active');
        $items.filter(function () { return ($(this).attr('data-model') || '') === currentModelValue; }).addClass('active');
    }

    // 初始化模型选择器交互（自包含，不依赖会话状态）
    function initModelSelector() {
        var $selector = $('#agentsModelSelector');
        var $current = $('#agentsModelCurrent');
        var $dropdown = $('#agentsModelDropdown');
        if (!$selector.length) return;

        $current.on('click keydown', function (e) {
            if (e.type === 'keydown' && e.key !== 'Enter' && e.key !== ' ') return;
            e.preventDefault();
            e.stopPropagation();
            var opening = !$selector.hasClass('open');
            $('.model-selector.open').removeClass('open');
            $selector.toggleClass('open', opening);
            if (opening) {
                requestAnimationFrame(function () {
                    var active = $dropdown.find('.model-dropdown-item.active').get(0);
                    if (active) active.scrollIntoView({ block: 'nearest' });
                    $dropdown.find('.model-search-input').val('').trigger('input').focus();
                });
            }
        });

        $dropdown.on('click', function (e) {
            var $item = $(e.target).closest('.model-dropdown-item');
            if (!$item.length) return;
            e.stopPropagation();
            selectModelValue($item.attr('data-model') || '');
            $selector.removeClass('open');
        });

        $dropdown.find('.model-search-input').on('input', function () {
            var query = ($(this).val() || '').toLowerCase().trim();
            var $items = $dropdown.find('.model-dropdown-items').children();
            $items.each(function () {
                if (!query) { $(this).show(); return; }
                var name = ($(this).attr('data-model') || '').toLowerCase();
                var text = ($(this).text() || '').toLowerCase();
                $(this).toggle(name.indexOf(query) !== -1 || text.indexOf(query) !== -1);
            });
        });

        $(document).on('click', function (e) {
            if ($(e.target).closest('#agentsModelSelector').length) return;
            $selector.removeClass('open');
        });
    }

    function showListView() {
        $('#agentsToolsSelector').hide();
        $formView.hide();
        $listView.addClass('slide-back').show();
        setTimeout(function () { $listView.removeClass('slide-back'); }, 260);
    }

    function showFormView(title, editing) {
        $('#agentsFormTitle').text(title || I18n.t('agents.formTitle.add'));
        $listView.hide();
        $formView.show();
        $('#agentsFormActions').toggle(!!editing);
    }

    function loadList() {
        $.get('/web/settings/agents', function (resp) {
            if (resp.code === 200) renderList(resp.data || []);
            else showToast(resp.message || I18n.t('toast.agentLoadFailed'), 'error');
        }).fail(function () { showToast(I18n.t('toast.agentLoadFailed'), 'error'); });
    }

    function renderList(items) {
        if (!items.length) {
            $list.html('<div class="mcp-empty-state"><div class="mcp-empty-title">' + I18n.t('agents.empty') + '</div><div class="mcp-empty-desc">' + I18n.t('agents.emptyDesc') + '</div></div>');
            return;
        }
        var html = '';
        items.forEach(function (item) {
            var scope = item.scope || 'user';
            var sourceScope = item.sourceScope || scope;
            var badge = scope === 'workspace'
                ? '<span class="mounts-scope-badge scope-workspace">' + I18n.t('agents.scopeBadge.workspace') + '</span>'
                : '';
            if (item.valid === false) badge += '<span class="agent-status-badge invalid">' + I18n.t('agents.statusBadge.invalid') + '</span>';
            var tools = item.tools && item.tools.length ? item.tools.join(', ') : I18n.t('agents.noTools');
            html += '<div class="settings-list-item agent-item' + (item.enabled === false ? ' disabled' : '') + '" data-name="' + escapeAttr(item.name) + '" data-scope="' + escapeAttr(sourceScope) + '">'
                + '<div class="settings-list-icon">A</div><div class="settings-list-info">'
                + '<div class="settings-list-title">' + escapeHtml(item.name) + ' ' + badge + '</div>'
                + '<div class="settings-list-desc">' + escapeHtml(item.description || '') + '</div>'
                + '<div class="settings-list-desc settings-accent-text">' + escapeHtml(tools) + '</div>'
                + '</div><div class="settings-list-actions"><button class="settings-action-btn edit" title="' + I18n.t('agents.edit') + '">'
                + '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>'
                + '<label class="toggle-switch" title="' + (item.enabled !== false ? I18n.t('agents.toggle.disable') : I18n.t('agents.toggle.enable')) + '">'
                + '<input type="checkbox" ' + (item.enabled !== false ? 'checked' : '') + ' data-name="' + escapeAttr(item.name) + '" data-scope="' + escapeAttr(sourceScope) + '" class="agents-toggle"/>'
                + '<span class="toggle-slider"></span></label></div></div>';
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
        var total = allToolIds().length;
        var text;
        if (!selectedTools.length) text = I18n.t('agents.noTools');
        else if (selectedTools.length === total) text = I18n.t('agents.toolAuthorizedAll', { n: total });
        else text = I18n.t('agents.toolAuthorizedPart', { n: selectedTools.length, m: total });
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
        $('#agentsToolsBtn').removeClass('is-open');
        $('#agentsToolsSelector').hide().removeClass('is-open');
        setSelectedTools([]);
        populateModelOptions('');
        setScopeValue('agentsScope', 'user');
        setScopeReadonly('agentsScope', false);
        $('#agentsFormActions, #agentsFormDeleteBtn').hide();
        $('#agentsFormCopyBtn').show();
        $saveBtn.show().text(I18n.t('agents.saveBtn'));
    }

    function openAgent(name, scope) {
        $.get('/web/settings/agents/get', { name: name, scope: scope }, function (resp) {
            if (resp.code !== 200 || !resp.data) {
                showToast(resp.message || I18n.t('agents.readFailed'), 'error');
                return;
            }
            var data = resp.data;
            editName = name;
            editScope = scope;
            builtinSource = data.builtin === true;
            sourceName = name;
            sourceScope = scope;
            showFormView(data.valid === false ? I18n.t('agents.repairConfig') : I18n.t('agents.formTitle.edit'), true);
            $('#agentsName').val(name).prop('readOnly', true).addClass('readonly-gray');
            $('#agentsDescription').val(data.description || '').prop('readOnly', false).removeClass('readonly-gray');
            $('#agentsSystemPrompt').val(data.systemPrompt || '').prop('readOnly', false).removeClass('readonly-gray');
            $('#agentsToolsBtn').prop('disabled', false);
            $('#agentsToolsBtn').removeClass('is-open');
            $('#agentsToolsSelector').hide().removeClass('is-open');
            setSelectedTools(data.tools || []);
            populateModelOptions(data.model || '');
            if (data.valid === false) showToast(I18n.t('agents.configParseError') + (data.parseError || ''), 'error');
            setScopeValue('agentsScope', scope);
            setScopeReadonly('agentsScope', false);
            $('#agentsFormDeleteBtn').toggle(!builtinSource);
            $('#agentsFormCopyBtn').show();
            $saveBtn.show().text(I18n.t('agents.updateBtn'));
        });
    }

    function prepareCopy() {
        editName = null;
        editScope = null;
        builtinSource = false;
        showFormView(I18n.t('agents.copyAgent'), false);
        $('#agentsFormActions').hide();
        $('#agentsName').prop('readOnly', false).removeClass('readonly-gray').focus().select();
        $('#agentsDescription, #agentsSystemPrompt').prop('readOnly', false).removeClass('readonly-gray');
        $('#agentsToolsBtn').prop('disabled', false);
        $('#agentsToolsBtn').removeClass('is-open');
        $('#agentsToolsSelector').hide().removeClass('is-open');
        setScopeReadonly('agentsScope', false);
        $saveBtn.show().text(I18n.t('agents.saveCopy'));
    }

    $list.on('click', '.settings-action-btn.edit', function (e) {
        e.stopPropagation();
        var $item = $(this).closest('.agent-item');
        openAgent($item.attr('data-name'), $item.attr('data-scope'));
    });

    $list.on('change', '.agents-toggle', function () {
        var $toggle = $(this);
        var enabled = this.checked;
        $toggle.prop('disabled', true);
        postJson('/web/settings/agents/toggle', {
            name: $toggle.attr('data-name'),
            scope: $toggle.attr('data-scope'),
            enabled: enabled
        }, function (resp) {
            if (resp.code === 200) {
                showToast(enabled ? I18n.t('agents.enabled') : I18n.t('agents.disabled'));
                loadList();
                if (typeof window.reloadCommandHints === 'function') window.reloadCommandHints();
            } else {
                $toggle.prop('checked', !enabled).prop('disabled', false);
                showToast(resp.message || I18n.t('agents.operateFailed'), 'error');
            }
        }, function () {
            $toggle.prop('disabled', false);
        });
    });

    $('#agentsAddBtn').on('click', function () {
        resetForm();
        setSelectedTools(allToolIds());
        showFormView(I18n.t('agents.formTitle.add'), false);
        $('#agentsName').focus();
    });
    $('#agentsBackBtn').on('click', function () { showListView(); resetForm(); });
    $('#agentsFormCopyBtn').on('click', prepareCopy);

    $('#agentsToolsBtn').on('click', function () {
        if ($(this).prop('disabled')) return;
        var $selector = $('#agentsToolsSelector');
        var opening = !$selector.is(':visible');
        $(this).toggleClass('is-open', opening);
        $selector.toggleClass('is-open', opening);
        $selector.stop(true, true).slideToggle(140);
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
            if (resp.code === 200) { showToast(I18n.t('agents.refreshSuccess')); loadList(); if (typeof window.reloadCommandHints === 'function') window.reloadCommandHints(); }
            else showToast(resp.message || I18n.t('agents.refreshFailed'), 'error');
        });
    });

    $('#agentsFormDeleteBtn').on('click', function () {
        if (!editName || !editScope) return;
        layer.confirm(I18n.t('agents.deleteConfirm', { name: editName, scope: editScope === 'workspace' ? I18n.t('agents.scope.workspace') : I18n.t('agents.scope.user') }),
            { title: I18n.t('agents.deleteConfirmTitle'), btn: [I18n.t('common.delete'), I18n.t('common.cancel')], icon: 3, offset: '120px' }, function (index) {
                layer.close(index);
                postJson('/web/settings/agents/remove', { name: editName, scope: editScope }, function (resp) {
                    if (resp.code === 200) { showToast(I18n.t('agents.deleteSuccess')); showListView(); resetForm(); loadList(); if (typeof window.reloadCommandHints === 'function') window.reloadCommandHints(); }
                    else showToast(resp.message || I18n.t('agents.deleteFailed'), 'error');
                });
            });
    });

    $saveBtn.on('click', function () {
        var name = $('#agentsName').val().trim();
        var scope = $('#agentsScope').val() || 'user';
        var description = $('#agentsDescription').val().trim();
        var systemPrompt = $('#agentsSystemPrompt').val().trim();
        if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(name)) { showToast(I18n.t('agents.nameInvalid'), 'error'); return; }
        if (!description) { showToast(I18n.t('agents.descriptionRequired'), 'error'); return; }
        if (!systemPrompt) { showToast(I18n.t('agents.systemPromptRequired'), 'error'); return; }
        var model = ($('#agentsModel').val() || '').trim();
        var body = { name: name, scope: scope, description: description, tools: selectedTools, model: model, systemPrompt: systemPrompt };
        if (sourceName && sourceScope) { body.sourceName = sourceName; body.sourceScope = sourceScope; body.sourceBuiltin = builtinSource; }
        var isEdit = !!editName && !builtinSource;
        if (isEdit) { body.originalName = editName; body.originalScope = editScope; }
        $saveBtn.prop('disabled', true);
        postJson(isEdit ? '/web/settings/agents/update' : '/web/settings/agents/add', body, function (resp) {
            if (resp.code === 200) {
                showToast(builtinSource ? I18n.t('agents.savedToUser') : (isEdit ? I18n.t('agents.updateSuccess') : I18n.t('agents.addSuccess')));
                showListView(); resetForm(); loadList();
                if (typeof window.reloadCommandHints === 'function') window.reloadCommandHints();
            } else showToast(resp.message || I18n.t('toast.saveFailed'), 'error');
        }, function () { $saveBtn.prop('disabled', false); });
    });

    initModelSelector();

    window._settingsAgents = { load: loadList, reset: resetForm, showList: showListView };
})();

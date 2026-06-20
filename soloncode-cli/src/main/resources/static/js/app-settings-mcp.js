/**
 * app-settings-mcp.js — 设置面板子模块
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

    var $mcpServerList = $('#mcpServerList');
    var $mcpSaveBtn = $('#mcpSaveBtn');
    var $mcpFormTitle = $('#mcpFormTitle');
    var $mcpListView = $('#mcpListView');
    var $mcpFormView = $('#mcpFormView');
    var $mcpTypeBtns = $('#mcpAddForm .mcp-type-btn');
    var $mcpCheckResult = $('#mcpCheckResult');
    var $mcpToolsView = $('#mcpToolsView');
    var $mcpToolsList = $('#mcpToolsList');
    var $mcpToolsTitle = $('#mcpToolsTitle');
    var mcpEditName = null;
    var mcpCachedList = [];

    function showMcpListView() { $mcpToolsView.hide(); $mcpFormView.hide(); $mcpListView.addClass('slide-back').show(); setTimeout(function(){ $mcpListView.removeClass('slide-back'); }, 260); }
    function showMcpFormView(title, isEdit) { $mcpToolsView.hide(); $mcpFormTitle.text(title || '添加服务器'); $mcpListView.hide(); $mcpFormView.show(); $('#mcpFormActions').toggle(!!isEdit); }
    function setMcpType(type) {
        $mcpTypeBtns.removeClass('active');
        $mcpTypeBtns.filter('[data-type="' + type + '"]').addClass('active');
        $('#mcpConfigStdio').toggle(type === 'stdio');
        $('#mcpConfigRemote').toggle(type === 'sse' || type === 'streamable');
    }

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
                    + '<div class="mcp-server-name">' + escapeHtml(name) + ' <span class="settings-inline-tag">[' + escapeHtml(type) + ']</span>' + (item.scope === 'workspace' ? ' <span class="mounts-scope-badge scope-workspace">工作区</span>' : '') + '</div>'
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
            html += '<div class="mcp-server-item mcp-tool-item" data-tool="' + escapeAttr(toolName) + '">'
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
        $('#mcpName').val('').prop('readOnly', false).removeClass('readonly-gray');
        $('#mcpCommand, #mcpArgs, #mcpEnv, #mcpRemoteUrl, #mcpHeaders, #mcpTimeout').val('');
        setScopeValue('mcpScope', 'user');
        setScopeReadonly('mcpScope', false);
        setMcpType('stdio');
    }

    function fillMcpForm(server) {
        var type = server.type || 'stdio';
        setMcpType(type);
        setScopeValue('mcpScope', server.scope || 'user');

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
        var type = $('#mcpAddForm .mcp-type-btn.active').attr('data-type') || 'stdio';
        if (!name) { showToast('名称为必填项', 'error'); return null; }
        if (!/^[a-zA-Z0-9_-]+$/.test(name)) { showToast('名称仅允许字母、数字、下划线和连字符', 'error'); return null; }

        var bodyObj = { name: name, type: type, enabled: true, scope: $('#mcpScope').val() || 'user' };

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
            if (timeout) bodyObj.timeout = parseTimeout(timeout) || timeout;
        }
        return bodyObj;
    }

    function mcpEditServer(name) {
        var server = mcpCachedList.find(function (s) { return s.name === name; });
        if (!server) return;
        mcpEditName = name;
        showMcpFormView('编辑服务器', true);
        $mcpSaveBtn.text('更新');
        $('#mcpName').val(server.name).prop('readOnly', true).addClass('readonly-gray');
        fillMcpForm(server);
    }

    function mcpCopyServer(name) {
        var server = mcpCachedList.find(function (s) { return s.name === name; });
        if (!server) return;
        mcpEditName = null;
        showMcpFormView('添加服务器', false);
        $mcpSaveBtn.text('保存');
        $('#mcpName').val(server.name + '-copy').prop('readOnly', false).removeClass('readonly-gray');
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
                if (resp.code === 200) { showToast(actionText + '成功'); loadMcpList(); showMcpListView(); resetMcpForm(); }
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

    // ==================== MCP 导入（增强版） ====================
    
    // 导入状态跟踪，用于回滚
    var lastImportSession = null;
    var importPreviewDialog = null;
    
    /**
     * 统一解析 timeout 值，兼容多种格式
     * 支持: 30, "30", "30s", "30S", "PT30S" (ISO-8601)
     * @param {*} val - timeout 原始值
     * @returns {string|null} ISO-8601 格式的 duration 字符串，如 "PT30S"
     */
    function parseTimeout(val) {
        if (val === undefined || val === null || val === '') return null;
        if (typeof val === 'number') {
            return 'PT' + val + 'S';
        }
        var str = String(val).trim().toUpperCase();
        // 已经是 ISO-8601 格式
        if (/^PT\d+(\.\d+)?[SMHD]$/.test(str)) {
            return str;
        }
        // 纯数字字符串
        if (/^\d+$/.test(str)) {
            return 'PT' + str + 'S';
        }
        // 数字 + s/m/h/d 后缀
        var match = str.match(/^(\d+(\.\d+)?)\s*([SMHD])?$/);
        if (match) {
            var num = match[1];
            var unit = match[3] || 'S';
            return 'PT' + num + unit;
        }
        // 无法识别，原样返回（后端可能会报错）
        return str;
    }
    
    /**
     * 解析可能包含注释的 JSON（JSON5 兼容）
     * 支持去除单行注释(//) 和多行注释(斜杠+星号...星号+斜杠)，以及尾逗号
     * @param {string} text - 原始文本
     * @returns {Object} 解析后的配置对象
     */
    function parseJsonWithComments(text) {
        // 先尝试标准解析（标准 JSON 直接通过，避免误伤 URL 中的 //）
        try {
            return JSON.parse(text);
        } catch (e) {
            // 标准解析失败，再走去注释逻辑处理
        }
        // 去除注释，使用负向后顾避开 https:// http:// 等 URL 中的 //
        var cleaned = text.replace(/(?<!:)//.*$/gm, '')   // 单行注释
                          .replace(/\/\*[\s\S]*?\*\//g, ''); // 多行注释
        // 再去掉尾逗号（最后一个属性后的逗号）
        cleaned = cleaned.replace(/,([\t\r\n ]+[}\]])/g, '$1');
        return JSON.parse(cleaned);
    }
    
    /**
     * 检测并解析配置文件的格式
     * @param {Object} config - 解析后的 JSON 对象
     * @returns {{format: string, mcpServers: Object, name: string}} 检测结果
     */
    function detectFormat(config) {
        // 1. OpenCode 格式 ($schema 字段)
        if (config.$schema && typeof config.$schema === 'string' &&
            config.$schema.indexOf('opencode.ai/config') !== -1 &&
            config.mcp) {
            return { format: 'OpenCode', mcpServers: config.mcp };
        }
        // 2. Claude/Cursor 等通用 mcpServers 格式
        if (config.mcpServers && typeof config.mcpServers === 'object' && !Array.isArray(config.mcpServers)) {
            return { format: 'mcpServers', mcpServers: config.mcpServers };
        }
        // 3. 显式格式声明
        if (config.format === 'mcp' && config.servers) {
            return { format: 'explicit', mcpServers: config.servers };
        }
        // 未识别
        return null;
    }
    
    /**
     * 创建导入预览对话框
     * @param {{format:string, mcpServers:Object}} detection - 格式检测结果
     * @param {Function} onConfirm - 确认回调，接收选中的服务器名列表
     */
    function showImportPreview(detection, onConfirm) {
        var serverNames = Object.keys(detection.mcpServers);
        if (serverNames.length === 0) {
            showToast('未找到有效的 MCP 服务器配置', 'error');
            return;
        }
        
        // 构建各服务器的预览信息
        var previewItems = '';
        var allNew = true;
        serverNames.forEach(function(name) {
            var cfg = detection.mcpServers[name];
            var typeLabel = 'stdio';
            if (cfg.type === 'remote' || cfg.type === 'sse') typeLabel = 'sse';
            else if (cfg.type === 'streamable') typeLabel = 'streamable';
            else if (cfg.type === 'local' || cfg.command) typeLabel = 'stdio';
            else if (cfg.url) typeLabel = 'sse';
            
            var detail = typeLabel === 'stdio'
                ? (Array.isArray(cfg.command) ? cfg.command.join(' ') : (cfg.command || ''))
                : (cfg.url || '');
            
            var exists = mcpCachedList.some(function(s) { return s.name === name; });
            if (!exists) allNew = false;
            var statusBadge = exists
                ? '<span class="import-preview-badge badge-exists" title="已存在同名服务器，导入将跳过">已存在</span>'
                : '<span class="import-preview-badge badge-new">新导入</span>';
            var disabled = exists ? ' disabled' : '';
            
            previewItems += '<div class="import-preview-item">'
                + '<label class="import-preview-checkbox' + disabled + '">'
                + '<input type="checkbox" class="import-server-checkbox" value="' + escapeAttr(name) + '"'
                + (exists ? '' : ' checked') + disabled + '/>'
                + '<span class="mcp-tool-checkmark"></span>'
                + '</label>'
                + '<div class="import-preview-info">'
                + '<div class="import-preview-name">' + escapeHtml(name) + ' <span class="settings-inline-tag">[' + escapeHtml(typeLabel) + ']</span>' + statusBadge + '</div>'
                + '<div class="import-preview-detail">' + escapeHtml(detail) + '</div>'
                + '</div></div>';
        });
        
        // 检测来源格式标签
        var formatLabel = detection.format || '自动检测';
        var formatTag = '<span class="import-format-tag">' + escapeHtml(formatLabel) + '</span>';
        
        var dialogHtml = '<div class="import-overlay" id="importPreviewOverlay">'
            + '<div class="import-dialog">'
            + '<div class="import-dialog-header">'
            + '<span class="import-dialog-title">导入 MCP 服务器</span>'
            + '<button class="import-dialog-close" id="importPreviewClose">&times;</button>'
            + '</div>'
            + '<div class="import-dialog-body">'
            + '<div class="import-summary">'
            + '检测到 <strong>' + serverNames.length + '</strong> 个 MCP 服务器配置 ' + formatTag
            + '<br/><span class="import-hint">请勾选需要导入的服务器，已存在的服务器将自动跳过</span>'
            + '</div>'
            + '<div class="import-preview-list">' + previewItems + '</div>'
            + '</div>'
            + '<div class="import-dialog-footer">'
            + '<button class="btn-secondary" id="importPreviewCancel">取消</button>'
            + '<button class="btn-primary" id="importPreviewConfirm">导入所选 (<span id="importSelectedCount">' + (serverNames.length - (allNew ? 0 : serverNames.filter(function(n){return mcpCachedList.some(function(s){return s.name===n;})}).length)) + '</span>)</button>'
            + '</div>'
            + '</div>'
            + '</div>';
        
        // 添加对话框到页面
        $('body').append(dialogHtml);
        
        var $overlay = $('#importPreviewOverlay');
        var $checks = $overlay.find('.import-server-checkbox');
        
        function updateCount() {
            var count = $checks.filter(':checked').length;
            $('#importSelectedCount').text(count);
        }
        
        $checks.on('change', updateCount);
        
        $('#importPreviewConfirm').on('click', function() {
            var selected = [];
            $checks.filter(':checked').each(function() {
                selected.push($(this).val());
            });
            $overlay.remove();
            if (selected.length > 0) {
                onConfirm(selected);
            } else {
                showToast('未选择任何服务器', 'info');
            }
        });
        
        $('#importPreviewCancel, #importPreviewClose').on('click', function() {
            $overlay.remove();
        });
        
        // 点击遮罩层关闭
        $overlay.on('click', function(e) {
            if (e.target === this) $overlay.remove();
        });
    }
    
    /**
     * 创建导入进度对话框
     */
    function createProgressDialog() {
        var html = '<div class="import-overlay" id="importProgressOverlay">'
            + '<div class="import-dialog import-dialog-progress">'
            + '<div class="import-dialog-header">'
            + '<span class="import-dialog-title">正在导入...</span>'
            + '</div>'
            + '<div class="import-dialog-body">'
            + '<div class="import-progress-bar-wrap">'
            + '<div class="import-progress-fill" id="importProgressFill" style="width:0%"></div>'
            + '</div>'
            + '<div class="import-progress-status" id="importProgressStatus">准备中...</div>'
            + '<div class="import-progress-log" id="importProgressLog"></div>'
            + '</div>'
            + '</div>'
            + '</div>';
        $('body').append(html);
    }
    
    function updateProgress(current, total, statusText) {
        var pct = Math.round((current / total) * 100);
        $('#importProgressFill').css('width', pct + '%');
        $('#importProgressStatus').text(pct + '% - ' + statusText);
    }
    
    function appendProgressLog(text, isError) {
        var $log = $('#importProgressLog');
        var cls = isError ? ' class="import-log-error"' : '';
        $log.append('<div' + cls + '>' + escapeHtml(text) + '</div>');
        $log.scrollTop($log[0].scrollHeight);
    }
    
    /**
     * 导入完成后创建结果对话框（含回滚支持）
     */
    function showImportResult(result) {
        $('#importProgressOverlay').remove();
        
        var hasImported = result.imported.length > 0;
        var hasSkipped = result.skipped.length > 0;
        var hasErrors = result.errors.length > 0;
        
        var importedHtml = '';
        if (hasImported) {
            importedHtml = '<div class="import-result-section">'
                + '<div class="import-result-title success">✓ 成功导入 (' + result.imported.length + ')</div>'
                + '<div class="import-result-items">';
            result.imported.forEach(function(name) {
                importedHtml += '<div class="import-result-item imported-item" data-name="' + escapeAttr(name) + '">'
                    + '<span class="import-result-name">' + escapeHtml(name) + '</span>'
                    + '</div>';
            });
            importedHtml += '</div></div>';
        }
        
        var skippedHtml = '';
        if (hasSkipped) {
            skippedHtml = '<div class="import-result-section">'
                + '<div class="import-result-title skipped">→ 已跳过 (' + result.skipped.length + ')</div>'
                + '<div class="import-result-items">';
            result.skipped.forEach(function(item) {
                skippedHtml += '<div class="import-result-item skipped-item">'
                    + '<span class="import-result-name">' + escapeHtml(item.name) + '</span>'
                    + '<span class="import-result-reason">' + escapeHtml(item.reason || '已存在') + '</span>'
                    + '</div>';
            });
            skippedHtml += '</div></div>';
        }
        
        var errorsHtml = '';
        if (hasErrors) {
            errorsHtml = '<div class="import-result-section">'
                + '<div class="import-result-title error">✗ 导入失败 (' + result.errors.length + ')</div>'
                + '<div class="import-result-items">';
            result.errors.forEach(function(item) {
                errorsHtml += '<div class="import-result-item error-item">'
                    + '<span class="import-result-name">' + escapeHtml(item.name) + '</span>'
                    + '<span class="import-result-reason">' + escapeHtml(item.reason || '未知错误') + '</span>'
                    + '</div>';
            });
            errorsHtml += '</div></div>';
        }
        
        var rollbackBtn = hasImported
            ? '<button class="btn-secondary" id="importRollbackBtn">↩ 撤销导入</button>'
            : '';
        
        var dialogHtml = '<div class="import-overlay" id="importResultOverlay">'
            + '<div class="import-dialog import-dialog-result">'
            + '<div class="import-dialog-header">'
            + '<span class="import-dialog-title">导入完成</span>'
            + '<button class="import-dialog-close" id="importResultClose">&times;</button>'
            + '</div>'
            + '<div class="import-dialog-body">'
            + importedHtml + skippedHtml + errorsHtml
            + '<div class="import-result-summary">共 ' + result.total + ' 个服务器，'
            + '成功 ' + result.imported.length + '，'
            + '跳过 ' + result.skipped.length + '，'
            + '失败 ' + result.errors.length + ''
            + '</div>'
            + '</div>'
            + '<div class="import-dialog-footer">'
            + rollbackBtn
            + '<button class="btn-primary" id="importResultDone">完成</button>'
            + '</div>'
            + '</div>'
            + '</div>';
        
        $('body').append(dialogHtml);
        
        var $overlay = $('#importResultOverlay');
        
        $('#importResultClose, #importResultDone').on('click', function() {
            $overlay.remove();
            loadMcpList();
        });
        
        // 回滚逻辑
        if (hasImported) {
            $('#importRollbackBtn').on('click', function() {
                if (!confirm('确定要撤销刚刚导入的 ' + result.imported.length + ' 个 MCP 服务器吗？')) return;
                var $btn = $(this);
                $btn.prop('disabled', true).text('撤销中...');
                rollbackImport(result.imported, function(successCount) {
                    $overlay.remove();
                    loadMcpList();
                    showToast('已撤销 ' + successCount + ' 个服务器', 'info');
                });
            });
        }
        
        $overlay.on('click', function(e) {
            if (e.target === this) {
                $overlay.remove();
                loadMcpList();
            }
        });
    }
    
    /**
     * 回滚导入：逐个删除刚导入的服务器
     */
    function rollbackImport(names, callback) {
        var completed = 0;
        var successCount = 0;
        
        function delNext(idx) {
            if (idx >= names.length) {
                callback(successCount);
                return;
            }
            $.ajax({
                url: '/web/settings/mcp/servers/remove',
                method: 'POST',
                data: JSON.stringify({ name: names[idx] }),
                contentType: 'application/json',
                dataType: 'json',
                success: function(resp) {
                    if (resp.code === 200) successCount++;
                },
                complete: function() {
                    delNext(idx + 1);
                }
            });
        }
        delNext(0);
    }
    
    // ==================== 入口事件绑定 ====================
    
    // 导入按钮点击事件
    $('#mcpImportBtn').on('click', function () {
        $('#mcpImportFileInput').trigger('click');
    });
    
    // 文件选择变化事件
    $('#mcpImportFileInput').on('change', function (e) {
        var file = e.target.files[0];
        if (!file) return;
        
        var reader = new FileReader();
        reader.onload = function (e) {
            try {
                var text = e.target.result;
                // 支持带注释的 JSON（JSON5）
                var config = parseJsonWithComments(text);
                // 检测格式
                var detection = detectFormat(config);
                if (!detection) {
                    showToast('无法识别的配置文件格式: 期望 OpenCode 或 mcpServers 格式', 'error');
                    return;
                }
                // 显示预览对话框
                showImportPreview(detection, function(selectedNames) {
                    // 用户确认后开始导入
                    executeImport(selectedNames, detection.mcpServers);
                });
            } catch (error) {
                showToast('文件解析失败: ' + error.message, 'error');
            }
        };
        reader.readAsText(file);
        // 重置文件输入，允许再次选择同一文件
        e.target.value = '';
    });
    
    /**
     * 执行导入（含进度反馈）
     * @param {string[]} names - 要导入的服务器名称列表
     * @param {Object} mcpServers - 完整的 MCP 配置对象
     */
    function executeImport(names, mcpServers) {
        var result = {
            total: names.length,
            imported: [],
            skipped: [],
            errors: []
        };
        
        createProgressDialog();
        appendProgressLog('开始导入 ' + names.length + ' 个 MCP 服务器...');
        
        function processNext(index) {
            if (index >= names.length) {
                // 完成
                appendProgressLog('导入完成！');
                lastImportSession = result;
                showImportResult(result);
                return;
            }
            
            var name = names[index];
            var serverConfig = mcpServers[name];
            
            updateProgress(index + 1, names.length, '正在导入 ' + (index + 1) + '/' + names.length + ': ' + name);
            
            // 检查是否已存在同名服务器
            var exists = mcpCachedList.some(function(s) { return s.name === name; });
            if (exists) {
                result.skipped.push({ name: name, reason: '已存在同名服务器' });
                appendProgressLog('✖ ' + name + ': 已跳过（已存在）', false);
                processNext(index + 1);
                return;
            }
            
            // 转换配置格式
            var mcpBody = convertToMcpBodyWithNames(name, serverConfig);
            if (!mcpBody) {
                result.errors.push({ name: name, reason: '无法识别的配置格式' });
                appendProgressLog('✗ ' + name + ': 无法识别配置格式', true);
                processNext(index + 1);
                return;
            }
            
            // 调用保存 API
            appendProgressLog('→ ' + name + ': 正在导入...');
            $.ajax({
                url: '/web/settings/mcp/servers/add',
                method: 'POST',
                data: JSON.stringify(mcpBody),
                contentType: 'application/json',
                dataType: 'json',
                success: function(resp) {
                    if (resp.code === 200) {
                        result.imported.push(name);
                        appendProgressLog('✓ ' + name + ': 导入成功');
                    } else {
                        result.errors.push({ name: name, reason: resp.message || '导入失败' });
                        appendProgressLog('✗ ' + name + ': ' + (resp.message || '失败'), true);
                    }
                    processNext(index + 1);
                },
                error: function(jqXHR, textStatus) {
                    result.errors.push({ name: name, reason: '网络错误: ' + textStatus });
                    appendProgressLog('✗ ' + name + ': 网络错误', true);
                    processNext(index + 1);
                }
            });
        }
        
        processNext(0);
    }
    
    /**
     * 将单个 MCP 服务器配置转换为标准格式（增强版：含名称校验 + timeout 统一解析）
     * @param {string} name - 服务器名称
     * @param {Object} config - 原始配置
     * @returns {Object|null} 转换后的配置对象
     */
    function convertToMcpBodyWithNames(name, config) {
        // 名称校验（与手动添加规则一致）
        if (!/^[a-zA-Z0-9_\-]+$/.test(name)) {
            return null;
        }
        
        var bodyObj = {
            name: name,
            enabled: config.enabled !== false,
            scope: 'user'
        };
        
        // OpenCode 格式: type: 'local' -> stdio, type: 'remote' -> sse/streamable
        if (config.type === 'local' || (!config.type && config.command)) {
            bodyObj.type = 'stdio';
            bodyObj.command = Array.isArray(config.command) ? config.command.join(' ') : config.command;
            if (config.args) {
                bodyObj.args = config.args;
            } else if (Array.isArray(config.command) && config.command.length > 1) {
                bodyObj.args = config.command.slice(1);
                bodyObj.command = config.command[0];
            }
            // 支持 environment / env / envVars / environmentVariables 等常见命名
            var env = config.environment || config.env || config.envVars || config.environmentVariables;
            if (env) {
                bodyObj.env = env;
            }
        } else if (config.type === 'remote' || config.type === 'sse' || config.type === 'streamable') {
            bodyObj.type = config.type === 'remote' ? 'streamable' : config.type;
            bodyObj.url = config.url;
            if (config.headers) {
                bodyObj.headers = config.headers;
            }
            if (config.timeout) {
                bodyObj.timeout = parseTimeout(config.timeout);
            }
        } else {
            // 尝试自动检测类型
            if (config.command) {
                bodyObj.type = 'stdio';
                bodyObj.command = Array.isArray(config.command) ? config.command.join(' ') : config.command;
                if (config.args) bodyObj.args = config.args;
                var env2 = config.environment || config.env || config.envVars || config.environmentVariables;
                if (env2) bodyObj.env = env2;
            } else if (config.url) {
                bodyObj.type = 'sse';
                bodyObj.url = config.url;
                if (config.headers) bodyObj.headers = config.headers;
                if (config.timeout) bodyObj.timeout = parseTimeout(config.timeout);
            } else {
                return null;
            }
        }
        
        return bodyObj;
    }
    
    // 保持旧函数名兼容（可删除）
    var convertToMcpBody = convertToMcpBodyWithNames;
    
    window._settingsMcp = { load: loadMcpList, reset: resetMcpForm, showList: showMcpListView };
})();

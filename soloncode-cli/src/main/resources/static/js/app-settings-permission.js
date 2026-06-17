/**
 * app-settings-permission.js — 工具权限设置模块（可视化工具列表）
 *
 * 依赖：layui.js（jQuery）
 */
(function () {
    'use strict';

    // 工具定义列表
    var TOOLS_LIST = [
        // 公域工具
        { id: 'bash', name: 'bash', desc: 'Shell 命令执行', category: 'builtin', risk: 'high' },
        { id: 'read', name: 'read', desc: '读取文件内容', category: 'builtin', risk: 'low' },
        { id: 'write', name: 'write', desc: '写入文件内容', category: 'builtin', risk: 'high' },
        { id: 'edit', name: 'edit', desc: '文件编辑', category: 'builtin', risk: 'medium' },
        { id: 'grep', name: 'grep', desc: '递归内容搜索', category: 'builtin', risk: 'low' },
        { id: 'glob', name: 'glob', desc: '通配符文件搜索', category: 'builtin', risk: 'low' },
        { id: 'ls', name: 'ls', desc: '列出目录内容', category: 'builtin', risk: 'low' },
        { id: 'codesearch', name: 'codesearch', desc: '网络代码搜索', category: 'builtin', risk: 'low' },
        { id: 'websearch', name: 'websearch', desc: '网络搜索', category: 'builtin', risk: 'low' },
        { id: 'webfetch', name: 'webfetch', desc: '网页内容抓取', category: 'builtin', risk: 'low' },
        { id: 'todo', name: 'todo', desc: '任务清单管理', category: 'builtin', risk: 'low' },
        { id: 'skill', name: 'skill', desc: '专家技能调用', category: 'builtin', risk: 'low' },
        { id: 'task', name: 'task', desc: '子代理任务委派', category: 'builtin', risk: 'medium' },
        
        // 私域工具
        { id: 'hitl', name: 'hitl', desc: '人工介入审核', category: 'private', risk: 'low' },
        { id: 'generate', name: 'generate', desc: '动态生成子代理', category: 'private', risk: 'high' },
        { id: 'restapi', name: 'restapi', desc: 'Web 服务 API 接入', category: 'private', risk: 'medium' },
        { id: 'mcp', name: 'mcp', desc: 'MCP 服务接入', category: 'private', risk: 'medium' },
        { id: 'lsp', name: 'lsp', desc: 'LSP 代码理解服务', category: 'private', risk: 'low' },
        
    ];

    // 分类显示名称
    var CATEGORY_NAMES = {
        'builtin': '公域工具',
        'private': '私域工具'
    };

    // 风险等级显示
    var RISK_LABELS = {
        'high': '<span class="permission-risk-high">高风险</span>',
        'medium': '<span class="permission-risk-medium">中风险</span>',
        'low': ''
    };

    function showToast(msg, type) {
        if (typeof layer !== 'undefined' && layer.msg) {
            layer.msg(msg, { icon: type === 'error' ? 2 : 1, time: 2500, offset: '120px' });
        } else {
            alert(msg);
        }
    }

    // 数组 -> 多行文本
    function toLines(arr) {
        if (!arr || !arr.length) return '';
        return arr.join('\n');
    }

    // 多行文本 -> 去重去空的数组
    function toList(text) {
        var seen = {};
        var out = [];
        (text || '').split('\n').forEach(function (line) {
            var v = line.trim();
            if (v && !seen[v]) {
                seen[v] = true;
                out.push(v);
            }
        });
        return out;
    }

    // 渲染工具列表
    function renderToolsList(disallowedTools) {
        var disallowedMap = {};
        disallowedTools.forEach(function (t) { disallowedMap[t] = true; });

        var $list = $('#permissionToolsList');
        
        // 按分类分组
        var groups = {};
        TOOLS_LIST.forEach(function (tool) {
            if (!groups[tool.category]) groups[tool.category] = [];
            groups[tool.category].push(tool);
        });

        var html = '';
        Object.keys(groups).forEach(function (category) {
            var tools = groups[category];
            var enabledCount = tools.filter(function (t) { return !disallowedMap[t.id]; }).length;
            
            html += '<div class="permission-category">';
            html += '<div class="permission-category-header">';
            html += '<span class="permission-category-title">' + CATEGORY_NAMES[category] + ' (' + tools.length + ')</span>';
            html += '<span class="permission-category-count">' + enabledCount + '/' + tools.length + ' 已启用</span>';
            html += '<button class="permission-category-toggle btn-link" data-category="' + category + '">全选/取消</button>';
            html += '</div>';
            html += '<div class="permission-category-tools">';
            
            tools.forEach(function (tool) {
                var isEnabled = !disallowedMap[tool.id];
                html += '<div class="permission-tool-item">';
                html += '<label class="permission-tool-checkbox" title="' + (isEnabled ? '禁用' : '启用') + '">';
                html += '<input type="checkbox" ' + (isEnabled ? 'checked' : '') + ' data-tool="' + escapeAttr(tool.id) + '" class="permission-tool-toggle"/>';
                html += '<span class="permission-tool-checkmark"></span>';
                html += '</label>';
                html += '<div class="permission-tool-info">';
                html += '<div class="permission-tool-name">' + escapeHtml(tool.name) + RISK_LABELS[tool.risk] + '</div>';
                html += '<div class="permission-tool-desc">' + escapeHtml(tool.desc) + '</div>';
                html += '</div>';
                html += '</div>';
            });
            
            html += '</div>';
            html += '</div>';
        });

        if (html === '') {
            html = '<div class="permission-empty-state">没有找到匹配的工具</div>';
        }

        $list.html(html);
        updatePermissionCount(disallowedTools);
    }

    // 更新计数
    function updatePermissionCount(disallowedTools) {
        var total = TOOLS_LIST.length;
        var disabled = disallowedTools.length;
        var enabled = total - disabled;
        $('#permissionCount').text(enabled + '/' + total + ' 已启用');
    }

    // 加载权限设置
    function loadPermissionSettings() {
        $.get('/web/settings/permission', function (resp) {
            if (resp.code === 200 && resp.data) {
                var disallowedTools = resp.data.disallowedTools || [];
                renderToolsList(disallowedTools);
                // 高级模式同步
                $('#permissionDisallowedTools').val(toLines(disallowedTools));
            }
        }).fail(function () { console.error('[Settings] Failed to load permission settings'); });
    }

    // 获取当前禁用的工具列表
    function getDisallowedTools() {
        var disallowedTools = [];
        $('#permissionToolsList .permission-tool-toggle:not(:checked)').each(function () {
            disallowedTools.push($(this).attr('data-tool'));
        });
        return disallowedTools;
    }

    // 保存权限设置
    function savePermissionSettings() {
        var disallowedTools;
        if ($('#permissionAdvanced').is(':visible')) {
            // 高级模式：从文本框解析
            disallowedTools = toList($('#permissionDisallowedTools').val());
        } else {
            // 可视化模式：从复选框获取
            disallowedTools = getDisallowedTools();
        }

        var $btn = $('#permissionSaveBtn');
        $btn.prop('disabled', true);
        
        // 后端API需要tools字段，留空表示允许所有
        var bodyObj = {
            tools: ['**'],  // 允许所有工具
            disallowedTools: disallowedTools
        };

        $.ajax({ url: '/web/settings/permission/save', method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json' })
            .done(function (resp) {
                if (resp.code === 200) {
                    showToast('保存成功');
                    loadPermissionSettings();
                } else {
                    showToast('保存失败: ' + (resp.message || '未知错误'), 'error');
                }
            })
            .fail(function () { showToast('网络错误', 'error'); })
            .always(function () { $btn.prop('disabled', false); });
    }

    // 转义HTML
    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    // 转义属性
    function escapeAttr(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    // 初始化事件绑定
    function initEvents() {
        // 保存按钮
        $('#permissionSaveBtn').on('click', savePermissionSettings);

        // 移除搜索过滤功能

        // 工具开关变化
        $('#permissionToolsList').on('change', '.permission-tool-toggle', function () {
            var disallowedTools = getDisallowedTools();
            updatePermissionCount(disallowedTools);
        });

        // 全选按钮
        $('#permissionSelectAllBtn').on('click', function () {
            $('#permissionToolsList .permission-tool-toggle').prop('checked', true);
            updatePermissionCount(getDisallowedTools());
        });

        // 取消全选按钮
        $('#permissionDeselectAllBtn').on('click', function () {
            $('#permissionToolsList .permission-tool-toggle').prop('checked', false);
            updatePermissionCount(getDisallowedTools());
        });

        // 分类全选/取消
        $('#permissionToolsList').on('click', '.permission-category-toggle', function () {
            var category = $(this).data('category');
            var $tools = $(this).closest('.permission-category').find('.permission-tool-toggle');
            var allChecked = $tools.filter(':checked').length === $tools.length;
            $tools.prop('checked', !allChecked);
            updatePermissionCount(getDisallowedTools());
        });

        // 高级模式切换
        $('#permissionAdvancedBtn').on('click', function () {
            var $advanced = $('#permissionAdvanced');
            var $list = $('#permissionToolsList');
            var $toolbar = $('.permission-toolbar-actions');
            
            if ($advanced.is(':visible')) {
                // 切换到可视化模式
                $advanced.hide();
                $list.show();
                $toolbar.find('.btn-secondary').not('#permissionAdvancedBtn').show();
                $(this).text('高级模式');
                // 同步数据：从文本框更新到复选框
                var disallowedTools = toList($('#permissionDisallowedTools').val());
                renderToolsList(disallowedTools);
            } else {
                // 切换到高级模式
                $advanced.show();
                $list.hide();
                $toolbar.find('.btn-secondary').not('#permissionAdvancedBtn').hide();
                $(this).text('简易模式');
                // 同步数据：从复选框更新到文本框
                var disallowedTools = getDisallowedTools();
                $('#permissionDisallowedTools').val(toLines(disallowedTools));
            }
        });
    }

    // 初始化
    initEvents();

    window._settingsPermission = {
        load: loadPermissionSettings
    };
})();

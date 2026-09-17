/**
 * app-settings-permission.js — 工具权限设置模块（可视化工具列表）
 *
 * 依赖：layui.js（jQuery）
 */
(function () {
    'use strict';

    // 工具定义列表 - 按列分组（每次调用动态翻译，跟随语言切换）
    function getToolsColumns() {
        return [
            // 第一列：文件操作
            [
                { id: 'bash', name: 'bash', desc: I18n.t('permission.tool.bash'), risk: 'low' },
                { id: 'read', name: 'read', desc: I18n.t('permission.tool.read'), risk: 'low' },
                { id: 'write', name: 'write', desc: I18n.t('permission.tool.write'), risk: 'low' },
                { id: 'edit', name: 'edit', desc: I18n.t('permission.tool.edit'), risk: 'low' },
                { id: 'grep', name: 'grep', desc: I18n.t('permission.tool.grep'), risk: 'low' },
                { id: 'glob', name: 'glob', desc: I18n.t('permission.tool.glob'), risk: 'low' },
                { id: 'ls', name: 'ls', desc: I18n.t('permission.tool.ls'), risk: 'low' }
            ],
            // 第二列：网络搜索
            [
                { id: 'codesearch', name: 'codesearch', desc: I18n.t('permission.tool.codesearch'), risk: 'low' },
                { id: 'websearch', name: 'websearch', desc: I18n.t('permission.tool.websearch'), risk: 'low' },
                { id: 'webfetch', name: 'webfetch', desc: I18n.t('permission.tool.webfetch'), risk: 'low' }
            ],
            // 第三列：任务管理
            [
                { id: 'code', name: 'code', desc: I18n.t('permission.tool.code'), risk: 'low' },
                { id: 'todo', name: 'todo', desc: I18n.t('permission.tool.todo'), risk: 'low' },
                { id: 'skill', name: 'skill', desc: I18n.t('permission.tool.skill'), risk: 'low' }
            ]
        ];
    }

    // 风险等级显示（每次调用动态翻译）
    function getRiskLabels() {
        return {
            'high': '<span class="permission-risk-high">' + I18n.t('permission.highRisk') + '</span>',
            'medium': '<span class="permission-risk-medium">' + I18n.t('permission.mediumRisk') + '</span>',
            'low': ''
        };
    }

    function showToast(msg, type) {
        if (typeof window.showToast === 'function') { window.showToast(msg, type); return; }
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

    // 渲染工具列表 - 网格布局
    function renderToolsList(disallowedTools) {
        var disallowedMap = {};
        disallowedTools.forEach(function (t) { disallowedMap[t] = true; });

        var $list = $('#permissionToolsList');
        
        var html = '<div class="permission-tools-grid">';
        
        // 渲染三列
        getToolsColumns().forEach(function (column, colIndex) {
            html += '<div class="permission-tools-column">';
            
            column.forEach(function (tool) {
                var isEnabled = !disallowedMap[tool.id];
                html += '<div class="permission-tool-item">';
                html += '<label class="permission-tool-checkbox" title="' + (isEnabled ? I18n.t('permission.disable') : I18n.t('permission.enable')) + '">';
                html += '<input type="checkbox" ' + (isEnabled ? 'checked' : '') + ' data-tool="' + escapeAttr(tool.id) + '" class="permission-tool-toggle"/>';
                html += '<span class="permission-tool-checkmark"></span>';
                html += '</label>';
                html += '<div class="permission-tool-info">';
                html += '<div class="permission-tool-name">' + escapeHtml(tool.name) + getRiskLabels()[tool.risk] + '</div>';
                html += '<div class="permission-tool-desc">' + escapeHtml(tool.desc) + '</div>';
                html += '</div>';
                html += '</div>';
            });
            
            html += '</div>';
        });
        
        html += '</div>';

        if (html === '') {
            html = '<div class="permission-empty-state">' + I18n.t('permission.noMatch') + '</div>';
        }

        $list.html(html);
        updatePermissionCount(disallowedTools);
    }

    // 更新计数（已移除计数显示）
    function updatePermissionCount(disallowedTools) {
        // 计数显示已移除，保留函数避免调用错误
    }

    // 加载权限设置
    function loadPermissionSettings() {
        $.get('/web/settings/permission', function (resp) {
            if (resp.code === 200 && resp.data) {
                var disallowedTools = resp.data.disallowedTools || [];
                renderToolsList(disallowedTools);
                // 已移除高级模式同步
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
    // 返回 jqXHR promise，由调用方统一处理 toast
    function savePermissionSettings() {
        // 从复选框获取禁用的工具列表
        var disallowedTools = getDisallowedTools();

        // 后端API需要tools字段，留空表示允许所有
        var bodyObj = {
            tools: ['**'],  // 允许所有工具
            disallowedTools: disallowedTools
        };

        return $.ajax({ url: '/web/settings/permission/save', method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json' })
            .then(function (resp) {
                if (resp.code !== 200) {
                    return $.Deferred().reject(resp.message || I18n.t('toast.unknownError')).promise();
                }
                loadPermissionSettings();
                return resp;
            });
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
        // 保存按钮由通用 tab 底部统一处理

        // 移除搜索过滤功能

        // 工具开关变化
        $('#permissionToolsList').on('change', '.permission-tool-toggle', function () {
            var disallowedTools = getDisallowedTools();
            updatePermissionCount(disallowedTools);
        });





        // 高级模式已移除
    }

    // 初始化
    initEvents();

    window._settingsPermission = {
        load: loadPermissionSettings,
        save: savePermissionSettings
    };
})();

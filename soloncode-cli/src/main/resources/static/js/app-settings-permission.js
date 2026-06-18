/**
 * app-settings-permission.js — 工具权限设置模块（可视化工具列表）
 *
 * 依赖：layui.js（jQuery）
 */
(function () {
    'use strict';

    // 工具定义列表 - 按列分组
    var TOOLS_COLUMNS = [
        // 第一列：文件操作
        [
            { id: 'bash', name: 'bash', desc: 'Shell 命令执行', risk: 'low' },
            { id: 'read', name: 'read', desc: '读取文件内容', risk: 'low' },
            { id: 'write', name: 'write', desc: '写入文件内容', risk: 'low' },
            { id: 'edit', name: 'edit', desc: '文件编辑', risk: 'low' },
            { id: 'grep', name: 'grep', desc: '递归内容搜索', risk: 'low' },
            { id: 'glob', name: 'glob', desc: '通配符文件搜索', risk: 'low' },
            { id: 'ls', name: 'ls', desc: '列出目录内容', risk: 'low' }
        ],
        // 第二列：网络搜索
        [
            { id: 'codesearch', name: 'codesearch', desc: '网络代码搜索', risk: 'low' },
            { id: 'websearch', name: 'websearch', desc: '网络搜索', risk: 'low' },
            { id: 'webfetch', name: 'webfetch', desc: '网页内容抓取', risk: 'low' }
        ],
        // 第三列：任务管理
        [
            { id: 'code', name: 'code', desc: '编码环境识别与引导', risk: 'low' },
            { id: 'todo', name: 'todo', desc: '任务清单管理', risk: 'low' },
            { id: 'skill', name: 'skill', desc: '专家技能调用', risk: 'low' }
        ]
    ];

    // 分类显示名称
    var CATEGORY_NAMES = {
        'builtin': '工具列表'
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

    // 渲染工具列表 - 网格布局
    function renderToolsList(disallowedTools) {
        var disallowedMap = {};
        disallowedTools.forEach(function (t) { disallowedMap[t] = true; });

        var $list = $('#permissionToolsList');
        
        var html = '<div class="permission-tools-grid">';
        
        // 渲染三列
        TOOLS_COLUMNS.forEach(function (column, colIndex) {
            html += '<div class="permission-tools-column">';
            
            column.forEach(function (tool) {
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
        });
        
        html += '</div>';

        if (html === '') {
            html = '<div class="permission-empty-state">没有找到匹配的工具</div>';
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





        // 高级模式切换
        $('#permissionAdvancedBtn').on('click', function () {
            var $advanced = $('#permissionAdvanced');
            var $list = $('#permissionToolsList');
            var $toolbar = $('.permission-toolbar-actions');
            
            if ($advanced.is(':visible')) {
                // 切换到可视化模式
                $advanced.hide();
                $list.show();
                $(this).text('高级模式');
                // 同步数据：从文本框更新到复选框
                var disallowedTools = toList($('#permissionDisallowedTools').val());
                renderToolsList(disallowedTools);
            } else {
                // 切换到高级模式
                $advanced.show();
                $list.hide();
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

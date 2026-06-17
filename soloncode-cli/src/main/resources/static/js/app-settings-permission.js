/**
 * app-settings-permission.js — 工具权限设置模块（全局白名单 / 黑名单）
 *
 * 依赖：layui.js（jQuery）
 */
(function () {
    'use strict';

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

    function loadPermissionSettings() {
        $.get('/web/settings/permission', function (resp) {
            if (resp.code === 200 && resp.data) {
                $('#permissionTools').val(toLines(resp.data.tools));
                $('#permissionDisallowedTools').val(toLines(resp.data.disallowedTools));
            }
        }).fail(function () { console.error('[Settings] Failed to load permission settings'); });
    }

    $('#permissionSaveBtn').on('click', function () {
        var $btn = $('#permissionSaveBtn');
        var bodyObj = {
            tools: toList($('#permissionTools').val()),
            disallowedTools: toList($('#permissionDisallowedTools').val())
        };

        $btn.prop('disabled', true);
        $.ajax({ url: '/web/settings/permission/save', method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json' })
            .done(function (resp) {
                if (resp.code === 200) {
                    showToast('保存成功');
                    // 后端会对白名单留空兜底为 **，回读以保持显示一致
                    loadPermissionSettings();
                } else {
                    showToast('保存失败: ' + (resp.message || '未知错误'), 'error');
                }
            })
            .fail(function () { showToast('网络错误', 'error'); })
            .always(function () { $btn.prop('disabled', false); });
    });

    window._settingsPermission = {
        load: loadPermissionSettings
    };
})();

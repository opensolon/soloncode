/**
 * app-settings-general.js — 通用设置模块
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

    // 解析带千位分隔符的数字（支持 _ 和 ,）
    function parseNumStr(s) {
        if (!s) return null;
        var n = parseInt(s.replace(/[, _]/g, ''), 10);
        return isNaN(n) ? null : n;
    }

    // 将数字格式化为千位分隔（用下划线，与 placeholder 一致）
    function formatNum(n) {
        if (n == null || n === '') return '';
        return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, '_');
    }

    function loadGeneralSettings() {
        $.get('/web/settings/general', function (resp) {
            if (resp.code === 200 && resp.data) {
                var d = resp.data;
                $('#generalSessionWindowSize').val(d.sessionWindowSize != null ? formatNum(d.sessionWindowSize) : '');
                $('#generalSummaryWindowSize').val(d.summaryWindowSize != null ? formatNum(d.summaryWindowSize) : '');
                $('#generalSummaryWindowToken').val(d.summaryWindowToken != null ? formatNum(d.summaryWindowToken) : '');
                $('#generalSandboxMode').prop('checked', !!d.sandboxMode);
                $('#generalSandboxAllowUserHome').prop('checked', d.sandboxAllowUserHome !== false);
                $('#generalSandboxSystemRestrict').prop('checked', !!d.sandboxSystemRestrict);
                $('#generalApiRetries').val(d.apiRetries != null ? formatNum(d.apiRetries) : '');
                $('#generalMcpRetries').val(d.mcpRetries != null ? formatNum(d.mcpRetries) : '');
                $('#generalModelRetries').val(d.modelRetries != null ? formatNum(d.modelRetries) : '');
                $('#generalMemoryEnabled').prop('checked', d.memoryEnabled !== false);
                $('#generalMemoryIsolation').prop('checked', d.memoryIsolation !== false);
                $('#generalMcpEnabled').prop('checked', d.mcpEnabled !== false);
                $('#generalOpenApiEnabled').prop('checked', d.openApiEnabled !== false);
                $('#generalBashAsyncEnabled').prop('checked', !!d.bashAsyncEnabled);
                $('#generalSubagentEnabled').prop('checked', d.subagentEnabled !== false);
                $('#generalLspEnabled').prop('checked', !!d.lspEnabled);
                $('#generalCliPrintSimplified').prop('checked', d.cliPrintSimplified !== false);
                window.cliPrintSimplified = d.cliPrintSimplified !== false;
            }
        }).fail(function () { console.error('[Settings] Failed to load general settings'); });
    }

    $('#generalSaveBtn').on('click', function () {
        var $generalSaveBtn = $('#generalSaveBtn');
        var bodyObj = {
            sessionWindowSize: parseNumStr($('#generalSessionWindowSize').val().trim()),
            summaryWindowSize: parseNumStr($('#generalSummaryWindowSize').val().trim()),
            summaryWindowToken: parseNumStr($('#generalSummaryWindowToken').val().trim()),
            sandboxMode: $('#generalSandboxMode').is(':checked'),
            sandboxAllowUserHome: $('#generalSandboxAllowUserHome').is(':checked'),
            sandboxSystemRestrict: $('#generalSandboxSystemRestrict').is(':checked'),
            apiRetries: parseNumStr($('#generalApiRetries').val().trim()),
            mcpRetries: parseNumStr($('#generalMcpRetries').val().trim()),
            modelRetries: parseNumStr($('#generalModelRetries').val().trim()),
            memoryEnabled: $('#generalMemoryEnabled').is(':checked'),
            memoryIsolation: $('#generalMemoryIsolation').is(':checked'),
            mcpEnabled: $('#generalMcpEnabled').is(':checked'),
            openApiEnabled: $('#generalOpenApiEnabled').is(':checked'),
            bashAsyncEnabled: $('#generalBashAsyncEnabled').is(':checked'),
            subagentEnabled: $('#generalSubagentEnabled').is(':checked'),
            lspEnabled: $('#generalLspEnabled').is(':checked'),
            cliPrintSimplified: $('#generalCliPrintSimplified').is(':checked')
        };

        $generalSaveBtn.prop('disabled', true);
        $.ajax({ url: '/web/settings/general/save', method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json' })
            .done(function (resp) {
                if (resp.code === 200) {
                    window.cliPrintSimplified = bodyObj.cliPrintSimplified;
                    showToast('保存成功');
                } else showToast('保存失败: ' + (resp.message || '未知错误'), 'error');
            })
            .fail(function () { showToast('网络错误', 'error'); })
            .always(function () { $generalSaveBtn.prop('disabled', false); });
    });

    window._settingsGeneral = {
        load: loadGeneralSettings
    };
})();

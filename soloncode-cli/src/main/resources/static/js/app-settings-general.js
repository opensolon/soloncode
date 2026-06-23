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

    // 解析带千位分隔符的数字（支持 _ 和 , 以及 k/m 后缀）
    function parseNumStr(s) {
        if (!s) return null;
        var raw = s.trim().replace(/[, _]/g, '');
        var matchK = raw.match(/^(\d+\.?\d*)k$/i);
        var matchM = raw.match(/^(\d+\.?\d*)m$/i);
        var n;
        if (matchK) {
            n = Math.round(parseFloat(matchK[1]) * 1000);
        } else if (matchM) {
            n = Math.round(parseFloat(matchM[1]) * 1000000);
        } else {
            n = parseInt(raw, 10);
        }
        return isNaN(n) ? null : n;
    }

    // 将数字格式化为千位分隔（用下划线，与 placeholder 一致），大于等于 1000 优先显示 xk 格式
    function formatNum(n) {
        if (n == null || n === '') return '';
        if (n >= 1000000 && n % 1000000 === 0) {
            return (n / 1000000) + 'm';
        } else if (n >= 1000) {
            return n % 1000 === 0 ? (n / 1000) + 'k' : (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k';
        }
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

                // Web 访问认证
                $('#generalWebAuthUser').val(d.webAuthUser || '');
                $('#generalWebAuthPass').val(d.webAuthPass || '');
            }
        }).fail(function () { console.error('[Settings] Failed to load general settings'); });

        // 加载 Loop Goal 配置
        $.get('/web/settings/loop', function (resp) {
            if (resp.code === 200 && resp.data) {
                var d = resp.data;
                $('#generalLoopDefaultMaxTokens').val(d.defaultMaxTokens != null && d.defaultMaxTokens > 0 ? formatNum(d.defaultMaxTokens) : '');
                $('#generalLoopDefaultMaxDuration').val(d.defaultMaxDurationMinutes != null && d.defaultMaxDurationMinutes > 0 ? d.defaultMaxDurationMinutes : '');
                $('#generalLoopStagnationThreshold').val(d.stagnationThreshold != null ? d.stagnationThreshold : '');
                $('#generalLoopMaxConsecutiveErrors').val(d.maxConsecutiveErrors != null ? d.maxConsecutiveErrors : '');
                $('#generalLoopPauseAutoAbandonHours').val(d.pauseAutoAbandonHours != null ? d.pauseAutoAbandonHours : '');
                $('#generalLoopBudgetWarningPercent').val(d.budgetWarningPercent != null ? d.budgetWarningPercent : '');
                $('#generalLoopBudgetCriticalPercent').val(d.budgetCriticalPercent != null ? d.budgetCriticalPercent : '');
                $('#generalLoopValidatorEnabled').prop('checked', d.validatorEnabled !== false);
            }
        }).fail(function () { console.error('[Settings] Failed to load loop settings'); });
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
            cliPrintSimplified: $('#generalCliPrintSimplified').is(':checked'),
            webAuthUser: $('#generalWebAuthUser').val().trim() || null,
            webAuthPass: $('#generalWebAuthPass').val().trim() || null
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

        // 同步保存 Loop Goal 配置
        var loopObj = {
            defaultMaxTokens: parseNumStr($('#generalLoopDefaultMaxTokens').val().trim()) || 0,
            defaultMaxDurationMinutes: parseNumStr($('#generalLoopDefaultMaxDuration').val().trim()) || 0,
            stagnationThreshold: parseNumStr($('#generalLoopStagnationThreshold').val().trim()),
            maxConsecutiveErrors: parseNumStr($('#generalLoopMaxConsecutiveErrors').val().trim()),
            pauseAutoAbandonHours: parseNumStr($('#generalLoopPauseAutoAbandonHours').val().trim()),
            budgetWarningPercent: parseNumStr($('#generalLoopBudgetWarningPercent').val().trim()),
            budgetCriticalPercent: parseNumStr($('#generalLoopBudgetCriticalPercent').val().trim()),
            validatorEnabled: $('#generalLoopValidatorEnabled').is(':checked')
        };
        $.ajax({ url: '/web/settings/loop/save', method: 'POST', data: JSON.stringify(loopObj), contentType: 'application/json', dataType: 'json' })
            .fail(function () { console.error('[Settings] Failed to save loop settings'); });
    });

    window._settingsGeneral = {
        load: loadGeneralSettings
    };
})();

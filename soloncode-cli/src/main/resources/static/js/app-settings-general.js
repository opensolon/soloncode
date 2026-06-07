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

    function loadGeneralSettings() {
        $.get('/web/settings/general', function (resp) {
            if (resp.code === 200 && resp.data) {
                var d = resp.data;
                $('#generalSessionWindowSize').val(d.sessionWindowSize != null ? d.sessionWindowSize : '');
                $('#generalSummaryWindowSize').val(d.summaryWindowSize || '');
                $('#generalSummaryWindowToken').val(d.summaryWindowToken || '');
                $('#generalSandboxMode').prop('checked', !!d.sandboxMode);
                $('#generalSandboxAllowUserHome').prop('checked', d.sandboxAllowUserHome !== false);
                $('#generalApiRetries').val(d.apiRetries != null ? d.apiRetries : '');
                $('#generalMcpRetries').val(d.mcpRetries != null ? d.mcpRetries : '');
                $('#generalModelRetries').val(d.modelRetries != null ? d.modelRetries : '');
                $('#generalMemoryEnabled').prop('checked', d.memoryEnabled !== false);
                $('#generalMemoryIsolation').prop('checked', d.memoryIsolation !== false);
                $('#generalMcpEnabled').prop('checked', d.mcpEnabled !== false);
                $('#generalOpenApiEnabled').prop('checked', d.openApiEnabled !== false);
                $('#generalBashAsyncEnabled').prop('checked', !!d.bashAsyncEnabled);
                $('#generalLspEnabled').prop('checked', !!d.lspEnabled);
            }
        }).fail(function () { console.error('[Settings] Failed to load general settings'); });
    }

    $('#generalSaveBtn').on('click', function () {
        var $generalSaveBtn = $('#generalSaveBtn');
        var bodyObj = {
            sessionWindowSize: $('#generalSessionWindowSize').val().trim() ? parseInt($('#generalSessionWindowSize').val().trim(), 10) : null,
            summaryWindowSize: $('#generalSummaryWindowSize').val().trim() ? parseInt($('#generalSummaryWindowSize').val().trim(), 10) : null,
            summaryWindowToken: $('#generalSummaryWindowToken').val().trim() ? parseInt($('#generalSummaryWindowToken').val().trim(), 10) : null,
            sandboxMode: $('#generalSandboxMode').is(':checked'),
            sandboxAllowUserHome: $('#generalSandboxAllowUserHome').is(':checked'),
            apiRetries: $('#generalApiRetries').val().trim() ? parseInt($('#generalApiRetries').val().trim(), 10) : null,
            mcpRetries: $('#generalMcpRetries').val().trim() ? parseInt($('#generalMcpRetries').val().trim(), 10) : null,
            modelRetries: $('#generalModelRetries').val().trim() ? parseInt($('#generalModelRetries').val().trim(), 10) : null,
            memoryEnabled: $('#generalMemoryEnabled').is(':checked'),
            memoryIsolation: $('#generalMemoryIsolation').is(':checked'),
            mcpEnabled: $('#generalMcpEnabled').is(':checked'),
            openApiEnabled: $('#generalOpenApiEnabled').is(':checked'),
            bashAsyncEnabled: $('#generalBashAsyncEnabled').is(':checked'),
            lspEnabled: $('#generalLspEnabled').is(':checked')
        };

        $generalSaveBtn.prop('disabled', true);
        $.ajax({ url: '/web/settings/general/save', method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json' })
            .done(function (resp) {
                if (resp.code === 200) showToast('保存成功');
                else showToast('保存失败: ' + (resp.message || '未知错误'), 'error');
            })
            .fail(function () { showToast('网络错误', 'error'); })
            .always(function () { $generalSaveBtn.prop('disabled', false); });
    });

    window._settingsGeneral = {
        load: loadGeneralSettings
    };
})();

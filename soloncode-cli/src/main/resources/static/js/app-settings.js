/**
 * app-settings.js — 设置面板核心逻辑（面板开关、Tab 管理、公共工具）
 *
 * 各设置页逻辑拆分在 app-settings-*.js 中。
 */
(function () {
    'use strict';

    function escapeHtml(str) {
        if (!str) return '';
        return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function escapeAttr(str) {
        if (!str) return '';
        return String(str).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/'/g, '&#39;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    function parseKvLines(text) {
        var obj = {};
        (text || '').split('\n').forEach(function (line) {
            var idx = line.indexOf('=');
            if (idx > 0) obj[line.substring(0, idx).trim()] = line.substring(idx + 1).trim();
        });
        return obj;
    }

    function showToast(msg, type) {
        if (typeof layer !== 'undefined' && layer.msg) {
            layer.msg(msg, { icon: type === 'error' ? 2 : 1, time: 2500, offset: '120px' });
        } else {
            alert(msg);
        }
    }

    function postJson(url, data, doneFn, alwaysFn) {
        return $.ajax({ url: url, method: 'POST', data: JSON.stringify(data), contentType: 'application/json', dataType: 'json' })
            .done(function (resp) { doneFn(resp); })
            .fail(function () { showToast('网络错误', 'error'); })
            .always(function () { if (alwaysFn) alwaysFn(); });
    }

    function checkWithLoading(opts) {
        var $btn = opts.$btn;
        var btnOriginal = $btn.html();
        var loadingSvg = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="animation:spin 1s linear infinite"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg> ' + opts.loadingText;
        var okSvg = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg> ';
        var failSvg = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg> ';

        $btn.prop('disabled', true).html(loadingSvg);
        opts.$result.hide();

        $.ajax($.extend({ timeout: 15000, dataType: 'json' }, opts.ajax))
            .done(function (resp) {
                var ok = resp.code === 200;
                if (opts.isList && ok) ok = resp.data && resp.data.length > 0;
                opts.$result.attr('class', opts.cls + ' ' + (ok ? 'success' : 'error'))
                    .html((ok ? okSvg : failSvg) + escapeHtml(ok ? (opts.successMsg || '连接成功') : (resp.message || opts.failMsg || '连接失败')))
                    .css('display', 'flex');
            })
            .fail(function (jqXHR, textStatus) {
                var msg = textStatus === 'timeout' ? '连接超时（15秒），请检查地址是否正确' : '网络错误，请重试';
                opts.$result.attr('class', opts.cls + ' error').html(msg).css('display', 'flex');
            })
            .always(function () { $btn.prop('disabled', false).html(btnOriginal); });
    }

    function getScopeControl(scopeInput) {
        return $('.settings-scope-toggle[data-target="' + scopeInput + '"]');
    }

    function setScopeValue(scopeInput, value) {
        value = value || 'user';
        $('#' + scopeInput).val(value);
        var $toggle = getScopeControl(scopeInput);
        $toggle.find('.settings-scope-btn').removeClass('active');
        $toggle.find('.settings-scope-btn[data-scope="' + value + '"]').addClass('active');
    }

    function setScopeReadonly(scopeInput, readonly) {
        var $toggle = getScopeControl(scopeInput);
        $toggle.toggleClass('readonly-gray disabled', !!readonly);
        $toggle.find('.settings-scope-btn').toggleClass('disabled', !!readonly).prop('disabled', !!readonly);
    }

    window._settingsCore = {
        escapeHtml: escapeHtml,
        escapeAttr: escapeAttr,
        parseKvLines: parseKvLines,
        postJson: postJson,
        showToast: showToast,
        checkWithLoading: checkWithLoading,
        setScopeValue: setScopeValue,
        setScopeReadonly: setScopeReadonly
    };

    var $overlay = $('#settingsOverlay');
    var $settingsBtn = $('#settingsBtn');
    var $tabs = $('.settings-tab');
    var $tabContents = $('.settings-tab-content');

    function mod(name) { return window[name]; }

    function resetCurrentTabView(targetTab) {
        if (targetTab === 'llm' && mod('_settingsLlm')) { mod('_settingsLlm').showList(); mod('_settingsLlm').reset(); }
        else if (targetTab === 'mounts' && mod('_settingsMounts')) { mod('_settingsMounts').showList(); mod('_settingsMounts').reset(); }
        else if (targetTab === 'mcp' && mod('_settingsMcp')) { mod('_settingsMcp').showList(); mod('_settingsMcp').reset(); $('#mcpToolsToolbar').hide(); }
        else if (targetTab === 'openapi' && mod('_settingsOpenapi')) { mod('_settingsOpenapi').showList(); mod('_settingsOpenapi').reset(); }
        else if (targetTab === 'lsp' && mod('_settingsLsp')) { mod('_settingsLsp').showList(); mod('_settingsLsp').reset(); }
        else if (targetTab === 'providers' && window.settingsProviders) { window.settingsProviders.showList(); }
    }

    function openSettings() {
        $overlay.css('display', 'flex');
        loadActiveTabData();
    }

    function closeSettings() {
        $overlay.hide();
        if (mod('_settingsLlm')) mod('_settingsLlm').showList();
        if (mod('_settingsMcp')) mod('_settingsMcp').showList();
        if (mod('_settingsOpenapi')) mod('_settingsOpenapi').showList();
        if (mod('_settingsLsp')) mod('_settingsLsp').showList();
        if (mod('_settingsMounts')) mod('_settingsMounts').showList();
        if (window.settingsProviders) window.settingsProviders.showList();
        $('#llmCheckResult').hide();
        $('#llmFormActions, #mcpFormActions, #openapiFormActions, #lspFormActions, #providerFormActions').hide();
        if ($('#skillsSearchInput').length) {
            $('#skillsSearchInput').val('');
            $('#skillsSearchClear').hide();
        }
    }

    $settingsBtn.on('click', openSettings);
    $overlay.on('click', function (e) { if (e.target === $overlay[0]) closeSettings(); });
    $(document).on('keydown', function (e) { if (e.key === 'Escape' && $overlay.is(':visible')) closeSettings(); });

    $(document).on('click', '.settings-scope-btn', function () {
        var $btn = $(this);
        if ($btn.prop('disabled') || $btn.hasClass('disabled')) return;
        var $toggle = $btn.closest('.settings-scope-toggle');
        var target = $toggle.attr('data-target');
        if (!target) return;
        setScopeValue(target, $btn.attr('data-scope') || 'user');
    });

    $('.settings-tabs').on('click', '.settings-tab', function () {
        var $tab = $(this);
        $tabs.removeClass('active');
        $tabContents.removeClass('active');
        $tab.addClass('active');

        var targetTab = $tab.attr('data-tab');
        resetCurrentTabView(targetTab);
        if (targetTab === 'general') {
            $('#settingsTabGeneral').addClass('active');
            if (mod('_settingsGeneral')) mod('_settingsGeneral').load();
        } else if (targetTab === 'permission') {
            $('#settingsTabPermission').addClass('active');
            if (mod('_settingsPermission')) mod('_settingsPermission').load();
        } else if (targetTab === 'llm') {
            $('#settingsTabLlm').addClass('active');
            if (mod('_settingsLlm')) mod('_settingsLlm').load();
        } else if (targetTab === 'skills') {
            $('#settingsTabSkills').addClass('active');
            if (window._skillModule) window._skillModule.resetAndLoad();
        } else if (targetTab === 'mounts') {
            $('#settingsTabMounts').addClass('active');
            if (mod('_settingsMounts')) mod('_settingsMounts').load();
        } else if (targetTab === 'mcp') {
            $('#settingsTabMcp').addClass('active');
            if (mod('_settingsMcp')) mod('_settingsMcp').load();
        } else if (targetTab === 'openapi') {
            $('#settingsTabOpenapi').addClass('active');
            if (mod('_settingsOpenapi')) mod('_settingsOpenapi').load();
        } else if (targetTab === 'lsp') {
            $('#settingsTabLsp').addClass('active');
            if (mod('_settingsLsp')) mod('_settingsLsp').load();
        } else if (targetTab === 'providers') {
            $('#settingsTabProviders').addClass('active');
            if (window.settingsProviders) window.settingsProviders.loadList();
        }
    });

    function loadActiveTabData() {
        var $active = $('.settings-tab.active');
        if (!$active.length) return;
        var targetTab = $active.attr('data-tab');
        if (targetTab === 'general') { if (mod('_settingsGeneral')) mod('_settingsGeneral').load(); }
        else if (targetTab === 'permission') { if (mod('_settingsPermission')) mod('_settingsPermission').load(); }
        else if (targetTab === 'llm') { if (mod('_settingsLlm')) mod('_settingsLlm').load(); }
        else if (targetTab === 'skills') { if (window._skillModule) window._skillModule.resetAndLoad(); }
        else if (targetTab === 'mounts') { if (mod('_settingsMounts')) mod('_settingsMounts').load(); }
        else if (targetTab === 'mcp') { if (mod('_settingsMcp')) mod('_settingsMcp').load(); }
        else if (targetTab === 'openapi') { if (mod('_settingsOpenapi')) mod('_settingsOpenapi').load(); }
        else if (targetTab === 'lsp') { if (mod('_settingsLsp')) mod('_settingsLsp').load(); }
        else if (targetTab === 'providers') { if (window.settingsProviders) window.settingsProviders.loadList(); }
    }

    // 全局打开设置 tab 入口（供 app-filer.js 调用）
    window.openSettingsTab = function(tab) {
        $overlay.css('display', 'flex');
        $('.settings-tab[data-tab="' + tab + '"]').click();
    };
})();

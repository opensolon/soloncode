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
        // 统一委托全局 showToast（app-base.js，标准 = layer.msg）
        if (typeof window.showToast === 'function') { window.showToast(msg, type); return; }
        if (typeof layer !== 'undefined' && layer.msg) {
            layer.msg(msg, { icon: type === 'error' ? 2 : 1, time: 2500, offset: '120px' });
        } else {
            alert(msg);
        }
    }

    function postJson(url, data, doneFn, alwaysFn) {
        return $.ajax({ url: url, method: 'POST', data: JSON.stringify(data), contentType: 'application/json', dataType: 'json' })
            .done(function (resp) { doneFn(resp); })
            .fail(function () { showToast(I18n.t('toast.networkError'), 'error'); })
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
                    .html((ok ? okSvg : failSvg) + escapeHtml(ok ? (opts.successMsg || I18n.t('settings.checkOk')) : (resp.message || opts.failMsg || I18n.t('settings.checkFail'))))
                    .css('display', 'flex');
            })
            .fail(function (jqXHR, textStatus) {
                var msg = textStatus === 'timeout' ? I18n.t('settings.checkTimeout') : I18n.t('toast.networkErrorRetry');
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

    // 「添加」按钮跳进来时要比落的子视图（技能市场），只对紧接着的那次打开生效
    var pendingSkillView = null;
    function takeSkillView() {
        var view = pendingSkillView;
        pendingSkillView = null;
        return view;
    }

    // 「大语言模型」页内的两个子视图：模型 / 供应商（供应商原本是独立 Tab）
    var currentLlmView = 'models';
    // 「供应商」深链进来时要落的子视图，只对紧接着的那次打开生效
    var pendingLlmView = null;
    function takeLlmView() {
        var view = pendingLlmView;
        pendingLlmView = null;
        // 无深链时沿用当前子视图：面板内来回切 tab 不会把用户甩回「模型」，
        // 重新打开面板则是 models（closeSettings 会复位）
        return view || currentLlmView;
    }

    /**
     * 切换「大语言模型」页内的子视图。
     * skipLoad=true 只做显示切换（进页面/关闭面板复位时用），避免和随后的 load 重复请求。
     */
    function switchLlmView(view, skipLoad) {
        currentLlmView = view === 'providers' ? 'providers' : 'models';
        $('#llmViewToggle .mcp-type-btn').removeClass('active')
            .filter('[data-view="' + currentLlmView + '"]').addClass('active');
        if (currentLlmView === 'providers') {
            $('#llmViewModels').hide();
            $('#llmViewProviders').show();
            if (skipLoad) return;
            // 切进去先回列表页：上次可能停留在编辑表单，不归位会看起来像「供应商列表空页」
            if (window.settingsProviders) window.settingsProviders.showList();
        } else {
            $('#llmViewProviders').hide();
            $('#llmViewModels').show();
            if (skipLoad) return;
            if (mod('_settingsLlm')) { mod('_settingsLlm').showList(); mod('_settingsLlm').load(); }
        }
    }

    function resetCurrentTabView(targetTab) {
        if (targetTab === 'llm' || targetTab === 'providers') {
            if (currentLlmView === 'providers') {
                if (window.settingsProviders) window.settingsProviders.showList();
            } else if (mod('_settingsLlm')) {
                mod('_settingsLlm').showList();
                mod('_settingsLlm').reset();
            }
        }
        else if (targetTab === 'agents' && mod('_settingsAgents')) { mod('_settingsAgents').showList(); mod('_settingsAgents').reset(); }
        else if (targetTab === 'mounts' && mod('_settingsMounts')) { mod('_settingsMounts').showList(); mod('_settingsMounts').reset(); }
        else if (targetTab === 'mcp' && mod('_settingsMcp')) { mod('_settingsMcp').showList(); mod('_settingsMcp').reset(); $('#mcpToolsToolbar').hide(); }
        else if (targetTab === 'openapi' && mod('_settingsOpenapi')) { mod('_settingsOpenapi').showList(); mod('_settingsOpenapi').reset(); }
        else if (targetTab === 'lsp' && mod('_settingsLsp')) { mod('_settingsLsp').showList(); mod('_settingsLsp').reset(); }
    }

    function openSettings() {
        $overlay.css('display', 'flex');
        loadActiveTabData();
    }

    function closeSettings() {
        $overlay.hide();
        // 字体是即时预览的，未点保存就关闭要回滚到已持久化的值
        if (mod('_settingsGeneral') && typeof mod('_settingsGeneral').revertFont === 'function') {
            mod('_settingsGeneral').revertFont();
        }
        // 子视图复位：下次打开默认落「模型」列表，不沿用上次停留的子视图/表单
        switchLlmView('models', true);
        if (mod('_settingsLlm')) mod('_settingsLlm').showList();
        if (mod('_settingsMcp')) mod('_settingsMcp').showList();
        if (mod('_settingsOpenapi')) mod('_settingsOpenapi').showList();
        if (mod('_settingsLsp')) mod('_settingsLsp').showList();
        if (mod('_settingsAgents')) mod('_settingsAgents').showList();
        if (mod('_settingsMounts')) mod('_settingsMounts').showList();
        if (window.settingsProviders) window.settingsProviders.showList();
        $('#llmCheckResult').hide();
        $('#llmFormActions, #mcpFormActions, #openapiFormActions, #lspFormActions, #providerFormActions').hide();
        if ($('#skillsSearchInput').length) {
            $('#skillsSearchInput').val('');
            $('#skillsSearchClear').hide();
        }
        // 设置里可能刚装完技能 / 新建了连接器，回到会话要能看到：选项是缓存的一次性快照，必须重拉
        if (typeof window.reloadCapabilities === 'function') window.reloadCapabilities();
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
            if (mod('_settingsPermission')) mod('_settingsPermission').load();

        } else if (targetTab === 'appearance') {
            $('#settingsTabAppearance').addClass('active');
            // 外观页的表单（字体/缩放/简化展示）与通用页同一份存储，走同一套 load/save
            if (mod('_settingsGeneral')) mod('_settingsGeneral').load();
            if (mod('_settingsSkin')) mod('_settingsSkin').load();
        } else if (targetTab === 'agents') {
            $('#settingsTabAgents').addClass('active');
            if (mod('_settingsAgents')) mod('_settingsAgents').load();
        } else if (targetTab === 'llm' || targetTab === 'providers') {
            // providers 是合并前的老 tab 名，只作为深链别名
            $('#settingsTabLlm').addClass('active');
            var llmView = targetTab === 'providers' ? 'providers' : takeLlmView();
            switchLlmView(llmView, true);
            if (llmView === 'providers') {
                if (window.settingsProviders) window.settingsProviders.loadList();
            } else if (mod('_settingsLlm')) {
                mod('_settingsLlm').load();
            }
        } else if (targetTab === 'skills') {
            $('#settingsTabSkills').addClass('active');
            if (window._skillModule) window._skillModule.resetAndLoad(takeSkillView());
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
        }
    });

    // 「模型 / 供应商」子视图切换
    $(document).on('click', '#llmViewToggle .mcp-type-btn', function () {
        var view = $(this).attr('data-view');
        if (view === currentLlmView) return;
        switchLlmView(view);
    });

    function loadActiveTabData() {
        var $active = $('.settings-tab.active');
        if (!$active.length) return;
        var targetTab = $active.attr('data-tab');
        if (targetTab === 'general') {
            if (mod('_settingsGeneral')) mod('_settingsGeneral').load();
            if (mod('_settingsPermission')) mod('_settingsPermission').load();
        }
        else if (targetTab === 'appearance') {
            if (mod('_settingsGeneral')) mod('_settingsGeneral').load();
            if (mod('_settingsSkin')) mod('_settingsSkin').load();
        }
        else if (targetTab === 'agents') { if (mod('_settingsAgents')) mod('_settingsAgents').load(); }
        else if (targetTab === 'llm' || targetTab === 'providers') {
            if (currentLlmView === 'providers') { if (window.settingsProviders) window.settingsProviders.loadList(); }
            else if (mod('_settingsLlm')) mod('_settingsLlm').load();
        }
        else if (targetTab === 'skills') { if (window._skillModule) window._skillModule.resetAndLoad(takeSkillView()); }
        else if (targetTab === 'mounts') { if (mod('_settingsMounts')) mod('_settingsMounts').load(); }
        else if (targetTab === 'mcp') { if (mod('_settingsMcp')) mod('_settingsMcp').load(); }
        else if (targetTab === 'openapi') { if (mod('_settingsOpenapi')) mod('_settingsOpenapi').load(); }
        else if (targetTab === 'lsp') { if (mod('_settingsLsp')) mod('_settingsLsp').load(); }
    }

    /**
     * 从磁盘重新加载 settings.json 到当前实例（多实例共享全局配置时使用）
     */
    function reloadSettingsFromDisk() {
        window.settingsReloadFromDisk = reloadSettingsFromDisk;
        var $btn = $('#settingsReloadBtn');
        if ($btn.prop('disabled')) return;

        $btn.prop('disabled', true).addClass('is-loading');

        $.ajax({
            url: '/web/settings/reload',
            method: 'POST',
            dataType: 'json',
            timeout: 60000
        })
            .done(function (resp) {
                if (!resp || resp.code !== 200) {
                    showToast((resp && resp.message) || I18n.t('settings.reloadFailed'), 'error');
                    return;
                }

                var data = resp.data || {};
                if (!data.reloaded) {
                    showToast(I18n.t('settings.reloadNoChange'));
                } else {
                    var warnings = data.warnings || [];
                    if (warnings.length) {
                        showToast(I18n.t('settings.reloadDoneWarn'));
                    } else {
                        showToast(I18n.t('settings.reloadDone'));
                    }
                }

                // 刷新当前设置 Tab + 聊天区模型列表
                var $active = $('.settings-tab.active');
                if ($active.length) {
                    resetCurrentTabView($active.attr('data-tab'));
                }
                loadActiveTabData();
                if (typeof window.reloadModels === 'function') {
                    window.reloadModels();
                }
            })
            .fail(function (jqXHR, textStatus) {
                var msg = textStatus === 'timeout' ? I18n.t('settings.reloadTimeout') : I18n.t('toast.networkError');
                showToast(msg, 'error');
            })
            .always(function () {
                $btn.prop('disabled', false).removeClass('is-loading');
            });
    }

    $('#settingsReloadBtn').on('click', function () {
        if (typeof layer !== 'undefined' && layer.confirm) {
            layer.confirm(I18n.t('settings.reloadConfirm'), {
                title: I18n.t('settings.reloadTitle'),
                btn: [I18n.t('settings.reloadBtn'), I18n.t('common.cancel')],
                icon: 3,
                offset: '120px'
            }, function (index) {
                layer.close(index);
                reloadSettingsFromDisk();
            });
            return;
        }
        // layer 不可用时的降级
        if (window.confirm(I18n.t('settings.reloadConfirm').replace(/<br>/g, '\n'))) {
            reloadSettingsFromDisk();
        }
    });

    // 全局打开设置 tab 入口（供 app-filer.js / 选择器「添加」按钮调用）
    // opts.view：技能 tab 的子视图（'market' 直接落到技能市场）
    // 旧入口兼容：skin → 外观（外观已独立成 tab）、providers → 大语言模型 + 供应商子视图、lsp → 通用
    window.openSettingsTab = function(tab, opts) {
        if (tab === 'skin') {
            tab = 'appearance';
        } else if (tab === 'providers') {
            tab = 'llm';
            pendingLlmView = 'providers';
        } else if (tab === 'lsp') {
            // LSP tab 已在侧边栏隐藏，深链降到通用页，避免点了没反应
            tab = 'general';
        }
        if (tab !== 'llm') pendingLlmView = null;
        pendingSkillView = (opts && opts.view) || null;
        $overlay.css('display', 'flex');
        var $tab = $('.settings-tab[data-tab="' + tab + '"]');
        if ($tab.length) {
            $tab.click();
        } else {
            // tab 不存在时不能把视图提示留到下一个人，否则眼镜按钮进去也会跑到市场
            pendingSkillView = null;
        }
    };
})();
/**
 * app-settings-general.js — 通用设置模块
 *
 * 依赖：layui.js（jQuery）
 */
(function () {
    'use strict';

    function showToast(msg, type) {
        if (typeof window.showToast === 'function') { window.showToast(msg, type); return; }
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

    /* ===== 字体设置 ===== */

    // 预设字族。分两组：界面字体（sans）与代码字体（mono）。
    // 顺序按“命中率 + 常用度”排：本机装了的排前面用户更容易看到效果。
    var FONT_PRESETS = {
        sans: [
            'PingFang SC', 'Microsoft YaHei', 'Noto Sans SC', 'Source Han Sans SC',
            'HarmonyOS Sans SC', 'Hiragino Sans GB', 'Segoe UI', 'Helvetica Neue',
            'Inter', 'Roboto', 'system-ui'
        ],
        mono: [
            'JetBrains Mono', 'Fira Code', 'Cascadia Code', 'Menlo', 'Consolas',
            'Source Code Pro', 'IBM Plex Mono', 'Sarasa Mono SC', 'Maple Mono', 'ui-monospace'
        ]
    };

    // 字体是否已安装：document.fonts.check 对系统字体判定不完全可靠，
    // 拿不到结论时按“已安装”处理（宁可不标记，也不误标为缺失）。
    function fontInstalled(name) {
        if (!document.fonts || typeof document.fonts.check !== 'function') return true;
        try {
            return document.fonts.check('12px "' + name + '"');
        } catch (e) {
            return true;
        }
    }

    // 字族输入框为自研 combobox（input + 下拉面板，结构同 .locale-selector）：预设可选、也可自行输入。
    // 空值 = 用 theme.css 的默认字体栈（由 placeholder 与下拉首项提示）。
    function setFontFamilyInput($el, value) {
        if (!$el.length) return;
        $el.val(value || '');
        markFontFamilyValid($el);
    }

    /* ---- 字体下拉面板 ---- */

    function fontSelectorOf($el) {
        return $el.closest('.font-selector');
    }

    // 渲染下拉项。keyword 非空时按子串过滤（大小写不敏感）。
    function renderFontDropdown($sel, keyword) {
        var $dropdown = $sel.find('.font-selector-dropdown');
        if (!$dropdown.length) return;
        var type = $sel.data('font-type') === 'mono' ? 'mono' : 'sans';
        var current = ($sel.find('.font-selector-input').val() || '').trim();
        var kw = (keyword || '').trim().toLowerCase();
        var list = FONT_PRESETS[type].filter(function (name) {
            return !kw || name.toLowerCase().indexOf(kw) >= 0;
        });

        $dropdown.empty();

        // 首项：清空 = 回到默认字体栈（combobox 没有空 option 可用，这里补上入口）
        var defaultLabel = window.I18n ? window.I18n.t('general.ui.fontPlaceholder') : '系统默认';
        $dropdown.append(
            $('<div>').addClass('font-selector-item is-default')
                .attr({ role: 'option', 'data-value': '' })
                .toggleClass('active', !current)
                .text(defaultLabel)
        );

        if (!list.length) {
            $dropdown.append(
                $('<div>').addClass('font-selector-empty')
                    .text(window.I18n ? window.I18n.t('general.ui.fontNoMatch') : '无匹配字体')
            );
            return;
        }

        list.forEach(function (name) {
            var $item = $('<div>').addClass('font-selector-item')
                .attr({ role: 'option', 'data-value': name })
                .toggleClass('active', name === current)
                // 用字体自身渲染：装了就能直接看出字形
                .css('font-family', '"' + name + '", var(--font-' + (type === 'mono' ? 'mono' : 'sans') + '-fallback)')
                .text(name);
            if (!fontInstalled(name)) {
                $item.append(
                    $('<span>').addClass('font-selector-item-preview')
                        .css('font-family', 'var(--font-sans)')
                        .text(window.I18n ? window.I18n.t('general.ui.fontNotInstalled') : '未安装')
                );
            }
            $dropdown.append($item);
        });
    }

    function openFontDropdown($sel, keyword) {
        if (!$sel.length) return;
        // 同一时刻只开一个
        $('.font-selector.open').not($sel).each(function () { closeFontDropdown($(this)); });
        renderFontDropdown($sel, keyword);
        $sel.addClass('open');
        $sel.find('.font-selector-input').attr('aria-expanded', 'true');
    }

    function closeFontDropdown($sel) {
        if (!$sel || !$sel.length) return;
        $sel.removeClass('open');
        $sel.find('.font-selector-item').removeClass('hover');
        $sel.find('.font-selector-input').attr('aria-expanded', 'false');
    }

    function closeAllFontDropdowns() {
        $('.font-selector.open').each(function () { closeFontDropdown($(this)); });
    }

    // 键盘高亮项在面板内滚动可见
    function moveFontHover($sel, delta) {
        var $items = $sel.find('.font-selector-item');
        if (!$items.length) return;
        var idx = $items.index($sel.find('.font-selector-item.hover'));
        idx = idx < 0 ? (delta > 0 ? 0 : $items.length - 1) : idx + delta;
        if (idx < 0) idx = $items.length - 1;
        if (idx >= $items.length) idx = 0;
        $items.removeClass('hover');
        var $target = $items.eq(idx).addClass('hover');
        var el = $target[0];
        if (el && el.scrollIntoView) el.scrollIntoView({ block: 'nearest' });
    }

    function commitFontValue($sel, value) {
        var $input = $sel.find('.font-selector-input');
        setFontFamilyInput($input, value);
        closeFontDropdown($sel);
        previewFont();
    }

    // 无效输入给可见反馈：过去是静默变空，用户不知道自己输错了
    function markFontFamilyValid($el) {
        if (!$el.length) return true;
        var ok = typeof window.isValidFontFamily !== 'function'
            || window.isValidFontFamily($el.val());
        $el.toggleClass('input-invalid', !ok);
        return ok;
    }

    function currentFontScale() {
        var v = parseInt($('#generalUiFontScale').val(), 10);
        return isNaN(v) ? 1 : v / 100;
    }

    // 同步读数与滑块已选段填充比（--range-fill 供 settings.css 的轨道渐变使用）
    function updateFontScaleLabel() {
        var $range = $('#generalUiFontScale');
        var pct = Math.round(currentFontScale() * 100);
        $('#generalUiFontScaleValue').text(pct + '%');
        if (!$range.length) return;
        var min = parseInt($range.attr('min'), 10);
        var max = parseInt($range.attr('max'), 10);
        if (isNaN(min)) min = 85;
        if (isNaN(max)) max = 150;
        var ratio = max > min ? (pct - min) / (max - min) : 0;
        ratio = Math.max(0, Math.min(1, ratio));
        $range[0].style.setProperty('--range-fill', (ratio * 100).toFixed(2) + '%');
    }

    // 已持久化的字体基线（服务端值）。预览未保存时用它回滚。
    // app-ui.js 首屏校准也会写 window.savedFontBaseline，作为本面板 GET 未返回时的兜底。
    var savedFont = null;

    function fontBaseline() {
        return savedFont || window.savedFontBaseline || null;
    }

    function setFontBaseline(f) {
        savedFont = f;
        window.savedFontBaseline = f;
        window._fontPreviewDirty = false;
    }

    function readFontForm() {
        return {
            family: ($('#generalUiFontFamily').val() || '').trim(),
            mono: ($('#generalUiFontMono').val() || '').trim(),
            scale: currentFontScale()
        };
    }

    // 即时应用（仅运行时预览，持久化由保存按钮完成）
    // 置 dirty 标记：避免首屏那次异步校准把用户正在看的预览覆盖掉
    function previewFont() {
        if (typeof window.applyFont !== 'function') return;
        window._fontPreviewDirty = true;
        window.applyFont(readFontForm());
    }

    // 放弃未保存的预览，恢复到基线（关闭设置面板时调用）
    function revertFont() {
        var base = fontBaseline();
        if (!base || typeof window.applyFont !== 'function') return;
        window._fontPreviewDirty = false;
        window.applyFont(base);
        setFontFamilyInput($('#generalUiFontFamily'), base.family);
        setFontFamilyInput($('#generalUiFontMono'), base.mono);
        $('#generalUiFontScale').val(Math.round(base.scale * 100));
        updateFontScaleLabel();
    }

    /* ---- 字体 combobox 事件（委托绑定：设置面板是后插入的 DOM）---- */

    // 手打：即时预览 + 按关键字过滤下拉
    $(document).on('input', '.font-selector-input', function () {
        var $input = $(this);
        markFontFamilyValid($input);
        openFontDropdown(fontSelectorOf($input), $input.val());
        previewFont();
    });

    // change 兜住非 input 触发的赋值路径（如浏览器自动填充）
    $(document).on('change', '.font-selector-input', function () {
        markFontFamilyValid($(this));
        previewFont();
    });

    // 点输入框：展开全部预设（不按当前值过滤，方便换字体）
    $(document).on('focus click', '.font-selector-input', function (e) {
        e.stopPropagation();
        var $sel = fontSelectorOf($(this));
        if (!$sel.hasClass('open')) openFontDropdown($sel, '');
    });

    // 点箭头：开合切换
    $(document).on('click', '.font-selector-toggle', function (e) {
        e.stopPropagation();
        e.preventDefault();
        var $sel = fontSelectorOf($(this));
        if ($sel.hasClass('open')) {
            closeFontDropdown($sel);
        } else {
            openFontDropdown($sel, '');
            $sel.find('.font-selector-input').focus();
        }
    });

    // 选中下拉项（首项 data-value="" 即回到默认字体栈）
    $(document).on('click', '.font-selector-item', function (e) {
        e.stopPropagation();
        var $item = $(this);
        commitFontValue(fontSelectorOf($item), $item.attr('data-value') || '');
    });

    // 键盘：上下移动高亮、回车选中、Esc 关闭
    $(document).on('keydown', '.font-selector-input', function (e) {
        var $sel = fontSelectorOf($(this));
        var open = $sel.hasClass('open');
        if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault();
            if (!open) { openFontDropdown($sel, ''); return; }
            moveFontHover($sel, e.key === 'ArrowDown' ? 1 : -1);
        } else if (e.key === 'Enter') {
            var $hover = $sel.find('.font-selector-item.hover');
            if (open && $hover.length) {
                e.preventDefault();
                commitFontValue($sel, $hover.attr('data-value') || '');
            } else {
                closeFontDropdown($sel);
            }
        } else if (e.key === 'Escape') {
            if (open) { e.stopPropagation(); closeFontDropdown($sel); }
        }
    });

    // 点面板外关闭（不吞事件，避免影响设置面板其它交互）
    $(document).on('click', function () { closeAllFontDropdowns(); });
    $(document).on('input change', '#generalUiFontScale', function () {
        updateFontScaleLabel();
        previewFont();
    });
    $(document).on('click', '#generalUiFontReset', function () {
        setFontFamilyInput($('#generalUiFontFamily'), '');
        setFontFamilyInput($('#generalUiFontMono'), '');
        $('#generalUiFontScale').val(100);
        updateFontScaleLabel();
        previewFont();
    });

    function loadGeneralSettings() {
        $.get('/web/settings/general', function (resp) {
            if (resp.code === 200 && resp.data) {
                var d = resp.data;
                $('#generalSessionWindowSize').val(d.sessionWindowSize != null ? formatNum(d.sessionWindowSize) : '');
                $('#generalCompressionMsgs').val((d.compressionThresholdMessages ?? d.summaryWindowSize) != null ? formatNum(d.compressionThresholdMessages ?? d.summaryWindowSize) : '');
                $('#generalCompressionPct').val(d.compressionThresholdPercent != null ? formatNum(d.compressionThresholdPercent) : '');
                $('#generalSandboxMode').prop('checked', !!d.sandboxMode);
                $('#generalSandboxAllowUserHome').prop('checked', d.sandboxAllowUserHome !== false);
                $('#generalSandboxSystemRestrict').prop('checked', !!d.sandboxSystemRestrict);
                $('#generalApiRetries').val(d.apiRetries != null ? formatNum(d.apiRetries) : '');
                $('#generalMcpRetries').val(d.mcpRetries != null ? formatNum(d.mcpRetries) : '');
                $('#generalModelRetries').val(d.modelRetries != null ? formatNum(d.modelRetries) : '');
                $('#generalMemoryEnabled').prop('checked', d.memoryEnabled !== false);
                $('#generalMemoryRelevanceCount').val(d.memoryRelevanceCount != null ? formatNum(d.memoryRelevanceCount) : '');
                $('#generalMemoryPriorityCount').val(d.memoryPriorityCount != null ? formatNum(d.memoryPriorityCount) : '');
                $('#generalMemorySummaryLength').val(d.memorySummaryLength != null ? formatNum(d.memorySummaryLength) : '');
                $('#generalMcpEnabled').prop('checked', d.mcpEnabled !== false);
                $('#generalOpenApiEnabled').prop('checked', d.openApiEnabled !== false);
                $('#generalBashAsyncEnabled').prop('checked', !!d.bashAsyncEnabled);
                $('#generalSubagentEnabled').prop('checked', d.subagentEnabled !== false);
                $('#generalManagerEnabled').prop('checked', d.managerEnabled !== false);
                $('#generalLoopEnabled').prop('checked', d.loopsEnabled !== false);
                $('#generalLspEnabled').prop('checked', !!d.lspEnabled);
                $('#generalCliPrintSimplified').prop('checked', d.cliPrintSimplified !== false);
                window.cliPrintSimplified = d.cliPrintSimplified !== false;

                // Web 访问认证
                $('#generalWebAuthUser').val(d.webAuthUser || '');
                $('#generalWebAuthPass').val(d.webAuthPass || '');

                // 新建对话默认（思考模式 / 推理强度）
                $('#generalDefaultThinkingMode').val(d.defaultThinkingMode || '');
                $('#generalDefaultReasoningEffort').val(d.defaultReasoningEffort || '');

                // 日志
                $('#generalLogLevel').val(d.logLevel || '');
                $('#generalLogFileMaxSize').val(d.logFileMaxSize || '');
                $('#generalLogMaxHistory').val(d.logMaxHistory != null ? d.logMaxHistory : '');

                // HTTP 代理
                $('#generalProxyHost').val(d.proxyHost || '');
                $('#generalProxyPort').val(d.proxyPort != null && d.proxyPort > 0 ? d.proxyPort : '');
                $('#generalNoProxy').val(d.noProxy || '');

                // 字体：基线取服务端值（不经表单，避免与用户正在进行的预览互相污染）
                var serverFont = {
                    family: d.uiFontFamily || '',
                    mono: d.uiFontMono || '',
                    scale: d.uiFontScale != null ? d.uiFontScale : 1
                };
                var dirty = !!window._fontPreviewDirty;
                setFontBaseline(serverFont);
                // 用户已在预览：保留其表单与预览效果，只更新基线；否则按服务端值回填 + 校准
                // （localStorage 可能是上次未保存的预览残留）
                if (dirty) {
                    window._fontPreviewDirty = true;
                } else {
                    setFontFamilyInput($('#generalUiFontFamily'), serverFont.family);
                    setFontFamilyInput($('#generalUiFontMono'), serverFont.mono);
                    $('#generalUiFontScale').val(Math.round(serverFont.scale * 100));
                    updateFontScaleLabel();
                    if (typeof window.applyFont === 'function') window.applyFont(serverFont);
                }
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
                $('#generalLoopBudgetWarningPercent').val(d.budgetWarningPercent != null ? d.budgetWarningPercent : '');
                $('#generalLoopBudgetCriticalPercent').val(d.budgetCriticalPercent != null ? d.budgetCriticalPercent : '');
                $('#generalLoopValidatorEnabled').prop('checked', d.validatorEnabled !== false);
            }
        }).fail(function () { console.error('[Settings] Failed to load loop settings'); });
    }

    // 皮肤切换由 app-settings-skin.js 统一处理（含服务端 activeSkin 持久化）

    $('#generalSaveBtn').on('click', function () {
        var $generalSaveBtn = $('#generalSaveBtn');
        var bodyObj = {
            sessionWindowSize: parseNumStr($('#generalSessionWindowSize').val().trim()),
            compressionThresholdMessages: parseNumStr($('#generalCompressionMsgs').val().trim()),
            compressionThresholdPercent: parseNumStr($('#generalCompressionPct').val().trim()),
            sandboxMode: $('#generalSandboxMode').is(':checked'),
            sandboxAllowUserHome: $('#generalSandboxAllowUserHome').is(':checked'),
            sandboxSystemRestrict: $('#generalSandboxSystemRestrict').is(':checked'),
            apiRetries: parseNumStr($('#generalApiRetries').val().trim()),
            mcpRetries: parseNumStr($('#generalMcpRetries').val().trim()),
            modelRetries: parseNumStr($('#generalModelRetries').val().trim()),
            memoryEnabled: $('#generalMemoryEnabled').is(':checked'),
            memoryRelevanceCount: parseNumStr($('#generalMemoryRelevanceCount').val().trim()),
            memoryPriorityCount: parseNumStr($('#generalMemoryPriorityCount').val().trim()),
            memorySummaryLength: parseNumStr($('#generalMemorySummaryLength').val().trim()),
            mcpEnabled: $('#generalMcpEnabled').is(':checked'),
            openApiEnabled: $('#generalOpenApiEnabled').is(':checked'),
            bashAsyncEnabled: $('#generalBashAsyncEnabled').is(':checked'),
            subagentEnabled: $('#generalSubagentEnabled').is(':checked'),
            managerEnabled: $('#generalManagerEnabled').is(':checked'),
            loopsEnabled: $('#generalLoopEnabled').is(':checked'),
            lspEnabled: $('#generalLspEnabled').is(':checked'),
            cliPrintSimplified: $('#generalCliPrintSimplified').is(':checked'),
            webAuthUser: $('#generalWebAuthUser').val().trim() || null,
            webAuthPass: $('#generalWebAuthPass').val().trim() || null,
            logLevel: $('#generalLogLevel').val().trim() || null,
            logFileMaxSize: $('#generalLogFileMaxSize').val().trim() || null,
            logMaxHistory: parseNumStr($('#generalLogMaxHistory').val().trim()),
            proxyHost: $('#generalProxyHost').val().trim() || null,
            proxyPort: parseInt($('#generalProxyPort').val().trim(), 10) || 0,
            noProxy: $('#generalNoProxy').val().trim() || null,
            uiFontFamily: ($('#generalUiFontFamily').val() || '').trim() || null,
            uiFontMono: ($('#generalUiFontMono').val() || '').trim() || null,
            uiFontScale: currentFontScale(),
            defaultThinkingMode: $('#generalDefaultThinkingMode').val() || null,
            defaultReasoningEffort: $('#generalDefaultReasoningEffort').val() || null
        };

        $generalSaveBtn.prop('disabled', true);

        // 收集所有保存请求的 promise
        var promises = [];

        // 1. 保存通用设置（用 .then() 过滤 resp.code）
        promises.push(
            $.ajax({ url: '/web/settings/general/save', method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json' })
                .then(function (resp) {
                    if (resp.code !== 200) {
                        return $.Deferred().reject(resp.message || I18n.t('toast.unknownError')).promise();
                    }
                    window.cliPrintSimplified = bodyObj.cliPrintSimplified;
                    setFontBaseline(readFontForm());
                    // 新建对话默认改了：重拉 _default 槽位，让输入框的思考/推理 pill 无需刷页就跟上
                    if (typeof loadModels === 'function') {
                        loadModels(null);
                    }
                    return resp;
                })
        );

        // 2. 同步保存 Loop Goal 配置
        var loopObj = {
            defaultMaxTokens: parseNumStr($('#generalLoopDefaultMaxTokens').val().trim()) || 0,
            defaultMaxDurationMinutes: parseNumStr($('#generalLoopDefaultMaxDuration').val().trim()) || 0,
            stagnationThreshold: parseNumStr($('#generalLoopStagnationThreshold').val().trim()),
            maxConsecutiveErrors: parseNumStr($('#generalLoopMaxConsecutiveErrors').val().trim()),
            budgetWarningPercent: parseNumStr($('#generalLoopBudgetWarningPercent').val().trim()),
            budgetCriticalPercent: parseNumStr($('#generalLoopBudgetCriticalPercent').val().trim()),
            validatorEnabled: $('#generalLoopValidatorEnabled').is(':checked')
        };
        promises.push(
            $.ajax({ url: '/web/settings/loop/save', method: 'POST', data: JSON.stringify(loopObj), contentType: 'application/json', dataType: 'json' })
                .then(function (resp) {
                    if (resp.code !== 200) {
                        return $.Deferred().reject(resp.message || I18n.t('toast.unknownError')).promise();
                    }
                    return resp;
                })
        );

        // 3. 同步保存工具权限
        if (window._settingsPermission && typeof window._settingsPermission.save === 'function') {
            var p = window._settingsPermission.save();
            if (p) promises.push(p);
        }

        // 统一处理所有请求的结果
        $.when.apply($, promises)
            .done(function () { showToast(window.I18n ? window.I18n.t('toast.saveSuccess') : '\u4fdd\u5b58\u6210\u529f'); })
            .fail(function () { showToast(window.I18n ? window.I18n.t('toast.saveFailed') : '\u4fdd\u5b58\u5931\u8d25', 'error'); })
            .always(function () { $generalSaveBtn.prop('disabled', false); });
    });

        // 打开日志目录（在系统文件管理器中打开）
        $(document).on('click', '#generalLogOpenDirBtn', function () {
            var $btn = $(this).prop('disabled', true);
            $.get('/web/settings/logs/open').done(function (resp) {
                if (resp.code !== 200) {
                    showToast(resp.message || (window.I18n ? window.I18n.t('toast.unknownError') : '打开失败'), 'error');
                }
            }).fail(function () {
                showToast(window.I18n ? window.I18n.t('toast.unknownError') : '打开失败', 'error');
            }).always(function () {
                $btn.prop('disabled', false);
            });
        });

        window._settingsGeneral = {
        load: loadGeneralSettings,
        revertFont: revertFont
    };
})();

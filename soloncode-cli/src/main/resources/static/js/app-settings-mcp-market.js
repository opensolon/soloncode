/**
 * app-settings-mcp-market.js — 「连接器市场」二级页
 *
 * 由设置 → 连接器（MCP）页右上角「添加连接器」进入，浏览/搜索远端连接器，
 * 选中一种接入方式后生成配置草稿并带入「添加连接器」表单预填；
 * 右上角「手动添加」则直接打开空白表单（即原有手动添加方式）。
 *
 * 与技能市场的差异：连接器市场的「安装」不是下载解压，而是生成配置草稿；
 * 密钥等机密参数仍由用户填写，保存走原 /web/settings/mcp/servers/add 链路。
 *
 * 后端接口：
 *   GET  /web/settings/mcp/market/markets                 — 市场列表
 *   GET  /web/settings/mcp/market/proxy?q=&limit=&cursor= — 列表/搜索（游标分页）
 *   GET  /web/settings/mcp/market/detail?id=              — 详情（含接入方式）
 *   POST /web/settings/mcp/market/resolve                 — 生成配置草稿（不落库）
 */

(function () {
    'use strict';

    var core = window._settingsCore;
    var escapeHtml = core.escapeHtml;
    var escapeAttr = core.escapeAttr;
    var showToast = core.showToast;

    var $mcpListView = $('#mcpListView');
    var $mcpMarketView = $('#mcpMarketView');
    var $mcpMarketSelect = $('#mcpMarketSelect');
    var $mcpMarketSearchInput = $('#mcpMarketSearchInput');
    var $mcpMarketSearchClear = $('#mcpMarketSearchClear');
    var $mcpMarketList = $('#mcpMarketList');
    var $mcpMarketCategories = $('#mcpMarketCategories');
    var $mcpMarketStatus = $('#mcpMarketStatus');
    var $mcpMarketLoading = $('#mcpMarketLoading');
    var $mcpMarketError = $('#mcpMarketError');

    // 添加：加号图标（与设置页「添加」按钮的视觉语义一致）
    var SVG_ADD = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14"/><path d="M5 12h14"/></svg>';
    var SVG_SPIN = '<svg class="skill-install-spin" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>';

    var PAGE_SIZE = 30;

    // ==================== 状态 ====================

    var _marketName = '';
    var _categoryId = '';
    var _loaded = false;
    var _loading = false;
    /** 请求序号：只采纳最后一次发起的列表请求 */
    var _reqSeq = 0;
    var _cursor = null;
    var _query = '';
    var _detailCache = {};
    var _searchTimer = null;

    // ==================== 视图切换 ====================

    function showMarketView() {
        $mcpListView.hide();
        $('#mcpFormView').hide();
        $('#mcpToolsView').hide();
        $mcpMarketView.show();

        if (!_loaded) {
            _loaded = true;
            loadMarketOptions();
            loadCategories();
            loadList(true);
        }
    }

    function showListView() {
        $mcpMarketView.hide();
        $mcpListView.addClass('slide-back').show();
        setTimeout(function () { $mcpListView.removeClass('slide-back'); }, 260);
    }

    // ==================== 市场下拉 ====================

    function loadMarketOptions() {
        $.ajax({
            url: '/web/settings/mcp/market/markets',
            method: 'GET',
            timeout: 5000,
            dataType: 'json'
        }).done(function (resp) {
            var markets = (resp && resp.data) ? resp.data : [];
            if (!markets.length) return;

            var html = '';
            markets.forEach(function (m) {
                html += '<option value="' + escapeAttr(m.name || '') + '" title="' + escapeAttr(m.description || '') + '">'
                    + escapeHtml(m.name || '') + '</option>';
            });
            $mcpMarketSelect.html(html);

            _marketName = markets[0].name || '';
            $mcpMarketSelect.val(_marketName);
        }).fail(function () {
            _marketName = '';
        });
    }

    // ==================== 分类筛选 ====================

    /**
     * 加载当前市场的分类。无分类维度的市场（官方 Registry / Smithery）
     * 返回空数组，此时隐藏整条分类栏。
     */
    function loadCategories() {
        $.ajax({
            url: '/web/settings/mcp/market/categories?marketName=' + encodeURIComponent(_marketName || ''),
            method: 'GET',
            timeout: 15000,
            dataType: 'json'
        }).done(function (resp) {
            var categories = (resp && resp.code === 200 && resp.data) ? resp.data : [];
            renderCategories(categories);
        }).fail(function () {
            // 分类是辅助功能，失败时静默隐藏，不影响主列表
            renderCategories([]);
        });
    }

    // 分类名是市场返回的动态内容，原样展示（不查 i18n）；只有「全部」是界面文案，才走 i18n
    function renderCategories(categories) {
        _categoryId = '';

        if (!categories || !categories.length) {
            $mcpMarketCategories.hide().html('');
            return;
        }

        var html = '<button type="button" class="mcp-category-chip active" data-id="">'
            + escapeHtml(I18n.t('mcp.marketAllCategories')) + '</button>';

        categories.forEach(function (c) {
            html += '<button type="button" class="mcp-category-chip" data-id="' + escapeAttr(c.id || '') + '" title="' + escapeAttr(c.name || '') + '">'
                + escapeHtml(c.name || '')
                + '</button>';
        });

        $mcpMarketCategories.html(html).show();
    }

    // ==================== 列表加载 ====================

    /**
     * 切换「加载更多」按钮的忙碌态。
     * 失败时必须回滚，否则按钮会卡在禁用态、用户再也点不动。
     */
    function loadMoreBusy(busy) {
        var $btn = $mcpMarketList.find('.mcp-market-load-more');
        if (!$btn.length) return;

        if (busy) {
            $btn.prop('disabled', true).text(I18n.t('common.loading'));
        } else {
            $btn.prop('disabled', false).text(I18n.t('mcp.marketLoadMore'));
        }
    }

    function loadList(reset) {
        // 切分类/改关键词会连续发请求，用序号丢弃过期响应（否则慢请求会把新结果覆盖）
        if (_loading && !reset) return;
        var seq = ++_reqSeq;
        _loading = true;

        if (reset) {
            _cursor = null;
            $mcpMarketList.html('');
        }

        $mcpMarketStatus.show();
        $mcpMarketLoading.css('display', 'flex');
        $mcpMarketError.hide();

        var url = '/web/settings/mcp/market/proxy?limit=' + PAGE_SIZE
            + '&q=' + encodeURIComponent(_query || '')
            + '&categoryId=' + encodeURIComponent(_categoryId || '')
            + '&marketName=' + encodeURIComponent(_marketName || '');
        if (!reset && _cursor) {
            url += '&cursor=' + encodeURIComponent(_cursor);
        }

        $.ajax({ url: url, method: 'GET', timeout: 30000, dataType: 'json' })
            .done(function (resp) {
                if (seq !== _reqSeq) return;
                $mcpMarketLoading.hide();

                if (resp && resp.code !== undefined && resp.code !== 200) {
                    loadMoreBusy(false);
                    $mcpMarketError.text(resp.description || resp.message || I18n.t('mcp.marketLoadFailed')).show();
                    return;
                }

                var page = (resp && resp.data) ? resp.data : {};
                var items = page.items || [];
                _cursor = page.nextCursor || null;

                renderList(items, reset);
                if (reset && items.length === 0) {
                    $mcpMarketStatus.hide();
                }
            })
            .fail(function (jqXHR, textStatus) {
                if (seq !== _reqSeq) return;
                $mcpMarketLoading.hide();
                loadMoreBusy(false);
                var msg = I18n.t('mcp.marketLoadFailed');
                if (textStatus === 'timeout') msg = I18n.t('mcp.marketTimeout');
                else if (jqXHR && jqXHR.status) msg = I18n.t('mcp.marketLoadFailedHttp', { n: jqXHR.status });
                $mcpMarketError.text(msg).show();
            })
            .always(function () {
                if (seq === _reqSeq) _loading = false;
            });
    }

    // ==================== 渲染 ====================

    function transportLabel(transports) {
        if (!transports || !transports.length) return '';
        return transports.map(function (t) {
            if (t === 'stdio') return 'stdio';
            if (t === 'sse') return 'SSE';
            if (t === 'streamable-http' || t === 'streamable') return 'HTTP';
            return t;
        }).join(' / ');
    }

    /**
     * 重复图标判定阈值：同一个图标地址在当次渲染中出现达到该次数，即视为平台默认占位图，
     * 这类图标没有辨识度（多个条目长得一样），不如回退成名称首字母。
     */
    var REPEATED_ICON_THRESHOLD = 3;

    /** 统计本次批内各图标地址的出现次数 */
    function countIcons(iconUrls) {
        var counts = {};
        (iconUrls || []).forEach(function (url) {
            if (url) {
                counts[url] = (counts[url] || 0) + 1;
            }
        });
        return counts;
    }

    /** 是否属于「重复出现的占位图」——是则不用图片，直接用文字徽标 */
    function isPlaceholderIcon(iconUrl, iconCounts) {
        return !!iconUrl && (iconCounts[iconUrl] || 0) >= REPEATED_ICON_THRESHOLD;
    }

    /**
     * 图标：优先用市场返回的 iconUrl（经后端代理加载，前端不直连境外域名），
     * 加载失败、无图标、或图标疑似占位图时回退为名称首字母。
     */
    function iconHtml(item, iconText, placeholder) {
        var iconUrl = item.iconUrl || '';
        if (!iconUrl || placeholder) {
            return '<div class="settings-list-icon">' + escapeHtml(iconText) + '</div>';
        }

        var proxied = '/web/settings/mcp/market/icon?url=' + encodeURIComponent(iconUrl);
        return '<div class="settings-list-icon has-img">'
            + '<img src="' + escapeAttr(proxied) + '" alt="" loading="lazy"'
            + ' onerror="this.parentNode.classList.remove(\'has-img\');this.parentNode.classList.add(\'img-failed\');this.remove();">'
            + '<span class="icon-fallback">' + escapeHtml(iconText) + '</span>'
            + '</div>';
    }

    /**
     * 使用量：过千折算为 1.2k / 3.5w
     */
    function formatUseCount(count) {
        var n = Number(count) || 0;
        if (n <= 0) return '';
        if (n >= 10000) return (Math.round(n / 1000) / 10) + 'w';
        if (n >= 1000) return (Math.round(n / 100) / 10) + 'k';
        return String(n);
    }

    function renderList(items, reset) {
        if (reset) {
            $mcpMarketList.html('');
        }

        // 先移除上一次的「加载更多」
        $mcpMarketList.find('.skills-load-more-wrap').remove();

        if (reset && (!items || items.length === 0)) {
            $mcpMarketList.html(
                '<div class="skill-empty-state">'
                + '<svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5">'
                + '<rect x="2" y="7" width="20" height="8" rx="2"/><rect x="2" y="15" width="20" height="6" rx="2"/>'
                + '</svg>'
                + '<div style="font-size:13px;margin-top:12px;">' + I18n.t('mcp.marketEmpty') + '</div>'
                + '</div>'
            );
            return;
        }

        // 先统计本批图标出现次数，用于识别平台默认占位图
        var iconCounts = countIcons(items.map(function (item) { return item.iconUrl || ''; }));

        var html = '';
        items.forEach(function (item) {
            var id = item.id || '';
            var name = item.displayName || item.name || id;
            var desc = item.description || '';
            var shortDesc = Array.from(desc).length > 80 ? Array.from(desc).slice(0, 80).join('') + '...' : desc;
            var iconText = name ? name.replace(/[^a-zA-Z0-9]/g, '').substring(0, 2).toUpperCase() : 'MC';
            if (!iconText) iconText = 'MC';

            var version = item.version ? 'v' + String(item.version).replace(/^v/i, '') : '';
            var transport = transportLabel(item.transports);
            // 外部页面地址：优先仓库地址，其次项目主页（腾讯、Smithery 市场只提供主页）
            var externalUrl = item.repositoryUrl || item.homepageUrl || '';

            html += '<div class="settings-list-item is-static" data-id="' + escapeAttr(id) + '">'
                + iconHtml(item, iconText, isPlaceholderIcon(item.iconUrl, iconCounts))
                + '<div class="settings-list-info">'
                + '<div class="settings-list-title" title="' + escapeAttr(id) + '">' + escapeHtml(name)
                + (item.installed ? '<span class="skill-installed-badge">' + I18n.t('mcp.marketInstalledBadge') + '</span>' : '')
                + '</div>'
                + (shortDesc ? '<div class="settings-list-desc" title="' + escapeAttr(desc) + '">' + escapeHtml(shortDesc) + '</div>' : '')
                + '<div class="skill-item-meta">'
                + (version ? '<span class="skill-item-version">' + escapeHtml(version) + '</span>' : '')
                + (transport ? '<span>' + escapeHtml(transport) + '</span>' : '')
                + (item.owner ? '<span>' + escapeHtml(item.owner) + '</span>' : '')
                + (item.verified ? '<span class="mcp-market-verified" title="' + I18n.t('mcp.marketVerified') + '">✓ ' + I18n.t('mcp.marketVerified') + '</span>' : '')
                + (formatUseCount(item.useCount) ? '<span title="' + I18n.t('mcp.marketUses') + '">' + escapeHtml(formatUseCount(item.useCount)) + '</span>' : '')
                + (externalUrl ? '<button type="button" class="skill-item-detail-link" data-url="' + escapeAttr(externalUrl) + '" title="' + I18n.t('skills.openExternal') + '">↗</button>' : '')
                + '</div></div>'
                + '<div class="settings-list-actions">'
                + '<div class="skill-install-wrap">'
                + '<button type="button" class="skill-install-btn mcp-market-use-btn" data-id="' + escapeAttr(id) + '" title="' + I18n.t('mcp.marketAdd') + '">' + SVG_ADD + '</button>'
                + '<div class="skill-install-dropdown" data-id="' + escapeAttr(id) + '"></div>'
                + '</div>'
                + '</div></div>';
        });

        $mcpMarketList.append(html);

        if (_cursor) {
            $mcpMarketList.append('<div class="skills-load-more-wrap" style="text-align:center;padding:12px;">'
                + '<button type="button" class="skills-load-more-btn mcp-market-load-more" style="min-width:200px;">'
                + I18n.t('mcp.marketLoadMore') + '</button></div>');
        }
    }

    // ==================== 详情与草稿 ====================

    function fetchDetail(id, callback) {
        if (_detailCache[id]) {
            callback(_detailCache[id]);
            return;
        }

        $.ajax({
            url: '/web/settings/mcp/market/detail?id=' + encodeURIComponent(id)
                + '&marketName=' + encodeURIComponent(_marketName || ''),
            method: 'GET',
            timeout: 30000,
            dataType: 'json'
        }).done(function (resp) {
            if (resp && resp.code === 200 && resp.data) {
                _detailCache[id] = resp.data;
                callback(resp.data);
            } else {
                showToast((resp && (resp.description || resp.message)) || I18n.t('mcp.marketDetailFailed'), 'error');
            }
        }).fail(function () {
            showToast(I18n.t('mcp.marketDetailFailed'), 'error');
        });
    }

    function resolveDraft(id, configId) {
        $.ajax({
            url: '/web/settings/mcp/market/resolve',
            method: 'POST',
            data: {
                id: id,
                configId: configId || '',
                marketName: _marketName || ''
            },
            timeout: 30000,
            dataType: 'json'
        }).done(function (resp) {
            if (resp && resp.code === 200 && resp.data) {
                if (window._settingsMcp && window._settingsMcp.showDraft) {
                    window._settingsMcp.showDraft(resp.data);
                }
            } else {
                showToast((resp && (resp.description || resp.message)) || I18n.t('mcp.marketResolveFailed'), 'error');
            }
        }).fail(function () {
            showToast(I18n.t('mcp.marketResolveFailed'), 'error');
        });
    }

    // ==================== 事件 ====================

    $('#mcpAddBtn').on('click', function () {
        showMarketView();
    });

    // 市场页右上角「手动添加」→ 空白表单
    $('#mcpMarketManualAddBtn').on('click', function () {
        if (window._settingsMcp && window._settingsMcp.showAddForm) {
            window._settingsMcp.showAddForm();
        }
    });

    $('#mcpMarketBackBtn').on('click', function () {
        showListView();
    });

    $mcpMarketSelect.on('change', function () {
        _marketName = $(this).val() || '';
        _categoryId = '';
        _detailCache = {};
        loadCategories();
        loadList(true);
    });

    // 分类切换
    $mcpMarketCategories.on('click', '.mcp-category-chip', function () {
        var $chip = $(this);
        if ($chip.hasClass('active')) return;

        $mcpMarketCategories.find('.mcp-category-chip').removeClass('active');
        $chip.addClass('active');
        _categoryId = $chip.attr('data-id') || '';
        loadList(true);
    });

    // 搜索（防抖 + 回车立即）
    $mcpMarketSearchInput.on('input', function () {
        var val = $(this).val().trim();
        $mcpMarketSearchClear.toggle(val.length > 0);
        clearTimeout(_searchTimer);
        _searchTimer = setTimeout(function () {
            _query = val;
            loadList(true);
        }, 350);
    }).on('keydown', function (e) {
        if (e.key === 'Enter' && !isInputComposing(e)) {
            e.preventDefault();
            clearTimeout(_searchTimer);
            _query = $(this).val().trim();
            loadList(true);
        }
    });

    $mcpMarketSearchClear.on('click', function () {
        $mcpMarketSearchInput.val('').focus();
        $(this).hide();
        _query = '';
        loadList(true);
    });

    // 加载更多
    $mcpMarketList.on('click', '.mcp-market-load-more', function () {
        loadMoreBusy(true);
        loadList(false);
    });

    // 点击「使用」：拉详情 → 单一接入方式直接解析，多种则展开选择
    $mcpMarketList.on('click', '.mcp-market-use-btn', function (e) {
        e.stopPropagation();
        var $btn = $(this);
        var id = $btn.attr('data-id');
        if (!$btn.length || $btn.hasClass('loading')) return;

        $btn.addClass('loading').html(SVG_SPIN);

        fetchDetail(id, function (detail) {
            $btn.removeClass('loading').html(SVG_ADD);

            var configs = detail.configs || [];
            if (!configs.length) {
                showToast(I18n.t('mcp.marketNoConfig'), 'error');
                return;
            }

            if (configs.length === 1) {
                resolveDraft(id, configs[0].configId);
                return;
            }

            // 多种接入方式：填充下拉并展开
            var $dropdown = $btn.closest('.skill-install-wrap').find('.skill-install-dropdown');
            var html = '';
            configs.forEach(function (c) {
                html += '<div class="skill-install-mount-option" data-id="' + escapeAttr(id) + '" data-config="' + escapeAttr(c.configId) + '" title="' + escapeAttr(c.label || '') + '">'
                    + escapeHtml(c.label || c.configId) + '</div>';
            });
            $('.skill-install-dropdown').not($dropdown).removeClass('active');
            $dropdown.html(html).addClass('active');
        });
    });

    // 选择接入方式 → 解析草稿
    $mcpMarketList.on('click', '.skill-install-mount-option', function (e) {
        e.stopPropagation();
        var $option = $(this);
        var id = $option.attr('data-id');
        var configId = $option.attr('data-config');
        $option.closest('.skill-install-dropdown').removeClass('active');
        resolveDraft(id, configId);
    });

    // 打开外部仓库页面
    $mcpMarketList.on('click', '.skill-item-detail-link', function (e) {
        e.stopPropagation();
        var url = $(this).attr('data-url');
        if (url) window.open(url, '_blank');
    });

    $(document).on('click', function () {
        $mcpMarketList.find('.skill-install-dropdown').removeClass('active');
    });

    // ==================== 对外接口 ====================

    window._settingsMcpMarket = {
        /** 重置并回到市场列表（下次进入重新拉取） */
        reset: function () {
            _loaded = false;
            _cursor = null;
            _detailCache = {};
            _query = '';
            _categoryId = '';
            $mcpMarketSearchInput.val('');
            $mcpMarketSearchClear.hide();
            $mcpMarketCategories.hide().html('');
        },
        show: showMarketView,
        hide: function () {
            $mcpMarketView.hide();
        }
    };

})();

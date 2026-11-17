/**
 * app-settings-skill.js — 「技能」页交互逻辑（所有 API 调用均走后端代理）
 *
 * 两级视图：
 *   技能管理（列表页）— 按 SKILLS 挂载分组列出本地技能包，支持升级、删除、打开目录
 *   技能市场（二级页）— 由右上角「添加技能」进入，浏览/搜索/安装远程技能包，返回按钮回列表
 *
 * 依赖：layui.js（jQuery）、app-base.js、app-settings.js（escapeHtml/escapeAttr 全局共享）
 * 协同：app-history.js（commandList / loadCommands）
 *
 * 后端接口：
 *   GET  /web/settings/skills/markets                     — 获取可用市场列表
 *   GET  /web/settings/skills/market/categories           — 获取市场分类清单
 *   GET  /web/settings/skills/proxy?q=&page=              — 分页获取技能（q 为空=浏览）
 *   POST /web/settings/skills/install  {slug, marketName, mountAlias}  — 安装技能
 *   GET  /web/settings/mounts                             — 挂载列表
 *   GET  /web/settings/mounts/content?alias=&type=SKILLS  — 挂载内技能包列表
 *   POST /web/settings/mounts/skills/remove {alias, skillName} — 删除技能包
 *   GET  /web/settings/mounts/open?path=                  — 打开本地目录
 */

(function () {
    'use strict';

    // ==================== 常量 ====================

    var SKILLS_API_BASE = '/web/settings/skills/proxy';
    /** 技能市场每页条数（SkillHub 的 /api/skills 上限 100，50 条一页在列表长度与首屏速度间比较均衡） */
    var MARKET_PAGE_SIZE = 50;

    // ==================== DOM 引用 ====================

    var $skillsMarketSelect = $('#skillsMarketSelect');
    var $skillsSearchInput = $('#skillsSearchInput');
    var $skillsSearchClear = $('#skillsSearchClear');
    var $skillsList = $('#skillsList');
    var $skillsLoading = $('#skillsLoading');
    var $skillsError = $('#skillsError');
    var $skillsStatus = $('#skillsStatus');
    var $skillsMarketCategories = $('#skillsMarketCategories');

    var $skillsViewInstalled = $('#skillsViewInstalled');
    var $skillsViewMarket = $('#skillsViewMarket');
    var $skillsInstalledList = $('#skillsInstalledList');
    var $skillsInstalledStatus = $('#skillsInstalledStatus');
    var $skillsInstalledLoading = $('#skillsInstalledLoading');
    var $skillsInstalledError = $('#skillsInstalledError');
    var $skillsMountFilter = $('#skillsMountFilter');
    var $skillsInstalledFilter = $('#skillsInstalledFilter');
    var $skillsInstalledFilterClear = $('#skillsInstalledFilterClear');

    // ==================== 状态 ====================

    var _installedSkillsCache = null;
    var _skillsSearchTimer = null;
    var _currentMarketName = '';  // 当前选中的市场名称
    var _mountPoolsCache = null;  // SKILLS 类型挂载缓存 [{alias, path}, ...]
    var _skillCategoryKey = '';   // 当前选中的市场分类（空串 = 全部）

    // 技能市场分页状态（后端返回 SkillMarketPage：items/hasMore/total）
    var _marketPage = 1;              // 当前已加载到的页码
    var _marketHasMore = false;       // 是否还有下一页
    var _marketTotal = -1;            // 结果总数（-1 = 未知，如 ClawHub 类不支持分页的市场）
    var _marketQuery = null;          // 当前列表的搜索关键词（null = 浏览），翻页时沿用
    var _marketLoadingMore = false;   // 是否正在加载下一页
    var _marketReqSeq = 0;            // 请求序号，丢弃过期响应（快速切分类/改关键词时）

    var _currentView = 'installed';   // 当前子视图：installed | market
    var _marketLoaded = false;        // 市场列表是否已拉取（懒加载）
    var _installedDirty = true;       // 已安装列表是否需要重载
    var _installedGroups = [];        // [{alias, skills:[{name, description, realPath}]}]

    // 分页：已安装技能列表（防止大量技能导致 DOM 暴炸）
    var _installedPage = 1;           // 当前页码
    var _installedPageSize = 50;      // 每页渲染条数
    var _installedFilteredGroups = null;  // 过滤后的分组缓存（用于分页追加渲染）
    var _installedKeyword = '';           // 当前搜索关键词（供分页函数跨作用域访问）

    // ==================== SVG 图标常量（消除重复硬编码） ====================

    var SVG_REFRESH  = '<svg class="skill-install-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>';
    var SVG_DOWNLOAD = '<svg class="skill-install-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>';
    var SVG_SPIN_REFRESH  = '<svg class="skill-install-spin" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>';
    var SVG_SPIN_DOWNLOAD  = '<svg class="skill-install-spin" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>';
    var SVG_REFRESH_SM = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>';
    var SVG_DELETE_SM  = SVG_TRASH; /* 全局统一删除图标（app-base.js），不再内联副本 */

    // ==================== 工具函数 ====================

    /** 使用全局共享的转义函数（app-settings.js 通过 window._settingsCore 暴露） */
    var escapeHtml = (window._settingsCore && window._settingsCore.escapeHtml) || function (str) {
        if (!str) return '';
        return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    };
    var escapeAttr = (window._settingsCore && window._settingsCore.escapeAttr) || function (str) {
        if (!str) return '';
        return String(str).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/'/g, '&#39;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    };

    // ==================== 挂载预加载 ====================

    /** 加载 SKILLS 类型挂载列表（带缓存） */
    function loadMountPools(callback) {
        if (_mountPoolsCache) {
            callback(_mountPoolsCache);
            return;
        }
        $.ajax({
            url: '/web/settings/mounts',
            method: 'GET',
            timeout: 5000,
            dataType: 'json'
        }).done(function (resp) {
            var pools = (resp && resp.code === 200 && resp.data) ? resp.data : [];
            _mountPoolsCache = pools.filter(function (p) {
                return p.type === 'SKILLS' || !p.type;
            }).map(function (p) {
                return { alias: p.alias || '', path: p.path || '' };
            });
            if (!_mountPoolsCache.length) {
                // 兵底项仅供市场“安装到”下拉使用，已安装视图不能拿它去请求内容
                _mountPoolsCache = [{ alias: '@skills', path: '', fallback: true }];
            }
            callback(_mountPoolsCache);
        }).fail(function () {
            _mountPoolsCache = [{ alias: '@skills', path: '', fallback: true }];
            callback(_mountPoolsCache);
        });
    }

    // ==================== 市场选择器初始化 ====================

    /**
     * 从后端加载可用市场列表并填充下拉框
     */
    function loadMarketOptions() {
        $.ajax({
            url: '/web/settings/skills/markets',
            method: 'GET',
            timeout: 5000,
            dataType: 'json'
        }).done(function (resp) {
            var markets = (resp && resp.data) ? resp.data : [];
            if (!markets.length) return;

            var html = '';
            markets.forEach(function (m) {
                var label = escapeHtml(m.name || '');
                html += '<option value="' + escapeAttr(m.name || '') + '">' + label + '</option>';
            });
            $skillsMarketSelect.html(html);

            // 默认选中第一个
            _currentMarketName = markets[0].name || '';
            $skillsMarketSelect.val(_currentMarketName);

            // 市场确定后再拉分类（无分类维度的市场会返回空，分类栏自动隐藏）
            loadCategories();
        }).fail(function () {
            $skillsMarketSelect.html('<option value="">ClawHub</option>');
            _currentMarketName = '';
            loadCategories();
        });
    }

    // ==================== 分类筛选 ====================

    /**
     * 加载当前市场的分类。无分类维度的市场返回空数组，此时隐藏整条分类栏。
     */
    function loadCategories() {
        $.ajax({
            url: '/web/settings/skills/market/categories?marketName=' + encodeURIComponent(_currentMarketName || ''),
            method: 'GET',
            timeout: 20000,
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
        _skillCategoryKey = '';

        if (!categories || !categories.length) {
            $skillsMarketCategories.hide().html('');
            return;
        }

        var html = '<button type="button" class="mcp-category-chip active" data-key="">'
            + escapeHtml(I18n.t('skills.marketAllCategories')) + '</button>';

        categories.forEach(function (c) {
            var count = Number(c.count) || 0;
            html += '<button type="button" class="mcp-category-chip" data-key="' + escapeAttr(c.id || '') + '" title="' + escapeAttr(c.name || '') + '">'
                + escapeHtml(c.name || '')
                + (count > 0 ? '<span class="mcp-category-count">' + count + '</span>' : '')
                + '</button>';
        });

        $skillsMarketCategories.html(html).show();
    }


    // ==================== 已安装技能 ====================

    function getInstalledSkills(callback) {
        if (typeof commandList !== 'undefined' && commandList.length > 0) {
            if (!_installedSkillsCache) {
                _installedSkillsCache = {};
                commandList.forEach(function (item) {
                    if (item.type === 'skill') _installedSkillsCache[item.name] = item.mountAlias || true;
                });
            }
            callback(_installedSkillsCache);
            return;
        }
        $.get('/web/chat/hints', function (resp) {
            _installedSkillsCache = {};
            (resp.data || []).forEach(function (item) {
                if (item.type === 'skill') _installedSkillsCache[item.name] = item.mountAlias || true;
            });
            callback(_installedSkillsCache);
        }).fail(function () {
            _installedSkillsCache = {};
            callback(_installedSkillsCache);
        });
    }

    // ==================== 子视图切换 ====================

    function switchView(view) {
        _currentView = (view === 'market') ? 'market' : 'installed';

        if (_currentView === 'market') {
            $skillsViewInstalled.hide();
            $skillsViewMarket.show();
            if (!_marketLoaded) {
                _marketLoaded = true;
                loadMarketOptions();
                loadSkillsList(null);
            }
        } else {
            $skillsViewMarket.hide();
            // 返回列表页时播放入场动画，方向与二级页进入相反
            $skillsViewInstalled.addClass('slide-back').show();
            setTimeout(function () { $skillsViewInstalled.removeClass('slide-back'); }, 260);
            if (_installedDirty) loadInstalledSkills();
        }
    }

    // ==================== 已安装：数据加载 ====================

    /** 填充挂载筛选下拉框（带技能包计数） */
    function fillMountFilter(pools, groupCounts) {
        var prev = $skillsMountFilter.val() || '';
        var html = '';
        pools.forEach(function (p) {
            var count = groupCounts ? (groupCounts[p.alias] || 0) : 0;
            html += '<option value="' + escapeAttr(p.alias) + '">' + escapeHtml(p.alias) + ' - ' + count + '</option>';
        });
        $skillsMountFilter.html(html);
        // 默认选中第一个挂载（取消"全部挂载"后保证有初始选中项）
        if (!prev || !pools.some(function (p) { return p.alias === prev; })) {
            if (pools.length) $skillsMountFilter.val(pools[0].alias);
        } else {
            $skillsMountFilter.val(prev);
        }
    }

    /**
     * 加载已安装技能：加载全部 SKILLS 挂载内容，填充下拉框计数后按选中挂载过滤渲染
     */
    function loadInstalledSkills() {
        $skillsInstalledStatus.show();
        $skillsInstalledLoading.css('display', 'flex');
        $skillsInstalledError.hide();
        $skillsInstalledList.html('');

        loadMountPools(function (allPools) {
            // 排除兵底占位项（无真实挂载时的占位）
            var pools = allPools.filter(function (p) { return !p.fallback; });

            if (!pools.length) {
                _installedGroups = [];
                _installedDirty = false;
                fillMountFilter(pools);
                $skillsInstalledLoading.hide();
                $skillsInstalledStatus.hide();
                renderInstalledList();
                return;
            }

            // 加载所有挂载的技能内容（用于在下拉框中显示各挂载的技能包计数）
            var pending = pools.length;
            var allGroups = [];

            pools.forEach(function (pool, idx) {
                $.get('/web/settings/mounts/content', { alias: pool.alias, type: 'SKILLS' })
                    .done(function (resp) {
                        var list = (resp && resp.code === 200 && Array.isArray(resp.data)) ? resp.data : [];
                        allGroups[idx] = { alias: pool.alias, skills: list };
                    })
                    .fail(function () {
                        allGroups[idx] = { alias: pool.alias, skills: [], failed: true };
                    })
                    .always(function () {
                        if (--pending > 0) return;
                        allGroups = allGroups.filter(function (g) { return !!g; });

                        // 构建挂载计数映射
                        var groupCounts = {};
                        allGroups.forEach(function (g) {
                            groupCounts[g.alias] = (g.skills || []).length;
                        });

                        // 填充下拉框（带计数）
                        fillMountFilter(pools, groupCounts);

                        // 按选中的挂载过滤
                        var selected = $skillsMountFilter.val() || (pools.length ? pools[0].alias : '');
                        _installedGroups = selected
                            ? allGroups.filter(function (g) { return g.alias === selected; })
                            : allGroups;

                        _installedDirty = false;
                        $skillsInstalledLoading.hide();
                        $skillsInstalledStatus.hide();
                        renderInstalledList();
                    });
            });
        });
    }

    // ==================== 已安装：渲染 ====================

    /**
     * 渲染已安装列表（分页渲染，防止大量技能导致 DOM 暴炸）
     * 首次渲染第 1 页，底部追加「加载更多」按钮按需翻页。
     * 搜索/筛选/挂载切换时重置页码。
     */
    function renderInstalledList() {
        var keyword = ($skillsInstalledFilter.val() || '').trim().toLowerCase();

        // 过滤出符合条件的分组数据
        var filteredGroups = [];
        var total = 0;

        _installedGroups.forEach(function (group) {
            var skills = group.skills || [];
            if (keyword) {
                skills = skills.filter(function (s) {
                    return ((s.name || '') + ' ' + (s.description || '')).toLowerCase().indexOf(keyword) >= 0;
                });
            }
            if (!skills.length) return;
            total += skills.length;
            filteredGroups.push({ alias: group.alias, skills: skills });
        });

        _installedFilteredGroups = filteredGroups;
        _installedPage = 1;
        _installedKeyword = keyword;

        // 已安装 tab 上的数字已移除，不再显示计数

        if (!total) {
            var isFiltering = !!keyword;
            $skillsInstalledList.html(
                '<div class="skill-empty-state">'
                + '<svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5">'
                + '<polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/>'
                + '</svg>'
                + '<div style="font-size:13px;margin-top:12px;">' + (isFiltering ? I18n.t('skills.noMatchDesc') : I18n.t('skills.notInstalled')) + '</div>'
                + (isFiltering ? '' : '<div style="font-size:12px;margin-top:6px;opacity:.7">' + I18n.t('skills.emptyDesc') + '</div>'
                    + '<button type="button" class="skills-installed-goto-market" style="max-width:200px;margin:16px auto 0">' + I18n.t('skills.browseMarket') + '</button>')
                + '</div>'
            );
            return;
        }

        // 渲染第 1 页
        var pageHtml = renderInstalledPageHtml(1);
        $skillsInstalledList.html(pageHtml);

        // 如果还有更多页，追加「加载更多」按钮
        appendLoadMoreIfNeeded();
    }

    /**
     * 计算指定页码的 HTML 片段
     */
    function renderInstalledPageHtml(pageNum) {
        var start = (pageNum - 1) * _installedPageSize;
        var end = start + _installedPageSize;

        var html = '';
        var rendered = 0;

        // 扁平化所有分组中的技能，保留分组边界
        var flatItems = [];
        _installedFilteredGroups.forEach(function (group) {
            group.skills.forEach(function (skill) {
                flatItems.push({ group: group, skill: skill });
            });
        });

        var slice = flatItems.slice(start, end);

        slice.forEach(function (item) {
            var skill = item.skill;
            var name = skill.name || '';
            var version = skill.version || '';
            var iconText = name ? name.substring(0, 2).toUpperCase() : 'SK';
            var enabled = skill.enabled !== false;
            var aliasPath = item.group.alias + (name ? '/' + name : '');
            html += '<div class="settings-list-item mounts-skill-item' + (enabled ? '' : ' disabled') + '" data-real-path="' + escapeAttr(skill.realPath || '') + '">'
                + '<div class="settings-list-icon">' + escapeHtml(iconText) + '</div>'
                + '<div class="settings-list-info">'
                + '<div class="settings-list-title">' + escapeHtml(name) + '</div>'
                + (skill.description ? '<div class="settings-list-desc">' + escapeHtml(skill.description) + '</div>' : '')
                + (skill.realPath ? '<div class="settings-list-desc settings-muted-text">' + escapeHtml(skill.realPath) + '</div>' : '')
                + (version ? '<div class="settings-list-desc"><span class="skill-item-version">v' + escapeHtml(version.replace(/^v/i, '')) + '</span></div>' : '')
                + '</div><div class="settings-list-actions">'
                + '<button class="settings-action-btn skills-installed-upgrade-btn" data-skill="' + escapeAttr(name) + '" data-alias="' + escapeAttr(item.group.alias) + '" title="' + I18n.t('skills.upgradeTitle') + '">' + SVG_REFRESH_SM + '</button>'
                + '<button class="settings-action-btn delete skills-installed-delete-btn" data-skill="' + escapeAttr(name) + '" data-alias="' + escapeAttr(item.group.alias) + '" title="' + I18n.t('skills.deleteTitle') + '">' + SVG_DELETE_SM + '</button>'
                + '<label class="toggle-switch" title="' + (enabled ? I18n.t('skills.toggle.disable') : I18n.t('skills.toggle.enable')) + '">'
                + '<input type="checkbox" ' + (enabled ? 'checked' : '') + ' data-alias-path="' + escapeAttr(aliasPath) + '" class="skills-installed-toggle"/> '
                + '<span class="toggle-slider"></span>'
                + '</label>'
                + '</div></div>';
            rendered++;
        });

        if (pageNum === 1 && !_installedKeyword) {
            // 首页底部保留「去市场」入口
            var totalItems = flatItems.length;
            if (end >= totalItems) {
                html += '<button type="button" class="skills-installed-goto-market">' + I18n.t('skills.goMarket') + '</button>';
            }
        }

        return html;
    }

    /**
     * 如果还有更多未渲染的技能，在列表底部追加「加载更多」按钮
     */
    function appendLoadMoreIfNeeded() {
        if (!_installedFilteredGroups) return;
        var flatItems = [];
        _installedFilteredGroups.forEach(function (group) {
            group.skills.forEach(function (skill) {
                flatItems.push(skill);
            });
        });
        var totalLoaded = _installedPage * _installedPageSize;
        if (totalLoaded < flatItems.length) {
            var remaining = flatItems.length - totalLoaded;
            var btn = '<div class="skills-load-more-wrap" style="text-align:center;padding:12px;">'
                + '<button type="button" class="skills-load-more-btn" style="min-width:200px;">'
                + I18n.t('skills.loadMore', {n: remaining})
                + '</button></div>';
            $skillsInstalledList.append(btn);
        } else {
            // 全部已加载，追加「去市场」入口（如果首页没加过）
            if (!($skillsInstalledList.find('.skills-installed-goto-market').length)) {
                $skillsInstalledList.append('<button type="button" class="skills-installed-goto-market">' + I18n.t('skills.goMarket') + '</button>');
            }
        }
    }

    // ==================== 已安装：事件 ====================

    // 右上角「添加技能」进入二级页「技能市场」
    $('#skillsAddBtn').on('click', function () {
        switchView('market');
    });

    // 二级页返回列表
    $('#skillsMarketBackBtn').on('click', function () {
        switchView('installed');
    });

    $skillsInstalledList.on('click', '.skills-installed-goto-market', function (e) {
        e.stopPropagation();
        switchView('market');
    });

    // 加载更多
    $skillsInstalledList.on('click', '.skills-load-more-btn', function (e) {
        e.stopPropagation();
        var $btn = $(this);
        $btn.prop('disabled', true).text(I18n.t('skills.loadingMore'));
        _installedPage++;
        var moreHtml = renderInstalledPageHtml(_installedPage);
        $btn.parent('.skills-load-more-wrap').remove();
        $skillsInstalledList.append(moreHtml);
        appendLoadMoreIfNeeded();
    });

    // 挂载筛选
    $skillsMountFilter.on('change', function () {
        loadInstalledSkills();
    });

    // 本地过滤（纯前端，无需重新请求）
    $skillsInstalledFilter.on('input', function () {
        var val = $(this).val().trim();
        $skillsInstalledFilterClear.toggle(val.length > 0);
        clearTimeout(_skillsSearchTimer);
        _skillsSearchTimer = setTimeout(renderInstalledList, 120);
    });

    $skillsInstalledFilterClear.on('click', function () {
        $skillsInstalledFilter.val('').focus();
        $(this).hide();
        renderInstalledList();
    });

    // 启用/停用技能
    $skillsInstalledList.on('change', '.skills-installed-toggle', function () {
        var $chk = $(this);
        var aliasPath = $chk.attr('data-alias-path');
        var enabled = this.checked;
        var $item = $chk.closest('.mounts-skill-item');
        $.ajax({
            url: '/web/settings/skills/toggle',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({ aliasPath: aliasPath, enabled: enabled }),
            dataType: 'json'
        }).done(function (resp) {
            if (resp && resp.code === 200) {
                $item.toggleClass('disabled', !enabled);
                if (typeof loadCommands === 'function') loadCommands();
                if (typeof layer !== 'undefined' && layer.msg) {
                    layer.msg(enabled ? I18n.t('skills.enabled') : I18n.t('skills.disabled'), { icon: 1, time: 2200, offset: '120px' });
                }
            } else {
                $chk.prop('checked', !enabled);
                var msg = (resp && (resp.description || resp.message)) || I18n.t('toast.operateFailed');
                if (typeof layer !== 'undefined' && layer.msg) layer.msg(msg, { icon: 2, time: 3000, offset: '120px' });
            }
        }).fail(function () {
            $chk.prop('checked', !enabled);
            if (typeof layer !== 'undefined' && layer.msg) layer.msg(I18n.t('toast.operateFailed'), { icon: 2, time: 3000, offset: '120px' });
        });
    });

    // 删除技能包
    $skillsInstalledList.on('click', '.skills-installed-delete-btn', function (e) {
        e.stopPropagation();
        var skillName = $(this).attr('data-skill');
        var alias = $(this).attr('data-alias');

        var doRemove = function () {
            $.ajax({
                url: '/web/settings/mounts/skills/remove',
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({ alias: alias, skillName: skillName }),
                dataType: 'json'
            }).done(function (resp) {
                if (resp && resp.code === 200) {
                    if (typeof layer !== 'undefined' && layer.msg) {
                        layer.msg(I18n.t('skills.deleted', {name: escapeHtml(skillName)}), { icon: 1, time: 2200, offset: '120px' });
                    }
                    // 市场徒章需重算
                    _installedSkillsCache = null;
                    _marketLoaded = false;
                    if (typeof loadCommands === 'function') loadCommands();
                    loadInstalledSkills();
                } else {
                    var msg = (resp && (resp.description || resp.message)) || I18n.t('skills.deleteFailed');
                    if (typeof layer !== 'undefined' && layer.msg) layer.msg(msg, { icon: 2, time: 3000, offset: '120px' });
                }
            }).fail(function () {
                if (typeof layer !== 'undefined' && layer.msg) layer.msg(I18n.t('skills.deleteFailedNetwork'), { icon: 2, time: 3000, offset: '120px' });
            });
        };

        if (typeof layer !== 'undefined' && layer.confirm) {
            layer.confirm(I18n.t('skills.deleteConfirm', {name: escapeHtml(skillName)}),
                { title: I18n.t('skills.deleteConfirmTitle'), btn: [I18n.t('common.delete'), I18n.t('common.cancel')], icon: 3, offset: '120px' },
                function (index) { layer.close(index); doRemove(); });
        } else if (confirm(I18n.t('skills.deleteConfirm', {name: skillName}))) {
            doRemove();
        }
    });

    // 升级（从市场重装到原挂载点）
    $skillsInstalledList.on('click', '.skills-installed-upgrade-btn', function (e) {
        e.stopPropagation();
        var $btn = $(this);
        if ($btn.hasClass('installing')) return;

        var slug = $btn.attr('data-skill');
        var alias = $btn.attr('data-alias');
        var originHtml = $btn.html();

        $btn.addClass('installing').prop('disabled', true).html(SVG_SPIN_REFRESH);

        // 尝试安装：优先用当前选中的市场名，没有则让后端用默认市场
        var tryInstall = function(marketName) {
            var postData = { slug: slug, mountAlias: alias };
            if (marketName) postData.marketName = marketName;

            return $.ajax({
                url: '/web/settings/skills/install',
                method: 'POST',
                data: postData,
                timeout: 60000,
                dataType: 'json'
            });
        };

        // 先用当前市场尝试，失败则提示用户去市场搜索
        var marketName = _currentMarketName || '';

        $.when(tryInstall(marketName))
        .done(function (resp) {
            if (resp && resp.code === 200 && resp.data) {
                if (typeof layer !== 'undefined' && layer.msg) {
                    layer.msg(I18n.t('skills.upgraded', {name: escapeHtml((resp.data || slug) + '')}), { icon: 1, time: 2200, offset: '120px' });
                }
                _installedSkillsCache = null;
                if (typeof loadCommands === 'function') loadCommands();
                loadInstalledSkills();
            } else {
                var msg = (resp && (resp.description || resp.message)) || I18n.t('skills.upgradeFailed');
                // 如果错误提示技能不存在，给用户更友好的引导
                if (msg.indexOf('技能不存在') >= 0 || msg.indexOf('not found') >= 0) {
                    msg = I18n.t('skills.notFoundInMarket', {name: slug});
                }
                if (typeof layer !== 'undefined' && layer.msg) layer.msg(msg, { icon: 2, time: 3000, offset: '120px' });
                $btn.removeClass('installing').prop('disabled', false).html(originHtml);
            }
        })
        .fail(function (jqXHR) {
            var msg = I18n.t('skills.upgradeFailed');
            try {
                var err = JSON.parse(jqXHR.responseText);
                if (err && (err.description || err.message)) msg = err.description || err.message;
            } catch (ex) {
                if (jqXHR.status) msg = I18n.t('skills.upgradeFailedHttp', {n: jqXHR.status});
            }
            if (msg.indexOf('技能不存在') >= 0) {
                msg = I18n.t('skills.notFoundInMarket', {name: slug});
            }
            if (typeof layer !== 'undefined' && layer.msg) layer.msg(msg, { icon: 2, time: 3000, offset: '120px' });
            $btn.removeClass('installing').prop('disabled', false).html(originHtml);
        });
    });

    // 点击条目 → 打开所在目录
    $skillsInstalledList.on('click', '.mounts-skill-item', function (e) {
        if ($(e.target).closest('.settings-action-btn').length) return;
        if ($(e.target).closest('.toggle-switch').length) return;
        var realPath = $(this).attr('data-real-path') || '';
        if (!realPath) return;
        $.get('/web/settings/mounts/open', { path: realPath }, function (resp) {
            if (resp && resp.code !== 200 && typeof layer !== 'undefined' && layer.msg) {
                layer.msg(resp.message || I18n.t('skills.openDirFailed'), { icon: 2, time: 3000, offset: '120px' });
            }
        }).fail(function () {
            if (typeof layer !== 'undefined' && layer.msg) layer.msg(I18n.t('skills.openDirFailed'), { icon: 2, time: 3000, offset: '120px' });
        });
    });

    // ==================== 数据加载 ====================

    /**
     * 加载技能市场列表（分页）。
     *
     * @param {string|null} query - 搜索关键词，null 时浏览默认列表
     * @param {boolean}     more  - true 时加载下一页并追加，false/省略时从第一页重新加载
     */
    function loadSkillsList(query, more) {
        var append = more === true;
        if (append && (!_marketHasMore || _marketLoadingMore)) return;

        var seq = ++_marketReqSeq;
        _marketLoadingMore = append;

        if (append) {
            _marketPage++;
            loadMoreBusy(true);
        } else {
            _marketPage = 1;
            _marketQuery = query || null;
            _marketHasMore = false;
            _marketTotal = -1;
            $skillsStatus.show();
            $skillsLoading.css('display', 'flex');
            $skillsError.hide();
            removeMarketLoadMore();
            $skillsList.html('');
        }

        var url = SKILLS_API_BASE + '?q=' + encodeURIComponent(_marketQuery || '')
            + '&limit=' + MARKET_PAGE_SIZE
            + '&page=' + _marketPage
            + (_currentMarketName ? '&marketName=' + encodeURIComponent(_currentMarketName) : '')
            + (_skillCategoryKey ? '&category=' + encodeURIComponent(_skillCategoryKey) : '');

        $.ajax({
            url: url,
            method: 'GET',
            timeout: 15000,
            dataType: 'json'
        })
            .done(function (resp) {
                // 快速切分类/改关键词时，旧请求的响应可能后到，直接丢弃
                if (seq !== _marketReqSeq) return;
                $skillsLoading.hide();

                // 后端返回 Result 包装：{code:200, data:{items,hasMore,total}, description:""}
                // code !== 200 时为业务错误，展示后端返回的具体提示
                if (resp && resp.code !== undefined && resp.code !== 200) {
                    marketLoadFailed(append, (resp.description || I18n.t('skills.loadFailedRetry')));
                    return;
                }

                var data = (resp && resp.data !== undefined) ? resp.data : resp;
                // 兼容旧版后端（data 直接是数组，无分页信息）
                var skills = Array.isArray(data) ? data : (data.items || []);
                _marketHasMore = Array.isArray(data) ? false : !!data.hasMore;
                _marketTotal = (!Array.isArray(data) && typeof data.total === 'number') ? data.total : -1;

                getInstalledSkills(function (installedMap) {
                    if (seq !== _marketReqSeq) return;
                    renderSkillsList(skills, installedMap, append);
                    $skillsStatus.hide();
                });
            })
            .fail(function (jqXHR, textStatus) {
                if (seq !== _marketReqSeq) return;
                $skillsLoading.hide();
                var msg;
                if (textStatus === 'timeout') {
                    msg = I18n.t('skills.requestTimeout');
                } else if (jqXHR.status === 0) {
                    msg = I18n.t('skills.networkErrorConnect');
                } else if (jqXHR.status === 429) {
                    msg = I18n.t('skills.tooManyRequests');
                } else if (jqXHR.status >= 500) {
                    msg = I18n.t('skills.serverUnavailable', {n: jqXHR.status});
                } else {
                    msg = I18n.t('skills.networkErrorHttp', {n: (jqXHR.status || '?')});
                }
                marketLoadFailed(append, msg);
            })
            .always(function () {
                if (seq === _marketReqSeq) _marketLoadingMore = false;
            });
    }

    /**
     * 加载失败收尾：首屏失败显示错误块；翻页失败则回退页码并把「加载更多」按钮还原，列表已加载的内容保留。
     */
    function marketLoadFailed(append, msg) {
        _marketLoadingMore = false;

        if (append) {
            _marketPage = Math.max(_marketPage - 1, 1);
            loadMoreBusy(false);
            showToast(msg, 'error');
        } else {
            $skillsError.text(msg).show();
        }
    }

    /** 切换「加载更多」按钮的忙碌态（找不到按钮时静默忽略） */
    function loadMoreBusy(busy) {
        var $btn = $skillsList.find('.skills-market-load-more');
        if (!$btn.length) return;

        if (busy) {
            $btn.prop('disabled', true).text(I18n.t('skills.loadingMore'));
        } else {
            $btn.prop('disabled', false).text(loadMoreText());
        }
    }

    /** 「加载更多」按钮文案：知道总数时带上剩余个数，否则用普通文案 */
    function loadMoreText() {
        var loaded = $skillsList.find('.settings-list-item').length;
        if (_marketTotal > loaded) {
            return I18n.t('skills.loadMore', {n: _marketTotal - loaded});
        }
        return I18n.t('skills.marketLoadMore');
    }

    function appendMarketLoadMore() {
        // 先清掉旧按钮：没有下一页时必须把之前那个「加载更多」收起来
        removeMarketLoadMore();
        if (!_marketHasMore) return;

        $skillsList.append('<div class="skills-load-more-wrap" style="text-align:center;padding:12px;">'
            + '<button type="button" class="skills-load-more-btn skills-market-load-more" style="min-width:200px;">'
            + loadMoreText()
            + '</button></div>');
    }

    function removeMarketLoadMore() {
        $skillsList.find('.skills-load-more-wrap').remove();
    }

    // ==================== 渲染 ====================

    /**
     * 重复图标判定阈值：同一个图标地址在当次列表里出现达到该次数，即视为平台默认占位图。
     * 例如 SkillHub 上有若干条目共用同一张腾讯云默认图，直接用会让列表看起来像坏图。
     */
    var REPEATED_ICON_THRESHOLD = 3;

    /** 统计本次列表内各图标地址的出现次数（key 为图标地址，值为次数） */
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
     * 条目图标：优先用市场返回的 iconUrl（经后端代理加载，前端不直连远端域名）；
     * 无图标、图标疑似占位图、或图片拉取/解析失败时，回退为名称首字母文字图标。
     */
    function skillIconHtml(iconUrl, iconText, placeholder) {
        if (!iconUrl || placeholder) {
            return '<div class="settings-list-icon">' + escapeHtml(iconText) + '</div>';
        }

        var proxied = '/web/settings/skills/market/icon?url=' + encodeURIComponent(iconUrl);
        return '<div class="settings-list-icon has-img">'
            + '<img src="' + escapeAttr(proxied) + '" alt="" loading="lazy"'
            + ' onerror="this.parentNode.classList.remove(\'has-img\');this.parentNode.classList.add(\'img-failed\');this.remove();">'
            + '<span class="icon-fallback">' + escapeHtml(iconText) + '</span>'
            + '</div>';
    }

    function renderSkillsList(skills, installedMap, append) {
        if (!skills || skills.length === 0) {
            if (append) {
                // 翻页拿到空页：保留已加载内容，只收起「加载更多」
                removeMarketLoadMore();
                return;
            }

            $skillsList.html(
                '<div class="skill-empty-state">'
                + '<svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5">'
                + '<polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/>'
                + '</svg>'
                + '<div style="font-size:13px;margin-top:12px;">' + I18n.t('skills.noResult') + '</div>'
                + '</div>'
            );
            return;
        }

        // 先统计本批图标出现次数，用于识别平台默认占位图（按批统计：分页追加时只看本页，
        // 跨页才凑够阈值的占位图不会回头修正已渲染的那几条，代价是要重渲染整个列表）
        var iconCounts = countIcons(skills.map(function (skill) {
            return skill.iconUrl || (skill.owner && skill.owner.image) || '';
        }));

        var html = '';
        skills.forEach(function (skill) {
            var name = skill.slug || skill.name || '';
            var displayName = skill.displayName || name;
            var desc = skill.summary || skill.description || '';
            var owner = skill.ownerHandle || (skill.owner && skill.owner.handle) || '';
            var source = owner ? owner + '/' + name : name;
            var installs = skill.installs || (skill.stats && skill.stats.installsCurrent) || 0;
            var stars = skill.stars || (skill.stats && skill.stats.stars) || 0;
            var version = skill.version || '';
            var isInstalled = !!installedMap[name];
            var iconText = displayName ? displayName.substring(0, 2).toUpperCase() : 'SK';
            var shortDesc = desc && Array.from(desc).length > 60 ? Array.from(desc).slice(0, 60).join('') + '...' : desc;

            var skillUrl = skill.url || '';
            var iconUrl = skill.iconUrl || (skill.owner && skill.owner.image) || '';

            // 行不可点（预览已移除），只有右侧安装按钮与「打开外部页面」按钮可交互
            html += '<div class="settings-list-item is-static">'
                + skillIconHtml(iconUrl, iconText, isPlaceholderIcon(iconUrl, iconCounts))
                + '<div class="settings-list-info">'
                + '<div class="settings-list-title" title="' + escapeAttr(name) + '">' + escapeHtml(displayName) + (isInstalled ? '<span class="skill-installed-badge">' + I18n.t('skills.installedBadge') + '</span>' : '') + '</div>'
                + (shortDesc ? '<div class="settings-list-desc" title="' + escapeAttr(desc) + '">' + escapeHtml(shortDesc) + '</div>' : '')
                + '<div class="skill-item-meta">'
                + (version ? '<span class="skill-item-version">v' + escapeHtml(version.replace(/^v/i, '')) + '</span>' : '')
                + (installs > 0 ? '<span>' + I18n.t('skills.installCount', {n: (installs >= 1000 ? (installs / 1000).toFixed(1) + 'k' : installs)}) + '</span>' : '')
                + (stars > 0 ? '<span>⭐ ' + (stars >= 1000 ? (stars / 1000).toFixed(1) + 'k' : stars) + '</span>' : '')
                + (owner ? '<span>' + escapeHtml(owner) + '</span>' : '')
                + (skillUrl ? '<button type="button" class="skill-item-detail-link" data-url="' + escapeAttr(skillUrl) + '" title="' + I18n.t('skills.openExternal') + '">↗</button>' : '')
                + '</div></div>'
                + '<div class="settings-list-actions">'
                    + (isInstalled
                        ? '<div class="skill-install-wrap">'
                    +   '<button class="skill-install-btn skill-reinstall-btn installed" data-slug="' + escapeAttr(name) + '" data-display="' + escapeAttr(displayName) + '" data-market="' + escapeAttr(_currentMarketName) + '" data-mount-alias="' + escapeAttr(installedMap[name]) + '" title="' + I18n.t('skills.reinstallTitle') + '">' + SVG_REFRESH + '</button>'
                    + '</div>'
                        : '<div class="skill-install-wrap">'
                    +   '<button class="skill-install-btn" data-slug="' + escapeAttr(name) + '" data-display="' + escapeAttr(displayName) + '" data-market="' + escapeAttr(_currentMarketName) + '" title="' + I18n.t('skills.installTo') + '">' + SVG_DOWNLOAD + '</button>'
                    +   '<div class="skill-install-dropdown" data-slug="' + escapeAttr(name) + '" data-display="' + escapeAttr(displayName) + '" data-market="' + escapeAttr(_currentMarketName) + '">'
                    +     '<div class="skill-install-dropdown-loading">' + I18n.t('skills.loading') + '</div>'
                    +   '</div>'
                    + '</div>')
                + '</div></div>';
        });
        if (append) {
            $skillsList.append(html);
        } else {
            $skillsList.html(html);
        }
        appendMarketLoadMore();
    }

    // ==================== 事件绑定 ====================

    // 市场切换
    $skillsMarketSelect.on('change', function () {
        _currentMarketName = $(this).val() || '';
        _installedSkillsCache = null;
        _skillCategoryKey = '';
        loadCategories();
        loadSkillsList(null);
    });

    // 下拉菜单延时关闭管理（防止鼠标在按钮和下拉之间移动时闪烁）
    var _dropdownCloseTimer = null;

    function openDropdown($wrap) {
        clearTimeout(_dropdownCloseTimer);
        var $dropdown = $wrap.find('.skill-install-dropdown');
        // 已有选项直接显示
        if ($dropdown.find('.skill-install-mount-option').length) {
            $dropdown.addClass('active');
            return;
        }
        loadMountPools(function (pools) {
            var html = '';
            pools.forEach(function (p) {
                html += '<div class="skill-install-mount-option" data-alias="' + escapeAttr(p.alias) + '">'
                    + escapeHtml(p.alias)
                    + '</div>';
            });
            $dropdown.html(html).addClass('active');
        });
    }

    function closeDropdown($wrap) {
        clearTimeout(_dropdownCloseTimer);
        _dropdownCloseTimer = setTimeout(function () {
            $wrap.find('.skill-install-dropdown').removeClass('active');
        }, 150);
    }

    // 鼠标进入按钮区域 → 打开下拉
    $skillsList.on('mouseenter', '.skill-install-wrap', function () {
        openDropdown($(this));
    });

    // 鼠标进入下拉菜单本身 → 取消关闭
    $skillsList.on('mouseenter', '.skill-install-dropdown', function () {
        clearTimeout(_dropdownCloseTimer);
    });

    // 鼠标离开整个 wrap 区域 → 延时关闭下拉
    $skillsList.on('mouseleave', '.skill-install-wrap', function () {
        closeDropdown($(this));
    });

    // 触屏设备降级：点击按钮切换下拉
    $skillsList.on('click', '.skill-install-btn:not(.installed)', function (e) {
        e.stopPropagation();
        var $wrap = $(this).closest('.skill-install-wrap');
        var $dropdown = $wrap.find('.skill-install-dropdown');
        // 关闭其他下拉
        $('.skill-install-dropdown').not($dropdown).removeClass('active');
        // 如果还没填充过选项，先填充
        if (!$dropdown.find('.skill-install-mount-option').length) {
            loadMountPools(function (pools) {
                var html = '';
                pools.forEach(function (p) {
                    html += '<div class="skill-install-mount-option" data-alias="' + escapeAttr(p.alias) + '">'
                        + escapeHtml(p.alias)
                        + '</div>';
                });
                $dropdown.html(html).toggleClass('active');
            });
        } else {
            $dropdown.toggleClass('active');
        }
    });

    // 点击重新安装按钮，直接使用原挂载点升级（不需要选下拉）
    $skillsList.on('click', '.skill-reinstall-btn', function (e) {
        e.stopPropagation();
        var $btn = $(this);
        var slug = $btn.attr('data-slug');
        var displayName = $btn.attr('data-display') || slug;
        var marketUrl = $btn.attr('data-market') || '';
        var mountAlias = $btn.attr('data-mount-alias');

        if (!mountAlias || mountAlias === 'true') {
            if (typeof layer !== 'undefined' && layer.msg) {
                layer.msg(I18n.t('skills.cannotGetLocation'), {icon: 2, time: 3000, offset: '120px'});
            }
            return;
        }

        // 开始安装（覆盖升级）
        $btn.addClass('installing').html(SVG_SPIN_REFRESH).prop('disabled', true);

        var postData = { slug: slug, mountAlias: mountAlias };
        if (marketUrl) postData.marketName = marketUrl;

        $.ajax({
            url: '/web/settings/skills/install',
            method: 'POST',
            data: postData,
            timeout: 60000,
            dataType: 'json'
        })
        .done(function (resp) {
            var isSuccess = resp && resp.code === 200 && resp.data;
            if (isSuccess) {
                var skillName = (resp.data || slug) + '';
                showToast(I18n.t('skills.upgradeOk', {name: escapeHtml(skillName)}), 'success');
                $btn.removeClass('installing').html(SVG_REFRESH).prop('disabled', false);
                _installedSkillsCache = null;  // 市场已安装徽章需重算
                _installedDirty = true;   // 已安装列表需重载
                if (typeof loadCommands === 'function') loadCommands();
            } else {
                var msg = (resp && (resp.description || resp.message)) || I18n.t('skills.upgradeFailed');
                $btn.removeClass('installing').html(SVG_REFRESH).prop('disabled', false);
                showToast(msg, 'error');
            }
        })
        .fail(function (jqXHR) {
            $btn.removeClass('installing').html(SVG_REFRESH).prop('disabled', false);
            var msg = I18n.t('skills.upgradeFailed');
            try {
                var err = JSON.parse(jqXHR.responseText);
                if (err && err.description) msg = err.description;
                else if (err && err.data) msg = err.data;
            } catch (e) {
                if (jqXHR.status) msg = I18n.t('skills.upgradeFailedHttp', {n: jqXHR.status});
            }
            showToast(msg, 'error');
        });
    });

    // 点击挂载选项，执行安装
    $skillsList.on('click', '.skill-install-mount-option', function (e) {
        e.stopPropagation();
        var $option = $(this);
        var $dropdown = $option.closest('.skill-install-dropdown');
        var slug = $dropdown.attr('data-slug');
        var displayName = $dropdown.attr('data-display') || slug;
        var marketUrl = $dropdown.attr('data-market') || '';
        var mountAlias = $option.attr('data-alias');

        var $btn = $dropdown.closest('.skill-install-wrap').find('.skill-install-btn');

        // 开始安装
        $btn.addClass('installing').html(SVG_SPIN_DOWNLOAD).prop('disabled', true);
        $dropdown.removeClass('active');

        var postData = { slug: slug, mountAlias: mountAlias };
        if (marketUrl) postData.marketName = marketUrl;

        $.ajax({
            url: '/web/settings/skills/install',
            method: 'POST',
            data: postData,
            timeout: 60000,
            dataType: 'json'
        })
        .done(function (resp) {
            var isSuccess = resp && resp.code === 200 && resp.data;
            if (isSuccess) {
                var skillName = (resp.data || slug) + '';
                var $item = $btn.closest('.settings-list-item');
                var $nameEl = $item.find('.settings-list-title');
                if (!$nameEl.find('.skill-installed-badge').length) {
                    $nameEl.append('<span class="skill-installed-badge">' + I18n.t('skills.installedBadge') + '</span>');
                }
                $btn.closest('.skill-install-wrap').remove();
                if (!_installedSkillsCache) _installedSkillsCache = {};
                _installedSkillsCache[slug] = mountAlias || true;
                _installedDirty = true;   // 已安装列表需重载
                if (typeof loadCommands === 'function') loadCommands();
                showToast(I18n.t('skills.installOk', {name: escapeHtml(skillName)}), 'success');
            } else {
                var msg = (resp && (resp.description || resp.message)) || I18n.t('skills.installFailed');
                $btn.removeClass('installing').html(SVG_DOWNLOAD).prop('disabled', false);
                showToast(msg, 'error');
            }
        })
        .fail(function (jqXHR) {
            $btn.removeClass('installing').html(SVG_DOWNLOAD).prop('disabled', false);
            var msg = I18n.t('skills.installFailed');
            try {
                var err = JSON.parse(jqXHR.responseText);
                if (err && err.description) msg = err.description;
                else if (err && err.data) msg = err.data;
            } catch (e) {
                if (jqXHR.status) msg = I18n.t('skills.installFailedHttp', {n: jqXHR.status});
            }
            showToast(msg, 'error');
        });
    });

    // 点击页面其他区域关闭所有下拉
    $(document).on('click', function () {
        $('.skill-install-dropdown').removeClass('active');
    });

    // 「打开外部页面」按钮：行本身不可点，只有这个按钮跳转市场页面
    $skillsList.on('click', '.skill-item-detail-link', function (e) {
        e.stopPropagation();
        var url = $(this).attr('data-url');
        if (url) window.open(url, '_blank');
    });

    // 分类切换
    $skillsMarketCategories.on('click', '.mcp-category-chip', function () {
        var $chip = $(this);
        if ($chip.hasClass('active')) return;

        $skillsMarketCategories.find('.mcp-category-chip').removeClass('active');
        $chip.addClass('active');
        _skillCategoryKey = $chip.attr('data-key') || '';

        // 切分类时保留当前关键词，回到“全部”则按当前关键词重查
        loadSkillsList($skillsSearchInput.val().trim() || null);
    });

    // 市场列表「加载更多」：沿用当前关键词与分类，追加下一页
    $skillsList.on('click', '.skills-market-load-more', function (e) {
        e.stopPropagation();
        loadSkillsList(_marketQuery, true);
    });

    // 搜索输入（防抖即时搜索 + 回车立即搜索）
    var _marketSearchTimer = null;
    $skillsSearchInput.on('input', function () {
        var val = $(this).val().trim();
        $skillsSearchClear.toggle(val.length > 0);
        clearTimeout(_marketSearchTimer);
        _marketSearchTimer = setTimeout(function () {
            loadSkillsList(val || null);
        }, 350);
    }).on('keydown', function (e) {
        if (e.key === 'Enter' && !isInputComposing(e)) {
            e.preventDefault();
            clearTimeout(_marketSearchTimer);
            var val = $(this).val().trim();
            loadSkillsList(val || null);
        }
    });

    // 清除搜索
    $skillsSearchClear.on('click', function () {
        $skillsSearchInput.val('').focus();
        $(this).hide();
        loadSkillsList(null);
    });

    // 刷新按钮
    $('#skillsRefreshBtn').on('click', function () {
        var $btn = $(this);
        $btn.prop('disabled', true).addClass('is-loading');
        _installedSkillsCache = null;
        _mountPoolsCache = null;
        _marketLoaded = false;
        _installedDirty = true;
        _installedGroups = [];
        switchView(_currentView === 'market' ? 'market' : 'installed');
        if (_currentView === 'market') {
            _marketLoaded = true;
            loadMarketOptions();
            loadSkillsList(null);
        } else {
            loadInstalledSkills();
        }
        setTimeout(function () { $btn.prop('disabled', false).removeClass('is-loading'); }, 800);
    });

    // ==================== 暴露给外部调用的接口 ====================

    // 供 app-settings.js Tab 切换和面板初始化时调用
    window._skillModule = {
        /**
         * 重置缓存并加载。默认落在「已安装」子视图，
         * 市场列表懒加载（首次切过去时才请求外网）。
         */
        resetAndLoad: function (view) {
            _installedSkillsCache = null;
            _mountPoolsCache = null;
            _marketLoaded = false;
            _installedDirty = true;
            _installedGroups = [];
            $skillsInstalledFilter.val('');
            $skillsInstalledFilterClear.hide();
            // 「添加」按钮从选择器跳进来时直接落到技能市场（选视图就是想去装新的），否则回默认
            switchView(view === 'market' ? 'market' : 'installed');
        }
    };

})();

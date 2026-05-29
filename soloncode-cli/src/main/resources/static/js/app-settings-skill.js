/**
 * app-settings-skill.js — 技能市场交互逻辑（所有 API 调用均走后端代理）
 *
 * 依赖：layui.js（jQuery）、app-base.js、app-settings.js（escapeHtml/escapeAttr 全局共享）
 * 协同：app-history.js（commandList / loadCommands）
 *
 * 后端接口：
 *   GET  /web/settings/skills/markets                     — 获取可用市场列表
 *   GET  /web/settings/skills/proxy?action=trending       — 热门技能列表
 *   GET  /web/settings/skills/proxy?action=search&q=xxx   — 搜索技能
 *   POST /web/settings/skills/install  {slug, marketUrl}  — 安装技能
 */

(function () {
    'use strict';

    // ==================== 常量 ====================

    var SKILLS_API_BASE = '/web/settings/skills/proxy';

    // ==================== DOM 引用 ====================

    var $skillsMarketSelect = $('#skillsMarketSelect');
    var $skillsSearchInput = $('#skillsSearchInput');
    var $skillsSearchClear = $('#skillsSearchClear');
    var $skillsList = $('#skillsList');
    var $skillsLoading = $('#skillsLoading');
    var $skillsError = $('#skillsError');
    var $skillsStatus = $('#skillsStatus');

    // ==================== 状态 ====================

    var _installedSkillsCache = null;
    var _skillsSearchTimer = null;
    var _currentMarketUrl = '';  // 当前选中的市场URL

    // ==================== 工具函数 ====================

    /** HTML 转义（与 app-settings.js 共享同一个闭包作用域不可用，自备一份） */
    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function escapeAttr(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/'/g, '&#39;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
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
                var label = escapeHtml(m.name || m.url);
                var desc = m.description ? ' — ' + escapeHtml(m.description) : '';
                html += '<option value="' + escapeAttr(m.url) + '">' + label + desc + '</option>';
            });
            $skillsMarketSelect.html(html);

            // 默认选中第一个
            _currentMarketUrl = markets[0].url || '';
            $skillsMarketSelect.val(_currentMarketUrl);
        }).fail(function () {
            // 加载失败时使用默认空值
            $skillsMarketSelect.html('<option value="">ClawHub</option>');
            _currentMarketUrl = '';
        });
    }

    // ==================== 已安装技能 ====================

    function getInstalledSkills(callback) {
        if (typeof commandList !== 'undefined' && commandList.length > 0) {
            if (!_installedSkillsCache) {
                _installedSkillsCache = {};
                commandList.forEach(function (item) {
                    if (item.type === 'skill') _installedSkillsCache[item.name] = true;
                });
            }
            callback(_installedSkillsCache);
            return;
        }
        $.get('/web/chat/hints', function (resp) {
            _installedSkillsCache = {};
            (resp.data || []).forEach(function (item) {
                if (item.type === 'skill') _installedSkillsCache[item.name] = true;
            });
            callback(_installedSkillsCache);
        }).fail(function () {
            _installedSkillsCache = {};
            callback(_installedSkillsCache);
        });
    }

    // ==================== 数据加载 ====================

    /**
     * 加载技能列表
     * @param {string|null} query - 搜索关键词，null 时加载热门技能
     */
    function loadSkillsList(query) {
        $skillsStatus.show();
        $skillsLoading.css('display', 'flex');
        $skillsError.hide();
        $skillsList.html('');

        var url;
        var marketParam = _currentMarketUrl ? '&marketUrl=' + encodeURIComponent(_currentMarketUrl) : '';
        if (query) {
            url = SKILLS_API_BASE + '?action=search&q=' + encodeURIComponent(query) + '&limit=50' + marketParam;
        } else {
            url = SKILLS_API_BASE + '?action=trending&limit=50' + marketParam;
        }

        $.ajax({
            url: url,
            method: 'GET',
            timeout: 15000,
            dataType: 'json'
        })
            .done(function (resp) {
                // 后端返回 Result 包装：{code:200, data:[...], description:""}
                var payload = resp;
                if (resp && resp.code !== undefined && resp.data !== undefined) {
                    payload = resp.data;
                }

                // 后端 Market 适配器已统一返回 MarketItem 列表
                var skills = [];
                if (Array.isArray(payload)) {
                    skills = payload;
                }

                getInstalledSkills(function (installedMap) {
                    renderSkillsList(skills, installedMap);
                    $skillsStatus.hide();
                });
            })
            .fail(function (jqXHR, textStatus) {
                $skillsLoading.hide();
                var msg;
                if (textStatus === 'timeout') {
                    msg = '请求超时，请检查网络连接';
                } else if (jqXHR.status === 0) {
                    msg = '网络错误，无法连接服务器';
                } else if (jqXHR.status === 429) {
                    msg = '请求过于频繁，请稍后再试';
                } else if (jqXHR.status >= 500) {
                    msg = '服务暂时不可用（HTTP ' + jqXHR.status + '）';
                } else {
                    msg = '网络错误（HTTP ' + (jqXHR.status || '?') + '）';
                }
                $skillsError.text(msg).show();
            });
    }

    // ==================== 渲染 ====================

    function renderSkillsList(skills, installedMap) {
        if (!skills || skills.length === 0) {
            $skillsList.html(
                '<div class="skill-empty-state">'
                + '<svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5">'
                + '<polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/>'
                + '</svg>'
                + '<div style="font-size:13px;margin-top:12px;">暂无结果</div>'
                + '</div>'
            );
            return;
        }

        var html = '';
        skills.forEach(function (skill) {
            var name = skill.slug || skill.name || '';
            var displayName = skill.displayName || name;
            var desc = skill.summary || skill.description || '';
            var owner = skill.ownerHandle || (skill.owner && skill.owner.handle) || '';
            var source = owner ? owner + '/' + name : name;
            var installs = skill.installs || (skill.stats && skill.stats.installsCurrent) || 0;
            var stars = skill.stars || (skill.stats && skill.stats.stars) || 0;
            var isInstalled = !!installedMap[name];
            var iconText = displayName ? displayName.substring(0, 2).toUpperCase() : 'SK';
            var shortDesc = desc && desc.length > 60 ? desc.substring(0, 60) + '...' : desc;

            html += '<div class="skill-item">'
                + '<div class="skill-item-icon">' + escapeHtml(iconText) + '</div>'
                + '<div class="skill-item-info">'
                + '<div class="skill-item-name" title="' + escapeAttr(name) + '">' + escapeHtml(displayName) + '</div>'
                + (shortDesc ? '<div class="skill-item-desc" title="' + escapeAttr(desc) + '">' + escapeHtml(shortDesc) + '</div>' : '')
                + '<div class="skill-item-meta">'
                + (installs > 0 ? '<span>' + (installs >= 1000 ? (installs / 1000).toFixed(1) + 'k' : installs) + ' 安装</span>' : '')
                + (stars > 0 ? '<span>⭐ ' + (stars >= 1000 ? (stars / 1000).toFixed(1) + 'k' : stars) + '</span>' : '')
                + (owner ? '<span>' + escapeHtml(owner) + '</span>' : '')
                + '</div></div>'
                + '<div class="skill-item-actions">'
                + (isInstalled
                    ? '<button class="skill-install-btn installed" disabled>已安装</button>'
                    : '<button class="skill-install-btn" data-slug="' + escapeAttr(name) + '" data-market="' + escapeAttr(_currentMarketUrl) + '">安装</button>')
                + '</div></div>';
        });
        $skillsList.html(html);
    }

    // ==================== 事件绑定 ====================

    // 市场切换
    $skillsMarketSelect.on('change', function () {
        _currentMarketUrl = $(this).val() || '';
        _installedSkillsCache = null;
        loadSkillsList(null);
    });

    // 安装按钮（事件委托）
    $skillsList.on('click', '.skill-install-btn:not(.installed)', function () {
        var $btn = $(this);
        var slug = $btn.attr('data-slug');
        var marketUrl = $btn.attr('data-market') || '';

        $btn.addClass('installing').text('安装中...').prop('disabled', true);

        var postData = { slug: slug };
        if (marketUrl) postData.marketUrl = marketUrl;

        $.ajax({
            url: '/web/settings/skills/install',
            method: 'POST',
            data: postData,
            timeout: 60000,
            dataType: 'json'
        })
            .done(function (resp) {
                if (resp && resp.code === 200) {
                    var skillName = (resp.data || slug) + '';
                    $btn.removeClass('installing').addClass('installed').text('已安装').prop('disabled', true);
                    if (!_installedSkillsCache) _installedSkillsCache = {};
                    _installedSkillsCache[slug] = true;
                    if (typeof loadCommands === 'function') loadCommands();
                    if (typeof layer !== 'undefined' && layer.msg) {
                        layer.msg('技能「' + escapeHtml(skillName) + '」安装成功！', {icon: 1, time: 2500, offset: '120px'});
                    } else {
                        alert('技能「' + skillName + '」安装成功！');
                    }
                } else {
                    var msg = (resp && resp.description) ? resp.description : '安装失败，请稍后重试';
                    $btn.removeClass('installing').text('安装').prop('disabled', false);
                    if (typeof layer !== 'undefined' && layer.msg) {
                        layer.msg(msg, {icon: 2, time: 3000, offset: '120px'});
                    } else {
                        alert(msg);
                    }
                }
            })
            .fail(function (jqXHR) {
                $btn.removeClass('installing').text('安装').prop('disabled', false);
                var msg = '安装失败，请稍后重试';
                try {
                    var err = JSON.parse(jqXHR.responseText);
                    if (err && err.description) msg = err.description;
                    else if (err && err.data) msg = err.data;
                } catch (e) {
                    if (jqXHR.status) msg = '安装失败 (HTTP ' + jqXHR.status + ')';
                }
                if (typeof layer !== 'undefined' && layer.msg) {
                    layer.msg(msg, {icon: 2, time: 3000, offset: '120px'});
                } else {
                    alert(msg);
                }
            });
    });

    // 搜索输入（按回车键搜索）
    $skillsSearchInput.on('input', function () {
        var val = $(this).val().trim();
        $skillsSearchClear.toggle(val.length > 0);
    }).on('keydown', function (e) {
        if (e.key === 'Enter') {
            e.preventDefault();
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

    // ==================== 暴露给外部调用的接口 ====================

    // 供 app-settings.js Tab 切换和面板初始化时调用
    window._skillModule = {
        /** 重置缓存并加载技能列表 */
        resetAndLoad: function () {
            _installedSkillsCache = null;
            loadMarketOptions();
            loadSkillsList(null);
        }
    };

})();

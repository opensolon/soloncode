/**
 * app-settings-skill.js — 技能市场交互逻辑（直接调用 skills.sh API）
 *
 * 依赖：layui.js（jQuery）、app-base.js、app-settings.js（escapeHtml/escapeAttr 全局共享）
 * 协同：app-history.js（commandList / loadCommands）
 *
 * skills.sh API 参考：
 *   GET /api/v1/skills          — 技能排行榜（trending / hot / all-time）
 *   GET /api/v1/skills/search   — 搜索技能（q=关键词，limit=数量）
 *   GET /api/v1/skills/curated  — 官方精选技能
 */

(function () {
    'use strict';

    // ==================== 常量 ====================

    var SKILLS_API_BASE = '/web/settings/skills/proxy';

    // ==================== DOM 引用 ====================

    var $skillsSearchInput = $('#skillsSearchInput');
    var $skillsSearchClear = $('#skillsSearchClear');
    var $skillsList = $('#skillsList');
    var $skillsLoading = $('#skillsLoading');
    var $skillsError = $('#skillsError');
    var $skillsStatus = $('#skillsStatus');

    // ==================== 状态 ====================

    var _installedSkillsCache = null;
    var _skillsSearchTimer = null;

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
        if (query) {
            // 搜索模式：/web/settings/skills/proxy?action=search&q=xxx&limit=50
            url = SKILLS_API_BASE + '?action=search&q=' + encodeURIComponent(query) + '&limit=50';
        } else {
            // 默认模式：展示热门技能
            url = SKILLS_API_BASE + '?action=trending&per_page=50';
        }

        $.ajax({
            url: url,
            method: 'GET',
            timeout: 15000,
            dataType: 'json'
        })
            .done(function (resp) {
                // skills.sh API 直接返回 JSON，格式：
                // 搜索: { data: [...], query, searchType, count, durationMs }
                // 列表: { data: [...], pagination: { page, perPage, total, hasMore } }
                var skills = [];
                if (resp && resp.data && Array.isArray(resp.data)) {
                    skills = resp.data;
                } else if (Array.isArray(resp)) {
                    skills = resp;
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
                    msg = '网络错误，无法连接 skills.sh（可能被 CORS 策略阻止或网络不可达）';
                } else if (jqXHR.status === 429) {
                    msg = 'skills.sh 请求过于频繁，请稍后再试';
                } else if (jqXHR.status >= 500) {
                    msg = 'skills.sh 服务暂时不可用（HTTP ' + jqXHR.status + '）';
                } else {
                    msg = '网络错误，无法连接 skills.sh（HTTP ' + (jqXHR.status || '?') + '）';
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
            var name = skill.name || '';
            var desc = skill.description || '';
            // skills.sh API 的安装来源字段
            var source = skill.source || '';
            var installUrl = skill.installUrl || '';
            // 如果 installUrl 为空，尝试从 source 拼装
            if (!installUrl && source) {
                installUrl = source;
            }
            var installs = skill.installs || 0;
            var isInstalled = !!installedMap[name];
            var iconText = name ? name.substring(0, 2).toUpperCase() : 'SK';
            var shortDesc = desc && desc.length > 60 ? desc.substring(0, 60) + '...' : desc;

            html += '<div class="skill-item">'
                + '<div class="skill-item-icon">' + escapeHtml(iconText) + '</div>'
                + '<div class="skill-item-info">'
                + '<div class="skill-item-name" title="' + escapeAttr(name) + '">' + escapeHtml(name) + '</div>'
                + (shortDesc ? '<div class="skill-item-desc" title="' + escapeAttr(desc) + '">' + escapeHtml(shortDesc) + '</div>' : '')
                + '<div class="skill-item-meta">'
                + (installs > 0 ? '<span>' + (installs >= 1000 ? (installs / 1000).toFixed(1) + 'k' : installs) + ' 安装</span>' : '')
                + (source ? '<span>' + escapeHtml(source.split('/').pop()) + '</span>' : '')
                + '</div></div>'
                + '<div class="skill-item-actions">'
                + (isInstalled
                    ? '<button class="skill-install-btn installed" disabled>已安装</button>'
                    : '<button class="skill-install-btn" data-install-url="' + escapeAttr(installUrl) + '">安装</button>')
                + '</div></div>';
        });
        $skillsList.html(html);
    }

    // ==================== 事件绑定 ====================

    // 安装按钮（事件委托）
    $skillsList.on('click', '.skill-install-btn:not(.installed)', function () {
        var $btn = $(this);
        var installUrl = $btn.attr('data-install-url');
        $btn.addClass('installing').text('安装中...').prop('disabled', true);

        $.post('/web/chat/input', { text: '/skills add ' + installUrl })
            .done(function () {
                $btn.removeClass('installing').addClass('installed').text('已安装').prop('disabled', true);
                _installedSkillsCache = null;
                if (typeof loadCommands === 'function') loadCommands();
            })
            .fail(function () {
                $btn.removeClass('installing').text('安装').prop('disabled', false);
                alert('安装失败');
            });
    });

    // 搜索输入（防抖 400ms）
    $skillsSearchInput.on('input', function () {
        var val = $(this).val().trim();
        $skillsSearchClear.toggle(val.length > 0);
        clearTimeout(_skillsSearchTimer);
        _skillsSearchTimer = setTimeout(function () {
            loadSkillsList(val || null);
        }, 400);
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
            loadSkillsList(null);
        }
    };

})();

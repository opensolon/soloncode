/**
 * app-capabilities.js — 会话级技能 / MCP 选择器
 *
 * 与模型选择器同理：选择写在会话上（后端 /web/chat/skills/select），随快照持久化，
 * 之后该会话的每一轮新消息都自动生效。切会话后打开下拉会重新拉取该会话的选择。
 *
 * 自动任务页（app-automation.js）复用本文件的 normalize / itemsHtml / label 等口径，
 * 保证两处的图标、条目标记、计数标签、失效态与「不限制」按钮完全一致。
 */
(function () {
    'use strict';

    // 图标与自动任务页共用同一份，避免两处各画一套
    var SVG_SKILL_ICON = '<svg class="toolbar-setting-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3l2.1 5.1L19 10l-4.9 1.9L12 17l-2.1-5.1L5 10l4.9-1.9z"/><path d="M18 16l.9 2.1L21 19l-2.1.9L18 22l-.9-2.1L15 19l2.1-.9z"/></svg>';
    var SVG_MCP_ICON = '<svg class="toolbar-setting-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 7V3M15 7V3"/><rect x="5" y="7" width="14" height="8" rx="2"/><path d="M12 15v3a3 3 0 0 0 6 0v-2"/></svg>';

    var DIMENSIONS = [
        {field: 'skills', itemsKey: 'skills'},
        {field: 'mcps', itemsKey: 'mcpServers'}
    ];

    var options = null;          // { skills: [...], mcpServers: [...] }
    var selection = { skills: null, mcps: null };  // null = 不限制
    var loadedSessionId = null;
    var widgets = [];

    function t(key, params) {
        return (window.I18n && window.I18n.t) ? window.I18n.t(key, params) : key;
    }

    function currentSessionId() {
        return (typeof window.SESSION_ID !== 'undefined' && window.SESSION_ID) || null;
    }

    function esc(text) {
        return String(text == null ? '' : text)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    /** 技能 id 形如 @agent-skills/agently-mail，列表里只显示短名，避免一行全是路径 */
    function shortName(id) {
        var s = String(id == null ? '' : id);
        var i = s.lastIndexOf('/');
        return i >= 0 ? s.slice(i + 1) : s;
    }

    /** 取作用域前缀（agent-skills → agent），用于同名技能消歧 */
    function scopeOf(id) {
        var m = /^@([^/]+)\//.exec(String(id == null ? '' : id));
        return m ? m[1].replace(/-skills$/, '') : '';
    }

    /**
     * 把后端选项归一化成列表项，两处选择器共用。
     * 技能用短名 + 作用域徽标，MCP 直接用名字。
     */
    function normalize(field, opts) {
        var dim = dimOf(field);
        var list = (opts && opts[dim.itemsKey]) || [];
        return list.map(function (item) {
            var value = field === 'skills' ? item.id : item.name;
            return {
                value: value,
                label: field === 'skills' ? shortName(value) : String(value == null ? '' : value),
                badge: field === 'skills' ? scopeOf(value) : '',
                desc: (field === 'skills' ? item.description : item.description) || '',
                enabled: item.enabled !== false
            };
        })
            // 已禁用（停用的技能）/ 未启用（停用的 MCP 服务）一律不进列表：不可选的东西不该占位置
            .filter(function (item) { return !!item.value && item.enabled; });
    }

    /** 从选择里剔掉当前不可用的项（历史选择残留、或选项已变化），返回值即引擎实际生效的那一份 */
    function filterAvailable(field, values, items) {
        var list = items || (options ? normalize(field, options) : null);
        // 选项还没拉回来时不做过滤，避免把选择误当成“全部失效”而清空
        if (!list) return (values || []).slice();
        var available = {};
        list.forEach(function (item) { available[item.value] = true; });
        return (values || []).filter(function (v) { return available[v]; });
    }

    function dimOf(field) {
        for (var i = 0; i < DIMENSIONS.length; i++) {
            if (DIMENSIONS[i].field === field) return DIMENSIONS[i];
        }
        return DIMENSIONS[0];
    }

    /** 触发器标签：空态只显示名称，有选择时追加数量（与模型 / 子代理选择器同口径） */
    function label(field, count) {
        var name = t(field === 'skills' ? 'chatcap.skills' : 'chatcap.mcp');
        return name + (count ? ' ' + count : '');
    }

    function searchPlaceholder(field) {
        return t(field === 'skills' ? 'chatcap.searchSkills' : 'chatcap.searchMcp');
    }

    function itemHtml(item, selected) {
        var active = selected.indexOf(item.value) >= 0;
        var tip = item.value + (item.desc ? '\n' + item.desc : '');

        return ''
            + '<div class="cap-item' + (active ? ' active' : '') + '"'
            + ' data-value="' + esc(item.value) + '"'
            + ' title="' + esc(tip) + '">'
            + '  <span class="cap-check">' + (active ? '✓' : '') + '</span>'
            + '  <span class="cap-item-main">'
            + '    <span class="cap-item-name">'
            + '      <span class="cap-item-text">' + esc(item.label) + '</span>'
            + (item.badge ? '<span class="cap-item-badge">' + esc(item.badge) + '</span>' : '')
            + '    </span>'
            + (item.desc ? '<span class="cap-item-desc">' + esc(item.desc) + '</span>' : '')
            + '  </span>'
            + '</div>';
    }

    /** 列表 HTML（含空态 / 无匹配），两处选择器共用 */
    function itemsHtml(items, selected, query) {
        var sel = selected || [];
        var q = String(query || '').toLowerCase().trim();
        var html = '';
        items.forEach(function (item) {
            if (q && (item.value + ' ' + item.label + ' ' + item.desc).toLowerCase().indexOf(q) < 0) return;
            html += itemHtml(item, sel);
        });
        if (html) return html;
        return '<div class="model-empty-state"><div class="model-empty-desc">'
            + esc(q ? t('chatcap.noMatch') : t('chatcap.empty')) + '</div></div>';
    }

    function selectedCount(field) {
        var list = selection[field];
        return list ? list.length : 0;
    }

    function buildWidget(dim, anchorSelector, suffix) {
        var $anchor = $(anchorSelector).closest('.toolbar-selector-group');
        if (!$anchor.length) return null;

        var id = 'cap-' + dim.field + (suffix ? '-' + suffix : '');
        var html = ''
            + '<div class="toolbar-selector-group model-selector-group cap-selector-group">'
            + icon(dim.field)
            + '  <div class="model-selector cap-selector" id="' + id + '">'
            + '    <div class="model-selector-current" id="' + id + '-current" tabindex="0" role="button">'
            + '      <span class="model-name" id="' + id + '-name"></span>'
            + '      <i class="layui-icon layui-icon-down model-arrow"></i>'
            + '    </div>'
            + '    <div class="model-dropdown cap-dropdown" id="' + id + '-dropdown">'
            + '      <div class="model-search-wrap">'
            + '        <input type="text" class="model-search-input" id="' + id + '-search"/>'
            + '      </div>'
            + '      <div class="model-dropdown-items"></div>'
            + '      <div class="model-dropdown-footer cap-footer">'
            + '        <button type="button" class="cap-clear"></button>'
            + '      </div>'
            + '    </div>'
            + '  </div>'
            + '</div>';

        $anchor.after(html);

        var widget = {
            dim: dim,
            $root: $('#' + id),
            $name: $('#' + id + '-name'),
            $dd: $('#' + id + '-dropdown'),
            $items: $('#' + id + '-dropdown .model-dropdown-items'),
            $search: $('#' + id + '-search')
        };
        widgets.push(widget);
        bind(widget);
        return widget;
    }

    function icon(field) {
        return field === 'skills' ? SVG_SKILL_ICON : SVG_MCP_ICON;
    }

    function itemsOf(field) {
        return normalize(field, options);
    }

    function render(widget) {
        var field = widget.dim.field;
        // 计数与勾选只看当前可用项：已禁用 / 未启用的不显示，也就不该算进标签
        var selected = filterAvailable(field, selection[field]);

        widget.$items.html(itemsHtml(itemsOf(field), selected, widget.$search.val()));

        // 标题只显示「技能」或「技能 3」，与模型选择器的短标签口径一致
        widget.$name.text(label(field, selected.length));
        if (selected.length) widget.$name.attr('title', selected.join('\n'));
        else widget.$name.removeAttr('title');
        widget.$root.toggleClass('has-selection', selected.length > 0);

        // 文案每次重绘时同步：语言包是异步加载的，不能在构建期就把 t() 结果烧进 DOM
        widget.$search.attr('placeholder', searchPlaceholder(field));
        widget.$dd.find('.cap-clear').text(t('chatcap.unrestricted')).prop('disabled', selected.length === 0);
    }

    /** 两套工具栏（新会话 / 会话中）共用同一份状态，必须一起重绘，否则切换视图后标签与实际不一致 */
    function renderAll() {
        widgets.forEach(render);
    }

    function refreshSelection(callback) {
        var sessionId = currentSessionId();
        if (!sessionId) { callback && callback(); return; }

        $.get('/web/chat/skills', { sessionId: sessionId })
            .done(function (res) {
                var data = (res && res.data) || {};
                selection.skills = data.skills && data.skills.length ? data.skills : null;
                selection.mcps = data.mcps && data.mcps.length ? data.mcps : null;
                loadedSessionId = sessionId;
                renderAll();
            })
            .always(function () {
                callback && callback();
            });
    }

    function save(field, previous) {
        var sessionId = currentSessionId();
        if (!sessionId) return;

        var payload = { sessionId: sessionId };
        var value = field && selection[field] != null ? filterAvailable(field, selection[field]) : null;
        payload[field] = value == null ? '' : value.join(',');

        $.post('/web/chat/skills/select', payload).fail(function (err) {
            // 保存失败则回到点击前的状态，避免界面显示已选中、引擎却不认
            console.error('Failed to save capability selection:', err);
            selection[field] = previous;
            renderAll();
            if (window.showToast) window.showToast(t('chatcap.saveFailed'), 'error');
        });
    }

    function toggle(widget, value) {
        var field = widget.dim.field;
        var previous = selection[field];
        var selected = previous ? previous.slice() : [];

        var index = selected.indexOf(value);
        if (index >= 0) {
            selected.splice(index, 1);
        } else {
            selected.push(value);
        }

        // 全取消与「不限制」等价：清空而非存空数组
        selection[field] = selected.length ? selected : null;
        renderAll();
        save(field, previous);
    }

    function clear(widget) {
        var field = widget.dim.field;
        if (selection[field] == null) return;
        var previous = selection[field];
        selection[field] = null;
        renderAll();
        save(field, previous);
    }

    /** 下拉默认向上展开、左对齐；贴到视口边缘时翻转，避免被裁掉 */
    function place(widget) {
        var dd = widget.$dd[0];
        widget.$dd.removeClass('edge-right flip-down');
        if (!dd || !dd.getBoundingClientRect) return;

        var rect = dd.getBoundingClientRect();
        if (rect.right > window.innerWidth - 8) widget.$dd.addClass('edge-right');
        if (rect.top < 8) widget.$dd.addClass('flip-down');
    }

    function bind(widget) {
        var $root = widget.$root;

        $root.on('click', '.model-selector-current', function (e) {
            e.stopPropagation();
            var opening = !$root.hasClass('open');
            // 输入面板里只允许一个浮层：顺带关掉模型 / 子代理 / 更多菜单与另一个选择器
            if (typeof window.closeAllToolbarPanels === 'function') {
                window.closeAllToolbarPanels();
            } else {
                $('.model-selector.cap-selector').removeClass('open');
            }

            if (opening) {
                $root.addClass('open');
                // 切会话后第一次打开：重新拉取该会话的选择
                if (loadedSessionId !== currentSessionId()) {
                    refreshSelection();
                } else {
                    render(widget);
                }
                place(widget);
            }
        });

        $root.on('click', '.model-dropdown', function (e) {
            e.stopPropagation();
        });

        $root.on('click', '.cap-item', function () {
            if ($(this).attr('data-disabled')) return;
            toggle(widget, $(this).attr('data-value'));
        });

        $root.on('click', '.cap-clear', function () {
            clear(widget);
        });

        $root.on('input', '.model-search-input', function () {
            render(widget);
        });

        $root.on('keydown', '.model-selector-current', function (e) {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                $(this).trigger('click');
            }
        });
    }

    function init() {
        $.get('/web/chat/skills/options')
            .done(function (res) {
                options = (res && res.data) || {};

                // 两套工具栏（新会话 / 会话中）都放一份，状态共用。
                // 逐个 after() 插入到同一锚点后面，所以倒序遍历，DOM 里才是「技能、MCP」的顺序
                var anchors = [
                    { selector: '#chatModelSelector', suffix: '' },
                    { selector: '#newChatModelSelector', suffix: 'new' }
                ];

                anchors.forEach(function (anchor) {
                    DIMENSIONS.slice().reverse().forEach(function (dim) {
                        buildWidget(dim, anchor.selector, anchor.suffix);
                    });
                });

                refreshSelection();
            })
            .fail(function (err) {
                console.error('Failed to load capability options:', err);
            });

        $(document).on('click', function () {
            $('.model-selector.cap-selector').removeClass('open');
        });

        // 语言包异步到位（或切换语言）后重绘，否则首屏可能停在裸 key 上
        document.addEventListener('i18n:loaded', renderAll);
        document.addEventListener('i18n:switched', renderAll);

        $(document).on('keydown', function (e) {
            if (e.key === 'Escape') $('.model-selector.cap-selector').removeClass('open');
        });

        $(window).on('resize', function () {
            widgets.forEach(function (w) {
                if (w.$root.hasClass('open')) place(w);
            });
        });
    }

    /**
     * 供自动任务页复用：同一份图标 / 选项归一化 / 条目 HTML / 标签与文案口径，
     * 这样两个入口的技能与 MCP 选择器不会各长一副样子。
     */
    window.Capabilities = {
        shortName: shortName,
        scopeOf: scopeOf,
        icon: icon,
        label: label,
        searchPlaceholder: searchPlaceholder,
        clearText: function () { return t('chatcap.unrestricted'); },
        normalize: normalize,
        filterAvailable: filterAvailable,
        itemsHtml: itemsHtml
    };

    window.reloadCapabilities = function () {
        options = null;
        $.get('/web/chat/skills/options').done(function (res) {
            options = (res && res.data) || {};
            refreshSelection();
        });
    };

    $(init);
})();

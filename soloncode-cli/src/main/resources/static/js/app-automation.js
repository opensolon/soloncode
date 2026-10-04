/* 工作区自动任务：列表内展开编辑；查看对话直接复用聊天消息列表（含流式）。 */
(function () {
    'use strict';
    var nav = document.getElementById('automationNavBtn');
    var badge = document.getElementById('automationBadge');
    var viewer = document.getElementById('gitViewer');
    var content = document.getElementById('gitViewerContent');
    var label = document.getElementById('gitViewerLabel');
    var file = document.getElementById('gitViewerFile');
    var newView = document.getElementById('newChatView');
    var chatView = document.getElementById('chatView');
    var closeBtn = document.getElementById('gitViewerClose');
    var autoNewBtn = document.getElementById('gitViewerAutoNew');
    var tasks = [];
    var expandedId = null;
    var viewVersion = 0;
    var NEW_ID = '__new__';

    function esc(v) {
        return v == null ? '' : String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }
    function toast(msg, type) {
        if (window.showToast) window.showToast(msg, type || 'info');
        else if (window.layer && layer.msg) layer.msg(msg);
    }
    function fail(e) { toast(e.message || I18n.t('toast.operateFailed'), 'error'); }
    function api(method, path, data) {
        var options = {method: method, headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'}};
        if (data) options.body = new URLSearchParams(data).toString();
        return fetch(path, options).then(function (r) { return r.json(); }).then(function (res) {
            if (!res || res.code !== 200) throw new Error((res && res.description) || I18n.t('toast.operateFailed'));
            return res.data;
        });
    }
    function show() {
        if (!viewer) return;
        viewVersion++;
        expandedId = null;
        if (newView) newView.style.display = 'none';
        if (chatView) chatView.style.display = 'none';
        document.body.classList.add('memory-active');
        viewer.classList.add('mem-overlay');
        viewer.style.display = 'flex';
        if (label) label.textContent = I18n.t('loop.title');
        if (file) file.textContent = I18n.t('automation.subtitle');
        if (autoNewBtn) autoNewBtn.style.display = '';
        ['gitViewerMdToggle','gitViewerCopyBtn','gitViewerMemNew','gitViewerMemClear','gitViewerMemOrganize'].forEach(function (id) {
            var el = document.getElementById(id); if (el) el.style.display = 'none';
        });
        if (autoNewBtn) autoNewBtn.onclick = function () {
            expandedId = NEW_ID;
            renderList();
            var input = document.querySelector('#autoList .auto-prompt');
            if (input) input.focus();
        };
        render();
        load();
    }
    function hide() {
        if (!viewer || !content.querySelector('.automation-page')) return;
        viewVersion++;
        viewer.style.display = 'none';
        viewer.classList.remove('mem-overlay');
        if (autoNewBtn) autoNewBtn.style.display = 'none';
        document.body.classList.remove('memory-active');
        if (chatView) chatView.style.display = '';
        if (newView) newView.style.display = '';
        if (chatView && chatView.classList.contains('active') && newView) newView.style.display = 'none';
    }
    function cronToText(cron) {
        var v = cronToVisual(cron);
        if (v) return visualToText(v);
        return 'cron: ' + cron;
    }
    function scheduleText(t) {
        if (t.cron) return cronToText(t.cron);
        return I18n.t('loop.every', {n: t.intervalMinutes || 5});
    }
    function render() {
        if (!content) return;
        content.innerHTML = '<div class="automation-page"><div id="autoList" class="automation-list"></div></div>';
        renderList();
    }
    function displayTitle(t) {
        // 无任务名称：行内标题直接用提示词（CSS ellipsis 截断 + title 悬停全文，与循环任务列表一致）
        return t.name && t.name !== '自动任务' ? t.name : (t.prompt || I18n.t('automation.defaultName'));
    }
    function formHtml(t) {
        var cronVal = t.cron || '';
        var intervalVal = t.intervalMinutes || 5;
        return '<div class="mem-row-body"><div class="automation-form mem-form">' +
            '<div class="auto-input-box">' +
            '<div class="auto-prompt-label">' + esc(I18n.t('loop.taskDesc')) + '</div>' +
            '<textarea class="auto-prompt" rows="2" aria-label="' + esc(I18n.t('loop.taskDesc')) + '" placeholder="' + I18n.t('automation.promptPlaceholder') + '">' + esc(t.prompt || '') + '</textarea>' +
            '<div class="input-toolbar"><div class="toolbar-left">' +
            selHtml('agent', t.agentName || '', I18n.t('automation.agentPlaceholder')) +
            selHtml('model', t.modelName || '', I18n.t('automation.modelPlaceholder')) +
            scheduleSelHtml(cronVal, intervalVal) +
            '</div></div>' +
            '</div>' +
            '<div class="mem-actions"><button class="memory-btn memory-btn-primary auto-save" type="button">' + I18n.t('automation.saveTask') + '</button>' +
            '<button class="memory-btn auto-cancel" type="button">' + I18n.t('common.cancel') + '</button>' +
            (t.id ? '<button class="memory-btn memory-btn-danger auto-delete" type="button">' + I18n.t('common.delete') + '</button>' : '') +
            '</div></div></div>';
    }
    // 输入面板式内联选择器（复用 toolbar-selector-group / model-dropdown 样式：图标 + 名称 + 箭头，hover 显背景）
    // getModelItem/getAgentItem：row 私有数据（存 data-* 属性，行重建不丢失）；setXxx 同步回写
    var SVG_AGENT_ICON = '<svg class="toolbar-setting-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/><path d="M19 3v4M17 5h4"/></svg>';
    var SVG_MODEL_ICON = '<svg class="toolbar-setting-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 2a2 2 0 0 1 2 2c0 .74-.4 1.39-1 1.73V7h1a7 7 0 0 1 7 7h1a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-1.27a7 7 0 0 1-12.46 0H6a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1h1a7 7 0 0 1 7-7h1V5.73A2 2 0 0 1 12 2z"/><circle cx="8" cy="14" r="1"/><circle cx="16" cy="14" r="1"/></svg>';
    var SVG_SCHEDULE_ICON = '<svg class="toolbar-setting-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="13" r="8"/><path d="M12 9v4l2.5 1.5M7 3 5 5M17 3l2 2M9 21h6"/></svg>';
    // ===== 定时定点（可视化 cron）=====
    // 后端 java-cron 为 Quartz 语义：周几 1=周日 … 7=周六（SUN=1, MON=2, ... SAT=7）
    var DOW_KEYS = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'];
    var DOW_CRON = {sun: 1, mon: 2, tue: 3, wed: 4, thu: 5, fri: 6, sat: 7};
    // 可视化状态 → cron（7 位：秒 分 时 日 月 周 年）；周几至少保留一个由 UI 防呆保证
    function visualToCron(v) {
        var parts = String(v.time || '09:00').split(':');
        var hh = parseInt(parts[0], 10); var mm = parseInt(parts[1] || '0', 10);
        if (!(hh >= 0 && hh <= 23) || !(mm >= 0 && mm <= 59)) return '';
        var sec = '0', hour = String(hh), minute = String(mm);
        if (v.freq === 'monthly') {
            var day = parseInt(v.dom, 10);
            if (!(day >= 1 && day <= 31)) return '';
            return [sec, minute, hour, String(day), '*', '?', '*'].join(' ');
        }
        if (v.freq === 'weekly') {
            var dows = (v.days || []).map(function (d) { return DOW_CRON[d]; }).filter(function (n) { return !!n; }).sort(function (a, b) { return a - b; });
            if (!dows.length) return '';
            return [sec, minute, hour, '?', '*', dows.join(','), '*'].join(' ');
        }
        return [sec, minute, hour, '*', '*', '?', '*'].join(' ');
    }
    // cron → 可视化状态（仅识别可视化可生成的三种形态；步进、区间、L 等高级表达式返回 null → 停在 Cron Tab）
    function cronToVisual(cron) {
        var f = String(cron || '').trim().split(/\s+/);
        if (f.length !== 7 || f[0] !== '0' || f[6] !== '*') return null;
        var hour = parseInt(f[2], 10), minute = parseInt(f[1], 10);
        if (!(hour >= 0 && hour <= 23) || !(minute >= 0 && minute <= 59)) return null;
        var time = (hour < 10 ? '0' : '') + hour + ':' + (minute < 10 ? '0' : '') + minute;
        if (f[3] === '*' && f[4] === '*' && f[5] === '?' ) {
            return {freq: 'daily', time: time};
        }
        if (f[3] === '?' && f[4] === '*' && f[5] !== '*' && f[5] !== '?') {
            var days = [];
            var toks = f[5].split(',');
            for (var i = 0; i < toks.length; i++) {
                var n = parseInt(toks[i], 10);
                if (!(n >= 1 && n <= 7)) return null;
                var hit = false;
                for (var k in DOW_CRON) if (DOW_CRON[k] === n) { days.push(k); hit = true; break; }
                if (!hit) return null;
            }
            if (!days.length) return null;
            return {freq: 'weekly', time: time, days: days};
        }
        if (f[3] !== '*' && f[3] !== '?' && f[4] === '*' && f[5] === '?') {
            var dom = parseInt(f[3], 10);
            if (!(dom >= 1 && dom <= 31)) return null;
            return {freq: 'monthly', time: time, dom: dom};
        }
        return null;
    }
    // 可视化状态 → 摘要文案（触发器与行头共用）
    function visualToText(v) {
        if (v.freq === 'weekly') {
            var names = (v.days || []).slice().sort(function (a, b) { return DOW_KEYS.indexOf(a) - DOW_KEYS.indexOf(b); })
                .map(function (d) { return I18n.t('loop.dow.' + d); });
            return I18n.t('loop.scheduleSummary.weekly', {days: names.join(I18n.t('loop.dayJoinSep')), time: v.time});
        }
        if (v.freq === 'monthly') return I18n.t('loop.scheduleSummary.monthly', {day: v.dom, time: v.time});
        return I18n.t('loop.scheduleSummary.daily', {time: v.time});
    }
    function scheduleSummary(cron, interval) {
        if (cron) {
            var v = cronToVisual(cron);
            if (v) return visualToText(v);
            return 'cron: ' + cron;
        }
        return I18n.t('loop.every', {n: interval || 5});
    }
    // 拆 loop.everyRunOnce 模板（如 "每隔{n}分钟执行一次"）的前后缀，供浮层行内句式 "每隔 [5] 分钟执行一次" 使用；
    // 哨兵字符避免与真实内容冲突，ja 等语言前缀可能为空字符串。
    function everyParts() {
        var parts = String(I18n.t('loop.everyRunOnce', {n: '\u0000'})).split('\u0000');
        return { pre: parts[0] || '', post: parts[1] || '' };
    }
    // Cron 快捷模板：单行 3 个即可（间隔/天/周各一，够示意且不换行）；全部保留在可视化解析集内无意义——
    // 点击行为只填充表达式、不跳 Tab，日常类频率由“定时定点”面板承担
    var CRON_TEMPLATES = [
        {key: 'loop.cronEvery2h', cron: '0 0 */2 * * ? *'},
        {key: 'loop.cronDaily22', cron: '0 0 22 * * ? *'},
        {key: 'loop.cronWeeklyMon', cron: '0 0 0 ? * 2 *'}
    ];
    function scheduleSelHtml(cron, interval) {
        var isCron = !!cron;
        var visual = cron ? cronToVisual(cron) : null;
        var isVisual = !!visual;
        var summary = scheduleSummary(cron, interval);
        var ev = everyParts();
        var v = visual || {freq: 'daily', time: '09:00', days: ['mon'], dom: 1};
        var dowBtns = DOW_KEYS.map(function (d) {
            var on = (v.days || []).indexOf(d) !== -1;
            return '<button type="button" class="' + (on ? 'active' : '') + '" data-dow="' + d + '" aria-label="' + esc(I18n.t('loop.dow.' + d)) + '">' + esc(I18n.t('loop.dow.' + d)) + '</button>';
        }).join('');
        return '<div class="toolbar-selector-group auto-schedule-group">' + SVG_SCHEDULE_ICON +
            '<div class="model-selector auto-select auto-schedule-select" data-kind="schedule">' +
            '<div class="model-selector-current auto-select-current" tabindex="0" role="button" aria-label="' + esc(I18n.t('loop.scheduleMethod')) + '">' +
            '<span class="model-name auto-schedule-summary">' + esc(summary) + '</span><i class="layui-icon layui-icon-down model-arrow"></i></div>' +
            '<div class="model-dropdown auto-schedule-pop">' +
            '<div class="auto-schedule-title">' + I18n.t('loop.scheduleMethod') + '</div>' +
            '<div class="auto-schedule-tabs"><button type="button" class="auto-schedule-tab' + (!isCron ? ' active' : '') + '" data-schedule="interval">' + I18n.t('loop.fixedInterval') + '</button><button type="button" class="auto-schedule-tab' + (isVisual ? ' active' : '') + '" data-schedule="visual">' + I18n.t('loop.visual') + '</button><button type="button" class="auto-schedule-tab' + (isCron && !isVisual ? ' active' : '') + '" data-schedule="cron">' + I18n.t('loop.cronExpression') + '</button></div>' +
            '<div class="auto-schedule-panel auto-schedule-interval' + (!isCron ? '' : ' hidden') + '"><div class="auto-schedule-line"><span class="auto-interval-pre">' + esc(ev.pre) + '</span><input type="number" class="auto-interval" min="1" value="' + esc(interval) + '"/><span>' + esc(ev.post) + '</span></div><div class="model-option-pills auto-interval-pills"><button type="button" data-minutes="1">1</button><button type="button" data-minutes="5">5</button><button type="button" data-minutes="15">15</button><button type="button" data-minutes="30">30</button><button type="button" data-minutes="60">60</button></div></div>' +
            '<div class="auto-schedule-panel auto-schedule-visual' + (isVisual ? '' : ' hidden') + '">' +
            '<div class="auto-visual-row"><span class="auto-visual-label">' + I18n.t('loop.repeat') + '</span><div class="model-option-pills auto-repeat-pills">' +
            ['daily', 'weekly', 'monthly'].map(function (fq) {
                return '<button type="button" class="' + (v.freq === fq ? 'active' : '') + '" data-freq="' + fq + '">' + I18n.t('loop.' + fq) + '</button>';
            }).join('') + '</div></div>' +
            '<div class="auto-visual-row"><span class="auto-visual-label">' + I18n.t('loop.time') + '</span><input type="time" class="auto-visual-time" value="' + esc(v.time) + '"/></div>' +
            '<div class="auto-visual-row auto-visual-weekly' + (v.freq === 'weekly' ? '' : ' hidden') + '"><span class="auto-visual-label">' + I18n.t('loop.weekly') + '</span><div class="model-option-pills auto-dow-pills">' + dowBtns + '</div></div>' +
            '<div class="auto-visual-row auto-visual-monthly' + (v.freq === 'monthly' ? '' : ' hidden') + '"><span class="auto-visual-label">' + I18n.t('loop.monthly') + '</span><input type="number" class="auto-dom" min="1" max="31" value="' + esc(v.dom || 1) + '"/><span class="auto-dom-suffix">' + I18n.t('loop.dayOfMonthSuffix') + '</span></div></div>' +
            '<div class="auto-schedule-panel auto-schedule-cron' + (isCron && !isVisual ? '' : ' hidden') + '"><input type="text" class="auto-cron" value="' + esc(cron) + '" placeholder="0 */5 * * * ? *" title="' + esc(I18n.t('loop.cronExpression')) + '"/><div class="auto-cron-links"><span class="loop-cron-hint">' + I18n.t('loop.cronExamples') + '</span>' + CRON_TEMPLATES.map(function (tpl) { return '<a class="loop-cron-link" data-cron="' + tpl.cron + '">' + I18n.t(tpl.key) + '</a>'; }).join('') + '</div></div>' +
            '</div></div></div>';
    }
    // 从面板 DOM 读当前可视化状态（预览刷新与保存共用单一数据源）
    function readVisualState(box) {
        var freqBtn = box.querySelector('.auto-repeat-pills button.active');
        var freq = (freqBtn && freqBtn.getAttribute('data-freq')) || 'daily';
        var time = box.querySelector('.auto-visual-time').value || '09:00';
        var days = [];
        box.querySelectorAll('.auto-dow-pills button.active').forEach(function (b) { days.push(b.getAttribute('data-dow')); });
        var dom = parseInt(box.querySelector('.auto-dom').value, 10) || 1;
        return {freq: freq, time: time, days: days.length ? days : ['mon'], dom: dom};
    }
    function selHtml(kind, value, placeholder) {
        var shown = value ? esc(value) : esc(placeholder);
        var icon = kind === 'agent' ? SVG_AGENT_ICON : SVG_MODEL_ICON;
        return '<div class="toolbar-selector-group auto-select-group">' + icon +
            '<div class="model-selector auto-select" data-kind="' + kind + '">' +
            '<div class="model-selector-current auto-select-current" tabindex="0" role="button" aria-label="' + esc(kind === 'agent' ? I18n.t('automation.agent') : I18n.t('automation.model')) + '">' +
            '<span class="model-name">' + shown + '</span>' +
            '<i class="layui-icon layui-icon-down model-arrow"></i></div>' +
            '<div class="model-dropdown">' +
            '<div class="model-search-wrap"><input type="text" class="model-search-input" placeholder="' + I18n.t('automation.search') + '"/></div>' +
            '<div class="model-dropdown-items"></div></div></div></div>';
    }
    function setSel(row, kind, value, placeholder) {
        row.setAttribute('data-' + kind, value || '');
        var box = row.querySelector('.auto-select[data-kind=' + kind + ']');
        if (box) box.querySelector('.model-name').textContent = value || placeholder;
    }
    function getSel(row, kind) {
        return row.getAttribute('data-' + kind) || '';
    }
    // 渲染下拉项：items=[{value,label,desc}]，空值项始终在首位
    function renderSelItems(box, items, selected, emptyLabel, emptyDesc) {
        var html = '<div class="model-dropdown-item' + (selected ? '' : ' active') + '" data-value="">' +
            '<span class="model-item-name">' + esc(emptyLabel) + '</span>' +
            (emptyDesc ? '<span class="model-item-desc">' + esc(emptyDesc) + '</span>' : '') + '</div>';
        items.forEach(function (it) {
            var cls = it.value === selected ? ' active' : '';
            html += '<div class="model-dropdown-item' + cls + '" data-value="' + esc(it.value) + '">' +
                '<span class="model-item-name">' + esc(it.label) + '</span>' +
                (it.desc ? '<span class="model-item-desc">' + esc(it.desc) + '</span>' : '') + '</div>';
        });
        if (selected && !items.some(function (x) { return x.value === selected; })) {
            html += '<div class="model-dropdown-item active" data-value="' + esc(selected) + '">' +
                '<span class="model-item-name">' + esc(selected) + '</span></div>';
        }
        box.querySelector('.model-dropdown-items').innerHTML = html;
        var search = box.querySelector('.model-search-input');
        if (search) search.value = '';
    }
    // 绑定触发器/搜索/选择；onChange 在选择后回调（row 内数据由 setSel 维护）
    function bindSel(row, kind, placeholder, onChange) {
        var box = row.querySelector('.auto-select[data-kind=' + kind + ']');
        if (!box) return;
        var current = box.querySelector('.auto-select-current');
        function closeOthers() { row.querySelectorAll('.auto-select.open').forEach(function (o) { if (o !== box) o.classList.remove('open'); }); }
        current.onclick = function (e) {
            e.stopPropagation();
            var opening = !box.classList.contains('open');
            closeOthers();
            box.classList.toggle('open', opening);
            if (opening) {
                requestAnimationFrame(function () {
                    var active = box.querySelector('.model-dropdown-item.active');
                    if (active) active.scrollIntoView({ block: 'nearest' });
                    var input = box.querySelector('.model-search-input');
                    if (input) { input.value = ''; filterSelItems(box, ''); input.focus(); }
                });
            }
        };
        current.onkeydown = function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); current.onclick(e); } };
        var search = box.querySelector('.model-search-input');
        if (search) search.oninput = function () { filterSelItems(box, this.value); };
        box.querySelector('.model-dropdown').onclick = function (e) {
            var item = e.target.closest('.model-dropdown-item');
            if (!item) return;
            e.stopPropagation();
            setSel(row, kind, item.getAttribute('data-value') || '', placeholder);
            closeOthers();
            if (onChange) onChange(getSel(row, kind));
        };
    }
    function filterSelItems(box, query) {
        var q = (query || '').toLowerCase().trim();
        box.querySelectorAll('.model-dropdown-item').forEach(function (item) {
            item.style.display = !q || (item.textContent || '').toLowerCase().indexOf(q) !== -1 ? '' : 'none';
        });
    }
    // 点击行内其它区域关闭所有下拉
    function closeAllSels(row) { row.querySelectorAll('.auto-select.open').forEach(function (o) { o.classList.remove('open'); }); }
    // 行内右侧图标按钮（对齐循环任务列表的 loop-item-actions 样式：图标 + title 提示）
    var SVG_PAUSE = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>';
    var SVG_PLAY = '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><polygon points="5,3 19,12 5,21"/></svg>';
    var SVG_RUN = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>';
    var SVG_CHAT = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>';
    function actionBtn(cls, title, svg) {
        return '<button class="' + cls + '" type="button" title="' + esc(title) + '" aria-label="' + esc(title) + '">' + svg + '</button>';
    }
    function isAutomationTask(t) { return t && t.automation !== false; }
    function taskRef(t) {
        return isAutomationTask(t)
            ? {type: 'AUTOMATION', id: t.id}
            : {type: 'SESSION_LOOP', id: t.taskId || t.id, sessionId: t.sessionId};
    }
    function rowHtml(t, isNew) {
        var open = expandedId === (isNew ? NEW_ID : t.id);
        var state = t.running ? I18n.t('automation.status.running') : (t.enabled ? I18n.t('automation.status.enabled') : I18n.t('automation.status.paused'));
        var stateClass = t.running ? 'running' : (t.enabled ? 'enabled' : 'paused');
        // 会话任务（会话内 /loop 或循环表单创建）与自动任务同页展示，用来源徽标区分
        var isAuto = t.automation !== false;
        var badgeHtml = isNew ? '' : '<span class="automation-source' + (isAuto ? '' : ' session') + '">' + (isAuto ? I18n.t('automation.source.auto') : I18n.t('automation.source.session')) + '</span>';
        var actions = '';
        if (!isNew) {
            actions = '<div class="automation-row-actions">' +
                actionBtn('auto-toggle', t.enabled ? I18n.t('automation.pause') : I18n.t('automation.resume'), t.enabled ? SVG_PAUSE : SVG_PLAY) +
                actionBtn('auto-trigger', I18n.t('automation.trigger'), SVG_RUN) +
                actionBtn('auto-session', I18n.t('automation.viewSession'), SVG_CHAT) +
                '</div>';
        }
        return '<div class="mem-row automation-row' + (open ? ' open' : '') + '" data-id="' + esc(isNew ? NEW_ID : t.id) + '" data-session="' + esc(t.sessionId || '') + '">' +
            '<div class="mem-row-head automation-row-head" role="button" tabindex="0" aria-expanded="' + open + '">' +
            '<span class="mem-caret"><svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 4 10 8 6 12"></polyline></svg></span>' +
            (isNew ? '<span class="mem-row-key">' + I18n.t('automation.new') + '</span>' :
                '<span class="automation-status-dot ' + stateClass + '"></span><span class="mem-row-key automation-row-title" title="' + esc(t.prompt || '') + '">' + esc(displayTitle(t)) + '</span>' +
                badgeHtml +
                '<span class="automation-row-schedule">' + esc(scheduleText(t)) + '</span><span class="automation-state ' + stateClass + '">' + state + '</span>') +
            actions +
            '</div>' + (open ? formHtml(t) : '') + '</div>';
    }
    function renderList() {
        var box = document.getElementById('autoList'); if (!box) return;
        var html = expandedId === NEW_ID ? rowHtml({}, true) : '';
        html += tasks.map(function (t) { return rowHtml(t, false); }).join('');
        box.innerHTML = html || '<div class="automation-empty">' + I18n.t('automation.empty.title') + '<br><span>' + I18n.t('automation.empty.desc') + '</span></div>';
        box.querySelectorAll('.automation-row').forEach(function (row) {
            var id = row.getAttribute('data-id');
            var head = row.querySelector('.automation-row-head');
            function toggle() { closeAllSels(row); expandedId = expandedId === id ? null : id; renderList(); }
            head.onclick = toggle;
            head.onkeydown = function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); } };
            if (row.classList.contains('open')) {
                var t = tasks.find(function (x) { return x.id === id; }) || {};
                populateSelectors(row, t);
                bindSchedule(row, t);
                row.querySelector('.auto-save').onclick = function () { save(row, id); };
                row.querySelector('.auto-cancel').onclick = function () { expandedId = null; renderList(); };
                var del = row.querySelector('.auto-delete');
                if (del) del.onclick = function () { removeTask(t); };
            }
            if (id === NEW_ID) return;
            var task = tasks.find(function (x) { return x.id === id; }) || {};
            var rowSession = row.getAttribute('data-session') || '';
            // 自动任务和会话循环任务共用列表，但后端定位键不同：
            // 自动任务用 automation id，会话任务用 sessionId + LoopTask taskId。
            var ref = taskRef(task);
            // 行内右侧图标按钮：阻止冒泡，避免误触发行展开/收起
            row.querySelector('.auto-toggle').onclick = function (e) { e.stopPropagation(); api('POST', '/web/tasks/toggle', ref).then(load).catch(fail); };
            row.querySelector('.auto-trigger').onclick = function (e) { e.stopPropagation(); api('POST', '/web/tasks/trigger', ref).then(function () { toast(I18n.t('automation.triggered')); load(); }).catch(fail); };
            row.querySelector('.auto-session').onclick = function (e) { e.stopPropagation(); showSession(rowSession); };
        });
    }
    function bindSchedule(row, t) {
        var box = row.querySelector('.auto-schedule-select');
        if (!box) return;
        // 三模式：interval 固定间隔 / visual 定时定点（生成 cron）/ cron 手写表达式
        var mode = 'interval';
        var origCron = (t && t.cron) || '';
        var parsed = origCron ? cronToVisual(origCron) : null;
        if (parsed) mode = 'visual'; else if (origCron) mode = 'cron';
        var current = box.querySelector('.auto-select-current');
        function closeOthers() { row.querySelectorAll('.auto-select.open').forEach(function (o) { if (o !== box) { o.classList.remove('open'); var p = o.querySelector('.auto-schedule-pop'); if (p) { p.classList.remove('is-fixed'); p.style.left = ''; p.style.top = ''; p.style.maxHeight = ''; } } }); }
        // 展开方向在打开瞬间一次性锁定（按三种模式中最高面板判断），切换 tab / 重复粒度时不再上下翻转
        var placeDir = null, openMaxHeight = 0;
        function captureOpenHeight() {
            var pop = box.querySelector('.auto-schedule-pop');
            if (!pop) return;
            var panels = pop.querySelectorAll('.auto-schedule-panel');
            var savedPanels = [];
            panels.forEach(function (p) { savedPanels.push(p.classList.contains('hidden')); });
            var weekly = pop.querySelector('.auto-visual-weekly');
            var monthly = pop.querySelector('.auto-visual-monthly');
            var savedWeekly = weekly ? weekly.classList.contains('hidden') : true;
            var savedMonthly = monthly ? monthly.classList.contains('hidden') : true;
            var max = 0;
            panels.forEach(function (p, i) {
                panels.forEach(function (q, j) { q.classList.toggle('hidden', j !== i); });
                if (p.classList.contains('auto-schedule-visual')) {
                    // 周几行与几号行互斥，分别测各自最高状态
                    if (weekly && monthly) {
                        weekly.classList.remove('hidden'); monthly.classList.add('hidden');
                        max = Math.max(max, pop.offsetHeight);
                        monthly.classList.remove('hidden'); weekly.classList.add('hidden');
                        max = Math.max(max, pop.offsetHeight);
                    } else if (weekly) {
                        weekly.classList.remove('hidden');
                    } else if (monthly) {
                        monthly.classList.remove('hidden');
                    }
                }
                var h = pop.offsetHeight;
                if (h > max) max = h;
            });
            panels.forEach(function (p, i) { p.classList.toggle('hidden', savedPanels[i]); });
            if (weekly) weekly.classList.toggle('hidden', savedWeekly);
            if (monthly) monthly.classList.toggle('hidden', savedMonthly);
            openMaxHeight = max;
        }
        function placePopover() {
            var pop = box.querySelector('.auto-schedule-pop');
            if (!pop || !box.classList.contains('open')) return;
            var anchor = current.getBoundingClientRect();
            var gap = 6, margin = 10;
            var width = pop.offsetWidth || 286;
            var height = pop.offsetHeight || 260;
            if (!placeDir) placeDir = (anchor.top - (openMaxHeight || height) - gap >= margin) ? 'up' : 'down';
            var left = Math.min(Math.max(margin, anchor.right - width), window.innerWidth - width - margin);
            var top, maxHeight;
            if (placeDir === 'up') {
                // 向上：底边锚定触发器，高度变化只向上生长
                top = anchor.top - height - gap;
                maxHeight = Math.max(160, anchor.top - gap - margin);
            } else {
                top = anchor.bottom + gap;
                maxHeight = Math.max(160, window.innerHeight - top - margin);
            }
            pop.style.left = Math.round(left) + 'px';
            pop.style.top = Math.round(Math.max(margin, top)) + 'px';
            pop.style.maxHeight = Math.round(maxHeight) + 'px';
            pop.classList.add('is-fixed');
        }
        function clearPopoverPosition() {
            var pop = box.querySelector('.auto-schedule-pop');
            if (pop) { pop.classList.remove('is-fixed'); pop.style.left = ''; pop.style.top = ''; pop.style.maxHeight = ''; }
            placeDir = null;
            openMaxHeight = 0;
        }
        // 可视化面板 → 触发器摘要同步；非法中间态时摘要回退原始 cron
        function refreshVisual() {
            var v = readVisualState(box);
            var cron = visualToCron(v);
            return {v: v, cron: cron};
        }
        function update() {
            var minutes = box.querySelector('.auto-interval').value || 5;
            var summary;
            if (mode === 'interval') summary = scheduleSummary('', minutes);
            else if (mode === 'visual') {
                var r = refreshVisual();
                summary = r.cron ? visualToText(r.v) : (origCron ? 'cron: ' + origCron : '');
            } else {
                var raw = box.querySelector('.auto-cron').value.trim() || origCron;
                summary = raw ? scheduleSummary(raw, minutes) : I18n.t('loop.cronExpression');
            }
            box.querySelector('.auto-schedule-summary').textContent = summary;
            row.setAttribute('data-schedule-type', mode === 'interval' ? 'interval' : 'cron');
        }
        function setFreq(freq) {
            box.querySelectorAll('.auto-repeat-pills button').forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-freq') === freq); });
            box.querySelector('.auto-visual-weekly').classList.toggle('hidden', freq !== 'weekly');
            box.querySelector('.auto-visual-monthly').classList.toggle('hidden', freq !== 'monthly');
            update();
            if (box.classList.contains('open')) requestAnimationFrame(placePopover);
        }
        function setMode(next) {
            mode = next;
            box.querySelectorAll('.auto-schedule-tab').forEach(function (tab) { tab.classList.toggle('active', tab.getAttribute('data-schedule') === next); });
            box.querySelector('.auto-schedule-interval').classList.toggle('hidden', next !== 'interval');
            box.querySelector('.auto-schedule-visual').classList.toggle('hidden', next !== 'visual');
            box.querySelector('.auto-schedule-cron').classList.toggle('hidden', next !== 'cron');
            update();
            if (box.classList.contains('open')) requestAnimationFrame(placePopover);
        }
        current.onclick = function (e) {
            e.stopPropagation();
            var opening = !box.classList.contains('open');
            closeOthers();
            box.classList.toggle('open', opening);
            if (opening) { placeDir = null; captureOpenHeight(); requestAnimationFrame(placePopover); } else clearPopoverPosition();
        };
        current.onkeydown = function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); current.onclick(e); } };
        window.addEventListener('resize', function () { if (box.classList.contains('open')) placePopover(); });
        window.addEventListener('scroll', function () { if (box.classList.contains('open')) placePopover(); }, true);
        box.querySelectorAll('.auto-schedule-tab').forEach(function (tab) { tab.onclick = function () { setMode(tab.getAttribute('data-schedule')); }; });
        box.querySelector('.auto-interval').oninput = update;
        box.querySelector('.auto-cron').oninput = update;
        box.querySelector('.auto-visual-time').oninput = update;
        box.querySelector('.auto-dom').oninput = update;
        box.querySelectorAll('.auto-interval-pills button').forEach(function (button) { button.onclick = function () { box.querySelector('.auto-interval').value = button.getAttribute('data-minutes'); setMode('interval'); }; });
        box.querySelectorAll('.auto-repeat-pills button').forEach(function (button) { button.onclick = function () { setFreq(button.getAttribute('data-freq')); }; });
        // 周几多选，防呆：至少保留一个（最后一个不可取消）
        box.querySelectorAll('.auto-dow-pills button').forEach(function (button) { button.onclick = function () {
            if (button.classList.contains('active') && box.querySelectorAll('.auto-dow-pills button.active').length <= 1) return;
            button.classList.toggle('active');
            update();
        }; });
        box.querySelectorAll('.loop-cron-link').forEach(function (link) { link.onclick = function (e) { e.preventDefault();
            var c = link.getAttribute('data-cron');
            box.querySelector('.auto-cron').value = c;
            update();
        }; });
        box.querySelector('.auto-schedule-pop').onclick = function (e) { e.stopPropagation(); if (!e.target.closest('input')) placePopover(); };
        setMode(mode);
    }
    function save(row, id) {
        // 调度方式与后端契约一致：cron 或 intervalMinutes 必须提供一个（后端 validate：另一个可为空）。
        // 三模式统一归约：visual 在提交时实时生成 cron；row 上 data-schedule-type 仅存 cron/interval。
        var schedBox = row.querySelector('.auto-schedule-select');
        var activeTab = schedBox.querySelector('.auto-schedule-tab.active');
        var mode = (activeTab && activeTab.getAttribute('data-schedule')) || 'interval';
        var isCron = mode !== 'interval';
        var cron = '';
        if (mode === 'visual') {
            cron = visualToCron(readVisualState(schedBox));
            if (!cron) { toast(I18n.t('automation.cronRequired'), 'error'); return; }
        } else if (mode === 'cron') {
            cron = schedBox.querySelector('.auto-cron').value.trim();
        }
        var data = {type: 'AUTOMATION', prompt: row.querySelector('.auto-prompt').value.trim(),
            intervalMinutes: isCron ? '' : row.querySelector('.auto-interval').value,
            cron: isCron ? cron : '',
            // 自动任务统一按定时任务创建；不传 taskType，创建走默认、更新时保留旧任务的底层类型。
            // 不传 name：新建时后端自动取提示词前 20 字作为名称，编辑时保留旧名称。
            runNow: 'false',
            modelName: getSel(row, 'model'), agentName: getSel(row, 'agent')};
        if (!data.prompt) { toast(I18n.t('automation.promptRequired'), 'error'); return; }
        if (isCron && !data.cron) { toast(I18n.t('automation.cronRequired'), 'error'); return; }
        if (!isCron && !(parseInt(data.intervalMinutes, 10) > 0)) { toast(I18n.t('automation.intervalInvalid'), 'error'); return; }
        var button = row.querySelector('.auto-save');
        button.disabled = true;
        var current = id === NEW_ID ? null : (tasks.find(function (x) { return x.id === id; }) || null);
        if (id !== NEW_ID) {
            var ref = taskRef(current);
            Object.keys(ref).forEach(function (key) { data[key] = ref[key]; });
        }
        api('POST', id === NEW_ID ? '/web/tasks/create' : '/web/tasks/update', data)
            .then(function () { toast(I18n.t('toast.saveSuccess'), 'success'); expandedId = null; load(); })
            .catch(fail).finally(function () { button.disabled = false; });
    }
    function removeTask(t) {
        if (!confirm(I18n.t('automation.confirmDelete'))) return;
        api('POST', '/web/tasks/delete', taskRef(t)).then(function () { expandedId = null; load(); }).catch(fail);
    }
    function populateSelectors(row, t) {
        // 输入面板风格选择器：选中值存 row 的 data-agent/data-model 属性，避免 DOM 重建丢失
        var agentBox = row.querySelector('.auto-select[data-kind=agent]');
        var modelBox = row.querySelector('.auto-select[data-kind=model]');
        if (!agentBox || !modelBox) return;
        row.setAttribute('data-agent', t.agentName || '');
        row.setAttribute('data-model', t.modelName || '');
        bindSel(row, 'agent', I18n.t('automation.agentPlaceholder'));
        bindSel(row, 'model', I18n.t('automation.modelPlaceholder'));
        api('GET', '/web/chat/models').then(function (data) {
            if (!modelBox.isConnected) return;
            var list = (data && data.list) || [];
            var items = list.map(function (x) {
                var v = x.name || x.model;
                return {value: v, label: v, desc: x.desc || ''};
            }).filter(function (x) { return x.value; });
            renderSelItems(modelBox, items, getSel(row, 'model'), I18n.t('automation.modelPlaceholder'), I18n.t('automation.modelDefaultDesc'));
        }).catch(fail);
        api('GET', '/web/settings/agents').then(function (data) {
            if (!agentBox.isConnected) return;
            var list = data && (data.list || data) || [];
            if (!Array.isArray(list)) list = [];
            var items = list.map(function (x) {
                var v = x.name || x.id || x;
                return {value: v, label: v, desc: (x && x.description) || ''};
            }).filter(function (x) { return x.value; });
            renderSelItems(agentBox, items, getSel(row, 'agent'), I18n.t('automation.agentPlaceholder'), I18n.t('automation.agentDefaultDesc'));
        }).catch(fail);
    }
    function showSession(sessionId) {
        // 完全复用现有聊天消息列表：列表数据直接携带 sessionId，关闭面板后把该会话激活为当前聊天会话。
        // 历史与流式均走原有管线（loadMessages / WebSocket 按 sessionId 路由），不另建渲染逻辑。
        if (!sessionId) { toast(I18n.t('automation.sessionNotFound'), 'error'); return; }
        hide();
        var sess = getOrCreateSession(sessionId);
        // 任务会话可能不在左侧历史栏：进入时清空历史高亮，
        // 避免"主区显示任务会话、侧栏却亮着旧会话"的误导
        currentChatIndex = -1;
        updateHistoryUI();
        setActiveSession(sessionId);
        if (!inChatMode) switchToChatMode();
        if (!sess.isStreaming && sess.container.children.length === 0) {
            loadMessages(sess);
        } else {
            scrollToBottom(true);
            if (typeof scheduleMsgNavRebuild === 'function') scheduleMsgNavRebuild();
        }
    }
    function load(silent) {
        var version = viewVersion;
        api('GET', '/web/tasks/list?type=AUTOMATION').then(function (data) {
            if (version !== viewVersion) return;
            tasks = Array.isArray(data) ? data : [];
            renderList();
            updateBadge();
        }).catch(silent ? function () {} : fail);
    }
    // 任务数徽标（对齐 memoryBadge 模式：列表条数，0 个隐藏）
    function updateBadge() {
        if (!badge) return;
        if (tasks.length > 0) {
            badge.textContent = tasks.length;
            badge.style.display = '';
        } else {
            badge.style.display = 'none';
        }
    }
    if (nav) nav.addEventListener('click', show);
    if (closeBtn) closeBtn.addEventListener('click', hide);
    // 语言切换时若面板正打开，重渲染以应用新文案（列表数据已在内存，无需重新拉取）
    document.addEventListener('i18n:switched', function () {
        if (!viewer || viewer.style.display === 'none' || !content.querySelector('.automation-page')) return;
        if (label) label.textContent = I18n.t('loop.title');
        if (file) file.textContent = I18n.t('automation.subtitle');
        renderList();
    });
    // 点击选择器外部时关闭下拉（全局仅注册一次，避免 renderList 重复挂监听）
    document.addEventListener('click', function (e) {
        if (e.target.closest && !e.target.closest('.auto-select')) {
            document.querySelectorAll('.auto-select.open').forEach(function (o) { o.classList.remove('open'); });
        }
    });
    window.openAutomationViewer = show;
    // 页面加载即静默拉取任务列表，徽标初始化对齐心智记忆（无需先打开面板；失败不打扰）
    load(true);
})();

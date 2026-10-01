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
    function fail(e) { toast(e.message || '操作失败', 'error'); }
    function api(method, path, data) {
        var options = {method: method, headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'}};
        if (data) options.body = new URLSearchParams(data).toString();
        return fetch(path, options).then(function (r) { return r.json(); }).then(function (res) {
            if (!res || res.code !== 200) throw new Error((res && res.description) || '操作失败');
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
        if (label) label.textContent = '自动任务';
        if (file) file.textContent = '按计划执行任务，每个任务使用独立的专用会话';
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
    function scheduleText(t) {
        return t.cron ? 'cron: ' + t.cron : ('每 ' + (t.intervalMinutes || 5) + ' 分钟');
    }
    function render() {
        if (!content) return;
        content.innerHTML = '<div class="automation-page"><div id="autoList" class="automation-list"></div></div>';
        renderList();
    }
    function displayTitle(t) {
        // 无任务名称：行内标题直接用提示词（CSS ellipsis 截断 + title 悬停全文，与循环任务列表一致）
        return t.name && t.name !== '自动任务' ? t.name : (t.prompt || '自动任务');
    }
    function formHtml(t) {
        var cronVal = t.cron || '';
        // 调度方式：对齐定时心跳表单（radio 单选 + 输入联动），radio 按行内唯一表单命名，避免多任务 DOM 冲突
        return '<div class="mem-row-body"><div class="automation-form mem-form">' +
            '<label class="automation-field"><span>任务描述 <b>*</b></span><textarea class="auto-prompt" rows="2" placeholder="描述自动任务需要完成的事情">' + esc(t.prompt || '') + '</textarea></label>' +
            '<div class="automation-field"><span>调度方式</span><div class="automation-schedule">' +
            '<div class="loop-interval-row"><label class="loop-radio"><input type="radio" name="autoScheduleType" value="interval"' + (cronVal ? '' : ' checked') + '/> 固定间隔</label>' +
            '<input type="number" class="loop-input loop-input-sm auto-interval" min="1" value="' + esc(t.intervalMinutes || 5) + '" placeholder="5" title="分钟"/>' +
            '<span class="automation-schedule-unit">分钟</span></div>' +
            '<div class="loop-interval-row"><label class="loop-radio"><input type="radio" name="autoScheduleType" value="cron"' + (cronVal ? ' checked' : '') + '/> Cron 表达式</label>' +
            '<input type="text" class="loop-input loop-input-sm auto-cron" value="' + esc(cronVal) + '" placeholder="0 */5 * * * ? *"/>' +
            '<a class="loop-cron-link" data-cron="0 0 */2 * * ? *">每2小时</a>' +
            '<a class="loop-cron-link" data-cron="0 0 22 * * ? *">每天22点</a>' +
            '</div></div></div>' +
            '<div class="automation-form-grid">' +
            '<label class="automation-field"><span>智能体</span>' + selHtml('agent', t.agentName || '', 'main（主代理）') + '</label>' +
            '<label class="automation-field"><span>模型</span>' + selHtml('model', t.modelName || '', '跟随默认模型') + '</label>' +
            '</div>' +
            '<div class="mem-actions"><button class="memory-btn memory-btn-primary auto-save" type="button">保存任务</button>' +
            '<button class="memory-btn auto-cancel" type="button">取消</button>' +
            (t.id ? '<button class="memory-btn memory-btn-danger auto-delete" type="button">删除</button>' : '') +
            '</div></div></div>';
    }
    // 输入面板风格的下拉选择器（复用 agents-model-selector 全宽向下弹出样式：搜索框 + active 高亮）
    // getModelItem/getAgentItem：row 私有数据（存 data-* 属性，行重建不丢失）；setXxx 同步回写
    function selHtml(kind, value, placeholder) {
        var shown = value ? esc(value) : esc(placeholder);
        return '<div class="model-selector agents-model-selector auto-select" data-kind="' + kind + '">' +
            '<div class="model-selector-current auto-select-current" tabindex="0" role="button">' +
            '<span class="model-name">' + shown + '</span>' +
            '<i class="layui-icon layui-icon-down model-arrow"></i></div>' +
            '<div class="model-dropdown">' +
            '<div class="model-search-wrap"><input type="text" class="model-search-input" placeholder="搜索..."/></div>' +
            '<div class="model-dropdown-items"></div></div></div>';
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
    function rowHtml(t, isNew) {
        var open = expandedId === (isNew ? NEW_ID : t.id);
        var state = t.running ? '执行中' : (t.enabled ? '已启用' : '已暂停');
        var stateClass = t.running ? 'running' : (t.enabled ? 'enabled' : 'paused');
        var actions = '';
        if (!isNew) {
            actions = '<div class="automation-row-actions">' +
                actionBtn('auto-toggle', t.enabled ? '暂停' : '恢复', t.enabled ? SVG_PAUSE : SVG_PLAY) +
                actionBtn('auto-trigger', '立即执行', SVG_RUN) +
                actionBtn('auto-session', '查看对话', SVG_CHAT) +
                '</div>';
        }
        return '<div class="mem-row automation-row' + (open ? ' open' : '') + '" data-id="' + esc(isNew ? NEW_ID : t.id) + '">' +
            '<div class="mem-row-head automation-row-head" role="button" tabindex="0" aria-expanded="' + open + '">' +
            '<span class="mem-caret"><svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 4 10 8 6 12"></polyline></svg></span>' +
            (isNew ? '<span class="mem-row-key">新建自动任务</span>' :
                '<span class="automation-status-dot ' + stateClass + '"></span><span class="mem-row-key automation-row-title" title="' + esc(t.prompt || '') + '">' + esc(displayTitle(t)) + '</span>' +
                '<span class="automation-row-schedule">' + esc(scheduleText(t)) + '</span><span class="automation-state ' + stateClass + '">' + state + '</span>') +
            actions +
            '</div>' + (open ? formHtml(t) : '') + '</div>';
    }
    function renderList() {
        var box = document.getElementById('autoList'); if (!box) return;
        var html = expandedId === NEW_ID ? rowHtml({}, true) : '';
        html += tasks.map(function (t) { return rowHtml(t, false); }).join('');
        box.innerHTML = html || '<div class="automation-empty">还没有自动任务<br><span>创建一个任务，让它按计划替你工作</span></div>';
        box.querySelectorAll('.automation-row').forEach(function (row) {
            var id = row.getAttribute('data-id');
            var head = row.querySelector('.automation-row-head');
            function toggle() { closeAllSels(row); expandedId = expandedId === id ? null : id; renderList(); }
            head.onclick = toggle;
            head.onkeydown = function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); } };
            if (row.classList.contains('open')) {
                var t = tasks.find(function (x) { return x.id === id; }) || {};
                populateSelectors(row, t);
                // 调度方式 radio 联动：对齐定时心跳表单（选中另一种时禁用无关输入）
                row.querySelectorAll('input[name=autoScheduleType]').forEach(function (radio) {
                    radio.onchange = function () {
                        var isCron = row.querySelector('input[name=autoScheduleType]:checked').value === 'cron';
                        row.querySelector('.auto-interval').disabled = isCron;
                        row.querySelector('.auto-cron').disabled = !isCron;
                    };
                });
                row.querySelectorAll('.loop-cron-link').forEach(function (link) {
                    link.onclick = function (e) { e.preventDefault(); row.querySelector('.auto-cron').value = link.getAttribute('data-cron'); };
                });
                row.querySelector('.auto-save').onclick = function () { save(row, id); };
                row.querySelector('.auto-cancel').onclick = function () { expandedId = null; renderList(); };
                var del = row.querySelector('.auto-delete');
                if (del) del.onclick = function () { remove(id); };
            }
            if (id === NEW_ID) return;
            // 行内右侧图标按钮：阻止冒泡，避免误触发行展开/收起
            row.querySelector('.auto-toggle').onclick = function (e) { e.stopPropagation(); api('POST', '/web/tasks/toggle', {type: 'AUTOMATION', id: id}).then(load).catch(fail); };
            row.querySelector('.auto-trigger').onclick = function (e) { e.stopPropagation(); api('POST', '/web/tasks/trigger', {type: 'AUTOMATION', id: id}).then(function () { toast('已触发'); load(); }).catch(fail); };
            row.querySelector('.auto-session').onclick = function (e) { e.stopPropagation(); showSession(id); };
        });
    }
    function save(row, id) {
        // 调度方式与后端契约一致：cron 或 intervalMinutes 必须提供一个（后端 validate：另一个可为空）
        var isCron = row.querySelector('input[name=autoScheduleType]:checked').value === 'cron';
        var data = {type: 'AUTOMATION', prompt: row.querySelector('.auto-prompt').value.trim(),
            intervalMinutes: isCron ? '' : row.querySelector('.auto-interval').value,
            cron: isCron ? row.querySelector('.auto-cron').value.trim() : '',
            // 自动任务统一按定时任务创建；不传 taskType，创建走默认、更新时保留旧任务的底层类型。
            // 不传 name：新建时后端自动取提示词前 20 字作为名称，编辑时保留旧名称。
            runNow: 'false',
            modelName: getSel(row, 'model'), agentName: getSel(row, 'agent')};
        if (!data.prompt) { toast('请填写任务描述', 'error'); return; }
        if (isCron && !data.cron) { toast('请填写 Cron 表达式', 'error'); return; }
        if (!isCron && !(parseInt(data.intervalMinutes, 10) > 0)) { toast('请填写有效的固定间隔（分钟）', 'error'); return; }
        var button = row.querySelector('.auto-save');
        button.disabled = true;
        if (id !== NEW_ID) data.id = id;
        api('POST', id === NEW_ID ? '/web/tasks/create' : '/web/tasks/update', data)
            .then(function () { toast('保存成功', 'success'); expandedId = null; load(); })
            .catch(fail).finally(function () { button.disabled = false; });
    }
    function remove(id) {
        if (!confirm('确定删除这个自动任务吗？专用对话会保留归档。')) return;
        api('POST', '/web/tasks/delete', {type:'AUTOMATION', id:id}).then(function () { expandedId = null; load(); }).catch(fail);
    }
    function populateSelectors(row, t) {
        // 输入面板风格选择器：选中值存 row 的 data-agent/data-model 属性，避免 DOM 重建丢失
        var agentBox = row.querySelector('.auto-select[data-kind=agent]');
        var modelBox = row.querySelector('.auto-select[data-kind=model]');
        if (!agentBox || !modelBox) return;
        row.setAttribute('data-agent', t.agentName || '');
        row.setAttribute('data-model', t.modelName || '');
        bindSel(row, 'agent', 'main（主代理）');
        bindSel(row, 'model', '跟随默认模型');
        api('GET', '/web/chat/models').then(function (data) {
            if (!modelBox.isConnected) return;
            var list = (data && data.list) || [];
            var items = list.map(function (x) {
                var v = x.name || x.model;
                return {value: v, label: v, desc: x.desc || ''};
            }).filter(function (x) { return x.value; });
            renderSelItems(modelBox, items, getSel(row, 'model'), '跟随默认模型', '任务执行时使用默认模型');
        }).catch(fail);
        api('GET', '/web/settings/agents').then(function (data) {
            if (!agentBox.isConnected) return;
            var list = data && (data.list || data) || [];
            if (!Array.isArray(list)) list = [];
            var items = list.map(function (x) {
                var v = x.name || x.id || x;
                return {value: v, label: v, desc: (x && x.description) || ''};
            }).filter(function (x) { return x.value; });
            renderSelItems(agentBox, items, getSel(row, 'agent'), 'main（主代理）', '留空时使用主代理');
        }).catch(fail);
    }
    function showSession(id) {
        // 完全复用现有聊天消息列表：解析专用会话后关闭面板，把该会话激活为当前聊天会话。
        // 历史与流式均走原有管线（loadMessages / WebSocket 按 sessionId 路由），不另建渲染逻辑。
        api('GET', '/web/tasks/session?type=AUTOMATION&id=' + encodeURIComponent(id)).then(function (sessionId) {
            if (!sessionId) { toast('未找到专用会话', 'error'); return; }
            hide();
            var sess = getOrCreateSession(sessionId);
            // 专用会话不在左侧历史栏：进入时清空历史高亮，
            // 避免"主区显示 auto 会话、侧栏却亮着旧会话"的误导
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
        }).catch(fail);
    }
    function load() {
        var version = viewVersion;
        api('GET', '/web/tasks/list?type=AUTOMATION').then(function (data) {
            if (version !== viewVersion) return;
            tasks = Array.isArray(data) ? data : [];
            renderList();
            updateBadge();
        }).catch(fail);
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
    // 点击选择器外部时关闭下拉（全局仅注册一次，避免 renderList 重复挂监听）
    document.addEventListener('click', function (e) {
        if (e.target.closest && !e.target.closest('.auto-select')) {
            document.querySelectorAll('.auto-select.open').forEach(function (o) { o.classList.remove('open'); });
        }
    });
    window.openAutomationViewer = show;
})();

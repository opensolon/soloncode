/* 工作区自动任务：列表内展开编辑；查看对话直接复用聊天消息列表（含流式）。 */
(function () {
    'use strict';
    var nav = document.getElementById('automationNavBtn');
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
            var input = document.querySelector('#autoList .auto-name');
            if (input) input.focus();
        };
        render();
        load();
    }
    function hide() {
        if (!viewer || !content.querySelector('.automation-page, .automation-detail')) return;
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
    function formHtml(t) {
        return '<div class="mem-row-body"><div class="automation-form mem-form">' +
            '<div class="automation-form-grid">' +
            '<label class="automation-field"><span>任务名称 <b>*</b></span><input class="auto-name" value="' + esc(t.name || '') + '" placeholder="例如：每日代码检查"></label>' +
            '<label class="automation-field"><span>子代理</span><select class="auto-agent"><option value="">main（主代理）</option>' + (t.agentName ? '<option value="' + esc(t.agentName) + '" selected>' + esc(t.agentName) + '</option>' : '') + '</select><small>留空时使用主代理。</small></label>' +
            '<label class="automation-field"><span>模型</span><select class="auto-model"><option value="">跟随默认模型</option>' + (t.modelName ? '<option value="' + esc(t.modelName) + '" selected>' + esc(t.modelName) + '</option>' : '') + '</select><small>留空时跟随默认模型。</small></label>' +
            '<label class="automation-field"><span>固定间隔（分钟）</span><input class="auto-interval" type="number" min="1" value="' + esc(t.intervalMinutes || 5) + '" placeholder="5"></label>' +
            '<label class="automation-field"><span>Cron 表达式 <em>可选</em></span><input class="auto-cron" value="' + esc(t.cron || '') + '" placeholder="0 */5 * * * ? *"></label>' +
            '</div><label class="automation-field"><span>执行提示词 <b>*</b></span><textarea class="auto-prompt" rows="5" placeholder="描述自动任务需要完成的事情">' + esc(t.prompt || '') + '</textarea><small>任务在专用会话中执行，不会出现在普通会话列表。</small></label>' +
            '<div class="mem-actions"><button class="memory-btn memory-btn-primary auto-save" type="button">保存任务</button>' +
            '<button class="memory-btn auto-cancel" type="button">取消</button>' +
            (t.id ? '<button class="memory-btn memory-btn-danger auto-delete" type="button">删除</button>' : '') +
            '</div></div></div>';
    }
    function rowHtml(t, isNew) {
        var open = expandedId === (isNew ? NEW_ID : t.id);
        var state = t.running ? '执行中' : (t.enabled ? '已启用' : '已暂停');
        var stateClass = t.running ? 'running' : (t.enabled ? 'enabled' : 'paused');
        return '<div class="mem-row automation-row' + (open ? ' open' : '') + '" data-id="' + esc(isNew ? NEW_ID : t.id) + '">' +
            '<div class="mem-row-head automation-row-head" role="button" tabindex="0" aria-expanded="' + open + '">' +
            '<span class="mem-caret"><svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 4 10 8 6 12"></polyline></svg></span>' +
            (isNew ? '<span class="mem-row-key">新建自动任务</span>' :
                '<span class="automation-status-dot ' + stateClass + '"></span><span class="mem-row-key automation-row-title">' + esc(t.name || '自动任务') + '</span>' +
                '<span class="automation-row-schedule">' + esc(scheduleText(t)) + '</span><span class="automation-state ' + stateClass + '">' + state + '</span>') +
            '</div>' + (open ? formHtml(t) : '') +
            (!isNew ? '<div class="automation-row-summary">' + esc(t.prompt || '') + '</div>' +
                '<div class="automation-row-actions"><button class="memory-btn auto-toggle" type="button">' + (t.enabled ? '暂停' : '恢复') + '</button>' +
                '<button class="memory-btn auto-trigger" type="button">立即执行</button>' +
                '<button class="memory-btn auto-runs" type="button">执行记录</button>' +
                '<button class="memory-btn auto-session" type="button">查看对话</button></div>' : '') + '</div>';
    }
    function renderList() {
        var box = document.getElementById('autoList'); if (!box) return;
        var html = expandedId === NEW_ID ? rowHtml({}, true) : '';
        html += tasks.map(function (t) { return rowHtml(t, false); }).join('');
        box.innerHTML = html || '<div class="automation-empty">还没有自动任务<br><span>创建一个任务，让它按计划替你工作</span></div>';
        box.querySelectorAll('.automation-row').forEach(function (row) {
            var id = row.getAttribute('data-id');
            var head = row.querySelector('.automation-row-head');
            function toggle() { expandedId = expandedId === id ? null : id; renderList(); }
            head.onclick = toggle;
            head.onkeydown = function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); } };
            if (row.classList.contains('open')) {
                var t = tasks.find(function (x) { return x.id === id; }) || {};
                populateSelectors(row, t);
                row.querySelector('.auto-save').onclick = function () { save(row, id); };
                row.querySelector('.auto-cancel').onclick = function () { expandedId = null; renderList(); };
                var del = row.querySelector('.auto-delete');
                if (del) del.onclick = function () { remove(id); };
            }
            if (id === NEW_ID) return;
            row.querySelector('.auto-toggle').onclick = function () {
        api('POST', '/web/tasks/toggle', {type:'AUTOMATION', id:id}).then(load).catch(fail);
            };
            row.querySelector('.auto-trigger').onclick = function () {
        api('POST', '/web/tasks/trigger', {type:'AUTOMATION', id:id}).then(function () { toast('已触发'); load(); }).catch(fail);
            };
            row.querySelector('.auto-runs').onclick = function () { showRuns(id); };
            row.querySelector('.auto-session').onclick = function () { showSession(id); };
        });
    }
    function save(row, id) {
        var data = {type: 'AUTOMATION', name: row.querySelector('.auto-name').value.trim(), prompt: row.querySelector('.auto-prompt').value.trim(),
            intervalMinutes: row.querySelector('.auto-interval').value, cron: row.querySelector('.auto-cron').value.trim(),
            // 自动任务统一按定时任务创建；不传 taskType，创建走默认、更新时保留旧任务的底层类型。
            runNow: 'false',
            modelName: row.querySelector('.auto-model').value, agentName: row.querySelector('.auto-agent').value};
        if (!data.name || !data.prompt) { toast('请填写任务名称和执行提示词', 'error'); return; }
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
        var model = row.querySelector('.auto-model');
        var agent = row.querySelector('.auto-agent');
        api('GET', '/web/chat/models').then(function (data) {
            if (!model.isConnected) return;
            var list = data && data.list || [];
            var selected = model.value;
            model.innerHTML = '<option value="">跟随默认模型</option>' + list.map(function (x) {
                var v = x.name || x.model; return '<option value="' + esc(v) + '">' + esc(v) + '</option>';
            }).join('');
            if (selected && !Array.prototype.some.call(model.options, function (o) { return o.value === selected; })) {
                model.add(new Option(selected, selected));
            }
            model.value = selected;
        }).catch(fail);
        api('GET', '/web/settings/agents').then(function (data) {
            if (!agent.isConnected) return;
            var list = data && (data.list || data) || [];
            if (!Array.isArray(list)) list = [];
            var selected = agent.value;
            agent.innerHTML = '<option value="">main（主代理）</option>' + list.map(function (x) {
                var v = x.name || x.id || x; return '<option value="' + esc(v) + '">' + esc(v) + '</option>';
            }).join('');
            if (selected && !Array.prototype.some.call(agent.options, function (o) { return o.value === selected; })) {
                agent.add(new Option(selected, selected));
            }
            agent.value = selected;
        }).catch(fail);
    }
    function detail(title, body) {
        content.innerHTML = '<div class="automation-detail"><div class="automation-detail-head"><button class="memory-btn auto-back" type="button">← 返回任务</button><h2>' + esc(title) + '</h2></div>' + body + '</div>';
        content.querySelector('.auto-back').onclick = function () { viewVersion++; render(); };
    }
    function showRuns(id) {
        var version = ++viewVersion;
        detail('执行记录', '<div class="automation-empty">正在加载…</div>');
        api('GET', '/web/tasks/runs?type=AUTOMATION&id=' + encodeURIComponent(id) + '&limit=50').then(function (runs) {
            if (version !== viewVersion || !content.querySelector('.automation-detail')) return;
            var rows = Array.isArray(runs) ? runs : [];
            detail('执行记录', rows.length ? rows.map(function (r) {
                return '<div class="automation-run"><strong>' + esc(r.status) + '</strong> · ' + esc(r.startedAt || r.at || '') +
                    '<div>' + esc(r.error || r.result || '') + '</div></div>';
            }).join('') : '<div class="automation-empty">暂无执行记录</div>');
        }).catch(fail);
    }
    function showSession(id) {
        // 完全复用现有聊天消息列表：解析专用会话后关闭面板，把该会话激活为当前聊天会话。
        // 历史与流式均走原有管线（loadMessages / WebSocket 按 sessionId 路由），不另建渲染逻辑。
        api('GET', '/web/tasks/session?type=AUTOMATION&id=' + encodeURIComponent(id)).then(function (sessionId) {
            if (!sessionId) { toast('未找到专用会话', 'error'); return; }
            hide();
            var sess = getOrCreateSession(sessionId);
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
        }).catch(fail);
    }
    if (nav) nav.addEventListener('click', show);
    if (closeBtn) closeBtn.addEventListener('click', hide);
    window.openAutomationViewer = show;
})();

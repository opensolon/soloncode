/* ===== app-todos.js ===== */
/* 任务面板：读取并展示当前会话的 TODO.md 任务清单 */

(function() {
    var todoBadge = document.getElementById('todoBadge');
    var todoList = document.getElementById('todoList');
    var todoEmpty = document.getElementById('todoEmpty');
    var todoStats = document.getElementById('todoStats');
    var todoRefreshBtn = document.getElementById('todoRefreshBtn');

    function loadTodos() {
        var sid = typeof SESSION_ID !== 'undefined' ? SESSION_ID : null;
        if (!sid) return;

        fetch('/web/chat/todos?sessionId=' + encodeURIComponent(sid))
            .then(function(r) { return r.json(); })
            .then(function(res) {
                renderTodos(res && res.data ? res.data : {});
            })
            .catch(function() {
                renderError();
            });
    }

    function renderTodos(data) {
        var items = data.items || [];
        var stats = data.stats || {};

        // badge
        if (todoBadge) {
            var pending = (stats.pending || 0) + (stats.inProgress || 0);
            todoBadge.textContent = pending;
            todoBadge.style.display = pending > 0 ? '' : 'none';
        }

        // empty state
        if (!data.exists || items.length === 0) {
            todoList.innerHTML = '';
            todoEmpty.style.display = '';
            todoEmpty.textContent = data.exists ? '暂无任务' : '当前会话暂无任务清单';
            todoStats.style.display = 'none';
            // 清理会话级缓存
            var sid = typeof SESSION_ID !== 'undefined' ? SESSION_ID : null;
            if (sid) delete (window.sessionTodoMap || {})[sid];
            if (typeof updateHistoryUI === 'function') updateHistoryUI();
            return;
        }

        todoEmpty.style.display = 'none';
        todoStats.style.display = '';
        todoStats.textContent = '(' + stats.done + ' / ' + stats.total + ')';

        // 写入会话级缓存，驱动侧边栏 badge 更新
        window.sessionTodoMap = window.sessionTodoMap || {};
        var sid = typeof SESSION_ID !== 'undefined' ? SESSION_ID : null;
        if (sid) {
            window.sessionTodoMap[sid] = { done: stats.done || 0, total: stats.total || 0 };
            if (typeof updateHistoryUI === 'function') updateHistoryUI();
        }

        renderTodoItems(items);
    }

    function statusIcon(status) {
        if (status === 'done') return '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle;"><polyline points="20 6 9 17 4 12"/></svg>';
        if (status === 'in_progress') return '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" stroke="none" style="vertical-align: middle;"><polygon points="5 3 19 12 5 21 5 3"/></svg>';
        return '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle;"><circle cx="12" cy="12" r="10"/></svg>';
    }

    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, function(c) {
            return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
        });
    }

    // 仅渲染右侧任务面板的条目列表（不触发额外请求）
    function renderTodoItems(items) {
        if (!todoList) return;
        if (!items || items.length === 0) {
            todoList.innerHTML = '';
            todoEmpty.style.display = '';
            todoEmpty.textContent = '暂无任务';
            return;
        }
        todoEmpty.style.display = 'none';
        var html = '';
        var lastGroup = '';
        for (var i = 0; i < items.length; i++) {
            var item = items[i];
            if (item.group && item.group !== lastGroup) {
                html += '<div class="todo-group">' + escapeHtml(item.group) + '</div>';
                lastGroup = item.group;
            }
            html += '<div class="todo-item todo-' + item.status + '">' +
                '<span class="todo-check">' + statusIcon(item.status) + '</span>' +
                '<span class="todo-text">' + escapeHtml(item.text) + '</span>' +
                '</div>';
        }
        todoList.innerHTML = html;
    }

    // 从 todowrite 的原始 markdown 解析任务条目（与后端 /web/chat/todos 的解析逻辑保持一致）
    function parseTodoMarkdown(raw) {
        var items = [];
        var currentGroup = '';
        var lines = String(raw || '').split('\n');
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            // ## 标题作为分组
            if (/^\s*##\s+.+$/.test(line)) {
                currentGroup = line.replace(/^\s*##\s+/, '').trim();
                continue;
            }
            // checkbox 行: - [ ] / - [/] / - [x] / - [X]
            var m = line.match(/^\s*-\s*\[([ xX/])\]\s+(.+)$/);
            if (m) {
                var statusChar = m[1];
                var status = statusChar === ' ' ? 'pending'
                    : (statusChar === '/' ? 'in_progress' : 'done');
                items.push({ status: status, text: m[2].trim(), group: currentGroup });
            }
        }
        return items;
    }

    function renderError() {
        todoList.innerHTML = '';
        todoEmpty.style.display = '';
        todoEmpty.textContent = '\u52A0\u8F7D\u4EFB\u52A1\u6E05\u5355\u5931\u8D25';
        todoStats.style.display = 'none';
        if (todoBadge) todoBadge.style.display = 'none';
    }

    // refresh button
    if (todoRefreshBtn) {
        todoRefreshBtn.addEventListener('click', loadTodos);
    }

    // 监听 WebSocket action chunk，当 todowrite 完成时直接从返回值提取统计
    if (typeof window._todoChunkHandlers === 'undefined') {
        window._todoChunkHandlers = [];
    }
    window._todoChunkHandlers.push(function(chunk) {
        if (chunk && chunk.toolName === 'todowrite') {
            var rawText = chunk.text || '';

            // 直接从 chunk.text 提取统计，避免二次请求
            var match = rawText.match(/\(total:\s*(\d+),\s*done:\s*(\d+),\s*in-progress:\s*(\d+),\s*pending:\s*(\d+)\)/);
            var stats;
            if (match) {
                stats = {
                    total: parseInt(match[1]),
                    done: parseInt(match[2]),
                    inProgress: parseInt(match[3]),
                    pending: parseInt(match[4])
                };
            } else {
                // 兜底：格式不匹配时从 raw markdown 直接解析 checkbox 统计
                var total = 0, done = 0, inProgress = 0;
                var lines = rawText.split('\n');
                for (var i = 0; i < lines.length; i++) {
                    var line = lines[i];
                    if (/\- \[[ x/]\]/.test(line)) {
                        total++;
                        if (/\- \[x\]/i.test(line)) done++;
                        else if (/\- \[\/\]/.test(line)) inProgress++;
                    }
                }
                stats = {
                    total: total,
                    done: done,
                    inProgress: inProgress,
                    pending: total - done - inProgress
                };
            }

            // 更新右侧面板统计
            if (todoBadge) {
                var pending = (stats.pending || 0) + (stats.inProgress || 0);
                todoBadge.textContent = pending;
                todoBadge.style.display = pending > 0 ? '' : 'none';
            }
            if (todoStats && stats.total > 0) {
                todoStats.style.display = '';
                todoStats.textContent = '(' + stats.done + ' / ' + stats.total + ')';
            }

            // 写入会话级缓存，驱动侧边栏 badge 更新（使用 chunk.sessionId 而非 SESSION_ID）
            window.sessionTodoMap = window.sessionTodoMap || {};
            var sid = chunk.sessionId;
            if (sid) {
                window.sessionTodoMap[sid] = { done: stats.done, total: stats.total };
                if (typeof updateHistoryUI === 'function') updateHistoryUI();
            }

            // 实时重渲染右侧任务面板条目列表（仅当 chunk 属于当前展示的会话时）
            var currentSid = typeof SESSION_ID !== 'undefined' ? SESSION_ID : null;
            if (sid && currentSid && sid === currentSid) {
                renderTodoItems(parseTodoMarkdown(rawText));
            }
        }
    });

    // expose for external calls
    window.loadTodos = loadTodos;
})();

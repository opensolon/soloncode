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
            return;
        }

        todoEmpty.style.display = 'none';
        todoStats.style.display = '';
        todoStats.textContent = '(' + stats.done + ' / ' + stats.total + ')';

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

    function statusIcon(status) {
        if (status === 'done') return '\u2713';
        if (status === 'in_progress') return '\u25B6';
        return '\u25CB';
    }

    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, function(c) {
            return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
        });
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

    // 监听 WebSocket action chunk，当 todowrite 完成时自动刷新
    if (typeof window._todoChunkHandlers === 'undefined') {
        window._todoChunkHandlers = [];
    }
    window._todoChunkHandlers.push(function(chunk) {
        if (chunk && chunk.toolName === 'todowrite') {
            loadTodos();
        }
    });

    // expose for external calls
    window.loadTodos = loadTodos;
})();

/* ===== app-gitdiff.js ===== */
/* Filer Panel Git Diff 面板：三态检测、文件列表（带勾选）、Diff Viewer 内联查看、精确提交 */

(function() {
    // ---- DOM 元素 ----
    var tabs = document.querySelectorAll('.filer-tab');
    var tabContents = document.querySelectorAll('.filer-tab-content');
    var gitUnavailable = document.getElementById('gitUnavailable');
    var gitUninitialized = document.getElementById('gitUninitialized');
    var gitDiffPanel = document.getElementById('gitDiffPanel');
    var gitBadge = document.getElementById('gitBadge');
    var gitBranch = document.getElementById('gitBranch');
    var gitDiffFileList = document.getElementById('gitDiffFileList');
    var gitDiffEmpty = document.getElementById('gitDiffEmpty');
    var gitInitBtn = document.getElementById('gitInitBtn');
    var gitInitCommit = document.getElementById('gitInitCommit');
    var gitCommitBtn = document.getElementById('gitCommitBtn');
    var gitCommitMsg = document.getElementById('gitCommitMsg');
    var gitCommitBar = document.getElementById('gitCommitBar');
    var gitSelectAll = document.getElementById('gitSelectAll');

    // Diff Viewer 元素（内联在 main-area 内）
    var gitDiffViewer = document.getElementById('gitDiffViewer');
    var gitViewerFile = document.getElementById('gitViewerFile');
    var gitViewerContent = document.getElementById('gitViewerContent');
    var gitViewerClose = document.getElementById('gitViewerClose');
    // main-area 子视图引用
    var welcomeView = document.getElementById('welcomeView');
    var chatView = document.getElementById('chatView');

    // ---- 状态 ----
    var gitStatus = null;
    var isInitializing = false;

    // ---- Tab 切换 ----
    tabs.forEach(function(tab) {
        tab.addEventListener('click', function() {
            var targetTab = this.getAttribute('data-tab');
            tabs.forEach(function(t) { t.classList.remove('active'); });
            tabContents.forEach(function(tc) { tc.classList.remove('active'); });
            this.classList.add('active');

            var contentId = 'tabContent' + targetTab.charAt(0).toUpperCase() + targetTab.slice(1);
            var contentEl = document.getElementById(contentId);
            if (contentEl) contentEl.classList.add('active');

            // 切到 Git tab 时刷新
            if (targetTab === 'gitdiff') loadGitStatus();
            // 持久化
            localStorage.setItem('filer-active-tab', targetTab);
        });
    });

    // 恢复 Tab 状态
    var savedTab = localStorage.getItem('filer-active-tab');
    if (savedTab === 'gitdiff') {
        tabs.forEach(function(t) { t.classList.remove('active'); });
        tabContents.forEach(function(tc) { tc.classList.remove('active'); });
        var gitTab = document.querySelector('.filer-tab[data-tab="gitdiff"]');
        var gitContent = document.getElementById('tabContentGitdiff');
        if (gitTab) gitTab.classList.add('active');
        if (gitContent) gitContent.classList.add('active');
    }

    // ---- 显示/隐藏状态区 ----
    function showState(state) {
        if (gitUnavailable) gitUnavailable.style.display = 'none';
        if (gitUninitialized) gitUninitialized.style.display = 'none';
        if (gitDiffPanel) gitDiffPanel.style.display = 'none';

        if (state === 'unavailable' && gitUnavailable) gitUnavailable.style.display = '';
        else if (state === 'uninitialized' && gitUninitialized) gitUninitialized.style.display = '';
        else if (state === 'ready' && gitDiffPanel) gitDiffPanel.style.display = '';
    }

    // ---- 加载 Git 状态 ----
    function loadGitStatus() {
        fetch('/chat/git/status')
            .then(function(r) { return r.json(); })
            .then(function(res) {
                var data = (res && res.data) ? res.data : {};
                gitStatus = data;

                if (!data.gitAvailable) {
                    showState('unavailable');
                    return;
                }
                if (!data.initialized) {
                    showState('uninitialized');
                    updateBadge(0);
                    return;
                }

                showState('ready');
                renderBranch(data.branch);
                renderFileList(data);
                updateBadge(
                    (data.changed || []).length +
                    (data.staged || []).length +
                    (data.untracked || []).length
                );
            })
            .catch(function(e) {
                console.error('[gitdiff] status error', e);
                showState('unavailable');
            });
    }

    // ---- 渲染分支名 ----
    function renderBranch(branch) {
        if (gitBranch) gitBranch.textContent = branch || '--';
    }

    // ---- 渲染文件列表（带 checkbox）----
    function renderFileList(data) {
        if (!gitDiffFileList) return;
        gitDiffFileList.innerHTML = '';

        var files = [];

        // 已暂存
        (data.staged || []).forEach(function(p) {
            files.push({ path: p, status: 'S' });
        });
        // 已修改（未暂存）
        (data.changed || []).forEach(function(p) {
            files.push({ path: p, status: 'M' });
        });
        // 未跟踪
        (data.untracked || []).forEach(function(p) {
            files.push({ path: p, status: '?' });
        });

        if (files.length === 0) {
            if (gitDiffEmpty) gitDiffEmpty.style.display = '';
            gitDiffFileList.style.display = 'none';
            if (gitCommitBar) gitCommitBar.style.display = 'none';
            return;
        }
        if (gitDiffEmpty) gitDiffEmpty.style.display = 'none';
        gitDiffFileList.style.display = '';
        if (gitCommitBar) gitCommitBar.style.display = '';

        // 全选默认勾选
        if (gitSelectAll) gitSelectAll.checked = true;

        files.forEach(function(file) {
            var item = document.createElement('div');
            item.className = 'git-file-item';

            // checkbox
            var cb = document.createElement('input');
            cb.type = 'checkbox';
            cb.className = 'git-file-checkbox';
            cb.checked = true;
            cb.setAttribute('data-path', file.path);
            cb.addEventListener('click', function(e) {
                e.stopPropagation(); // 防止触发外层 click 打开 diff
                syncSelectAll();
            });

            // 状态字母
            var statusSpan = document.createElement('span');
            statusSpan.className = 'git-status-letter ' + file.status;
            statusSpan.textContent = file.status;

            // 文件路径
            var pathSpan = document.createElement('span');
            pathSpan.className = 'git-file-path';
            pathSpan.title = file.path;
            pathSpan.textContent = file.path;

            item.appendChild(cb);
            item.appendChild(statusSpan);
            item.appendChild(pathSpan);

            item.setAttribute('data-status', file.status);

            // 点击文件行打开 diff viewer
            item.addEventListener('click', function(e) {
                // 避免点 checkbox 时也触发
                if (e.target === cb) return;
                openDiffViewer(file.path, file.status);
            });

            gitDiffFileList.appendChild(item);
        });
    }

    // ---- 同步全选 checkbox 状态 ----
    function syncSelectAll() {
        if (!gitSelectAll || !gitDiffFileList) return;
        var all = gitDiffFileList.querySelectorAll('.git-file-checkbox');
        var checked = gitDiffFileList.querySelectorAll('.git-file-checkbox:checked');
        gitSelectAll.checked = (all.length > 0 && all.length === checked.length);
    }

    // ---- 全选/取消全选 ----
    if (gitSelectAll) {
        gitSelectAll.addEventListener('change', function() {
            if (!gitDiffFileList) return;
            var cbs = gitDiffFileList.querySelectorAll('.git-file-checkbox');
            var val = this.checked;
            cbs.forEach(function(cb) { cb.checked = val; });
        });
    }

    // ---- 获取选中的文件路径列表 ----
    function getSelectedFiles() {
        if (!gitDiffFileList) return [];
        var checked = gitDiffFileList.querySelectorAll('.git-file-checkbox:checked');
        var paths = [];
        checked.forEach(function(cb) {
            var p = cb.getAttribute('data-path');
            if (p) paths.push(p);
        });
        return paths;
    }

    // ---- Diff Viewer：打开内联 diff（在 main-area 内）----
    var diffViewerActive = false;

    function openDiffViewer(path, status) {
        if (!gitDiffViewer) return;

        // 隐藏欢迎页和聊天视图
        if (welcomeView) welcomeView.style.display = 'none';
        if (chatView) chatView.style.display = 'none';

        // 显示 diff viewer
        gitDiffViewer.style.display = 'flex';
        diffViewerActive = true;

        if (gitViewerFile) gitViewerFile.textContent = path;

        // 判断是否是目录（以 / 结尾）
        var isDir = path.endsWith('/');

        if (isDir) {
            // 目录：显示提示信息，不调用 diff 接口
            if (gitViewerContent) {
                gitViewerContent.innerHTML = '<div style="padding:20px;color:var(--text-secondary)">'
                    + '<div style="margin-bottom:8px">&#x1F4C1; ' + escapeHtml(path) + '</div>'
                    + '<div>这是一个目录，暂无可查看的文本差异。</div>'
                    + '</div>';
            }
            renderViewerActions(path, status);
            return;
        }

        if (gitViewerContent) gitViewerContent.innerHTML = '<div style="padding:20px;color:var(--text-secondary)">加载中...</div>';

        fetch('/chat/git/diff?path=' + encodeURIComponent(path))
            .then(function(r) { return r.json(); })
            .then(function(res) {
                var d = (res && res.data) ? res.data : {};
                var diffText = d.diff || '';
                if (!diffText.trim()) {
                    gitViewerContent.innerHTML = '<div style="padding:20px;color:var(--text-secondary)">'
                        + (status === '?' ? '新文件（未跟踪），暂无可比较的差异内容。' : '无变更内容。')
                        + '</div>';
                } else {
                    renderViewerDiff(diffText);
                }
            })
            .catch(function(e) {
                if (gitViewerContent) gitViewerContent.innerHTML = '<div style="padding:20px;color:#cb2431">加载失败: ' + escapeHtml(e.message) + '</div>';
            })
            .finally(function() {
                renderViewerActions(path, status);
            });
    }

    // ---- Diff Viewer：渲染操作按钮（添加到Git / 移出暂存）----
    function renderViewerActions(path, status) {
        // 移除旧的操作栏（如有）
        var oldActions = gitDiffViewer.querySelector('.git-viewer-actions');
        if (oldActions) oldActions.remove();

        if (status !== '?' && status !== 'S') return; // 只有未跟踪和已暂存需要操作按钮

        var actionBar = document.createElement('div');
        actionBar.className = 'git-viewer-actions';

        if (status === '?') {
            // 未跟踪 -> 提供 "添加到 Git" 按钮
            var addBtn = document.createElement('button');
            addBtn.className = 'git-action-btn git-action-add';
            addBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg> 添加到 Git';
            addBtn.addEventListener('click', function() {
                addBtn.disabled = true;
                addBtn.textContent = '添加中...';
                fetch('/chat/git/stage', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ path: path })
                })
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (res && res.code === 200) {
                        loadGitStatus();
                        closeDiffViewer();
                    } else {
                        alert('操作失败：' + ((res && res.data && res.data.message) || '未知错误'));
                        addBtn.disabled = false;
                        addBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg> 添加到 Git';
                    }
                })
                .catch(function(e) {
                    alert('操作失败：' + e.message);
                    addBtn.disabled = false;
                    addBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg> 添加到 Git';
                });
            });
            actionBar.appendChild(addBtn);
        }

        if (status === 'S') {
            // 已暂存 -> 提供 "移出暂存" 按钮
            var unstageBtn = document.createElement('button');
            unstageBtn.className = 'git-action-btn git-action-unstage';
            unstageBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="5" y1="12" x2="19" y2="12"/></svg> 移出暂存';
            unstageBtn.addEventListener('click', function() {
                unstageBtn.disabled = true;
                unstageBtn.textContent = '移出中...';
                fetch('/chat/git/unstage', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ path: path })
                })
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (res && res.code === 200) {
                        loadGitStatus();
                        closeDiffViewer();
                    } else {
                        alert('操作失败：' + ((res && res.data && res.data.message) || '未知错误'));
                        unstageBtn.disabled = false;
                        unstageBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="5" y1="12" x2="19" y2="12"/></svg> 移出暂存';
                    }
                })
                .catch(function(e) {
                    alert('操作失败：' + e.message);
                    unstageBtn.disabled = false;
                    unstageBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="5" y1="12" x2="19" y2="12"/></svg> 移出暂存';
                });
            });
            actionBar.appendChild(unstageBtn);
        }

        // 插入到 header 后面、content 前面
        var content = gitDiffViewer.querySelector('.git-viewer-content');
        if (content) {
            gitDiffViewer.insertBefore(actionBar, content);
        }
    }

    // ---- Diff Viewer：渲染 diff 文本（带行号）----
    function renderViewerDiff(raw) {
        if (!gitViewerContent) return;
        var lines = (raw || '').split('\n');
        var html = '';
        var oldLineNo = 0, newLineNo = 0;
        var hunkRe = /^@@\s+-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?\s+@@/;

        for (var i = 0; i < lines.length; i++) {
            var rawLine = lines[i];
            var line = escapeHtml(rawLine);

            if (rawLine.startsWith('+++') || rawLine.startsWith('---')) {
                // 元信息行：无行号
                html += '<div class="git-diff-line git-line-head">'
                    + '<span class="git-line-num"></span>'
                    + '<span class="git-line-num"></span>'
                    + '<span class="git-line-text">' + line + '</span>'
                    + '</div>';
            } else if (rawLine.startsWith('@@')) {
                // Hunk header：解析行号并重置计数器
                var m = rawLine.match(hunkRe);
                if (m) {
                    oldLineNo = parseInt(m[1], 10);
                    newLineNo = parseInt(m[2], 10);
                }
                html += '<div class="git-diff-line git-line-hunk">'
                    + '<span class="git-line-num"></span>'
                    + '<span class="git-line-num"></span>'
                    + '<span class="git-line-text">' + line + '</span>'
                    + '</div>';
            } else if (rawLine.startsWith('+')) {
                // 新增行：new 行号递增，old 留空
                html += '<div class="git-diff-line git-line-add">'
                    + '<span class="git-line-num"></span>'
                    + '<span class="git-line-num">' + (newLineNo++) + '</span>'
                    + '<span class="git-line-text">' + line + '</span>'
                    + '</div>';
            } else if (rawLine.startsWith('-')) {
                // 删除行：old 行号递增，new 留空
                html += '<div class="git-diff-line git-line-del">'
                    + '<span class="git-line-num">' + (oldLineNo++) + '</span>'
                    + '<span class="git-line-num"></span>'
                    + '<span class="git-line-text">' + line + '</span>'
                    + '</div>';
            } else {
                // 上下文行：两个行号同时递增
                html += '<div class="git-diff-line git-line-ctx">'
                    + '<span class="git-line-num">' + (oldLineNo++) + '</span>'
                    + '<span class="git-line-num">' + (newLineNo++) + '</span>'
                    + '<span class="git-line-text">' + line + '</span>'
                    + '</div>';
            }
        }
        gitViewerContent.innerHTML = html;
        gitViewerContent.scrollTop = 0;
    }

    // ---- Diff Viewer：关闭，恢复原始视图 ----
    function closeDiffViewer() {
        if (!gitDiffViewer) return;
        gitDiffViewer.style.display = 'none';
        diffViewerActive = false;

        // 清理操作栏
        var oldActions = gitDiffViewer.querySelector('.git-viewer-actions');
        if (oldActions) oldActions.remove();

        // 关键：必须先清除两个视图的内联 display 样式
        // 因为 chatView 的可见性由 CSS .active 类控制（.chat-view.active { display: flex }）
        // 如果残留 style="display:none"，会覆盖 CSS 类规则，导致视图空白
        if (chatView) chatView.style.display = '';
        if (welcomeView) welcomeView.style.display = '';

        // 根据当前模式恢复正确的可见性
        // chatView 可见性由 .active 类控制（CSS 规则），无需额外操作
        // welcomeView 仅在非聊天模式下可见
        if (chatView && chatView.classList.contains('active')) {
            welcomeView.style.display = 'none';
        }
    }

    if (gitViewerClose) {
        gitViewerClose.addEventListener('click', closeDiffViewer);
    }

    // ESC 关闭 diff viewer
    document.addEventListener('keydown', function(e) {
        if (e.key === 'Escape' && diffViewerActive) {
            closeDiffViewer();
        }
    });

    // ---- Git 提交（精确文件列表）----
    var isCommitting = false;
    if (gitCommitBtn) {
        gitCommitBtn.addEventListener('click', function() {
            if (isCommitting) return;
            var msg = (gitCommitMsg && gitCommitMsg.value.trim()) || '';
            if (!msg) {
                gitCommitMsg && gitCommitMsg.focus();
                return;
            }
            var files = getSelectedFiles();
            if (files.length === 0) {
                alert('请至少勾选一个文件');
                return;
            }

            isCommitting = true;
            gitCommitBtn.disabled = true;
            gitCommitBtn.innerHTML = '<span style="opacity:0.7">提交中...</span>';

            fetch('/chat/git/commit', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ message: msg, files: files })
            })
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (res && res.code === 200) {
                        if (gitCommitMsg) gitCommitMsg.value = '';
                        loadGitStatus();
                    } else {
                        alert('提交失败：' + ((res && res.data && res.data.message) || '未知错误'));
                    }
                })
                .catch(function(e) {
                    alert('提交失败：' + e.message);
                })
                .finally(function() {
                    isCommitting = false;
                    gitCommitBtn.disabled = false;
                    gitCommitBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg> 提交';
                });
        });

        // Enter 键提交
        if (gitCommitMsg) {
            gitCommitMsg.addEventListener('keydown', function(e) {
                if (e.key === 'Enter' && !e.isComposing) {
                    e.preventDefault();
                    gitCommitBtn.click();
                }
            });
        }
    }

    // ---- 初始化 Git 仓库 ----
    if (gitInitBtn) {
        gitInitBtn.addEventListener('click', function() {
            if (isInitializing) return;
            isInitializing = true;
            gitInitBtn.disabled = true;
            gitInitBtn.textContent = '初始化中...';

            var doCommit = gitInitCommit && gitInitCommit.checked;
            fetch('/chat/git/init?initialCommit=' + (doCommit ? 'true' : 'false'), { method: 'POST' })
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (res && res.code === 200) {
                        loadGitStatus();
                    } else {
                        alert('初始化失败：' + ((res && res.data && res.data.message) || '未知错误'));
                        gitInitBtn.disabled = false;
                        gitInitBtn.innerHTML =
                            '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg> 初始化 Git 仓库';
                    }
                })
                .catch(function(e) {
                    alert('初始化失败：' + e.message);
                    gitInitBtn.disabled = false;
                    gitInitBtn.innerHTML =
                        '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg> 初始化 Git 仓库';
                })
                .finally(function() {
                    isInitializing = false;
                });
        });
    }

    // ---- Badge 更新 ----
    function updateBadge(count) {
        if (!gitBadge) return;
        if (count > 0) {
            gitBadge.textContent = count > 99 ? '99+' : count;
            gitBadge.style.display = 'inline';
        } else {
            gitBadge.style.display = 'none';
        }
    }

    // ---- WebSocket 联动：文件变更时刷新 git status ----
    var origOnFilerChange = window.onFilerChange;
    window.onFilerChange = function(chunk) {
        if (origOnFilerChange) origOnFilerChange(chunk);

        // 如果当前在 Git tab 上且面板可见，debounce 后刷新
        var gitTab = document.querySelector('.filer-tab[data-tab="gitdiff"]');
        if (gitTab && gitTab.classList.contains('active') && gitDiffPanel && gitDiffPanel.style.display !== 'none') {
            clearTimeout(window._gitDiffRefreshTimer);
            window._gitDiffRefreshTimer = setTimeout(loadGitStatus, 1500);
        } else {
            // 不在 Git tab 上，后台静默刷新 badge
            clearTimeout(window._gitBadgeRefreshTimer);
            window._gitBadgeRefreshTimer = setTimeout(loadGitStatus, 2000);
        }
    };

    // ---- 工具函数 ----
    function escapeHtml(str) {
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(str));
        return div.innerHTML;
    }

    // ---- 初始化 ----
    loadGitStatus();
    // 每60秒兜底刷新
    setInterval(loadGitStatus, 60000);

    // 暴露全局（调试用）
    window.loadGitStatus = loadGitStatus;
})();

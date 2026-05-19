/* ===== app-gitdiff.js ===== */
/* Filer Panel Git Diff 面板：三态检测（可用/未初始化/不可用）、文件列表、Diff 渲染、WebSocket 联动 */

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
    var gitDiffViewer = document.getElementById('gitDiffViewer');
    var gitDiffContent = document.getElementById('gitDiffContent');
    var gitDiffFilePath = document.getElementById('gitDiffFilePath');
    var gitDiffEmpty = document.getElementById('gitDiffEmpty');
    var gitDiffBack = document.getElementById('gitDiffBack');
    var gitRefreshBtn = document.getElementById('gitRefreshBtn');
    var gitInitBtn = document.getElementById('gitInitBtn');
    var gitInitCommit = document.getElementById('gitInitCommit');

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

    // ---- 渲染文件列表 ----
    function renderFileList(data) {
        if (!gitDiffFileList) return;
        gitDiffFileList.innerHTML = '';
        if (gitDiffViewer) gitDiffViewer.style.display = 'none';

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
            return;
        }
        if (gitDiffEmpty) gitDiffEmpty.style.display = 'none';
        gitDiffFileList.style.display = '';

        files.forEach(function(file) {
            var item = document.createElement('div');
            item.className = 'git-file-item';
            item.innerHTML =
                '<span class="git-status-letter ' + file.status + '">' + file.status + '</span>' +
                '<span class="git-file-path" title="' + escapeHtml(file.path) + '">' + escapeHtml(file.path) + '</span>';
            item.addEventListener('click', function() {
                loadFileDiff(file.path);
            });
            gitDiffFileList.appendChild(item);
        });
    }

    // ---- 加载单文件 diff ----
    function loadFileDiff(path) {
        if (gitDiffFileList) gitDiffFileList.style.display = 'none';
        if (gitDiffEmpty) gitDiffEmpty.style.display = 'none';
        if (gitDiffViewer) gitDiffViewer.style.display = '';
        if (gitDiffFilePath) gitDiffFilePath.textContent = path;

        fetch('/chat/git/diff?path=' + encodeURIComponent(path))
            .then(function(r) { return r.json(); })
            .then(function(res) {
                var d = (res && res.data) ? res.data : {};
                renderDiffText(d.diff || '');
            });
    }

    // ---- 渲染 diff 文本（纯 CSS 着色）----
    function renderDiffText(raw) {
        if (!gitDiffContent) return;
        var lines = (raw || '').split('\n');
        var html = '';
        for (var i = 0; i < lines.length; i++) {
            var line = escapeHtml(lines[i]);
            if (lines[i].startsWith('+++') || lines[i].startsWith('---')) {
                html += '<div class="git-line-head">' + line + '</div>';
            } else if (lines[i].startsWith('@@')) {
                html += '<div class="git-line-hunk">' + line + '</div>';
            } else if (lines[i].startsWith('+')) {
                html += '<div class="git-line-add">' + line + '</div>';
            } else if (lines[i].startsWith('-')) {
                html += '<div class="git-line-del">' + line + '</div>';
            } else {
                html += '<div class="git-line-ctx">' + line + '</div>';
            }
        }
        gitDiffContent.innerHTML = html;
        gitDiffContent.scrollTop = 0;
    }

    // ---- 返回文件列表 ----
    if (gitDiffBack) {
        gitDiffBack.addEventListener('click', function() {
            if (gitDiffViewer) gitDiffViewer.style.display = 'none';
            if (gitDiffFileList) gitDiffFileList.style.display = '';
            if (gitDiffEmpty) gitDiffEmpty.style.display = 'none';
        });
    }

    // ---- 刷新按钮 ----
    if (gitRefreshBtn) {
        gitRefreshBtn.addEventListener('click', function() {
            this.classList.add('spinning');
            loadGitStatus();
            var self = this;
            setTimeout(function() { self.classList.remove('spinning'); }, 600);
        });
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

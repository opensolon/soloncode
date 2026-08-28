/* ===== app-git.js ===== */
/* Filer Panel Git Diff 面板：三态检测、文件列表（带勾选）、Diff Viewer 内联查看、精确提交 */

(function() {
    // ---- DOM 元素 ----
    var tabs = document.querySelectorAll('.workspace-tab');
    var tabContents = document.querySelectorAll('.workspace-tab-content');
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
    var gitRefreshBtn = document.getElementById('gitRefreshBtn');

    // Diff Viewer / File Viewer 元素（内联在 main-area 内）
    var gitViewer = document.getElementById('gitViewer');
    var gitViewerLabel = document.getElementById('gitViewerLabel');
    var gitViewerFile = document.getElementById('gitViewerFile');
    var gitViewerContent = document.getElementById('gitViewerContent');
    var gitViewerClose = document.getElementById('gitViewerClose');
    // main-area 子视图引用
    var newChatView = document.getElementById('newChatView');

    // ---- 多工作区状态 ----
    var gitWorkspace = 'workspace';
    var gitWritableWorkspaces = [];

    function gitUrl(basePath, params) {
        var query = params || '';
        if (gitWorkspace !== 'workspace') {
            query += (query ? '&' : '') + 'mount=' + encodeURIComponent(gitWorkspace);
        }
        return basePath + (query ? '?' + query : '');
    }

    function loadGitWorkspaces() {
        fetch('/web/chat/filer/workspaces')
            .then(function(r) { return r.json(); })
            .then(function(res) {
                var all = (res && res.data) ? res.data : [];
                // 只保留可写的
                gitWritableWorkspaces = all.filter(function(ws) { return ws.writeable; });
                renderGitWorkspaceBar(gitWritableWorkspaces);
                var exists = gitWritableWorkspaces.some(function(ws) { return ws.id === gitWorkspace; });
                if (!exists) {
                    gitWorkspace = 'workspace';
                }
                loadGitStatus();
            });
    }

    function renderGitWorkspaceBar(list) {
        var $selector = document.getElementById('gitWorkspaceSelector');
        var $name = document.getElementById('gitWorkspaceName');
        var $items = document.getElementById('gitWorkspaceDropdownItems');
        if (!$selector || !$name || !$items) return;
        var currentLabel = '';
        var html = '';
        list.forEach(function(ws) {
        var label = ws.name === '__current_workspace__' ? I18n.t('gitdiff.currentWorkspace') : (ws.name || ws.id);
            if (ws.id === gitWorkspace) currentLabel = label;
            html += '<div class="git-workspace-dropdown-item" data-workspace-id="' + ws.id + '">' + escapeHtml(label) + '</div>';
        });
        $items.innerHTML = html;
        $name.textContent = currentLabel || I18n.t('gitdiff.workspace');
        $name.removeAttribute('data-i18n');
    }
    var chatView = document.getElementById('chatView');

    // ---- 状态 ----
    var gitStatus = null;
    var isInitializing = false;
    var viewerMode = null; // 'diff' | 'file' | null

    // ---- 刷新按钮 ----
    if (gitRefreshBtn) {
        gitRefreshBtn.addEventListener('click', function() {
            loadGitStatus();
        });
    }

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
            if (targetTab === 'gitDiff') loadGitStatus();
            // 切到任务 tab 时刷新
            if (targetTab === 'tasks' && window.loadTodos) window.loadTodos();
            // 持久化
            localStorage.setItem('workspace-active-tab', targetTab);
        });
    });

    // 恢复 Tab 状态
    var savedTab = localStorage.getItem('workspace-active-tab') || localStorage.getItem('filer-active-tab');
    if (savedTab === 'gitdiff') savedTab = 'gitDiff';
    localStorage.removeItem('filer-active-tab'); // 迁移后清理旧 key，避免遗留 filer 前缀
    if (savedTab && savedTab !== 'files') {
        var savedContentId = 'tabContent' + savedTab.charAt(0).toUpperCase() + savedTab.slice(1);
        var savedTabEl = document.querySelector('.workspace-tab[data-tab="' + savedTab + '"]');
        var savedContentEl = document.getElementById(savedContentId);
        if (savedTabEl && savedContentEl) {
            tabs.forEach(function(t) { t.classList.remove('active'); });
            tabContents.forEach(function(tc) { tc.classList.remove('active'); });
            savedTabEl.classList.add('active');
            savedContentEl.classList.add('active');
            if (savedTab === 'gitDiff') loadGitStatus();
            if (savedTab === 'tasks' && window.loadTodos) window.loadTodos();
        }
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
        fetch(gitUrl('/web/chat/git/status'))
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

    // ---- 双击加入对话（复用文件树的插入规则）----
    function insertGitPathToInput(rawPath) {
        if (!rawPath) return;
        var targetInput = (typeof inChatMode !== 'undefined' && inChatMode) ? chatInput : newChatInput;
        if (!targetInput) return;
        var relPath = rawPath.replace(/\/$/, '');
        var insertText = (gitWorkspace !== 'workspace')
            ? '[' + gitWorkspace + '/' + relPath + ']'
            : '[./' + relPath + ']';
        var currentVal = targetInput.value || '';
        var cursorPos = targetInput.selectionStart || currentVal.length;
        var before = currentVal.substring(0, cursorPos);
        var after = currentVal.substring(cursorPos);
        var prefix = (before.length > 0 && !before.endsWith(' ') && !before.endsWith('\n')) ? ' ' : '';
        targetInput.value = before + prefix + insertText + ' ' + after;
        targetInput.focus();
        var newPos = cursorPos + prefix.length + insertText.length + 1;
        targetInput.setSelectionRange(newPos, newPos);
        if (typeof autoResize === 'function') autoResize(targetInput);
    }

    /** 绑定单击/双击：延迟单击以规避双击时的两次 click 冲突 */
    function bindGitClickDblClick(el, onClick, onDblClick) {
        var timer = null;
        el.addEventListener('click', function(e) {
            if (timer) { clearTimeout(timer); timer = null; }
            timer = setTimeout(function() {
                timer = null;
                onClick(e);
            }, 250);
        });
        el.addEventListener('dblclick', function(e) {
            e.stopPropagation();
            e.preventDefault();
            if (timer) { clearTimeout(timer); timer = null; }
            onDblClick(e);
        });
    }

    // ---- 渲染文件列表（带 checkbox）----
    function renderFileList(data) {
        if (!gitDiffFileList) return;

        // 在清空列表前，记录当前已勾选的文件路径
        var prevChecked = {};
        var hasPrevState = false;
        gitDiffFileList.querySelectorAll('.git-file-checkbox').forEach(function(cb) {
            hasPrevState = true;
            if (cb.checked) prevChecked[cb.getAttribute('data-path')] = true;
        });

        // 记录当前已展开的目录路径，以便刷新后恢复
        var prevExpandedDirs = {};
        gitDiffFileList.querySelectorAll('.git-file-item-dir.expanded').forEach(function(item) {
            var p = item.getAttribute('data-path');
            if (p) prevExpandedDirs[p] = true;
        });

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

        files.forEach(function(file) {
            // 判断是否为目录：untracked 目录路径以 / 结尾
            var isDir = file.path.endsWith('/');

            var item = document.createElement('div');
            item.className = 'git-file-item' + (isDir ? ' git-file-item-dir' : '');

            // checkbox：如果之前有选中状态则恢复，否则默认全选
            var cb = document.createElement('input');
            cb.type = 'checkbox';
            cb.className = 'git-file-checkbox';
            cb.checked = hasPrevState ? !!prevChecked[file.path] : true;
            cb.setAttribute('data-path', file.path);
            cb.addEventListener('click', function(e) {
                e.stopPropagation(); // 防止触发外层 click 打开 diff
                syncSelectAll();
            });

            // 状态字母
            var GIT_STATUS_CLASS = { S: 'staged', M: 'modified', A: 'added', D: 'deleted', '?': 'untracked', R: 'renamed', U: 'conflict', C: 'copied' };
            var statusSpan = document.createElement('span');
            statusSpan.className = 'git-status-letter ' + (GIT_STATUS_CLASS[file.status] || 'other');
            statusSpan.textContent = file.status;

            // 文件/文件夹图标 + 路径
            var pathSpan = document.createElement('span');
            pathSpan.className = 'git-file-path';
            var displayPath = isDir ? file.path.slice(0, -1) : file.path;
            pathSpan.title = file.path;
            if (isDir) {
                pathSpan.innerHTML = escapeHtml(displayPath);
            } else {
                pathSpan.textContent = displayPath;
            }

            item.appendChild(cb);
            item.appendChild(statusSpan);
            item.appendChild(pathSpan);

            item.setAttribute('data-status', file.status);
            item.setAttribute('data-path', file.path);

            // 点击文件行：目录展开/折叠，文件打开 diff viewer；双击加入对话
            bindGitClickDblClick(item,
                function(e) {
                    // 避免点 checkbox 时也触发
                    if (e.target === cb) return;
                    if (isDir) {
                        toggleDirExpand(item, file.path);
                    } else {
                        openDiffViewer(file.path, file.status);
                    }
                },
                function(e) {
                    if (e.target === cb) return;
                    insertGitPathToInput(file.path);
                }
            );

            gitDiffFileList.appendChild(item);
        });

        // 恢复之前已展开的目录状态
        Object.keys(prevExpandedDirs).forEach(function(dirPath) {
            gitDiffFileList.querySelectorAll('.git-file-item-dir').forEach(function(item) {
                if (item.getAttribute('data-path') === dirPath && !item.classList.contains('expanded')) {
                    toggleDirExpand(item, dirPath);
                }
            });
        });
    }

    // ---- 目录展开/折叠：列出目录下文件列表 ----
    function toggleDirExpand(item, dirPath, expandDepth) {
        var subList = item.nextElementSibling;
        if (subList && subList.classList.contains('git-dir-sublist')) {
            // 已展开，折叠
            subList.remove();
            item.classList.remove('expanded');
            return;
        }

        // 展开：调用 filer/tree API 获取目录内容
        item.classList.add('expanded');

        var subListEl = document.createElement('div');
        subListEl.className = 'git-dir-sublist';
        subListEl.innerHTML = '<div class="git-dir-loading" style="padding:6px 12px 6px 40px;color:var(--text-secondary);font-size:11px;">' + I18n.t('common.loading') + '...</div>';

        // 在 item 后面插入子列表
        item.parentNode.insertBefore(subListEl, item.nextSibling);

        var treeUrl = '/web/chat/filer/tree?path=' + encodeURIComponent(dirPath.replace(/\/$/, '')) + '&depth=1';
        if (gitWorkspace !== 'workspace') {
            treeUrl += '&mount=' + encodeURIComponent(gitWorkspace);
        }

        fetch(treeUrl)
            .then(function(r) { return r.json(); })
            .then(function(res) {
                var children = (res && res.data) ? res.data : [];
                if (children.length === 0) {
                    subListEl.innerHTML = '<div class="git-dir-empty" style="padding:6px 12px 6px 40px;color:var(--text-secondary);font-size:11px;">' + I18n.t('gitdiff.emptyDirectory') + '</div>';
                    return;
                }

                subListEl.innerHTML = '';
                children.forEach(function(child) {
                    var childItem = document.createElement('div');
                    childItem.className = 'git-file-item git-file-item-child' + (child.type === 'directory' ? ' git-file-item-dir' : '');
                    childItem.setAttribute('data-path', child.path);

                    // 子项图标
                    var childIconSpan = document.createElement('span');
                    childIconSpan.className = 'git-file-child-icon';
                    if (child.type === 'directory') {
                        childIconSpan.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>';
                    } else {
                        childIconSpan.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>';
                    }

                    // 子项路径
                    var childPathSpan = document.createElement('span');
                    childPathSpan.className = 'git-file-path';
                    childPathSpan.title = child.path;
                    childPathSpan.textContent = child.name;

                    childItem.appendChild(childIconSpan);
                    childItem.appendChild(childPathSpan);

                    // 子项点击：文件打开查看，目录递归展开；双击加入对话
                    (function(childNode, childEl) {
                        bindGitClickDblClick(childEl,
                            function() {
                                if (childNode.type === 'directory') {
                                    toggleDirExpand(childEl, childNode.path + '/');
                                } else {
                                    // 未跟踪目录下的文件：用 openFileViewer 打开（status='?' 未跟踪）
                                    openFileViewer(childNode.path, childNode.name, '?');
                                }
                            },
                            function() {
                                insertGitPathToInput(childNode.path);
                            }
                        );
                    })(child, childItem);

                    subListEl.appendChild(childItem);
                });

                // 借鉴文件树风格：若该层只有一个子节点且为目录，自动穿透展开
                autoExpandSingleGitDir(subListEl, children, (expandDepth || 0) + 1);
            })
            .catch(function(e) {
                subListEl.innerHTML = '<div class="git-dir-error" style="padding:6px 12px 6px 40px;color:var(--color-danger);font-size:11px;">' + I18n.t('gitdiff.loadFailed') + ': ' + escapeHtml(e.message) + '</div>';
            });
    }

    // ---- 自动穿透展开：目录下只有一个子节点且为目录时，继续展开 ----
    var GIT_AUTO_EXPAND_MAX_DEPTH = 20;

    function autoExpandSingleGitDir(subListEl, children, depth) {
        if (!subListEl) return;
        if ((depth || 0) >= GIT_AUTO_EXPAND_MAX_DEPTH) return;
        if (!children || children.length !== 1) return;

        var only = children[0];
        if (!only || only.type !== 'directory') return;

        var onlyEl = null;
        subListEl.querySelectorAll('.git-file-item-dir').forEach(function(el) {
            if (!onlyEl && el.getAttribute('data-path') === only.path) onlyEl = el;
        });
        if (!onlyEl) return;
        // 已展开则跳过
        var next = onlyEl.nextElementSibling;
        if (next && next.classList.contains('git-dir-sublist')) return;

        toggleDirExpand(onlyEl, only.path + '/', depth);
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

    // ---- 根据文件扩展名推测语言（用于 hljs）----
    function guessLang(path) {
        var ext = (path || '').replace(/.*\./, '').toLowerCase();
        var map = {
            js: 'javascript', jsx: 'javascript', ts: 'typescript', tsx: 'typescript',
            java: 'java', kt: 'kotlin', kts: 'kotlin',
            py: 'python', rb: 'ruby', go: 'go', rs: 'rust',
            c: 'c', h: 'c', cpp: 'cpp', cc: 'cpp', cxx: 'cpp', hpp: 'cpp',
            cs: 'csharp', fs: 'fsharp',
            scala: 'scala', clj: 'clojure', ex: 'elixir', exs: 'elixir',
            html: 'xml', htm: 'xml', xml: 'xml', svg: 'xml',
            css: 'css', scss: 'scss', less: 'less', sass: 'scss',
            json: 'json', yaml: 'yaml', yml: 'yaml', toml: 'ini',
            md: 'markdown', markdown: 'markdown',
            sql: 'sql', sh: 'bash', bash: 'bash', zsh: 'bash',
            dockerfile: 'dockerfile', makefile: 'makefile',
            gradle: 'groovy', groovy: 'groovy',
            lua: 'lua', r: 'r', pl: 'perl', pm: 'perl',
            swift: 'swift', dart: 'dart',
            vue: 'xml', svelte: 'xml',
            properties: 'properties', conf: 'nginx', nginx: 'nginx',
            ini: 'ini', cfg: 'ini',
            txt: 'plaintext'
        };
        // 特殊文件名
        var name = (path || '').replace(/.*\//, '').toLowerCase();
        if (name === 'makefile' || name === 'gnumakefile') return 'makefile';
        if (name === 'dockerfile') return 'dockerfile';
        if (name === '.gitignore' || name === '.gitattributes') return 'bash';
        if (name === 'jenkinsfile') return 'groovy';
        if (name === 'vagrantfile') return 'ruby';
        return map[ext] || '';
    }

    // ---- 格式化文件大小 ----
    function formatSize(bytes) {
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    }

    // ---- File Viewer：打开文件内容（在 main-area 内）----
    var diffViewerActive = false;

    function openFileViewer(path, name, status) {
        if (!gitViewer) return;

        viewerMode = 'file';

        // 隐藏欢迎页和聊天视图
        if (newChatView) newChatView.style.display = 'none';
        if (chatView) chatView.style.display = 'none';

        // 显示 viewer：占据整个中间主区（隐藏顶部条），清理浮层动画类
        document.body.classList.add('memory-active');
        gitViewer.classList.remove('mem-overlay');
        gitViewer.style.display = 'flex';
        diffViewerActive = true;

        // 从路径中解析工作区前缀：@xxx/xxx
        var fileWorkspace = 'workspace';
        var apiPath = path;
        var displayPath = path;
        if (path && path.charAt(0) === '@') {
            var slashIdx = path.indexOf('/');
            if (slashIdx > 1) {
                fileWorkspace = path.substring(0, slashIdx);
                apiPath = path.substring(slashIdx + 1);
                // displayPath 保持原样（含 @xxx/ 前缀）
            }
        } else {
            // 无 @ 前缀时，回退到全局工作区状态
            fileWorkspace = window.activeFilerWorkspace || 'workspace';
            // 如果全局状态是挂载工作区，displayPath 也要补上 @xxx/ 前缀
            if (fileWorkspace !== 'workspace') {
                displayPath = fileWorkspace + '/' + displayPath;
            }
        }

        // 更新 header（显示带工作区前缀的路径）
        if (gitViewerLabel) gitViewerLabel.textContent = I18n.t('gitdiff.fileContent');
        if (gitViewerFile) gitViewerFile.textContent = displayPath;

        // 清理操作栏
        var oldActions = gitViewer.querySelector('.git-viewer-actions');
        if (oldActions) oldActions.remove();

        if (gitViewerContent) gitViewerContent.innerHTML = '<div style="padding:20px;color:var(--text-secondary)">' + I18n.t('common.loading') + '...</div>';

        var readUrl = '/web/chat/filer/read?path=' + encodeURIComponent(apiPath);
        if (fileWorkspace !== 'workspace') {
            readUrl += '&mount=' + encodeURIComponent(fileWorkspace);
        }
        fetch(readUrl)
            .then(function(r) { return r.json(); })
            .then(function(res) {
                var d = (res && res.data) ? res.data : {};
                if (res && res.code !== 200) {
                    gitViewerContent.innerHTML = '<div style="padding:20px;color:var(--color-danger)">'
                        + escapeHtml((res && res.data && res.data.message) || res.description || I18n.t('gitdiff.cannotReadFile'))
                        + '</div>';
                    return;
                }
                // 构造原始二进制读取 URL（用于图片/视频）
                var rawUrl = '/web/chat/filer/read-raw?path=' + encodeURIComponent(apiPath);
                if (fileWorkspace !== 'workspace') {
                    rawUrl += '&mount=' + encodeURIComponent(fileWorkspace);
                }
                // 多工作区隔离：<img>/<video> 的 src 由浏览器直发，绕过了 fetch/XHR 劫持层，
                // 必须显式在 URL 携带 workspaceId（统一入口 window.wsAndSuffix）
                rawUrl += window.wsAndSuffix();
                renderFileContent(d.content, d.name || name, d.size, path, rawUrl);
            })
            .catch(function(e) {
                if (gitViewerContent) gitViewerContent.innerHTML = '<div style="padding:20px;color:var(--color-danger)">' + I18n.t('gitdiff.loadFailed') + ': ' + escapeHtml(e.message) + '</div>';
            })
            .finally(function() {
                // 渲染操作按钮（添加到Git / 回滚），status 默认按未跟踪处理
                renderViewerActions(path, status || '?');
            });
    }

    // ---- 文件类型检测（根据扩展名）----
    var MEDIA_IMAGE_EXTS = ['.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg', '.ico', '.bmp'];
    var MEDIA_VIDEO_EXTS = ['.mp4', '.webm', '.ogg', '.mov', '.avi', '.mkv'];
    var BINARY_EXTS = ['.pdf', '.zip', '.rar', '.7z', '.tar', '.gz', '.exe', '.dll', '.bin', '.o', '.so', '.dylib'];

    /**
     * 检测文件是否为图片类型。
     */
    function isImageFile(filePath) {
        var lower = (filePath || '').toLowerCase();
        return MEDIA_IMAGE_EXTS.some(function(ext) { return lower.endsWith(ext); });
    }

    /**
     * 检测文件是否为视频类型。
     */
    function isVideoFile(filePath) {
        var lower = (filePath || '').toLowerCase();
        return MEDIA_VIDEO_EXTS.some(function(ext) { return lower.endsWith(ext); });
    }

    /**
     * 检测文件是否为已知的二进制类型（无法以文本预览）。
     */
    function isBinaryFile(filePath) {
        var lower = (filePath || '').toLowerCase();
        return BINARY_EXTS.some(function(ext) { return lower.endsWith(ext); });
    }

    /**
     * 渲染媒体文件（图片/视频）。
     */
    function renderMediaContent(rawUrl, fileName, isImage) {
        if (!gitViewerContent) return;

        // 隐藏不适用的 Header 按钮（防止从文本/MD 文件切换过来时残留）
        var _mdToggle = document.getElementById('gitViewerMdToggle');
        if (_mdToggle) _mdToggle.style.display = 'none';
        var _copyBtn = document.getElementById('gitViewerCopyBtn');
        if (_copyBtn) _copyBtn.style.display = 'none';
        var _fullscreen = document.getElementById('gitViewerFullscreen');
        if (_fullscreen) _fullscreen.style.display = 'none';
        var _memNew = document.getElementById('gitViewerMemNew');
        if (_memNew) _memNew.style.display = 'none';
        var _addToChatMedia = document.getElementById('gitViewerAddToChat');
        if (_addToChatMedia) _addToChatMedia.style.display = 'none';

        var displayName = escapeHtml(fileName || '');
        var html = '<div class="file-view-media">';
        html += '<div class="file-view-media-preview">';
        if (isImage) {
            html += '<img src="' + escapeHtml(rawUrl) + '" alt="' + displayName + '" class="file-view-media-img" />';
        } else {
            html += '<video src="' + escapeHtml(rawUrl) + '" controls class="file-view-media-video" autoplay>';
            html += I18n.t('gitdiff.videoNotSupported') + '</video>';
        }
        html += '</div></div>';
        gitViewerContent.innerHTML = html;
        gitViewerContent.scrollTop = 0;
    }

    /**
     * 渲染二进制文件不可预览提示。
     */
    function renderBinaryUnreadable(fileName) {
        if (!gitViewerContent) return;

        // 隐藏不适用的 Header 按钮（防止从文本/MD 文件切换过来时残留）
        var _mdToggle = document.getElementById('gitViewerMdToggle');
        if (_mdToggle) _mdToggle.style.display = 'none';
        var _copyBtn = document.getElementById('gitViewerCopyBtn');
        if (_copyBtn) _copyBtn.style.display = 'none';
        var _fullscreen = document.getElementById('gitViewerFullscreen');
        if (_fullscreen) _fullscreen.style.display = 'none';
        var _memNew = document.getElementById('gitViewerMemNew');
        if (_memNew) _memNew.style.display = 'none';
        var _addToChatBin = document.getElementById('gitViewerAddToChat');
        if (_addToChatBin) _addToChatBin.style.display = 'none';

        gitViewerContent.innerHTML = '<div style="padding:40px 20px;text-align:center;color:var(--text-secondary)">'
            + '<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" style="opacity:0.4;margin-bottom:16px">'
            + '<path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"/>'
            + '<polyline points="14 2 14 8 20 8"/>'
            + '<line x1="9" y1="13" x2="15" y2="13"/>'
            + '</svg>'
            + '<div style="font-size:14px;font-weight:500;margin-bottom:6px">' + I18n.t('gitdiff.binaryFile') + '</div>'
            + '<div style="font-size:12px;opacity:0.6">' + escapeHtml(fileName || '') + ' ' + I18n.t('gitdiff.binaryFileHint') + '</div>'
            + '</div>';
        gitViewerContent.scrollTop = 0;
    }

    // ---- File Viewer：渲染文件内容（语法高亮 + 行号 / 媒体预览 / 二进制提示）----
    function renderFileContent(content, fileName, fileSize, filePath, rawUrl) {
        if (!gitViewerContent) return;

        // 重置容器状态（清理之前文件留下的 overflow 等样式）
        gitViewerContent.style.overflow = '';

        // ---- 媒体文件类型检测（优先于文本渲染）----
        if (rawUrl && isImageFile(filePath || fileName)) {
            renderMediaContent(rawUrl, fileName || filePath, true);
            return;
        }
        if (rawUrl && isVideoFile(filePath || fileName)) {
            renderMediaContent(rawUrl, fileName || filePath, false);
            return;
        }
        // 已知的二进制文件类型（非图片/视频）—— 显示不可预览提示
        if (isBinaryFile(filePath || fileName)) {
            renderBinaryUnreadable(fileName || filePath);
            return;
        }

        // 重置 MD 切换按钮为初始状态（源码态），应对切换文件时图标未复位的问题
        var _mdToggleReset = document.getElementById('gitViewerMdToggle');
        if (_mdToggleReset) {
            _mdToggleReset.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
            _mdToggleReset.title = I18n.t('gitViewer.previewMd');
        }
        // 显示复制按钮（可能在审查详情中被隐藏）
        var _copyBtnReset = document.getElementById('gitViewerCopyBtn');
        if (_copyBtnReset) _copyBtnReset.style.display = '';

        // 显示全屏按钮（可能在审查详情中被隐藏）
        var _fullscreenReset = document.getElementById('gitViewerFullscreen');
        if (_fullscreenReset) _fullscreenReset.style.display = '';

        // 隐藏记忆面板专用的「新建」按钮
        var _memNewReset = document.getElementById('gitViewerMemNew');
        if (_memNewReset) _memNewReset.style.display = 'none';

        // 显示「加入对话内容」按钮（文件视图可用）
        var _addToChat = document.getElementById('gitViewerAddToChat');
        if (_addToChat) _addToChat.style.display = '';

        var lang = guessLang(filePath || fileName);
        var lines = (content || '').split('\n');
        var totalLines = lines.length;

        // 构建带行号的代码行
        var codeHtml = '';
        for (var i = 0; i < totalLines; i++) {
            var escapedLine = escapeHtml(lines[i]);
            // 空行保留高度
            if (escapedLine === '') escapedLine = ' ';
            codeHtml += '<div class="file-view-line">'
                + '<span class="file-view-num">' + (i + 1) + '</span>'
                + '<span class="file-view-text">' + escapedLine + '</span>'
                + '</div>';
        }

        // 检测是否为 .md 文件
        var isMdFile = /\.md$/i.test(filePath || fileName || "");

        gitViewerContent.innerHTML = ''
            + '<div class="file-view-code' + (lang ? ' hljs-language-' + lang : '') + '">' + codeHtml + '</div>'
            + (isMdFile ? '<div class="file-view-md-frame-wrap" style="display:none;"><iframe class="file-view-md-frame" sandbox="allow-scripts"></iframe></div>' : '');

        // 如果能识别语言，按需加载 hljs 后对代码区进行语法高亮
        if (lang) {
            var _contentRef = content;
            var _langRef = lang;
            var _viewerContentRef = gitViewerContent;
            var _applyHighlight = function() {
                var codeBlock = _viewerContentRef.querySelector('.file-view-code');
                if (!codeBlock || typeof hljs === 'undefined') return;
                try {
                    var rawText = _contentRef || '';
                    var highlighted = hljs.highlight(rawText, { language: _langRef, ignoreIllegals: true });
                    var hlLines = highlighted.value.split('\n');
                    var hlHtml = '';
                    for (var j = 0; j < hlLines.length; j++) {
                        var hlLine = hlLines[j] || ' ';
                        hlHtml += '<div class="file-view-line">'
                            + '<span class="file-view-num">' + (j + 1) + '</span>'
                            + '<span class="file-view-text">' + hlLine + '</span>'
                            + '</div>';
                    }
                    codeBlock.innerHTML = hlHtml;
                } catch (e) {
                    // highlight 失败时保留纯文本
                }
            };
            if (typeof hljs !== 'undefined') {
                _applyHighlight();
            } else if (typeof loadScriptOnce === 'function') {
                loadScriptOnce('/highlight/highlight.min.js', function(err) {
                    if (!err) _applyHighlight();
                });
            }
        }

        // ---- 操作 header 中的按钮 ----
        var mdToggleBtn = document.getElementById('gitViewerMdToggle');
        var copyBtn = document.getElementById('gitViewerCopyBtn');

        // 显示/隐藏 MD 切换按钮
        if (mdToggleBtn) {
            mdToggleBtn.style.display = isMdFile ? '' : 'none';
        }

        // 复制按钮事件
        if (copyBtn) {
            (function(rawContent, btn) {
                // 移除旧事件监听（通过克隆替换方式）
                var newBtn = btn.cloneNode(true);
                btn.parentNode.replaceChild(newBtn, btn);

                newBtn.addEventListener('click', function() {
                    if (navigator.clipboard && navigator.clipboard.writeText) {
                        navigator.clipboard.writeText(rawContent).then(function() {
                            showCopyFeedback(newBtn);
                        }).catch(function() {
                            fallbackCopy(rawContent, newBtn);
                        });
                    } else {
                        fallbackCopy(rawContent, newBtn);
                    }
                });

                // 更新全局引用
                copyBtn = newBtn;
            })(content, copyBtn);
        }

        // MD 切换按钮事件
        if (isMdFile && mdToggleBtn) {
            var mdFrameWrap = gitViewerContent.querySelector('.file-view-md-frame-wrap');
            var codeBlock = gitViewerContent.querySelector('.file-view-code');
            var mdRenderedFlag = false;

            if (mdFrameWrap && codeBlock) {
                // 移除旧事件监听
                var newToggle = mdToggleBtn.cloneNode(true);
                mdToggleBtn.parentNode.replaceChild(newToggle, mdToggleBtn);
                mdToggleBtn = newToggle;

                (function(content, toggle, wrap, code) {
                    toggle.addEventListener('click', function() {
                        if (wrap.style.display === 'none') {
                            // 切换到预览模式
                            code.style.display = 'none';
                            wrap.style.display = 'block';
                            // 隐藏外层滚动条，与全屏模式行为一致
                            gitViewerContent.style.overflow = 'hidden';

                            // 调整 iframe 高度
                            var iframe = wrap.querySelector('iframe');
                            if (iframe) {
                                var headerHeight = 8;
                                var availHeight = gitViewerContent.clientHeight - headerHeight - 8;
                                if (availHeight < 300) availHeight = 300;
                                iframe.style.height = availHeight + 'px';
                            }

                            if (!mdRenderedFlag && iframe) {
                                var mdHtml = renderMdForPreview(content);
                                // 应用语法高亮到代码块
                                if (typeof hljs !== 'undefined') {
                                    mdHtml = applyMdHighlight(mdHtml);
                                }
                                var frameDoc = buildMdPreviewDocument(mdHtml);
                                iframe.srcdoc = frameDoc;
                                mdRenderedFlag = true;
                            }

                            // 切换 SVG 图标为"代码"图标
                            toggle.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>';
                            toggle.title = I18n.t('gitViewer.changeDetails');
                        } else {
                            // 切换回"源码"模式
                            code.style.display = 'block';
                            wrap.style.display = 'none';
                            // 恢复外层滚动条（源码模式需要）
                            gitViewerContent.style.overflow = '';

                            // 切换 SVG 图标为"眼睛"图标
                            toggle.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
                            toggle.title = I18n.t('gitViewer.previewMd');
                        }
                    });
                })(content, mdToggleBtn, mdFrameWrap, codeBlock);
            }
        }

        gitViewerContent.scrollTop = 0;
    }

    // ---- 将选中行/选中内容加入对话输入框 ----
    function addSelectedLinesToChat() {
        var filePath = gitViewerFile ? gitViewerFile.textContent : '';
        if (!filePath) {
            if (typeof showToast === 'function') showToast('No file open', 'error');
            return;
        }

        var selection = window.getSelection();
        if (!selection || selection.isCollapsed || !selection.rangeCount) {
            // 没有选中内容，直接插入文件路径引用
            insertGitPathToInput(filePath);
            return;
        }

        var range = selection.getRangeAt(0);
        var startContainer = range.startContainer;
        var endContainer = range.endContainer;

        // 查找选中的起始和结束 .file-view-line 元素
        var startLine = findLineElement(startContainer);
        var endLine = findLineElement(endContainer);

        if (!startLine || !endLine) {
            // 无法确定行号，直接插入文件路径
            if (viewerMode === 'diff') {
                return;
            }
            insertGitPathToInput(filePath);
            return;
        }

        var startNum = getLineNumber(startLine);
        var endNum = getLineNumber(endLine);

        if (startNum === null || endNum === null) {
            // diff 视图中行号可能为 null（如选中 @@ 头或 ---/+++ 元信息行），此时不加入对话避免重复
            if (viewerMode === 'diff') {
                return;
            }
            insertGitPathToInput(filePath);
            return;
        }

        // 确保 start <= end
        if (startNum > endNum) {
            var tmp = startNum;
            startNum = endNum;
            endNum = tmp;
        }

        var pathRef = (gitWorkspace !== 'workspace')
            ? gitWorkspace + '/' + filePath
            : './' + filePath;
        var insertText;
        if (startNum === endNum) {
            insertText = '[' + pathRef + ']L' + startNum;
        } else {
            insertText = '[' + pathRef + ']L' + startNum + '-L' + endNum;
        }

        // 插入到输入框（复用 insertGitPathToInput 的模式）
        var targetInput = (typeof inChatMode !== 'undefined' && inChatMode) ? chatInput : newChatInput;
        if (!targetInput) return;

        var currentVal = targetInput.value || '';
        var cursorPos = targetInput.selectionStart || currentVal.length;
        var before = currentVal.substring(0, cursorPos);
        var after = currentVal.substring(cursorPos);
        var prefix = (before.length > 0 && !before.endsWith(' ') && !before.endsWith('\n')) ? ' ' : '';
        targetInput.value = before + prefix + insertText + ' ' + after;
        targetInput.focus();
        var newPos = cursorPos + prefix.length + insertText.length + 1;
        targetInput.setSelectionRange(newPos, newPos);
        if (typeof autoResize === 'function') autoResize(targetInput);

        // 清除选中状态
        selection.removeAllRanges();

        // 反馈提示
        if (typeof showToast === 'function') {
            showToast(I18n.t('gitViewer.addedToChat', { text: insertText }), 'success', 1500);
        }
    }

    /** 从 DOM 节点向上查找最近的行元素（file-view-line 或 git-diff-line） */
    function findLineElement(node) {
        while (node && node !== gitViewerContent) {
            if (node.classList && (node.classList.contains('file-view-line') || node.classList.contains('git-diff-line'))) {
                return node;
            }
            node = node.parentNode;
        }
        return null;
    }

    /** 从行元素中提取行号（支持 file-view-line 和 git-diff-line） */
    function getLineNumber(lineEl) {
        // diff 行：取第二个 .git-line-num（新行号），没有则取第一个
        if (lineEl.classList.contains('git-diff-line')) {
            var numEls = lineEl.querySelectorAll('.git-line-num');
            if (numEls.length >= 2) {
                var newNum = parseInt(numEls[1].textContent, 10);
                if (!isNaN(newNum)) return newNum;
                var oldNum = parseInt(numEls[0].textContent, 10);
                return isNaN(oldNum) ? null : oldNum;
            }
            if (numEls.length === 1) {
                var num = parseInt(numEls[0].textContent, 10);
                return isNaN(num) ? null : num;
            }
            return null;
        }
        // 普通文件行
        var numEl = lineEl.querySelector('.file-view-num');
        if (!numEl) return null;
        var num = parseInt(numEl.textContent, 10);
        return isNaN(num) ? null : num;
    }

    // 绑定「加入对话内容」按钮
    var gitViewerAddToChat = document.getElementById('gitViewerAddToChat');
    if (gitViewerAddToChat) {
        gitViewerAddToChat.addEventListener('click', addSelectedLinesToChat);
    }

    // 复制兜底方法
    function fallbackCopy(text, btn) {
        var ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.left = '-9999px';
        document.body.appendChild(ta);
        ta.select();
        try { document.execCommand('copy'); } catch(e) {}
        document.body.removeChild(ta);
        showCopyFeedback(btn);
    }

    // 复制成功反馈（SVG 图标切换为勾选再恢复）
    function showCopyFeedback(btn) {
        var origHtml = btn.innerHTML;
        btn.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>';
        setTimeout(function() {
            btn.innerHTML = origHtml;
        }, 1500);
    }

    // Markdown 辅助函数（用于 iframe 视图预览）
    function renderMdForPreview(text) {
        if (typeof marked === 'undefined') return escapeHtml(text || '');
        try {
            var savedOpts = {};
            // 尝试保存当前全局配置
            if (marked.defaults) {
                savedOpts.breaks = marked.defaults.breaks;
                savedOpts.gfm = marked.defaults.gfm;
                savedOpts.renderer = marked.defaults.renderer;
            }
            // 设置为预览模式
            marked.setOptions({ breaks: true, gfm: true, renderer: null });
            var html = marked.parse(text || '');
            // 多工作区隔离：iframe 内嵌图 src 不走 fetch/XHR 劫持层，重写为 read-raw + workspaceId
            html = html.replace(/<img\s+src="([^"]*)"/gi, function(m, src) {
                if (/^(https?:|data:|\/\/)/i.test(src)) return m;
                var u = '/web/chat/filer/read-raw?path=' + encodeURIComponent(src) + window.wsAndSuffix();
                return '<img src="' + u + '"';
            });
            // 恢复全局配置
            if (marked.defaults) {
                marked.setOptions(savedOpts);
            }
            return html;
        } catch(e) {
            return escapeHtml(text || '');
        }
    }

    function applyMdHighlight(mdHtml) {
        return mdHtml.replace(/<pre><code class="language-(\w+)">([\s\S]*?)<\/code><\/pre>/g, function(match, lang, code) {
            try {
                // 解码 HTML 实体
                var decoded = code.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'");
                var result = hljs.highlight(decoded, { language: lang, ignoreIllegals: true });
                return '<pre><code class="language-' + lang + ' hljs">' + result.value + '</code></pre>';
            } catch(e) {
                return match;
            }
        });
    }

    function buildMdPreviewDocument(mdHtml) {
        return '<!DOCTYPE html>\n<html lang="zh-CN">\n<head>\n'
            + '<meta charset="utf-8">\n'
            + '<meta name="viewport" content="width=device-width,initial-scale=1">\n'
            + '<style>\n'
            + '  * { margin: 0; padding: 0; box-sizing: border-box; }\n'
            + '  body {\n'
            + '    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;\n'
            + '    font-size: 15px;\n'
            + '    line-height: 1.7;\n'
            + '    color: #24292e;\n'
            + '    padding: 24px 32px;\n'
            + '    max-width: 960px;\n'
            + '    margin: 0 auto;\n'
            + '    background: #ffffff;\n'
            + '  }\n'
            + '  h1, h2, h3, h4 { margin-top: 24px; margin-bottom: 16px; font-weight: 600; line-height: 1.25; }\n'
            + '  h1 { font-size: 2em; border-bottom: 1px solid #eaecef; padding-bottom: 0.3em; }\n'
            + '  h2 { font-size: 1.5em; border-bottom: 1px solid #eaecef; padding-bottom: 0.3em; }\n'
            + '  h3 { font-size: 1.25em; }\n'
            + '  p, ul, ol, blockquote, table, pre { margin-bottom: 16px; }\n'
            + '  ul, ol { padding-left: 2em; }\n'
            + '  li { margin: 0.25em 0; }\n'
            + '  a { color: #0366d6; text-decoration: none; }\n'
            + '  a:hover { text-decoration: underline; }\n'
            + '  code {\n'
            + '    font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace;\n'
            + '    font-size: 13px;\n'
            + '    background: #f6f8fa;\n'
            + '    padding: 2px 6px;\n'
            + '    border-radius: 3px;\n'
            + '  }\n'
            + '  pre {\n'
            + '    background: #f6f8fa;\n'
            + '    padding: 16px;\n'
            + '    border-radius: 6px;\n'
            + '    overflow-x: auto;\n'
            + '  }\n'
            + '  pre code {\n'
            + '    background: none;\n'
            + '    padding: 0;\n'
            + '    font-size: 13px;\n'
            + '    line-height: 1.5;\n'
            + '  }\n'
            // highlight.js 高亮样式（GitHub Light 主题）
            + '  pre code.hljs{display:block;overflow-x:auto;padding:1em}code.hljs{padding:3px 5px}\n'
            + '  .hljs{color:#24292e;background:#fff}\n'
            + '  .hljs-doctag,.hljs-keyword,.hljs-meta .hljs-keyword,.hljs-template-tag,.hljs-template-variable,.hljs-type,.hljs-variable.language_{color:#d73a49}\n'
            + '  .hljs-title,.hljs-title.class_,.hljs-title.class_.inherited__,.hljs-title.function_{color:#6f42c1}\n'
            + '  .hljs-attr,.hljs-attribute,.hljs-literal,.hljs-meta,.hljs-number,.hljs-operator,.hljs-selector-attr,.hljs-selector-class,.hljs-selector-id,.hljs-variable{color:#005cc5}\n'
            + '  .hljs-meta .hljs-string,.hljs-regexp,.hljs-string{color:#032f62}\n'
            + '  .hljs-built_in,.hljs-symbol{color:#e36209}\n'
            + '  .hljs-code,.hljs-comment,.hljs-formula{color:#6a737d}\n'
            + '  .hljs-name,.hljs-quote,.hljs-selector-pseudo,.hljs-selector-tag{color:#22863a}\n'
            + '  .hljs-subst{color:#24292e}\n'
            + '  .hljs-section{color:#005cc5;font-weight:700}\n'
            + '  .hljs-bullet{color:#735c0f}\n'
            + '  .hljs-emphasis{color:#24292e;font-style:italic}\n'
            + '  .hljs-strong{color:#24292e;font-weight:700}\n'
            + '  .hljs-addition{color:#22863a;background-color:#f0fff4}\n'
            + '  .hljs-deletion{color:#b31d28;background-color:#ffeef0}\n'
            + '  '
            + '  blockquote {\n'
            + '    border-left: 4px solid #dfe2e5;\n'
            + '    padding: 0 16px;\n'
            + '    color: #6a737d;\n'
            + '  }\n'
            + '  img { max-width: 100%; }\n'
            + '  table { border-collapse: collapse; width: 100%; }\n'
            + '  th, td { border: 1px solid #dfe2e5; padding: 6px 13px; }\n'
            + '  th { background: #f6f8fa; font-weight: 600; }\n'
            + '  hr { border: none; border-top: 1px solid #eaecef; margin: 24px 0; }\n'
            + '  ::-webkit-scrollbar { width: 6px; height: 6px; }\n'
            + '  ::-webkit-scrollbar-thumb { background: #c1c1c1; border-radius: 3px; }\n'
            + '</style>\n</head>\n<body>\n'
            + mdHtml + '\n'
            + '</body>\n</html>';
    }

    // ---- Diff Viewer：打开内联 diff（在 main-area 内）----

    function openDiffViewer(path, status) {
        if (!gitViewer) return;

        viewerMode = 'diff';

        // 隐藏欢迎页和聊天视图
        if (newChatView) newChatView.style.display = 'none';
        if (chatView) chatView.style.display = 'none';

        // 显示 diff viewer：占据整个中间主区（隐藏顶部条），清理浮层动画类
        document.body.classList.add('memory-active');
        gitViewer.classList.remove('mem-overlay');
        gitViewer.style.display = 'flex';
        diffViewerActive = true;

        // 审查详情模式：隐藏"视图"和"复制"按钮（全屏按钮保留）
        var _mdToggle = document.getElementById('gitViewerMdToggle');
        var _copyBtn = document.getElementById('gitViewerCopyBtn');
        var _fullscreenBtn = document.getElementById('gitViewerFullscreen');
        var _memNewBtn = document.getElementById('gitViewerMemNew');
        if (_mdToggle) _mdToggle.style.display = 'none';
        if (_copyBtn) _copyBtn.style.display = 'none';
        if (_fullscreenBtn) _fullscreenBtn.style.display = '';
        if (_memNewBtn) _memNewBtn.style.display = 'none';
        // Diff 视图显示「加入对话内容」按钮（支持行号选择）
        var _addToChatDiff = document.getElementById('gitViewerAddToChat');
        if (_addToChatDiff) _addToChatDiff.style.display = '';

        if (gitViewerLabel) gitViewerLabel.textContent = I18n.t('gitViewer.changeDetails');
        // 如果是挂载工作区，显示路径时带上 @xxx/ 前缀
        var displayDiffPath = path;
        if (gitWorkspace !== 'workspace') {
            displayDiffPath = gitWorkspace + '/' + displayDiffPath;
        }
        if (gitViewerFile) gitViewerFile.textContent = displayDiffPath;

        // 判断是否是目录（以 / 结尾）
        var isDir = path.endsWith('/');

        if (isDir) {
            // 目录：列出目录下文件列表
            if (gitViewerContent) {
                gitViewerContent.innerHTML = '<div style="padding:20px;color:var(--text-secondary)">'
                    + '<div style="margin-bottom:8px"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path></svg> ' + escapeHtml(displayDiffPath) + '</div>'
                    + '<div id="gitDirFileList" style="margin-top:12px">' + I18n.t('common.loading') + '...</div>'
                    + '</div>';
            }
            renderViewerActions(path, status);

            // 加载目录内容
            var dirTreeUrl = '/web/chat/filer/tree?path=' + encodeURIComponent(path.replace(/\/$/, '')) + '&depth=1';
            if (gitWorkspace !== 'workspace') {
                dirTreeUrl += '&mount=' + encodeURIComponent(gitWorkspace);
            }
            fetch(dirTreeUrl)
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    var dirListEl = document.getElementById('gitDirFileList');
                    if (!dirListEl) return;
                    var children = (res && res.data) ? res.data : [];
                    if (children.length === 0) {
                        dirListEl.innerHTML = '<div style="color:var(--text-secondary);font-size:12px;padding:8px 0">' + I18n.t('gitdiff.emptyDirectory') + '</div>';
                        return;
                    }
                    var html = '<div style="font-size:12px;">';
                    children.forEach(function(child) {
                        var icon = child.type === 'directory'
                            ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>'
                            : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>';
                        html += '<div class="git-dir-viewer-child" data-path="' + escapeHtml(child.path) + '" data-type="' + child.type + '" style="display:flex;align-items:center;gap:6px;padding:5px 8px;cursor:pointer;border-radius:4px;transition:background 0.15s">'
                            + icon + '<span style="font-family:var(--font-mono);font-size:var(--fs-xs)">' + escapeHtml(child.name) + '</span></div>';
                    });
                    html += '</div>';
                    dirListEl.innerHTML = html;

                    // 绑定点击事件
                    dirListEl.querySelectorAll('.git-dir-viewer-child').forEach(function(childEl) {
                        childEl.addEventListener('mouseenter', function() { this.style.background = 'var(--bg-hover)'; });
                        childEl.addEventListener('mouseleave', function() { this.style.background = ''; });
                        childEl.addEventListener('click', function() {
                            var cp = this.getAttribute('data-path');
                            var ct = this.getAttribute('data-type');
                            if (ct === 'directory') {
                                openDiffViewer(cp + '/', '?');
                            } else {
                                openFileViewer(cp, cp.replace(/.*\//, ''), '?');
                            }
                        });
                    });
                })
                .catch(function(e) {
                    var dirListEl = document.getElementById('gitDirFileList');
                    if (dirListEl) dirListEl.innerHTML = '<div style="color:var(--color-danger);font-size:12px">' + I18n.t('gitdiff.loadFailed') + ': ' + escapeHtml(e.message) + '</div>';
                });
            return;
        }

        if (gitViewerContent) gitViewerContent.innerHTML = '<div style="padding:20px;color:var(--text-secondary)">' + I18n.t('common.loading') + '...</div>';

        fetch(gitUrl('/web/chat/git/diff', 'path=' + encodeURIComponent(path)))
            .then(function(r) { return r.json(); })
            .then(function(res) {
                var d = (res && res.data) ? res.data : {};
                var diffText = d.diff || '';
                if (!diffText.trim()) {
                    // 后端已对未跟踪文件生成整文件新增 diff；此处仅作兜底空态
                    gitViewerContent.innerHTML = '<div style="padding:20px;color:var(--text-secondary)">'
                        + (status === '?'
                            ? I18n.t('gitdiff.newFileHint')
                            : I18n.t('gitdiff.noChanges'))
                        + '</div>';
                } else {
                    renderViewerDiff(diffText);
                }
            })
            .catch(function(e) {
                if (gitViewerContent) gitViewerContent.innerHTML = '<div style="padding:20px;color:#cb2431">' + I18n.t('gitdiff.loadFailed') + ': ' + escapeHtml(e.message) + '</div>';
            })
            .finally(function() {
                renderViewerActions(path, status);
            });
    }

    // ---- Diff Viewer：渲染操作按钮（添加到Git / 移出暂存 / 回滚）----
    function renderViewerActions(path, status) {
        // 移除旧的操作栏（如有）
        var oldActions = gitViewer.querySelector('.git-viewer-actions');
        if (oldActions) oldActions.remove();

        var actionBar = document.createElement('div');
        actionBar.className = 'git-viewer-actions';
        var hasAction = false;

        if (status === '?') {
            // 未跟踪 -> 提供 "添加到 Git" 按钮
            var addBtn = document.createElement('button');
            addBtn.className = 'git-action-btn git-action-add';
            addBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg> ' + I18n.t('gitdiff.addToGit');
            addBtn.addEventListener('click', function() {
                addBtn.disabled = true;
                addBtn.textContent = I18n.t('gitdiff.adding') + '...';
                fetch(gitUrl('/web/chat/git/stage'), {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ path: path })
                })
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (res && res.code === 200) {
                        loadGitStatus();
                        closeCenterViewer();
                    } else {
                        alert(I18n.t('toast.operateFailed') + '：' + gitActionError(res));
                        addBtn.disabled = false;
                        addBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg> ' + I18n.t('gitdiff.addToGit');
                    }
                })
                .catch(function(e) {
                    alert(I18n.t('toast.operateFailed') + '：' + e.message);
                    addBtn.disabled = false;
                    addBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg> ' + I18n.t('gitdiff.addToGit');
                });
            });
            actionBar.appendChild(addBtn);
            hasAction = true;
        }

        if (status === 'S') {
            // 已暂存 -> 提供 "移出暂存" 按钮
            var unstageBtn = document.createElement('button');
            unstageBtn.className = 'git-action-btn git-action-unstage';
            unstageBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="5" y1="12" x2="19" y2="12"/></svg> ' + I18n.t('gitdiff.unstage');
            unstageBtn.addEventListener('click', function() {
                unstageBtn.disabled = true;
                unstageBtn.textContent = I18n.t('gitdiff.unstaging') + '...';
                fetch(gitUrl('/web/chat/git/unstage'), {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ path: path })
                })
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (res && res.code === 200) {
                        loadGitStatus();
                        closeCenterViewer();
                    } else {
                        alert(I18n.t('toast.operateFailed') + '：' + gitActionError(res));
                        unstageBtn.disabled = false;
                        unstageBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="5" y1="12" x2="19" y2="12"/></svg> ' + I18n.t('gitdiff.unstage');
                    }
                })
                .catch(function(e) {
                    alert(I18n.t('toast.operateFailed') + '：' + e.message);
                    unstageBtn.disabled = false;
                    unstageBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="5" y1="12" x2="19" y2="12"/></svg> ' + I18n.t('gitdiff.unstage');
                });
            });
            actionBar.appendChild(unstageBtn);
            hasAction = true;
        }

        // 所有变更状态均可回滚：已修改 / 已暂存 / 未跟踪
        if (status === 'M' || status === 'S' || status === '?') {
            var discardBtn = document.createElement('button');
            discardBtn.className = 'git-action-btn git-action-discard';
            discardBtn.title = status === '?'
                ? I18n.t('gitdiff.discardUntrackedHint')
                : I18n.t('gitdiff.discardHint');
            discardBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/></svg> ' + I18n.t('gitdiff.discard');
            discardBtn.addEventListener('click', function() {
                var confirmMsg = status === '?'
                    ? I18n.t('gitdiff.confirmDeleteUntracked', { path: path })
                    : I18n.t('gitdiff.confirmDiscard', { path: path });
                var confirmTitle = status === '?' ? I18n.t('history.confirmDelete') : I18n.t('gitdiff.confirmDiscardTitle');
                var confirmBtn = status === '?' ? I18n.t('common.delete') : I18n.t('gitdiff.discard');
                var discardIconHtml = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/></svg> ' + I18n.t('gitdiff.discard');

                function doDiscard() {
                    discardBtn.disabled = true;
                    discardBtn.textContent = I18n.t('gitdiff.discarding') + '...';
                    fetch(gitUrl('/web/chat/git/discard'), {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ path: path })
                    })
                    .then(function(r) { return r.json(); })
                    .then(function(res) {
                        if (res && res.code === 200) {
                            if (typeof showToast === 'function') {
                                showToast(I18n.t('gitdiff.discarded', { path: path }), 'success', 2200);
                            }
                            loadGitStatus();
                            closeCenterViewer();
                        } else {
                            if (typeof layer !== 'undefined' && layer.msg) {
                                layer.msg(I18n.t('gitdiff.discardFailed', { error: gitActionError(res) }), { icon: 2, time: 3000, offset: '120px' });
                            } else if (typeof showToast === 'function') {
                                showToast(I18n.t('gitdiff.discardFailed', { error: gitActionError(res) }), 'error');
                            }
                            discardBtn.disabled = false;
                            discardBtn.innerHTML = discardIconHtml;
                        }
                    })
                    .catch(function(e) {
                        if (typeof layer !== 'undefined' && layer.msg) {
                            layer.msg(I18n.t('gitdiff.discardFailed', { error: e.message }), { icon: 2, time: 3000, offset: '120px' });
                        } else if (typeof showToast === 'function') {
                            showToast(I18n.t('gitdiff.discardFailed', { error: e.message }), 'error');
                        }
                        discardBtn.disabled = false;
                        discardBtn.innerHTML = discardIconHtml;
                    });
                }

                if (typeof layer !== 'undefined' && layer.confirm) {
                    layer.confirm(confirmMsg, {
                        title: confirmTitle,
                        btn: [confirmBtn, I18n.t('common.cancel')],
                        icon: 3,
                        offset: '120px'
                    }, function(index) {
                        layer.close(index);
                        doDiscard();
                    });
                } else if (window.confirm(confirmMsg)) {
                    doDiscard();
                }
            });
            actionBar.insertBefore(discardBtn, actionBar.firstChild);
            hasAction = true;
        }

        if (!hasAction) return;

        // 插入到 header 后面、content 前面
        var content = gitViewer.querySelector('.git-viewer-content');
        if (content) {
            gitViewer.insertBefore(actionBar, content);
        }
    }

    function gitActionError(res) {
        if (!res) return I18n.t('toast.unknownError');
        if (res.message) return res.message;
        if (res.data && res.data.message) return res.data.message;
        return I18n.t('toast.unknownError');
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
    function closeCenterViewer() {
        if (!gitViewer) return;

        // 如果当前在全屏状态，退出全屏
        if (document.fullscreenElement === gitViewer && document.exitFullscreen) {
            document.exitFullscreen().catch(function(e){});
        }

        gitViewer.style.display = 'none';
        diffViewerActive = false;

        // 恢复主区顶部条（审查/文件详情/记忆面板均在打开时将其隐藏）
        document.body.classList.remove('memory-active');
        gitViewer.classList.remove('mem-overlay');

        // 清理操作栏
        var oldActions = gitViewer.querySelector('.git-viewer-actions');
        if (oldActions) oldActions.remove();

        // 关键：必须先清除两个视图的内联 display 样式
        // 因为 chatView 的可见性由 CSS .active 类控制（.chat-view.active { display: flex }）
        // 如果残留 style="display:none"，会覆盖 CSS 类规则，导致视图空白
        if (chatView) chatView.style.display = '';
        if (newChatView) newChatView.style.display = '';

        // 根据当前模式恢复正确的可见性
        // chatView 可见性由 .active 类控制（CSS 规则），无需额外操作
        // newChatView 仅在非聊天模式下可见
        if (chatView && chatView.classList.contains('active')) {
            newChatView.style.display = 'none';
        }
    }

    if (gitViewerClose) {
        gitViewerClose.addEventListener('click', closeCenterViewer);
    }

    // ---- 全屏功能 ----
    var gitViewerFullscreen = document.getElementById('gitViewerFullscreen');
    if (gitViewerFullscreen) {
        gitViewerFullscreen.addEventListener('click', function() {
            if (!document.fullscreenElement) {
                if (gitViewer.requestFullscreen) {
                    gitViewer.requestFullscreen().catch(function(e) {
                        console.warn('[gitdiff] fullscreen error', e);
                    });
                }
            } else {
                if (document.exitFullscreen) {
                    document.exitFullscreen().catch(function(e) {
                        console.warn('[gitdiff] exit fullscreen error', e);
                    });
                }
            }
        });

        // 监听全屏状态变化，更新图标
        document.addEventListener('fullscreenchange', function() {
            if (document.fullscreenElement === gitViewer) {
                gitViewerFullscreen.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 14h6v6m10-10h-6V4M4 10h6V4m10 10h-6v6"/></svg>';
                gitViewerFullscreen.title = I18n.t('gitdiff.exitFullscreen');
            } else {
                gitViewerFullscreen.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"/></svg>';
                gitViewerFullscreen.title = I18n.t('gitdiff.fullscreen');
            }
        });
    }

    // ESC 关闭 diff viewer
    document.addEventListener('keydown', function(e) {
        if (e.key === 'Escape' && diffViewerActive) {
            closeCenterViewer();
        }
    });

    // ---- AI 生成变更摘要（专用 HTTP 接口）----
    var gitSummaryBtn = document.getElementById('gitSummaryBtn');
    var isGeneratingSummary = false;

    if (gitSummaryBtn) {
        gitSummaryBtn.addEventListener('click', function() {
            if (isGeneratingSummary) return;

            var files = getSelectedFiles();
            if (files.length === 0) {
                if (typeof showToast === 'function') showToast(I18n.t('gitdiff.selectAtLeastOneFile'), 'error');
                else alert(I18n.t('gitdiff.selectAtLeastOneFile'));
                return;
            }

            isGeneratingSummary = true;
            gitSummaryBtn.disabled = true;
            gitSummaryBtn.classList.add('loading');
            gitSummaryBtn.innerHTML = I18n.t('gitdiff.generating') + '...';
            if (gitCommitMsg) gitCommitMsg.value = '';

            // 获取当前会话的 sessionId
            var currentSessionId = (typeof activeSessionId !== 'undefined') ? activeSessionId : '';

        fetch(gitUrl('/web/chat/git/summary'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'sessionId=' + encodeURIComponent(currentSessionId)
                    + '&paths=' + encodeURIComponent(JSON.stringify(files))
            })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                if (res && res.code === 200 && res.data) {
                    var summary = res.data.summary || '';
                    if (gitCommitMsg) {
                        gitCommitMsg.value = summary;
                        gitCommitMsg.style.height = 'auto';
                        gitCommitMsg.style.height = Math.min(gitCommitMsg.scrollHeight, 80) + 'px';
                    }
                } else {
                    var errMsg = (res && res.description) || I18n.t('toast.unknownError');
                    if (typeof showToast === 'function') showToast(I18n.t('gitdiff.generateSummaryFailed', { error: errMsg }), 'error');
                    else alert(I18n.t('gitdiff.generateSummaryFailed', { error: errMsg }));
                }
            })
            .catch(function(e) {
                if (typeof showToast === 'function') showToast(I18n.t('gitdiff.generateSummaryFailed', { error: e.message }), 'error');
                    else alert(I18n.t('gitdiff.generateSummaryFailed', { error: e.message }));
            })
            .finally(function() {
                isGeneratingSummary = false;
                if (gitSummaryBtn) {
                    gitSummaryBtn.disabled = false;
                    gitSummaryBtn.classList.remove('loading');
                    gitSummaryBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 4V2"/><path d="M15 16v-2"/><path d="M8 9h2"/><path d="M20 9h2"/><path d="M17.8 11.8L19 13"/><path d="M15 9h.01"/><path d="M17.8 6.2L19 5"/><path d="m3 21 9-9"/><path d="M12.2 6.2L11 5"/></svg> ' + I18n.t('git.generateSummaryBtn');
                }
            });
        });
    }

    // ---- Git 提交（精确文件列表）----
    var isCommitting = false;
    if (gitCommitBtn) {
        gitCommitBtn.addEventListener('click', function() {
            if (isCommitting) return;
            var msg = (gitCommitMsg && gitCommitMsg.value.trim()) || '';
            if (!msg) {
                gitCommitMsg && gitCommitMsg.focus();
                gitCommitMsg && gitCommitMsg.classList.add('shake');
                var origPH = gitCommitMsg.placeholder;
                gitCommitMsg.placeholder = I18n.t('gitdiff.pleaseEnterCommitMsg');
                setTimeout(function() {
                    gitCommitMsg && gitCommitMsg.classList.remove('shake');
                    gitCommitMsg.placeholder = origPH;
                }, 1200);
                return;
            }
            var files = getSelectedFiles();
            if (files.length === 0) {
                if (typeof showToast === 'function') showToast(I18n.t('gitdiff.selectAtLeastOneFile'), 'error');
                return;
            }

            isCommitting = true;
            gitCommitBtn.disabled = true;
            gitCommitBtn.innerHTML = '<span style="opacity:0.7">' + I18n.t('gitdiff.committing') + '...</span>';

        fetch(gitUrl('/web/chat/git/commit'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ message: msg, files: files })
            })
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (res && res.code === 200) {
                        if (gitCommitMsg) {
                            gitCommitMsg.value = '';
                            gitCommitMsg.style.height = '30px';
                        }
                        loadGitStatus();
                        // 提交成功，不显示提示
                    } else {
                        alert(I18n.t('gitdiff.commitFailed', { error: ((res && res.data && res.data.message) || I18n.t('toast.unknownError')) }));
                    }
                })
                .catch(function(e) {
                    alert(I18n.t('gitdiff.commitFailed', { error: e.message }));
                })
                .finally(function() {
                    isCommitting = false;
                    gitCommitBtn.disabled = false;
                    gitCommitBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg> ' + I18n.t('git.commitBtn');
                });
        });

        // Enter 键提交
        if (gitCommitMsg) {
            gitCommitMsg.addEventListener('keydown', function(e) {
                if (e.key === 'Enter' && !e.shiftKey && !isInputComposing(e)) {
                    e.preventDefault();
                    gitCommitBtn.click();
                }
            });
            // textarea 自动增高（最多4行）
            gitCommitMsg.addEventListener('input', function() {
                this.style.height = 'auto';
                this.style.height = Math.min(this.scrollHeight, 80) + 'px';
            });
        }
    }

    // ---- 初始化 Git 仓库 ----
    if (gitInitBtn) {
        gitInitBtn.addEventListener('click', function() {
            if (isInitializing) return;
            isInitializing = true;
            gitInitBtn.disabled = true;
            gitInitBtn.textContent = I18n.t('gitdiff.initializing') + '...';

            var doCommit = gitInitCommit && gitInitCommit.checked;
        fetch(gitUrl('/web/chat/git/init', 'initialCommit=' + (doCommit ? 'true' : 'false')), { method: 'POST' })
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (res && res.code === 200) {
                        loadGitStatus();
                    } else {
                        alert(I18n.t('gitdiff.initFailed', { error: ((res && res.data && res.data.message) || I18n.t('toast.unknownError')) }));
                        gitInitBtn.disabled = false;
                        gitInitBtn.innerHTML =
                            '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg> ' + I18n.t('git.initBtn');
                    }
                })
                .catch(function(e) {
                    alert(I18n.t('gitdiff.initFailed', { error: e.message }));
                    gitInitBtn.disabled = false;
                    gitInitBtn.innerHTML =
                        '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg> ' + I18n.t('git.initBtn');
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
        var gitTab = document.querySelector('.workspace-tab[data-tab="gitDiff"]');
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

    // ---- 工作区切换事件（自定义下拉）----
    document.addEventListener('click', function(e) {
        var $selector = document.getElementById('gitWorkspaceSelector');
        if (!$selector) return;
        var $current = document.getElementById('gitWorkspaceCurrent');
        var $dropdown = document.getElementById('gitWorkspaceDropdown');

        // 点击 current 切换打开/关闭
        if ($current && $current.contains(e.target)) {
            e.stopPropagation();
            $selector.classList.toggle('open');
            return;
        }

        // 点击 dropdown item 选择工作区
        var $item = $(e.target).closest('.git-workspace-dropdown-item');
        if ($item.length) {
            e.stopPropagation();
            var ws = $item.attr('data-workspace-id');
            if (ws && ws !== gitWorkspace) {
                gitWorkspace = ws;
                // 更新显示名称
                var $name = document.getElementById('gitWorkspaceName');
                if ($name) $name.textContent = $item.text();
                loadGitStatus();
            }
            $selector.classList.remove('open');
            return;
        }

        // 点击外部关闭
        if ($selector.classList.contains('open') && !$selector.contains(e.target)) {
            $selector.classList.remove('open');
        }
    });

    // ---- 初始化 ----
    loadGitWorkspaces();
    // 每60秒兜底刷新
    setInterval(loadGitStatus, 60000);

    // 语言包首次加载完成后重渲染（修复启动竞态：fetch 先于 zh-CN.json 返回时显示 key 名）
    document.addEventListener('i18n:loaded', function() {
        if (gitWritableWorkspaces.length > 0) {
            renderGitWorkspaceBar(gitWritableWorkspaces);
        }
    });
    // 语言切换后重渲染 git 工作区名
    document.addEventListener('i18n:switched', function() {
        if (typeof gitWritableWorkspaces !== 'undefined' && gitWritableWorkspaces.length > 0) {
            renderGitWorkspaceBar(gitWritableWorkspaces);
        }
    });

    // 暴露全局（供 app-filer.js / app-message.js 调用）
    window.loadGitStatus = loadGitStatus;
    window.loadGitWorkspaces = loadGitWorkspaces;
    window.openFileViewer = openFileViewer;
    window.closeCenterViewer = closeCenterViewer;
    window.guessLang = guessLang;
})();

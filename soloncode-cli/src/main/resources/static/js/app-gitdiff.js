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
    var gitRefreshBtn = document.getElementById('gitRefreshBtn');

    // Diff Viewer / File Viewer 元素（内联在 main-area 内）
    var gitDiffViewer = document.getElementById('gitDiffViewer');
    var gitViewerLabel = document.getElementById('gitViewerLabel');
    var gitViewerFile = document.getElementById('gitViewerFile');
    var gitViewerContent = document.getElementById('gitViewerContent');
    var gitViewerClose = document.getElementById('gitViewerClose');
    // main-area 子视图引用
    var welcomeView = document.getElementById('welcomeView');

    // ---- 多工作区状态 ----
    var gitWorkspace = 'workspace';
    var gitWritableWorkspaces = [];

    function gitUrl(basePath, params) {
        var query = params || '';
        if (gitWorkspace !== 'workspace') {
            query += (query ? '&' : '') + 'workspace=' + encodeURIComponent(gitWorkspace);
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
            var label = ws.name || ws.id;
            if (ws.id === gitWorkspace) currentLabel = label;
            html += '<div class="git-workspace-dropdown-item" data-workspace="' + ws.id + '">' + escapeHtml(label) + '</div>';
        });
        $items.innerHTML = html;
        $name.textContent = currentLabel || '工作区';
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
            if (targetTab === 'gitdiff') loadGitStatus();
            // 切到任务 tab 时刷新
            if (targetTab === 'tasks' && window.loadTodos) window.loadTodos();
            // 持久化
            localStorage.setItem('filer-active-tab', targetTab);
        });
    });

    // 恢复 Tab 状态
    var savedTab = localStorage.getItem('filer-active-tab');
    if (savedTab && savedTab !== 'files') {
        var savedContentId = 'tabContent' + savedTab.charAt(0).toUpperCase() + savedTab.slice(1);
        var savedTabEl = document.querySelector('.filer-tab[data-tab="' + savedTab + '"]');
        var savedContentEl = document.getElementById(savedContentId);
        if (savedTabEl && savedContentEl) {
            tabs.forEach(function(t) { t.classList.remove('active'); });
            tabContents.forEach(function(tc) { tc.classList.remove('active'); });
            savedTabEl.classList.add('active');
            savedContentEl.classList.add('active');
            if (savedTab === 'gitdiff') loadGitStatus();
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
            var item = document.createElement('div');
            item.className = 'git-file-item';

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

    function openFileViewer(path, name) {
        if (!gitDiffViewer) return;

        viewerMode = 'file';

        // 隐藏欢迎页和聊天视图
        if (welcomeView) welcomeView.style.display = 'none';
        if (chatView) chatView.style.display = 'none';

        // 显示 viewer
        gitDiffViewer.style.display = 'flex';
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
        if (gitViewerLabel) gitViewerLabel.textContent = '文件内容';
        if (gitViewerFile) gitViewerFile.textContent = displayPath;

        // 清理操作栏
        var oldActions = gitDiffViewer.querySelector('.git-viewer-actions');
        if (oldActions) oldActions.remove();

        if (gitViewerContent) gitViewerContent.innerHTML = '<div style="padding:20px;color:var(--text-secondary)">加载中...</div>';

        var readUrl = '/web/chat/filer/read?path=' + encodeURIComponent(apiPath);
        if (fileWorkspace !== 'workspace') {
            readUrl += '&workspace=' + encodeURIComponent(fileWorkspace);
        }
        fetch(readUrl)
            .then(function(r) { return r.json(); })
            .then(function(res) {
                var d = (res && res.data) ? res.data : {};
                if (res && res.code !== 200) {
                    gitViewerContent.innerHTML = '<div style="padding:20px;color:var(--color-danger)">'
                        + escapeHtml((res && res.data && res.data.message) || res.description || '无法读取文件')
                        + '</div>';
                    return;
                }
                renderFileContent(d.content, d.name || name, d.size, path);
            })
            .catch(function(e) {
                if (gitViewerContent) gitViewerContent.innerHTML = '<div style="padding:20px;color:var(--color-danger)">加载失败: ' + escapeHtml(e.message) + '</div>';
            });
    }

    // ---- File Viewer：渲染文件内容（语法高亮 + 行号）----
    function renderFileContent(content, fileName, fileSize, filePath) {
        if (!gitViewerContent) return;

        // 重置 MD 切换按钮为初始状态（源码态），应对切换文件时图标未复位的问题
        var _mdToggleReset = document.getElementById('gitViewerMdToggle');
        if (_mdToggleReset) {
            _mdToggleReset.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
            _mdToggleReset.title = '预览 Markdown';
        }
        // 显示复制按钮（可能在审查详情中被隐藏）
        var _copyBtnReset = document.getElementById('gitViewerCopyBtn');
        if (_copyBtnReset) _copyBtnReset.style.display = '';

        // 显示全屏按钮（可能在审查详情中被隐藏）
        var _fullscreenReset = document.getElementById('gitViewerFullscreen');
        if (_fullscreenReset) _fullscreenReset.style.display = '';

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

        // 如果有 hljs 且能识别语言，对代码区进行语法高亮
        if (lang && typeof hljs !== 'undefined') {
            var codeBlock = gitViewerContent.querySelector('.file-view-code');
            if (codeBlock) {
                try {
                    // 将纯文本替换为高亮后的 HTML
                    var rawText = content || '';
                    var highlighted = hljs.highlight(rawText, { language: lang, ignoreIllegals: true });
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
                            // 切换到"视图"模式
                            code.style.display = 'none';
                            wrap.style.display = 'block';

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
                            toggle.title = '查看源码';
                        } else {
                            // 切换回"源码"模式
                            code.style.display = 'block';
                            wrap.style.display = 'none';

                            // 切换 SVG 图标为"眼睛"图标
                            toggle.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
                            toggle.title = '预览 Markdown';
                        }
                    });
                })(content, mdToggleBtn, mdFrameWrap, codeBlock);
            }
        }

        gitViewerContent.scrollTop = 0;
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
        if (!gitDiffViewer) return;

        viewerMode = 'diff';

        // 隐藏欢迎页和聊天视图
        if (welcomeView) welcomeView.style.display = 'none';
        if (chatView) chatView.style.display = 'none';

        // 显示 diff viewer
        gitDiffViewer.style.display = 'flex';
        diffViewerActive = true;

        // 审查详情模式：隐藏"视图"和"复制"按钮（全屏按钮保留）
        var _mdToggle = document.getElementById('gitViewerMdToggle');
        var _copyBtn = document.getElementById('gitViewerCopyBtn');
        var _fullscreenBtn = document.getElementById('gitViewerFullscreen');
        if (_mdToggle) _mdToggle.style.display = 'none';
        if (_copyBtn) _copyBtn.style.display = 'none';
        if (_fullscreenBtn) _fullscreenBtn.style.display = '';

        if (gitViewerLabel) gitViewerLabel.textContent = '变更详情';
        // 如果是挂载工作区，显示路径时带上 @xxx/ 前缀
        var displayDiffPath = path;
        if (gitWorkspace !== 'workspace') {
            displayDiffPath = gitWorkspace + '/' + displayDiffPath;
        }
        if (gitViewerFile) gitViewerFile.textContent = displayDiffPath;

        // 判断是否是目录（以 / 结尾）
        var isDir = path.endsWith('/');

        if (isDir) {
            // 目录：显示提示信息，不调用 diff 接口
            if (gitViewerContent) {
                gitViewerContent.innerHTML = '<div style="padding:20px;color:var(--text-secondary)">'
                    + '<div style="margin-bottom:8px"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path></svg> ' + escapeHtml(displayDiffPath) + '</div>'
                    + '<div>这是一个目录，暂无可查看的文本差异。</div>'
                    + '</div>';
            }
            renderViewerActions(path, status);
            return;
        }

        if (gitViewerContent) gitViewerContent.innerHTML = '<div style="padding:20px;color:var(--text-secondary)">加载中...</div>';

        fetch(gitUrl('/web/chat/git/diff', 'path=' + encodeURIComponent(path)))
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
        fetch(gitUrl('/web/chat/git/stage'), {
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
        fetch(gitUrl('/web/chat/git/unstage'), {
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

        // 如果当前在全屏状态，退出全屏
        if (document.fullscreenElement === gitDiffViewer && document.exitFullscreen) {
            document.exitFullscreen().catch(function(e){});
        }

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

    // ---- 全屏功能 ----
    var gitViewerFullscreen = document.getElementById('gitViewerFullscreen');
    if (gitViewerFullscreen) {
        gitViewerFullscreen.addEventListener('click', function() {
            if (!document.fullscreenElement) {
                if (gitDiffViewer.requestFullscreen) {
                    gitDiffViewer.requestFullscreen().catch(function(e) {
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
            if (document.fullscreenElement === gitDiffViewer) {
                gitViewerFullscreen.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 14h6v6m10-10h-6V4M4 10h6V4m10 10h-6v6"/></svg>';
                gitViewerFullscreen.title = '退出全屏';
            } else {
                gitViewerFullscreen.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"/></svg>';
                gitViewerFullscreen.title = '全屏';
            }
        });
    }

    // ESC 关闭 diff viewer
    document.addEventListener('keydown', function(e) {
        if (e.key === 'Escape' && diffViewerActive) {
            closeDiffViewer();
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
                if (typeof showToast === 'function') showToast('请至少勾选一个文件', 'error');
                else alert('请至少勾选一个文件');
                return;
            }

            isGeneratingSummary = true;
            gitSummaryBtn.disabled = true;
            gitSummaryBtn.classList.add('loading');
            gitSummaryBtn.innerHTML = '生成中...';
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
                    var errMsg = (res && res.description) || '未知错误';
                    if (typeof showToast === 'function') showToast('生成摘要失败: ' + errMsg, 'error');
                    else alert('生成摘要失败: ' + errMsg);
                }
            })
            .catch(function(e) {
                if (typeof showToast === 'function') showToast('生成摘要失败: ' + e.message, 'error');
                else alert('生成摘要失败: ' + e.message);
            })
            .finally(function() {
                isGeneratingSummary = false;
                if (gitSummaryBtn) {
                    gitSummaryBtn.disabled = false;
                    gitSummaryBtn.classList.remove('loading');
                    gitSummaryBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 4V2"/><path d="M15 16v-2"/><path d="M8 9h2"/><path d="M20 9h2"/><path d="M17.8 11.8L19 13"/><path d="M15 9h.01"/><path d="M17.8 6.2L19 5"/><path d="m3 21 9-9"/><path d="M12.2 6.2L11 5"/></svg> 生成摘要';
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
                gitCommitMsg.placeholder = '请输入提交信息';
                setTimeout(function() {
                    gitCommitMsg && gitCommitMsg.classList.remove('shake');
                    gitCommitMsg.placeholder = origPH;
                }, 1200);
                return;
            }
            var files = getSelectedFiles();
            if (files.length === 0) {
                if (typeof showToast === 'function') showToast('请至少勾选一个文件', 'error');
                return;
            }

            isCommitting = true;
            gitCommitBtn.disabled = true;
            gitCommitBtn.innerHTML = '<span style="opacity:0.7">提交中...</span>';

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
                if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
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
            gitInitBtn.textContent = '初始化中...';

            var doCommit = gitInitCommit && gitInitCommit.checked;
        fetch(gitUrl('/web/chat/git/init', 'initialCommit=' + (doCommit ? 'true' : 'false')), { method: 'POST' })
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
            var ws = $item.attr('data-workspace');
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

    // 暴露全局（供 app-filer.js / app-message.js 调用）
    window.loadGitStatus = loadGitStatus;
    window.loadGitWorkspaces = loadGitWorkspaces;
    window.openFileViewer = openFileViewer;
    window.closeDiffViewer = closeDiffViewer;
    window.guessLang = guessLang;
})();

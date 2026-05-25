/* ===== app-filer.js ===== */
/* 工作区文件树面板（右侧） */

(function() {
    var panel = document.getElementById('filerPanel');
    var toggleBtn = document.getElementById('filerToggleBtn');
    var treeEl = document.getElementById('filerTree');
    var worknameEl = document.getElementById('filerWorkname');
    var resizeHandle = document.getElementById('filerResizeHandle');

    var FILER_MIN_WIDTH = 180;
    var FILER_MAX_WIDTH = 600;
    var FILER_DEFAULT_WIDTH = 280;

    // ---- 同步 toggle 按钮位置 ----
    function syncToggleBtnPosition() {
        if (!toggleBtn || !panel) return;
        var collapsed = panel.classList.contains('collapsed');
        if (collapsed) {
            toggleBtn.style.right = '4px';
        } else {
            var w = panel.offsetWidth;
            toggleBtn.style.right = (w - 14) + 'px';
        }
    }

    // ---- 拖拽调整大小 ----
    function initResize() {
        if (!resizeHandle || !panel) return;

        var isDragging = false;
        var startX = 0;
        var startWidth = 0;

        resizeHandle.addEventListener('mousedown', function(e) {
            if (panel.classList.contains('collapsed')) return;
            isDragging = true;
            startX = e.clientX;
            startWidth = panel.offsetWidth;
            resizeHandle.classList.add('dragging');
            document.body.style.cursor = 'col-resize';
            document.body.style.userSelect = 'none';
            e.preventDefault();
        });

        document.addEventListener('mousemove', function(e) {
            if (!isDragging) return;
            var dx = startX - e.clientX; // 拖向左边 dx > 0
            var newWidth = Math.max(FILER_MIN_WIDTH, Math.min(FILER_MAX_WIDTH, startWidth + dx));
            panel.style.width = newWidth + 'px';
            localStorage.setItem('filer-width', newWidth);
            syncToggleBtnPosition();
        });

        document.addEventListener('mouseup', function() {
            if (!isDragging) return;
            isDragging = false;
            resizeHandle.classList.remove('dragging');
            document.body.style.cursor = '';
            document.body.style.userSelect = '';
        });
    }

    // ---- 恢复持久化宽度 ----
    function restoreWidth() {
        if (!panel) return;
        var savedWidth = localStorage.getItem('filer-width');
        if (savedWidth) {
            var w = parseInt(savedWidth, 10);
            if (w >= FILER_MIN_WIDTH && w <= FILER_MAX_WIDTH) {
                panel.style.width = w + 'px';
            }
        }
    }

    // ---- Toggle 折叠 ----
    var mainHeader = document.querySelector('.main-header');

    function syncHeaderPadding(collapsed) {
        if (!mainHeader) return;
        if (collapsed) {
            mainHeader.classList.add('filer-collapsed');
        } else {
            mainHeader.classList.remove('filer-collapsed');
        }
    }

    if (toggleBtn) {
        toggleBtn.addEventListener('click', function() {
            panel.classList.toggle('collapsed');
            var collapsed = panel.classList.contains('collapsed');
            toggleBtn.classList.toggle('collapsed', collapsed);
            toggleBtn.innerHTML = collapsed ? '\u2039' : '\u203A';
            toggleBtn.title = collapsed ? '\u5C55\u5F00\u6587\u4EF6\u6811' : '\u6536\u7F29\u6587\u4EF6\u6811';
            localStorage.setItem('filer-collapsed', collapsed ? '1' : '0');
            syncHeaderPadding(collapsed);
            syncToggleBtnPosition();
        });
    }

    // 恢复持久化状态
    restoreWidth();
    if (localStorage.getItem('filer-collapsed') === '1') {
        if (panel) panel.classList.add('collapsed');
        if (toggleBtn) {
            toggleBtn.classList.add('collapsed');
            toggleBtn.innerHTML = '\u2039';
            toggleBtn.title = '\u5C55\u5F00\u6587\u4EF6\u6811';
        }
        syncHeaderPadding(true);
    }
    syncToggleBtnPosition();
    initResize();

    // ---- 收集当前已展开的目录路径集合 ----
    function collectExpandedPaths() {
        var paths = {};
        if (!treeEl) return paths;
        treeEl.querySelectorAll('.filer-node-children.open').forEach(function(cEl) {
            var parent = cEl.parentElement;
            if (parent && parent.getAttribute('data-path')) {
                paths[parent.getAttribute('data-path')] = true;
            }
        });
        return paths;
    }

    // ---- 加载文件树 ----
    function loadTree() {
        fetch('/chat/filer/tree?depth=1')
            .then(function(r) { return r.json(); })
            .then(function(res) {
                var data = (res && res.data) ? res.data : [];
                if (treeEl) renderTree(data, treeEl, 0);
            })
            .catch(function(e) { console.error('[filer] load error', e); });
    }

    // ---- 渲染树节点 ----
    function renderTree(nodes, container, indent) {
        container.innerHTML = '';
        nodes.forEach(function(node) {
            appendNode(node, container, indent);
        });
    }

    // ---- 渲染并追加单个节点 ----
    function appendNode(node, container, indent) {
        var nodeEl = document.createElement('div');
        nodeEl.className = 'filer-node';
        nodeEl.setAttribute('data-indent', indent);
        nodeEl.setAttribute('data-path', node.path);
        nodeEl.setAttribute('data-type', node.type);

        var row = document.createElement('div');
        row.className = 'filer-node-row';

        if (node.type === 'directory') {
            var arrow = document.createElement('span');
            arrow.className = 'filer-arrow' + (node.expanded ? ' open' : '');
            arrow.innerHTML = '<svg width="12" height="12" viewBox="0 0 16 16"><path d="M6 3l5 5-5 5" stroke="currentColor" stroke-width="1.8" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>';
            row.appendChild(arrow);
        } else {
            var spacer = document.createElement('span');
            spacer.className = 'filer-arrow filer-arrow-spacer';
            spacer.innerHTML = '&nbsp;';
            row.appendChild(spacer);

            var icon = document.createElement('span');
            icon.className = 'filer-node-icon filer-icon-file';
            icon.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M4 1.5h4.75L12.5 5.75V13.5a1 1 0 01-1 1H4a1 1 0 01-1-1V2.5a1 1 0 011-1z" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/><path d="M8.75 1.5v4.25H12.5" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/></svg>';
            row.appendChild(icon);
        }

        var name = document.createElement('span');
        name.className = 'filer-node-name';
        name.textContent = node.name;
        name.title = node.path;
        row.appendChild(name);

        nodeEl.appendChild(row);

        if (node.type === 'directory') {
            var childrenEl = document.createElement('div');
            childrenEl.className = 'filer-node-children' + (node.expanded ? ' open' : '');
            if (node.expanded && node.children) {
                renderTree(node.children, childrenEl, indent + 1);
            }
            nodeEl.appendChild(childrenEl);
        }

        // 单击：目录展开/折叠；文件打开查看器
        if (node.type === 'directory') {
            (function(n, ne) {
                row.addEventListener('click', function(e) {
                    e.stopPropagation();
                    var cEl = ne.querySelector(':scope > .filer-node-children');
                    var aEl = row.querySelector('.filer-arrow');
                    if (!cEl) return;

                    var isOpen = cEl.classList.contains('open');
                    if (isOpen) {
                        cEl.classList.remove('open');
                        if (aEl) aEl.classList.remove('open');
                    } else {
                        cEl.classList.add('open');
                        if (aEl) aEl.classList.add('open');
                        // dirty 标记或无子节点时，展开后重新拉取最新数据
                        var isDirty = ne.getAttribute('data-dirty') === '1';
                        if (isDirty || !cEl.hasChildNodes()) {
                            ne.removeAttribute('data-dirty');
                            fetch('/chat/filer/tree?path=' + encodeURIComponent(n.path) + '&depth=1')
                                .then(function(r) { return r.json(); })
                                .then(function(res) {
                                    var subData = (res && res.data) ? res.data : [];
                                    renderTree(subData, cEl, indent + 1);
                                });
                        }
                    }
                });
            })(node, nodeEl);
        } else {
            // 文件：单击打开文件查看器（延迟 250ms，避免与双击冲突）
            (function(n) {
                var clickTimer = null;
                row.addEventListener('click', function(e) {
                    e.stopPropagation();
                    if (clickTimer) { clearTimeout(clickTimer); clickTimer = null; }
                    clickTimer = setTimeout(function() {
                        if (typeof window.openFileViewer === 'function') {
                            window.openFileViewer(n.path, n.name);
                        }
                    }, 250);
                });
                row.addEventListener('dblclick', function(e) {
                    e.stopPropagation();
                    e.preventDefault();
                    if (clickTimer) { clearTimeout(clickTimer); clickTimer = null; }
                });
            })(node);
        }

        // 双击：插入 path 到输入框（所有节点通用）
        (function(n) {
            row.addEventListener('dblclick', function(e) {
                e.stopPropagation();
                e.preventDefault();

                var targetInput = (typeof inChatMode !== 'undefined' && inChatMode) ? chatInput : welcomeInput;
                if (!targetInput) return;
                var currentVal = targetInput.value || '';
                var insertText = '[' + n.path + ']';
                var cursorPos = targetInput.selectionStart || currentVal.length;
                var before = currentVal.substring(0, cursorPos);
                var after = currentVal.substring(cursorPos);
                var prefix = (before.length > 0 && !before.endsWith(' ') && !before.endsWith('\n')) ? ' ' : '';
                targetInput.value = before + prefix + insertText + ' ' + after;
                targetInput.focus();
                var newPos = cursorPos + prefix.length + insertText.length + 1;
                targetInput.setSelectionRange(newPos, newPos);
                if (typeof autoResize === 'function') autoResize(targetInput);
            });
        })(node);

        container.appendChild(nodeEl);
    }

    // ---- 文件变更实时同步 ----
    function onFilerChange(chunk) {
        if (!chunk || !chunk.changes || chunk.changes.length === 0) return;

        // 文件树为空时，直接全量刷新根目录兜底
        if (!treeEl || !treeEl.hasChildNodes()) {
            smartRefreshRoot();
            showFilerChangeIndicator();
            return;
        }

        var changes = chunk.changes;

        // 收集所有受影响的父目录
        var affectedDirs = {};
        changes.forEach(function(path) {
            var lastSlash = path.lastIndexOf('/');
            var parentDir = lastSlash > 0 ? path.substring(0, lastSlash) : '';
            affectedDirs[parentDir] = true;
        });

        // 逐个刷新受影响的目录
        Object.keys(affectedDirs).forEach(function(dirPath) {
            refreshDirectory(dirPath);
        });

        showFilerChangeIndicator();
    }

    function refreshDirectory(dirPath) {
        if (!dirPath) {
            // 根目录变化：智能刷新，保留已展开目录的展开状态
            smartRefreshRoot();
            return;
        }

        if (!treeEl) return;
        var selector = '.filer-node[data-path="' + CSS.escape(dirPath) + '"]';
        var nodeEl = treeEl.querySelector(selector);
        if (!nodeEl) return;

        var childrenEl = nodeEl.querySelector(':scope > .filer-node-children');
        if (!childrenEl) return;

        var isExpanded = childrenEl.classList.contains('open');
        if (!isExpanded) {
            // 折叠的目录标记为 dirty，下次展开时重新拉取最新数据
            nodeEl.setAttribute('data-dirty', '1');
            return;
        }

        var indent = parseInt(nodeEl.getAttribute('data-indent') || '0', 10);
        fetch('/chat/filer/tree?path=' + encodeURIComponent(dirPath) + '&depth=1')
            .then(function(r) { return r.json(); })
            .then(function(res) {
                var subData = (res && res.data) ? res.data : [];
                // 先收集已展开的子目录，刷新后恢复
                var expandedPaths = {};
                childrenEl.querySelectorAll('.filer-node-children.open').forEach(function(cEl) {
                    var parent = cEl.parentElement;
                    if (parent && parent.getAttribute('data-path')) {
                        expandedPaths[parent.getAttribute('data-path')] = true;
                    }
                });
                renderTree(subData, childrenEl, indent + 1);
                // 恢复子目录展开状态（异步重新拉取数据）
                Object.keys(expandedPaths).forEach(function(expandedPath) {
                    var expSelector = '.filer-node[data-path="' + CSS.escape(expandedPath) + '"]';
                    var expNodeEl = childrenEl.querySelector(expSelector);
                    if (expNodeEl) {
                        var expChildrenEl = expNodeEl.querySelector(':scope > .filer-node-children');
                        var expArrow = expNodeEl.querySelector(':scope > .filer-node-row .filer-arrow');
                        var expIndent = parseInt(expNodeEl.getAttribute('data-indent') || '0', 10);
                        if (expChildrenEl) {
                            expChildrenEl.classList.add('open');
                            if (expArrow) expArrow.classList.add('open');
                            fetch('/chat/filer/tree?path=' + encodeURIComponent(expandedPath) + '&depth=1')
                                .then(function(r2) { return r2.json(); })
                                .then(function(res2) {
                                    var subData2 = (res2 && res2.data) ? res2.data : [];
                                    renderTree(subData2, expChildrenEl, expIndent + 1);
                                });
                        }
                    }
                });
            })
            .catch(function(e) { console.error('[filer] refresh error', dirPath, e); });
    }

    /**
     * 智能刷新根树：重新拉取根层节点，但保留已展开目录的展开状态
     */
    function smartRefreshRoot() {
        var expandedPaths = collectExpandedPaths();

        fetch('/chat/filer/tree?depth=1')
            .then(function(r) { return r.json(); })
            .then(function(res) {
                var newData = (res && res.data) ? res.data : [];
                if (!treeEl) return;

                treeEl.innerHTML = '';
                newData.forEach(function(node) {
                    if (expandedPaths[node.path] && node.type === 'directory') {
                        node.expanded = true;
                    }
                    appendNode(node, treeEl, 0);
                });

                // 对之前已展开的目录，重新拉取子节点
                Object.keys(expandedPaths).forEach(function(dirPath) {
                    var selector = '.filer-node[data-path="' + CSS.escape(dirPath) + '"]';
                    var nodeEl = treeEl.querySelector(selector);
                    if (!nodeEl) return;
                    var childrenEl = nodeEl.querySelector(':scope > .filer-node-children');
                    if (!childrenEl) return;
                    var indent = parseInt(nodeEl.getAttribute('data-indent') || '0', 10);

                    fetch('/chat/filer/tree?path=' + encodeURIComponent(dirPath) + '&depth=1')
                        .then(function(r2) { return r2.json(); })
                        .then(function(res2) {
                            var subData = (res2 && res2.data) ? res2.data : [];
                            renderTree(subData, childrenEl, indent + 1);
                        });
                });
            })
            .catch(function(e) { console.error('[filer] smart refresh root error', e); });
    }

    function showFilerChangeIndicator() {
        var filesTab = panel ? panel.querySelector('.filer-tab[data-tab="files"]') : null;
        if (!filesTab) return;
        var dot = filesTab.querySelector('.filer-change-dot');
        if (!dot) {
            dot = document.createElement('span');
            dot.className = 'filer-change-dot';
            filesTab.appendChild(dot);
        }
        dot.classList.add('active');
        setTimeout(function() { dot.classList.remove('active'); }, 2000);
    }

    // ---- 暴露全局函数 ----
    window.loadTree = loadTree;
    window.onFilerChange = onFilerChange;

    // ---- 搜索（后端全量搜索） ----
    var searchInput = document.getElementById('filerSearchInput');
    var searchClear = document.getElementById('filerSearchClear');
    var searchResultsEl = null;

    function ensureSearchResultsContainer() {
        if (!searchResultsEl && treeEl && treeEl.parentElement) {
            searchResultsEl = document.createElement('div');
            searchResultsEl.className = 'filer-search-results';
            treeEl.parentElement.insertBefore(searchResultsEl, treeEl.nextSibling);
        }
    }

    function escapeHtml(text) {
        var div = document.createElement('div');
        div.textContent = text || '';
        return div.innerHTML;
    }

    function showSearchResults(keyword) {
        if (!treeEl || !keyword) return;
        var kw = keyword.trim().toLowerCase();
        if (!kw) { hideSearchResults(); return; }

        treeEl.style.display = 'none';
        ensureSearchResultsContainer();
        searchResultsEl.style.display = 'block';
        searchResultsEl.innerHTML = '<div class="filer-search-loading">搜索中...</div>';

        fetch('/chat/filer/search?keyword=' + encodeURIComponent(kw))
            .then(function(r) { return r.json(); })
            .then(function(res) {
                var data = (res && res.data) ? res.data : [];
                searchResultsEl.innerHTML = '';

                if (data.length === 0) {
                    searchResultsEl.innerHTML = '<div class="filer-search-empty">未找到匹配文件</div>';
                    return;
                }

                data.forEach(function(item) {
                    var row = document.createElement('div');
                    row.className = 'filer-search-item';
                    row.setAttribute('data-path', item.path);
                    row.setAttribute('data-name', item.name);
                    row.setAttribute('data-type', item.type);

                    // 图标
                    var icon = document.createElement('span');
                    icon.className = 'filer-search-item-icon';
                    if (item.type === 'directory') {
                        icon.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M2 4a1 1 0 011-1h3.5l1.5 1.5H13a1 1 0 011 1V12a1 1 0 01-1 1H3a1 1 0 01-1-1V4z" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/></svg>';
                    } else {
                        icon.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M4 1.5h4.75L12.5 5.75V13.5a1 1 0 01-1 1H4a1 1 0 01-1-1V2.5a1 1 0 011-1z" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/><path d="M8.75 1.5v4.25H12.5" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/></svg>';
                    }
                    row.appendChild(icon);

                    // 路径显示（高亮匹配部分）
                    var pathSpan = document.createElement('span');
                    pathSpan.className = 'filer-search-item-path';
                    var pathLower = item.path.toLowerCase();
                    var idx = pathLower.indexOf(kw);
                    if (idx >= 0) {
                        pathSpan.innerHTML = escapeHtml(item.path.substring(0, idx))
                            + '<mark>' + escapeHtml(item.path.substring(idx, idx + kw.length)) + '</mark>'
                            + escapeHtml(item.path.substring(idx + kw.length));
                    } else {
                        pathSpan.textContent = item.path;
                    }
                    row.appendChild(pathSpan);

                    // 单击：打开文件查看器（延迟 250ms，避免与双击冲突）
                    if (item.type === 'file') {
                        (function(it, r) {
                            var clickTimer = null;
                            r.addEventListener('click', function(e) {
                                e.stopPropagation();
                                if (clickTimer) { clearTimeout(clickTimer); clickTimer = null; }
                                clickTimer = setTimeout(function() {
                                    if (typeof window.openFileViewer === 'function') {
                                        window.openFileViewer(it.path, it.name);
                                    }
                                }, 250);
                            });
                            r.addEventListener('dblclick', function(e) {
                                if (clickTimer) { clearTimeout(clickTimer); clickTimer = null; }
                            });
                        })(item, row);
                    }

                    // 双击：插入路径到输入框
                    (function(it) {
                        row.addEventListener('dblclick', function(e) {
                            e.stopPropagation();
                            e.preventDefault();
                            var targetInput = (typeof inChatMode !== 'undefined' && inChatMode) ? chatInput : welcomeInput;
                            if (!targetInput) return;
                            var currentVal = targetInput.value || '';
                            var insertText = '[' + it.path + ']';
                            var cursorPos = targetInput.selectionStart || currentVal.length;
                            var before = currentVal.substring(0, cursorPos);
                            var after = currentVal.substring(cursorPos);
                            var prefix = (before.length > 0 && !before.endsWith(' ') && !before.endsWith('\n')) ? ' ' : '';
                            targetInput.value = before + prefix + insertText + ' ' + after;
                            targetInput.focus();
                            var newPos = cursorPos + prefix.length + insertText.length + 1;
                            targetInput.setSelectionRange(newPos, newPos);
                            if (typeof autoResize === 'function') autoResize(targetInput);
                        });
                    })(item);

                    searchResultsEl.appendChild(row);
                });
            })
            .catch(function(e) {
                console.error('[filer] search error', e);
                searchResultsEl.innerHTML = '<div class="filer-search-empty">搜索失败</div>';
            });
    }

    function hideSearchResults() {
        if (treeEl) treeEl.style.display = '';
        if (searchResultsEl) searchResultsEl.style.display = 'none';
    }

    if (searchInput) {
        var searchTimer = null;
        searchInput.addEventListener('input', function() {
            var val = searchInput.value;
            if (searchClear) {
                searchClear.classList.toggle('visible', val.length > 0);
            }
            clearTimeout(searchTimer);
            searchTimer = setTimeout(function() {
                if (val.trim()) {
                    showSearchResults(val);
                } else {
                    hideSearchResults();
                }
            }, 250);
        });
    }
    if (searchClear) {
        searchClear.addEventListener('click', function() {
            if (searchInput) {
                searchInput.value = '';
                searchInput.focus();
            }
            searchClear.classList.remove('visible');
            hideSearchResults();
        });
    }

    // ---- 启动 ----
    loadTree();
})();

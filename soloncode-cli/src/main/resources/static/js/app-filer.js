/* ===== app-filer.js ===== */
/* 工作区文件树面板（右侧） */

(function() {
    var $panel = $('#filerPanel');
    var $toggleBtn = $('#filerToggleBtn');
    var $treeEl = $('#filerTree');
    var $worknameEl = $('#filerWorkname');
    var $resizeHandle = $('#filerResizeHandle');

    var FILER_MIN_WIDTH = 180;
    var FILER_MAX_WIDTH = 600;
    var FILER_DEFAULT_WIDTH = 280;

    /** 当前活动工作区（用于搜索/文件查看器） */
    window.activeFilerWorkspace = 'workspace';

    /** 查找 DOM 节点所属的工作区 ID */
    function getNodeWorkspaceId($el) {
        var $wsRoot = $el.closest('[data-workspace-id]');
        return $wsRoot.length ? ($wsRoot.attr('data-workspace-id') || 'workspace') : 'workspace';
    }

    /** 构建带 workspace 参数的 URL */
    function filerUrl(basePath, params) {
        var query = params || '';
        if (window.activeFilerWorkspace !== 'workspace') {
            query += (query ? '&' : '') + 'workspace=' + encodeURIComponent(window.activeFilerWorkspace);
        }
        return basePath + (query ? '?' + query : '');
    }

    // ---- 同步 toggle 按钮位置 ----
    function syncToggleBtnPosition(knownWidth) {
        if (!$toggleBtn.length || !$panel.length) return;
        var collapsed = $panel.hasClass('collapsed');
        if (collapsed) {
            $toggleBtn.css('right', '4px');
        } else {
            var w = knownWidth || $panel[0].offsetWidth;
            $toggleBtn.css('right', (w - 14) + 'px');
        }
    }

    // ---- 拖拽调整大小 ----
    function initResize() {
        if (!$resizeHandle.length || !$panel.length) return;

        var isDragging = false;
        var startX = 0;
        var startWidth = 0;
        var rafId = null;
        var latestClientX = 0;

        $resizeHandle.on('mousedown', function(e) {
            if ($panel.hasClass('collapsed')) return;
            isDragging = true;
            startX = e.clientX;
            startWidth = $panel[0].offsetWidth;
            $resizeHandle.addClass('dragging');
            $(document.body).css({ cursor: 'col-resize', userSelect: 'none' });
            e.preventDefault();
        });

        $(document).on('mousemove', function(e) {
            if (!isDragging) return;
            latestClientX = e.clientX;
            if (rafId) return;
            rafId = requestAnimationFrame(function() {
                rafId = null;
                var dx = startX - latestClientX;
                var newWidth = Math.max(FILER_MIN_WIDTH, Math.min(FILER_MAX_WIDTH, startWidth + dx));
                $panel.css('width', newWidth + 'px');
                syncToggleBtnPosition(newWidth);
            });
        });

        $(document).on('mouseup', function() {
            if (!isDragging) return;
            isDragging = false;
            if (rafId) { cancelAnimationFrame(rafId); rafId = null; }
            $resizeHandle.removeClass('dragging');
            $(document.body).css({ cursor: '', userSelect: '' });
            var finalWidth = $panel[0].offsetWidth;
            if (finalWidth >= FILER_MIN_WIDTH && finalWidth <= FILER_MAX_WIDTH) {
                localStorage.setItem('filer-width', finalWidth);
            }
        });
    }

    // ---- 恢复持久化宽度 ----
    function restoreWidth() {
        if (!$panel.length) return;
        var savedWidth = localStorage.getItem('filer-width');
        if (savedWidth) {
            var w = parseInt(savedWidth, 10);
            if (w >= FILER_MIN_WIDTH && w <= FILER_MAX_WIDTH) {
                $panel.css('width', w + 'px');
            }
        }
    }

    // ---- Toggle 折叠 ----
    var $mainHeader = $('.main-header');

    function syncHeaderPadding(collapsed) {
        if (!$mainHeader.length) return;
        if (collapsed) {
            $mainHeader.addClass('filer-collapsed');
        } else {
            $mainHeader.removeClass('filer-collapsed');
        }
    }

    if ($toggleBtn.length) {
        $toggleBtn.on('click', function() {
            $panel.toggleClass('collapsed');
            var collapsed = $panel.hasClass('collapsed');
            $toggleBtn.toggleClass('collapsed', collapsed);
            $toggleBtn.html(collapsed ? '\u2039' : '\u203A');
            $toggleBtn.attr('title', collapsed ? '\u5C55\u5F00\u6587\u4EF6\u6811' : '\u6536\u7F29\u6587\u4EF6\u6811');
            localStorage.setItem('filer-collapsed', collapsed ? '1' : '0');
            syncHeaderPadding(collapsed);
            syncToggleBtnPosition();
        });
    }

    // 恢复持久化状态
    restoreWidth();
    var shouldExpand = localStorage.getItem('filer-collapsed') === '0';
    if (shouldExpand) {
        $panel.removeClass('collapsed');
        $toggleBtn.removeClass('collapsed');
        $toggleBtn.html('\u203A');
        $toggleBtn.attr('title', '\u6536\u7F29\u6587\u4EF6\u6811');
        syncHeaderPadding(false);
    } else {
        syncHeaderPadding(true);
    }
    syncToggleBtnPosition();
    initResize();

    // ---- 收集当前已展开的目录路径集合 ----
    function collectExpandedPaths() {
        var paths = {};
        if (!$treeEl.length) return paths;
        $treeEl.find('.filer-node-children.open').each(function() {
            var $parent = $(this).parent();
            var dataPath = $parent.attr('data-path');
            if (dataPath) {
                paths[dataPath] = true;
            }
        });
        return paths;
    }

    /** 加载工作区列表作为树的根节点 */
    function loadTree() {
        $.get('/web/chat/filer/workspaces', function(res) {
            var wsList = (res && res.data) ? res.data : [];
            if ($treeEl.length) {
                $treeEl.html('');
                wsList.forEach(function(ws) {
                    appendWorkspaceNode(ws, $treeEl, 0);
                });
            }
        }).fail(function(jqXHR, textStatus, error) {
            console.error('[filer] workspaces load error', error);
            // fallback：直接用当前工作区文件树
            $.get('/web/chat/filer/tree?depth=1', function(res) {
                var data = (res && res.data) ? res.data : [];
                if ($treeEl.length) renderTree(data, $treeEl, 0);
            });
        });
    }

    /** 渲染工作区根节点（树的顶级节点） */
    function appendWorkspaceNode(ws, $container, indent) {
        var wsId = ws.id;
        var isReadonly = ws.readonly === true;

        var $nodeEl = $('<div>').addClass('filer-node')
            .attr('data-indent', indent)
            .attr('data-workspace-id', wsId)
            .attr('data-path', ws.name);

        var $row = $('<div>').addClass('filer-node-row')
            .addClass(isReadonly ? 'filer-workspace-readonly' : '')
            .attr('title', ws.name + (isReadonly ? ' (只读)' : ''));

        // 箭头
        var $arrow = $('<span>').addClass('filer-arrow')
            .html('<svg width="12" height="12" viewBox="0 0 16 16"><path d="M6 3l5 5-5 5" stroke="currentColor" stroke-width="1.8" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>');
        $row.append($arrow);

        // 图标（文件夹样式）
        var $icon = $('<span>').addClass('filer-node-icon')
            .html('<svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M2 4a1 1 0 011-1h3.5l1.5 1.5H13a1 1 0 011 1V12a1 1 0 01-1 1H3a1 1 0 01-1-1V4z" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/></svg>');
        $row.append($icon);

        // 名称
        var $name = $('<span>').addClass('filer-node-name')
            .text(ws.name);
        $row.append($name);

        // 只读徽标
        if (isReadonly) {
            $row.append('<span class="filer-ws-badge">只读</span>');
        }

        $nodeEl.append($row);

        // 子容器（工作区展开后，文件树渲染在此）
        var $childrenEl = $('<div>').addClass('filer-node-children');
        $nodeEl.append($childrenEl);

        // 点击展开/折叠
        (function(id, $ne, $cEl, $r) {
            $row.on('click', function(e) {
                e.stopPropagation();
                var $aEl = $r.find('.filer-arrow');
                var isOpen = $cEl.hasClass('open');
                if (isOpen) {
                    $cEl.removeClass('open');
                    $aEl.removeClass('open');
                } else {
                    // 设置为当前活动工作区（供搜索和文件查看器使用）
                    window.activeFilerWorkspace = id;

                    $cEl.addClass('open');
                    $aEl.addClass('open');
                    if (!$cEl.children().length) {
                        var url = '/web/chat/filer/tree?depth=1';
                        if (id !== 'workspace') {
                            url += '&workspace=' + encodeURIComponent(id);
                        }
                        $.get(url, function(res) {
                            var data = (res && res.data) ? res.data : [];
                            renderTree(data, $cEl, indent + 1);
                        }).fail(function() {
                            console.error('[filer] load workspace tree error', id);
                        });
                    }
                }
            });
        })(wsId, $nodeEl, $childrenEl, $row);

        $container.append($nodeEl);
    }

    // ---- 渲染树节点 ----
    function renderTree(nodes, $container, indent) {
        $container.html('');
        nodes.forEach(function(node) {
            appendNode(node, $container, indent);
        });
    }

    // ---- 渲染并追加单个节点 ----
    function appendNode(node, $container, indent) {
        var $nodeEl = $('<div>').addClass('filer-node')
            .attr('data-indent', indent)
            .attr('data-path', node.path)
            .attr('data-type', node.type);

        var $row = $('<div>').addClass('filer-node-row')
            .attr('title', node.type === 'directory'
                ? '单击展开/折叠，双击插入路径到输入框：' + node.path
                : '单击打开文件，双击插入路径到输入框：' + node.path);

        if (node.type === 'directory') {
            var $arrow = $('<span>').addClass('filer-arrow')
                .toggleClass('open', !!node.expanded)
                .html('<svg width="12" height="12" viewBox="0 0 16 16"><path d="M6 3l5 5-5 5" stroke="currentColor" stroke-width="1.8" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>');
            $row.append($arrow);
        } else {
            var $spacer = $('<span>').addClass('filer-arrow filer-arrow-spacer')
                .html('&nbsp;');
            $row.append($spacer);

            var $icon = $('<span>').addClass('filer-node-icon filer-icon-file')
                .html('<svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M4 1.5h4.75L12.5 5.75V13.5a1 1 0 01-1 1H4a1 1 0 01-1-1V2.5a1 1 0 011-1z" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/><path d="M8.75 1.5v4.25H12.5" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/></svg>');
            $row.append($icon);
        }

        var $name = $('<span>').addClass('filer-node-name')
            .text(node.name)
            .attr('title', node.type === 'directory'
                ? '单击展开/折叠，双击插入路径到输入框：' + node.path
                : '单击打开文件，双击插入路径到输入框：' + node.path);
        $row.append($name);

        $nodeEl.append($row);

        if (node.type === 'directory') {
            var $childrenEl = $('<div>').addClass('filer-node-children')
                .toggleClass('open', !!node.expanded);
            if (node.expanded && node.children) {
                renderTree(node.children, $childrenEl, indent + 1);
            }
            $nodeEl.append($childrenEl);
        }

        // 单击：目录展开/折叠；文件打开查看器
        if (node.type === 'directory') {
            (function(n, $ne) {
                $row.on('click', function(e) {
                    e.stopPropagation();
                    var $cEl = $ne.children('.filer-node-children');
                    var $aEl = $row.find('.filer-arrow');
                    if (!$cEl.length) return;

                    var isOpen = $cEl.hasClass('open');
                    if (isOpen) {
                        $cEl.removeClass('open');
                        $aEl.removeClass('open');
                    } else {
                        $cEl.addClass('open');
                        $aEl.addClass('open');
                        var isDirty = $ne.attr('data-dirty') === '1';
                        if (isDirty || !$cEl.children().length) {
                            $ne.removeAttr('data-dirty');
                            // 使用 workspace 感知的路径查询
                            var wsId = getNodeWorkspaceId($ne);
                            var url = '/web/chat/filer/tree?path=' + encodeURIComponent(n.path) + '&depth=1';
                            if (wsId !== 'workspace') {
                                url += '&workspace=' + encodeURIComponent(wsId);
                            }
                            $.get(url, function(res) {
                                var subData = (res && res.data) ? res.data : [];
                                renderTree(subData, $cEl, indent + 1);
                            });
                        }
                    }
                });
            })(node, $nodeEl);
        } else {
            // 文件：单击打开文件查看器（延迟 250ms，避免与双击冲突）
            (function(n, $ne) {
                var wsId = getNodeWorkspaceId($ne);
                var viewPath = (wsId !== 'workspace' && wsId.indexOf('@') === 0) ? wsId + '/' + n.path : n.path;
                var clickTimer = null;
                $row.on('click', function(e) {
                    e.stopPropagation();
                    if (clickTimer) { clearTimeout(clickTimer); clickTimer = null; }
                    clickTimer = setTimeout(function() {
                        if (typeof window.openFileViewer === 'function') {
                            window.openFileViewer(viewPath, n.name);
                        }
                    }, 250);
                });
                $row.on('dblclick', function(e) {
                    e.stopPropagation();
                    e.preventDefault();
                    if (clickTimer) { clearTimeout(clickTimer); clickTimer = null; }
                });
            })(node, $nodeEl);
        }

        // 双击：插入 path 到输入框（所有节点通用）
        (function(n, $ne) {
            $row.on('dblclick', function(e) {
                e.stopPropagation();
                e.preventDefault();

                var wsId = getNodeWorkspaceId($ne);
                var targetInput = (typeof inChatMode !== 'undefined' && inChatMode) ? chatInput : welcomeInput;
                if (!targetInput) return;
                var currentVal = targetInput.value || '';
                var insertText = (wsId !== 'workspace')
                    ? '[' + wsId + '/' + n.path + ']'
                    : '[' + n.path + ']';
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
        })(node, $nodeEl);

        $container.append($nodeEl);
    }

    // ---- 文件变更实时同步 ----
    function onFilerChange(chunk) {
        if (!chunk || !chunk.changes || chunk.changes.length === 0) return;

        if (!$treeEl.length || !$treeEl.children().length) {
            smartRefreshRoot();
            showFilerChangeIndicator();
            return;
        }

        var changes = chunk.changes;
        var affectedDirs = {};
        changes.forEach(function(path) {
            var lastSlash = path.lastIndexOf('/');
            var parentDir = lastSlash > 0 ? path.substring(0, lastSlash) : '';
            affectedDirs[parentDir] = true;
        });

        Object.keys(affectedDirs).forEach(function(dirPath) {
            refreshDirectory(dirPath);
        });

        showFilerChangeIndicator();
    }

    function refreshDirectory(dirPath) {
        if (!dirPath) {
            smartRefreshRoot();
            return;
        }

        if (!$treeEl.length) return;
        var selector = '.filer-node[data-path="' + CSS.escape(dirPath) + '"]';
        var $nodeEl = $treeEl.find(selector);
        if (!$nodeEl.length) return;

        var $childrenEl = $nodeEl.children('.filer-node-children');
        if (!$childrenEl.length) return;

        var isExpanded = $childrenEl.hasClass('open');
        if (!isExpanded) {
            $nodeEl.attr('data-dirty', '1');
            return;
        }

        var indent = parseInt($nodeEl.attr('data-indent') || '0', 10);
        var wsId = getNodeWorkspaceId($nodeEl);

        var url = '/web/chat/filer/tree?path=' + encodeURIComponent(dirPath) + '&depth=1';
        if (wsId !== 'workspace') {
            url += '&workspace=' + encodeURIComponent(wsId);
        }

        $.get(url, function(res) {
            var subData = (res && res.data) ? res.data : [];
            var expandedPaths = {};
            $childrenEl.find('.filer-node-children.open').each(function() {
                var $parent = $(this).parent();
                var dataPath = $parent.attr('data-path');
                if (dataPath) {
                    expandedPaths[dataPath] = true;
                }
            });
            renderTree(subData, $childrenEl, indent + 1);
            Object.keys(expandedPaths).forEach(function(expandedPath) {
                var expSelector = '.filer-node[data-path="' + CSS.escape(expandedPath) + '"]';
                var $expNodeEl = $childrenEl.find(expSelector);
                if ($expNodeEl.length) {
                    var $expChildrenEl = $expNodeEl.children('.filer-node-children');
                    var $expArrow = $expNodeEl.children('.filer-node-row').find('.filer-arrow');
                    var expIndent = parseInt($expNodeEl.attr('data-indent') || '0', 10);
                    if ($expChildrenEl.length) {
                        $expChildrenEl.addClass('open');
                        $expArrow.addClass('open');

                        var expUrl = '/web/chat/filer/tree?path=' + encodeURIComponent(expandedPath) + '&depth=1';
                        if (wsId !== 'workspace') {
                            expUrl += '&workspace=' + encodeURIComponent(wsId);
                        }
                        $.get(expUrl, function(res2) {
                            var subData2 = (res2 && res2.data) ? res2.data : [];
                            renderTree(subData2, $expChildrenEl, expIndent + 1);
                        });
                    }
                }
            });
        }).fail(function(jqXHR, textStatus, error) {
            console.error('[filer] refresh error', dirPath, error);
        });
    }

    function smartRefreshRoot() {
        var expandedPaths = collectExpandedPaths();

        // 重新加载工作区列表（可能有新增/删除的挂载）
        $.get('/web/chat/filer/workspaces', function(res) {
            var wsList = (res && res.data) ? res.data : [];
            if (!$treeEl.length) return;

            $treeEl.html('');
            wsList.forEach(function(ws) {
                // 之前展开的工作区保持展开
                if (expandedPaths[ws.name] && ws.type === 'workspace') {
                    // workspace 根节点不支持 expanded 标记，暂时忽略
                }
                appendWorkspaceNode(ws, $treeEl, 0);
            });

            // 对之前已展开的工作区，重新加载其文件树
            // 注意：expandedPaths 存的是 ws.name，需要匹配
            wsList.forEach(function(ws) {
                if (expandedPaths[ws.name]) {
                    var sel = '.filer-node[data-path="' + CSS.escape(ws.name) + '"]';
                    var $wn = $treeEl.find(sel);
                    if ($wn.length) {
                        var $wc = $wn.children('.filer-node-children');
                        var $wa = $wn.children('.filer-node-row').find('.filer-arrow');
                        if ($wc.length) {
                            $wc.addClass('open');
                            $wa.addClass('open');
                            var url = '/web/chat/filer/tree?depth=1';
                            if (ws.id !== 'workspace') {
                                url += '&workspace=' + encodeURIComponent(ws.id);
                            }
                            $.get(url, function(res2) {
                                var data2 = (res2 && res2.data) ? res2.data : [];
                                renderTree(data2, $wc, 1);
                            });
                        }
                    }
                }
            });
        }).fail(function() {
            // fallback
            $.get('/web/chat/filer/tree?depth=1', function(res) {
                var newData = (res && res.data) ? res.data : [];
                if (!$treeEl.length) return;
                $treeEl.html('');
                newData.forEach(function(node) {
                    if (expandedPaths[node.path] && node.type === 'directory') {
                        node.expanded = true;
                    }
                    appendNode(node, $treeEl, 0);
                });
                Object.keys(expandedPaths).forEach(function(dirPath) {
                    var selector = '.filer-node[data-path="' + CSS.escape(dirPath) + '"]';
                    var $nodeEl = $treeEl.find(selector);
                    if (!$nodeEl.length) return;
                    var $childrenEl = $nodeEl.children('.filer-node-children');
                    if (!$childrenEl.length) return;
                    var indent = parseInt($nodeEl.attr('data-indent') || '0', 10);
                    $.get('/web/chat/filer/tree?path=' + encodeURIComponent(dirPath) + '&depth=1', function(res2) {
                        var subData = (res2 && res2.data) ? res2.data : [];
                        renderTree(subData, $childrenEl, indent + 1);
                    });
                });
            });
        });
    }

    function showFilerChangeIndicator() {
        var $filesTab = $panel.length ? $panel.find('.filer-tab[data-tab="files"]') : $();
        if (!$filesTab.length) return;
        var $dot = $filesTab.find('.filer-change-dot');
        if (!$dot.length) {
            $dot = $('<span>').addClass('filer-change-dot');
            $filesTab.append($dot);
        }
        $dot.addClass('active');
        setTimeout(function() { $dot.removeClass('active'); }, 2000);
    }

    // ---- 暴露全局函数 ----
    window.loadTree = loadTree;
    window.onFilerChange = onFilerChange;

    // ---- 搜索（后端全量搜索） ----
    var $searchInput = $('#filerSearchInput');
    var $searchClear = $('#filerSearchClear');
    var searchResultsEl = null;

    function ensureSearchResultsContainer() {
        if (!searchResultsEl && $treeEl.length) {
            searchResultsEl = $('<div>').addClass('filer-search-results');
            $treeEl.after(searchResultsEl);
        }
    }

    function escapeHtml(text) {
        return $('<div>').text(text || '').html();
    }

    function showSearchResults(keyword) {
        if (!$treeEl.length || !keyword) return;
        var kw = keyword.trim().toLowerCase();
        if (!kw) { hideSearchResults(); return; }

        $treeEl.hide();
        ensureSearchResultsContainer();
        searchResultsEl.show();
        searchResultsEl.html('<div class="filer-search-loading">搜索中...</div>');

        $.get(filerUrl('/web/chat/filer/search', 'keyword=' + encodeURIComponent(kw)), function(res) {
            var data = (res && res.data) ? res.data : [];
            searchResultsEl.html('');

            if (data.length === 0) {
                searchResultsEl.html('<div class="filer-search-empty">未找到匹配文件</div>');
                return;
            }

            data.forEach(function(item) {
                var $row = $('<div>').addClass('filer-search-item')
                    .attr('data-path', item.path)
                    .attr('data-name', item.name)
                    .attr('data-type', item.type);

                var $icon = $('<span>').addClass('filer-search-item-icon');
                if (item.type === 'directory') {
                    $icon.html('<svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M2 4a1 1 0 011-1h3.5l1.5 1.5H13a1 1 0 011 1V12a1 1 0 01-1 1H3a1 1 0 01-1-1V4z" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/></svg>');
                } else {
                    $icon.html('<svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M4 1.5h4.75L12.5 5.75V13.5a1 1 0 01-1 1H4a1 1 0 01-1-1V2.5a1 1 0 011-1z" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/><path d="M8.75 1.5v4.25H12.5" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/></svg>');
                }
                $row.append($icon);

                var $pathSpan = $('<span>').addClass('filer-search-item-path');
                var pathLower = item.path.toLowerCase();
                var idx = pathLower.indexOf(kw);
                if (idx >= 0) {
                    $pathSpan.html(escapeHtml(item.path.substring(0, idx))
                        + '<mark>' + escapeHtml(item.path.substring(idx, idx + kw.length)) + '</mark>'
                        + escapeHtml(item.path.substring(idx + kw.length)));
                } else {
                    $pathSpan.text(item.path);
                }
                $row.append($pathSpan);

                if (item.type === 'file') {
                    (function(it, $r) {
                        var wsId = window.activeFilerWorkspace || 'workspace';
                        var viewPath = (wsId !== 'workspace' && wsId.indexOf('@') === 0) ? wsId + '/' + it.path : it.path;
                        var clickTimer = null;
                        $r.on('click', function(e) {
                            e.stopPropagation();
                            if (clickTimer) { clearTimeout(clickTimer); clickTimer = null; }
                            clickTimer = setTimeout(function() {
                                if (typeof window.openFileViewer === 'function') {
                                    window.openFileViewer(viewPath, it.name);
                                }
                            }, 250);
                        });
                        $r.on('dblclick', function(e) {
                            if (clickTimer) { clearTimeout(clickTimer); clickTimer = null; }
                        });
                    })(item, $row);
                }

                (function(it) {
                    $row.on('dblclick', function(e) {
                        e.stopPropagation();
                        e.preventDefault();
                        var wsId = window.activeFilerWorkspace || 'workspace';
                        var targetInput = (typeof inChatMode !== 'undefined' && inChatMode) ? chatInput : welcomeInput;
                        if (!targetInput) return;
                        var currentVal = targetInput.value || '';
                        var insertText = (wsId !== 'workspace')
                            ? '[' + wsId + '/' + it.path + ']'
                            : '[' + it.path + ']';
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

                searchResultsEl.append($row);
            });
        }).fail(function(jqXHR, textStatus, error) {
            console.error('[filer] search error', error);
            searchResultsEl.html('<div class="filer-search-empty">搜索失败</div>');
        });
    }

    function hideSearchResults() {
        if ($treeEl.length) $treeEl.css('display', '');
        if (searchResultsEl) searchResultsEl.hide();
    }

    if ($searchInput.length) {
        var searchTimer = null;
        $searchInput.on('input', function() {
            var val = $searchInput.val();
            if ($searchClear.length) {
                $searchClear.toggleClass('visible', val.length > 0);
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
    if ($searchClear.length) {
        $searchClear.on('click', function() {
            if ($searchInput.length) {
                $searchInput.val('');
                $searchInput.trigger('focus');
            }
            $searchClear.removeClass('visible');
            hideSearchResults();
        });
    }

    // ---- 启动 ----
    loadTree();
})();

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

    /** 绑定单击/双击事件，解决双击时两次 click 的冲突 */
    function bindClickDblClick($el, onClick, onDblClick) {
        var timer = null;
        $el.on('click', function(e) {
            e.stopPropagation();
            if (timer) { clearTimeout(timer); timer = null; }
            timer = setTimeout(function() {
                timer = null;
                onClick(e);
            }, 250);
        });
        $el.on('dblclick', function(e) {
            e.stopPropagation();
            e.preventDefault();
            if (timer) { clearTimeout(timer); timer = null; }
            onDblClick(e);
        });
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

    // ---- 展开状态：收集 / 排序 / 串行恢复 ----

    /** 路径按深度从浅到深排序，保证父目录先于子目录恢复 */
    function sortPathsByDepth(paths) {
        return (paths || []).slice().sort(function(a, b) {
            var da = a ? a.split('/').length : 0;
            var db = b ? b.split('/').length : 0;
            if (da !== db) return da - db;
            if (a === b) return 0;
            return a < b ? -1 : 1;
        });
    }

    /**
     * 收集当前展开状态，按工作区分组。
     * 返回: { [wsId]: { root: boolean, dirs: string[] } }
     * 无工作区节点的 fallback 树使用 wsId = '__flat__'
     */
    function collectExpandedState() {
        var state = {};
        if (!$treeEl.length) return state;

        var $wsNodes = $treeEl.children('.filer-node[data-workspace-id]');
        if ($wsNodes.length) {
            $wsNodes.each(function() {
                var $ws = $(this);
                var wsId = $ws.attr('data-workspace-id') || 'workspace';
                var entry = {
                    root: $ws.children('.filer-node-children').hasClass('open'),
                    dirs: []
                };
                var dirMap = {};
                $ws.find('.filer-node > .filer-node-children.open').each(function() {
                    var $parent = $(this).parent();
                    if ($parent[0] === $ws[0]) return;
                    var dataPath = $parent.attr('data-path');
                    if (dataPath && !dirMap[dataPath]) {
                        dirMap[dataPath] = true;
                        entry.dirs.push(dataPath);
                    }
                });
                state[wsId] = entry;
            });
            return state;
        }

        // fallback：扁平树（无工作区根节点）
        var dirs = [];
        var dirMap = {};
        $treeEl.find('.filer-node > .filer-node-children.open').each(function() {
            var dataPath = $(this).parent().attr('data-path');
            if (dataPath && !dirMap[dataPath]) {
                dirMap[dataPath] = true;
                dirs.push(dataPath);
            }
        });
        state['__flat__'] = { root: true, dirs: dirs };
        return state;
    }

    /** 在指定作用域内，按深度串行恢复展开目录 */
    function restoreExpandedPathsSequential($scope, paths, wsId, index, done) {
        if (!$scope || !$scope.length || !paths || index >= paths.length) {
            if (done) done();
            return;
        }

        var path = paths[index];
        var selector = '.filer-node[data-path="' + CSS.escape(path) + '"]';
        var $nodeEl = $scope.find(selector).first();
        if (!$nodeEl.length) {
            // 目录可能已被删除，跳过
            restoreExpandedPathsSequential($scope, paths, wsId, index + 1, done);
            return;
        }

        var $childrenEl = $nodeEl.children('.filer-node-children');
        var $arrow = $nodeEl.children('.filer-node-row').find('.filer-arrow');
        if (!$childrenEl.length) {
            restoreExpandedPathsSequential($scope, paths, wsId, index + 1, done);
            return;
        }

        var indent = parseInt($nodeEl.attr('data-indent') || '0', 10);
        $childrenEl.addClass('open');
        $arrow.addClass('open');
        $nodeEl.removeAttr('data-dirty');

        var url = '/web/chat/filer/tree?path=' + encodeURIComponent(path) + '&depth=1';
        if (wsId && wsId !== 'workspace' && wsId !== '__flat__') {
            url += '&workspace=' + encodeURIComponent(wsId);
        }

        $.get(url, function(res) {
            var subData = (res && res.data) ? res.data : [];
            renderTree(subData, $childrenEl, indent + 1);
            restoreExpandedPathsSequential($scope, paths, wsId, index + 1, done);
        }).fail(function() {
            console.error('[filer] restore expand error', path);
            restoreExpandedPathsSequential($scope, paths, wsId, index + 1, done);
        });
    }

    /** 加载工作区列表作为树的根节点；若树已存在则走智能刷新以保留展开状态 */
    function loadTree() {
        if ($treeEl.length && $treeEl.children().length) {
            smartRefreshRoot();
            return;
        }

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

        // 单击展开/折叠，双击插入路径到输入框
        var wsId = ws.id;
        bindClickDblClick($row,
            function() {
                var $aEl = $row.find('.filer-arrow');
                var isOpen = $childrenEl.hasClass('open');
                if (isOpen) {
                    $childrenEl.removeClass('open');
                    $aEl.removeClass('open');
                } else {
                    window.activeFilerWorkspace = wsId;
                    $childrenEl.addClass('open');
                    $aEl.addClass('open');
                    if (!$childrenEl.children().length) {
                        var url = '/web/chat/filer/tree?depth=1';
                        if (wsId !== 'workspace') {
                            url += '&workspace=' + encodeURIComponent(wsId);
                        }
                        $.get(url, function(res) {
                            var data = (res && res.data) ? res.data : [];
                            renderTree(data, $childrenEl, indent + 1);
                        }).fail(function() {
                            console.error('[filer] load workspace tree error', wsId);
                        });
                    }
                }
            },
            function() {
                var targetInput = (typeof inChatMode !== 'undefined' && inChatMode) ? chatInput : welcomeInput;
                if (!targetInput) return;
                var currentVal = targetInput.value || '';
                var insertText = '[' + wsId + ']';
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
        );

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

        // 目录：单击展开/折叠，双击插入路径到输入框
        if (node.type === 'directory') {
            bindClickDblClick($row,
                function() {
                    var $cEl = $nodeEl.children('.filer-node-children');
                    var $aEl = $row.find('.filer-arrow');
                    if (!$cEl.length) return;
                    var isOpen = $cEl.hasClass('open');
                    if (isOpen) {
                        $cEl.removeClass('open');
                        $aEl.removeClass('open');
                    } else {
                        $cEl.addClass('open');
                        $aEl.addClass('open');
                        var isDirty = $nodeEl.attr('data-dirty') === '1';
                        if (isDirty || !$cEl.children().length) {
                            $nodeEl.removeAttr('data-dirty');
                            var wsId = getNodeWorkspaceId($nodeEl);
                            var url = '/web/chat/filer/tree?path=' + encodeURIComponent(node.path) + '&depth=1';
                            if (wsId !== 'workspace') {
                                url += '&workspace=' + encodeURIComponent(wsId);
                            }
                            $.get(url, function(res) {
                                var subData = (res && res.data) ? res.data : [];
                                renderTree(subData, $cEl, indent + 1);
                            });
                        }
                    }
                },
                function() {
                    var wsId = getNodeWorkspaceId($nodeEl);
                    var targetInput = (typeof inChatMode !== 'undefined' && inChatMode) ? chatInput : welcomeInput;
                    if (!targetInput) return;
                    var currentVal = targetInput.value || '';
                    var insertText = (wsId !== 'workspace')
                        ? '[' + wsId + '/' + node.path + ']'
                        : '[' + node.path + ']';
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
            );
        } else {
            // 文件：单击打开查看器，双击插入路径到输入框
            bindClickDblClick($row,
                function() {
                    var wsId = getNodeWorkspaceId($nodeEl);
                    var viewPath = (wsId !== 'workspace' && wsId.indexOf('@') === 0) ? wsId + '/' + node.path : node.path;
                    if (typeof window.openFileViewer === 'function') {
                        window.openFileViewer(viewPath, node.name);
                    }
                },
                function() {
                    var wsId = getNodeWorkspaceId($nodeEl);
                    var targetInput = (typeof inChatMode !== 'undefined' && inChatMode) ? chatInput : welcomeInput;
                    if (!targetInput) return;
                    var currentVal = targetInput.value || '';
                    var insertText = (wsId !== 'workspace')
                        ? '[' + wsId + '/' + node.path + ']'
                        : '[' + node.path + ']';
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
            );
        }

        $container.append($nodeEl);
    }

    // ---- 文件变更实时同步（增量增删，不整层重绘） ----
    function onFilerChange(chunk) {
        if (!chunk || !chunk.changes || chunk.changes.length === 0) return;

        if (!$treeEl.length || !$treeEl.children().length) {
            smartRefreshRoot();
            showFilerChangeIndicator();
            return;
        }

        var changes = chunk.changes.slice();
        // 先删后增，避免同批次 rename 场景下路径冲突
        changes.sort(function(a, b) {
            var ka = kindPriority(a && a.kind);
            var kb = kindPriority(b && b.kind);
            if (ka !== kb) return ka - kb;
            var pa = (a && a.path) || '';
            var pb = (b && b.path) || '';
            // 删除时先删更深路径，创建时先建更浅路径
            var da = pa ? pa.split('/').length : 0;
            var db = pb ? pb.split('/').length : 0;
            if (ka === 0) return db - da;
            if (ka === 2) return da - db;
            return pa < pb ? -1 : (pa > pb ? 1 : 0);
        });

        changes.forEach(function(change) {
            applyFilerChange(change);
        });

        showFilerChangeIndicator();
    }

    function kindPriority(kind) {
        if (kind === 'delete') return 0;
        if (kind === 'modify') return 1;
        if (kind === 'create') return 2;
        // 兼容旧事件（无 kind）：按修改处理
        return 1;
    }

    function applyFilerChange(change) {
        if (!change) return;
        var wsId = change.wsId || 'workspace';
        var relPath = change.path || '';
        if (!relPath) return;

        var kind = change.kind || 'modify';
        var nodeType = change.type || null;

        if (kind === 'delete') {
            removeTreeNode(wsId, relPath);
            return;
        }
        if (kind === 'create') {
            ensureTreeNode(wsId, relPath, nodeType || 'file');
            return;
        }
        // modify：文件内容变化不影响树结构；若节点尚未出现则补建
        if (nodeType === 'directory') {
            // 目录修改通常来自子项变化，树结构本身不需要处理
            return;
        }
        // 兼容旧后端（无 kind）：节点不存在时尝试补建，存在则不动
        ensureTreeNode(wsId, relPath, nodeType || 'file');
    }

    function getWorkspaceRoot(wsId) {
        if (!$treeEl.length) return $();
        wsId = wsId || 'workspace';
        var $ws = $treeEl.children('.filer-node[data-workspace-id="' + CSS.escape(wsId) + '"]').first();
        if ($ws.length) return $ws;
        // fallback 扁平树（无工作区根）
        return $treeEl;
    }

    function getParentDir(relPath) {
        if (!relPath) return '';
        var idx = relPath.lastIndexOf('/');
        return idx > 0 ? relPath.substring(0, idx) : '';
    }

    function getBaseName(relPath) {
        if (!relPath) return '';
        var idx = relPath.lastIndexOf('/');
        return idx >= 0 ? relPath.substring(idx + 1) : relPath;
    }

    function findNodeInWorkspace($wsRoot, relPath) {
        if (!$wsRoot || !$wsRoot.length || !relPath) return $();
        // 在作用域内查找（工作区子树或扁平树根），保留深层节点
        return $wsRoot.find('.filer-node[data-path="' + CSS.escape(relPath) + '"]').first();
    }

    function getChildrenContainer($wsRoot, parentDir) {
        if (!$wsRoot || !$wsRoot.length) return $();
        if (!parentDir) {
            // 工作区根的一级列表，或扁平树根
            if ($wsRoot.is($treeEl)) return $wsRoot;
            return $wsRoot.children('.filer-node-children');
        }
        var $parentNode = findNodeInWorkspace($wsRoot, parentDir);
        if (!$parentNode.length) return $();
        return $parentNode.children('.filer-node-children');
    }

    function removeTreeNode(wsId, relPath) {
        var $wsRoot = getWorkspaceRoot(wsId);
        if (!$wsRoot.length) return;

        var $node = findNodeInWorkspace($wsRoot, relPath);
        if ($node.length) {
            $node.remove();
            return;
        }

        // 节点未渲染：若父目录已展开则无需处理；未展开则标脏，下次展开重拉
        var parentDir = getParentDir(relPath);
        if (!parentDir) return;
        var $parentNode = findNodeInWorkspace($wsRoot, parentDir);
        if ($parentNode.length) {
            var $children = $parentNode.children('.filer-node-children');
            if ($children.length && !$children.hasClass('open')) {
                $parentNode.attr('data-dirty', '1');
            }
        }
    }

    function ensureTreeNode(wsId, relPath, nodeType) {
        var $wsRoot = getWorkspaceRoot(wsId);
        if (!$wsRoot.length) return;

        // 工作区根节点本身不在这里创建
        if ($wsRoot.is($treeEl) === false && !$wsRoot.children('.filer-node-children').hasClass('open')) {
            // 工作区未展开：展开时会重新拉一级列表
            return;
        }

        // 已存在则跳过（保留展开状态）
        var $existing = findNodeInWorkspace($wsRoot, relPath);
        if ($existing.length) return;

        var parentDir = getParentDir(relPath);
        var $childrenEl = getChildrenContainer($wsRoot, parentDir);
        if (!$childrenEl.length) return;

        // 父目录未展开：只标脏，下次展开再拉真实列表
        if (parentDir) {
            var $parentNode = findNodeInWorkspace($wsRoot, parentDir);
            if ($parentNode.length) {
                var $pc = $parentNode.children('.filer-node-children');
                if ($pc.length && !$pc.hasClass('open')) {
                    $parentNode.attr('data-dirty', '1');
                    return;
                }
            } else {
                // 父节点不在 DOM：无法插入
                return;
            }
        } else if (!$childrenEl.hasClass('open') && !$wsRoot.is($treeEl)) {
            return;
        }

        // 父容器已展开并有内容，或是根级列表：直接插入
        // 若根级已展开但还没加载 children，触发一次轻量加载而不是整树刷新
        if (!parentDir && !$wsRoot.is($treeEl) && $childrenEl.hasClass('open') && !$childrenEl.children().length) {
            loadChildrenInto($childrenEl, '', wsId, 1);
            return;
        }

        var name = getBaseName(relPath);
        var indent;
        if (!parentDir) {
            indent = $wsRoot.is($treeEl) ? 0 : 1;
        } else {
            var $parentNode2 = findNodeInWorkspace($wsRoot, parentDir);
            indent = parseInt($parentNode2.attr('data-indent') || '0', 10) + 1;
        }

        var node = {
            name: name,
            path: relPath,
            type: nodeType === 'directory' ? 'directory' : 'file'
        };
        insertNodeSorted($childrenEl, node, indent);
    }

    function loadChildrenInto($childrenEl, dirPath, wsId, indent) {
        if (!$childrenEl || !$childrenEl.length) return;
        var url = '/web/chat/filer/tree?depth=1';
        if (dirPath) {
            url = '/web/chat/filer/tree?path=' + encodeURIComponent(dirPath) + '&depth=1';
        }
        if (wsId && wsId !== 'workspace' && wsId !== '__flat__') {
            url += (url.indexOf('?') >= 0 ? '&' : '?') + 'workspace=' + encodeURIComponent(wsId);
        }
        $.get(url, function(res) {
            var data = (res && res.data) ? res.data : [];
            // 仅当容器仍为空时填充，避免覆盖用户后续展开
            if (!$childrenEl.children().length) {
                renderTree(data, $childrenEl, indent);
            }
        }).fail(function(jqXHR, textStatus, error) {
            console.error('[filer] load children error', dirPath, error);
        });
    }

    /** 按目录优先、名称字典序插入节点，不触碰其他节点 */
    function insertNodeSorted($container, node, indent) {
        if (!$container || !$container.length || !node) return;

        // 再次防重
        var exists = $container.children('.filer-node[data-path="' + CSS.escape(node.path) + '"]').length > 0;
        if (exists) return;

        var $children = $container.children('.filer-node');
        var insertBefore = null;
        $children.each(function() {
            if (insertBefore) return;
            var $n = $(this);
            var t = $n.attr('data-type') || 'file';
            var p = $n.attr('data-path') || '';
            var name = getBaseName(p);
            var nodeIsDir = node.type === 'directory';
            var otherIsDir = t === 'directory';
            if (nodeIsDir && !otherIsDir) {
                insertBefore = $n;
                return;
            }
            if (!nodeIsDir && otherIsDir) {
                return;
            }
            if (name.localeCompare(node.name, undefined, { sensitivity: 'base' }) > 0) {
                insertBefore = $n;
            }
        });

        // appendNode 总是 append；这里先 append 再移动，或临时容器
        var $tmp = $('<div>');
        appendNode(node, $tmp, indent);
        var $newNode = $tmp.children().first();
        if (!$newNode.length) return;
        if (insertBefore && insertBefore.length) {
            $newNode.insertBefore(insertBefore);
        } else {
            $container.append($newNode);
        }
    }

    /** 收集某个节点子树内已展开的目录路径（不含自身） */
    function collectExpandedDirsUnder($scope) {
        var expandedDirs = [];
        var dirMap = {};
        if (!$scope || !$scope.length) return expandedDirs;
        $scope.find('.filer-node > .filer-node-children.open').each(function() {
            var dataPath = $(this).parent().attr('data-path');
            if (dataPath && !dirMap[dataPath]) {
                dirMap[dataPath] = true;
                expandedDirs.push(dataPath);
            }
        });
        return sortPathsByDepth(expandedDirs);
    }

    function smartRefreshRoot() {
        var expandedState = collectExpandedState();

        // 重新加载工作区列表（可能有新增/删除的挂载）
        $.get('/web/chat/filer/workspaces', function(res) {
            var wsList = (res && res.data) ? res.data : [];
            if (!$treeEl.length) return;

            $treeEl.html('');
            wsList.forEach(function(ws) {
                appendWorkspaceNode(ws, $treeEl, 0);
            });

            // 串行恢复每个已展开工作区及其深层目录，避免并发重绘互相覆盖
            var wsIndex = 0;
            function restoreNextWorkspace() {
                if (wsIndex >= wsList.length) return;
                var ws = wsList[wsIndex++];
                var entry = expandedState[ws.id];
                if (!entry || !entry.root) {
                    restoreNextWorkspace();
                    return;
                }

                var $wn = $treeEl.find('.filer-node[data-workspace-id="' + CSS.escape(ws.id) + '"]').first();
                if (!$wn.length) {
                    restoreNextWorkspace();
                    return;
                }

                var $wc = $wn.children('.filer-node-children');
                var $wa = $wn.children('.filer-node-row').find('.filer-arrow');
                if (!$wc.length) {
                    restoreNextWorkspace();
                    return;
                }

                $wc.addClass('open');
                $wa.addClass('open');

                var url = '/web/chat/filer/tree?depth=1';
                if (ws.id !== 'workspace') {
                    url += '&workspace=' + encodeURIComponent(ws.id);
                }

                $.get(url, function(res2) {
                    var data2 = (res2 && res2.data) ? res2.data : [];
                    renderTree(data2, $wc, 1);
                    var dirs = sortPathsByDepth(entry.dirs || []);
                    restoreExpandedPathsSequential($wn, dirs, ws.id, 0, restoreNextWorkspace);
                }).fail(function() {
                    console.error('[filer] smart refresh workspace error', ws.id);
                    restoreNextWorkspace();
                });
            }
            restoreNextWorkspace();
        }).fail(function() {
            // fallback：扁平树恢复
            var flatEntry = expandedState['__flat__'] || { dirs: [] };
            var allDirs = sortPathsByDepth(flatEntry.dirs || []);
            // 兼容：若此前是工作区模式，把各 ws 的 dirs 合并
            Object.keys(expandedState).forEach(function(key) {
                if (key === '__flat__') return;
                var entry = expandedState[key];
                if (entry && entry.dirs) {
                    entry.dirs.forEach(function(p) {
                        if (allDirs.indexOf(p) < 0) allDirs.push(p);
                    });
                }
            });
            allDirs = sortPathsByDepth(allDirs);

            $.get('/web/chat/filer/tree?depth=1', function(res) {
                var newData = (res && res.data) ? res.data : [];
                if (!$treeEl.length) return;
                $treeEl.html('');

                // 根级目录若在展开列表中，先标 expanded 以便 appendNode 打开容器
                var rootExpanded = {};
                allDirs.forEach(function(p) {
                    if (p && p.indexOf('/') < 0) rootExpanded[p] = true;
                });
                newData.forEach(function(node) {
                    if (node.type === 'directory' && rootExpanded[node.path]) {
                        node.expanded = true;
                    }
                    appendNode(node, $treeEl, 0);
                });

                // 根级 expanded 只打开了容器，子节点需按 depth=1 拉数据；
                // 统一走串行恢复（含根级与深层）
                restoreExpandedPathsSequential($treeEl, allDirs, 'workspace', 0);
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

    // ---- 添加文件类型挂载点击事件 ----
    $(document).on('click', '#filerMountHint', function() {
        if (typeof window.openSettingsTab === 'function') {
            window.openSettingsTab('mounts');
        } else {
            $('#settingsBtn').click();
        }
    });

    // ---- 启动：文件树非输入关键路径，延后一点，给 sessions/ws 让带宽 ----
    if (window.requestIdleCallback) {
        requestIdleCallback(function() { loadTree(); }, { timeout: 2000 });
    } else {
        setTimeout(function() { loadTree(); }, 300);
    }
})();

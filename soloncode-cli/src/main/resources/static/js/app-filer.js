/* ===== app-filer.js ===== */
/* 工作区文件树面板（右侧） */

(function() {
    var $panel = $('#workspacePanel');
    var $toggleBtn = $('#workspaceToggleBtn');
    var $treeEl = $('#fileTree');
    var $resizeHandle = $('#workspaceResizeHandle');

    var FILER_MIN_WIDTH = 180;
    var FILER_MAX_WIDTH = 600;
    var FILER_DEFAULT_WIDTH = 280;

    /* jQuery 默认不设超时，全仓也没有 $.ajaxSetup：连接已建立但服务端不回的请求会永远悬置，
     * 回调链末端的收尾逻辑（转圈停止、重入标记释放）随之永不执行。文件面板的请求统一走本封装。 */
    var FILER_AJAX_TIMEOUT_MS = 20000;

    /** 带超时的 GET（语义等同 jQuery 的 get(url, done)，仍可链 .fail()） */
    function filerGet(url, done) {
        return $.ajax({ url: url, timeout: FILER_AJAX_TIMEOUT_MS, success: done });
    }

    /** 当前活动工作区（用于搜索/文件查看器） */
    window.activeFilerWorkspace = 'workspace';

    /** 查找 DOM 节点所属的工作区 ID */
    function getNodeWorkspaceId($el) {
        var $wsRoot = $el.closest('[data-workspace-id]');
        return $wsRoot.length ? ($wsRoot.attr('data-workspace-id') || 'workspace') : 'workspace';
    }

    /**
     * 工作区路径缓存（用于右键菜单：复制真实路径 / 作为工作区打开）
     * launchPath：当前工作区根的真实绝对路径；mountRealPaths：别名 -> 真实路径
     */
    var wsPathCache = { loaded: false, launchPath: '', mountRealPaths: {} };

    function loadWsPathCache(callback) {
        // 多工作区：URL 带 workspaceId 时缓存需按工作区失效（切换工作区后重取）
        var curWsId = (window.wsId && window.wsId()) || '';
        if (wsPathCache.loaded && wsPathCache.forWsId === curWsId) { if (callback) callback(); return; }
        filerGet('/web/workspace/list', function(res) {
            if (res && res.data) {
                // 当前工作区的根路径：优先按 URL 的 workspaceId 在历史工作区列表中匹配；
                // 匹配不到（默认/启动工作区）才回落 launch.path（启动目录）
                var wsPath = '';
                if (curWsId && res.data.workspaces) {
                    (res.data.workspaces || []).forEach(function(w) {
                        if (w && w.id === curWsId && w.path) wsPath = w.path;
                    });
                }
                if (!wsPath && res.data.launch && res.data.launch.path) wsPath = res.data.launch.path;
                wsPathCache.launchPath = wsPath;
                wsPathCache.mountRealPaths = {};
                (res.data.mounts || []).forEach(function(m) {
                    if (m.alias && m.path) wsPathCache.mountRealPaths[m.alias] = m.path;
                });
            }
            wsPathCache.loaded = true;
            wsPathCache.forWsId = curWsId;
            if (callback) callback();
        }).fail(function() { wsPathCache.loaded = true; wsPathCache.forWsId = curWsId; if (callback) callback(); });
    }

    /** 计算节点的真实绝对路径（无法解析时返回 null） */
    function resolveRealPath(wsId, relPath) {
        if (wsId === 'workspace') {
            if (!wsPathCache.launchPath) return null;
            return relPath ? (wsPathCache.launchPath.replace(/[\\/]+$/, '') + '/' + relPath) : wsPathCache.launchPath;
        }
        var base = wsPathCache.mountRealPaths[wsId];
        if (!base) return null;
        return relPath ? (base.replace(/[\\/]+$/, '') + '/' + relPath) : base;
    }

    /** 用于 /web/workspace/open 的路径（当前工作区用绝对路径，挂载用 @alias/rel） */
    function resolveOpenPath(wsId, relPath) {
        if (wsId === 'workspace') return resolveRealPath(wsId, relPath);
        return relPath ? (wsId + '/' + relPath) : wsId;
    }

    /** 把文本插入当前聊天输入框（与树节点双击行为一致） */
    function insertToChat(text) {
        var targetInput = (typeof inChatMode !== 'undefined' && inChatMode) ? chatInput : newChatInput;
        if (!targetInput) return;
        var currentVal = targetInput.value || '';
        var cursorPos = targetInput.selectionStart || currentVal.length;
        var before = currentVal.substring(0, cursorPos);
        var after = currentVal.substring(cursorPos);
        var prefix = (before.length > 0 && !before.endsWith(' ') && !before.endsWith('\n')) ? ' ' : '';
        targetInput.value = before + prefix + text + after;
        targetInput.focus();
        var newPos = (before + prefix + text).length;
        try { targetInput.setSelectionRange(newPos, newPos); } catch (e) {}
    }

    /** 关闭文件树右键菜单 */
    function closeFilerContextMenu() {
        $('.filer-context-menu').remove();
        $(document).off('mousedown.filerCtx keydown.filerCtx');
    }

    /** 在事件坐标处弹出文件树右键菜单 */
    function showFilerContextMenu(e, info) {
        closeFilerContextMenu();
        var $menu = $('<div>').addClass('filer-context-menu');

        function addItem(labelKey, fallback, handler) {
            var $item = $('<div>').addClass('filer-context-menu-item')
                .text(I18n.t(labelKey, fallback))
            $item.on('click', function() { closeFilerContextMenu(); handler(); });
            $menu.append($item);
        }

        // 1. 复制路径
        addItem('filer.ctxCopyPath', '复制路径', function() {
            var text = info.realPath || info.displayPath;
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text).catch(function() { fallbackCopy(text); });
            } else { fallbackCopy(text); }
            function fallbackCopy(t) {
                var $tmp = $('<textarea>').css({position:'fixed', opacity:0}).val(t).appendTo('body');
                $tmp[0].select(); try { document.execCommand('copy'); } catch (err) {}
                $tmp.remove();
            }
        });

        // 2. 加入对话（与双击行为一致，不替代双击）
        addItem('msg.addToChat', '加入对话', function() {
            insertToChat(info.quoteText);
        });

        // 3. 作为工作区打开（仅目录且路径可解析时显示）
        if (info.isDirectory && info.openPath) {
            addItem('filer.ctxOpenAsWorkspace', '作为工作区打开', function() {
                fetch('/web/workspace/open', { method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: 'path=' + encodeURIComponent(info.openPath) })
                    .then(function(r) { return r.json(); })
                    .then(function(res) {
                        if (res.code === 200 && res.data && res.data.id) {
                            window.open('/?workspaceId=' + encodeURIComponent(res.data.id), '_blank');
                        } else {
                            showToast(res.description || res.message || I18n.t('filer.ctxOpenFailed', '打开失败'), 'error');
                        }
                    })
                    .catch(function() { showToast(I18n.t('filer.ctxOpenFailed', '打开失败'), 'error'); });
            });
        }

        $(document.body).append($menu);
        var menuW = $menu.outerWidth(), menuH = $menu.outerHeight();
        var x = Math.min(e.clientX, window.innerWidth - menuW - 8);
        var y = Math.min(e.clientY, window.innerHeight - menuH - 8);
        $menu.css({ left: Math.max(8, x) + 'px', top: Math.max(8, y) + 'px', display: 'block' });

        // 点击其他区域 / ESC 关闭
        setTimeout(function() {
            $(document).on('mousedown.filerCtx', function(ev) {
                if (!$(ev.target).closest('.filer-context-menu').length) closeFilerContextMenu();
            });
            $(document).on('keydown.filerCtx', function(ev) {
                if (ev.key === 'Escape') closeFilerContextMenu();
            });
        }, 0);
    }

    /** 文件树右键菜单入口（事件委托） */
    function initFilerContextMenu() {
        $treeEl.on('contextmenu', function(e) {
            var $row = $(e.target).closest('.file-node-row');
            if (!$row.length) return;
            e.preventDefault();
            e.stopPropagation();

            var $nodeEl = $row.closest('.file-node');
            if (!$nodeEl.length) return;
            var isWsRoot = $nodeEl.attr('data-workspace-id') !== undefined;
            var wsId = getNodeWorkspaceId($nodeEl);
            var relPath = isWsRoot ? '' : ($nodeEl.attr('data-path') || '');
            var nodeType = $nodeEl.attr('data-type') || (isWsRoot ? 'directory' : '');
            var isDirectory = nodeType === 'directory';

            loadWsPathCache(function() {
                var info = {
                    isDirectory: isDirectory,
                    realPath: resolveRealPath(wsId, relPath),
                    displayPath: isWsRoot ? (wsId === 'workspace' ? (wsPathCache.launchPath || wsId) : wsId) : (wsId !== 'workspace' ? wsId + '/' + relPath : relPath),
                    openPath: isDirectory ? resolveOpenPath(wsId, relPath) : null,
                    quoteText: isWsRoot
                        ? (wsId !== 'workspace' ? '[' + wsId + ']' : '[./]')
                        : (wsId !== 'workspace' ? '[' + wsId + '/' + relPath + ']' : '[./' + relPath + ']')
                };
                showFilerContextMenu(e, info);
            });
        });
    }

    /** 构建带 workspace 参数的 URL */
    function filerUrl(basePath, params) {
        var query = params || '';
        if (window.activeFilerWorkspace !== 'workspace') {
            query += (query ? '&' : '') + 'mount=' + encodeURIComponent(window.activeFilerWorkspace);
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
        localStorage.setItem('files-width', finalWidth);
            }
        });
    }

    // ---- 恢复持久化宽度 ----
    function restoreWidth() {
        if (!$panel.length) return;
        var savedWidth = localStorage.getItem('files-width') || localStorage.getItem('filer-width');
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
            $mainHeader.addClass('workspace-collapsed');
        } else {
            $mainHeader.removeClass('workspace-collapsed');
        }
    }

    /** 设置折叠按钮箭头 SVG 方向，保留内部角标等子节点 */
    function setToggleBtnArrow(collapsed) {
        if (!$toggleBtn.length) return;
        var svgPath = $toggleBtn.find('path');
        if (svgPath.length) {
            svgPath.attr('d', collapsed ? 'm15 18-6-6 6-6' : 'm9 18 6-6-6-6');
        }
        $toggleBtn.attr('title', collapsed ? I18n.t('filer.expandFiles') : I18n.t('filer.collapseFiles'));
    }
    
    if ($toggleBtn.length) {
        $toggleBtn.on('click', function() {
            $panel.toggleClass('collapsed');
            var collapsed = $panel.hasClass('collapsed');
            $toggleBtn.toggleClass('collapsed', collapsed);
            setToggleBtnArrow(collapsed);
            localStorage.setItem('files-collapsed', collapsed ? '1' : '0');
            syncHeaderPadding(collapsed);
            syncToggleBtnPosition();
            // 折叠后重新同步排队角标（CSS 仅在 collapsed 时显示）
            if (typeof window.renderQueueDock === 'function') {
                window.renderQueueDock();
            }
        });
    }

    // 恢复持久化状态
    restoreWidth();
    var shouldExpand = (localStorage.getItem('files-collapsed') || localStorage.getItem('filer-collapsed')) === '0';
    if (shouldExpand) {
        $panel.removeClass('collapsed');
        $toggleBtn.removeClass('collapsed');
        setToggleBtnArrow(false);
        syncHeaderPadding(false);
    } else {
        setToggleBtnArrow(true);
        syncHeaderPadding(true);
    }
    syncToggleBtnPosition();
    initResize();
    initFilerContextMenu();

    // ---- 展开状态：收集 / 排序 / 串行恢复 ----

    /** 路径按深度从浅到深排序，保证父目录先于子目录恢复 */
    /* collectExpandedState / sortPathsByDepth 已删：整树刷新改为原地 diff，
     * 不再需要「先拍快照再恢复展开态」那套机制。 */

    /** 用服务端数据原地更新某个已展开目录的子列表（diff 后若目录仍在且已展开，递归处理其子层） */
    function diffDir(wsId, dirPath, done) {
        var $wsRoot = getWorkspaceRoot(wsId);
        var $children, indent;
        if (!dirPath) {
            if ($wsRoot.is($treeEl)) { if (done) done(); return; } // 扁平树无根节点，由调用方直接 diff $treeEl
            $children = $wsRoot.children('.file-node-children');
            indent = 1;
        } else {
            var $node = findNodeInWorkspace($wsRoot, dirPath);
            if (!$node.length) { if (done) done(); return; } // 目录已被删除，上层的 diff 会移除其节点
            $children = $node.children('.file-node-children');
            indent = parseInt($node.attr('data-indent') || '0', 10) + 1;
        }
        if (!$children.length || !$children.hasClass('open')) { if (done) done(); return; }

        var url = '/web/chat/filer/tree?path=' + encodeURIComponent(dirPath) + '&depth=1';
        if (wsId && wsId !== 'workspace' && wsId !== '__flat__') {
            url += '&mount=' + encodeURIComponent(wsId);
        }

        filerGet(url, function(res) {
            var data = (res && res.data) ? res.data : [];
            diffChildren($children, data, indent);
            // 本层 diff 后，对「仍在 DOM 且已展开」的子目录递归 diff；其余交由展开时加载
            var openSubs = [];
            $children.children('.file-node').each(function() {
                var $c = $(this);
                if ($c.attr('data-type') !== 'directory') return;
                var $cc = $c.children('.file-node-children');
                if ($cc.length && $cc.hasClass('open')) openSubs.push($c.attr('data-path'));
            });
            var idx = 0;
            (function next() {
                if (idx >= openSubs.length) { if (done) done(); return; }
                diffDir(wsId, openSubs[idx++], next);
            })();
        }).fail(function() {
            console.error('[filer] diff dir error', wsId, dirPath);
            if (done) done();
        });
    }

    /**
     * 加载工作区列表作为树的根节点；若树已存在则走智能刷新以保留展开状态
     *
     * @param done 可选，整棵树（含展开态恢复）加载完毕后回调，用于刷新按钮结束转圈
     */
    function loadTree(done) {
        if ($treeEl.length && $treeEl.children().length) {
            smartRefreshRoot(done);
            return;
        }

        filerGet('/web/chat/filer/workspaces', function(res) {
            var wsList = (res && res.data) ? res.data : [];
            function doRender() {
                if ($treeEl.length) {
                    $treeEl.html('');
                    wsList.forEach(function(ws) {
                        appendWorkspaceNode(ws, $treeEl, 0);
                    });
                }
            }
            doRender();
            // 语言包尚未加载时（fetch 先于 zh-CN.json 返回），包就绪后重渲染工作区名
            if (window.I18n && window.I18n.messages && !window.I18n.messages[window.I18n.locale || 'zh-CN']) {
                document.addEventListener('i18n:loaded', function _rl() {
                    document.removeEventListener('i18n:loaded', _rl);
                    doRender();
                });
            }
            if (done) done();
        }).fail(function(jqXHR, textStatus, error) {
            console.error('[filer] workspaces load error', error);
            // fallback：直接用当前工作区文件树
            filerGet('/web/chat/filer/tree?depth=1', function(res) {
                var data = (res && res.data) ? res.data : [];
                if ($treeEl.length) renderTree(data, $treeEl, 0);
                if (done) done();
            }).fail(function() {
                if (done) done();
            });
        });
    }

    /** 渲染工作区根节点（树的顶级节点） */
    function appendWorkspaceNode(ws, $container, indent) {
        var wsId = ws.id;
        var isReadonly = ws.readonly === true;

        var $nodeEl = $('<div>').addClass('file-node')
            .attr('data-indent', indent)
            .attr('data-workspace-id', wsId)
            .attr('data-path', ws.name)
            .attr('data-type', 'directory');

        var wsDisplayName = ws.name === '__current_workspace__' ? I18n.t('gitdiff.currentWorkspace') : ws.name;
        var $row = applyIndent($('<div>').addClass('file-node-row'), indent)
            .addClass(isReadonly ? 'file-workspace-readonly' : '')
            .attr('title', wsDisplayName + (isReadonly ? ' (' + I18n.t('filer.readonly') + ')' : ''));

        // 箭头
        var $arrow = $('<span>').addClass('file-arrow')
            .html('<svg width="12" height="12" viewBox="0 0 16 16"><path d="M6 3l5 5-5 5" stroke="currentColor" stroke-width="1.8" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>');
        $row.append($arrow);

        // 图标（文件夹样式）
        var $icon = $('<span>').addClass('file-node-icon')
            .html('<svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M2 4a1 1 0 011-1h3.5l1.5 1.5H13a1 1 0 011 1V12a1 1 0 01-1 1H3a1 1 0 01-1-1V4z" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/></svg>');
        $row.append($icon);

        // 名称
        var $name = $('<span>').addClass('file-node-name')
            .text(wsDisplayName);
        $row.append($name);

        // 只读徽标
        if (isReadonly) {
            $row.append('<span class="file-ws-badge">' + I18n.t('filer.readonly') + '</span>');
        }

        $nodeEl.append($row);

        // 子容器（工作区展开后，文件树渲染在此）
        var $childrenEl = $('<div>').addClass('file-node-children');
        $nodeEl.append($childrenEl);

        // 单击展开/折叠，双击加入对话
        var wsId = ws.id;
        bindClickDblClick($row,
            function() {
                var $aEl = $row.find('.file-arrow');
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
                            url += '&mount=' + encodeURIComponent(wsId);
                        }
                        filerGet(url, function(res) {
                            var data = (res && res.data) ? res.data : [];
                            renderTree(data, $childrenEl, indent + 1);
                            autoExpandSingleDir($childrenEl, data, indent + 1, wsId, 0);
                        }).fail(function() {
                            console.error('[filer] load workspace tree error', wsId);
                        });
                    }
                }
            },
            function() {
                var targetInput = (typeof inChatMode !== 'undefined' && inChatMode) ? chatInput : newChatInput;
                if (!targetInput) return;
                var currentVal = targetInput.value || '';
                var insertText = (wsId !== 'workspace') ? '[' + wsId + ']' : '[./]';
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

    // ---- 自动穿透展开：目录下只有一个子节点且为目录时，继续展开 ----
    var AUTO_EXPAND_MAX_DEPTH = 20;

    /**
     * 渲染完某层后调用：若该层只有一个节点且是目录，则自动展开它，
     * 并对新加载出的层继续判断，直到不再是「只有一个目录节点」。
     * @param $childrenEl 该层的容器
     * @param nodes       该层的数据
     * @param indent      该层节点的缩进层级
     * @param wsId        所属工作区 ID
     * @param depth       已自动穿透的层数（防御死循环）
     */
    function autoExpandSingleDir($childrenEl, nodes, indent, wsId, depth) {
        if (!$childrenEl || !$childrenEl.length) return;
        if ((depth || 0) >= AUTO_EXPAND_MAX_DEPTH) return;
        if (!nodes || nodes.length !== 1) return;

        var only = nodes[0];
        if (!only || only.type !== 'directory') return;

        var $nodeEl = $childrenEl.children('.file-node[data-path="' + CSS.escape(only.path) + '"]').first();
        if (!$nodeEl.length) return;

        var $cEl = $nodeEl.children('.file-node-children');
        if (!$cEl.length || $cEl.hasClass('open')) return;

        $cEl.addClass('open');
        $nodeEl.children('.file-node-row').find('.file-arrow').addClass('open');
        $nodeEl.removeAttr('data-dirty');

        var url = '/web/chat/filer/tree?path=' + encodeURIComponent(only.path) + '&depth=1';
        if (wsId && wsId !== 'workspace' && wsId !== '__flat__') {
            url += '&mount=' + encodeURIComponent(wsId);
        }

        filerGet(url, function(res) {
            var subData = (res && res.data) ? res.data : [];
            renderTree(subData, $cEl, indent + 1);
            autoExpandSingleDir($cEl, subData, indent + 1, wsId, (depth || 0) + 1);
        }).fail(function() {
            console.error('[filer] auto expand error', only.path);
        });
    }

    // 缩进基准与每层步进（px）。行宽保持满宽，缩进用行自身的 padding-left 表达，
    // 故层级不受 CSS 枚举规则限制，可无限加深。
    var INDENT_BASE = 16;
    var INDENT_STEP = 16;

    /** 按层级为节点行设置缩进 */
    function applyIndent($row, indent) {
        var lv = parseInt(indent, 10);
        if (isNaN(lv) || lv < 0) lv = 0;
        $row.css('padding-left', (INDENT_BASE + lv * INDENT_STEP) + 'px');
        return $row;
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
        var $nodeEl = $('<div>').addClass('file-node')
            .attr('data-indent', indent)
            .attr('data-path', node.path)
            .attr('data-type', node.type);

        var $row = applyIndent($('<div>').addClass('file-node-row'), indent)
            .attr('title', node.type === 'directory'
                ? I18n.t('filer.dirTip', {path: node.path})
                : I18n.t('filer.fileTip', {path: node.path}));

        if (node.type === 'directory') {
            var $arrow = $('<span>').addClass('file-arrow')
                .toggleClass('open', !!node.expanded)
                .html('<svg width="12" height="12" viewBox="0 0 16 16"><path d="M6 3l5 5-5 5" stroke="currentColor" stroke-width="1.8" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>');
            $row.append($arrow);
        } else {
            var $spacer = $('<span>').addClass('file-arrow file-arrow-spacer')
                .html('&nbsp;');
            $row.append($spacer);

            var $icon = $('<span>').addClass('file-node-icon file-icon-file')
                .html('<svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M4 1.5h4.75L12.5 5.75V13.5a1 1 0 01-1 1H4a1 1 0 01-1-1V2.5a1 1 0 011-1z" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/><path d="M8.75 1.5v4.25H12.5" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/></svg>');
            $row.append($icon);
        }

        var $name = $('<span>').addClass('file-node-name')
            .text(node.name)
            .attr('title', node.type === 'directory'
                ? I18n.t('filer.dirTip', {path: node.path})
                : I18n.t('filer.fileTip', {path: node.path}));
        $row.append($name);

        $nodeEl.append($row);

        if (node.type === 'directory') {
            var $childrenEl = $('<div>').addClass('file-node-children')
                .toggleClass('open', !!node.expanded);
            if (node.expanded && node.children) {
                renderTree(node.children, $childrenEl, indent + 1);
            }
            $nodeEl.append($childrenEl);
        }

        // 目录：单击展开/折叠，双击加入对话
        if (node.type === 'directory') {
            bindClickDblClick($row,
                function() {
                    var $cEl = $nodeEl.children('.file-node-children');
                    var $aEl = $row.find('.file-arrow');
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
                                url += '&mount=' + encodeURIComponent(wsId);
                            }
                            filerGet(url, function(res) {
                                var subData = (res && res.data) ? res.data : [];
                                renderTree(subData, $cEl, indent + 1);
                                autoExpandSingleDir($cEl, subData, indent + 1, wsId, 0);
                            });
                        }
                    }
                },
                function() {
                    var wsId = getNodeWorkspaceId($nodeEl);
                    var targetInput = (typeof inChatMode !== 'undefined' && inChatMode) ? chatInput : newChatInput;
                    if (!targetInput) return;
                    var currentVal = targetInput.value || '';
                    var insertText = (wsId !== 'workspace')
                        ? '[' + wsId + '/' + node.path + ']'
                        : '[./' + node.path + ']';
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
            // 文件：单击打开查看器，双击加入对话
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
                    var targetInput = (typeof inChatMode !== 'undefined' && inChatMode) ? chatInput : newChatInput;
                    if (!targetInput) return;
                    var currentVal = targetInput.value || '';
                    var insertText = (wsId !== 'workspace')
                        ? '[' + wsId + '/' + node.path + ']'
                        : '[./' + node.path + ']';
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

        // 后端要求整树对账（事件溢出、监听重建等无法枚举具体路径的场景）
        var needResync = false;
        var structuralChange = false;
        for (var i = 0; i < chunk.changes.length; i++) {
            var c = chunk.changes[i];
            if (!c) continue;
            if (c.kind === 'resync') needResync = true;
            if (c.kind === 'create' || c.kind === 'delete') structuralChange = true;
        }

        /* 整树刷新正在飞：本批增量打在旧 DOM 上，会被换入的离屏树覆盖，
         * 而离屏数据可能早于本批变更，标记一下让它结束后再对账一次。 */
        if (structuralChange && refreshInFlight) filerChangedDuringRefresh = true;

        if (needResync || !$treeEl.length || !$treeEl.children().length) {
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

        // 后端要求整树对账（正常由 onFilerChange 整批拦下，此处仅作兜底）
        if (change.kind === 'resync') {
            smartRefreshRoot();
            return;
        }
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
        var $ws = $treeEl.children('.file-node[data-workspace-id="' + CSS.escape(wsId) + '"]').first();
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
        return $wsRoot.find('.file-node[data-path="' + CSS.escape(relPath) + '"]').first();
    }

    function getChildrenContainer($wsRoot, parentDir) {
        if (!$wsRoot || !$wsRoot.length) return $();
        if (!parentDir) {
            // 工作区根的一级列表，或扁平树根
            if ($wsRoot.is($treeEl)) return $wsRoot;
            return $wsRoot.children('.file-node-children');
        }
        var $parentNode = findNodeInWorkspace($wsRoot, parentDir);
        if (!$parentNode.length) return $();
        return $parentNode.children('.file-node-children');
    }

    function removeTreeNode(wsId, relPath) {
        var $wsRoot = getWorkspaceRoot(wsId);
        if (!$wsRoot.length) return;

        var $node = findNodeInWorkspace($wsRoot, relPath);
        if ($node.length) {
            $node.remove();
            return;
        }

        // 节点未渲染：说明 DOM 与磁盘已不一致（此前漏过它的 create）。
        // 交给兜底——折叠态祖先标脏，展开态祖先做一次局部重载。
        markDirtyOrResync(wsId, relPath);
    }

    /**
     * 增量事件无法精确落到 DOM 时的兜底：从最近一个「已渲染」的祖先目录开始收敛。
     *
     * <p>折叠态祖先只标 data-dirty（下次展开自然重拉）；已展开的祖先安排一次局部重载。
     * 若一路上溯到未展开的工作区根，则无需处理（展开时会重新拉一级列表）。</p>
     */
    function markDirtyOrResync(wsId, relPath) {
        var $wsRoot = getWorkspaceRoot(wsId);
        if (!$wsRoot.length) return;

        var dir = getParentDir(relPath);
        // 逐级上溯，最多走到工作区根（dir === ''）
        for (var guard = 0; guard < 128; guard++) {
            if (!dir) {
                if ($wsRoot.is($treeEl)) return; // 扁平树：无根节点可收敛
                var $wsChildren = $wsRoot.children('.file-node-children');
                if (!$wsChildren.length || !$wsChildren.hasClass('open')) return;
                scheduleDirResync(wsId, '');
                return;
            }
            var $node = findNodeInWorkspace($wsRoot, dir);
            if ($node.length) {
                var $children = $node.children('.file-node-children');
                if (!$children.length) return;
                if (!$children.hasClass('open')) {
                    $node.attr('data-dirty', '1');
                    return;
                }
                scheduleDirResync(wsId, dir);
                return;
            }
            dir = getParentDir(dir);
        }
    }

    /** 待局部重载的目录：{ wsId: { dirPath: true } }，防抖合并后统一执行 */
    var pendingResyncDirs = {};
    var pendingResyncTimer = null;

    function scheduleDirResync(wsId, dirPath) {
        if (!pendingResyncDirs[wsId]) pendingResyncDirs[wsId] = {};
        pendingResyncDirs[wsId][dirPath] = true;
        if (pendingResyncTimer) return;
        pendingResyncTimer = setTimeout(function() {
            pendingResyncTimer = null;
            var batch = pendingResyncDirs;
            pendingResyncDirs = {};
            Object.keys(batch).forEach(function(wid) {
                var dirs = Object.keys(batch[wid]);
                // 父目录已覆盖子目录时丢弃子目录，避免重复请求
                dirs.filter(function(d) {
                    for (var i = 0; i < dirs.length; i++) {
                        var other = dirs[i];
                        if (other === d) continue;
                        if (other === '' || d.indexOf(other + '/') === 0) return false;
                    }
                    return true;
                }).forEach(function(d) {
                    refreshDirNode(wid, d);
                });
            });
        }, 300);
    }

    /** 局部重载某个已展开目录（WS 丢事件后的自愈入口）：与整树刷新同一套 diff */
    function refreshDirNode(wsId, dirPath, done) {
        diffDir(wsId, dirPath, done);
    }

    function ensureTreeNode(wsId, relPath, nodeType) {
        var $wsRoot = getWorkspaceRoot(wsId);
        if (!$wsRoot.length) return;

        // 工作区根节点本身不在这里创建
        if ($wsRoot.is($treeEl) === false && !$wsRoot.children('.file-node-children').hasClass('open')) {
            // 工作区未展开：展开时会重新拉一级列表
            return;
        }

        // 已存在则跳过（保留展开状态）
        var $existing = findNodeInWorkspace($wsRoot, relPath);
        if ($existing.length) return;

        var parentDir = getParentDir(relPath);
        var $childrenEl = getChildrenContainer($wsRoot, parentDir);
        if (!$childrenEl.length) {
            // 父目录不在 DOM（中间层未加载，或漏了它的 create）：向上兜底收敛
            markDirtyOrResync(wsId, relPath);
            return;
        }

        // 父目录未展开：只标脏，下次展开再拉真实列表
        if (parentDir) {
            var $parentNode = findNodeInWorkspace($wsRoot, parentDir);
            var $pc = $parentNode.children('.file-node-children');
            if ($pc.length && !$pc.hasClass('open')) {
                $parentNode.attr('data-dirty', '1');
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

    /* 按目录优先、名称字典序插入节点，不触碰其他节点（WS 增量 create 事件专用；
     * 刷新链路已改走 diffChildren，但单事件插入仍需要它，避免为一个节点拉整层） */
    function insertNodeSorted($container, node, indent) {
        if (!$container || !$container.length || !node) return;

        // 再次防重
        var exists = $container.children('.file-node[data-path="' + CSS.escape(node.path) + '"]').length > 0;
        if (exists) return;

        var $children = $container.children('.file-node');
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

        var $newNode = buildNodeEl(node, indent);
        if (!$newNode.length) return;
        if (insertBefore && insertBefore.length) {
            $newNode.insertBefore(insertBefore);
        } else {
            $container.append($newNode);
        }
    }

    function loadChildrenInto($childrenEl, dirPath, wsId, indent) {
        if (!$childrenEl || !$childrenEl.length) return;
        var url = '/web/chat/filer/tree?depth=1';
        if (dirPath) {
            url = '/web/chat/filer/tree?path=' + encodeURIComponent(dirPath) + '&depth=1';
        }
        if (wsId && wsId !== 'workspace' && wsId !== '__flat__') {
            url += (url.indexOf('?') >= 0 ? '&' : '?') + 'mount=' + encodeURIComponent(wsId);
        }
        filerGet(url, function(res) {
            var data = (res && res.data) ? res.data : [];
            // 仅当容器仍为空时填充，避免覆盖用户后续展开
            if (!$childrenEl.children().length) {
                renderTree(data, $childrenEl, indent);
            }
        }).fail(function(jqXHR, textStatus, error) {
            console.error('[filer] load children error', dirPath, error);
        });
    }

    /* ===== 原地 keyed diff：刷新与局部重载统一走「对现有树做增删」 =====
     * 背后的请求骨架不变（1 + 工作区数 + 展开目录数 次逐层请求），但每个响应落地时
     * 不再清空容器重建，而是与服务端顺序做 key（wsId+path）比对：
     *   - 未变的节点一个都不动（事件、hover、选中态、滚动全部原样保留）
     *   - 前后缀剪枝后，新增走 insertBefore 锚点插入，消失的 remove，
     *     类型漂移（文件↔目录）重建单个节点
     * 这套 key 语义与 WS 增量事件（ensureTreeNode/removeTreeNode）完全一致，
     * 刷新与实时推送从此收敛到同一条 patch 路径。 */

    /** 节点身份：工作区层用 wsId，目录层用 path */
    function nodeKey(node) {
        return node.id || node.path;
    }

    /** DOM 节点的身份：工作区根取 data-workspace-id，其余取 data-path */
    function domKey($el) {
        return $el.attr('data-workspace-id') || $el.attr('data-path') || '';
    }

    /** 在临时容器里建一个新节点（工作区根与普通文件节点分别复用各自的完整渲染/事件绑定） */
    function buildNodeEl(node, indent) {
        var $tmp = $('<div>');
        if (node && node.id) {
            appendWorkspaceNode(node, $tmp, indent);
        } else {
            appendNode(node, $tmp, indent);
        }
        return $tmp.children('.file-node').first();
    }

    /**
     * 用服务端列表原地更新 $container 的直接子节点。
     *
     * <p>服务端顺序（目录优先 + 忽略大小写字典序，FileService.buildTree）是排序权威；
     * 前后缀剪枝后按锚点插入，DOM 中残留的乱序（历史增量插入的 localeCompare 与服务端
     * compareToIgnoreCase 在大小写边界上偶有分歧）会被顺带修正。新增目录默认折叠，
     * 由上层（diffDir 或工作区层）决定要不要展开它。</p>
     *
     * @param $container 目标容器（.file-node-children 或 #fileTree 扁平根）
     * @param nodes      服务端节点数组（name/path/type）
     * @param indent     本层缩进
     * @param expandedSet 可选，服务端数据中应保持展开的目录 path 集合
     */
    function diffChildren($container, nodes, indent, expandedSet) {
        if (!$container || !$container.length) return;
        nodes = nodes || [];

        var $kids = $container.children('.file-node');
        var serverKeys = {};
        for (var si = 0; si < nodes.length; si++) {
            serverKeys[nodeKey(nodes[si])] = si;
        }

        // 1) 删除：服务端已不存在的节点（目录被删时其整棵子树随节点一起移除）
        $kids.each(function() {
            var $n = $(this);
            if (!(domKey($n) in serverKeys)) $n.remove();
        });

        // 2) 前后缀剪枝：两端未变的节点不动（最常见路径，一个 DOM 都不碰）
        var $cur = $container.children('.file-node');
        var lead = 0;
        while (lead < nodes.length && lead < $cur.length && domKey($cur.eq(lead)) === nodeKey(nodes[lead])) lead++;
        var tail = 0;
        while (tail < nodes.length - lead && tail < $cur.length - lead
                && domKey($cur.eq($cur.length - 1 - tail)) === nodeKey(nodes[nodes.length - 1 - tail])) tail++;
        var changedMid = nodes.length - lead - tail;
        var $midNodes = (changedMid > 0) ? $cur.slice(lead, lead + changedMid) : $();
        var midKeys = {};
        $midNodes.each(function(i) { midKeys[domKey($(this))] = i; });

        // 3) 中段：新增插入锚点之前；既存节点修正类型漂移并归位；锚点逐步右移
        var anchor = (lead < $cur.length) ? $cur.eq(lead) : null;
        for (var i = lead; i < lead + changedMid; i++) {
            var node = nodes[i];
            var key = nodeKey(node);
            var $existing = (key in midKeys) ? $midNodes.eq(midKeys[key]) : null;
            if (!$existing || !$existing.length) {
                var expanded = !!(expandedSet && node.type === 'directory' && expandedSet[node.path]);
                var n2 = $.extend({}, node);
                if (expanded) n2.expanded = true;
                var $newEl = buildNodeEl(n2, indent);
                if ($newEl.length) {
                    if (anchor) $newEl.insertBefore(anchor);
                    else $container.append($newEl);
                }
            } else {
                if (($existing.attr('data-type') || '') !== node.type) {
                    var n3 = $.extend({}, node);
                    if (expandedSet && node.type === 'directory' && expandedSet[node.path]) n3.expanded = true;
                    var $replaced = buildNodeEl(n3, parseInt($existing.attr('data-indent') || '0', 10));
                    if ($replaced.length) {
                        $existing.replaceWith($replaced);
                        $existing = $replaced;
                    }
                }
                if (anchor && anchor[0] === $existing[0]) {
                    anchor = $existing.next('.file-node');
                    if (!anchor.length) anchor = null;
                } else {
                    if (anchor) $existing.insertBefore(anchor);
                    else $container.append($existing);
                }
            }
            if (anchor && !anchor.closest($container).length) anchor = null;
        }
    }

    /* ============================== 工作区层 diff ==============================
     * 工作区根与普通目录节点不同（data-workspace-id 优先于 data-path），
     * 用同一套 keyed 思路单独处理。diffChildren 里直接判 data-workspace-id，
     * 所以这里只是把容器与 key 描述清楚，保持语义集中。 */

    /* ================================ 整树刷新 ================================ */

    /* 整树刷新的重入保护：进行中的重复请求合并为「结束后再跑一次」，
     * 避免多路并发（WS 事件 / 手动刷新 / 重连对账）的串行恢复互相覆盖。 */
    var refreshInFlight = false;
    var refreshQueued = false;
    var refreshQueuedDones = [];

    /* 离屏刷新期间到达的增量事件打在旧 DOM 上，换入离屏树时会被丢掉，
     * 而离屏树的数据可能早于这些变更 —— 记一笔，提交后补跑一轮对账。 */
    var filerChangedDuringRefresh = false;

    /** 整树刷新的兜底上限：超过则强制释放重入标记，不让面板陷入永久不可刷新 */
    var REFRESH_WATCHDOG_MS = 30000;

    /** 把服务端工作区列表适配成 diffChildren 能吃的节点（key = wsId，详见 domKey） */
    function wsNodesFor(wsList) {
        return (wsList || []).map(function(ws) {
            return { id: ws.id, name: ws.name, path: ws.name, type: 'directory', readonly: ws.readonly };
        });
    }

    /**
     * 整树智能刷新：保留展开状态重新拉取
     *
     * @param done 可选，刷新（含展开态恢复）全部完成后回调
     */
    function smartRefreshRoot(done) {
        if (refreshInFlight) {
            refreshQueued = true;
            if (done) refreshQueuedDones.push(done);
            return;
        }
        refreshInFlight = true;

        var finished = false;
        /* 看门狗：finish() 只在 ajax 回调链末端触发。尽管请求已统一加了超时，
         * 但恢复链路很长（工作区数 × 展开目录数），任何一环出现意外异常都会让
         * refreshInFlight 永久为 true，之后所有刷新（WS 对账 / 可见性对账 / 手动按钮）
         * 只进队列不执行 —— 正是本次要消灭的「永久死状态」。这里强制兜底。 */
        var watchdog = setTimeout(function() {
            if (finished) return;
            console.warn('[filer] smart refresh watchdog fired, force release');
            finish();
        }, REFRESH_WATCHDOG_MS);

        function finish() {
            if (finished) return;
            finished = true;
            clearTimeout(watchdog);
            refreshInFlight = false;
            if (filerChangedDuringRefresh) {
                // 本轮离屏数据可能已过期：补一轮，避免丢掉刷新窗口内的增量
                filerChangedDuringRefresh = false;
                refreshQueued = true;
            }
            if (done) {
                try { done(); } catch (e) { console.error('[filer] refresh callback error', e); }
            }
            if (refreshQueued) {
                refreshQueued = false;
                var dones = refreshQueuedDones;
                refreshQueuedDones = [];
                smartRefreshRoot(function() {
                    dones.forEach(function(cb) {
                        try { cb(); } catch (e) { console.error('[filer] refresh callback error', e); }
                    });
                });
            }
        }

        /* 原地 diff：逐层拉服务端快照，对现有 DOM 做增/删/归位。
         * 未变的节点一个都不动（事件、hover、选中、展开态全部保留）；
         * 中间被删除的目录节点整棵子树随节点移除，无需单独处理。 */
        filerGet('/web/chat/filer/workspaces', function(res) {
            var wsList = (res && res.data) ? res.data : [];
            if (!$treeEl.length) { finish(); return; }

            // 工作区层 diff（key = wsId）：新增挂载建根节点，被移除的挂载整棵删除
            diffChildren($treeEl, wsNodesFor(wsList), 0);

            // 逐个 diff 已展开的工作区（串行，与旧链路一致，避免请求风暴）
            var wsIndex = 0;
            (function nextWs() {
                if (wsIndex >= wsList.length) { finish(); return; }
                var ws = wsList[wsIndex++];
                var $wsRoot = getWorkspaceRoot(ws.id);
                if (!$wsRoot.length || $wsRoot.is($treeEl)) { nextWs(); return; }
                var $wsChildren = $wsRoot.children('.file-node-children');
                if (!$wsChildren.length || !$wsChildren.hasClass('open')) {
                    $wsRoot.removeAttr('data-dirty');
                    nextWs();
                    return;
                }
                var url = '/web/chat/filer/tree?depth=1';
                if (ws.id !== 'workspace') {
                    url += '&mount=' + encodeURIComponent(ws.id);
                }
                filerGet(url, function(res2) {
                    var data2 = (res2 && res2.data) ? res2.data : [];
                    diffChildren($wsChildren, data2, 1);
                    $wsRoot.removeAttr('data-dirty');
                    // 递归 diff 该工作区里已展开的深层目录
                    diffDir(ws.id, '', nextWs);
                }).fail(function() {
                    console.error('[filer] smart refresh workspace error', ws.id);
                    nextWs();
                });
            })();
        }).fail(function() {
            // fallback：无工作区列表（扁平树）—— 直接 diff 根级，再递归已展开目录
            filerGet('/web/chat/filer/tree?depth=1', function(res) {
                var data = (res && res.data) ? res.data : [];
                if (!$treeEl.length) { finish(); return; }
                diffChildren($treeEl, data, 0);
                var openRoots = [];
                $treeEl.children('.file-node').each(function() {
                    var $c = $(this);
                    if ($c.attr('data-type') !== 'directory') return;
                    var $cc = $c.children('.file-node-children');
                    if ($cc.length && $cc.hasClass('open')) openRoots.push($c.attr('data-path'));
                });
                var idx = 0;
                (function next() {
                    if (idx >= openRoots.length) { finish(); return; }
                    diffDir('workspace', openRoots[idx++], next);
                })();
            }).fail(function() {
                console.error('[filer] smart refresh fallback error');
                finish();
            });
        });
    }

    function showFilerChangeIndicator() {
        var $filesTab = $panel.length ? $panel.find('.workspace-tab[data-tab="files"]') : $();
        if (!$filesTab.length) return;
        var $dot = $filesTab.find('.file-change-dot');
        if (!$dot.length) {
            $dot = $('<span>').addClass('file-change-dot');
            $filesTab.append($dot);
        }
        $dot.addClass('active');
        setTimeout(function() { $dot.removeClass('active'); }, 2000);
    }

    /** 展开右栏（若当前折叠）；返回是否执行了展开 */
    function expandFilerPanel() {
        if (!$panel.length || !$panel.hasClass('collapsed')) return false;
        // 移动端右栏整体隐藏，展开无意义
        if (window.matchMedia && window.matchMedia('(max-width: 768px)').matches) return false;
        $panel.removeClass('collapsed');
        if ($toggleBtn.length) {
            $toggleBtn.removeClass('collapsed');
            setToggleBtnArrow(false);
        }
        localStorage.setItem('files-collapsed', '0');
        syncHeaderPadding(false);
        syncToggleBtnPosition();
        return true;
    }
    
    /** 折叠态下在 toggle 按钮显示排队条数；展开或无排队时隐藏 */
    function updateFilerQueueBadge(count) {
        if (!$toggleBtn.length) return;
        var n = count | 0;
        var $badge = $toggleBtn.find('.workspace-queue-badge');
        if (n <= 0) {
            if ($badge.length) $badge.remove();
            return;
        }
        var label = n > 99 ? '99+' : String(n);
        if (!$badge.length) {
            $badge = $('<span>').addClass('workspace-queue-badge');
            $toggleBtn.append($badge);
        }
        $badge.text(label);
    }
    
    /* ---- 对账入口（给可见性恢复 / 网络恢复等「可能漏了推送」的时机用）----
     * 直接无条件 loadTree() 偏重：每次切回标签页都是 1 + 展开目录数 个请求，
     * 而此时文件面板可能根本不可见（用户在聊天页）。因此：面板不可见时只标脏延后做，
     * 可见时也做最小间隔节流。 */
    var RECONCILE_THROTTLE_MS = 10000;
    var lastReconcileAt = 0;
    var reconcilePending = false;

    /** 文件树是否真正展现给用户（面板展开 && 当前页签是文件） */
    function isFilerTreeVisible() {
        if (!$panel.length || $panel.hasClass('collapsed')) return false;
        var $filesTab = $panel.find('.workspace-tab[data-tab="files"]');
        return !$filesTab.length || $filesTab.hasClass('active');
    }

    /**
     * 对账刷新：节流 + 仅在面板可见时真正拉取，不可见时先标脏，下次可见再补
     */
    function reconcileFilerTree() {
        if (!isFilerTreeVisible()) {
            reconcilePending = true;
            return;
        }

        var now = Date.now();
        if (now - lastReconcileAt < RECONCILE_THROTTLE_MS) {
            reconcilePending = true;
            return;
        }
        lastReconcileAt = now;
        reconcilePending = false;
        loadTree();
    }

    // 面板重新可见（展开面板 / 切回文件页签）时补做欠下的对账
    $(document).on('click', '#workspaceToggleBtn, .workspace-tab[data-tab="files"]', function() {
        setTimeout(function() {
            if (reconcilePending) reconcileFilerTree();
        }, 0);
    });

    // ---- 暴露全局函数 ----
    window.loadTree = loadTree;
    window.reconcileFilerTree = reconcileFilerTree;
    window.onFilerChange = onFilerChange;
    window.expandFilerPanel = expandFilerPanel;
    window.updateFilerQueueBadge = updateFilerQueueBadge;

    // ---- 搜索（后端全量搜索） ----
    var $searchInput = $('#fileSearchInput');
    var $searchClear = $('#fileSearchClear');
    var searchResultsEl = null;

    function ensureSearchResultsContainer() {
        if (!searchResultsEl && $treeEl.length) {
            searchResultsEl = $('<div>').addClass('file-search-results');
            $treeEl.after(searchResultsEl);
        }
    }

    function escapeHtml(text) {
        return $('<div>').text(text || '').html();
    }

    function showSearchResults(keyword, done) {
        function finish() { if (done) done(); }
        if (!$treeEl.length || !keyword) { finish(); return; }
        var kw = keyword.trim().toLowerCase();
        if (!kw) { hideSearchResults(); finish(); return; }

        $treeEl.hide();
        ensureSearchResultsContainer();
        searchResultsEl.show();
        searchResultsEl.html('<div class="file-search-loading">' + I18n.t('common.loading') + '</div>');

        filerGet(filerUrl('/web/chat/filer/search', 'keyword=' + encodeURIComponent(kw)), function(res) {
            var data = (res && res.data) ? res.data : [];
            searchResultsEl.html('');

            if (data.length === 0) {
                searchResultsEl.html('<div class="file-search-empty">' + I18n.t('filer.noResults') + '</div>');
                finish();
                return;
            }

            data.forEach(function(item) {
                var $row = $('<div>').addClass('file-search-item')
                    .attr('data-path', item.path)
                    .attr('data-name', item.name)
                    .attr('data-type', item.type);

                var $icon = $('<span>').addClass('file-search-item-icon');
                if (item.type === 'directory') {
                    $icon.html('<svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M2 4a1 1 0 011-1h3.5l1.5 1.5H13a1 1 0 011 1V12a1 1 0 01-1 1H3a1 1 0 01-1-1V4z" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/></svg>');
                } else {
                    $icon.html('<svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M4 1.5h4.75L12.5 5.75V13.5a1 1 0 01-1 1H4a1 1 0 01-1-1V2.5a1 1 0 011-1z" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/><path d="M8.75 1.5v4.25H12.5" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/></svg>');
                }
                $row.append($icon);

                var $pathSpan = $('<span>').addClass('file-search-item-path');
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
                        var targetInput = (typeof inChatMode !== 'undefined' && inChatMode) ? chatInput : newChatInput;
                        if (!targetInput) return;
                        var currentVal = targetInput.value || '';
                        var insertText = (wsId !== 'workspace')
                            ? '[' + wsId + '/' + it.path + ']'
                            : '[./' + it.path + ']';
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
            finish();
        }).fail(function(jqXHR, textStatus, error) {
            console.error('[filer] search error', error);
            searchResultsEl.html('<div class="file-search-empty">' + I18n.t('filer.searchFailed') + '</div>');
            finish();
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

    // ---- 手动刷新（搜索框右侧） ----
    // 文件树的自动刷新依赖 WS 推送，链路上任何一环丢事件都会让面板停在旧状态，
    // 这里提供一个不依赖任何推送的自救入口（等价于刷新浏览器，但保留展开状态）。
    var $refreshBtn = $('#fileRefreshBtn');
    if ($refreshBtn.length) {
        $refreshBtn.on('click', function() {
            if ($refreshBtn.hasClass('spinning')) return; // 防连点
            $refreshBtn.addClass('spinning');

            // 请求卡住/异常时也要把图标转圈停下来（与整树刷新看门狗对齐，略留余量）
            var spinGuard = setTimeout(function() { $refreshBtn.removeClass('spinning'); }, REFRESH_WATCHDOG_MS + 2000);
            var done = function() {
                clearTimeout(spinGuard);
                $refreshBtn.removeClass('spinning');
            };

            var kw = $searchInput.length ? ($searchInput.val() || '').trim() : '';
            if (kw) {
                // 搜索态：重跑搜索（结果列表本身就是全量重建）
                showSearchResults(kw, done);
                return;
            }
            wsPathCache.loaded = false; // 右键菜单的真实路径缓存一并失效
            loadTree(done);
        });
    }

    // ---- 添加文件类型挂载点击事件 ----
    $(document).on('click', '#fileMountHint', function() {
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

/* ===== app-history.js ===== */
/* 数据管理：会话历史 + 命令系统 + 输入历史 + 模型选择 */
/* 依赖：app-base.js */

/* ===== History ===== */

/* 记住“当前活动会话”，刷新或下次打开时自动恢复 */
/* 自定义 composing 标志，替代 e.isComposing（macOS 输入法组合态下 Enter 时序问题） */
var composing = false;

function isInputComposing(event) {
    return composing || !!(event && (event.isComposing || event.keyCode === 229));
}

var ACTIVE_SESSION_KEY = 'soloncode-active-session';
function rememberActiveSession(sessionId) {
    try { if (sessionId) localStorage.setItem(ACTIVE_SESSION_KEY, sessionId); } catch (e) {}
}
function forgetActiveSession() {
    try { localStorage.removeItem(ACTIVE_SESSION_KEY); } catch (e) {}
}
window.rememberActiveSession = rememberActiveSession;
window.forgetActiveSession = forgetActiveSession;

/* 历史列表加载完成后，尝试恢复上次的活动会话 */
function restoreActiveSession() {
    var saved = null;
    try { saved = localStorage.getItem(ACTIVE_SESSION_KEY); } catch (e) {}
    if (!saved) return;
    for (var i = 0; i < chatHistory.length; i++) {
        if (chatHistory[i].sessionId === saved) {
            selectSession(i);
            return;
        }
    }
    /* 保存的会话已不存在，清理掉 */
    forgetActiveSession();
}

function loadSessionHistory() {
    $.get('/web/chat/sessions', function(resp) {
        try {
            var list = resp.data;
            chatHistory = [];
            for (var i = 0; i < list.length; i++) {
                chatHistory.push({ label: list[i].label, sessionId: list[i].sessionId, isPinned: list[i].isPinned === true });
            }
            updateHistoryUI();
            restoreActiveSession();
        } catch (e) {}
    });
}

function saveChatToHistory(firstMsg) {
    ensureChatInHistory(SESSION_ID, firstMsg, true);
    rememberActiveSession(SESSION_ID);
}

function ensureChatInHistory(sessionId, firstMsg, makeCurrent) {
    if (!sessionId) return;
    var label = (firstMsg || I18n.t('history.newConversation')).toString();
    label = label.length > 30 ? label.substring(0, 30) + '...' : label;
    var shouldMakeCurrent = (makeCurrent !== false) && (sessionId === SESSION_ID || sessionId === activeSessionId || currentChatIndex === -1);
    for (var i = 0; i < chatHistory.length; i++) {
        if (chatHistory[i].sessionId === sessionId) {
            if (shouldMakeCurrent) currentChatIndex = i;
            updateHistoryUI();
            return;
        }
    }
    chatHistory.unshift({ label: label, sessionId: sessionId, isPinned: false });
    if (chatHistory.length > 50) chatHistory.pop();
    if (shouldMakeCurrent) {
        currentChatIndex = 0;
    } else if (currentChatIndex >= 0) {
        currentChatIndex++;
        if (currentChatIndex >= chatHistory.length) currentChatIndex = chatHistory.length - 1;
    }
    updateHistoryUI();
}

/* Sidebar event delegation — single listener instead of per-item binding */
function closeSidebarItemMenus() {
    $(historyList).find('.sidebar-item-menu-wrap.open').removeClass('open');
    $(historyList).find('.sidebar-item.menu-open').removeClass('menu-open');
    $(historyList).find('.sidebar-item-menu-trigger').attr('aria-expanded', 'false');
}

$(historyList).on('click', function(e) {
    var $target = $(e.target);
    var $menuTrigger = $target.closest('.sidebar-item-menu-trigger');
    if ($menuTrigger.length) {
        e.stopPropagation();
        var $wrap = $menuTrigger.closest('.sidebar-item-menu-wrap');
        var $item = $wrap.closest('.sidebar-item');
        var shouldOpen = !$wrap.hasClass('open');
        closeSidebarItemMenus();
        if (shouldOpen) {
            $wrap.addClass('open');
            $item.addClass('menu-open');
            $menuTrigger.attr('aria-expanded', 'true');
        }
        return;
    }
    var $delBtn = $target.closest('.sidebar-item-del');
    if ($delBtn.length) {
        e.stopPropagation();
        closeSidebarItemMenus();
        var idx = parseInt($delBtn.closest('.sidebar-item').attr('data-idx'));
        if (!isNaN(idx)) deleteSession(idx);
        return;
    }
    var $renameBtn = $target.closest('.sidebar-item-rename');
    if ($renameBtn.length) {
        e.stopPropagation();
        closeSidebarItemMenus();
        var idx = parseInt($renameBtn.closest('.sidebar-item').attr('data-idx'));
        if (!isNaN(idx)) startRename(idx);
        return;
    }
    var $forkBtn = $target.closest('.sidebar-item-fork');
    if ($forkBtn.length) {
        e.stopPropagation();
        closeSidebarItemMenus();
        var idx = parseInt($forkBtn.closest('.sidebar-item').attr('data-idx'));
        if (!isNaN(idx)) forkSession(idx);
        return;
    }
    var $pinBtn = $target.closest('.sidebar-item-pin-btn');
    if ($pinBtn.length) {
        e.stopPropagation();
        closeSidebarItemMenus();
        var idx = parseInt($pinBtn.closest('.sidebar-item').attr('data-idx'));
        if (!isNaN(idx)) togglePin(idx);
        return;
    }
    var $item = $target.closest('.sidebar-item');
    if ($item.length) {
        var idx = parseInt($item.attr('data-idx'));
        if (!isNaN(idx)) selectSession(idx);
    }
});

$(document).on('click', function(e) {
    if (!$(e.target).closest('.sidebar-item-menu-wrap').length) closeSidebarItemMenus();
});

var _updateHistoryUIPending = false;
function updateHistoryUI() {
    if (_updateHistoryUIPending) return;
    _updateHistoryUIPending = true;
    requestAnimationFrame(function() {
        _updateHistoryUIPending = false;

        // 排序：置顶优先，内部保持原始顺序
        var sortedIndices = [];
        for (var si = 0; si < chatHistory.length; si++) sortedIndices.push(si);
        sortedIndices.sort(function(a, b) {
            var aPinned = chatHistory[a].isPinned ? 1 : 0;
            var bPinned = chatHistory[b].isPinned ? 1 : 0;
            if (aPinned !== bPinned) return bPinned - aPinned;
            return 0;
        });

        var html = '';
        for (var si = 0; si < sortedIndices.length; si++) {
            var i = sortedIndices[si];
            var sess = sessionMap[chatHistory[i].sessionId];
            var streaming = sess && sess.isStreaming;
            var isPinned = chatHistory[i].isPinned === true;
            var cls = 'sidebar-item'
                + (i === currentChatIndex ? ' active' : '')
                + (streaming ? ' streaming' : '')
                + (isPinned ? ' pinned' : '');
            
            html += '<div class="' + cls + '" data-idx="' + i + '">'
                + '<span class="sidebar-item-label">' + escapeHtml(chatHistory[i].label) + '</span>';
                
            // 任务进度 badge
            var todoInfo = window.sessionTodoMap && window.sessionTodoMap[chatHistory[i].sessionId];
            if (todoInfo && todoInfo.total > 0) {
                var doneClass = todoInfo.done === todoInfo.total ? ' done' : '';
                html += '<span class="sidebar-item-todo' + doneClass + '">' + todoInfo.done + '/' + todoInfo.total + '</span>';
            }
            if (streaming) {
                html += '<span class="sidebar-item-spinner" title="' + I18n.t('history.conversationInProgress') + '"></span>';
            }
            // pinned：未 hover 时在 ⋯ 槽位显示 pin 标识；hover/菜单打开时再显示 ⋯
            html += '<span class="sidebar-item-menu-wrap">';
            // 与菜单重命名/删除统一：stroke 线框风格（非 fill 实心）
            var pinSvg = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
                + '<line x1="12" y1="17" x2="12" y2="22"/>'
                + '<path d="M5 17h14v-1.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V6h1a2 2 0 0 0 0-4H8a2 2 0 0 0 0 4h1v4.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24Z"/>'
                + '</svg>';
            if (isPinned) {
                html += '<span class="sidebar-item-pin-mark" title="' + I18n.t('history.pinned') + '" aria-hidden="true">' + pinSvg + '</span>';
            }
            html += '<button type="button" class="sidebar-item-menu-trigger" title="' + I18n.t('history.conversationActions') + '" aria-label="' + I18n.t('history.conversationActions') + '" aria-expanded="false">'
                + '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="1.25"/><circle cx="12" cy="12" r="1.25"/><circle cx="19" cy="12" r="1.25"/></svg>'
                + '</button>'
                + '<span class="sidebar-item-menu" role="menu">'
                + '<button type="button" class="sidebar-item-pin-btn" role="menuitem">'
                + pinSvg
                + '<span>' + (isPinned ? I18n.t('history.unpin') : I18n.t('history.pinConversation')) + '</span></button>'
                + '<button type="button" class="sidebar-item-rename" role="menuitem"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg><span>' + I18n.t('history.rename') + '</span></button>'
                + '<button type="button" class="sidebar-item-fork" role="menuitem"><svg viewBox="0 0 16 16" fill="currentColor"><path d="M5 5.372v.878c0 .414.336.75.75.75h4.5a.75.75 0 0 0 .75-.75v-.878a2.25 2.25 0 1 1 1.5 0v.878a2.25 2.25 0 0 1-2.25 2.25h-1.5v2.128a2.25 2.25 0 1 1-1.5 0v-2.128h-1.5A2.25 2.25 0 0 1 3.5 6.25v-.878a2.25 2.25 0 1 1 1.5 0ZM5 3.25a.75.75 0 1 0-1.5 0 .75.75 0 0 0 1.5 0Zm6.75.75a.75.75 0 1 0 0-1.5.75.75 0 0 0 0 1.5Zm-3 8.75a.75.75 0 1 0-1.5 0 .75.75 0 0 0 0 1.5Z"/></svg><span>' + I18n.t('history.copyConversation') + '</span></button>'
                + '<button type="button" class="sidebar-item-del" role="menuitem"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg><span>' + I18n.t('common.delete') + '</span></button>'
                + '</span></span></div>';
        }
        var $list = $(historyList);
        // 仅当 HTML 真正变化时才写入 DOM，避免无效重排
        if ($list.html() !== html) {
            $list.html(html);
        }
    });
}

function startRename(idx) {
    var $item = $(historyList).find('.sidebar-item[data-idx="' + idx + '"]');
    if (!$item.length) return;
    var $labelEl = $item.find('.sidebar-item-label');
    if (!$labelEl.length) return;

    var currentLabel = chatHistory[idx].label.replace(/\.\.\.$/, '');
    var $input = $('<input>', {
        type: 'text',
        'class': 'sidebar-rename-input',
        maxlength: 50,
        val: currentLabel
    });

    $labelEl.hide();
    $item.find('.sidebar-item-menu-wrap').hide();
    $labelEl.before($input);
    $input[0].focus();
    $input[0].select();

    function finishRename() {
        var newLabel = $input.val().trim();
        if (newLabel && newLabel !== currentLabel) {
            newLabel = newLabel.length > 30 ? newLabel.substring(0, 30) + '...' : newLabel;
            chatHistory[idx].label = newLabel;

            $.post('/web/chat/sessions/rename', {
                sessionId: chatHistory[idx].sessionId,
                label: newLabel
            });
        }
        $input.remove();
        $labelEl.show();
        $item.find('.sidebar-item-menu-wrap').show();
        updateHistoryUI();
    }

    $input.on('blur', finishRename);
    $input.on('keydown', function(e) {
        if (e.key === 'Enter' && !isInputComposing(e)) { e.preventDefault(); $input[0].blur(); }
        if (e.key === 'Escape') { $input.val(currentLabel); $input[0].blur(); }
    });
}

/**
 * 分叉会话：调用服务端把源会话的消息历史复制到一个新的 sessionId，
 * 然后在本地历史列表中创建新条目并自动切换过去。
 */
function forkSession(idx) {
    var entry = chatHistory[idx];
    if (!entry) return;

    layer.confirm(I18n.t('history.forkConfirmMessage'), {
        title: I18n.t('history.copyConversation'),
        btn: [I18n.t('common.copy'), I18n.t('common.cancel')],
        icon: 3,
        offset: '120px'
    }, function(confirmIdx) {
        layer.close(confirmIdx);
        $.post('/web/chat/sessions/fork', { sessionId: entry.sessionId }, function(resp) {
            try {
                if (!resp || resp.code !== 200 || !resp.data || !resp.data.sessionId) {
                    throw new Error('Invalid response');
                }
                var newId = resp.data.sessionId;
                ensureChatInHistory(newId, resp.data.name || newId, true);
                rememberActiveSession(newId);

                var newIdx = -1;
                for (var i = 0; i < chatHistory.length; i++) {
                    if (chatHistory[i].sessionId === newId) { newIdx = i; break; }
                }
                if (newIdx >= 0) selectSession(newIdx);

                if (typeof layer !== 'undefined' && layer.msg) {
                    layer.msg(I18n.t('history.forkSuccess'), { icon: 1, time: 2000, offset: '120px' });
                }
            } catch (e) {
                if (typeof layer !== 'undefined' && layer.msg) {
                    layer.msg(I18n.t('history.forkFailed'), { icon: 2, time: 3000, offset: '120px' });
                } else {
                    alert(I18n.t('history.forkFailed'));
                }
            }
        }).fail(function() {
            if (typeof layer !== 'undefined' && layer.msg) {
                layer.msg(I18n.t('history.forkFailed'), { icon: 2, time: 3000, offset: '120px' });
            } else {
                alert(I18n.t('history.forkFailed'));
            }
        });
    });
}

function deleteSession(idx) {
    var entry = chatHistory[idx];
    if (!entry) return;

    layer.confirm(I18n.t('history.deleteConfirmMessage', { name: entry.label || I18n.t('history.unnamed') }), { title: I18n.t('history.confirmDelete'), btn: [I18n.t('common.delete'), I18n.t('common.cancel')], icon: 3, offset: '120px' }, function(index) {
        layer.close(index);
        $.post('/web/chat/sessions/delete?sessionId=' + encodeURIComponent(entry.sessionId), function() {
        /* Clean up session state after server confirms */
        var sess = sessionMap[entry.sessionId];
        if (sess) {
            if (sess.eventSource) sess.eventSource.close();
            if (sess.silenceTimer) clearTimeout(sess.silenceTimer);
            if (sess.contentRafId) cancelAnimationFrame(sess.contentRafId);
            if (sess.reasonRafId) cancelAnimationFrame(sess.reasonRafId);
            $(sess.container).remove();
            delete sessionMap[entry.sessionId];
        }

        chatHistory.splice(idx, 1);

        if (idx === currentChatIndex) {
            currentChatIndex = -1;
            switchToWelcomeMode();
        } else if (idx < currentChatIndex) {
            currentChatIndex--;
        }

        updateHistoryUI();
    }).fail(function () {
        if (typeof layer !== 'undefined' && layer.msg) {
            layer.msg(I18n.t('history.deleteFailed'), { icon: 2, time: 3000, offset: '120px' });
        } else {
            alert(I18n.t('history.deleteFailed'));
        }
    });
    });
}

function togglePin(idx) {
    var entry = chatHistory[idx];
    if (!entry) return;

    var newPinned = !entry.isPinned;
    $.post('/web/chat/sessions/pin', {
        sessionId: entry.sessionId,
        pinned: newPinned
    }, function() {
        // 更新本地状态
        entry.isPinned = newPinned;
        updateHistoryUI();
    }).fail(function() {
        if (typeof layer !== 'undefined' && layer.msg) {
            layer.msg(I18n.t('history.operateFailedRetry'), { icon: 2, time: 3000, offset: '120px' });
        } else {
            alert(I18n.t('history.operateFailedRetry'));
        }
    });
}

function selectSession(idx) {
    // 先关闭中间区域可能存在的覆盖层（记忆面板 / git diff / 详情），
    // 必须放在早退守卫之前：否则点击"当前会话"时会因 idx===currentChatIndex 提前 return，
    // 覆盖层无法关闭，视图卡在记忆/详情面板上。
    if (typeof closeCenterViewer === 'function') closeCenterViewer();
    if (idx === currentChatIndex && inChatMode) return;
    var entry = chatHistory[idx];
    if (!entry) return;

    currentChatIndex = idx;
    SESSION_ID = entry.sessionId;
    rememberActiveSession(entry.sessionId);
    if (!inChatMode) switchToChatMode();
    setActiveSession(entry.sessionId);
    updateHistoryUI();

    var sess = sessionMap[entry.sessionId];
    /* Only load from server if not streaming and container has no content */
    if (!sess.isStreaming && sess.container.children.length === 0) {
        loadMessages(sess);
    } else {
        scrollToBottom(true);
        if (typeof scheduleMsgNavRebuild === 'function') scheduleMsgNavRebuild();
    }
}

function loadMessages(sess) {
    // 历史加载期间：流式 chunk 先缓存，加载完再回放，避免被 DOM 重建冲掉
    sess._loadingHistory = true;

    /* 两路请求并行，均就绲后才收尾：
     *  - /web/chat/messages：历史纯文本（主路径）
     *  - /web/chat/messages/last-trace：最后一轮的工具执行过程（增强项）
     * last-trace 只是锦上添花，给它 1.5s 硬超时：超时/失败就当作无过程处理，
     * 绝不允许它拖慢或卡住会话切换。 */
    var gate = {
        domDone: false,
        traceDone: false,
        traceData: null,
        lastAssistantRow: null,
        finished: false
    };

    var traceTimer = setTimeout(function() { onTraceReady(null); }, 1500);

    function onTraceReady(data) {
        if (gate.traceDone) return;
        gate.traceDone = true;
        clearTimeout(traceTimer);
        gate.traceData = data;
        tryFinishLoad();
    }

    function tryFinishLoad() {
        if (gate.finished || !gate.domDone || !gate.traceDone) return;
        gate.finished = true;

        // 必须先解除加载态：回放走的是与实时流同一条渲染管线，
        // 若仍为 _loadingHistory 会被当成实时包反向缓存起来
        sess._loadingHistory = false;

        try {
            /* 任务仍在跑（刷新页面的典型场景）：先把流式 UI 打开，再回放。顺序不能反 ——
             * ensureAssistantBubble 按 sess.isStreaming 决定是否隐藏复制/重跑/删除按钮，
             * 先开流才能让回放出来的这一行与实时流行为一致（转圈/Stop/计时恢复，
             * 按钮待 finishStream 收尾时统一显示），接下来的实时增量直接接续在回放内容之后。 */
            if (gate.traceData && gate.traceData.running &&
                    typeof openStreamFromIncoming === 'function' && !sess.isStreaming) {
                openStreamFromIncoming(sess);
            }

            /* 本轮已结束时，回放的过程会并入历史末尾这条 AI 气泡行（插在最终回答之前），
             * 保持与流式一样的单行结构；故错位修正与行合并都在 replayLastTrace 内完成。 */
            if (typeof replayLastTrace === 'function') {
                replayLastTrace(sess, gate.traceData, gate.lastAssistantRow);
            }
        } catch (e) {
            // 回放属于增强项，异常不得影响历史展示
            console.warn('[replayLastTrace]', e);
        }

        // 回放加载期间缓存的流式 chunk（刷新后后端仍在推的内容）
        if (typeof flushPendingStreamChunks === 'function') {
            flushPendingStreamChunks(sess);
        }
        if (typeof scheduleMsgNavRebuild === 'function') scheduleMsgNavRebuild();
        if (sess.sessionId === activeSessionId) scrollToBottom(true);
    }

    $.get('/web/chat/messages/last-trace?sessionId=' + encodeURIComponent(sess.sessionId), function(resp) {
        onTraceReady(resp && resp.data);
    }).fail(function() {
        onTraceReady(null);
    });

    $.get('/web/chat/messages?sessionId=' + encodeURIComponent(sess.sessionId), function(resp) {
        var realContainer = sess.container;
        try {
            var msgs = resp.data;
            // 用临时容器批量构建 DOM，避免逐条 append 触发多次 layout
            var tempDiv = document.createElement('div');
            sess.container = tempDiv;
            resetStreamState(sess);
            for (var i = 0; i < msgs.length; i++) {
                var m = msgs[i];
                if (m.role === 'USER') {
                    resetStreamState(sess);
                    gate.lastAssistantRow = null;
                    // 从附件元数据中分离出图片附件，构造 read-raw URL 实现历史图片预览
                    var historyImages = null;
                    var historyFileAttachments = null;
                    if (m.attachments && m.attachments.length > 0) {
                        historyImages = [];
                        historyFileAttachments = [];
                        for (var ai = 0; ai < m.attachments.length; ai++) {
                            var att = m.attachments[ai];
                            if (att.type === 'image') {
                                // read-raw 由浏览器 <img src> 直发，绕过 fetch/XHR 劫持层，
                                // 必须显式拼 workspaceId（统一入口 window.wsAndSuffix）
                                var _rawUrl = '/web/chat/filer/read-raw?path=' + encodeURIComponent(att.name) + window.wsAndSuffix();
                                historyImages.push(_rawUrl);
                            } else {
                                historyFileAttachments.push(att);
                            }
                        }
                        if (historyImages.length === 0) historyImages = null;
                        if (historyFileAttachments.length === 0) historyFileAttachments = null;
                    }
                    appendUserMessage(sess, m.content, historyImages, historyFileAttachments, m.createdAt, m.sourceLabel, m.agentName);
                    /* 补 runId：与 assistant 行同理，历史行建立时 sess.currentRunId 还是空的。
                     * 缺了它，删除/重跑拿不到锚点，只能退化成按 DOM 行数猜条数。 */
                    if (m.runId) {
                        var userRows = $(tempDiv).find('.msg-row.user');
                        var lastUserRow = userRows.length ? userRows[userRows.length - 1] : null;
                        if (lastUserRow && !lastUserRow.getAttribute('data-run-id')) {
                            lastUserRow.setAttribute('data-run-id', m.runId);
                        }
                    }
                } else if (m.role === 'ASSISTANT') {
                    var isConsecutive = (i > 0 && msgs[i - 1].role === 'ASSISTANT');
                    if (!isConsecutive) resetStreamState(sess);
                    var el = ensureAssistantBubble(sess);
                    // 记住末尾的 AI 气泡行：若后面回放了执行过程，需把它重新挪到末尾
                    gate.lastAssistantRow = (el && el.closest) ? el.closest('.msg-row') : null;
                    /* 补 runId：历史行是 loadMessages 建的，此刻 sess.currentRunId 还是空的，
                     * ensureAssistantBubble 打不上 data-run-id。缺了它，删除/重跑只能退化成
                     * 「只处理当前这一行」，同一轮里其它带 data-run-id 的行会留在屏上。
                     * 回放路径（mergeReplayRowInto）也会补，但它依赖 trace 对齐，不能指望。 */
                    if (gate.lastAssistantRow && m.runId && !gate.lastAssistantRow.getAttribute('data-run-id')) {
                        gate.lastAssistantRow.setAttribute('data-run-id', m.runId);
                    }
                    sess.reasonBuffer = isConsecutive ? sess.reasonBuffer + '\n\n' + m.content : m.content;
                    // 与流结束路径统一：先写入 MD；高亮/mermaid 循环后对真实容器统一跑一次
                    if (typeof finalizeMdElement === 'function') {
                        // 临时容器阶段只做 MD 解析，避免过早 ensureHljs/mermaid
                        el.classList.remove('md-streaming');
                        el.setAttribute('data-md-raw', sess.reasonBuffer);
                        el.innerHTML = renderMd(sess.reasonBuffer);
                        if (typeof addCodeBlockButtons === 'function') addCodeBlockButtons(el);
                    } else {
                        el.setAttribute('data-md-raw', sess.reasonBuffer);
                        $(el).html(renderMd(sess.reasonBuffer));
                        if (typeof addCodeBlockButtons === 'function') addCodeBlockButtons(el);
                    }
                    // 显示时间戳（连续助手消息取最后一条的时间）
                    setAssistantTime(sess, m.createdAt);
                }
            }
            // 恢复真实容器，一次性移入所有子节点
            sess.container = realContainer;
            $(realContainer).html('');
            var fragment = document.createDocumentFragment();
            while (tempDiv.firstChild) {
                fragment.appendChild(tempDiv.firstChild);
            }
            realContainer.appendChild(fragment);
            // 统一高亮所有代码块（user 消息的代码块已被 appendUserMessage 标记收集，不会重复）
            if (typeof highlightCodeBlocks === 'function') highlightCodeBlocks(realContainer);
            if (typeof processMermaidBlocks === 'function') processMermaidBlocks(realContainer);
            resetStreamState(sess);
            if (sess.sessionId === activeSessionId) scrollToBottom(true);
        } catch (e) {
            // 异常时确保容器恢复
            if (realContainer) sess.container = realContainer;
            gate.lastAssistantRow = null;
        } finally {
            gate.domDone = true;
            tryFinishLoad();
        }
    }).fail(function() {
        gate.domDone = true;
        gate.lastAssistantRow = null;
        tryFinishLoad();
    });
}

/* Load on startup：会话列表关键路径立即拉；hints 可延后 */
loadSessionHistory();

/* ===== Command System ===== */
var commandList = []; // [{name, description, type}, ...]
var commandsLoaded = false;
var cmdTrigger = null; // '/' for commands, '@' for subagents, '$' for skills

function loadCommands() {
    $.get('/web/chat/hints', function(resp) {
        try {
            commandList = resp.data || [];
            commandsLoaded = true;
            if (typeof renderAgentUI === 'function') renderAgentUI();
        } catch (e) {}
    });
}
window.reloadCommandHints = loadCommands;

// hints 非首屏必需，空闲时再拉，减少启动并发
if (window.requestIdleCallback) {
    requestIdleCallback(function() { loadCommands(); }, { timeout: 2500 });
} else {
    setTimeout(loadCommands, 600);
}

var $newChatCmdComplete = $('#newChatCmdComplete');
var $chatCmdComplete = $('#chatCmdComplete');
var cmdActiveIndex = -1;
var cmdVisibleItems = [];

function getActiveCmdComplete() {
    return inChatMode ? $chatCmdComplete[0] : $newChatCmdComplete[0];
}

/**
 * 关闭所有工具栏弹出面板（互斥核心）
 * 包括：命令补全、输入历史、循环任务、模型下拉
 */
function closeAllToolbarPanels() {
    // 命令补全
    hideCmdComplete();
    // 输入历史
    if (typeof $chatHistoryPanel !== 'undefined' && $chatHistoryPanel) $chatHistoryPanel.removeClass('show');
    // 循环任务面板
    $('#chatLoopPanel, #newChatLoopPanel').hide();
    // 模型下拉
    $('#chatModelSelector, #newChatModelSelector').removeClass('open');
    // 子代理下拉
    $('#chatAgentSelector, #newChatAgentSelector').removeClass('open');
    // 更多菜单
    $('#chatMoreMenu, #newChatMoreMenu').removeClass('open');
}
window.closeAllToolbarPanels = closeAllToolbarPanels;

function showCmdComplete(inputEl, completeEl, prefix) {
    if (!commandsLoaded || commandList.length === 0) return;
    closeAllToolbarPanels();
    var trigger = prefix.charAt(0);
    var query = prefix.substring(1).toLowerCase();
    var filterType = (trigger === '@') ? 'subagent' : (trigger === '$') ? 'skill' : 'command';
    cmdVisibleItems = [];
    var html = '';

    // Add search bar for skills
    if (filterType === 'skill') {
        html += '<div class="cmd-complete-search">'
            + '<input type="text" class="cmd-search-input" placeholder="' + I18n.t('history.searchSkills') + '" autocomplete="off" />'
            + '</div>';
    }

    for (var i = 0; i < commandList.length; i++) {
        var cmd = commandList[i];
        // Filter by type based on trigger
        if (cmd.type !== filterType) continue;
        if (cmd.name.toLowerCase().indexOf(query) === 0 || query.length === 0) {
            cmdVisibleItems.push(cmd);
            var nameClass = (trigger === '@') ? 'cmd-name subagent' : (trigger === '$') ? 'cmd-name skill' : 'cmd-name';
            html += '<div class="cmd-complete-item" data-index="' + (cmdVisibleItems.length - 1) + '">'
                + '<span class="' + nameClass + '">' + escapeHtml(trigger + cmd.name) + '</span>'
                + '<span class="cmd-desc">' + escapeHtml(cmd.description || '') + '</span>'
                + '</div>';
        }
    }

    if (cmdVisibleItems.length === 0) {
        hideCmdComplete();
        return;
    }

    cmdTrigger = trigger;
    cmdActiveIndex = -1;
    $(completeEl).html(html).addClass('show');

    // Bind search for skills
    if (filterType === 'skill') {
        var $searchInput = $(completeEl).find('.cmd-search-input');
        if ($searchInput.length) {
            $searchInput.on('input', function() {
                var q = this.value.trim().toLowerCase();
                var $items = $(completeEl).find('.cmd-complete-item');
                var newVisible = [];
                $items.each(function() {
                    var $item = $(this);
                    var name = $item.find('.cmd-name').text().toLowerCase().replace(/^\$/, '');
                    if (!q || name.indexOf(q) >= 0) {
                        $item.show();
                        newVisible.push(cmdVisibleItems[parseInt($item.attr('data-index'))]);
                    } else {
                        $item.hide();
                    }
                });
                cmdVisibleItems = newVisible;
                $items.filter(':visible').each(function(i) {
                    $(this).attr('data-index', i);
                });
                cmdActiveIndex = -1;
                $items.removeClass('active');
            });
            $searchInput.on('mousedown', function(e) {
                e.stopPropagation();
            });
            $searchInput.on('click', function(e) {
                e.stopPropagation();
            });
            $searchInput.on('keydown', function(e) {
                if (e.key === 'Escape') {
                    hideCmdComplete();
                    inputEl.focus();
                    e.stopPropagation();
                    e.preventDefault();
                    return;
                }
                e.stopPropagation();
            });
        }
    }
}

function hideCmdComplete() {
    $newChatCmdComplete.removeClass('show');
    $chatCmdComplete.removeClass('show');
    cmdActiveIndex = -1;
    cmdVisibleItems = [];
    cmdTrigger = null;
}

function applyCmdSelection(inputEl, completeEl) {
    if (cmdActiveIndex >= 0 && cmdActiveIndex < cmdVisibleItems.length) {
        var cmd = cmdVisibleItems[cmdActiveIndex];
        var trigger = cmdTrigger || '/';

        // 找到当前输入框中的命令前缀位置
        var val = inputEl.value;
        var prefixPos = -1;

        // 查找最近的命令前缀（/、@ 或 $）
        for (var i = val.length - 1; i >= 0; i--) {
            var ch = val.charAt(i);
            if (ch === '/' || ch === '@' || ch === '$') {
                prefixPos = i;
                break;
            }
        }

        if (prefixPos >= 0) {
            // 替换前缀及其后面的内容
            var textBefore = val.substring(0, prefixPos);
            var textAfter = val.substring(prefixPos);

            // 找到前缀后面的空格位置（如果有）
            var spaceIndex = textAfter.indexOf(' ');
            var argsStr = '';
            if (spaceIndex >= 0) {
                argsStr = textAfter.substring(spaceIndex);
            }

            // 构建新的值（命令/技能/子代理名称后追加空格）
            inputEl.value = textBefore + trigger + cmd.name + ' ' + argsStr;

            // 更新光标位置到命令和空格后面
            var newCursorPos = textBefore.length + trigger.length + cmd.name.length + 1;
            inputEl.setSelectionRange(newCursorPos, newCursorPos);
        } else {
            // 如果没有找到前缀，直接在开头插入
            inputEl.value = trigger + cmd.name + ' ' + val;
            inputEl.setSelectionRange(trigger.length + cmd.name.length + 1, trigger.length + cmd.name.length + 1);
        }

        autoResize(inputEl);
    }
    hideCmdComplete();
}

function navigateCmdComplete(e, inputEl, completeEl) {
    var $completeEl = $(completeEl);
    if (!$completeEl.hasClass('show')) return false;
    // 输入法组合中，不处理命令补全的回车
    if (composing) return false;

    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        var $items = $completeEl.find('.cmd-complete-item');
        if ($items.length === 0) return true;

        // Remove old active
        if (cmdActiveIndex >= 0 && $items[cmdActiveIndex]) {
            $items.eq(cmdActiveIndex).removeClass('active');
        }

        if (e.key === 'ArrowDown') {
            cmdActiveIndex = (cmdActiveIndex + 1) % $items.length;
        } else {
            cmdActiveIndex = cmdActiveIndex <= 0 ? $items.length - 1 : cmdActiveIndex - 1;
        }

        $items.eq(cmdActiveIndex).addClass('active');
        $items[cmdActiveIndex].scrollIntoView({ block: 'nearest' });
        return true;
    }

    if (e.key === 'Tab' || (e.key === 'Enter' && cmdActiveIndex >= 0)) {
        e.preventDefault();
        applyCmdSelection(inputEl, completeEl);
        return true;
    }

    if (e.key === 'Escape') {
        hideCmdComplete();
        return true;
    }

    return false;
}

function handleInputForCommands(e) {
    var inputEl = e.target;
    var completeEl = (inputEl === newChatInput) ? $newChatCmdComplete[0] : $chatCmdComplete[0];
    var val = inputEl.value;

    if (val.indexOf('/') === 0 || val.indexOf('@') === 0 || val.indexOf('$') === 0) {
        // Only show completion when cursor is at the command/agent/skill name part (no spaces yet)
        var cursorPos = inputEl.selectionStart;
        var textBeforeCursor = val.substring(0, cursorPos);
        var spaceIndex = textBeforeCursor.indexOf(' ');
        if (spaceIndex === -1) {
            showCmdComplete(inputEl, completeEl, textBeforeCursor);
        } else {
            hideCmdComplete();
        }
    } else {
        hideCmdComplete();
        if ($chatHistoryPanel.hasClass('show')) {
            hideHistoryPanel();
        }
    }
}

// History button handler (toolbar / more menu)
$('#chatHistoryBtn').on('click', function(e) {
    e.stopPropagation();
    if ($chatHistoryPanel.hasClass('show')) {
        hideHistoryPanel();
    } else {
        showHistoryPanel();
    }
});

// Command & Agent button handlers
function triggerCmdComplete(inputEl, completeEl, prefix) {
    // 保存当前光标位置
    var cursorPos = inputEl.selectionStart;
    var textBefore = inputEl.value.substring(0, cursorPos);
    var textAfter = inputEl.value.substring(cursorPos);

    // 在光标位置插入前缀（命令/子代理/技能符号后追加空格）
    inputEl.value = textBefore + prefix + ' ' + textAfter;

    // 更新光标位置到前缀和空格后面
    var newCursorPos = cursorPos + prefix.length + 1;
    inputEl.setSelectionRange(newCursorPos, newCursorPos);

    inputEl.focus();
    showCmdComplete(inputEl, completeEl, prefix);
}
$('#newChatCmdBtn, #chatCmdBtn').on('click', function() {
    var isWelcome = this.id.indexOf('newChat') === 0;
    triggerCmdComplete(isWelcome ? newChatInput : chatInput, isWelcome ? $newChatCmdComplete[0] : $chatCmdComplete[0], '/');
});
$('#newChatAgentBtn, #chatAgentBtn').on('click', function() {
    var isWelcome = this.id.indexOf('newChat') === 0;
    triggerCmdComplete(isWelcome ? newChatInput : chatInput, isWelcome ? $newChatCmdComplete[0] : $chatCmdComplete[0], '@');
});
$('#newChatSkillBtn, #chatSkillBtn').on('click', function() {
    var isWelcome = this.id.indexOf('newChat') === 0;
    triggerCmdComplete(isWelcome ? newChatInput : chatInput, isWelcome ? $newChatCmdComplete[0] : $chatCmdComplete[0], '$');
});

$(newChatInput).on('input', handleInputForCommands);
$(chatInput).on('input', handleInputForCommands);

// composition 状态追踪（使用自定义标志解决 macOS 输入法选词 Enter 时序问题）
$(document).on('compositionstart', function() { composing = true; });
$(document).on('compositionend', function() { composing = false; });

// 在 textarea 光标处插入文本的辅助函数
function insertAtCursor(textarea, text) {
    var start = textarea.selectionStart;
    var end = textarea.selectionEnd;
    textarea.value = textarea.value.substring(0, start) + text + textarea.value.substring(end);
    textarea.selectionStart = textarea.selectionEnd = start + text.length;
    $(textarea).trigger('input');
}

// Keyboard navigation for command completion
$(newChatInput).on('keydown', function(e) {
    // 输入法正在组合中（如拼音选词），不触发发送
    if (isInputComposing(e)) return;
    var handled = navigateCmdComplete(e, newChatInput, $newChatCmdComplete[0]);
    if (handled) return;
    // 输入框为空 + 左/右键 → 切换循环任务面板
    if (!newChatInput.value.trim() && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
        e.preventDefault();
        if (typeof window.toggleLoopPanel === 'function') window.toggleLoopPanel();
        return;
    }
    if (e.key === 'Enter' && !e.shiftKey && !e.altKey) { e.preventDefault(); sendMessage(); return; }
    // Alt+Enter (macOS: Option+Enter) 换行
    if (e.key === 'Enter' && e.altKey) { e.preventDefault(); insertAtCursor(newChatInput, '\n'); }
});
$(chatInput).on('keydown', function(e) {
    // 输入法正在组合中（如拼音选词），不触发发送
    if (isInputComposing(e)) return;
    // ESC：输入为空时取消队尾并回填
    if (e.key === 'Escape') {
        var escSess = activeSessionId && sessionMap[activeSessionId];
        if (escSess && escSess.messageQueue && escSess.messageQueue.length
            && !chatInput.value.trim() && pendingFiles.length === 0) {
            e.preventDefault();
            if (typeof cancelLastQueuedToInput === 'function') cancelLastQueuedToInput(escSess);
            return;
        }
    }
    // 优先级1：命令补全导航
    var handled = navigateCmdComplete(e, chatInput, $chatCmdComplete[0]);
    if (handled) return;
    // 优先级2：历史面板导航（面板已打开时）
    handled = navigateHistory(e);
    if (handled) return;
    // 优先级3：任务运行中 Tab=加入排队（补全/历史面板均未激活；修饰键排除，Shift+Tab 保留原生反向焦点）。
    // 与 Enter=立即插话（steer）互补：排队在本轮结束后作为新任务发送（对齐 Codex v0.98 起的默认键位）
    if (e.key === 'Tab' && !e.shiftKey && !e.altKey && !e.ctrlKey && !e.metaKey) {
        var tabSess = activeSessionId && sessionMap[activeSessionId];
        if (tabSess && tabSess.isStreaming && !tabSess.stopRequested) {
            e.preventDefault();
            if (chatInput.value.trim() || pendingFiles.length) {
                enqueueMessage(tabSess, getInputText(), pendingFiles.slice());
                chatInput.focus();
            }
            return;
        }
    }
    // 触发条件：输入框为空 + 上/下键 → 打开历史面板
    if (!chatInput.value.trim() && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
        e.preventDefault();
        showHistoryPanel();
        return;
    }
    // 输入框为空 + 左/右键 → 切换循环任务面板
    if (!chatInput.value.trim() && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
        e.preventDefault();
        if (typeof window.toggleLoopPanel === 'function') window.toggleLoopPanel();
        return;
    }
    if (e.key === 'Enter' && !e.shiftKey && !e.altKey) { e.preventDefault(); sendMessage(); return; }
    // Alt+Enter (macOS: Option+Enter) 换行
    if (e.key === 'Enter' && e.altKey) { e.preventDefault(); insertAtCursor(chatInput, '\n'); }
});

// Click on completion item
$newChatCmdComplete.on('click', function(e) {
    var $item = $(e.target).closest('.cmd-complete-item');
    if ($item.length) {
        cmdActiveIndex = parseInt($item.attr('data-index'));
        applyCmdSelection(newChatInput, $newChatCmdComplete[0]);
        newChatInput.focus();
    }
});
$chatCmdComplete.on('click', function(e) {
    var $item = $(e.target).closest('.cmd-complete-item');
    if ($item.length) {
        cmdActiveIndex = parseInt($item.attr('data-index'));
        applyCmdSelection(chatInput, $chatCmdComplete[0]);
        chatInput.focus();
    }
});

// Hide on outside click
$(document).on('click', function(e) {
    var $target = $(e.target);
    if (!$target.closest('.cmd-complete, .history-panel, textarea, .more-menu, .more-menu-btn').length) {
        hideCmdComplete();
        hideHistoryPanel();
    }
});

/* ===== Input History Panel (chatInput only) ===== */
var $chatHistoryPanel = $('#chatHistoryPanel');
var historyActiveIndex = -1;

/**
 * 从当前会话 DOM 中提取用户发送过的文本，倒序返回（最新在前）
 * 返回 [{text, idx, time}]
 */
function extractUserMessages() {
    var sess = activeSessionId ? sessionMap[activeSessionId] : null;
    if (!sess) return [];
    var $rows = $(sess.container).find('.msg-row.user');
    var items = [];
    for (var i = $rows.length - 1; i >= 0; i--) {
        var $row = $($rows[i]);
        var $bubble = $row.find('.msg-bubble');
        if (!$bubble.length) continue;
        var $lastSpan = $bubble.find('.user-msg-text');
        var rawMd = $lastSpan.length ? ($lastSpan.attr('data-md-raw') || '').trim() : '';
        var text = rawMd || ($lastSpan.length ? $lastSpan.text().trim() : '');
        if (!text) continue;
        // 去重
        var dup = false;
        for (var j = 0; j < items.length; j++) {
            if (items[j].text === text) { dup = true; break; }
        }
        if (dup) continue;
        var idx = parseInt($row.attr('data-user-msg-idx'));
        var time = $bubble.find('.msg-time').text() || '';
        items.push({ text: text, idx: isNaN(idx) ? -1 : idx, time: time });
    }
    return items;
}

function showHistoryPanel() {
    closeAllToolbarPanels();
    var messages = extractUserMessages();
    if (messages.length === 0) {
        $chatHistoryPanel.html('<div class="history-panel-empty">' + I18n.t('history.emptyHistory') + '</div>');
    } else {
        var html = '<div class="history-panel-search">'
            + '<input type="text" class="history-search-input" placeholder="' + I18n.t('history.searchHistoryMessages') + '" />'
            + '</div>';
        html += '<div class="history-panel-list">';
        for (var i = 0; i < messages.length; i++) {
            var display = messages[i].text.length > 80
                ? messages[i].text.substring(0, 80) + '...'
                : messages[i].text;
            var timeStr = messages[i].time ? '<span class="history-item-time">' + escapeHtml(messages[i].time) + '</span>' : '';
            html += '<div class="history-panel-item" data-index="' + i + '" data-msg-idx="' + messages[i].idx + '">'
                + '<span class="history-item-text">' + escapeHtml(display) + '</span>'
                + '<span class="history-item-actions">'
                + timeStr
                + '<button class="history-locate-btn" title="' + I18n.t('history.locateMessage') + '">◎</button>'
                + '</span>'
                + '</div>';
        }
        html += '</div>';
        $chatHistoryPanel.html(html);

        // 绑定搜索过滤
        var $searchInput = $chatHistoryPanel.find('.history-search-input');
        $searchInput.on('input', function() {
            var query = this.value.trim().toLowerCase();
            var $items = $chatHistoryPanel.find('.history-panel-item');
            for (var k = 0; k < $items.length; k++) {
                var txt = $($items[k]).find('.history-item-text').text().toLowerCase();
                if (!query || txt.indexOf(query) >= 0) {
                    $($items[k]).show();
                } else {
                    $($items[k]).hide();
                }
            }
        });

        // 阻止搜索框按键冒泡，避免干扰历史面板导航
        $searchInput.on('keydown', function(e) {
            if (e.key === 'Escape') {
                hideHistoryPanel();
                chatInput.focus();
                e.stopPropagation();
                return;
            }
            e.stopPropagation();
        });
    }
    historyActiveIndex = -1;
    $chatHistoryPanel.addClass('show');
}

function hideHistoryPanel() {
    $chatHistoryPanel.removeClass('show');
    historyActiveIndex = -1;
}

function applyHistorySelection() {
    var messages = extractUserMessages();
    if (historyActiveIndex >= 0 && historyActiveIndex < messages.length) {
        chatInput.value = messages[historyActiveIndex].text;
        autoResize(chatInput);
    }
    hideHistoryPanel();
}

/**
 * 定位到指定 idx 的用户消息，平滑滚动并高亮闪烁
 */
function locateUserMessage(msgIdx) {
    if (isNaN(msgIdx) || msgIdx < 0) return;
    var sess = activeSessionId ? sessionMap[activeSessionId] : null;
    if (!sess) return;
    var $target = $(sess.container).find('.msg-row.user[data-user-msg-idx="' + msgIdx + '"]');
    if (!$target.length) return;

    // 先关闭历史面板
    hideHistoryPanel();

    // 滚动到目标消息
    $target[0].scrollIntoView({ behavior: 'smooth', block: 'center' });

    // 高亮闪烁
    $target.addClass('msg-highlight');
    setTimeout(function() {
        $target.removeClass('msg-highlight');
    }, 1800);
}

/**
 * 处理历史面板内的键盘导航，返回 true 表示已消费事件
 */
function navigateHistory(e) {
    if (!$chatHistoryPanel.hasClass('show')) return false;
    if (composing) return false;

    var $items = $chatHistoryPanel.find('.history-panel-item');

    if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
        e.preventDefault();
        if ($items.length === 0) return true;
        if (historyActiveIndex >= 0 && $items[historyActiveIndex]) {
            $items.eq(historyActiveIndex).removeClass('active');
        }
        if (e.key === 'ArrowDown') {
            historyActiveIndex = (historyActiveIndex + 1) % $items.length;
        } else {
            historyActiveIndex = historyActiveIndex <= 0
                ? $items.length - 1
                : historyActiveIndex - 1;
        }
        $items.eq(historyActiveIndex).addClass('active');
        $items[historyActiveIndex].scrollIntoView({ block: 'nearest' });
        return true;
    }

    if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault();
        applyHistorySelection();
        chatInput.focus();
        return true;
    }

    if (e.key === 'Escape') {
        hideHistoryPanel();
        return true;
    }

    return false;
}

// Click on history item — text area fills input, locate button jumps to message
$chatHistoryPanel.on('click', function(e) {
    var $locateBtn = $(e.target).closest('.history-locate-btn');
    if ($locateBtn.length) {
        var $item = $locateBtn.closest('.history-panel-item');
        var msgIdx = parseInt($item.attr('data-msg-idx'));
        if (!isNaN(msgIdx)) locateUserMessage(msgIdx);
        return;
    }
    var $item = $(e.target).closest('.history-panel-item');
    if ($item.length) {
        historyActiveIndex = parseInt($item.attr('data-index'));
        applyHistorySelection();
        chatInput.focus();
    }
});

/* ===== Model Selector ===== */
var modelList = [];        // [{name, desc, supportsReasoning, reasoningEfforts, ...}]
    var modelsLoaded = false;  // whether model list has been fetched
    var sessionModelMap = {};  // { sessionId: selectedModelName } — 仅会话，无全局
    var sessionReasoningMap = {}; // { sessionId: effort|'' } — 与 model 相同，仅会话
    var sessionThinkingMap = {}; // { sessionId: 'on'|'off'|'' } — 思考模式开关，独立于推理强度

function getEffortLabels() {
    return {
        auto: I18n.t('history.effortLabelAuto'),
        low: I18n.t('history.effortLabelLow'),
        medium: I18n.t('history.effortLabelMedium'),
        high: I18n.t('history.effortLabelHigh'),
        max: I18n.t('history.effortLabelMax')
    };
}
function getEffortHints() {
    return {
        auto: I18n.t('history.effortHintAuto'),
        low: I18n.t('history.effortHintLow'),
        medium: I18n.t('history.effortHintMedium'),
        high: I18n.t('history.effortHintHigh'),
        max: I18n.t('history.effortHintMax')
    };
}

// Get the effective selected model for current context
function getSelectedModel() {
    var sid = getSessionKey();
    if (sessionModelMap[sid]) {
        return sessionModelMap[sid];
    }
    return sessionModelMap['_default'] || '';
}

function getSessionKey() {
    return activeSessionId || SESSION_ID || '_default';
}

function getSelectedReasoning() {
    var sid = getSessionKey();
    if (sessionReasoningMap[sid] !== undefined) return sessionReasoningMap[sid] || '';
    return sessionReasoningMap['_default'] || '';
}

function getSelectedThinking() {
    var sid = getSessionKey();
    var v = (sessionThinkingMap[sid] !== undefined) ? sessionThinkingMap[sid] : (sessionThinkingMap['_default'] || '');
    // '' 表示未显式设置 → UI 显示开（不干预，跟随模型/effort 默认）；发送时为空则不携带参数
    return v === 'on' || v === 'off' ? v : '';
    }

function getCurrentModelMeta() {
    var name = getSelectedModel();
    for (var i = 0; i < modelList.length; i++) {
        if (modelList[i].name === name) return modelList[i];
    }
    // 防御：selected 不在列表（如默认模型被禁用导致 getModelOrDef 返回禁用模型）时，
    // 回退到第一个可用模型，保证思考模式/推理强度面板不因 meta=null 被隐藏。
    // 仅影响展示，不改变 sessionModelMap 中的实际选择。
    return modelList.length ? modelList[0] : null;
}

    function clampEffortForModel(effort, meta) {
    if (!effort) return '';
    if (!meta || !meta.supportsReasoning) return '';
    var list = meta.reasoningEfforts || [];
    if (!list.length) return effort;
    if (list.indexOf(effort) >= 0) return effort;
    var order = ['max', 'high', 'medium', 'low'];
    var start = order.indexOf(effort);
    if (start < 0) start = 0;
    for (var i = start; i < order.length; i++) {
        if (list.indexOf(order[i]) >= 0) return order[i];
    }
    for (var j = start - 1; j >= 0; j--) {
        if (list.indexOf(order[j]) >= 0) return order[j];
    }
    return '';
    }

    function parseModelItem(raw) {
    return {
        name: raw.name,
        model: raw.model,
        desc: raw.description,
        contextLength: raw.contextLength || 0,
        standard: raw.standard || '',
        supportsReasoning: !!raw.supportsReasoning,
        reasoningEfforts: raw.reasoningEfforts || [],
        defaultReasoningEffort: raw.defaultReasoningEffort || ''
    };
        }

        // Load model list (once) + selected model for given session
        function loadModels(sessionId, callback) {
    var url = '/web/chat/models';
    if (sessionId) url += '?sessionId=' + encodeURIComponent(sessionId);

    $.get(url, function(resp) {
        try {
            var data = resp.data || {};
            var selected = data.selected || '';
            var effort = data.reasoningEffort || '';
            var thinking = data.thinkingMode || '';

            // Store selected model / effort / thinking per session only (no global sticky)
            if (sessionId) {
                sessionModelMap[sessionId] = selected;
                sessionReasoningMap[sessionId] = effort;
                sessionThinkingMap[sessionId] = thinking;
            } else {
                sessionModelMap['_default'] = selected;
                sessionReasoningMap['_default'] = effort;
                sessionThinkingMap['_default'] = thinking;
            }

            // 加载子代理选择状态（与模型相同的会话绑定机制）
            var selectedAgent = data.selectedAgent || '';
            if (sessionId) {
                sessionAgentMap[sessionId] = selectedAgent;
            } else {
                sessionAgentMap['_default'] = selectedAgent;
            }

            // Only parse list once (it's the same for all sessions)
            if (!modelsLoaded) {
                modelList = [];
                var list = data.list || [];
                for (var i = 0; i < list.length; i++) {
                    modelList.push(parseModelItem(list[i]));
                }
                modelsLoaded = true;
            }

            // 初始加载（sessionId 为空）完成后：刷新恢复路径可能先于 models 返回执行了
            // setActiveSession，而当时 modelsLoaded=false 会跳过 refreshSessionModel，
            // 导致输入框模型面板停留在 _default（失真）。此处补拉活动会话的模型/子代理。
            if (!sessionId && activeSessionId && sessionModelMap[activeSessionId] === undefined) {
                refreshSessionModel(activeSessionId);
            }

            renderModelUI();
            renderAgentUI();
            if (callback) callback();
        } catch (e) {
            console.error('Failed to parse models:', e);
        }
    });
                    }

                function reloadModels(callback) {
    modelsLoaded = false;
    loadModels(activeSessionId || null, callback);
            }

        // Refresh model & agent UI for a specific session using local cache (no network request)
            function refreshSessionModel(sessionId) {
    if (!sessionId) return;
    // model 用 !== undefined 判断（空串表示“会话未显式选择、走默认”，也是有效缓存值）；
    // agent 空串表示 main，同样是有效缓存值。避免对默认模型会话反复发请求。
    var modelCached = sessionModelMap[sessionId] !== undefined;
    var agentCached = sessionAgentMap[sessionId] !== undefined;
    if (!modelCached || !agentCached) {
        var url = '/web/chat/models?sessionId=' + encodeURIComponent(sessionId);
        $.get(url, function(resp) {
            try {
                var data = resp.data || {};
                if (!modelCached) {
                    sessionModelMap[sessionId] = data.selected || '';
                    sessionReasoningMap[sessionId] = data.reasoningEffort || '';
                    sessionThinkingMap[sessionId] = data.thinkingMode || '';
                }
                if (!agentCached) {
                    sessionAgentMap[sessionId] = data.selectedAgent || '';
                }
                renderModelUI();
                renderAgentUI();
            } catch (e) {}
        });
    } else {
        // Already cached — just re-render UI
        renderModelUI();
        renderAgentUI();
    }
                }

function buildTriggerLabel(modelName, effort, showDepth, thinkingMode) {
    var parts = [];
    var displayName = modelName ? modelName : I18n.t('history.defaultModel');
    parts.push(displayName);
    if (showDepth) {
        if (thinkingMode === 'off') {
            parts.push(I18n.t('history.effortLabelOff', 'off'));
        } else if (thinkingMode === 'on') {
            parts.push('on');
        }
        var _el = getEffortLabels();
        if (effort && _el[effort]) {
            parts.push(_el[effort]);
        }
    }
    return parts.join(' · ');
}

function buildTriggerTitle(modelName, effort, showDepth, thinkingMode) {
    var bits = [];
    bits.push(I18n.t('history.modelLabel') + (modelName || I18n.t('history.defaultShort')));
    if (showDepth) {
        if (thinkingMode === 'off') {
            bits.push(I18n.t('toolbar.thinkingMode') + ': ' + I18n.t('history.effortLabelOff', 'off'));
        } else if (thinkingMode === 'on') {
            bits.push(I18n.t('toolbar.thinkingMode') + ': on');
        }
        var _el = getEffortLabels();
        if (effort && _el[effort]) {
            bits.push(I18n.t('history.reasoningEffortLabel') + _el[effort]);
            var _h = getEffortHints();
            if (_h[effort]) bits.push(_h[effort]);
        } else {
            bits.push(I18n.t('history.reasoningEffortAutoHint'));
        }
    }
    return bits.join(' · ');
}

function renderModelUI() {
    // 语言包未加载时推迟渲染，避免 getEffortLabels()/I18n.t() 返回 key 名后 removeAttr('data-i18n') 永久固化
    if (window.I18n && window.I18n.messages && !window.I18n.messages[window.I18n.locale || 'zh-CN']) {
        document.addEventListener('i18n:loaded', function _rl() {
            document.removeEventListener('i18n:loaded', _rl);
            renderModelUI();
        });
        return;
    }
    var $chatName = $('#chatModelName');
    var $newChatName = $('#newChatModelName');
    var $chatDropdown = $('#chatModelDropdown');
    var $newChatDropdown = $('#newChatModelDropdown');

    var currentModel = getSelectedModel();
    var userEffort = getSelectedReasoning(); // session user only ('' = auto)
    var meta = getCurrentModelMeta();
    // 与后端 ReasoningEffortSupport.resolveForUi 对齐：user > auto
    var displayEffort = '';

    if (meta && meta.supportsReasoning) {
        if (userEffort) {
            displayEffort = clampEffortForModel(userEffort, meta);
        } else {
            displayEffort = ''; // auto — do not paint default as selected
        }
    } else {
        displayEffort = '';
    }

    var showDepth = !!(meta && meta.supportsReasoning);
    var thinkingMode = showDepth ? getSelectedThinking() : '';
    var label = buildTriggerLabel(currentModel, displayEffort, showDepth, thinkingMode);
    var title = buildTriggerTitle(currentModel, displayEffort, showDepth, thinkingMode);
    if (modelList.length === 0) {
        label = I18n.t('llm.empty');
        title = I18n.t('llm.emptyDesc');
    }
    $chatName.text(label).removeAttr('data-i18n');
    $newChatName.text(label).removeAttr('data-i18n');
    $('#chatModelCurrent').attr('title', title);
    $('#newChatModelCurrent').attr('title', title);

    function buildDescLine(m) {
        var standard = m.standard || 'openai';
        var hasDesc = !!(m.desc);
        var parts = [];
        if (hasDesc) {
            parts.push(escapeHtml(m.desc));
        }
        parts.push('<span class="model-item-standard">[' + escapeHtml(standard) + ']</span>');
        return '<span class="model-item-desc">' + parts.join(' ') + '</span>';
    }

    var html = '';
    var isEmpty = modelList.length === 0;
    for (var i = 0; i < modelList.length; i++) {
        var m = modelList[i];
        var cls = m.name === currentModel ? ' active' : '';
        var ctxLen = m.contextLength ? (m.contextLength >= 1000000 && m.contextLength % 1000000 === 0 ? (m.contextLength / 1000000) + 'm' : (m.contextLength >= 1000 ? (m.contextLength / 1000) + 'k' : m.contextLength)) : '';
        html += '<div class="model-dropdown-item' + cls + '" data-model="' + escapeHtml(m.name) + '">'
            + '<span class="model-item-name">' + escapeHtml(m.name) + (ctxLen ? '<span class="model-item-ctx">' + ctxLen + '</span>' : '') + '</span>'
            + buildDescLine(m)
            + '</div>';
    }
    if (isEmpty) {
        html = '<div class="model-empty-state">'
            + '<div class="model-empty-title">' + escapeHtml(I18n.t('llm.empty')) + '</div>'
            + '<div class="model-empty-desc">' + escapeHtml(I18n.t('llm.emptyDesc')) + '</div>'
            + '<button type="button" class="model-empty-add-btn">' + escapeHtml(I18n.t('newchat.onboardingAdd')) + '</button>'
            + '</div>';
    }
    $('#chatModelCurrent, #newChatModelCurrent').toggleClass('is-empty', isEmpty);
    $chatDropdown.find('.model-search-input').toggle(!isEmpty);
    $newChatDropdown.find('.model-search-input').toggle(!isEmpty);
    $chatDropdown.find('.model-dropdown-items').html(html);
    $newChatDropdown.find('.model-dropdown-items').html(html);
    // Reset search when models re-render
    $chatDropdown.find('.model-search-input').val('');
    $newChatDropdown.find('.model-search-input').val('');
    $chatDropdown.find('.model-dropdown-items').children().show();
    $newChatDropdown.find('.model-dropdown-items').children().show();

    renderModelOptionRows($chatDropdown, meta, userEffort);
    renderModelOptionRows($newChatDropdown, meta, userEffort);
    updateModelOnboarding();
    }

var onboardingDismissed = false; // 用户主动处理（添加/跳过）后，本次会话不再自动弹出；不做 localStorage 持久化，只要从未配置过模型，每次启动都展示

function updateModelOnboarding() {    var $mask = $('#onboardingMask');
    if (!$mask.length) return;
    if (onboardingDismissed || modelList.length > 0) { $mask.hide(); return; }
    $mask.show();
}

window.updateModelOnboarding = updateModelOnboarding;

$(document)
    .on('click', '#onboardingAddBtn', function() {
        onboardingDismissed = true;
        $('#onboardingMask').hide();
        if (window.openSettingsTab) window.openSettingsTab('llm');
    })
    .on('click', '#onboardingSkipBtn', function() {
        onboardingDismissed = true;
        $('#onboardingMask').hide();
    })
    .on('keydown', function(e) {
        if (e.key === 'Escape' && $('#onboardingMask').is(':visible')) {
            onboardingDismissed = true;
            $('#onboardingMask').hide();
        }
    })
    .on('click', '.model-empty-add-btn', function() {
        var $sel = $(this).closest('.model-selector');
        if ($sel.length) $sel.removeClass('open'); // 用类控制显隐，避免内联 display:none 锁死下拉
        if (window.openSettingsTab) window.openSettingsTab('llm');
    });

function renderModelOptionRows($dropdown, meta, userEffort) {
    var $thinkingRow = $dropdown.find('.model-thinking-row');
    var $reasonRow = $dropdown.find('.model-reasoning-row');
    if (meta && meta.supportsReasoning) {
        $thinkingRow.show();
        var thinkingMode = getSelectedThinking() || 'auto'; // 'auto' | 'on' | 'off'
        $thinkingRow.find('button[data-mode]').each(function() {
            var m = $(this).attr('data-mode');
            $(this).toggleClass('active', m === thinkingMode);
        });

        $reasonRow.show();
        var allowed = meta.reasoningEfforts && meta.reasoningEfforts.length
            ? meta.reasoningEfforts
            : ['low', 'medium', 'high', 'max'];
        $reasonRow.find('button[data-effort]').each(function() {
            var e = $(this).attr('data-effort');
            if (e === 'auto') {
                $(this).show().toggleClass('active', !userEffort);
                return;
            }
            var ok = allowed.indexOf(e) >= 0;
            var isUser = ok && userEffort && e === userEffort;
            $(this).toggle(ok).toggleClass('active', !!isUser);
        });
        var hintKey = userEffort || 'auto';
        var _effortHints = getEffortHints();
        var hint = _effortHints[hintKey] || _effortHints.auto;
        $reasonRow.find('.model-option-hint').text(hint);
    } else {
        $thinkingRow.hide();
        $reasonRow.hide();
    }
}

function postModelSelect(payload) {
    return $.post('/web/chat/models/select', payload).fail(function(err) {
        console.error('Failed to select model options on server:', err);
    });
    }

function postAgentSelect(payload) {
    return $.post('/web/chat/agents/select', payload).fail(function(err) {
        console.error('Failed to select agent on server:', err);
    });
}

        function selectModel(modelName) {
    var sid = getSessionKey();
    // 与 model selected 一致：effort 只跟当前会话，不跨会话/全局 sticky
    // 切换模型时：若目标支持推理，则保留本会话当前档（含 auto）；否则清空
    var prevEffort = getSelectedReasoning();

    sessionModelMap[sid] = modelName;

    var meta = null;
    for (var i = 0; i < modelList.length; i++) {
        if (modelList[i].name === modelName) { meta = modelList[i]; break; }
    }

    var effort = '';
    if (meta && meta.supportsReasoning) {
        effort = clampEffortForModel(prevEffort || '', meta);
    } else {
        effort = '';
    }
    sessionReasoningMap[sid] = effort;

    renderModelUI();

    var data = { sessionId: sid, modelName: modelName };
    data.reasoningEffort = effort || '';
    postModelSelect(data);
}

function selectReasoning(effort) {
    var sid = getSessionKey();
    var meta = getCurrentModelMeta();
    var normalized = (effort === 'auto' || !effort || effort === 'none') ? '' : effort;
    var clamped = normalized ? clampEffortForModel(normalized, meta) : '';
    // 仅写会话，无全局 sticky（与 model selected 相同机制）
    sessionReasoningMap[sid] = clamped || '';
    renderModelUI();
    postModelSelect({
        sessionId: sid,
        modelName: getSelectedModel(),
        reasoningEffort: clamped || ''
    });
}

function selectThinking(mode) {
    var sid = getSessionKey();
    // 思考模式三态：auto（默认，空值）/ on / off
    var normalized = (mode === 'on' || mode === 'off') ? mode : '';
    sessionThinkingMap[sid] = normalized;
    renderModelUI();
    postModelSelect({
        sessionId: sid,
        modelName: getSelectedModel(),
        thinkingMode: normalized
    });
}

        // Toggle dropdown open/close
        function initModelSelector(selectorId, currentId, dropdownId) {
    var $selector = $('#' + selectorId);
    var $current = $('#' + currentId);
    var $dropdown = $('#' + dropdownId);
    if (!$selector.length || !$current.length || !$dropdown.length) return;

    $current.on('click', function(e) {
        e.stopPropagation();
        var opening = !$selector.hasClass('open');
        closeAllToolbarPanels();
        $selector.toggleClass('open', opening);
        if (opening) {
            requestAnimationFrame(function() {
                var activeItem = $dropdown.find('.model-dropdown-items .model-dropdown-item.active').get(0);
                if (activeItem) {
                    activeItem.scrollIntoView({ block: 'nearest', inline: 'nearest' });
                }
            });
        }
    });

    $dropdown.on('click', function(e) {
        var $thinkingPill = $(e.target).closest('.model-option-pills[data-kind="thinking"] button');
        if ($thinkingPill.length) {
            e.stopPropagation();
            e.preventDefault();
            var mode = $thinkingPill.attr('data-mode');
            if (mode) selectThinking(mode);
            return;
        }

        var $reasoningPill = $(e.target).closest('.model-option-pills[data-kind="reasoning"] button, .model-option-pills:not([data-kind]) button');
        if ($reasoningPill.length) {
            e.stopPropagation();
            e.preventDefault();
            var effort = $reasoningPill.attr('data-effort');
            if (effort) selectReasoning(effort);
            return;
        }

        var $item = $(e.target).closest('.model-dropdown-item');
        if (!$item.length) return;
        e.stopPropagation();
        var modelName = $item.attr('data-model');
        if (modelName && modelName !== getSelectedModel()) {
            selectModel(modelName);
        }
        $selector.removeClass('open');
    });
    }

    // Close all dropdowns on outside click
    $(document).on('click', function(e) {
    // Don't close if clicking inside model search area or option footer
    if ($(e.target).closest('.model-search-input, .model-search-wrap, .model-dropdown-footer').length) return;
    $('.model-selector.open').removeClass('open');
        });

    // Model search filtering
function initModelSearch(dropdownId) {
    var $dropdown = $('#' + dropdownId);
    var $searchInput = $dropdown.find('.model-search-input');
    if (!$searchInput.length) return;

    $searchInput.on('input', function() {
        var query = $(this).val().toLowerCase().trim();
        var $items = $dropdown.find('.model-dropdown-items').children();
        if (query === '') {
            $items.show();
            return;
        }
        $items.each(function() {
            var name = ($(this).attr('data-model') || '').toLowerCase();
            $(this).toggle(name.indexOf(query) !== -1);
        });
    });
}

        initModelSelector('chatModelSelector', 'chatModelCurrent', 'chatModelDropdown');
        initModelSelector('newChatModelSelector', 'newChatModelCurrent', 'newChatModelDropdown');
            initModelSearch('chatModelDropdown');
            initModelSearch('newChatModelDropdown');

            window.reloadModels = reloadModels;
            window.loadModels = loadModels;
            window.getSelectedReasoning = getSelectedReasoning;
            window.getSelectedThinking = getSelectedThinking;

        // Initial load (no specific session, get default selected)
loadModels(null);

/* ===== Agent Selector ===== */
var sessionAgentMap = {}; // { sessionId: selectedAgentName }, 空值表示 main

function getSelectedAgent() {
    var sid = getSessionKey();
    if (sessionAgentMap[sid] !== undefined) return sessionAgentMap[sid] || '';
    return sessionAgentMap['_default'] || '';
}
window.getSelectedAgent = getSelectedAgent;

function renderAgentUI() {
    var selected = getSelectedAgent();
    var label = selected || 'main';
    $('#chatAgentName, #newChatAgentName').text(label);
    $('#chatAgentCurrent, #newChatAgentCurrent').attr('title', selected ? (I18n.t('history.subagentLabel') + selected) : I18n.t('history.useMainAgent'));
    var html = '<button type="button" class="agent-dropdown-item' + (!selected ? ' active' : '') + '" data-agent=""><span class="agent-item-name">main</span><span class="agent-item-desc">' + I18n.t('history.mainAgentDesc') + '</span></button>';
    for (var i = 0; i < commandList.length; i++) {
        var item = commandList[i];
        if (item.type !== 'subagent') continue;
        html += '<button type="button" class="agent-dropdown-item' + (item.name === selected ? ' active' : '') + '" data-agent="' + escapeHtml(item.name) + '"><span class="agent-item-name">' + escapeHtml(item.name) + '</span><span class="agent-item-desc">' + escapeHtml(item.description || '') + '</span></button>';
    }
    $('#chatAgentDropdown, #newChatAgentDropdown').html(html);
}

function selectAgent(agentName) {
    var sid = getSessionKey();
    sessionAgentMap[sid] = agentName || '';
    renderAgentUI();
    // 选择时调后端记住（与模型选择器保持一致）
    postAgentSelect({ sessionId: sid, agentName: agentName || '' });
}

function initAgentSelector(selectorId, currentId, dropdownId) {
    var $selector = $('#' + selectorId);
    var $current = $('#' + currentId);
    var $dropdown = $('#' + dropdownId);
    $current.on('click', function(e) {
        e.stopPropagation();
        var opening = !$selector.hasClass('open');
        closeAllToolbarPanels();
        $selector.toggleClass('open', opening);
        $current.attr('aria-expanded', opening ? 'true' : 'false');
    });
    $dropdown.on('click', '.agent-dropdown-item', function(e) {
        e.stopPropagation();
        selectAgent($(this).attr('data-agent') || '');
        $selector.removeClass('open');
        $current.attr('aria-expanded', 'false');
    });
}
initAgentSelector('chatAgentSelector', 'chatAgentCurrent', 'chatAgentDropdown');
initAgentSelector('newChatAgentSelector', 'newChatAgentCurrent', 'newChatAgentDropdown');

/* ===== More Menu ===== */
function initMoreMenu(menuId, buttonId) {
    var $menu = $('#' + menuId);
    var $button = $('#' + buttonId);
    $button.on('click', function(e) {
        e.stopPropagation();
        var opening = !$menu.hasClass('open');
        closeAllToolbarPanels();
        $menu.toggleClass('open', opening);
        $button.attr('aria-expanded', opening ? 'true' : 'false');
    });
    $menu.find('.more-menu-dropdown').on('click', 'button', function() {
        $menu.removeClass('open');
        $button.attr('aria-expanded', 'false');
    });
}
initMoreMenu('chatMoreMenu', 'chatMoreBtn');
initMoreMenu('newChatMoreMenu', 'newChatMoreBtn');
$(document).on('keydown', function(e) {
    if (e.key === 'Escape') closeAllToolbarPanels();
});
$(document).on('click', function(e) {
    if (!$(e.target).closest('.agent-selector, .more-menu').length) {
        $('#chatAgentSelector, #newChatAgentSelector, #chatMoreMenu, #newChatMoreMenu').removeClass('open');
        $('#chatAgentCurrent, #newChatAgentCurrent, #chatMoreBtn, #newChatMoreBtn').attr('aria-expanded', 'false');
    }
});

/* ===== 消息导航条 ===== */
var _msgNavRafId = null;

function rebuildMsgNav() {
    var $nav = $('#msgNav');
    if (!$nav.length) return;

    var sess = activeSessionId ? sessionMap[activeSessionId] : null;
    if (!sess || !sess.container) { $nav.hide().empty(); return; }

    var $rows = $(sess.container).find('.msg-row.user');
    if ($rows.length === 0) { $nav.hide().empty(); return; }

    $nav.show().attr('aria-hidden', 'false');

    var html = '';
    var userIdx = 0;
    $rows.each(function() {
        var $row = $(this);
        var msgIdx = parseInt($row.attr('data-user-msg-idx'));
        var raw = $row.find('.user-msg-text').attr('data-md-raw') || '';
        userIdx++;
        var preview = '#' + userIdx + ' ' + raw.replace(/\n+/g, ' ').substring(0, 20) + (raw.length > 20 ? '\u2026' : '');
        html += '<div class="msg-nav-block"'
            + ' data-msg-idx="' + msgIdx + '"'
            + ' data-preview="' + escapeHtml(preview) + '"></div>';
    });
    $nav.html(html);
    updateMsgNavActive();
}

function updateMsgNavActive() {
    var $nav = $('#msgNav');
    if (!$nav.is(':visible')) return;
    var wrap = document.getElementById('msgWrap');
    if (!wrap) return;
    var sess = activeSessionId ? sessionMap[activeSessionId] : null;
    if (!sess || !sess.container) return;

    var scrollTop = wrap.scrollTop;
    var viewH = wrap.clientHeight;
    var center = scrollTop + viewH * 0.3;

    var closestIdx = -1;
    var closestDist = Infinity;
    $(sess.container).find('.msg-row.user').each(function() {
        var dist = Math.abs(this.offsetTop - center);
        if (dist < closestDist) {
            closestDist = dist;
            closestIdx = parseInt($(this).attr('data-user-msg-idx'));
        }
    });

    $nav.find('.msg-nav-block').each(function() {
        $(this).toggleClass('active', parseInt($(this).attr('data-msg-idx')) === closestIdx);
    });
}

function scheduleMsgNavRebuild() {
    if (_msgNavRafId) cancelAnimationFrame(_msgNavRafId);
    _msgNavRafId = requestAnimationFrame(function() {
        _msgNavRafId = null;
        rebuildMsgNav();
    });
}

// 面板延时关闭计时器（防止 nav↔panel 间隙触发误关）
var _navPanelTimer = null;
function _scheduleNavClose() {
    _navPanelTimer = setTimeout(function() {
        $('.msg-nav-panel').remove();
        $('#msgNav .msg-nav-block').removeClass('highlight');
    }, 150);
}
function _cancelNavClose() {
    if (_navPanelTimer) { clearTimeout(_navPanelTimer); _navPanelTimer = null; }
}

// ① 块直接点击定位
$(document).on('click', '.msg-nav-block', function() {
    var msgIdx = parseInt($(this).attr('data-msg-idx'));
    if (!isNaN(msgIdx)) locateUserMessage(msgIdx);
});

// ② 悬浮导航条 → 弹出列表面板（垂直居中）
$(document).on('mouseenter', '#msgNav', function() {
    if ($('.msg-nav-panel').length) return;
    var $blocks = $(this).find('.msg-nav-block');
    if (!$blocks.length) return;
    var html = '';
    $blocks.each(function() {
        html += '<div class="msg-nav-panel-item' + ($(this).hasClass('active') ? ' active' : '') + '"'
            + ' data-msg-idx="' + $(this).attr('data-msg-idx') + '">'
            + escapeHtml($(this).attr('data-preview') || '') + '</div>';
    });
    var navRect = this.getBoundingClientRect();
    // 先插入 DOM，再用实际高度反算居中
    var $panel = $('<div class="msg-nav-panel"></div>').html(html)
        .css({ right: (window.innerWidth - navRect.left) + 'px', top: '-9999px' })
        .on('mouseenter', _cancelNavClose)
        .on('mouseleave', function(e) {
            if (e.relatedTarget && $(e.relatedTarget).closest('#msgNav').length) {
                _cancelNavClose();
            } else {
                _scheduleNavClose();
            }
        })
        .appendTo('body');
    var panelH = $panel[0].offsetHeight;
    var centeredTop = navRect.top + navRect.height / 2 - panelH / 2;
    var clampedTop = Math.max(8, Math.min(centeredTop, window.innerHeight - panelH - 8));
    $panel.css('top', clampedTop + 'px');
    // ③ 修正箭头指向：clamp 后箭头仍对准导航条中心
    var navCenterY = navRect.top + navRect.height / 2;
    $panel[0].style.setProperty('--arrow-top', (navCenterY - clampedTop) + 'px');
    // ② 滚动面板内部使 active 项居中可见
    var $activeItem = $panel.find('.msg-nav-panel-item.active');
    if ($activeItem.length) {
        var itemOffset = $activeItem[0].offsetTop;
        var itemH = $activeItem[0].offsetHeight;
        $panel.scrollTop(itemOffset - panelH / 2 + itemH / 2);
    }

}).on('mouseleave', '#msgNav', function() {
    _scheduleNavClose();
});

// ③ 面板 item hover ↔ 对应块联动高亮（双向）
$(document).on('mouseenter', '.msg-nav-panel-item', function() {
    var idx = $(this).attr('data-msg-idx');
    $('#msgNav .msg-nav-block').removeClass('highlight');
    $('#msgNav .msg-nav-block[data-msg-idx="' + idx + '"]').addClass('highlight');
}).on('mouseleave', '.msg-nav-panel-item', function() {
    $('#msgNav .msg-nav-block').removeClass('highlight');
});

// ④ 块 hover → 面板 item 反向联动高亮
$(document).on('mouseenter', '.msg-nav-block', function() {
    var idx = $(this).attr('data-msg-idx');
    $('.msg-nav-panel-item').removeClass('highlight');
    $('.msg-nav-panel-item[data-msg-idx="' + idx + '"]').addClass('highlight');
}).on('mouseleave', '.msg-nav-block', function() {
    $('.msg-nav-panel-item').removeClass('highlight');
});

// ④ 面板 item 点击定位
$(document).on('click', '.msg-nav-panel-item', function() {
    var msgIdx = parseInt($(this).attr('data-msg-idx'));
    if (!isNaN(msgIdx)) { locateUserMessage(msgIdx); $('.msg-nav-panel').remove(); }
});

// 滚动时更新 active 态
$('#msgWrap').on('scroll.msgnav', function() {
    updateMsgNavActive();
});

window.scheduleMsgNavRebuild = scheduleMsgNavRebuild;

// 语言切换后重新渲染模型选择器（防止 apply() 覆盖模型名）
document.addEventListener('i18n:switched', function() {
    if (typeof modelsLoaded !== 'undefined' && modelsLoaded) {
        renderModelUI();
    }
});

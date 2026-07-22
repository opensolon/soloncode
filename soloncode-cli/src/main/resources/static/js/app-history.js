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
                chatHistory.push({ label: list[i].label, sessionId: list[i].sessionId });
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
    var label = (firstMsg || '新对话').toString();
    label = label.length > 30 ? label.substring(0, 30) + '...' : label;
    var shouldMakeCurrent = (makeCurrent !== false) && (sessionId === SESSION_ID || sessionId === activeSessionId || currentChatIndex === -1);
    for (var i = 0; i < chatHistory.length; i++) {
        if (chatHistory[i].sessionId === sessionId) {
            if (shouldMakeCurrent) currentChatIndex = i;
            updateHistoryUI();
            return;
        }
    }
    chatHistory.unshift({ label: label, sessionId: sessionId });
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
$(historyList).on('click', function(e) {
    var $target = $(e.target);
    var $delBtn = $target.closest('.sidebar-item-del');
    if ($delBtn.length) {
        e.stopPropagation();
        var idx = parseInt($delBtn.closest('.sidebar-item').attr('data-idx'));
        if (!isNaN(idx)) deleteSession(idx);
        return;
    }
    var $renameBtn = $target.closest('.sidebar-item-rename');
    if ($renameBtn.length) {
        e.stopPropagation();
        var idx = parseInt($renameBtn.closest('.sidebar-item').attr('data-idx'));
        if (!isNaN(idx)) startRename(idx);
        return;
    }
    var $forkBtn = $target.closest('.sidebar-item-fork');
    if ($forkBtn.length) {
        e.stopPropagation();
        var idx = parseInt($forkBtn.closest('.sidebar-item').attr('data-idx'));
        if (!isNaN(idx)) forkSession(idx);
        return;
    }
    var $item = $target.closest('.sidebar-item');
    if ($item.length) {
        var idx = parseInt($item.attr('data-idx'));
        if (!isNaN(idx)) selectSession(idx);
    }
});

var _updateHistoryUIPending = false;
function updateHistoryUI() {
    if (_updateHistoryUIPending) return;
    _updateHistoryUIPending = true;
    requestAnimationFrame(function() {
        _updateHistoryUIPending = false;
        var html = '';
        for (var i = 0; i < chatHistory.length; i++) {
            var sess = sessionMap[chatHistory[i].sessionId];
            var streaming = sess && sess.isStreaming;
            var cls = 'sidebar-item' + (i === currentChatIndex ? ' active' : '') + (streaming ? ' streaming' : '');

            html += '<div class="' + cls + '" data-idx="' + i + '">'
                + '<span class="sidebar-item-label">' + escapeHtml(chatHistory[i].label) + '</span>';
            // 任务进度 badge
            var todoInfo = window.sessionTodoMap && window.sessionTodoMap[chatHistory[i].sessionId];
            if (todoInfo && todoInfo.total > 0) {
                var doneClass = todoInfo.done === todoInfo.total ? ' done' : '';
                html += '<span class="sidebar-item-todo' + doneClass + '">' + todoInfo.done + '/' + todoInfo.total + '</span>';
            }
            if (streaming) {
                html += '<span class="sidebar-item-spinner" title="对话进行中..."></span>';
            }
            html += '<button class="sidebar-item-rename" title="重命名"><i class="layui-icon layui-icon-edit"></i></button>'
                + '<button class="sidebar-item-fork" title="复制对话"><svg width="14" height="14" viewBox="0 0 16 16" fill="currentColor"><path d="M5 5.372v.878c0 .414.336.75.75.75h4.5a.75.75 0 0 0 .75-.75v-.878a2.25 2.25 0 1 1 1.5 0v.878a2.25 2.25 0 0 1-2.25 2.25h-1.5v2.128a2.25 2.25 0 1 1-1.5 0v-2.128h-1.5A2.25 2.25 0 0 1 3.5 6.25v-.878a2.25 2.25 0 1 1 1.5 0ZM5 3.25a.75.75 0 1 0-1.5 0 .75.75 0 0 0 1.5 0Zm6.75.75a.75.75 0 1 0 0-1.5.75.75 0 0 0 0 1.5Zm-3 8.75a.75.75 0 1 0 0-1.5.75.75 0 0 0 0 1.5Z"/></svg></button>'
                + '<button class="sidebar-item-del" title="删除对话"><i class="layui-icon layui-icon-close"></i></button>'
                + '</div>';
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
    $item.find('.sidebar-item-rename').hide();
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
        $item.find('.sidebar-item-rename').show();
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

    layer.confirm('将当前对话完整复制一份作为新对话，\n原对话不受影响，你可以在此基础上继续提问。', {
        title: '复制对话',
        btn: ['复制', '取消'],
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
                    layer.msg('复制对话成功', { icon: 1, time: 2000, offset: '120px' });
                }
            } catch (e) {
                if (typeof layer !== 'undefined' && layer.msg) {
                    layer.msg('复制对话失败，请重试', { icon: 2, time: 3000, offset: '120px' });
                } else {
                    alert('复制对话失败，请重试');
                }
            }
        }).fail(function() {
            if (typeof layer !== 'undefined' && layer.msg) {
                layer.msg('复制对话失败，请重试', { icon: 2, time: 3000, offset: '120px' });
            } else {
                alert('复制对话失败，请重试');
            }
        });
    });
}

function deleteSession(idx) {
    var entry = chatHistory[idx];
    if (!entry) return;

    layer.confirm('确定删除对话 "' + (entry.label || '未命名') + '"？', { title: '确认删除', btn: ['删除', '取消'], icon: 3, offset: '120px' }, function(index) {
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
            layer.msg('删除对话失败，请重试', { icon: 2, time: 3000, offset: '120px' });
        } else {
            alert('删除对话失败，请重试');
        }
    });
    });
}

function selectSession(idx) {
    if (idx === currentChatIndex && inChatMode) return;
    var entry = chatHistory[idx];
    if (!entry) return;

    currentChatIndex = idx;
    SESSION_ID = entry.sessionId;
    rememberActiveSession(entry.sessionId);
    if (typeof closeDiffViewer === 'function') closeDiffViewer();
    if (!inChatMode) switchToChatMode();
    setActiveSession(entry.sessionId);
    updateHistoryUI();

    var sess = sessionMap[entry.sessionId];
    /* Only load from server if not streaming and container has no content */
    if (!sess.isStreaming && sess.container.children.length === 0) {
        loadMessages(sess);
    } else {
        scrollToBottom(true);
    }
}

function loadMessages(sess) {
    // 历史加载期间：流式 chunk 先缓存，加载完再回放，避免被 DOM 重建冲掉
    sess._loadingHistory = true;
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
                    appendUserMessage(sess, m.content, null, m.attachments, m.createdAt, m.sourceLabel);
                } else if (m.role === 'ASSISTANT') {
                    var isConsecutive = (i > 0 && msgs[i - 1].role === 'ASSISTANT');
                    if (!isConsecutive) resetStreamState(sess);
                    var el = ensureAssistantBubble(sess);
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
        } finally {
            sess._loadingHistory = false;
            // 回放加载期间缓存的流式 chunk（刷新后后端仍在推的内容）
            if (typeof flushPendingStreamChunks === 'function') {
                flushPendingStreamChunks(sess);
            }
        }
    }).fail(function() {
        sess._loadingHistory = false;
        if (typeof flushPendingStreamChunks === 'function') {
            flushPendingStreamChunks(sess);
        }
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
        } catch (e) {}
    });
}

// hints 非首屏必需，空闲时再拉，减少启动并发
if (window.requestIdleCallback) {
    requestIdleCallback(function() { loadCommands(); }, { timeout: 2500 });
} else {
    setTimeout(loadCommands, 600);
}

var $welcomeCmdComplete = $('#welcomeCmdComplete');
var $chatCmdComplete = $('#chatCmdComplete');
var cmdActiveIndex = -1;
var cmdVisibleItems = [];

function getActiveCmdComplete() {
    return inChatMode ? $chatCmdComplete[0] : $welcomeCmdComplete[0];
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
    $('#chatLoopPanel, #welcomeLoopPanel').hide();
    // 模型下拉
    $('#chatModelDropdown, #welcomeModelDropdown').removeClass('show');
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
            + '<input type="text" class="cmd-search-input" placeholder="搜索技能..." autocomplete="off" />'
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
    $welcomeCmdComplete.removeClass('show');
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
    var completeEl = (inputEl === welcomeInput) ? $welcomeCmdComplete[0] : $chatCmdComplete[0];
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

// History button handler (toolbar)
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
$('#welcomeCmdBtn').on('click', function() {
    triggerCmdComplete(welcomeInput, $welcomeCmdComplete[0], '/');
});
$('#chatCmdBtn').on('click', function() {
    triggerCmdComplete(chatInput, $chatCmdComplete[0], '/');
});
$('#welcomeAgentBtn').on('click', function() {
    triggerCmdComplete(welcomeInput, $welcomeCmdComplete[0], '@');
});
$('#chatAgentBtn').on('click', function() {
    triggerCmdComplete(chatInput, $chatCmdComplete[0], '@');
});
$('#welcomeSkillBtn').on('click', function() {
    triggerCmdComplete(welcomeInput, $welcomeCmdComplete[0], '$');
});
$('#chatSkillBtn').on('click', function() {
    triggerCmdComplete(chatInput, $chatCmdComplete[0], '$');
});

$(welcomeInput).on('input', handleInputForCommands);
$(chatInput).on('input', handleInputForCommands);

// composition 状态追踪（使用自定义标志解决 macOS 输入法选词 Enter 时序问题）
$(document).on('compositionstart', function() { composing = true; });
$(document).on('compositionend', function() { composing = false; });

// Keyboard navigation for command completion
$(welcomeInput).on('keydown', function(e) {
    // 输入法正在组合中（如拼音选词），不触发发送
    if (isInputComposing(e)) return;
    var handled = navigateCmdComplete(e, welcomeInput, $welcomeCmdComplete[0]);
    if (handled) return;
    if (e.key === 'Enter' && !e.shiftKey && !e.altKey) { e.preventDefault(); sendMessage(); }
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
    // 触发条件：输入框为空 + 上/下键 → 打开历史面板
    if (!chatInput.value.trim() && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
        e.preventDefault();
        showHistoryPanel();
        return;
    }
    if (e.key === 'Enter' && !e.shiftKey && !e.altKey) { e.preventDefault(); sendMessage(); }
});

// Click on completion item
$welcomeCmdComplete.on('click', function(e) {
    var $item = $(e.target).closest('.cmd-complete-item');
    if ($item.length) {
        cmdActiveIndex = parseInt($item.attr('data-index'));
        applyCmdSelection(welcomeInput, $welcomeCmdComplete[0]);
        welcomeInput.focus();
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
    if (!$target.closest('.cmd-complete').length && !$target.closest('.history-panel').length && !$target.closest('textarea').length && !$target.closest('#welcomeCmdBtn').length && !$target.closest('#welcomeAgentBtn').length && !$target.closest('#chatCmdBtn').length && !$target.closest('#chatAgentBtn').length && !$target.closest('#welcomeSkillBtn').length && !$target.closest('#chatSkillBtn').length && !$target.closest('#chatHistoryBtn').length) {
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
        $chatHistoryPanel.html('<div class="history-panel-empty">暂无输入历史</div>');
    } else {
        var html = '<div class="history-panel-search">'
            + '<input type="text" class="history-search-input" placeholder="搜索历史消息..." />'
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
                + '<button class="history-locate-btn" title="定位到该消息">◎</button>'
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

var EFFORT_LABELS = {
    auto: 'auto',
    low: 'low',
    medium: 'medium',
    high: 'high',
    max: 'max'
};
var EFFORT_HINTS = {
    auto: '跟随模型或供应商默认，通常最省心',
    low: '更快更省，适合简单问答',
    medium: '均衡表现，日常任务推荐',
    high: '更仔细分析，适合难问题',
    max: '最强思考，通常最慢也更费额度'
};

// Get the effective selected model for current context
function getSelectedModel() {
    if (activeSessionId && sessionModelMap[activeSessionId]) {
        return sessionModelMap[activeSessionId];
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

function getCurrentModelMeta() {
    var name = getSelectedModel();
    for (var i = 0; i < modelList.length; i++) {
        if (modelList[i].name === name) return modelList[i];
    }
    return null;
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
        name: raw.name || raw.model,
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

            // Store selected model / effort per session only (no global sticky)
            if (sessionId) {
                sessionModelMap[sessionId] = selected;
                sessionReasoningMap[sessionId] = effort;
            } else {
                sessionModelMap['_default'] = selected;
                sessionReasoningMap['_default'] = effort;
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

            renderModelUI();
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

        // Refresh model UI for a specific session using local cache (no network request)
            function refreshSessionModel(sessionId) {
    if (!sessionId) return;
    // If we haven't seen this session's model yet, fetch it from backend
    if (!sessionModelMap[sessionId]) {
        var url = '/web/chat/models?sessionId=' + encodeURIComponent(sessionId);
        $.get(url, function(resp) {
            try {
                var data = resp.data || {};
                sessionModelMap[sessionId] = data.selected || '';
                sessionReasoningMap[sessionId] = data.reasoningEffort || '';
                renderModelUI();
            } catch (e) {}
        });
    } else {
        // Already cached — just re-render UI
        renderModelUI();
    }
                }

function buildTriggerLabel(modelName, effort, showDepth) {
    var parts = [];
    var displayName = modelName
        ? (modelName.length > 18 ? modelName.substring(0, 18) + '...' : modelName)
        : '默认模型';
    parts.push(displayName);
    // 支持推理强度调节时始终展示档位，auto 显示英文词以便发现
    if (showDepth) {
        parts.push((effort && EFFORT_LABELS[effort]) || EFFORT_LABELS.auto);
    }
    return parts.join(' · ');
}

function buildTriggerTitle(modelName, effort, showDepth) {
    var bits = [];
    bits.push('模型: ' + (modelName || '默认'));
    if (showDepth) {
        if (effort && EFFORT_LABELS[effort]) {
            bits.push('推理强度: ' + EFFORT_LABELS[effort]);
            if (EFFORT_HINTS[effort]) bits.push(EFFORT_HINTS[effort]);
        } else {
            bits.push('推理强度: auto（跟随模型/供应商）');
        }
    }
    return bits.join(' · ');
}

function renderModelUI() {
    var $chatName = $('#chatModelName');
    var $welcomeName = $('#welcomeModelName');
    var $chatDropdown = $('#chatModelDropdown');
    var $welcomeDropdown = $('#welcomeModelDropdown');

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
    var label = buildTriggerLabel(currentModel, displayEffort, showDepth);
    var title = buildTriggerTitle(currentModel, displayEffort, showDepth);
    $chatName.text(label);
    $welcomeName.text(label);
    $('#chatModelCurrent').attr('title', title);
    $('#welcomeModelCurrent').attr('title', title);

    var html = '';
    for (var i = 0; i < modelList.length; i++) {
        var m = modelList[i];
        var cls = m.name === currentModel ? ' active' : '';
        var ctxLen = m.contextLength ? (m.contextLength >= 1000000 && m.contextLength % 1000000 === 0 ? (m.contextLength / 1000000) + 'm' : (m.contextLength >= 1000 ? (m.contextLength / 1000) + 'k' : m.contextLength)) : '';
        html += '<div class="model-dropdown-item' + cls + '" data-model="' + escapeHtml(m.name) + '">'
            + '<span class="model-item-name">' + escapeHtml(m.name) + (ctxLen ? '<span class="model-item-ctx">' + ctxLen + '</span>' : '') + '</span>'
            + (m.desc ? '<span class="model-item-desc">' + escapeHtml(m.desc) + '</span>' : '')
            + '</div>';
    }
    $chatDropdown.find('.model-dropdown-items').html(html);
    $welcomeDropdown.find('.model-dropdown-items').html(html);
    // Reset search when models re-render
    $chatDropdown.find('.model-search-input').val('');
    $welcomeDropdown.find('.model-search-input').val('');
    $chatDropdown.find('.model-dropdown-items').children().show();
    $welcomeDropdown.find('.model-dropdown-items').children().show();

    renderModelOptionRows($chatDropdown, meta, userEffort);
    renderModelOptionRows($welcomeDropdown, meta, userEffort);
    }

function renderModelOptionRows($dropdown, meta, userEffort) {
    var $reasonRow = $dropdown.find('.model-reasoning-row');
    if (meta && meta.supportsReasoning) {
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
        var hint = EFFORT_HINTS[hintKey] || EFFORT_HINTS.auto;
        $reasonRow.find('.model-option-hint').text(hint);
    } else {
        $reasonRow.hide();
    }
}

function postModelSelect(payload) {
    return $.post('/web/chat/models/select', payload).fail(function(err) {
        console.error('Failed to select model options on server:', err);
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
    var normalized = (effort === 'auto' || !effort) ? '' : effort;
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

        // Toggle dropdown open/close
        function initModelSelector(selectorId, currentId, dropdownId) {
    var $selector = $('#' + selectorId);
    var $current = $('#' + currentId);
    var $dropdown = $('#' + dropdownId);
    if (!$selector.length || !$current.length || !$dropdown.length) return;

    $current.on('click', function(e) {
        e.stopPropagation();
        // Close all other selectors
        $('.model-selector.open').each(function() {
            if (this.id !== selectorId) $(this).removeClass('open');
        });
        $selector.toggleClass('open');
        if ($selector.hasClass('open')) {
            requestAnimationFrame(function() {
                var activeItem = $dropdown.find('.model-dropdown-items .model-dropdown-item.active').get(0);
                if (activeItem) {
                    activeItem.scrollIntoView({ block: 'nearest', inline: 'nearest' });
                }
            });
        }
    });

    $dropdown.on('click', function(e) {
        var $pill = $(e.target).closest('.model-option-pills button');
        if ($pill.length) {
            e.stopPropagation();
            e.preventDefault();
            var effort = $pill.attr('data-effort');
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
        initModelSelector('welcomeModelSelector', 'welcomeModelCurrent', 'welcomeModelDropdown');
            initModelSearch('chatModelDropdown');
            initModelSearch('welcomeModelDropdown');

            window.reloadModels = reloadModels;
            window.loadModels = loadModels;
            window.getSelectedReasoning = getSelectedReasoning;

        // Initial load (no specific session, get default selected)
loadModels(null);

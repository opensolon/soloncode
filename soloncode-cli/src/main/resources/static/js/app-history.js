/* ===== app-history.js ===== */
/* 数据管理：会话历史 + 命令系统 + 输入历史 + 模型选择 */
/* 依赖：app-base.js */

/* ===== History ===== */

/* 记住“当前活动会话”，刷新或下次打开时自动恢复 */
/* 自定义 composing 标志，替代 e.isComposing（macOS 输入法组合态下 Enter 时序问题） */
var composing = false;

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
        if (e.key === 'Enter') { e.preventDefault(); $input[0].blur(); }
        if (e.key === 'Escape') { $input.val(currentLabel); $input[0].blur(); }
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
    $.get('/web/chat/messages?sessionId=' + encodeURIComponent(sess.sessionId), function(resp) {
        try {
            var msgs = resp.data;
            var realContainer = sess.container;
            // 用临时容器批量构建 DOM，避免逐条 append 触发多次 layout
            var tempDiv = document.createElement('div');
            sess.container = tempDiv;
            resetStreamState(sess);
            for (var i = 0; i < msgs.length; i++) {
                var m = msgs[i];
                if (m.role === 'USER') {
                    resetStreamState(sess);
                    appendUserMessage(sess, m.content, null, null, m.createdAt);
                } else if (m.role === 'ASSISTANT') {
                    var isConsecutive = (i > 0 && msgs[i - 1].role === 'ASSISTANT');
                    if (!isConsecutive) resetStreamState(sess);
                    var el = ensureAssistantBubble(sess);
                    sess.reasonBuffer = isConsecutive ? sess.reasonBuffer + '\n\n' + m.content : m.content;
                    el.setAttribute('data-md-raw', sess.reasonBuffer);
                    $(el).html(renderMd(sess.reasonBuffer));
                    if (typeof addCodeBlockButtons === 'function') addCodeBlockButtons(el);
                    // 不在此处逐条调用 highlightCodeBlocks；循环结束后统一对真实容器调用一次
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
        }
    });
}

/* Load on startup */
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

loadCommands();

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
$(welcomeInput).on('compositionstart', function() { composing = true; });
$(welcomeInput).on('compositionend', function() { composing = false; });
$(chatInput).on('compositionstart', function() { composing = true; });
$(chatInput).on('compositionend', function() { composing = false; });

// Keyboard navigation for command completion
$(welcomeInput).on('keydown', function(e) {
    // 输入法正在组合中（如拼音选词），不触发发送
    if (composing) return;
    var handled = navigateCmdComplete(e, welcomeInput, $welcomeCmdComplete[0]);
    if (handled) return;
    if (e.key === 'Enter' && !e.shiftKey && !e.altKey) { e.preventDefault(); sendMessage(); }
});
$(chatInput).on('keydown', function(e) {
    // 输入法正在组合中（如拼音选词），不触发发送
    if (composing) return;
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
var modelList = [];        // [{name, desc}, ...] (shared, only loaded once)
var modelsLoaded = false;  // whether model list has been fetched
var sessionModelMap = {};  // { sessionId: selectedModelName }

// Get the effective selected model for current context
function getSelectedModel() {
    if (activeSessionId && sessionModelMap[activeSessionId]) {
        return sessionModelMap[activeSessionId];
    }
    return sessionModelMap['_default'] || '';
}

// Load model list (once) + selected model for given session
function loadModels(sessionId, callback) {
    var url = '/web/chat/models';
    if (sessionId) url += '?sessionId=' + encodeURIComponent(sessionId);

    $.get(url, function(resp) {
        try {
            var data = resp.data || {};
            var selected = data.selected || '';

            // Store selected model per session
            if (sessionId) {
                sessionModelMap[sessionId] = selected;
            } else {
                sessionModelMap['_default'] = selected;
            }

            // Only parse list once (it's the same for all sessions)
            if (!modelsLoaded) {
                modelList = [];
                var list = data.list || [];
                for (var i = 0; i < list.length; i++) {
                    modelList.push({ name: list[i].name || list[i].model, desc: list[i].description, contextLength: list[i].contextLength || 0 });
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
                var data = resp.data;
                sessionModelMap[sessionId] = data.selected || '';
                renderModelUI();
            } catch (e) {}
        });
    } else {
        // Already cached — just re-render UI
        renderModelUI();
    }
}

function renderModelUI() {
    var $chatName = $('#chatModelName');
    var $welcomeName = $('#welcomeModelName');
    var $chatDropdown = $('#chatModelDropdown');
    var $welcomeDropdown = $('#welcomeModelDropdown');

    var currentModel = getSelectedModel();
    var displayName = currentModel.length > 24 ? currentModel.substring(0, 24) + '...' : currentModel;
    $chatName.text(displayName || '默认模型');
    $welcomeName.text(displayName || '默认模型');

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
}

function selectModel(modelName) {
    var sid = activeSessionId || SESSION_ID;
    sessionModelMap[sid] = modelName;
    renderModelUI();

    // 立即通知服务端绑定模型选择，确保不走 /web/chat/input 的命令（如 /git、循环任务等）也能感知到模型变更
    $.post('/web/chat/models/select', {
        sessionId: sid,
        modelName: modelName
    }).fail(function(err) {
        console.error('Failed to select model on server:', err);
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
    });

    $dropdown.on('click', function(e) {
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
    // Don't close if clicking inside model search area
    if ($(e.target).closest('.model-search-input, .model-search-wrap').length) return;
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

// Initial load (no specific session, get default selected)
loadModels(null);

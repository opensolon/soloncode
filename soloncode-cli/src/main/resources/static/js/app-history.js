/* ===== app-history.js ===== */
/* 数据管理：会话历史 + 命令系统 + 输入历史 + 模型选择 */
/* 依赖：app-base.js */

/* ===== History ===== */
function loadSessionHistory() {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/chat/sessions', true);
    xhr.onreadystatechange = function() {
        if (xhr.readyState === 4 && xhr.status === 200) {
            try {
                var list = JSON.parse(xhr.responseText).data;
                chatHistory = [];
                for (var i = 0; i < list.length; i++) {
                    chatHistory.push({ label: list[i].label, sessionId: list[i].sessionId });
                }
                updateHistoryUI();
            } catch (e) {}
        }
    };
    xhr.send();
}

function saveChatToHistory(firstMsg) {
    var label = firstMsg.length > 30 ? firstMsg.substring(0, 30) + '...' : firstMsg;
    for (var i = 0; i < chatHistory.length; i++) {
        if (chatHistory[i].sessionId === SESSION_ID) {
            currentChatIndex = i;
            updateHistoryUI();
            return;
        }
    }
    chatHistory.unshift({ label: label, sessionId: SESSION_ID });
    if (chatHistory.length > 50) chatHistory.pop();
    currentChatIndex = 0;
    updateHistoryUI();
}

/* Sidebar event delegation — single listener instead of per-item binding */
historyList.addEventListener('click', function(e) {
    var delBtn = e.target.closest('.sidebar-item-del');
    if (delBtn) {
        e.stopPropagation();
        var idx = parseInt(delBtn.closest('.sidebar-item').getAttribute('data-idx'));
        if (!isNaN(idx)) deleteSession(idx);
        return;
    }
    var renameBtn = e.target.closest('.sidebar-item-rename');
    if (renameBtn) {
        e.stopPropagation();
        var idx = parseInt(renameBtn.closest('.sidebar-item').getAttribute('data-idx'));
        if (!isNaN(idx)) startRename(idx);
        return;
    }
    var item = e.target.closest('.sidebar-item');
    if (item) {
        var idx = parseInt(item.getAttribute('data-idx'));
        if (!isNaN(idx)) selectSession(idx);
    }
});

function updateHistoryUI() {
    var frag = document.createDocumentFragment();
    for (var i = 0; i < chatHistory.length; i++) {
        var item = document.createElement('div');
        var sess = sessionMap[chatHistory[i].sessionId];
        var streaming = sess && sess.isStreaming;
        item.className = 'sidebar-item' + (i === currentChatIndex ? ' active' : '') + (streaming ? ' streaming' : '');
        item.setAttribute('data-idx', i);

        var label = document.createElement('span');
        label.className = 'sidebar-item-label';
        label.textContent = chatHistory[i].label;

        var delBtn = document.createElement('button');
        delBtn.className = 'sidebar-item-del';
        delBtn.title = '删除对话';
        delBtn.innerHTML = '<i class="layui-icon layui-icon-close"></i>';

        item.appendChild(label);
        if (streaming) {
            var spinner = document.createElement('span');
            spinner.className = 'sidebar-item-spinner';
            spinner.title = '对话进行中...';
            item.appendChild(spinner);
        }
        var renameBtn = document.createElement('button');
        renameBtn.className = 'sidebar-item-rename';
        renameBtn.title = '重命名';
        renameBtn.innerHTML = '<i class="layui-icon layui-icon-edit"></i>';
        item.appendChild(renameBtn);
        item.appendChild(delBtn);
        frag.appendChild(item);
    }
    historyList.innerHTML = '';
    historyList.appendChild(frag);
}

function startRename(idx) {
    var item = historyList.querySelector('.sidebar-item[data-idx="' + idx + '"]');
    if (!item) return;
    var labelEl = item.querySelector('.sidebar-item-label');
    if (!labelEl) return;

    var currentLabel = chatHistory[idx].label.replace(/\.\.\.$/, '');
    var input = document.createElement('input');
    input.type = 'text';
    input.className = 'sidebar-rename-input';
    input.value = currentLabel;
    input.maxLength = 50;

    labelEl.style.display = 'none';
    item.querySelector('.sidebar-item-rename').style.display = 'none';
    item.insertBefore(input, labelEl);
    input.focus();
    input.select();

    function finishRename() {
        var newLabel = input.value.trim();
        if (newLabel && newLabel !== currentLabel) {
            newLabel = newLabel.length > 30 ? newLabel.substring(0, 30) + '...' : newLabel;
            chatHistory[idx].label = newLabel;

            var xhr = new XMLHttpRequest();
            xhr.open('POST', '/chat/sessions/rename', true);
            xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
            xhr.send('sessionId=' + encodeURIComponent(chatHistory[idx].sessionId) + '&label=' + encodeURIComponent(newLabel));
        }
        input.remove();
        labelEl.style.display = '';
        var renameBtn = item.querySelector('.sidebar-item-rename');
        if (renameBtn) renameBtn.style.display = '';
        updateHistoryUI();
    }

    input.addEventListener('blur', finishRename);
    input.addEventListener('keydown', function(e) {
        if (e.key === 'Enter') { e.preventDefault(); input.blur(); }
        if (e.key === 'Escape') { input.value = currentLabel; input.blur(); }
    });
}

function deleteSession(idx) {
    var entry = chatHistory[idx];
    if (!entry) return;

    var xhr = new XMLHttpRequest();
    xhr.open('POST', '/chat/sessions/delete?sessionId=' + encodeURIComponent(entry.sessionId), true);
    xhr.onreadystatechange = function() {
        if (xhr.readyState === 4) {
            /* Clean up session state after server confirms */
            var sess = sessionMap[entry.sessionId];
            if (sess) {
                if (sess.eventSource) sess.eventSource.close();
                if (sess.silenceTimer) clearTimeout(sess.silenceTimer);
                if (sess.contentRafId) cancelAnimationFrame(sess.contentRafId);
                if (sess.reasonRafId) cancelAnimationFrame(sess.reasonRafId);
                if (sess.container.parentNode) sess.container.parentNode.removeChild(sess.container);
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
        }
    };
    xhr.send();
}

function selectSession(idx) {
    if (idx === currentChatIndex && inChatMode) return;
    var entry = chatHistory[idx];
    if (!entry) return;

    currentChatIndex = idx;
    SESSION_ID = entry.sessionId;
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
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/chat/messages?sessionId=' + encodeURIComponent(sess.sessionId), true);
    xhr.onreadystatechange = function() {
        if (xhr.readyState === 4 && xhr.status === 200) {
            try {
                var msgs = JSON.parse(xhr.responseText).data;
                sess.container.innerHTML = '';
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
                        el.innerHTML = renderMd(sess.reasonBuffer);
                        // 显示时间戳（连续助手消息取最后一条的时间）
                        setAssistantTime(sess, m.createdAt);
                    }
                }
                resetStreamState(sess);
                if (sess.sessionId === activeSessionId) scrollToBottom(true);
            } catch (e) {}
        }
    };
    xhr.send();
}

/* Load on startup */
loadSessionHistory();

/* ===== Command System ===== */
var commandList = []; // [{name, description, type}, ...]
var commandsLoaded = false;
var cmdTrigger = null; // '/' for commands, '@' for subagents

function loadCommands() {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/chat/hints', true);
    xhr.onreadystatechange = function() {
        if (xhr.readyState === 4 && xhr.status === 200) {
            try {
                var resp = JSON.parse(xhr.responseText);
                commandList = resp.data || [];
                commandsLoaded = true;
            } catch (e) {}
        }
    };
    xhr.send();
}

loadCommands();

var welcomeCmdComplete = document.getElementById('welcomeCmdComplete');
var chatCmdComplete = document.getElementById('chatCmdComplete');
var cmdActiveIndex = -1;
var cmdVisibleItems = [];

function getActiveCmdComplete() {
    return inChatMode ? chatCmdComplete : welcomeCmdComplete;
}

function showCmdComplete(inputEl, completeEl, prefix) {
    if (!commandsLoaded || commandList.length === 0) return;

    var trigger = prefix.charAt(0);
    var query = prefix.substring(1).toLowerCase();
    var filterType = (trigger === '@') ? 'subagent' : 'command';
    cmdVisibleItems = [];
    var html = '';

    for (var i = 0; i < commandList.length; i++) {
        var cmd = commandList[i];
        // Filter by type based on trigger
        if (cmd.type !== filterType) continue;
        if (cmd.name.toLowerCase().indexOf(query) === 0 || query.length === 0) {
            cmdVisibleItems.push(cmd);
            var nameClass = (trigger === '@') ? 'cmd-name subagent' : 'cmd-name';
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
    completeEl.innerHTML = html;
    completeEl.classList.add('show');
}

function hideCmdComplete() {
    welcomeCmdComplete.classList.remove('show');
    chatCmdComplete.classList.remove('show');
    cmdActiveIndex = -1;
    cmdVisibleItems = [];
    cmdTrigger = null;
}

function applyCmdSelection(inputEl, completeEl) {
    if (cmdActiveIndex >= 0 && cmdActiveIndex < cmdVisibleItems.length) {
        var cmd = cmdVisibleItems[cmdActiveIndex];
        var trigger = cmdTrigger || '/';
        var parts = inputEl.value.trim().split(/\s+/);
        // Keep existing args after command name
        var argsStr = parts.length > 1 ? parts.slice(1).join(' ') : '';
        inputEl.value = trigger + cmd.name + (argsStr ? ' ' + argsStr : ' ');
        autoResize(inputEl);
    }
    hideCmdComplete();
}

function navigateCmdComplete(e, inputEl, completeEl) {
    if (!completeEl.classList.contains('show')) return false;
    // 输入法组合中，不处理命令补全的回车
    if (e.isComposing) return false;

    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        var items = completeEl.querySelectorAll('.cmd-complete-item');
        if (items.length === 0) return true;

        // Remove old active
        if (cmdActiveIndex >= 0 && items[cmdActiveIndex]) {
            items[cmdActiveIndex].classList.remove('active');
        }

        if (e.key === 'ArrowDown') {
            cmdActiveIndex = (cmdActiveIndex + 1) % items.length;
        } else {
            cmdActiveIndex = cmdActiveIndex <= 0 ? items.length - 1 : cmdActiveIndex - 1;
        }

        items[cmdActiveIndex].classList.add('active');
        items[cmdActiveIndex].scrollIntoView({ block: 'nearest' });
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
    var completeEl = (inputEl === welcomeInput) ? welcomeCmdComplete : chatCmdComplete;
    var val = inputEl.value;

    if (val.indexOf('/') === 0 || val.indexOf('@') === 0) {
        // Only show completion when cursor is at the command/agent name part (no spaces yet)
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
        if (chatHistoryPanel.classList.contains('show')) {
            hideHistoryPanel();
        }
    }
}

// Command & Agent button handlers
function triggerCmdComplete(inputEl, completeEl, prefix) {
    inputEl.value = prefix;
    inputEl.focus();
    showCmdComplete(inputEl, completeEl, prefix);
}
document.getElementById('welcomeCmdBtn').addEventListener('click', function() {
    triggerCmdComplete(welcomeInput, welcomeCmdComplete, '/');
});
document.getElementById('chatCmdBtn').addEventListener('click', function() {
    triggerCmdComplete(chatInput, chatCmdComplete, '/');
});
document.getElementById('welcomeAgentBtn').addEventListener('click', function() {
    triggerCmdComplete(welcomeInput, welcomeCmdComplete, '@');
});
document.getElementById('chatAgentBtn').addEventListener('click', function() {
    triggerCmdComplete(chatInput, chatCmdComplete, '@');
});

welcomeInput.addEventListener('input', handleInputForCommands);
chatInput.addEventListener('input', handleInputForCommands);

// Keyboard navigation for command completion
welcomeInput.addEventListener('keydown', function(e) {
    // 输入法正在组合中（如拼音选词），不触发发送
    if (e.isComposing) return;
    var handled = navigateCmdComplete(e, welcomeInput, welcomeCmdComplete);
    if (handled) return;
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
});
chatInput.addEventListener('keydown', function(e) {
    // 输入法正在组合中（如拼音选词），不触发发送
    if (e.isComposing) return;
    // 优先级1：命令补全导航
    var handled = navigateCmdComplete(e, chatInput, chatCmdComplete);
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
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
});

// Click on completion item
welcomeCmdComplete.addEventListener('click', function(e) {
    var item = e.target.closest('.cmd-complete-item');
    if (item) {
        cmdActiveIndex = parseInt(item.getAttribute('data-index'));
        applyCmdSelection(welcomeInput, welcomeCmdComplete);
        welcomeInput.focus();
    }
});
chatCmdComplete.addEventListener('click', function(e) {
    var item = e.target.closest('.cmd-complete-item');
    if (item) {
        cmdActiveIndex = parseInt(item.getAttribute('data-index'));
        applyCmdSelection(chatInput, chatCmdComplete);
        chatInput.focus();
    }
});

// Hide on outside click
document.addEventListener('click', function(e) {
    if (!e.target.closest('.cmd-complete') && !e.target.closest('.history-panel') && !e.target.closest('textarea') && !e.target.closest('#welcomeCmdBtn') && !e.target.closest('#welcomeAgentBtn') && !e.target.closest('#chatCmdBtn') && !e.target.closest('#chatAgentBtn')) {
        hideCmdComplete();
        hideHistoryPanel();
    }
});

/* ===== Input History Panel (chatInput only) ===== */
var chatHistoryPanel = document.getElementById('chatHistoryPanel');
var historyActiveIndex = -1;

/**
 * 从当前会话 DOM 中提取用户发送过的文本，倒序返回（最新在前）
 */
function extractUserMessages() {
    var sess = activeSessionId ? sessionMap[activeSessionId] : null;
    if (!sess) return [];
    var rows = sess.container.querySelectorAll('.msg-row.user');
    var texts = [];
    for (var i = rows.length - 1; i >= 0; i--) {
        var bubble = rows[i].querySelector('.msg-bubble');
        if (!bubble) continue;
        // 用户文本在 .msg-bubble 内的 .user-msg-text 中
        var lastSpan = bubble.querySelector('.user-msg-text');
        var text = lastSpan ? lastSpan.textContent.trim() : '';
        if (text && texts.indexOf(text) === -1) {
            texts.push(text);
        }
    }
    return texts;
}

function showHistoryPanel() {
    var messages = extractUserMessages();
    if (messages.length === 0) {
        chatHistoryPanel.innerHTML = '<div class="history-panel-empty">暂无输入历史</div>';
    } else {
        var html = '';
        for (var i = 0; i < messages.length; i++) {
            var display = messages[i].length > 80
                ? messages[i].substring(0, 80) + '...'
                : messages[i];
            html += '<div class="history-panel-item" data-index="' + i + '">'
                + escapeHtml(display)
                + '</div>';
        }
        chatHistoryPanel.innerHTML = html;
    }
    // 确保互斥：关闭命令补全
    hideCmdComplete();
    historyActiveIndex = -1;
    chatHistoryPanel.classList.add('show');
}

function hideHistoryPanel() {
    chatHistoryPanel.classList.remove('show');
    historyActiveIndex = -1;
}

function applyHistorySelection() {
    var messages = extractUserMessages();
    if (historyActiveIndex >= 0 && historyActiveIndex < messages.length) {
        chatInput.value = messages[historyActiveIndex];
        autoResize(chatInput);
    }
    hideHistoryPanel();
}

/**
 * 处理历史面板内的键盘导航，返回 true 表示已消费事件
 */
function navigateHistory(e) {
    if (!chatHistoryPanel.classList.contains('show')) return false;
    if (e.isComposing) return false;

    var items = chatHistoryPanel.querySelectorAll('.history-panel-item');

    if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
        e.preventDefault();
        if (items.length === 0) return true;
        if (historyActiveIndex >= 0 && items[historyActiveIndex]) {
            items[historyActiveIndex].classList.remove('active');
        }
        if (e.key === 'ArrowDown') {
            historyActiveIndex = (historyActiveIndex + 1) % items.length;
        } else {
            historyActiveIndex = historyActiveIndex <= 0
                ? items.length - 1
                : historyActiveIndex - 1;
        }
        items[historyActiveIndex].classList.add('active');
        items[historyActiveIndex].scrollIntoView({ block: 'nearest' });
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

// Click on history item
chatHistoryPanel.addEventListener('click', function(e) {
    var item = e.target.closest('.history-panel-item');
    if (item) {
        historyActiveIndex = parseInt(item.getAttribute('data-index'));
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
    var url = '/chat/models';
    if (sessionId) url += '?sessionId=' + encodeURIComponent(sessionId);

    var xhr = new XMLHttpRequest();
    xhr.open('GET', url, true);
    xhr.onreadystatechange = function() {
        if (xhr.readyState === 4 && xhr.status === 200) {
            try {
                var resp = JSON.parse(xhr.responseText).data;
                var selected = resp.selected || '';

                // Store selected model per session
                if (sessionId) {
                    sessionModelMap[sessionId] = selected;
                } else {
                    sessionModelMap['_default'] = selected;
                }

                // Only parse list once (it's the same for all sessions)
                if (!modelsLoaded) {
                    modelList = [];
                    var list = resp.list || [];
                    for (var i = 0; i < list.length; i++) {
                        modelList.push({ name: list[i].model, desc: list[i].description });
                    }
                    modelsLoaded = true;
                }

                renderModelUI();
                if (callback) callback();
            } catch (e) {
                console.error('Failed to parse models:', e);
            }
        }
    };
    xhr.send();
}

// Refresh model UI for a specific session using local cache (no network request)
function refreshSessionModel(sessionId) {
    if (!sessionId) return;
    // If we haven't seen this session's model yet, fetch it from backend
    if (!sessionModelMap[sessionId]) {
        var url = '/chat/models?sessionId=' + encodeURIComponent(sessionId);
        var xhr = new XMLHttpRequest();
        xhr.open('GET', url, true);
        xhr.onreadystatechange = function() {
            if (xhr.readyState === 4 && xhr.status === 200) {
                try {
                    var resp = JSON.parse(xhr.responseText).data;
                    sessionModelMap[sessionId] = resp.selected || '';
                    renderModelUI();
                } catch (e) {}
            }
        };
        xhr.send();
    } else {
        // Already cached — just re-render UI
        renderModelUI();
    }
}

function renderModelUI() {
    var chatName = document.getElementById('chatModelName');
    var welcomeName = document.getElementById('welcomeModelName');
    var chatDropdown = document.getElementById('chatModelDropdown');
    var welcomeDropdown = document.getElementById('welcomeModelDropdown');

    var currentModel = getSelectedModel();
    var displayName = currentModel.length > 24 ? currentModel.substring(0, 24) + '...' : currentModel;
    if (chatName) chatName.textContent = displayName || '默认模型';
    if (welcomeName) welcomeName.textContent = displayName || '默认模型';

    var html = '';
    for (var i = 0; i < modelList.length; i++) {
        var m = modelList[i];
        var cls = m.name === currentModel ? ' active' : '';
        html += '<div class="model-dropdown-item' + cls + '" data-model="' + escapeHtml(m.name) + '">'
            + '<span class="model-item-name">' + escapeHtml(m.name) + '</span>'
            + (m.desc ? '<span class="model-item-desc">' + escapeHtml(m.desc) + '</span>' : '')
            + '</div>';
    }
    if (chatDropdown) chatDropdown.innerHTML = html;
    if (welcomeDropdown) welcomeDropdown.innerHTML = html;
}

function selectModel(modelName) {
    var sid = activeSessionId || SESSION_ID;
    sessionModelMap[sid] = modelName;
    renderModelUI();
}

// Toggle dropdown open/close
function initModelSelector(selectorId, currentId, dropdownId) {
    var selector = document.getElementById(selectorId);
    var current = document.getElementById(currentId);
    var dropdown = document.getElementById(dropdownId);
    if (!selector || !current || !dropdown) return;

    current.addEventListener('click', function(e) {
        e.stopPropagation();
        // Close all other selectors
        document.querySelectorAll('.model-selector.open').forEach(function(el) {
            if (el.id !== selectorId) el.classList.remove('open');
        });
        selector.classList.toggle('open');
    });

    dropdown.addEventListener('click', function(e) {
        var item = e.target.closest('.model-dropdown-item');
        if (!item) return;
        e.stopPropagation();
        var modelName = item.getAttribute('data-model');
        if (modelName && modelName !== getSelectedModel()) {
            selectModel(modelName);
        }
        selector.classList.remove('open');
    });
}

// Close all dropdowns on outside click
document.addEventListener('click', function() {
    document.querySelectorAll('.model-selector.open').forEach(function(el) {
        el.classList.remove('open');
    });
});

initModelSelector('chatModelSelector', 'chatModelCurrent', 'chatModelDropdown');
initModelSelector('welcomeModelSelector', 'welcomeModelCurrent', 'welcomeModelDropdown');

// Initial load (no specific session, get default selected)
loadModels(null);

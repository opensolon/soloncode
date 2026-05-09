/* ===== DOM ===== */
var welcomeView = document.getElementById('welcomeView');
var chatView = document.getElementById('chatView');
var messagesWrap = document.getElementById('messagesWrap');
var welcomeInput = document.getElementById('welcomeInput');
var welcomeSendBtn = document.getElementById('welcomeSendBtn');
var chatInput = document.getElementById('chatInput');
var chatSendBtn = document.getElementById('chatSendBtn');
var themeBtn = document.getElementById('themeBtn');
var themeIcon = document.getElementById('themeIcon');
var newChatBtn = document.getElementById('newChatBtn');
var historyList = document.getElementById('historyList');

/* ===== Constants ===== */
var DOTS_HTML = '<span class="thinking-dots"><span></span><span></span><span></span></span>';

/* ===== Per-Session State ===== */
function SessionState(sessionId) {
    this.sessionId = sessionId;
    this.container = document.createElement('div');
    this.container.className = 'messages-inner';
    this.container.style.display = 'none';
    messagesWrap.appendChild(this.container);
    this.eventSource = null;
    this.isStreaming = false;
    this.currentBubbleEl = null;
    this.reasonBuffer = '';
    this.thinkingBlockEl = null;
    this.thinkingBodyMdEl = null;
    this.thinkingBodyWrapEl = null;
    this.thinkingBuffer = '';
    this.pendingToolCard = null;
    this.thinkingEl = null;
    this.inlineThinkingEl = null;
    this.silenceTimer = null;
    this.contentRafId = null;
    this.reasonRafId = null;
    this.thinkingTimerId = null;
    this.thinkingStartTime = null;
    this.inlineThinkingTimerId = null;
    this.inlineThinkingStartTime = null;
    this.thinkingBlockTimerId = null;
    this.thinkingBlockStartTime = null;
}

var sessionMap = {};
var activeSessionId = null;

/* ===== Global State ===== */
var SESSION_ID = 'web-' + Date.now().toString(36);
setActiveSession(SESSION_ID);
var isStreaming = false;
var inChatMode = false;
var chatHistory = [];
var currentChatIndex = -1;
var pendingFiles = []; // [{ type: 'image'|'file', name, size, file, dataUrl?, attachmentsType: 'image'|'file' }, ...]
var MAX_ATTACHMENTS = 10;
var userScrolledUp = false;

/* ===== Session Helpers ===== */
function getOrCreateSession(sessionId) {
    if (!sessionMap[sessionId]) {
        sessionMap[sessionId] = new SessionState(sessionId);
    }
    return sessionMap[sessionId];
}

function setActiveSession(sessionId) {
    if (activeSessionId && sessionMap[activeSessionId]) {
        sessionMap[activeSessionId].container.style.display = 'none';
    }
    var sess = getOrCreateSession(sessionId);
    sess.container.style.display = '';
    activeSessionId = sessionId;
    SESSION_ID = sessionId;
    isStreaming = sess.isStreaming;
    userScrolledUp = false;
    if (isStreaming) setBtnStopMode();
    else setBtnSendMode();
    // Refresh model selector for this session
    if (modelsLoaded) refreshSessionModel(sessionId);
}

function deactivateSession() {
    if (activeSessionId && sessionMap[activeSessionId]) {
        sessionMap[activeSessionId].container.style.display = 'none';
    }
    activeSessionId = null;
    isStreaming = false;
    setBtnSendMode();
}

/* ===== Attachment Helpers ===== */
var welcomeAttachmentsWrap = document.getElementById('welcomeAttachmentsWrap');
var chatAttachmentsWrap = document.getElementById('chatAttachmentsWrap');

function handlePasteImage(e) {
    var items = (e.clipboardData || e.originalEvent.clipboardData).items;
    for (var i = 0; i < items.length; i++) {
        if (items[i].type.indexOf('image') !== -1) {
            e.preventDefault();
            var file = items[i].getAsFile();
            processSelectedFile(file, 'image');
            return;
        }
    }
}

function getAttachmentsWrap() {
    return inChatMode ? chatAttachmentsWrap : welcomeAttachmentsWrap;
}

function renderAttachments() {
    // Render both wraps to keep them in sync when switching views
    renderAttachmentsWrap(welcomeAttachmentsWrap);
    renderAttachmentsWrap(chatAttachmentsWrap);
}

function renderAttachmentsWrap(wrap) {
    wrap.innerHTML = '';
    if (pendingFiles.length === 0) {
        wrap.classList.remove('has-items');
        return;
    }
    wrap.classList.add('has-items');
    for (var i = 0; i < pendingFiles.length; i++) {
        var item = pendingFiles[i];
        var el = document.createElement('div');
        el.className = 'attachment-item';
        var typeTag = '<span class="attachment-type-tag ' + (item.attachmentsType || 'file') + '">' + (item.attachmentsType === 'image' ? '多模态' : '文件') + '</span>';
        if (item.type === 'image') {
            el.innerHTML = '<img src="' + item.dataUrl + '"/>'
                + typeTag
                + '<button class="attachment-item-remove" data-idx="' + i + '">&times;</button>';
        } else {
            el.innerHTML = '<div class="attachment-item-file">'
                + '<span class="file-icon">📎</span>'
                + '<span class="file-name">' + escapeHtml(item.name) + '</span>'
                + '</div>'
                + typeTag
                + '<button class="attachment-item-remove" data-idx="' + i + '">&times;</button>';
        }
        wrap.appendChild(el);
    }
}

function clearAttachmentPreview() {
    pendingFiles = [];
    renderAttachments();
}

function removeAttachment(idx) {
    pendingFiles.splice(idx, 1);
    renderAttachments();
}



function formatFileSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function processSelectedFile(file, attachmentsType) {
    if (!file) return;
    if (pendingFiles.length >= MAX_ATTACHMENTS) return;

    if (attachmentsType === 'image') {
        // Image attachment: always treated as multimodal image
        var reader = new FileReader();
        reader.onload = function(evt) {
            pendingFiles.push({ type: 'image', name: file.name, size: file.size, file: file, dataUrl: evt.target.result, attachmentsType: 'image' });
            renderAttachments();
        };
        reader.readAsDataURL(file);
    } else if (file.type.indexOf('image') !== -1) {
        // File attachment + image file: show preview but mark as file type
        var reader = new FileReader();
        reader.onload = function(evt) {
            pendingFiles.push({ type: 'image', name: file.name, size: file.size, file: file, dataUrl: evt.target.result, attachmentsType: 'file' });
            renderAttachments();
        };
        reader.readAsDataURL(file);
    } else {
        pendingFiles.push({ type: 'file', name: file.name, size: file.size, file: file, attachmentsType: 'file' });
        renderAttachments();
    }
}

function processSelectedFiles(fileList, attachmentsType) {
    for (var i = 0; i < fileList.length; i++) {
        if (pendingFiles.length >= MAX_ATTACHMENTS) break;
        processSelectedFile(fileList[i], attachmentsType);
    }
}

welcomeInput.addEventListener('paste', handlePasteImage);
chatInput.addEventListener('paste', handlePasteImage);

// Attachment remove buttons - use event delegation on both wraps
welcomeAttachmentsWrap.addEventListener('click', function(e) {
    var btn = e.target.closest('.attachment-item-remove');
    if (btn) removeAttachment(parseInt(btn.getAttribute('data-idx')));
});
chatAttachmentsWrap.addEventListener('click', function(e) {
    var btn = e.target.closest('.attachment-item-remove');
    if (btn) removeAttachment(parseInt(btn.getAttribute('data-idx')));
});

// Attach button handlers
document.getElementById('welcomeAttachBtn').addEventListener('click', function(e) {
    e.stopPropagation();
    document.getElementById('welcomeAttachInput').click();
});
document.getElementById('chatAttachBtn').addEventListener('click', function(e) {
    e.stopPropagation();
    document.getElementById('chatAttachInput').click();
});
document.getElementById('welcomeAttachInput').addEventListener('change', function(e) {
    if (e.target.files && e.target.files.length > 0) processSelectedFiles(e.target.files, 'file');
    e.target.value = '';
});
document.getElementById('chatAttachInput').addEventListener('change', function(e) {
    if (e.target.files && e.target.files.length > 0) processSelectedFiles(e.target.files, 'file');
    e.target.value = '';
});
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

// Image button handlers
document.getElementById('welcomeImageBtn').addEventListener('click', function(e) {
    e.stopPropagation();
    document.getElementById('welcomeImageInput').click();
});
document.getElementById('chatImageBtn').addEventListener('click', function(e) {
    e.stopPropagation();
    document.getElementById('chatImageInput').click();
});
document.getElementById('welcomeImageInput').addEventListener('change', function(e) {
    if (e.target.files && e.target.files.length > 0) processSelectedFiles(e.target.files, 'image');
    e.target.value = '';
});
document.getElementById('chatImageInput').addEventListener('change', function(e) {
    if (e.target.files && e.target.files.length > 0) processSelectedFiles(e.target.files, 'image');
    e.target.value = '';
});

/* ===== Marked ===== */
if (typeof marked !== 'undefined') { marked.setOptions({ breaks: true, gfm: true }); }
function renderMd(text) {
    if (typeof marked !== 'undefined') return marked.parse(text);
    return text.replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\n/g,'<br>');
}

/* ===== Theme ===== */
var currentTheme = localStorage.getItem('chat-theme') || 'light';
document.body.setAttribute('data-theme', currentTheme);
updateThemeIcon();
themeBtn.addEventListener('click', function() {
    currentTheme = currentTheme === 'light' ? 'dark' : 'light';
    document.body.setAttribute('data-theme', currentTheme);
    localStorage.setItem('chat-theme', currentTheme);
    updateThemeIcon();
});
function updateThemeIcon() {
    themeIcon.innerHTML = currentTheme === 'light' ? '&#xe6c2;' : '&#xe748;';
    themeBtn.title = currentTheme === 'light' ? '切换至暗色' : '切换至浅色';
}

/* ===== View Switch ===== */
function switchToChatMode() {
    if (inChatMode) return;
    inChatMode = true;
    welcomeView.style.display = 'none';
    chatView.classList.add('active');
    chatInput.focus();
}
function switchToWelcomeMode() {
    inChatMode = false;
    SESSION_ID = 'web-' + Date.now().toString(36);
    setActiveSession(SESSION_ID);
    welcomeView.style.display = '';
    chatView.classList.remove('active');
    welcomeInput.focus();
    // Reset model UI to new session
    if (modelsLoaded) renderModelUI();
}

/* ===== Auto-resize ===== */
function autoResize(el) {
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 140) + 'px';
}
welcomeInput.addEventListener('input', function() { autoResize(this); });
chatInput.addEventListener('input', function() { autoResize(this); });

/* ===== Send from both inputs ===== */
function getInputText() {
    if (inChatMode) return chatInput.value.trim();
    return welcomeInput.value.trim();
}
function clearInput() {
    if (inChatMode) { chatInput.value = ''; chatInput.style.height = 'auto'; }
    else { welcomeInput.value = ''; welcomeInput.style.height = 'auto'; }
}

welcomeSendBtn.addEventListener('click', function() { sendMessage(); });
chatSendBtn.addEventListener('click', function() {
    if (isStreaming && activeSessionId && sessionMap[activeSessionId]) {
        try {
            //提交 interrupt
            let xhr = new XMLHttpRequest();
            xhr.open('POST', '/chat/interrupt?sessionId=' + encodeURIComponent(activeSessionId), true);
            xhr.send();
        }finally {
            // 停止流
            finishStream(sessionMap[activeSessionId]);
        }
    } else {
        sendMessage();
    }
});

/* ===== Click to focus ===== */
document.querySelector('.welcome-input-box').addEventListener('click', function(e) {
    if (!e.target.closest('button')) welcomeInput.focus();
});
document.querySelector('.input-box').addEventListener('click', function(e) {
    if (!e.target.closest('button')) chatInput.focus();
});

/* ===== New Chat ===== */
newChatBtn.addEventListener('click', function() {
    currentChatIndex = -1;
    switchToWelcomeMode();
    updateHistoryUI();
});

/* ===== Helpers ===== */
messagesWrap.addEventListener('scroll', function() {
    var gap = messagesWrap.scrollHeight - messagesWrap.scrollTop - messagesWrap.clientHeight;
    userScrolledUp = gap > 80;
});
var scrollRafPending = false;
function scrollToBottom(force) {
    if (!force && userScrolledUp) return;
    if (force) userScrolledUp = false;
    if (scrollRafPending) return;
    scrollRafPending = true;
    requestAnimationFrame(function() {
        scrollRafPending = false;
        messagesWrap.scrollTop = messagesWrap.scrollHeight;
    });
}

function resetStreamState(sess) {
    sess.currentBubbleEl = null;
    sess.reasonBuffer = '';
    sess.thinkingBlockEl = null;
    sess.thinkingBodyMdEl = null;
    sess.thinkingBodyWrapEl = null;
    sess.thinkingBuffer = '';
    if (sess.contentRafId) { cancelAnimationFrame(sess.contentRafId); sess.contentRafId = null; }
    if (sess.reasonRafId) { cancelAnimationFrame(sess.reasonRafId); sess.reasonRafId = null; }
}

function setBtnStopMode() {
    chatSendBtn.disabled = false;
    chatSendBtn.classList.add('stop-mode');
    chatSendBtn.innerHTML = '<div class="stop-icon"></div>';
    chatSendBtn.title = '停止生成';
}
function setBtnSendMode() {
    chatSendBtn.classList.remove('stop-mode');
    chatSendBtn.innerHTML = '<i class="layui-icon layui-icon-release"></i>';
    chatSendBtn.title = '发送';
    chatSendBtn.disabled = false;
}

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
        item.appendChild(delBtn);
        frag.appendChild(item);
    }
    historyList.innerHTML = '';
    historyList.appendChild(frag);
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
                        appendUserMessage(sess, m.content);
                    } else if (m.role === 'ASSISTANT') {
                        var isConsecutive = (i > 0 && msgs[i - 1].role === 'ASSISTANT');
                        if (!isConsecutive) resetStreamState(sess);
                        var el = ensureAssistantBubble(sess);
                        sess.reasonBuffer = isConsecutive ? sess.reasonBuffer + '\n\n' + m.content : m.content;
                        el.innerHTML = renderMd(sess.reasonBuffer);
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

/* ===== Voice Input (Web Speech API) - 按住说话（类似微信） ===== */
var SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
var recognition = null;
var voiceRecording = false;
var voiceTargetInput = null; // 当前语音目标 textarea
var voiceBaseText = '';      // 开始录音时 textarea 已有文本
var voiceFinalTranscript = ''; // 累计的最终识别文本

var welcomeVoiceBtn = document.getElementById('welcomeVoiceBtn');
var chatVoiceBtn = document.getElementById('chatVoiceBtn');

var voiceRafPending = false; // 限制 DOM 更新频率

function initVoice() {
    if (!SpeechRecognition) return; // 浏览器不支持
    recognition = new SpeechRecognition();
    recognition.lang = 'zh-CN';
    recognition.continuous = true; // 按住期间持续识别
    recognition.interimResults = true;
    recognition.maxAlternatives = 1;

    recognition.onresult = function(event) {
        var interimTranscript = '';
        var finalTranscript = '';
        for (var i = event.resultIndex; i < event.results.length; i++) {
            var transcript = event.results[i][0].transcript;
            if (event.results[i].isFinal) {
                finalTranscript += transcript;
            } else {
                interimTranscript += transcript;
            }
        }
        // 累积最终结果
        if (finalTranscript) {
            voiceFinalTranscript += finalTranscript;
        }
        // 用 RAF 节流 DOM 更新，避免频繁重绘拖慢感知
        if (!voiceRafPending && voiceTargetInput) {
            voiceRafPending = true;
            requestAnimationFrame(function() {
                voiceRafPending = false;
                if (voiceTargetInput) {
                    voiceTargetInput.value = voiceBaseText + voiceFinalTranscript + interimTranscript;
                    autoResize(voiceTargetInput);
                }
            });
        }
    };

    recognition.onerror = function(event) {
        console.warn('Speech recognition error:', event.error);
        stopVoiceRecording();
    };

    recognition.onend = function() {
        // 如果还在按住状态（voiceRecording），自动重启继续识别
        if (voiceRecording) {
            try { recognition.start(); } catch(e) {}
        } else {
            stopVoiceRecording();
        }
    };

    // 显示语音按钮
    welcomeVoiceBtn.classList.remove('hidden');
    chatVoiceBtn.classList.remove('hidden');
}

function startVoiceRecording(inputEl) {
    if (!recognition) return;
    if (voiceRecording) return;

    voiceTargetInput = inputEl;
    voiceBaseText = inputEl.value;
    voiceFinalTranscript = '';
    voiceRecording = true;

    try { recognition.start(); } catch(e) {}

    // 更新按钮状态
    var btn = (inputEl === welcomeInput) ? welcomeVoiceBtn : chatVoiceBtn;
    btn.classList.add('recording');
    btn.title = '松开结束';
}

function stopVoiceRecording() {
    if (!voiceRecording && !recognition) return;
    voiceRecording = false;
    try { if (recognition) recognition.stop(); } catch(e) {}

    // 更新按钮状态
    welcomeVoiceBtn.classList.remove('recording');
    chatVoiceBtn.classList.remove('recording');
    welcomeVoiceBtn.title = '按住说话';
    chatVoiceBtn.title = '按住说话';

    // 保留识别到的文本，重置基线以便下次追加
    if (voiceTargetInput) {
        voiceBaseText = voiceTargetInput.value;
    }
    voiceFinalTranscript = '';
    voiceTargetInput = null;
}

// --- 按住说话：按下开始录音，松开结束（类似微信） ---
function bindVoiceHold(btn, inputEl) {
    // 鼠标：按下开始，松开结束
    btn.addEventListener('mousedown', function(e) {
        e.preventDefault();
        startVoiceRecording(inputEl);
    });
    btn.addEventListener('mouseup', function(e) {
        e.preventDefault();
        stopVoiceRecording();
    });
    btn.addEventListener('mouseleave', function() {
        if (voiceRecording) stopVoiceRecording();
    });

    // 触摸：按下开始，松开结束
    btn.addEventListener('touchstart', function(e) {
        e.preventDefault();
        startVoiceRecording(inputEl);
    });
    btn.addEventListener('touchend', function(e) {
        e.preventDefault();
        stopVoiceRecording();
    });
    btn.addEventListener('touchcancel', function() {
        if (voiceRecording) stopVoiceRecording();
    });
}

bindVoiceHold(welcomeVoiceBtn, welcomeInput);
bindVoiceHold(chatVoiceBtn, chatInput);

initVoice();

/* ===== Command System ===== */
var commandList = []; // [{name, description, type}, ...]
var commandsLoaded = false;
var cmdTrigger = null; // '/' for commands, '@' for subagents

function loadCommands() {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/chat/commands', true);
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
    }
}

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
    var handled = navigateCmdComplete(e, chatInput, chatCmdComplete);
    if (handled) return;
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
    if (!e.target.closest('.cmd-complete') && !e.target.closest('textarea') && !e.target.closest('#welcomeCmdBtn') && !e.target.closest('#welcomeAgentBtn') && !e.target.closest('#chatCmdBtn') && !e.target.closest('#chatAgentBtn')) {
        hideCmdComplete();
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

/* ===== Message Rendering (Session-Aware) ===== */
function appendUserMessage(sess, text, imageDataUrls, fileAttachments) {
    var row = document.createElement('div');
    row.className = 'msg-row user';
    row.innerHTML = '<button class="user-copy-btn" title="复制"><i class="layui-icon layui-icon-file"></i></button><div class="msg-bubble"></div><div class="msg-avatar">我</div>';
    var bubble = row.querySelector('.msg-bubble');

    // Multiple images
    if (imageDataUrls && imageDataUrls.length > 0) {
        var imgWrap = document.createElement('div');
        imgWrap.style.cssText = 'display:flex;flex-wrap:wrap;gap:6px;margin-bottom:8px;';
        for (var i = 0; i < imageDataUrls.length; i++) {
            var img = document.createElement('img');
            img.src = imageDataUrls[i].dataUrl || imageDataUrls[i];
            img.style.cssText = 'max-height:120px;max-width:200px;border-radius:8px;object-fit:cover;';
            imgWrap.appendChild(img);
        }
        bubble.appendChild(imgWrap);
    }

    // Multiple file attachments
    if (fileAttachments && fileAttachments.length > 0) {
        for (var j = 0; j < fileAttachments.length; j++) {
            var tag = document.createElement('div');
            tag.style.cssText = 'display:flex;align-items:center;gap:6px;padding:6px 10px;background:rgba(255,255,255,0.18);border:1px solid rgba(255,255,255,0.25);border-radius:6px;margin-bottom:6px;font-size:13px;color:#fff;';
            tag.innerHTML = '<span>📎</span>'
                + '<span style="font-weight:500">' + escapeHtml(fileAttachments[j].name) + '</span>'
                + '<span style="opacity:0.7;font-size:11px">(' + formatFileSize(fileAttachments[j].size) + ')</span>';
            bubble.appendChild(tag);
        }
    }

    var span = document.createElement('span');
    span.textContent = text;
    bubble.appendChild(span);

    var copyBtn = row.querySelector('.user-copy-btn');
    copyBtn.addEventListener('click', function() {
        var txt = bubble.innerText || '';
        if (navigator.clipboard) {
            navigator.clipboard.writeText(txt).then(function() {
                copyBtn.classList.add('copied');
                copyBtn.innerHTML = '<i class="layui-icon layui-icon-ok" style="font-size:14px"></i>';
                setTimeout(function() {
                    copyBtn.classList.remove('copied');
                    copyBtn.innerHTML = '<i class="layui-icon layui-icon-file"></i>';
                }, 1500);
            });
        }
    });

    sess.container.appendChild(row);
    if (sess.sessionId === activeSessionId) scrollToBottom(true);
}

function ensureAssistantBubble(sess) {
    if (!sess.currentBubbleEl) {
        console.log('[ensureAssistantBubble] 新建 AI bubble, isStreaming=%s', sess.isStreaming, new Error().stack.split('\n').slice(1,4).join('\n'));
        removeThinking(sess);
        var row = document.createElement('div');
        row.className = 'msg-row assistant';
        row.innerHTML = '<div class="msg-avatar"><i class="layui-icon layui-icon-bot" style="font-size:18px"></i></div>'
            + '<div class="msg-bubble"><div class="md-content"></div>'
            + '<div class="msg-actions">'
            + '<button class="msg-action-btn copy-btn" title="复制"><i class="layui-icon layui-icon-file"></i> 复制</button>'
            + '</div></div>';
        sess.container.appendChild(row);
        sess.currentBubbleEl = row.querySelector('.md-content');
        var copyBtn = row.querySelector('.copy-btn');
        var mdRef = sess.currentBubbleEl;
        copyBtn.addEventListener('click', function() {
            var txt = mdRef.innerText || '';
            if (navigator.clipboard) { navigator.clipboard.writeText(txt); }
        });
    }
    return sess.currentBubbleEl;
}

function ensureThinkingBlock(sess) {
    if (!sess.thinkingBlockEl) {
        ensureAssistantBubble(sess);
        var parent = sess.currentBubbleEl.parentNode;
        var block = document.createElement('div');
        block.className = 'thinking-block streaming expanded';
        block.innerHTML = '<div class="thinking-block-header">'
            + '<span class="thinking-block-label">思考中...</span>'
            + '<span class="thinking-timer" style="margin-left:4px">0s</span>'
            + '<span class="thinking-block-dots"><span></span><span></span><span></span></span>'
            + '<i class="layui-icon layui-icon-right thinking-block-toggle"></i>'
            + '</div>'
            + '<div class="thinking-block-body"><div class="md-content"></div></div>';
        parent.insertBefore(block, sess.currentBubbleEl);
        block.querySelector('.thinking-block-header').addEventListener('click', function() {
            block.classList.toggle('expanded');
        });
        sess.thinkingBlockEl = block;
        sess.thinkingBodyMdEl = block.querySelector('.thinking-block-body .md-content');
        sess.thinkingBodyWrapEl = block.querySelector('.thinking-block-body');
        sess.thinkingBuffer = '';
        var timerSpan = block.querySelector('.thinking-timer');
        startThinkingTimer(sess, 'thinkingBlockTimerId', 'thinkingBlockStartTime', timerSpan);
    }
    return sess.thinkingBlockEl;
}

function insertBeforeActions(sess, el) {
    var parent = sess.currentBubbleEl.parentNode;
    parent.insertBefore(el, parent.querySelector('.msg-actions'));
}

function finishThinkingBlock(sess) {
    if (sess.thinkingBlockEl) {
        stopThinkingTimer(sess, 'thinkingBlockTimerId', 'thinkingBlockStartTime');
        if (sess.reasonRafId) {
            cancelAnimationFrame(sess.reasonRafId);
            sess.reasonRafId = null;
            if (sess.thinkingBodyMdEl) {
                sess.thinkingBodyMdEl.innerHTML = renderMd(sess.thinkingBuffer);
            }
        }
        sess.thinkingBlockEl.classList.remove('streaming', 'expanded');
        var elapsed = '';
        if (sess.thinkingBlockStartTime) {
            elapsed = ' (' + Math.floor((Date.now() - sess.thinkingBlockStartTime) / 1000) + 's)';
        }
        var label = sess.thinkingBlockEl.querySelector('.thinking-block-label');
        if (label) label.textContent = '思考结束' + elapsed;
        var dots = sess.thinkingBlockEl.querySelector('.thinking-block-dots');
        if (dots) dots.remove();
        var timerSpan = sess.thinkingBlockEl.querySelector('.thinking-timer');
        if (timerSpan) timerSpan.remove();
        sess.thinkingBlockEl = null;
        sess.thinkingBodyMdEl = null;
        sess.thinkingBodyWrapEl = null;
        sess.thinkingBuffer = '';
    }
}

function clearThinkTags(text) {
    return text.replace(/<\s*\/?think\s*>/gi, '');
}

function appendReasonChunk(sess, text) {
    removeThinking(sess);
    ensureThinkingBlock(sess);
    sess.thinkingBuffer += clearThinkTags(text);
    if (!sess.reasonRafId) {
        sess.reasonRafId = requestAnimationFrame(function() {
            sess.reasonRafId = null;
            if (!sess.thinkingBlockEl) return;
            if (sess.thinkingBodyMdEl) {
                sess.thinkingBodyMdEl.innerHTML = renderMd(sess.thinkingBuffer);
            }
            if (sess.thinkingBodyWrapEl) {
                sess.thinkingBodyWrapEl.scrollTop = sess.thinkingBodyWrapEl.scrollHeight;
            }
            if (sess.sessionId === activeSessionId) scrollToBottom();
        });
    }
}

function finishPendingTool(sess) {
    if (sess.pendingToolCard) {
        var icon = sess.pendingToolCard.querySelector('.tool-status-icon');
        if (icon) { icon.className = 'tool-status-icon done'; icon.innerHTML = '<i class="layui-icon layui-icon-ok" style="font-size:12px"></i>'; }

        sess.pendingToolCard = null;
    }
}

function appendActionEndChunk(sess, toolName, text, args) {
    finishPendingTool(sess);
    ensureAssistantBubble(sess);

    // 参考 CliShell 简化打印方式，将 args 拼接为短字符串
    function formatArgValue(v) {
        if (v === null) return 'null';
        if (v === undefined) return 'undefined';
        if (typeof v === 'string') return v.replace(/\n/g, ' ');
        if (typeof v === 'number' || typeof v === 'boolean') return String(v);
        if (Array.isArray(v)) return '[' + v.length + '项]';
        if (typeof v === 'object') {
            var keys = Object.keys(v);
            if (keys.length === 0) return '{}';
            if (keys.length > 3) return '{' + keys.slice(0, 2).join(',') + ',...}';
            var inner = [];
            keys.forEach(function(k) { inner.push(k + ':' + formatArgValue(v[k])); });
            var s = '{' + inner.join(',') + '}';
            return s.length > 30 ? '{' + keys.join(',') + '}' : s;
        }
        return String(v);
    }
    var argsHtml = '';
    if (args && typeof args === 'object') {
        var parts = [];
        Object.keys(args).forEach(function(k) {
            parts.push(k + '=' + formatArgValue(args[k]));
        });
        var argsStr = parts.join(' ');
        if (argsStr.length > 80) argsStr = argsStr.substring(0, 77) + '...';
        if (argsStr) argsHtml = '<span class="tool-args">' + escapeHtml(argsStr) + '</span>';
    }

    var card = document.createElement('div');
    card.className = 'tool-card';
    card.innerHTML = '<div class="tool-card-header">'
        + '<span class="tool-status-icon loading"></span>'
        + '<span class="tool-name">' + escapeHtml(toolName || 'tool') + '</span>'
        + argsHtml
        + '<i class="layui-icon layui-icon-right tool-toggle"></i>'
        + '</div>'
        + '<div class="tool-card-body"></div>';

    card.querySelector('.tool-card-body').textContent = text || '';
    card.querySelector('.tool-card-header').addEventListener('click', function() {
        card.classList.toggle('expanded');
    });

    insertBeforeActions(sess, card);
    sess.pendingToolCard = card;

    sess.reasonBuffer = '';
    var newMd = document.createElement('div');
    newMd.className = 'md-content';
    insertBeforeActions(sess, newMd);
    sess.currentBubbleEl = newMd;
    if (sess.sessionId === activeSessionId) scrollToBottom();
}

function appendContentChunk(sess, text, append) {
    var clean = clearThinkTags(text);
    sess.reasonBuffer = append ? sess.reasonBuffer + clean : clean;
    if (!sess.contentRafId) {
        sess.contentRafId = requestAnimationFrame(function() {
            var el = ensureAssistantBubble(sess);
            el.innerHTML = renderMd(sess.reasonBuffer);

            sess.contentRafId = null;

            if (sess.sessionId === activeSessionId) scrollToBottom();
        });
    }
}

function appendErrorChunk(sess, text) {
    ensureAssistantBubble(sess);
    var errEl = document.createElement('div');
    errEl.className = 'chunk-error';
    errEl.textContent = text;
    insertBeforeActions(sess, errEl);
    if (sess.sessionId === activeSessionId) scrollToBottom();
}

/* ===== Command Output ===== */
function appendCommandOutput(sess, text) {
    ensureAssistantBubble(sess);
    var mdEl = document.createElement('div');
    mdEl.className = 'md-content';
    mdEl.innerHTML = renderMd(text);
    insertBeforeActions(sess, mdEl);
    if (sess.sessionId === activeSessionId) scrollToBottom();
}

/* ===== Thinking Indicators ===== */
function startThinkingTimer(sess, timerKey, startTimeKey, labelEl) {
    sess[startTimeKey] = Date.now();
    if (sess[timerKey]) clearInterval(sess[timerKey]);
    sess[timerKey] = setInterval(function() {
        if (!labelEl || !labelEl.parentNode) { clearInterval(sess[timerKey]); sess[timerKey] = null; return; }
        var elapsed = Math.floor((Date.now() - sess[startTimeKey]) / 1000);
        labelEl.textContent = elapsed + 's';
    }, 1000);
}

function stopThinkingTimer(sess, timerKey, startTimeKey) {
    if (sess[timerKey]) { clearInterval(sess[timerKey]); sess[timerKey] = null; }
    sess[startTimeKey] = null;
}

function showThinking(sess) {
    console.log('[showThinking] isStreaming=%s', sess.isStreaming, new Error().stack.split('\n').slice(1,4).join('\n'));
    removeThinking(sess);
    sess.thinkingEl = document.createElement('div');
    sess.thinkingEl.className = 'thinking-row';
    sess.thinkingEl.innerHTML = '<div class="msg-avatar" style="background:linear-gradient(135deg,var(--accent),#a78bfa);color:#fff">'
        + '<i class="layui-icon layui-icon-bot" style="font-size:18px"></i></div>'
        + '<div class="thinking-bubble">思考中' + DOTS_HTML + '<span class="thinking-timer">0s</span></div>';
    sess.container.appendChild(sess.thinkingEl);
    var timerSpan = sess.thinkingEl.querySelector('.thinking-timer');
    startThinkingTimer(sess, 'thinkingTimerId', 'thinkingStartTime', timerSpan);
    if (sess.sessionId === activeSessionId) scrollToBottom(true);
}
function removeThinking(sess) {
    stopThinkingTimer(sess, 'thinkingTimerId', 'thinkingStartTime');
    if (sess.thinkingEl && sess.thinkingEl.parentNode) { sess.thinkingEl.parentNode.removeChild(sess.thinkingEl); sess.thinkingEl = null; }
}

function showInlineThinking(sess) {
    if (sess.inlineThinkingEl || !sess.currentBubbleEl) return;
    sess.inlineThinkingEl = document.createElement('div');
    sess.inlineThinkingEl.className = 'inline-thinking';
    sess.inlineThinkingEl.innerHTML = '思考中 ' + DOTS_HTML + '<span class="thinking-timer">0s</span>';
    insertBeforeActions(sess, sess.inlineThinkingEl);
    var timerSpan = sess.inlineThinkingEl.querySelector('.thinking-timer');
    startThinkingTimer(sess, 'inlineThinkingTimerId', 'inlineThinkingStartTime', timerSpan);
    if (sess.sessionId === activeSessionId) scrollToBottom();
}
function removeInlineThinking(sess) {
    stopThinkingTimer(sess, 'inlineThinkingTimerId', 'inlineThinkingStartTime');
    if (sess.inlineThinkingEl && sess.inlineThinkingEl.parentNode) { sess.inlineThinkingEl.parentNode.removeChild(sess.inlineThinkingEl); }
    sess.inlineThinkingEl = null;
}

/* ===== HITL ===== */
function appendHitlCard(sess, toolName, command) {
    ensureAssistantBubble(sess);

    var card = document.createElement('div');
    card.className = 'hitl-card';
    card.innerHTML = '<div class="hitl-card-header">'
        + '<i class="layui-icon layui-icon-tips"></i> \u9700\u8981\u6388\u6743'
        + '</div>'
        + '<div class="hitl-card-body">'
        + '<div class="hitl-tool">\u5de5\u5177: <strong>' + escapeHtml(toolName || 'unknown') + '</strong></div>'
        + (command ? '<div class="hitl-command">' + escapeHtml(command) + '</div>' : '')
        + '</div>'
        + '<div class="hitl-card-actions">'
        + '<button class="hitl-btn hitl-btn-approve">\u6279\u51c6</button>'
        + '<button class="hitl-btn hitl-btn-reject">\u62d2\u7edd</button>'
        + '</div>';

    insertBeforeActions(sess, card);

    var approveBtn = card.querySelector('.hitl-btn-approve');
    var rejectBtn = card.querySelector('.hitl-btn-reject');

    approveBtn.addEventListener('click', function() {
        approveBtn.disabled = true;
        rejectBtn.disabled = true;
        approveBtn.textContent = '\u5df2\u6279\u51c6';
        rejectBtn.style.display = 'none';
        card.style.borderColor = '#22c55e';
        handleHitlResponse(sess, 'approve');
    });

    rejectBtn.addEventListener('click', function() {
        approveBtn.disabled = true;
        rejectBtn.disabled = true;
        rejectBtn.textContent = '\u5df2\u62d2\u7edd';
        approveBtn.style.display = 'none';
        card.style.borderColor = '#ef4444';
        handleHitlResponse(sess, 'reject');
    });

    if (sess.sessionId === activeSessionId) scrollToBottom();
}

function escapeHtml(str) {
    var div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

function handleHitlResponse(sess, action) {
    if (sess.eventSource) { sess.eventSource.close(); sess.eventSource = null; }
    resetStreamState(sess);

    sess.isStreaming = true;
    if (sess.sessionId === activeSessionId) {
        isStreaming = true;
        setBtnStopMode();
    }
    showThinking(sess);

    // 通过 HTTP POST 发送 HITL 决策，结果通过 WebSocket 推送
    var formData = new FormData();
    formData.append('hitlAction', action);
    formData.append('sessionId', sess.sessionId);

    fetch(SSE_ENDPOINT, {
        method: 'POST',
        body: formData
    }).then(function(resp) {
        // HTTP 响应只有 {"status":"ok"}，实际数据通过 WebSocket 推送
    }).catch(function(err) {
        console.error('HITL error:', err);
        finishStream(sess);
    });
}

/* ===== Send ===== */
function sendMessage() {
    var text = getInputText();
    if (!text && pendingFiles.length === 0) return;
    /* Block only if the active session is currently streaming */
    if (activeSessionId && sessionMap[activeSessionId] && sessionMap[activeSessionId].isStreaming) return;

    var filesToSend = pendingFiles.slice(); // snapshot

    // Build display text
    var displayText = text || '';
    if (!displayText && filesToSend.length > 0) {
        var first = filesToSend[0];
        if (first.attachmentsType === 'image') {
            displayText = '请描述这些图片';
        } else {
            displayText = '请帮我处理这些文件';
        }
    }

    if (currentChatIndex === -1) {
        saveChatToHistory(displayText);
    }

    clearInput();
    clearAttachmentPreview();
    if (!inChatMode) switchToChatMode();
    setActiveSession(SESSION_ID);

    var sess = sessionMap[SESSION_ID];

    // Show user message with attachment previews
    var imageDataUrls = [];
    var fileAttachments = [];
    for (var i = 0; i < filesToSend.length; i++) {
        if (filesToSend[i].type === 'image') imageDataUrls.push(filesToSend[i]);
        else fileAttachments.push(filesToSend[i]);
    }
    appendUserMessage(sess, displayText, imageDataUrls, fileAttachments);

    sess.isStreaming = true;
    isStreaming = true;
    setBtnStopMode();
    resetStreamState(sess);
    showThinking(sess);

    sendWithFormData(sess, text, filesToSend);
}

function sendWithFormData(sess, text, filesToSend) {
    sendWithFormDataGrouped(sess, text, filesToSend);
}

function sendWithFormDataGrouped(sess, text, filesToSend) {
    if (sess.eventSource) { sess.eventSource.close(); sess.eventSource = null; }
    var model = getSelectedModel();
    var formData = new FormData();
    formData.append('input', text);
    formData.append('sessionId', sess.sessionId);
    if (model) formData.append('model', model);
    for (var i = 0; i < filesToSend.length; i++) {
        formData.append('attachments', filesToSend[i].file, filesToSend[i].name);
        formData.append('attachmentTypes', filesToSend[i].attachmentsType || 'file');
    }

    // 标记流式状态，WebSocket onmessage 会处理数据
    sess.isStreaming = true;
    if (sess.sessionId === activeSessionId) {
        isStreaming = true;
        setBtnStopMode();
    }
    resetStreamState(sess);
    showThinking(sess);

    fetch(SSE_ENDPOINT, {
        method: 'POST',
        body: formData
    }).then(function(resp) {
        // HTTP 响应只有 {"status":"ok"}，实际数据通过 WebSocket 推送
    }).catch(function(err) {
        console.error('Send error:', err);
        finishStream(sess);
    });
}

/* ===== Rewind Handling ===== */
function handleRewind(sess, count) {
    if (count <= 0) return;
    // 每轮对话 = 1个user + 1个assistant，从末尾倒序删除
    var toRemove = count * 2;
    var rows = sess.container.querySelectorAll('.msg-row');
    var actual = Math.min(toRemove, rows.length);
    for (var i = 0; i < actual; i++) {
        var last = rows[rows.length - 1];
        if (last) last.remove();
        rows = sess.container.querySelectorAll('.msg-row');
    }
    resetStreamState(sess);
    if (sess.sessionId === activeSessionId) scrollToBottom(true);
}

/* ===== WebChunk Handling (Session-Aware) ===== */
function onWebChunk(sess, chunk) {
    try {
        if (sess.silenceTimer) {
            clearTimeout(sess.silenceTimer);
        }

        removeInlineThinking(sess);

        switch (chunk.type) {
            case 'command': finishThinkingBlock(sess); finishPendingTool(sess); appendCommandOutput(sess, chunk.text); break;
            case 'rewind': finishThinkingBlock(sess); finishPendingTool(sess); handleRewind(sess, parseInt(chunk.text) || 1); break;
            case 'reason': finishPendingTool(sess); appendReasonChunk(sess, chunk.text); break;
            case 'text':   finishThinkingBlock(sess); finishPendingTool(sess); appendContentChunk(sess, chunk.text, true); break;
            case 'action': finishThinkingBlock(sess); appendActionEndChunk(sess, chunk.toolName, chunk.text, chunk.args); break;
            case 'agent':  finishThinkingBlock(sess); finishPendingTool(sess); appendContentChunk(sess, chunk.text, false); break;
            case 'error':  finishThinkingBlock(sess); appendErrorChunk(sess, chunk.text); break;
            case 'hitl':   finishThinkingBlock(sess); finishPendingTool(sess); appendHitlCard(sess, chunk.toolName, chunk.command); break;
        }
        sess.silenceTimer = setTimeout(function() {
            if (sess.isStreaming && !sess.thinkingBlockEl) showInlineThinking(sess);
        }, 1000);
    } catch (e) {}
}

function finishStream(sess) {
    var wasStreaming = sess.isStreaming;
    sess.isStreaming = false;
    if (sess.silenceTimer) { clearTimeout(sess.silenceTimer); sess.silenceTimer = null; }

    // --- 新增：强刷逻辑，必须在 resetStreamState 之前执行 ---
    // 1. 取消还没跑的动画帧
    if (sess.contentRafId) { cancelAnimationFrame(sess.contentRafId); sess.contentRafId = null; }
    if (sess.reasonRafId) { cancelAnimationFrame(sess.reasonRafId); sess.reasonRafId = null; }

    // 2. 立即把 Buffer 内容渲染出来
    if (sess.reasonBuffer) {
        var el = ensureAssistantBubble(sess);
        el.innerHTML = renderMd(sess.reasonBuffer);
    }
    // 如果有思考中的内容，也刷一下
    if (sess.thinkingBlockEl && sess.thinkingBuffer) {
        if (sess.thinkingBodyMdEl) sess.thinkingBodyMdEl.innerHTML = renderMd(sess.thinkingBuffer);
    }
    // ---------------------------------------------------

    removeThinking(sess);
    removeInlineThinking(sess);
    finishThinkingBlock(sess);
    finishPendingTool(sess);

    if (sess.eventSource) { sess.eventSource.close(); sess.eventSource = null; }

    // resetStreamState 会清空 buffer，所以必须在上面强刷完后再调
    resetStreamState(sess);

    if (sess.sessionId === activeSessionId) {
        isStreaming = false;
        setBtnSendMode();
        // 只有在活动会话才滚动
        scrollToBottom(true);
        chatInput.focus();
    }
    loadSessionHistory();
}

/* ===== WebSocket 单连接 ===== */
var webGateSocket = null;
var webGateReconnectAttempts = 0;
var webGateHeartbeatTimer = null;
var WEBGATE_MAX_RECONNECT = 10;

function connectWebGate() {
    if (webGateSocket && webGateSocket.readyState === WebSocket.OPEN) return;
    try {
        var protocol = (window.location.protocol === 'https:') ? 'wss:' : 'ws:';
        var wsUrl = protocol + '//' + window.location.host + '/web/gate';
        webGateSocket = new WebSocket(wsUrl);
    } catch(e) {
        console.error('[WebGate] create failed:', e);
        scheduleWebGateReconnect();
        return;
    }

    webGateSocket.onopen = function() {
        console.log('[WebGate] connected');
        webGateReconnectAttempts = 0;
        startWebGateHeartbeat();
    };

    webGateSocket.onmessage = function(event) {
        var raw = event.data;
        if (raw === 'pong') return; // 心跳回复
        try {
            var chunk = JSON.parse(raw);
            var sid = chunk.sessionId;

            // WebSocket 流结束信号
            if (chunk.type === 'done') {
                if (!sid) return;
                var sess = sessionMap[sid];
                if (!sess) return;
                finishStream(sess);
                return;
            }

            if (!sid) return; // 无 sessionId 的消息丢弃
            var sess2 = sessionMap[sid];
            if (!sess2) return; // 未知 session

            // Loop/微信 等后端推送触发流式状态
            if (!sess2.isStreaming) {
                sess2.isStreaming = true;
                if (sess2.sessionId === activeSessionId) {
                    isStreaming = true;
                    setBtnStopMode();
                }
                resetStreamState(sess2);
                showThinking(sess2);
            }
            onWebChunk(sess2, chunk);
        } catch(e) {
            // 非 JSON 消息忽略
        }
    };

    webGateSocket.onclose = function() {
        console.log('[WebGate] closed');
        stopWebGateHeartbeat();
        scheduleWebGateReconnect();
    };

    webGateSocket.onerror = function(err) {
        console.error('[WebGate] error:', err);
    };
}

function startWebGateHeartbeat() {
    stopWebGateHeartbeat();
    webGateHeartbeatTimer = setInterval(function() {
        if (webGateSocket && webGateSocket.readyState === WebSocket.OPEN) {
            webGateSocket.send('ping');
        }
    }, 15000);
}

function stopWebGateHeartbeat() {
    if (webGateHeartbeatTimer) {
        clearInterval(webGateHeartbeatTimer);
        webGateHeartbeatTimer = null;
    }
}

function scheduleWebGateReconnect() {
    if (webGateReconnectAttempts >= WEBGATE_MAX_RECONNECT) {
        console.warn('[WebGate] max reconnect attempts reached');
        return;
    }
    var delay = Math.min(1000 * Math.pow(2, webGateReconnectAttempts), 30000);
    webGateReconnectAttempts++;
    console.log('[WebGate] reconnecting in ' + delay + 'ms (attempt ' + webGateReconnectAttempts + ')');
    setTimeout(function() {
        connectWebGate();
    }, delay);
}

// 页面可见性控制心跳
document.addEventListener('visibilitychange', function() {
    if (document.hidden) {
        stopWebGateHeartbeat();
    } else {
        startWebGateHeartbeat();
    }
});

// 页面加载后自动建立 WebSocket 连接
connectWebGate();

/* ===== Sidebar Collapse Toggle ===== */
(function() {
    var btn = document.getElementById('sidebarToggleBtn');
    if (!btn) return;
    btn.addEventListener('click', function() {
        var sidebar = document.querySelector('.sidebar');
        sidebar.classList.toggle('collapsed');
        var collapsed = sidebar.classList.contains('collapsed');
        btn.classList.toggle('collapsed', collapsed);
        btn.innerHTML = collapsed ? '›' : '‹';
        btn.title = collapsed ? '展开侧边栏' : '收起侧边栏';
        localStorage.setItem('sidebar-collapsed', collapsed ? '1' : '0');
    });
    // Restore state
    if (localStorage.getItem('sidebar-collapsed') === '1') {
        document.querySelector('.sidebar').classList.add('collapsed');
        btn.classList.add('collapsed');
        btn.innerHTML = '›';
        btn.title = '展开侧边栏';
    }
})();

/* ===== WeChat ClawBot Channel ===== */
var wechatHeaderBtn = document.getElementById('wechatHeaderBtn');
var wechatHeaderLabel = document.getElementById('wechatHeaderLabel');
var wechatModalOverlay = null;
var wechatPollTimer = null;

function updateWechatUI() {
    if (!activeSessionId) return;
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/chat/wechat/status?sessionId=' + encodeURIComponent(activeSessionId), true);
    xhr.onreadystatechange = function() {
        if (xhr.readyState === 4 && xhr.status === 200) {
            try {
                var resp = JSON.parse(xhr.responseText);
                var bound = resp.data && resp.data.bound;
                wechatHeaderBtn.classList.toggle('bound', !!bound);
                wechatHeaderLabel.textContent = bound ? '已连接' : '';
                wechatHeaderBtn.title = bound ? '微信已接管（点击解绑）' : '微信接管';
            } catch(e) {}
        }
    };
    xhr.send();
}

// Page load & session switch: refresh status
updateWechatUI();
var origSetActiveSession = setActiveSession;
setActiveSession = function(sid) {
    origSetActiveSession(sid);
    updateWechatUI();
};

wechatHeaderBtn.addEventListener('click', function() {
    if (!activeSessionId) return;
    // If already bound, unbind
    if (wechatHeaderBtn.classList.contains('bound')) {
        if (!confirm('确定要断开微信连接吗？')) return;
        var xhr = new XMLHttpRequest();
        xhr.open('POST', '/chat/wechat/unbind?sessionId=' + encodeURIComponent(activeSessionId), true);
        xhr.onreadystatechange = function() {
            if (xhr.readyState === 4) updateWechatUI();
        };
        xhr.send();
        return;
    }
    // Not bound: show QR modal
    showWechatModal();
});

function showWechatModal() {
    if (wechatModalOverlay) return;

    wechatModalOverlay = document.createElement('div');
    wechatModalOverlay.className = 'wechat-modal-overlay';
    wechatModalOverlay.innerHTML = '<div class="wechat-modal">'
        + '<div class="wechat-modal-title">微信扫码接管</div>'
        + '<div class="wechat-modal-subtitle">用微信扫描二维码，当前会话将同步到微信</div>'
        + '<div class="wechat-qr-wrap" id="wechatQrWrap"><span style="color:#999;font-size:13px">加载中...</span></div>'
        + '<div class="wechat-status" id="wechatQrStatus">等待扫码...</div>'
        + '<button class="wechat-modal-close" id="wechatModalClose">取消</button>'
        + '</div>';
    document.body.appendChild(wechatModalOverlay);

    document.getElementById('wechatModalClose').addEventListener('click', closeWechatModal);
    wechatModalOverlay.addEventListener('click', function(e) {
        if (e.target === wechatModalOverlay) closeWechatModal();
    });

    // Fetch QR code
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/chat/wechat/qrcode?sessionId=' + encodeURIComponent(activeSessionId), true);
    xhr.onreadystatechange = function() {
        if (xhr.readyState === 4 && xhr.status === 200) {
            try {
                var resp = JSON.parse(xhr.responseText);
                if (resp.code !== 200 || !resp.data) {
                    document.getElementById('wechatQrStatus').textContent = resp.message || '获取二维码失败';
                    document.getElementById('wechatQrStatus').className = 'wechat-status error';
                    return;
                }
                var qrWrap = document.getElementById('wechatQrWrap');
                qrWrap.innerHTML = '';
                var qrContent = resp.data.qrcode_img_content || resp.data.qrcode;
                if (qrContent) {
                    try {
                        new QRCode(qrWrap, { text: qrContent, width: 180, height: 180 });
                    } catch(e) {
                        qrWrap.innerHTML = '<span style="font-size:12px;color:#666;padding:10px">' + escapeHtml(qrContent) + '</span>';
                    }
                }
                // Start polling
                startWechatPoll(resp.data.qrcode, activeSessionId);
            } catch(e) {
                document.getElementById('wechatQrStatus').textContent = '解析失败';
                document.getElementById('wechatQrStatus').className = 'wechat-status error';
            }
        }
    };
    xhr.send();
}

function startWechatPoll(qrcode, sessionId) {
    if (wechatPollTimer) clearInterval(wechatPollTimer);
    wechatPollTimer = setInterval(function() {
        var xhr = new XMLHttpRequest();
        xhr.open('GET', '/chat/wechat/qrcode/status?qrcode=' + encodeURIComponent(qrcode) + '&sessionId=' + encodeURIComponent(sessionId), true);
        xhr.onreadystatechange = function() {
            if (xhr.readyState === 4 && xhr.status === 200) {
                try {
                    var resp = JSON.parse(xhr.responseText);
                    var data = resp.data || {};
                    var statusEl = document.getElementById('wechatQrStatus');
                    if (!statusEl) return;

                    var status = data.status;
                    if (status === 'wait') {
                        statusEl.textContent = '等待扫码...';
                        statusEl.className = 'wechat-status';
                    } else if (status === 'scaned') {
                        statusEl.textContent = '已扫码，请在微信中确认...';
                        statusEl.className = 'wechat-status scanned';
                    } else if (status === 'confirmed') {
                        statusEl.textContent = '连接成功！';
                        statusEl.className = 'wechat-status scanned';
                        clearInterval(wechatPollTimer);
                        wechatPollTimer = null;
                        setTimeout(function() {
                            closeWechatModal();
                            updateWechatUI();
                            switchToChatMode();
                            // 连接成功后，自动发一条初始消息创建服务端会话记录
                            // 这样微信消息才能通过 session 互通到前端
                            var initSess = getOrCreateSession(SESSION_ID);
                            if (!initSess._wechatInited) {
                                initSess._wechatInited = true;
                                chatInput.value = '你好，微信已连接成功。';
                                welcomeSendBtn.click();
                            }
                        }, 1200);
                    } else if (status === 'expired') {
                        statusEl.textContent = '二维码已过期，请重新获取';
                        statusEl.className = 'wechat-status error';
                        clearInterval(wechatPollTimer);
                        wechatPollTimer = null;
                    } else {
                        // 临时错误或未知状态：继续轮询，扫码过程中的API短暂波动不应打断流程
                        if (wechatPollTimer) {
                            statusEl.textContent = '扫码处理中...';
                            statusEl.className = 'wechat-status';
                        }
                    }
                } catch(e) {}
            }
        };
        xhr.send();
    }, 2000);
}

function closeWechatModal() {
    if (wechatPollTimer) { clearInterval(wechatPollTimer); wechatPollTimer = null; }
    if (wechatModalOverlay) {
        wechatModalOverlay.remove();
        wechatModalOverlay = null;
    }
}
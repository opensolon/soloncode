/* ===== app-streaming.js ===== */
/* 通信与核心流程：发送 + WebChunk + WebSocket + 微信接管 */
/* 依赖：app-base.js, app-ui.js, app-history.js, app-message.js */

/* ===== Send from both inputs ===== */
welcomeSendBtn.addEventListener('click', function() { sendMessage(); });
chatSendBtn.addEventListener('click', function() {
    if (isStreaming && activeSessionId && sessionMap[activeSessionId]) {
        try {
            //提交 interrupt
            var xhr = new XMLHttpRequest();
            xhr.open('POST', '/chat/interrupt?sessionId=' + encodeURIComponent(activeSessionId), true);
            xhr.send();
        } finally {
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

    // 显示助手消息时间戳
    setAssistantTime(sess, sess._lastCreatedAt || Date.now());
    sess._lastCreatedAt = null;

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
                // 保存 done 消息的时间戳，用于 finishStream 显示
                if (chunk.createdAt) sess._lastCreatedAt = chunk.createdAt;
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

/* ===== 初始化：注册回调 + 激活默认会话 ===== */
onFinishStream = finishStream;
setActiveSession(SESSION_ID);

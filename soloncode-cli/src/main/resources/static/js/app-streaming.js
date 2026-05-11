/* ===== app-streaming.js ===== */
/* 通信与核心流程：发送 + WebChunk + WebSocket */
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
                wechatHeaderBtn.title = bound ? '微信已绑定（点击解绑）' : '微信绑定';
            } catch(e) {}
        }
    };
    xhr.send();
}

// Page load & session switch: refresh all IM status
updateWechatUI();
updateFeishuUI();
updateDingTalkUI();
var origSetActiveSession = setActiveSession;
setActiveSession = function(sid) {
    origSetActiveSession(sid);
    updateWechatUI();
    updateFeishuUI();
    updateDingTalkUI();
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
        + '<div class="wechat-modal-title">微信扫码绑定</div>'
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
                            var initSess = getOrCreateSession(SESSION_ID);
                            if (!initSess._wechatInited) {
                                initSess._wechatInited = true;
                                appendSystemNotice(initSess, '微信已连接成功，现在可以在微信上给机器人发条消息试试了。');
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

/* ===== Feishu Channel ===== */
var feishuHeaderBtn = document.getElementById('feishuHeaderBtn');
var feishuHeaderLabel = document.getElementById('feishuHeaderLabel');
var feishuModalOverlay = null;
var feishuPollTimer = null;

function updateFeishuUI() {
    if (!activeSessionId) return;
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/chat/feishu/status?sessionId=' + encodeURIComponent(activeSessionId), true);
    xhr.onreadystatechange = function() {
        if (xhr.readyState === 4 && xhr.status === 200) {
            try {
                var resp = JSON.parse(xhr.responseText);
                var data = resp.data || {};
                var bound = !!data.bound;
                feishuHeaderBtn.classList.toggle('bound', bound);
                feishuHeaderLabel.textContent = bound ? '已连接' : '';
                feishuHeaderBtn.title = bound ? '飞书已绑定（点击解绑）' : '飞书绑定';
            } catch(e) {}
        }
    };
    xhr.send();
}

// Page load: refresh status
updateFeishuUI();

feishuHeaderBtn.addEventListener('click', function() {
    if (!activeSessionId) return;
    // If already bound, unbind
    if (feishuHeaderBtn.classList.contains('bound')) {
        if (!confirm('确定要断开飞书连接吗？')) return;
        var xhr = new XMLHttpRequest();
        xhr.open('POST', '/chat/feishu/unbind?sessionId=' + encodeURIComponent(activeSessionId), true);
        xhr.onreadystatechange = function() {
            if (xhr.readyState === 4) updateFeishuUI();
        };
        xhr.send();
        return;
    }
    // Not bound: show bind modal
    showFeishuModal();
});

function showFeishuModal() {
    if (feishuModalOverlay) return;

    feishuModalOverlay = document.createElement('div');
    feishuModalOverlay.className = 'im-bind-modal-overlay';
    feishuModalOverlay.innerHTML = '<div class="im-bind-modal">'
        + '<div class="im-bind-modal-title" style="color:#3370ff">飞书绑定</div>'
        + '<div class="im-bind-modal-subtitle">输入飞书应用的 App ID 和 App Secret，连接后请在飞书上发消息给机器人完成自动绑定</div>'
        + '<div class="im-bind-input-group">'
        + '  <label class="im-bind-input-label">App ID</label>'
        + '  <input class="im-bind-input" id="feishuAppIdInput" placeholder="飞书开放平台 → 应用 → 凭据 → App ID" />'
        + '</div>'
        + '<div class="im-bind-input-group">'
        + '  <label class="im-bind-input-label">App Secret</label>'
        + '  <input class="im-bind-input" id="feishuAppSecretInput" type="password" placeholder="飞书开放平台 → 应用 → 凭据 → App Secret" />'
        + '</div>'
        + '<div class="im-bind-status" id="feishuBindStatus">&nbsp;</div>'
        + '<button class="im-bind-confirm-btn feishu" id="feishuBindConfirmBtn">连接</button>'
        + '<button class="im-bind-modal-close" id="feishuModalClose">取消</button>'
        + '<div class="im-bind-hint">提示：请在飞书开放平台（<a href="https://open.feishu.cn/" target="_blank">open.feishu.cn</a>）创建企业自建应用，开启机器人能力，事件订阅选择 WebSocket 长连接模式，然后复制 App ID 和 App Secret 到这里。</div>'
        + '</div>';
    document.body.appendChild(feishuModalOverlay);

    document.getElementById('feishuModalClose').addEventListener('click', closeFeishuModal);
    feishuModalOverlay.addEventListener('click', function(e) {
        if (e.target === feishuModalOverlay) closeFeishuModal();
    });

    var appIdInput = document.getElementById('feishuAppIdInput');
    var appSecretInput = document.getElementById('feishuAppSecretInput');
    var statusEl = document.getElementById('feishuBindStatus');
    var confirmBtn = document.getElementById('feishuBindConfirmBtn');

    appIdInput.focus();

    confirmBtn.addEventListener('click', function() {
        var appId = appIdInput.value.trim();
        var appSecret = appSecretInput.value.trim();
        if (!appId) {
            statusEl.textContent = '请输入 App ID';
            statusEl.className = 'im-bind-status error';
            return;
        }
        if (!appSecret) {
            statusEl.textContent = '请输入 App Secret';
            statusEl.className = 'im-bind-status error';
            return;
        }
        statusEl.textContent = '正在启动 WebSocket 连接...';
        statusEl.className = 'im-bind-status';
        confirmBtn.disabled = true;
        appIdInput.disabled = true;
        appSecretInput.disabled = true;

        var params = 'sessionId=' + encodeURIComponent(activeSessionId)
            + '&appId=' + encodeURIComponent(appId)
            + '&appSecret=' + encodeURIComponent(appSecret);

        var xhr = new XMLHttpRequest();
        xhr.open('POST', '/chat/feishu/bind?' + params, true);
        xhr.onreadystatechange = function() {
            if (xhr.readyState === 4) {
                if (xhr.status === 200) {
                    try {
                        var resp = JSON.parse(xhr.responseText);
                        if (resp.code === 200) {
                            // WebSocket 启动成功，进入等待飞书消息状态
                            statusEl.textContent = '连接成功！请在飞书上发送消息给机器人...';
                            statusEl.className = 'im-bind-status';
                            confirmBtn.style.display = 'none';
                            // 开始轮询绑定状态
                            startFeishuPoll();
                        } else {
                            statusEl.textContent = resp.message || '连接失败';
                            statusEl.className = 'im-bind-status error';
                            confirmBtn.disabled = false;
                            appIdInput.disabled = false;
                            appSecretInput.disabled = false;
                        }
                    } catch(e) {
                        statusEl.textContent = '连接失败';
                        statusEl.className = 'im-bind-status error';
                        confirmBtn.disabled = false;
                        appIdInput.disabled = false;
                        appSecretInput.disabled = false;
                    }
                } else {
                    statusEl.textContent = '请求失败 (' + xhr.status + ')';
                    statusEl.className = 'im-bind-status error';
                    confirmBtn.disabled = false;
                    appIdInput.disabled = false;
                    appSecretInput.disabled = false;
                }
            }
        };
        xhr.send();
    });

    function startFeishuPoll() {
        if (feishuPollTimer) clearInterval(feishuPollTimer);
        var dotCount = 0;
        feishuPollTimer = setInterval(function() {
            dotCount = (dotCount + 1) % 4;
            var dots = '.'.repeat(dotCount);
            statusEl.textContent = '等待飞书消息' + dots;

            var xhr = new XMLHttpRequest();
            xhr.open('GET', '/chat/feishu/status?sessionId=' + encodeURIComponent(activeSessionId), true);
            xhr.onreadystatechange = function() {
                if (xhr.readyState === 4 && xhr.status === 200) {
                    try {
                        var resp = JSON.parse(xhr.responseText);
                        var data = resp.data || {};
                        if (data.bound) {
                            // 绑定成功！
                            clearInterval(feishuPollTimer);
                            feishuPollTimer = null;
                            statusEl.textContent = '绑定成功！';
                            statusEl.className = 'im-bind-status scanned';
                            setTimeout(function() {
                                closeFeishuModal();
                                updateFeishuUI();
                                switchToChatMode();
                            }, 1000);
                        }
                    } catch(e) {}
                }
            };
            xhr.send();
        }, 2000);
    }

    // Enter key to confirm
    [appIdInput, appSecretInput].forEach(function(el) {
        el.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') {
                e.preventDefault();
                confirmBtn.click();
            }
        });
    });
}

function closeFeishuModal() {
    if (feishuPollTimer) {
        clearInterval(feishuPollTimer);
        feishuPollTimer = null;
    }
    if (feishuModalOverlay) {
        feishuModalOverlay.remove();
        feishuModalOverlay = null;
    }
}

/* ===== DingTalk Channel ===== */
var dingtalkHeaderBtn = document.getElementById('dingtalkHeaderBtn');
var dingtalkHeaderLabel = document.getElementById('dingtalkHeaderLabel');
var dingtalkModalOverlay = null;
var dingtalkPollTimer = null;

function updateDingTalkUI() {
    if (!activeSessionId) return;
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/chat/dingtalk/status?sessionId=' + encodeURIComponent(activeSessionId), true);
    xhr.onreadystatechange = function() {
        if (xhr.readyState === 4 && xhr.status === 200) {
            try {
                var resp = JSON.parse(xhr.responseText);
                var data = resp.data || {};
                var bound = !!data.bound;
                dingtalkHeaderBtn.classList.toggle('bound', bound);
                dingtalkHeaderLabel.textContent = bound ? '已连接' : '';
                dingtalkHeaderBtn.title = bound ? '钉钉已绑定（点击解绑）' : '钉钉绑定';
            } catch(e) {}
        }
    };
    xhr.send();
}

// Page load: refresh status
updateDingTalkUI();

dingtalkHeaderBtn.addEventListener('click', function() {
    if (!activeSessionId) return;
    // If already bound, unbind
    if (dingtalkHeaderBtn.classList.contains('bound')) {
        if (!confirm('确定要断开钉钉连接吗？')) return;
        var xhr = new XMLHttpRequest();
        xhr.open('POST', '/chat/dingtalk/unbind?sessionId=' + encodeURIComponent(activeSessionId), true);
        xhr.onreadystatechange = function() {
            if (xhr.readyState === 4) updateDingTalkUI();
        };
        xhr.send();
        return;
    }
    // Not bound: show bind modal
    showDingTalkModal();
});

function showDingTalkModal() {
    if (dingtalkModalOverlay) return;

    dingtalkModalOverlay = document.createElement('div');
    dingtalkModalOverlay.className = 'im-bind-modal-overlay';
    dingtalkModalOverlay.innerHTML = '<div class="im-bind-modal">'
        + '<div class="im-bind-modal-title" style="color:#0089FF">钉钉绑定</div>'
        + '<div class="im-bind-modal-subtitle">输入钉钉机器人应用的 AppKey 和 AppSecret，连接后请在钉钉上发消息给机器人完成自动绑定</div>'
        + '<div class="im-bind-input-group">'
        + '  <label class="im-bind-input-label">AppKey（Client ID）</label>'
        + '  <input class="im-bind-input" id="dingtalkAppKeyInput" placeholder="钉钉开放平台 → 应用 → 凭据 → AppKey" />'
        + '</div>'
        + '<div class="im-bind-input-group">'
        + '  <label class="im-bind-input-label">AppSecret（Client Secret）</label>'
        + '  <input class="im-bind-input" id="dingtalkAppSecretInput" type="password" placeholder="钉钉开放平台 → 应用 → 凭据 → AppSecret" />'
        + '</div>'
        + '<div class="im-bind-status" id="dingtalkBindStatus">&nbsp;</div>'
        + '<button class="im-bind-confirm-btn dingtalk" id="dingtalkBindConfirmBtn">连接</button>'
        + '<button class="im-bind-modal-close" id="dingtalkModalClose">取消</button>'
        + '<div class="im-bind-hint">提示：请在钉钉开放平台（<a href="https://open.dingtalk.com/" target="_blank">open.dingtalk.com</a>）创建企业内部应用，开启机器人能力，消息接收模式选择 Stream，然后复制 AppKey 和 AppSecret 到这里。</div>'
        + '</div>';
    document.body.appendChild(dingtalkModalOverlay);

    document.getElementById('dingtalkModalClose').addEventListener('click', closeDingTalkModal);
    dingtalkModalOverlay.addEventListener('click', function(e) {
        if (e.target === dingtalkModalOverlay) closeDingTalkModal();
    });

    var appKeyInput = document.getElementById('dingtalkAppKeyInput');
    var appSecretInput = document.getElementById('dingtalkAppSecretInput');
    var statusEl = document.getElementById('dingtalkBindStatus');
    var confirmBtn = document.getElementById('dingtalkBindConfirmBtn');
    var modalContent = dingtalkModalOverlay.querySelector('.im-bind-modal');

    appKeyInput.focus();

    confirmBtn.addEventListener('click', function() {
        var appKey = appKeyInput.value.trim();
        var appSecret = appSecretInput.value.trim();
        if (!appKey) {
            statusEl.textContent = '请输入 AppKey';
            statusEl.className = 'im-bind-status error';
            return;
        }
        if (!appSecret) {
            statusEl.textContent = '请输入 AppSecret';
            statusEl.className = 'im-bind-status error';
            return;
        }
        statusEl.textContent = '正在启动 Stream 连接...';
        statusEl.className = 'im-bind-status';
        confirmBtn.disabled = true;
        appKeyInput.disabled = true;
        appSecretInput.disabled = true;

        var params = 'sessionId=' + encodeURIComponent(activeSessionId)
            + '&appKey=' + encodeURIComponent(appKey)
            + '&appSecret=' + encodeURIComponent(appSecret);

        var xhr = new XMLHttpRequest();
        xhr.open('POST', '/chat/dingtalk/bind?' + params, true);
        xhr.onreadystatechange = function() {
            if (xhr.readyState === 4) {
                if (xhr.status === 200) {
                    try {
                        var resp = JSON.parse(xhr.responseText);
                        if (resp.code === 200) {
                            // Stream 启动成功，进入等待钉钉消息状态
                            statusEl.textContent = '连接成功！请在钉钉上发送消息给机器人...';
                            statusEl.className = 'im-bind-status';
                            confirmBtn.style.display = 'none';
                            // 开始轮询绑定状态
                            startDingTalkPoll();
                        } else {
                            statusEl.textContent = resp.message || '连接失败';
                            statusEl.className = 'im-bind-status error';
                            confirmBtn.disabled = false;
                            appKeyInput.disabled = false;
                            appSecretInput.disabled = false;
                        }
                    } catch(e) {
                        statusEl.textContent = '连接失败';
                        statusEl.className = 'im-bind-status error';
                        confirmBtn.disabled = false;
                        appKeyInput.disabled = false;
                        appSecretInput.disabled = false;
                    }
                } else {
                    statusEl.textContent = '请求失败 (' + xhr.status + ')';
                    statusEl.className = 'im-bind-status error';
                    confirmBtn.disabled = false;
                    appKeyInput.disabled = false;
                    appSecretInput.disabled = false;
                }
            }
        };
        xhr.send();
    });

    function startDingTalkPoll() {
        if (dingtalkPollTimer) clearInterval(dingtalkPollTimer);
        var dotCount = 0;
        dingtalkPollTimer = setInterval(function() {
            dotCount = (dotCount + 1) % 4;
            var dots = '.'.repeat(dotCount);
            statusEl.textContent = '等待钉钉消息' + dots;

            var xhr = new XMLHttpRequest();
            xhr.open('GET', '/chat/dingtalk/status?sessionId=' + encodeURIComponent(activeSessionId), true);
            xhr.onreadystatechange = function() {
                if (xhr.readyState === 4 && xhr.status === 200) {
                    try {
                        var resp = JSON.parse(xhr.responseText);
                        var data = resp.data || {};
                        if (data.bound) {
                            // 绑定成功！
                            clearInterval(dingtalkPollTimer);
                            dingtalkPollTimer = null;
                            statusEl.textContent = '绑定成功！';
                            statusEl.className = 'im-bind-status scanned';
                            setTimeout(function() {
                                closeDingTalkModal();
                                updateDingTalkUI();
                                switchToChatMode();
                            }, 1000);
                        }
                    } catch(e) {}
                }
            };
            xhr.send();
        }, 2000);
    }

    // Enter key to confirm
    [appKeyInput, appSecretInput].forEach(function(el) {
        el.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') {
                e.preventDefault();
                confirmBtn.click();
            }
        });
    });
}

function closeDingTalkModal() {
    if (dingtalkPollTimer) {
        clearInterval(dingtalkPollTimer);
        dingtalkPollTimer = null;
    }
    if (dingtalkModalOverlay) {
        dingtalkModalOverlay.remove();
        dingtalkModalOverlay = null;
    }
}

/* ===== 初始化：注册回调 + 激活默认会话 ===== */
onFinishStream = finishStream;
setActiveSession(SESSION_ID);

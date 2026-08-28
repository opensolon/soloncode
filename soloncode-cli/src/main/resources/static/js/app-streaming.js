/* ===== app-streaming.js ===== */
/* 通信与核心流程：发送 + WebEvent + WebSocket */
/* 依赖：app-base.js, app-ui.js, app-history.js, app-message.js */

/* ===== Send from both inputs ===== */
$(newChatSendBtn).on('click', function() { sendMessage(); });
$(chatSendBtn).on('click', function() {
    if (btnMode === 'stop' && activeSessionId && sessionMap[activeSessionId]) {
        var sess = sessionMap[activeSessionId];
        // 已在等待服务端 done，避免重复 interrupt
        if (sess.stopRequested) return;
        sess.stopRequested = true;
        // 标记本轮因 Stop 结束：finish 时不再 drain 排队
        sess._stoppedTurn = true;
        // Stop = 中断当前 + 清空排队
        if (sess.messageQueue && sess.messageQueue.length) {
            sess.messageQueue = [];
            if (typeof renderQueueDock === 'function') renderQueueDock();
            if (typeof schedulePersistMessageQueue === 'function') schedulePersistMessageQueue(sess);
        }
        if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
        // 提交 interrupt；不在本地立即 finishStream
        // 等服务端 error(取消) + trace + done 到齐后再收尾，避免迟到 chunk 被当成新流
        try {
            $.post('/web/chat/interrupt?sessionId=' + encodeURIComponent(activeSessionId))
                .fail(function(err) {
                    console.warn('[stop] interrupt request failed:', err);
                    // HTTP 请求失败时用短兜底快速恢复按鈕，无需等待 4s
                    if (sess._stopFallbackTimer) clearTimeout(sess._stopFallbackTimer);
                    sess._stopFallbackTimer = setTimeout(function() {
                        sess._stopFallbackTimer = null;
                        if (sess.isStreaming && sess.stopRequested) {
                            finishStream(sess);
                        }
                    }, 1500);
                });
        } catch (e) {
            console.warn('[stop] interrupt failed:', e);
        }
        // 兜底：服务端异常未回 done 时，避免按钮永久卡在 stop
        if (sess._stopFallbackTimer) clearTimeout(sess._stopFallbackTimer);
        sess._stopFallbackTimer = setTimeout(function() {
            sess._stopFallbackTimer = null;
            if (sess.isStreaming && sess.stopRequested) {
                finishStream(sess);
            }
        }, 4000);
    } else {
        sendMessage();
    }
});

/* ===== Click to focus ===== */
$('.newchat-input-box').on('click', function(e) {
    if (!$(e.target).closest('button').length && !$(e.target).closest('.loop-panel').length && !$(e.target).closest('.model-dropdown').length) newChatInput.focus();
});
$('.input-box').on('click', function(e) {
    if (!$(e.target).closest('button').length && !$(e.target).closest('.history-panel').length && !$(e.target).closest('.loop-panel').length && !$(e.target).closest('.model-dropdown').length) chatInput.focus();
});

/* ===== New Chat ===== */
$(newChatBtn).on('click', function() {
    if (typeof closeCenterViewer === 'function') closeCenterViewer();
    currentChatIndex = -1;
    switchToWelcomeMode();
    updateHistoryUI();
});

/* ===== Message Queue (运行中 follow-up 排队) ===== */
var QUEUE_PERSIST_DEBOUNCE_MS = 250;

/** 序列化为可落盘结构（V1：文本+模型元数据，不写附件二进制） */
function serializeQueueForPersist(queue) {
    var q = queue || [];
    var out = [];
    for (var i = 0; i < q.length; i++) {
        var item = q[i];
        if (!item) continue;
        var text = item.text || '';
        var displayText = item.displayText || '';
        var hasFiles = !!(item.files && item.files.length) || !!item.hasFiles;
        // 无文本的纯附件项无法跨刷新恢复，跳过落盘
        if (!String(text).trim() && !String(displayText).trim()) continue;
        var row = {
            id: item.id,
            text: text,
            displayText: displayText,
            createdAt: item.createdAt || Date.now()
        };
        if (item.model) row.model = item.model;
        if (item.reasoningEffort) row.reasoningEffort = item.reasoningEffort;
        if (item.selectedAgent) row.selectedAgent = item.selectedAgent;
        if (hasFiles) row.hasFiles = true;
        out.push(row);
    }
    return out;
}

var _queuePersistFailToastAt = 0;

function schedulePersistMessageQueue(sess) {
    if (!sess || !sess.sessionId) return;
    // 一旦本地发生变更，本地即为权威源（避免清空后因未 hydrate 而跳过写盘）
    sess._queueLoaded = true;
    if (sess._queuePersistTimer) clearTimeout(sess._queuePersistTimer);
    sess._queuePersistTimer = setTimeout(function() {
        sess._queuePersistTimer = null;
        persistMessageQueueNow(sess, false);
    }, QUEUE_PERSIST_DEBOUNCE_MS);
}
window.schedulePersistMessageQueue = schedulePersistMessageQueue;

/**
 * 立即落盘任务排队。
 * @param {boolean} [useKeepalive] 页面卸载路径传 true，提高关闭时请求存活率
 */
function persistMessageQueueNow(sess, useKeepalive) {
    if (!sess || !sess.sessionId) return;
    var payload = {
        sessionId: sess.sessionId,
        updatedAt: Date.now(),
        items: serializeQueueForPersist(sess.messageQueue || [])
    };
    try {
        var opts = {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        };
        if (useKeepalive) opts.keepalive = true;
        fetch('/web/chat/queue', opts)
            .then(function(r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function(res) {
                if (res && res.code === 200) return;
                var msg = (res && res.description) || (res && res.message) || I18n.t('streaming.queueSaveFailed');
                console.warn('[queue] persist rejected:', msg);
                var now = Date.now();
                if (typeof showToast === 'function' && now - _queuePersistFailToastAt > 8000) {
                    _queuePersistFailToastAt = now;
                    showToast(I18n.t('streaming.queuePersistFailed'), 'error', 2500);
                }
            })
            .catch(function(err) {
                console.warn('[queue] persist failed:', err);
                // unload 路径不弹 toast，避免关页时打扰
                if (useKeepalive) return;
                var now = Date.now();
                if (typeof showToast === 'function' && now - _queuePersistFailToastAt > 8000) {
                    _queuePersistFailToastAt = now;
                    showToast(I18n.t('streaming.queuePersistFailed'), 'error', 2500);
                }
            });
    } catch (e) {
        console.warn('[queue] persist failed:', e);
    }
}
window.persistMessageQueueNow = persistMessageQueueNow;

/** 从会话目录 queue-tasks.json 恢复排队（仅文本；附件需重新添加）。冷恢复只展示，不自动发送。 */
function loadMessageQueue(sess) {
    if (!sess || !sess.sessionId) return;
    if (sess._queueLoaded || sess._queueLoading) return;
    sess._queueLoading = true;
    var sid = sess.sessionId;
    fetch('/web/chat/queue?sessionId=' + encodeURIComponent(sid))
        .then(function(r) { return r.json(); })
        .then(function(res) {
            if (!sessionMap[sid]) return;
            var target = sessionMap[sid];
            target._queueLoading = false;
            target._queueLoaded = true;

            // 加载期间用户已本地入队：本地为准，并写回服务端
            if (target.messageQueue && target.messageQueue.length) {
                schedulePersistMessageQueue(target);
                if (sid === activeSessionId) {
                    if (typeof renderQueueDock === 'function') renderQueueDock();
                    if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
                }
                return;
            }

            if (res && res.code && res.code !== 200) {
                console.warn('[queue] load rejected:', res.description || res.message || res.code);
                return;
            }

            var items = (res && res.data && res.data.items) ? res.data.items : [];
            var restored = [];
            for (var i = 0; i < items.length; i++) {
                var it = items[i];
                if (!it) continue;
                restored.push({
                    id: it.id || ('q_' + Date.now().toString(36) + '_' + i),
                    text: it.text || '',
                    displayText: it.displayText || it.text || '',
                    files: [],
                    hasFiles: !!it.hasFiles,
                    model: it.model || null,
                    reasoningEffort: it.reasoningEffort || null,
                    selectedAgent: it.selectedAgent || '',
                    createdAt: it.createdAt || Date.now()
                });
            }
            target.messageQueue = restored;

            if (sid === activeSessionId) {
                if (typeof renderQueueDock === 'function') renderQueueDock();
                if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
                // 冷恢复：只 hydrate UI，不自动 drain。用户 Enter 空发或带新消息入队后会续发。
                if (restored.length && typeof showToast === 'function') {
                    showToast(I18n.t('streaming.queueRestored', {n: restored.length}), 'info', 2800);
                    if (typeof expandFilerPanel === 'function') {
                        try { expandFilerPanel(); } catch (e) {}
                    }
                }
            }
        })
        .catch(function(err) {
            if (sessionMap[sid]) {
                sessionMap[sid]._queueLoading = false;
                // 失败也标记 loaded，避免反复打接口；本地队列仍可用
                sessionMap[sid]._queueLoaded = true;
            }
            console.warn('[queue] load failed:', err);
        });
}
window.loadMessageQueue = loadMessageQueue;

// 页面关闭/刷新前冲刷未落盘的 debounce，尽量减少丢队
function flushAllMessageQueuesNow() {
    try {
        Object.keys(sessionMap || {}).forEach(function(sid) {
            var s = sessionMap[sid];
            if (!s) return;
            if (s._queuePersistTimer) {
                clearTimeout(s._queuePersistTimer);
                s._queuePersistTimer = null;
                persistMessageQueueNow(s, true);
            }
        });
    } catch (e) {}
}
window.addEventListener('beforeunload', flushAllMessageQueuesNow);
window.addEventListener('pagehide', flushAllMessageQueuesNow);

function buildDisplayText(text, filesToSend) {
    var displayText = text || '';
    if (!displayText && filesToSend && filesToSend.length > 0) {
        var first = filesToSend[0];
        if (first.attachmentsType === 'image') {
            displayText = I18n.t('streaming.describeImages');
        } else {
            displayText = I18n.t('streaming.processFiles');
        }
    }
    return displayText;
}

    function truncateQueueText(text, maxLen) {
    var s = String(text || '').replace(/\s+/g, ' ').trim();
    if (!s) return I18n.t('streaming.attachment');
    maxLen = maxLen || 60;
    if (s.length <= maxLen) return s;
    return s.slice(0, maxLen) + '…';
}

    function hasDraftInput() {
    return !!(chatInput && chatInput.value.trim()) || (pendingFiles && pendingFiles.length > 0);
        }

    /* 判定名称是否为已知子代理（commandList 由 app-history.js 加载） */
    function isKnownSubagent(name) {
    if (!name) return false;
    if (typeof commandList === 'undefined' || !commandList) return false;
    for (var i = 0; i < commandList.length; i++) {
        if (commandList[i].type === 'subagent' && commandList[i].name === name) return true;
    }
    return false;
}

    /* 解析本条消息最终生效的子代理（规则与后端 WebGate.onChatInput 一致）：
       输入开头的有效 "@agent " 优先，其次选择器值，都无效时返回空（主 Agent） */
    function resolveEffectiveAgent(text, selectedAgent) {
    if (text && text.charAt(0) === '@') {
        var sp = text.indexOf(' ');
        if (sp > 0 && isKnownSubagent(text.substring(1, sp))) return text.substring(1, sp);
    }
    return isKnownSubagent(selectedAgent) ? selectedAgent : '';
}

    function applyQueuedItemToInput(item) {
    if (!item) return;
    if (!inChatMode) switchToChatMode();
    chatInput.value = item.text || '';
    autoResize(chatInput);
    if (item.files && item.files.length) {
        pendingFiles = item.files.slice();
        if (typeof renderAttachments === 'function') renderAttachments();
    } else {
        clearAttachmentPreview();
    }
    chatInput.focus();
    }

    function enqueueMessage(sess, text, files) {
    if (!sess) return false;
    // Stop 窗口期：禁止再入队，避免结束后误续发
    if (sess.stopRequested) {
        showToast(I18n.t('streaming.stoppingWaitSend'), 'info', 1500);
        return false;
    }
    if (!sess.messageQueue) sess.messageQueue = [];
    if (sess.messageQueue.length >= MAX_QUEUED_MESSAGES) {
        showToast(I18n.t('streaming.queueMaxLimit', {n: MAX_QUEUED_MESSAGES}), 'info', 2000);
        return false;
    }
    var filesSnap = (files || []).slice();
    var displayText = buildDisplayText(text, filesSnap);
    sess.messageQueue.push({
        id: 'q_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 6),
        text: text || '',
        displayText: displayText,
        files: filesSnap,
        hasFiles: filesSnap.length > 0,
        model: typeof getSelectedModel === 'function' ? getSelectedModel() : null,
        reasoningEffort: typeof getSelectedReasoning === 'function' ? getSelectedReasoning() : null,
        thinkingMode: typeof getSelectedThinking === 'function' ? getSelectedThinking() : '',
        selectedAgent: typeof getSelectedAgent === 'function' ? getSelectedAgent() : '',
        createdAt: Date.now()
    });
    clearInput();
    clearAttachmentPreview();
    if (typeof renderQueueDock === 'function') renderQueueDock();
    if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
    if (typeof schedulePersistMessageQueue === 'function') schedulePersistMessageQueue(sess);
    // 首次入队且右栏折叠时自动展开，避免「队列进了黑洞」
    if (sess.messageQueue.length === 1 && typeof window.expandFilerPanel === 'function') {
        window.expandFilerPanel();
    }
    return true;
    }

    function sendMessageCore(sess, text, filesToSend, options) {
    options = options || {};
    filesToSend = filesToSend || [];
    var displayText = options.displayText || buildDisplayText(text, filesToSend);

    if (currentChatIndex === -1) {
        saveChatToHistory(displayText);
    }

    // 先标记 streaming，再 setActiveSession，避免会话切换时误触发 drain 连发
    sess.isStreaming = true;
    sess.stopRequested = false;
    // 新一轮开始：清除上一轮 Stop 标记
    sess._stoppedTurn = false;
    sess.acceptingStream = true;
    sess._streamClosed = false;
    sess._closedRunId = null;
    sess.messageStartTime = Date.now();

    if (!inChatMode) switchToChatMode();
    setActiveSession(sess.sessionId);

    // 用户主动发消息：作废此前上滑状态，后续 AI 输出必须重新粘底
    if (typeof scrollToBottom === 'function') scrollToBottom(true);

    var imageDataUrls = [];
    var fileAttachments = [];
    for (var i = 0; i < filesToSend.length; i++) {
        if (filesToSend[i].type === 'image') imageDataUrls.push(filesToSend[i]);
        else fileAttachments.push(filesToSend[i]);
    }
    var effectiveAgent = resolveEffectiveAgent(text,
        options.selectedAgent !== undefined ? options.selectedAgent
            : (typeof getSelectedAgent === 'function' ? getSelectedAgent() : ''));
    // 文本开头的 "@agent " 会被后端剔除后才入库，此处同步剔除，
    // 避免刷新前后同一条消息文本不一致（子代理信息改由徽标展示）
    if (effectiveAgent && displayText && displayText.indexOf('@' + effectiveAgent + ' ') === 0) {
        displayText = displayText.substring(effectiveAgent.length + 2);
    }
    appendUserMessage(sess, displayText, imageDataUrls, fileAttachments, null, null, effectiveAgent);

    isStreaming = true;
    setBtnStopMode();
    resetStreamState(sess);
    showThinking(sess);
    if (typeof startRoundElapsed === 'function') startRoundElapsed(sess);
    if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();

    sendWithFormDataGrouped(sess, text || '', filesToSend, options);
}

    function sendQueuedItem(sess, item) {
    if (!sess || !item) return;
    sendMessageCore(sess, item.text || '', item.files || [], {
        displayText: item.displayText,
        model: item.model,
        reasoningEffort: item.reasoningEffort,
        thinkingMode: item.thinkingMode || '',
        selectedAgent: item.selectedAgent
    });
        }

    /* ===== 运行中插话（steer） =====
     * 参考方案：docs/steering-inject-plan.md（对齐 Codex steering）。
     * 提交后仅进入“待生效”态（queue dock 徽标），注入真正发生在下一个推理回合
     * （后端 SteerInterceptor.onReasonStart），收到 system.steer_applied 才落气泡。
     * 应答分派：200=STEERED；409 NOT_RUNNING=回落普通发送；TURN_CHANGED/BOX_FULL 等=转排队或提示。 */
    function steerMessage(sess, text) {
        if (!sess || !text) return;
        var body = new URLSearchParams();
        body.append('sessionId', sess.sessionId);
        body.append('text', text);
        if (sess.currentRunId) body.append('runId', sess.currentRunId);

        fetch('/web/chat/steer', {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: body.toString()
        }).then(function (r) {
            return r.json();
        }).then(function (res) {
            if (res && res.code === 200) {
                // 成功：清输入、进入“待生效”态（延迟上屏：applied 事件到达才落气泡）
                clearInput();
                clearAttachmentPreview();
                if (!sess.steerPending) sess.steerPending = [];
                sess.steerPending.push({
                    id: 's_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 6),
                    text: text,
                    createdAt: Date.now()
                });
                if (typeof renderQueueDock === 'function') renderQueueDock();
                chatInput.focus();
                return;
            }
            var msg = (res && res.description) || '';
            if (msg === 'NOT_RUNNING') {
                // 会话已空闲：回落为普通发送（保持“不丢消息”优先）
                showToast(I18n.t('streaming.steerNotRunning'), 'info', 1800);
                sendMessageCore(sess, text, [], {displayText: text});
                return;
            }
            if (msg === 'TURN_CHANGED' || msg === 'BOX_FULL') {
                // 任务已切换/邮箱满：转排队，本轮结束后发送
                var demoted = msg === 'BOX_FULL'
                    ? I18n.t('streaming.steerBoxFull') : I18n.t('streaming.steerTurnChanged');
                showToast(demoted, 'info', 2000);
                enqueueMessage(sess, text, []);
                return;
            }
            showToast(I18n.t('streaming.steerFailed'), 'error', 2000);
        }).catch(function () {
            showToast(I18n.t('streaming.steerFailed'), 'error', 2000);
        });
    }

    /** 后端 steer_applied / steer_dropped 事件处理 */
    function handleSteerEvent(sess, event, p) {
    if (!sess) return;
    var texts = (p && p.texts) || [];
    if (!texts.length) return;

    if (event === 'system.steer_applied') {
        // 注入已生效：从待生效列表移除匹配项，气泡落主时间线（延迟上屏）
        if (sess.steerPending) {
            for (var i = 0; i < texts.length; i++) {
                for (var j = sess.steerPending.length - 1; j >= 0; j--) {
                    if (sess.steerPending[j].text === texts[i]) {
                        sess.steerPending.splice(j, 1);
                        break;
                    }
                }
            }
        }
        // 插话渲染进当前 AI 流式气泡内部（流片段的一部分），不再占用独立 .msg-row。
        // 插入点即当前增长尾部，后续思考块/工具卡落在其下方，位置稳定不抖。
        for (var k = 0; k < texts.length; k++) {
            // 流内渲染失败（无 AI 气泡可挂，如流状态已重置）时回落为独立行，
            // 绝不让已生效的插话不上屏（docs/codex-steer.md 记的 Codex #13595 教训）
            if (typeof appendSteerNote !== 'function' || !appendSteerNote(sess, texts[k])) {
                appendUserMessage(sess, texts[k], null, null, null, null, null, true);
            }
        }
    } else {
        // 任务结束仍未消费：后端兜底广播，前端转为排队消息（绝不“已接受但永不生效”）
        if (sess.steerPending) {
            for (var i2 = 0; i2 < texts.length; i2++) {
                for (var j2 = sess.steerPending.length - 1; j2 >= 0; j2--) {
                    if (sess.steerPending[j2].text === texts[i2]) {
                        sess.steerPending.splice(j2, 1);
                        break;
                    }
                }
            }
        }
        if (!sess.messageQueue) sess.messageQueue = [];
        for (var d = 0; d < texts.length; d++) {
            sess.messageQueue.push({
                id: 'q_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 6),
                text: texts[d],
                displayText: texts[d],
                files: [],
                hasFiles: false,
                model: null,
                reasoningEffort: null,
                thinkingMode: '',
                selectedAgent: '',
                createdAt: Date.now()
            });
        }
        if (typeof showToast === 'function') {
            showToast(I18n.t('streaming.steerDropped'), 'info', 2500);
        }
        if (typeof schedulePersistMessageQueue === 'function') schedulePersistMessageQueue(sess);
    }

    if (sess.sessionId === activeSessionId) {
        if (!inChatMode) switchToChatMode();
        scrollToBottom(true);
    }
    // 待生效项已全部出清时，取消 finishStream 挂的防御定时器
    if (sess._steerFallbackTimer && (!sess.steerPending || !sess.steerPending.length)) {
        clearTimeout(sess._steerFallbackTimer);
        sess._steerFallbackTimer = null;
    }
    if (typeof renderQueueDock === 'function') renderQueueDock();
    if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
    }
    window.steerMessage = steerMessage;

    function drainMessageQueue(sess) {
        if (!sess || sess._queueDraining) return;
        if (sess.isStreaming) return;
        if (sess.stopRequested || sess._stoppedTurn) return;
        if (!sess.messageQueue || !sess.messageQueue.length) return;

        // 仅 active 会话自动续发，避免后台会话抢焦点
        if (sess.sessionId !== activeSessionId) return;

        sess._queueDraining = true;
        try {
            var item = sess.messageQueue.shift();
            if (typeof renderQueueDock === 'function') renderQueueDock();
            if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
            if (typeof schedulePersistMessageQueue === 'function') schedulePersistMessageQueue(sess);
            sendQueuedItem(sess, item);
        } finally {
            sess._queueDraining = false;
        }
    }
    window.drainMessageQueue = drainMessageQueue;

    function removeQueuedMessage(sess, id) {
        if (!sess || !sess.messageQueue) return null;
        for (var i = 0; i < sess.messageQueue.length; i++) {
            if (sess.messageQueue[i].id === id) {
                var removed = sess.messageQueue.splice(i, 1)[0];
                if (typeof renderQueueDock === 'function') renderQueueDock();
                if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
                if (typeof schedulePersistMessageQueue === 'function') schedulePersistMessageQueue(sess);
                return removed;
            }
        }
        return null;
    }

function editQueuedMessageToInput(sess, id) {
    if (!sess || !id) return;
    // 先检查草稿，确认后再出队，避免取消时丢队列项
    if (hasDraftInput()) {
        if (!window.confirm(I18n.t('streaming.overwriteDraftConfirm'))) return;
    }
    var item = removeQueuedMessage(sess, id);
    if (!item) return;
    applyQueuedItemToInput(item);
}

function cancelLastQueuedToInput(sess) {
    if (!sess || !sess.messageQueue || !sess.messageQueue.length) return false;
    var item = sess.messageQueue.pop();
    if (typeof renderQueueDock === 'function') renderQueueDock();
    if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
    if (typeof schedulePersistMessageQueue === 'function') schedulePersistMessageQueue(sess);
    applyQueuedItemToInput(item);
    return true;
        }

    function clearMessageQueue(sess) {
    if (!sess) return;
    sess.messageQueue = [];
    // 队列 dock 同时承载“待生效”插话，一并出清并撤掉防御定时器，避免定时器把已清空的项重新入队
    sess.steerPending = [];
    if (sess._steerFallbackTimer) { clearTimeout(sess._steerFallbackTimer); sess._steerFallbackTimer = null; }
    if (typeof renderQueueDock === 'function') renderQueueDock();
    if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
    if (typeof schedulePersistMessageQueue === 'function') schedulePersistMessageQueue(sess);
    }

    var _queueDockExpanded = false;

    function renderQueueDock() {
        var dock = document.getElementById('chatQueueDock');
        if (!dock) return;
        var sess = activeSessionId && sessionMap[activeSessionId];
        var q = (sess && sess.messageQueue) || [];
        var steers = (sess && sess.steerPending) || [];
        // 折叠按钮角标：即使 strip 不可见也能感知排队数（含待生效插话）
        if (typeof window.updateFilerQueueBadge === 'function') {
            window.updateFilerQueueBadge(q.length + steers.length);
        }
        if (!q.length && !steers.length) {
            dock.style.display = 'none';
            return;
        }
        // 右栏底部 strip：用 flex 布局，避免 display:block 破坏 workspace-panel 列排布
        dock.style.display = 'flex';
        if (_queueDockExpanded) $(dock).removeClass('collapsed');
        else $(dock).addClass('collapsed');

        var total = q.length + steers.length;
        var titleEl = document.getElementById('chatQueueTitle');
        if (titleEl) titleEl.textContent = String(total);

        var previewEl = document.getElementById('chatQueuePreview');
        if (previewEl) {
            var first = steers.length ? steers[0].text : (q[0].displayText || q[0].text);
            previewEl.textContent = I18n.t('streaming.nextMessage') + truncateQueueText(first, 36);
            previewEl.style.display = _queueDockExpanded ? 'none' : 'block';
        }

        var toggleEl = document.getElementById('chatQueueToggle');
        if (toggleEl) {
            toggleEl.title = _queueDockExpanded ? I18n.t('streaming.collapse') : I18n.t('streaming.expand');
            toggleEl.setAttribute('aria-label', _queueDockExpanded ? I18n.t('streaming.collapse') : I18n.t('streaming.expand'));
            if (_queueDockExpanded) toggleEl.classList.add('expanded');
            else toggleEl.classList.remove('expanded');
        }

        var listEl = document.getElementById('chatQueueList');
        if (!listEl) return;
        var html = '';
        // 待生效插话项置顶（比排队更“热”）：仅展示徽标，不可取消（后端邮箱不提供按条撤销）
        for (var s = 0; s < steers.length; s++) {
            html += '<div class="queue-item queue-item-steer">' +
                '<span class="queue-item-steer-badge">' + I18n.t('streaming.steerBadgePending') + '</span>' +
                '<span class="queue-item-text" title="' + escapeHtml(steers[s].text) + '">' +
                    escapeHtml(truncateQueueText(steers[s].text, 48)) +
                '</span></div>';
        }
        for (var i = 0; i < q.length; i++) {
            var item = q[i];
            var fileCount = (item.files && item.files.length) ? item.files.length : 0;
            var attachBadge = (fileCount > 0 || item.hasFiles)
                ? '<span class="queue-item-attach" title="' +
                    (fileCount > 0 ? I18n.t('streaming.attachCount', {n: fileCount}) : I18n.t('streaming.attachNotPersisted')) +
                    '">📎' + (fileCount > 0 ? fileCount : '!') + '</span>'
                : '';
            html += '<div class="queue-item" data-qid="' + escapeHtml(item.id) + '">' +
                '<span class="queue-item-idx">' + (i + 1) + '.</span>' +
                '<span class="queue-item-text" title="' + escapeHtml(item.displayText || item.text || '') + '">' +
                    escapeHtml(truncateQueueText(item.displayText || item.text, 48)) +
                '</span>' + attachBadge +
                '<span class="queue-item-actions">' +
                    '<button type="button" data-act="edit">' + I18n.t('streaming.edit') + '</button>' +
                    '<button type="button" data-act="cancel">' + I18n.t('common.cancel') + '</button>' +
                '</span></div>';
        }
        listEl.innerHTML = html;
    }
    window.renderQueueDock = renderQueueDock;

        function updateStreamingPlaceholder() {
    if (!chatInput) return;
    var sess = activeSessionId && sessionMap[activeSessionId];
    if (!sess) {
        chatInput.placeholder = I18n.t('newchat.inputPlaceholder');
        return;
    }
    if (sess.isStreaming) {
        if (sess.stopRequested) {
            chatInput.placeholder = I18n.t('streaming.stoppingPlaceholder');
            return;
        }
        // 常显按键提示：排队条数已由 queue dock 标题与折叠角标表达，
        // placeholder 专职提示“现在按什么键”（易失知识，比条数更需要常驻）
        chatInput.placeholder = I18n.t('streaming.steerPlaceholder');
        return;
    }
    // 空闲但有任务排队：提示 Enter 续发（冷恢复后不自动发）
    var qn = (sess.messageQueue || []).length;
    if (qn > 0 && !sess.stopRequested && !sess._stoppedTurn) {
        chatInput.placeholder = I18n.t('streaming.queueWaiting', {n: qn});
        return;
    }
    chatInput.placeholder = I18n.t('newchat.inputPlaceholder');
            }
            window.updateStreamingPlaceholder = updateStreamingPlaceholder;

        // 任务排队 strip 事件（右栏底部，跨 Tab 常驻）—— DOM 就绪后绑定一次
        (function bindQueueDockEvents() {
            function bind() {
                var dock = document.getElementById('chatQueueDock');
                if (!dock || dock._queueBound) return;
                dock._queueBound = true;

                $(dock).on('click', '#chatQueueHeader', function(e) {
                    if ($(e.target).closest('#chatQueueClear, #chatQueueToggle').length) return;
                    _queueDockExpanded = !_queueDockExpanded;
                    renderQueueDock();
                });
                $(dock).on('click', '#chatQueueToggle', function(e) {
                    e.stopPropagation();
                    _queueDockExpanded = !_queueDockExpanded;
                    renderQueueDock();
                });
                $(dock).on('click', '#chatQueueClear', function(e) {
                    e.stopPropagation();
                    var sess = activeSessionId && sessionMap[activeSessionId];
                    if (!sess || !sess.messageQueue || !sess.messageQueue.length) return;
                    if (sess.messageQueue.length >= 3) {
                        if (!window.confirm(I18n.t('streaming.clearQueueConfirm', {n: sess.messageQueue.length}))) return;
                    }
                    clearMessageQueue(sess);
                });
                $(dock).on('click', '.queue-item-actions button', function(e) {
                    e.stopPropagation();
                    var act = $(this).attr('data-act');
                    var qid = $(this).closest('.queue-item').attr('data-qid');
                    var sess = activeSessionId && sessionMap[activeSessionId];
                    if (!sess || !qid) return;
                    if (act === 'edit') editQueuedMessageToInput(sess, qid);
                    else if (act === 'cancel') removeQueuedMessage(sess, qid);
                });
            }
            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', bind);
            } else {
                bind();
            }
        })();

            /* ===== Send ===== */
        function sendMessage() {
    var text = getInputText();
    var streamSess = activeSessionId && sessionMap[activeSessionId];

    /* 无可用模型：拦截发送并引导配置 */
    if (typeof modelList !== 'undefined' && modelList && modelList.length === 0) {
        showToast(I18n.t('newchat.noModelHint'), 'error', 2500);
        return;
    }


    /* 空闲 + 有排队：允许空 Enter 续发队头；有内容则入队尾再 drain */
    if (streamSess && !streamSess.isStreaming
        && !streamSess.stopRequested && !streamSess._stoppedTurn
        && streamSess.messageQueue && streamSess.messageQueue.length) {
        if (!text && pendingFiles.length === 0) {
            drainMessageQueue(streamSess);
            chatInput.focus();
            return;
        }
        if (text && text.charAt(0) === '/') {
            showToast(I18n.t('streaming.clearQueueBeforeCommand'), 'error', 2000);
            return;
        }
        // 入队失败（如超限）时仍尝试 drain 已有队列，避免卡住
        enqueueMessage(streamSess, text, pendingFiles.slice());
        drainMessageQueue(streamSess);
        chatInput.focus();
        return;
    }

    if (!text && pendingFiles.length === 0) return;

    /* 活动会话 streaming：Enter=立即插话（steer）；附件降级排队（附件语义属“新任务”） */
    if (streamSess && streamSess.isStreaming) {
        if (streamSess.stopRequested) {
            showToast(I18n.t('streaming.stoppingWaitSend'), 'info', 1500);
            return;
        }
        // 斜杠命令不进排队也不插入，避免语义错乱
        if (text && text.charAt(0) === '/') {
            showToast(I18n.t('streaming.stopBeforeCommand'), 'error', 2000);
            return;
        }
        if (pendingFiles.length > 0) {
            showToast(I18n.t('streaming.steerAttachDemote'), 'info', 1800);
            enqueueMessage(streamSess, text, pendingFiles.slice());
            chatInput.focus();
            return;
        }
        if (!text) return;
        steerMessage(streamSess, text);
        chatInput.focus();
        return;
    }

    /* /clear 命令：先发送到服务端清后端数据，流结束后再清前端 UI */
    if (text === '/clear') {
        clearInput();
        clearAttachmentPreview();
        if (!inChatMode) switchToChatMode();
        setActiveSession(SESSION_ID);
        var clearSess = sessionMap[SESSION_ID];
        if (clearSess) {
            clearSess._pendingClear = true;
            // /clear 会清会话，同步丢掉排队
            if (clearSess.messageQueue && clearSess.messageQueue.length) {
                clearMessageQueue(clearSess);
            }
            sendCommandSilent('/clear', null);
        }
        chatInput.focus();
        return;
    }

    var filesToSend = pendingFiles.slice(); // snapshot
    var displayText = buildDisplayText(text, filesToSend);

    clearInput();
    clearAttachmentPreview();

    // 统一由 sendMessageCore 负责 setActiveSession / 开流，避免重复调度 drain
    var sess = getOrCreateSession(SESSION_ID);
    sendMessageCore(sess, text, filesToSend, { displayText: displayText });
}

function sendWithFormData(sess, text, filesToSend) {
    sendWithFormDataGrouped(sess, text, filesToSend);
}

/* ===== 静默发送斜杠命令 =====
   与 sendMessage 不同：不渲染用户气泡（避免出现 "/rerun" 这样的丑斜杠文本），
   只进入流式等待态并发起命令。供最后一条 AI 消息的“重新运行/继续运行”按钮使用。
   onBeforeSend：发起前的同步回调（如清理旧 DOM）。 */
function sendCommandSilent(cmdText, onBeforeSend) {
    if (!activeSessionId || !sessionMap[activeSessionId]) return;
    var sess = sessionMap[activeSessionId];
    /* 流式进行中禁止重复触发 */
    if (sess.isStreaming) return;
    // 有排队时禁止静默命令插队（/clear 由 sendMessage 先清队列再调用）
    if (sess.messageQueue && sess.messageQueue.length) {
        if (typeof showToast === 'function') {
            showToast(I18n.t('streaming.clearQueueBeforeCommand'), 'error', 2000);
        }
        return;
    }
    if (sess.stopRequested || sess._stoppedTurn) {
        if (typeof showToast === 'function') {
            showToast(I18n.t('streaming.stoppingWaitRetry'), 'info', 1500);
        }
        return;
    }

    if (typeof onBeforeSend === 'function') {
        try { onBeforeSend(sess); } catch (e) {}
    }

    if (!inChatMode) switchToChatMode();

    // 先标记 streaming，再 setActiveSession，避免会话切换时误触发 drain
    sess.isStreaming = true;
    sess.stopRequested = false;
    sess._stoppedTurn = false;
    sess.acceptingStream = true;
    sess._streamClosed = false;
    sess._closedRunId = null;
    isStreaming = true;
    sess.messageStartTime = Date.now();
    setActiveSession(sess.sessionId);
    setBtnStopMode();
    resetStreamState(sess);
    showThinking(sess);
    if (typeof startRoundElapsed === 'function') startRoundElapsed(sess);
    if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();

    sendWithFormDataGrouped(sess, cmdText, []);
}
window.sendCommandSilent = sendCommandSilent;

function sendWithFormDataGrouped(sess, text, filesToSend, options) {
    options = options || {};
    if (sess.eventSource) { sess.eventSource.close(); sess.eventSource = null; }
    var model = (options.model !== undefined && options.model !== null)
        ? options.model
        : getSelectedModel();
    var formData = new FormData();
    formData.append('input', text);
    formData.append('sessionId', sess.sessionId);
    if (model) formData.append('model', model);
    var effort = (options.reasoningEffort !== undefined && options.reasoningEffort !== null)
        ? options.reasoningEffort
        : (typeof getSelectedReasoning === 'function' ? getSelectedReasoning() : '');
    if (effort) formData.append('reasoningEffort', effort);
    // 思考模式独立参数：仅显式 on/off 时携带（'' 不干预，跟随模型/effort 默认）
    var thinking = (options.thinkingMode !== undefined && options.thinkingMode !== null)
        ? options.thinkingMode
        : (typeof getSelectedThinking === 'function' ? getSelectedThinking() : '');
    if (thinking) formData.append('thinkingMode', thinking);
    var selectedAgent = options.selectedAgent;
    if (selectedAgent === undefined && typeof getSelectedAgent === 'function') {
        selectedAgent = getSelectedAgent();
    }
    if (selectedAgent) formData.append('selectedAgent', selectedAgent);
    for (var i = 0; i < filesToSend.length; i++) {
        formData.append('attachments', filesToSend[i].file, filesToSend[i].name);
        formData.append('attachmentTypes', filesToSend[i].attachmentsType || 'file');
    }

    // 标记流式状态，WebSocket onmessage 会处理数据
    sess.isStreaming = true;
    sess.stopRequested = false;
    sess.acceptingStream = true;
    sess._streamClosed = false;
    sess._closedRunId = null;
    if (!sess.messageStartTime) sess.messageStartTime = Date.now();
    if (sess.sessionId === activeSessionId) {
        isStreaming = true;
        setBtnStopMode();
        if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
    }
    resetStreamState(sess);
    showThinking(sess);
    // 兜底起表（外部推送 / 未走 sendMessage 的入口）；已 start 则不重置
    if (typeof startRoundElapsed === 'function') startRoundElapsed(sess);

    $.ajax({
        url: SSE_ENDPOINT,
        method: 'POST',
        data: formData,
        processData: false,
        contentType: false
    }).done(function() {
        // HTTP 响应只有 {"status":"ok"}，实际数据通过 WebSocket 推送
    }).fail(function(err) {
        console.error('Send error:', err);
        finishStream(sess);
    });
}
window.sendWithFormDataGrouped = sendWithFormDataGrouped;

/* ===== WebEvent / SAEP 2.0 Native Dispatcher (Session-Aware) =====
 * 基于 SAEP 2.0 规范的原生事件分发架构，直接解构 event + payload
 */
var AgentEventDispatcher = {
    // 统一将输入归一化为 SAEP 2.0 WebEvent 结构
    toWebEvent(raw) {
        if (!raw || !raw.event) return null;
        return {
            event: raw.event,
            sessionId: raw.sessionId,
            runId: raw.runId,
            taskId: raw.taskId,
            reasonId: raw.reasonId,
            agentName: raw.agentName,
            timestamp: raw.timestamp || Date.now(),
            payload: raw.payload || {}
        };
    }
};

var _STREAM_BATCH_EVENTS = {
    'message.delta': 1,
    'thought.delta': 1
};

/* ===== UI 扩展块渲染（SAEP 2.0 ui.render / ui.patch） ===== */
var _uiBlockStylesInjected = false;
function ensureUiBlockStyles() {
    if (_uiBlockStylesInjected) return;
    _uiBlockStylesInjected = true;
    var css = ''
        + '.ui-block{border:1px solid var(--border-color,#e3e3e8);border-radius:10px;margin:10px 0;overflow:hidden;background:var(--panel-bg,#fff);}'
        + '.ui-block-head{display:flex;align-items:center;justify-content:space-between;padding:8px 12px;background:var(--panel-bg-2,#f6f7f9);border-bottom:1px solid var(--border-color,#e3e3e8);font-weight:600;}'
        + '.ui-block-body{padding:10px 12px;overflow:auto;max-height:360px;}'
        + '.ui-block table{border-collapse:collapse;width:100%;font-size:13px;}'
        + '.ui-block th,.ui-block td{border:1px solid var(--border-color,#e3e3e8);padding:4px 8px;text-align:left;}'
        + '.ui-block-actions{padding:8px 12px;display:flex;gap:8px;flex-wrap:wrap;border-top:1px solid var(--border-color,#e3e3e8);}'
        + '.ui-block-actions button{cursor:pointer;}';
    var style = document.createElement('style');
    style.textContent = css;
    document.head.appendChild(style);
}

function findUiBubble(sess) {
    if (sess.currentBubbleEl) {
        var b = $(sess.currentBubbleEl).closest('.msg-bubble')[0];
        if (b) return b;
    }
    if (typeof ensureAssistantBubble === 'function') ensureAssistantBubble(sess);
    if (sess.currentBubbleEl) return $(sess.currentBubbleEl).closest('.msg-bubble')[0];
    return null;
}

function _uiBlockCells(val) {
    if (val == null) return [];
    if (Array.isArray(val)) return val.map(function (v) { return v == null ? '' : String(v); });
    var s = String(val);
    if (s.indexOf('|') >= 0) return s.split('|').map(function (x) { return x.trim(); });
    return [s];
}

function renderUiBlockBody(body, payload) {
    var type = payload.type || 'card';
    var props = payload.props || {};
    if (type === 'table') {
        var columns = _uiBlockCells(props.columns);
        var rowsSrc = props.rows;
        var rowList = Array.isArray(rowsSrc) ? rowsSrc : (rowsSrc == null ? [] : [rowsSrc]);
        var table = document.createElement('table');
        if (columns.length) {
            var thead = document.createElement('thead');
            var trh = document.createElement('tr');
            for (var c = 0; c < columns.length; c++) {
                var th = document.createElement('th');
                th.textContent = columns[c];
                trh.appendChild(th);
            }
            thead.appendChild(trh);
            table.appendChild(thead);
        }
        var tbody = document.createElement('tbody');
        for (var r = 0; r < rowList.length; r++) {
            var tr = document.createElement('tr');
            var row = _uiBlockCells(rowList[r]);
            for (var c2 = 0; c2 < row.length; c2++) {
                var td = document.createElement('td');
                td.textContent = row[c2];
                tr.appendChild(td);
            }
            tbody.appendChild(tr);
        }
        table.appendChild(tbody);
        body.appendChild(table);
    } else {
        var pre = document.createElement('pre');
        pre.style.whiteSpace = 'pre-wrap';
        pre.textContent = JSON.stringify(props, null, 2);
        body.appendChild(pre);
    }
}

function renderUiBlock(sess, payload) {
    if (!sess || !payload) return;
    ensureUiBlockStyles();
    if (!sess.uiBlocks) sess.uiBlocks = {};
    var blockId = payload.blockId || ('blk-' + Math.random().toString(36).slice(2));
    var bubble = findUiBubble(sess);
    if (!bubble) return;

    var block = document.createElement('div');
    block.className = 'ui-block';
    block.setAttribute('data-block-id', blockId);

    var head = document.createElement('div');
    head.className = 'ui-block-head';
    var titleEl = document.createElement('span');
    titleEl.className = 'ui-block-title';
    titleEl.textContent = payload.title || '';
    head.appendChild(titleEl);
    if (payload.schemaVersion) {
        var ver = document.createElement('span');
        ver.style.opacity = '0.6';
        ver.style.fontSize = '12px';
        ver.textContent = 'v' + payload.schemaVersion;
        head.appendChild(ver);
    }
    block.appendChild(head);

    var body = document.createElement('div');
    body.className = 'ui-block-body';
    renderUiBlockBody(body, payload);
    block.appendChild(body);

    var actions = payload.actions || [];
    if (actions.length) {
        var actBar = document.createElement('div');
        actBar.className = 'ui-block-actions';
        for (var i = 0; i < actions.length; i++) {
            (function(a) {
                var btn = document.createElement('button');
                var kind = a.kind || 'default';
                btn.className = 'btn btn-' + (kind === 'danger' ? 'danger' : (kind === 'primary' ? 'primary' : 'default'));
                btn.textContent = a.label || a.id || 'Action';
                btn.setAttribute('data-action-id', a.id || '');
                btn.addEventListener('click', function() {
                    postUiAction(sess, blockId, a.id, {});
                    btn.disabled = true;
                });
                actBar.appendChild(btn);
            })(actions[i]);
        }
        block.appendChild(actBar);
    }

    // 插入 .msg-bubble 内（.msg-actions 之前），保持稳定不被流式清理移除
    var actionsEl = bubble.querySelector('.msg-actions');
    if (actionsEl) bubble.insertBefore(block, actionsEl);
    else bubble.appendChild(block);

    sess.uiBlocks[blockId] = { el: block, titleEl: titleEl, bodyEl: body };
}

function patchUiBlock(sess, payload) {
    if (!sess || !payload || !sess.uiBlocks) return;
    var ref = sess.uiBlocks[payload.blockId];
    if (!ref) return;
    var op = payload.op || 'replace';
    var path = payload.path || '';
    var value = payload.value;
    if (path === '/title') {
        if (ref.titleEl) ref.titleEl.textContent = (value == null ? '' : String(value));
    } else if (path === '/props') {
        if (ref.bodyEl) {
            ref.bodyEl.innerHTML = '';
            renderUiBlockBody(ref.bodyEl, { type: payload.type || 'card', props: value || {} });
        }
    } else if (op === 'replace') {
        if (ref.bodyEl) {
            ref.bodyEl.innerHTML = '';
            renderUiBlockBody(ref.bodyEl, { type: payload.type || 'card', props: value || {} });
        }
    }
}

function postUiAction(sess, blockId, actionId, formData) {
    if (!sess) return;
    $.post('/web/chat/ui_action', {
        sessionId: sess.sessionId,
        blockId: blockId,
        actionId: actionId,
        formData: JSON.stringify(formData || {})
    }).fail(function(err) {
        console.warn('[ui_action] post failed:', err);
    });
}
window.postUiAction = postUiAction;

function processWebEventNow(sess, webEvt) {
    if (!webEvt || !webEvt.event) return;
    try {
        if (sess.silenceTimer) {
            clearTimeout(sess.silenceTimer);
        }

        removeInlineThinking(sess);

        var event = webEvt.event;
        var p = webEvt.payload || {};
        var taskId = webEvt.taskId;
        var reasonId = webEvt.reasonId;
        var agentName = webEvt.agentName;

        // 存储当前 runId
        if (webEvt.runId) {
            sess.currentRunId = webEvt.runId;
            /* 「重新运行」清屏时摘掉了 user 行上的陈旧 runId（旧轮消息已被后端 /rerun 删除）。
             * 新一轮 runId 一到就补回锚点，否则该行的删除/重跑会退化成按 DOM 行数猜条数。 */
            if (sess.pendingRunIdRow) {
                if (sess.pendingRunIdRow.parentNode && !sess.pendingRunIdRow.getAttribute('data-run-id')) {
                    sess.pendingRunIdRow.setAttribute('data-run-id', webEvt.runId);
                }
                sess.pendingRunIdRow = null;
            }
        }

        // 捕获消息来源标识
        if (p.sourceLabel && !sess.currentSourceLabel) {
            sess.currentSourceLabel = p.sourceLabel;
        }

        // 确定是否需要创建或定位 streamSegment
        var isVisualEvent = (event === 'message.delta' || event === 'thought.delta' || event === 'tool.start');
        var isActionEndWithoutPending = (event === 'tool.end' && !findPendingToolCard(sess, p.callId, null).pending);
        var segment = null;

        if (isVisualEvent || isActionEndWithoutPending) {
            segment = ensureStreamSegment(sess, taskId, p.taskDescription || p.title, agentName);
        } else if (event === 'tool.end' && taskId && sess.taskSegments[taskId]) {
            segment = sess.taskSegments[taskId];
            sess.currentStreamSegment = segment;
        }

        var sourceEl = null;

        switch (event) {
            case 'message.delta':
                sourceEl = appendContentChunk(sess, segment, p.delta || p.content || '', true, reasonId);
                break;

            case 'thought.delta':
                sourceEl = appendReasonChunk(sess, segment, p.delta || '', reasonId, agentName);
                break;

            case 'tool.start':
                sourceEl = appendActionStartChunk(sess, segment, p.name, p.args, p.title || p.name, reasonId, agentName, p.callId);
                break;

            case 'tool.end':
                var toolArgs = p.args || (p.diff ? { diff: p.diff } : {});
                if (p.diff && !toolArgs.diff) toolArgs.diff = p.diff;
                sourceEl = appendActionEndChunk(sess, segment, p.name, p.result || '', toolArgs, p.title || p.name, reasonId, agentName, p.callId, p.lsp);
                // todowrite 已在 handleWebGateChunk 入口统一派发过（会话不存在/未开流时也要更新左侧进度），
                // 此处只补派其它工具，避免同一事件重复触发 todo 面板刷新
                if (window._todoChunkHandlers && p.name !== 'todowrite') {
                    var todoEvent = { toolName: p.name, text: p.result, args: toolArgs, sessionId: sess.sessionId };
                    window._todoChunkHandlers.forEach(function(h) { h(todoEvent); });
                }
                break;

            case 'hitl.pending':
                finishThinkingBlock(sess);
                finishPendingTool(sess);
                sourceEl = appendHitlCard(sess, p.toolName || p.name, p.command, p.callId, p.args, p.toolTitle || p.title || p.toolName, p.comment);
                break;

            case 'task.done':
                if (typeof applyTaskDoneChunk === 'function') {
                    applyTaskDoneChunk(sess, {
                        taskId: p.taskId || taskId,
                        parentTaskId: p.parentTaskId,
                        status: p.status,
                        taskDescription: p.title || p.taskDescription
                    });
                }
                sourceEl = segment && segment.groupEl;
                break;

            case 'system.trace':
                finishThinkingBlock(sess);
                finishPendingTool(sess);
                sourceEl = appendTraceBadge(sess, { model: p.model, totalTokens: p.totalTokens, elapsedSeconds: p.elapsedSeconds, text: p.finalAnswer });
                break;

            case 'system.context':
                if (typeof updateContextIndicator === 'function') {
                    // updateContextIndicator 读取 totalTokens 与 args.contextLength/args.cacheRate，
                    // 必须按该结构透传，否则上下文条恒显 "/ 0 (0%)" 且 Cache% 丢失。
                    updateContextIndicator({
                        totalTokens: p.tokens,
                        args: {
                            contextLength: p.contextLimit,
                            cacheRate: p.cacheRate
                        }
                    }, sess);
                }
                break;

            case 'system.command':
                finishThinkingBlock(sess);
                finishPendingTool(sess);
                sourceEl = appendCommandOutput(sess, p.command);
                break;

            case 'system.rewind':
                finishThinkingBlock(sess);
                finishPendingTool(sess);
                handleRewind(sess, p.count || 1);
                break;

            case 'system.error':
                finishThinkingBlock(sess);
                sourceEl = appendErrorChunk(sess, p.message || '未知错误', taskId, p.title, agentName, p.code);
                break;

            case 'ui.render':
                renderUiBlock(sess, p);
                break;

            case 'ui.patch':
                patchUiBlock(sess, p);
                break;
        }

        // task-group 展开状态更新
        if (segment && segment.taskId && event !== 'task.done') {
            markTaskGroupUpdated(sess, segment);
        }

        if (event !== 'system.rewind' && event !== 'system.context') {
            scrollForStreamEvent(sess, { type: event }, sourceEl, false);
        }

        sess.silenceTimer = setTimeout(function() {
            if (sess.isStreaming && !sess.thinkingBlockEl) showInlineThinking(sess);
        }, 1000);
    } catch (e) {
        console.warn('[processWebEventNow]', e);
    }
}

/* 合并同类型连续 message.delta / thought.delta：减少 DOM 调度次数，保持事件保序 */
function coalesceQueuedEvents(queue) {
    if (!queue || queue.length <= 1) return queue || [];
    var out = [];
    for (var i = 0; i < queue.length; i++) {
        var e = queue[i];
        var prev = out.length ? out[out.length - 1] : null;
        var isMergeable = prev && prev.event === e.event && (e.event === 'message.delta' || e.event === 'thought.delta')
            && prev.reasonId === e.reasonId
            && prev.taskId === e.taskId
            && prev.agentName === e.agentName
            && prev.runId === e.runId;

        if (isMergeable) {
            prev.payload.delta = (prev.payload.delta || '') + (e.payload.delta || '');
            if (!prev.payload.sourceLabel && e.payload.sourceLabel) prev.payload.sourceLabel = e.payload.sourceLabel;
            if (!prev.runId && e.runId) prev.runId = e.runId;
            if (!prev.agentName && e.agentName) prev.agentName = e.agentName;
        } else {
            out.push(e);
        }
    }
    return out;
}

    function drainWebEventQueue(sess, flushAll) {
    if (!sess || !sess._chunkQueue || !sess._chunkQueue.length) {
        if (sess) sess._chunkDrainScheduled = false;
        return;
    }
    sess._chunkDrainScheduled = false;
    var batch = coalesceQueuedEvents(sess._chunkQueue);
    sess._chunkQueue = [];
    // 非 flush 时每帧最多处理一定数量，避免超长队列堵主线程
    var limit = flushAll ? batch.length : Math.min(batch.length, 40);
    for (var i = 0; i < limit; i++) {
        processWebEventNow(sess, batch[i]);
    }
    if (limit < batch.length) {
        sess._chunkQueue = batch.slice(limit).concat(sess._chunkQueue || []);
        scheduleWebEventDrain(sess);
    }
}
    window.drainWebEventQueue = drainWebEventQueue;

    function scheduleWebEventDrain(sess) {
        if (!sess || sess._chunkDrainScheduled) return;
        sess._chunkDrainScheduled = true;
        requestAnimationFrame(function() {
            drainWebEventQueue(sess, false);
        });
    }
    
    function onWebEvent(sess, raw) {
        if (!sess || !raw) return;
        var webEvt = AgentEventDispatcher.toWebEvent(raw);
        if (!webEvt) return;
        
        // 高频流式文本走队列批处理；控制类消息立即处理（先排空队列保序）
        if (_STREAM_BATCH_EVENTS[webEvt.event]) {
            if (!sess._chunkQueue) sess._chunkQueue = [];
            sess._chunkQueue.push(webEvt);
            scheduleWebEventDrain(sess);
            return;
        }
        if (sess._chunkQueue && sess._chunkQueue.length) {
            drainWebEventQueue(sess, true);
        }
        processWebEventNow(sess, webEvt);
    }

function finishStream(sess) {
    var wasStreaming = sess.isStreaming;
    // 必须在复位 stopRequested 前读取：Stop 窗口期入队/结束后误 drain 的防护
    var wasStopped = !!sess.stopRequested || !!sess._stoppedTurn;
    sess.isStreaming = false;
    sess.stopRequested = false;
    // 关闭流接收：done 之后的迟到 chunk 不得再把 UI 拉起
    sess.acceptingStream = false;
    // 仅标记“本页本轮已收尾”，刷新后不会带上该标记
    sess._streamClosed = true;
    sess._pendingStreamChunks = null;
    if (sess._stopFallbackTimer) {
        clearTimeout(sess._stopFallbackTimer);
        sess._stopFallbackTimer = null;
    }
    if (sess.silenceTimer) { clearTimeout(sess.silenceTimer); sess.silenceTimer = null; }

    // 先排空该会话尚未处理的 chunk 队列，避免丢尾部文本
        if (typeof drainWebEventQueue === 'function') drainWebEventQueue(sess, true);

    // 记录收尾时所属的 runId：用于区分“本轮的迟到尾包”与“后端新开的一轮流”（见 canReopenClosedStream）。
    // 必须在排空队列之后取：runId 由 processWebEventNow 写入 currentRunId，若队列里还压着本轮的
    // delta（短轮次可能一帧都没来得及 drain），提前取会拿到 null/上一轮的值，导致本轮迟到尾包被
    // 误判成“新一轮”而把已收尾的 UI 重新拉起。
    sess._closedRunId = sess.currentRunId || null;

    // steer 防御兜底：后端 onAgentEnd 的 steer_dropped 与 done 并行推送，若 5s 内仍未收到
    // applied/dropped（事件丢失、连接断开等），将待生效项就地转排队，杜绝“已接受但永不生效”
    if (sess.steerPending && sess.steerPending.length && !sess._steerFallbackTimer) {
        sess._steerFallbackTimer = setTimeout(function() {
            sess._steerFallbackTimer = null;
            if (sess.steerPending && sess.steerPending.length) {
                handleSteerEvent(sess, 'system.steer_dropped', {
                    texts: sess.steerPending.map(function(it) { return it.text; })
                });
            }
        }, 5000);
    }

    // --- 强刷逻辑：必须在 resetStreamState 之前执行 ---
    // 1. 取消还没跑的动画帧
    if (sess.contentRafId) { cancelAnimationFrame(sess.contentRafId); sess.contentRafId = null; }
    if (sess.reasonRafId) { cancelAnimationFrame(sess.reasonRafId); sess.reasonRafId = null; }
    // Cancel per-reasonId RAF IDs
    for (var _rid in sess.reasonGroups) {
        if (sess.reasonGroups[_rid].reasonRafId) {
            cancelAnimationFrame(sess.reasonGroups[_rid].reasonRafId);
            sess.reasonGroups[_rid].reasonRafId = null;
        }
        if (sess.reasonGroups[_rid].groupRafId) {
            cancelAnimationFrame(sess.reasonGroups[_rid].groupRafId);
            sess.reasonGroups[_rid].groupRafId = null;
        }
    }

    // 2. 取消文本 run 的待执行 RAF（真正的 Markdown 升级交给 finishThinkingBlock / 下方统一 finalize）
    if (sess.reasonBuffer) {
        // 旧路径：无 reasonGroups 时可能直接写在 bubble 上
        var el = ensureAssistantBubble(sess);
        if (typeof finalizeMdElement === 'function') finalizeMdElement(el, sess.reasonBuffer);
        else {
            el.setAttribute('data-md-raw', sess.reasonBuffer);
            el.innerHTML = renderMd(sess.reasonBuffer);
        }
    }
    for (var _rid in sess.reasonGroups) {
        var group = sess.reasonGroups[_rid];
        if (group.textRuns && group.textRuns.length) {
            for (var ri = 0; ri < group.textRuns.length; ri++) {
                var run = group.textRuns[ri];
                if (run.rafId) { cancelAnimationFrame(run.rafId); run.rafId = null; }
                // 仅升级文本 run；思考块留给 finishThinkingBlock，避免双重 marked
                if (run.el && run.buffer) {
                    if (typeof finalizeMdElement === 'function') finalizeMdElement(run.el, run.buffer);
                    else {
                        run.el.setAttribute('data-md-raw', run.buffer);
                        run.el.innerHTML = renderMd(run.buffer);
                    }
                }
            }
        } else if (group.groupContentEl && group.groupBuffer) {
            if (typeof finalizeMdElement === 'function') finalizeMdElement(group.groupContentEl, group.groupBuffer);
            else {
                group.groupContentEl.setAttribute('data-md-raw', group.groupBuffer);
                group.groupContentEl.innerHTML = renderMd(group.groupBuffer);
            }
        }
    }
    // ---------------------------------------------------

    removeThinking(sess);
    purgeInlineThinking(sess);
    // 关闭所有未完成的 reasonId 分组思考块（内部会 finalize 一次思考内容）
    for (var _rid in sess.reasonGroups) {
        if (sess.reasonGroups[_rid].thinkingBlockEl) {
            finishThinkingBlock(sess, _rid);
        }
    }
    finishThinkingBlock(sess);
    finishPendingTool(sess);
    sess.hitlApprovedCards = {};

    // 结算全部 task-group：非 error → done（绿勾）；error 保留红叉
    if (typeof finalizeTaskGroups === 'function') finalizeTaskGroups(sess);
    // 本轮总计时定格（Context 条）
    if (typeof stopRoundElapsed === 'function') stopRoundElapsed(sess);

    if (sess.eventSource) { sess.eventSource.close(); sess.eventSource = null; }

    // 保存行引用（currentBubbleEl 可能在后续清理中被移除）
    var doneRow = sess.currentBubbleEl ? $(sess.currentBubbleEl).closest('.msg-row')[0] : null;

    // 条件显示助手消息时间戳：仅当有实际文本输出时才显示
    var hasTextOutput = !!(sess.reasonBuffer && sess.reasonBuffer.trim());
    if (!hasTextOutput && doneRow) {
        $(doneRow).find('.msg-bubble .md-content').each(function() {
            if (this.getAttribute('data-md-raw') || (this.innerText && this.innerText.trim())) {
                hasTextOutput = true;
                return false;
            }
        });
    }
    if (hasTextOutput) {
        setAssistantTime(sess, sess._lastCreatedAt || Date.now());
    }
    sess._lastCreatedAt = null;

    // 流式结束：切换 class 并显示操作按钮
    if (doneRow) {
        $(doneRow).removeClass('streaming').addClass('done');
        $(doneRow).find('.msg-actions').show();
    }

    // 清理未落到任何实际内容块的前置空白缓存：这些空白没有对应的服务端正文，
    // 不应创建 DOM；已关联到正文的空白此前会原样拼回 buffer，绝不 trim。
    sess.pendingReasonWhitespace = {};
    sess.pendingGroupWhitespace = {};
    sess.pendingThinkingWhitespace = '';

    // 清理空的 md-content 节点（无 data-md-raw、无实际文本、无子元素）。
    // trim 仅用于判断 DOM 是否可回收，不会回写或修改服务端流式内容。
    if (doneRow) {
        $(doneRow).find('.msg-bubble .md-content').each(function() {
            if (!this.getAttribute('data-md-raw') && (!this.innerText || !this.innerText.trim()) && !$(this).children().length) {
                $(this).remove();
            }
        });
        // 先移除没有实际内容的思考块外壳。仅按 reason-group 的直接子节点数判断，
        // 会把包含空 reason-group-think 的分组误判为非空而残留。
        $(doneRow).find('.reason-group > .reason-group-think').each(function() {
            var body = $(this).find('.reason-group-think-body')[0];
            var hasContent = body && ((body.textContent && /\S/.test(body.textContent)) || $(body).children().length);
            if (!hasContent) {
                $(this).remove();
            }
        });
        // 回收没有任何实际子内容的 reason-group，避免留下空白边框。
        $(doneRow).find('.reason-group').each(function() {
            if (!$(this).children().length) {
                $(this).remove();
            }
        });
        // 空 task-group 仅可能由上一步移除最后一个 reason-group 产生，随即一并回收。
        $(doneRow).find('.task-group').each(function() {
            if (!$(this).find('.task-group-body').children().length) {
                $(this).remove();
            }
        });
    }

    // 清除客户端计时（已由 trace 类型的服务端耗时替代）
    if (sess.messageStartTime) {
        sess.messageStartTime = null;
    }

    // 清除消息来源标识，避免污染下一条流式响应
    sess.currentSourceLabel = null;

    // /clear 命令处理完毕：清空前端对话 UI
    if (sess._pendingClear) {
        sess._pendingClear = false;
        sess.messageQueue = [];
        // /clear 语义为清空会话上下文与界面，残留的待生效插话一并作废
        sess.steerPending = [];
        if (sess._steerFallbackTimer) { clearTimeout(sess._steerFallbackTimer); sess._steerFallbackTimer = null; }
        if (typeof schedulePersistMessageQueue === 'function') schedulePersistMessageQueue(sess);
        if (sess.sessionId === activeSessionId && typeof renderQueueDock === 'function') {
            renderQueueDock();
        }
        $(sess.container).empty();
        sess.currentBubbleEl = null;
        sess.reasonBuffer = '';
        sess.thinkingBuffer = '';
        sess.thinkingBlockEl = null;
        sess.thinkingBodyMdEl = null;
        sess.thinkingBodyWrapEl = null;
        sess.pendingToolCard = null;
        sess.pendingToolStarted = false;
        sess.hitlApprovedCards = {};
        sess.userMsgCounter = 0;
        // 清空后不再展示上轮 Context / 总计时
        sess.roundStartedAt = null;
        sess.roundEndedAt = null;
        sess.contextTokens = null;
        sess.contextLength = null;
        if (sess.sessionId === activeSessionId && typeof resetContextIndicator === 'function') {
            resetContextIndicator();
        }
    }

    // resetStreamState 会清空 buffer，所以必须在上面强刷完后再调
    resetStreamState(sess);

    if (sess.sessionId === activeSessionId) {
        isStreaming = false;
        setBtnSendMode();
        // 只有在活动会话才滚动
        if (shouldScheduleSessionScroll(sess)) {
            scrollToBottom(true);
        }
        chatInput.focus();
        if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
    }

    // 刷新任务面板
    if (window.loadTodos) window.loadTodos();

    // 任务完成通知（页面在后台时弹通知 + 播放提示音）
    setTimeout(window._notifyTaskComplete, 500);

    // 当前轮结束后按 FIFO 自动发送排队消息（仅 active 会话）
    // setTimeout(0) 让本轮 UI 先收尾，避免与 setBtnSendMode 竞态
    if (wasStopped) {
        // Stop 收尾：丢弃残留排队，避免停止后仍自动续发
        if (sess.messageQueue && sess.messageQueue.length) {
            sess.messageQueue = [];
            if (typeof schedulePersistMessageQueue === 'function') schedulePersistMessageQueue(sess);
            if (sess.sessionId === activeSessionId && typeof renderQueueDock === 'function') {
                renderQueueDock();
            }
        }
        // 同理丢弃“待生效”插话并撤掉防御定时器：否则 5s 后会被转成排队消息自动续发，
        // 与用户刚刚点下 Stop 的意图相反
        if (sess._steerFallbackTimer) { clearTimeout(sess._steerFallbackTimer); sess._steerFallbackTimer = null; }
        if (sess.steerPending && sess.steerPending.length) {
            sess.steerPending = [];
            if (sess.sessionId === activeSessionId && typeof renderQueueDock === 'function') {
                renderQueueDock();
            }
        }
        sess._stoppedTurn = false;
    } else if (sess.messageQueue && sess.messageQueue.length) {
        setTimeout(function() {
            if (typeof drainMessageQueue === 'function') drainMessageQueue(sess);
        }, 0);
    } else {
        sess._stoppedTurn = false;
    }

    // 本轮已收尾：若末尾仍是用户消息（无 AI 回复），显示「继续运行」入口
    if (typeof updateUserRerunButtons === 'function' && sess.container) {
        updateUserRerunButtons(sess.container);
    }
}
window.finishStream = finishStream;

/* ===== WebSocket 单连接 ===== */
var webGateSocket = null;
var webGateReconnectAttempts = 0;
var webGateHeartbeatTimer = null;
var WEBGATE_MAX_RECONNECT = 10;
var WEBGATE_PENDING_CHUNK_MAX = 300;

/* 历史消息加载期间先缓存流式 chunk，加载完再回放，避免被 DOM 重建冲掉 */
function bufferPendingStreamChunk(sess, chunk) {
    if (!sess || !chunk) return;
    if (!sess._pendingStreamChunks) sess._pendingStreamChunks = [];
    var buf = sess._pendingStreamChunks;

    /* 高频 delta 就地合并再计数：任务运行中刷新页面时，正文/思考的 delta 可能在
     * 历史加载的这一小段窗口里瞬间打满上限，若按条数 shift 会从最早的包开始丢，
     * 于是「历史补上了、实时开头却缺了一截」，反而制造出新的空洞。 */
    var ev = chunk.event;
    if (ev === 'message.delta' || ev === 'thought.delta') {
        var last = buf.length ? buf[buf.length - 1] : null;
        if (last && last.event === ev && last.payload && chunk.payload &&
                last.reasonId === chunk.reasonId && last.taskId === chunk.taskId) {
            var prev = (last.payload.delta != null) ? last.payload.delta : (last.payload.content || '');
            var cur = (chunk.payload.delta != null) ? chunk.payload.delta : (chunk.payload.content || '');
            last.payload.delta = prev + cur;
            if (last.payload.content != null) last.payload.content = last.payload.delta;
            return;
        }
    }

    if (buf.length >= WEBGATE_PENDING_CHUNK_MAX) {
        buf.shift();
    }
    buf.push(chunk);
}

function flushPendingStreamChunks(sess) {
    var buf = sess && sess._pendingStreamChunks;
    if (!sess) return;
    sess._pendingStreamChunks = null;
    if (!buf || !buf.length) return;
    for (var i = 0; i < buf.length; i++) {
        handleWebGateChunk(buf[i]);
    }
}
window.flushPendingStreamChunks = flushPendingStreamChunks;

/* ===== 最后一轮执行过程回放 =====
 *
 * ndjson 只落用户输入与最终回答，中间的思考、插话与工具调用只存在于后端 ReActTrace 的
 * WorkingMemory 里。刷新页面 / 切换会话后，/web/chat/messages/last-trace 把最近一轮的过程取回
 * ——一条线性事件序列（steer / thinking / note / tool，严格保持 WorkingMemory 原序）——
 * 这里逐条合成与实时流同构的 thought.delta / message.delta / tool.start / tool.end 事件，
 * 交给同一套渲染管线（processWebEventNow）铺开，使最后一条 AI 消息也能像流式那样展开执行过程。
 *
 * 为什么必须逐段回放而不是每轮一份「思考 + 正文」：
 *   流式聚合会在一条 AssistantMessage 里注入多对 <think> 标记（推理与正文交替就反复开合）。
 *   把它压成两个字符串，第二段思考就会被当成答案铺进气泡、段间也没有边界，表现就是
 *   「几个思考消息和答案消息合到了一起」。后端已按段拆好，这里只负责保序转发。
 *
 * 与实时流的冲突处理（任务运行中刷新是常态）：
 *  - 时序：必须在 _loadingHistory 置 false 之后、flushPendingStreamChunks 之前回放，
 *    保证「历史文本 → 回放过程 → 实时增量」三段顺序不乱；
 *  - 去重：WS 连接通常早于 last-trace 返回，[连上, 快照] 区间的事件两边都有。
 *    以 callId 为锚按「组」去重（见 collectReplayedGroups）；
 *  - 幂等：同一 runId 只回放一次，防止重复 loadMessages 叠加。
 *
 * DOM 形态必须与流式一致：实时流的一整轮只有一个 .msg-row.assistant，正文块、思考块、工具卡
 * 全在它的 .msg-content 内，行尾仅一套 .msg-actions。因此本轮已结束时，回放内容要并入历史那条
 * AI 气泡行（插在最终回答之前），而不是另起一行 —— 否则会多出一套复制/重跑/删除按钮，且
 * calcServerCount 按 .msg-row 计数换算 rewind 条数，会让删除多删一条真实消息。
 */
function replayLastTrace(sess, data, anchorRow) {
    if (!sess || !data || !data.aligned) return false;

    var events = data.events || [];
    if (!events.length) return false;

    // 同一轮只回放一次
    var runId = data.runId || '';
    if (sess._traceReplayedRunId && sess._traceReplayedRunId === runId) return false;
    sess._traceReplayedRunId = runId;

    /* 任务仍在跑时不并行：此刻 ndjson 里那条末尾 AI 气泡属于上一轮，本轮的过程连同随后的实时
     * 增量必须待在自己的行里 —— 那一行就是本轮的 AI 行，收尾时由 finishStream 正常显示操作按钮。 */
    var running = !!data.running;
    var canMerge = !running && anchorRow && anchorRow.parentNode === sess.container;

    // 回放前清干净流状态，让卡片直接落在会话容器上而不是残留的 task-group 里
    resetStreamState(sess);
    // 回放期间让 currentRunId 生效：steer note / 工具卡都靠 data-run-id 被 /clear 与 rewind 成批清除
    sess.currentRunId = runId || sess.currentRunId;

    /* 插话去重表：插话没有 callId，只能按原文比对。实时路径（steer_applied）与回放都可能
     * 渲染同一条，取 DOM 里已有的 .steer-note-text 建计数表，命中即消耗一个名额。
     * 用计数而非布尔，避免用户连发两条相同文本时被同一个 DOM 节点抵消掉两次。 */
    var steerSeen = collectRenderedSteers(sess);
    var skipGroups = collectReplayedGroups(sess, events);
    var replayed = 0;

    for (var i = 0; i < events.length; i++) {
        var e = events[i];
        if (!e || !e.kind) continue;

        /* 插话按原位回放，且不参与分组去重：某张工具卡已由实时路径渲染过，
         * 不代表这条插话也渲染过（它可能发生在 WS 连上之前）。 */
        if (e.kind === 'steer') {
            replayed += replaySteers(sess, [e.text], steerSeen);
            continue;
        }

        // 该组的工具卡已全部由实时流渲染 ⇒ 同组的思考/正文当时也已上屏，整组跳过
        if (skipGroups[e.group]) continue;

        /* 同一条 AssistantMessage 的思考、正文与工具卡共享 reasonId 才能聚成一组；
         * 跨消息必须换 reasonId，否则后一条的思考会挤进已收尾的思考块里。
         * 组内多段交替是安全的：正文到来时 finishThinkingBlock 会把思考块收尾并置空，
         * 下一段思考于是新建一个块，按 DOM 顺序追加在正文之后。 */
        var reasonId = 'replay-' + (runId || 'last') + '-g' + (e.group || 1);

        if (e.kind === 'thinking') {
            if (!e.text) continue;
            processWebEventNow(sess, makeReplayEvent(sess, runId, reasonId, 'thought.delta', { delta: e.text }));
            replayed++;
            continue;
        }

        if (e.kind === 'note') {
            if (!e.text) continue;
            processWebEventNow(sess, makeReplayEvent(sess, runId, reasonId, 'message.delta', { delta: e.text }));
            replayed++;
            continue;
        }

        if (e.kind !== 'tool' || !e.callId) continue;

        processWebEventNow(sess, makeReplayEvent(sess, runId, reasonId, 'tool.start', {
            name: e.name,
            title: e.title || e.name,
            args: e.args || {},
            callId: e.callId
        }));

        if (e.done) {
            var result = e.result || '';
            if (e.resultTruncated) {
                result += '\n\n… 内容过长已截断（共 ' + (e.resultChars || 0) + ' 字符）';
            } else if (e.omitted) {
                result = '… 本轮结果体积过大，已省略';
            }
            /* args 必须用后端加工过的 endArgs：实时流的 tool.end 走 ToolPresentationFilter，
             * edit 的 edits 已换成 diff、write/todowrite 的正文已提到 result 并从 args 摘除。
             * 若这里仍传 tool.start 的原始 args，diff 视图会是空的、正文会重复铺一遍。 */
            processWebEventNow(sess, makeReplayEvent(sess, runId, reasonId, 'tool.end', {
                name: e.name,
                title: e.title || e.name,
                args: e.endArgs || e.args || {},
                diff: e.diff || null,
                lsp: e.lsp || null,
                result: result,
                callId: e.callId
            }));
        } else if (!running) {
            /* 无结果且任务已不在跑 = 被中断。回放没有后续事件来收尾这张卡，
             * 放着会永久转圈；也不能标成绿勾假称成功，故落到 warn 态。
             * 任务仍在跑时保持 pending，等实时的 tool.end 来精确配对完成。 */
            markReplayUnfinishedTool(sess, e.callId);
        }

        replayed++;
    }

    if (!replayed) return false;

    // 思考块的 spinner 需要显式收尾（回放以思考结尾时，没有后续事件帮它停转）
    finishReplayThinkingBlocks(sess);

    var replayRow = sess.currentBubbleEl ? $(sess.currentBubbleEl).closest('.msg-row')[0] : null;

    if (canMerge && replayRow && replayRow !== anchorRow) {
        mergeReplayRowInto(sess, replayRow, anchorRow);
        // 内容已搬走，流状态指向的节点已失效
        resetStreamState(sess);
    } else if (replayRow && !running) {
        markOrphanReplayRow(replayRow);
    }

    return true;
}
window.replayLastTrace = replayLastTrace;

/* 按「组」判定哪些事件已由实时流渲染过。
 *
 * 一组 = 同一条 AssistantMessage 产出的思考、正文与工具卡。去重只能靠 callId（思考与正文
 * 没有任何可比对的标识），但不能逐卡跳过：那会把同一组的思考/正文一并呑掉。
 * 故以组为单位：组内有工具且全部已在 DOM 里 ⇒ 当时实时流已把这一组铺完，整组跳过；
 * 只要有一张缺失（或本组完全无工具 —— 纯思考消息就是这种）就整组回放。
 *
 * 残留缺口：运行中刷新且某个纯思考消息恰好落在 [WS 连上, 快照] 区间时，会重复一份。
 * 宁可重复也不能丢：这类消息正是推理模型把答案写进 reasoning 通道的产物，丢了就是一大段空白。 */
function collectReplayedGroups(sess, events) {
    var skip = {};
    if (!sess || !sess.container) return skip;

    var stat = {};
    for (var i = 0; i < events.length; i++) {
        var e = events[i];
        if (!e || e.kind !== 'tool' || !e.callId) continue;
        var g = e.group || 1;
        if (!stat[g]) stat[g] = { total: 0, seen: 0 };
        stat[g].total++;
        if (sess.container.querySelector('[data-call-id="' + e.callId + '"]')) stat[g].seen++;
    }

    for (var g2 in stat) {
        if (!Object.prototype.hasOwnProperty.call(stat, g2)) continue;
        if (stat[g2].total > 0 && stat[g2].total === stat[g2].seen) skip[g2] = true;
    }
    return skip;
}

/* 收尾回放产生的所有思考块。
 *
 * 回放每条消息自己一个 reasonId，无参的 finishThinkingBlock(sess) 只走旧式
 * sess.thinkingBlockEl 分支，构不到分组内的块 —— 以思考结尾的组会永久转圈。
 * 只收 replay- 开头的：任务仍在跑时，实时流那些组还要继续追加，不能替它们收尾。 */
function finishReplayThinkingBlocks(sess) {
    if (typeof finishThinkingBlock !== 'function') return;

    var segment = sess.currentStreamSegment;
    if (segment && segment.reasonEntries && typeof streamReasonKey === 'function') {
        for (var rid in segment.reasonEntries) {
            if (!Object.prototype.hasOwnProperty.call(segment.reasonEntries, rid)) continue;
            if (rid.indexOf('replay-') !== 0) continue;
            if (segment.reasonEntries[rid].thinkingBlockEl) {
                finishThinkingBlock(sess, streamReasonKey(segment, rid));
            }
        }
    }

    // 无分组的旧式思考块（reasonId 缺失时的降级路径）同样要收
    finishThinkingBlock(sess);
}

/* 把回放行的内容并入历史 AI 气泡行：过程在前、最终回答在后，合成流式那样的单行结构。
 * 复制按钮逆序扫 .md-content 取首个非空 data-md-raw，过程插在前面才不会被复制成「最终答案」。 */
function mergeReplayRowInto(sess, replayRow, anchorRow) {
    var from = $(replayRow).find('.msg-bubble > .msg-content')[0];
    var to = $(anchorRow).find('.msg-bubble > .msg-content')[0];
    if (!from || !to) return false;

    var frag = document.createDocumentFragment();
    while (from.firstChild) {
        var node = from.firstChild;
        from.removeChild(node);
        // 流式中预建的内联等待指示器属于运行态装饰，历史里不该出现
        if (node.nodeType === 1 && $(node).hasClass('inline-thinking')) continue;
        frag.appendChild(node);
    }
    to.insertBefore(frag, to.firstChild);

    /* runId 要补到锚行上：历史行由 loadMessages 建成时 sess.currentRunId 还是空的
     * （回放才把它填上），行上就没有 data-run-id。缺了它，「重新运行」只能走兼容分支
     * 删掉这一行本身，同一轮里其它带 data-run-id 的行（被中断的孤立回放行、
     * 流重开后新起的气泡行）会留在屏上，与后端回退不一致。 */
    var runId = replayRow.getAttribute('data-run-id');
    if (runId && !anchorRow.getAttribute('data-run-id')) {
        anchorRow.setAttribute('data-run-id', runId);
    }

    $(replayRow).remove();
    if (sess) sess.inlineThinkingEl = null;
    return true;
}

/* 独立成行的回放过程（本轮被中断、无最终回答）：这一行在 ndjson 里没有对应记录，
 * 既不能给它复制/重跑/删除按钮（删除按 .msg-row 计数换算 rewind，会多删一条真实消息），
 * 也不能显示编造的时间戳。打 data-replay 标记供 calcServerCount 与末行判定跳过。 */
function markOrphanReplayRow(row) {
    row.setAttribute('data-replay', '1');
    $(row).find('.msg-actions').remove();
    $(row).find('.msg-time').remove();
    $(row).find('.inline-thinking').remove();
    if (typeof updateUserRerunButtons === 'function') {
        updateUserRerunButtons(row.parentNode);
    }
}

/* 把回放出来的未完成工具卡标为 warn（中断），并从 pending 中摘除 */
function markReplayUnfinishedTool(sess, callId) {
    var match = findPendingToolCard(sess, callId, null);
    var card = (match && match.pending) ? match.pending.card : null;
    if (!card) return;
    var icon = $(card).find('.tool-status-icon')[0];
    if (icon) {
        icon.className = 'tool-status-icon warn';
        icon.innerHTML = '<i class="layui-icon layui-icon-tips"></i>';
    }
    card.setAttribute('title', window.I18n ? I18n.t('msg.toolInterrupted') : '');
    if (match.key && sess.pendingToolCards) delete sess.pendingToolCards[match.key];
}

/* 扫 DOM 里已渲染的插话原文，建「文本 → 份数」计数表供回放去重。
 * 不用属性选择器匹配文本（插话含引号/换行会把选择器打碎），改为逐个节点比 textContent。 */
function collectRenderedSteers(sess) {
    var seen = {};
    if (!sess || !sess.container) return seen;
    var nodes = sess.container.querySelectorAll('.steer-note .steer-note-text');
    for (var i = 0; i < nodes.length; i++) {
        var key = nodes[i].textContent || '';
        seen[key] = (seen[key] || 0) + 1;
    }
    return seen;
}

/* 回放一批插话（运行中的用户纠偏）。
 *
 * 插话是零持久化的：不写 ndjson，只存在于后端 WorkingMemory。不回放它，刷新后用户会
 * 看到 AI 忽然改了方向却找不到自己那句话。渲染走与实时路径同一个 appendSteerNote，
 * 保证它落在 AI 气泡内部而不是变成独立的 .msg-row（后者会多出一套操作按钮，
 * 并让 calcServerCount 按 .msg-row 换算的 rewind 条数多删一条真实消息）。
 *
 * @return 实际上屏条数（计入 replayed，否则全量去重时会把刚建的气泡当空行丢下） */
function replaySteers(sess, texts, seen) {
    if (!texts || !texts.length) return 0;
    if (typeof appendSteerNote !== 'function') return 0;

    var n = 0;
    for (var i = 0; i < texts.length; i++) {
        var text = texts[i];
        if (!text) continue;

        // 实时路径已渲染过同文本：消耗一个名额并跳过
        if (seen && seen[text] > 0) {
            seen[text]--;
            continue;
        }

        if (appendSteerNote(sess, text)) n++;
    }
    return n;
}

/* 合成一个与实时流同构的 webEvent；replay 标记供渲染层区分「回放」与「实时」 */
function makeReplayEvent(sess, runId, reasonId, event, payload) {
    return {
        event: event,
        payload: payload,
        sessionId: sess.sessionId,
        runId: runId || null,
        taskId: null,
        reasonId: reasonId,
        agentName: null,
        replay: true
    };
}


/**
 * 已收尾（_streamClosed）的会话能否因新到的 chunk 重新开流。
 *
 * <p>判据是事件语义而非时间：后端每次任务运行都有独立的 runId（AgentEvent.runId），
 * 因此“新一轮”= 到达的事件带着与收尾那一轮不同的 runId。同一 runId 的后续事件是
 * 本轮 done 之后的迟到尾包，无论隔多久都应丢弃；不同 runId 则说明后端已为本会话新开了
 * 一轮流（HITL 恢复、命令触发任务、Loop 续跑等）而 reset 信号丢失或未覆盖，必须自愈，
 * 否则会话永久哑掉：后端一直在推，前端全部静默丢弃。</p>
 * <p>无 runId 的事件不足以判定新一轮，保持丢弃。用户显式 Stop 的轮次（_stoppedTurn）
 * 也不自愈，避免被中断流的尾包反弹回 UI。</p>
 */
function canReopenClosedStream(sess, webEvt) {
    if (!sess || sess._stoppedTurn || sess.stopRequested) return false;
    var runId = webEvt && webEvt.runId;
    if (!runId) return false;
    return runId !== sess._closedRunId;
}

/** 有流式消息到来时，打开本会话的接收/展示状态 */
function openStreamFromIncoming(sess) {
    if (!sess || sess.stopRequested) return false;
    sess._streamClosed = false;
    sess._closedRunId = null;
    sess.acceptingStream = true;
    if (sess.isStreaming) return true;
    sess.isStreaming = true;
    sess.stopRequested = false;
    if (!sess.messageStartTime) sess.messageStartTime = Date.now();
    if (sess.sessionId === activeSessionId) {
        isStreaming = true;
        setBtnStopMode();
        if (!inChatMode) switchToChatMode();
        if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
    }
    resetStreamState(sess);
    showThinking(sess);
    if (typeof startRoundElapsed === 'function') startRoundElapsed(sess);
    if (typeof updateHistoryUI === 'function') updateHistoryUI();
    return true;
}

function handleWebGateChunk(raw) {
    if (!raw) return;

    var webEvt = AgentEventDispatcher.toWebEvent(raw);
    if (!webEvt) return;

    var sid = webEvt.sessionId;
    var event = webEvt.event;
    var p = webEvt.payload || {};

    // WebSocket 流结束信号
    if (event === 'system.done') {
        if (!sid) return;
        var sess = sessionMap[sid] || getOrCreateSession(sid);
        if (p.createdAt) sess._lastCreatedAt = p.createdAt;
        // 历史还在加载：先缓存，加载完再收尾
        if (sess._loadingHistory) {
            bufferPendingStreamChunk(sess, webEvt);
            return;
        }
        if (!sess.isStreaming) return;
        finishStream(sess);
        return;
    }

    // 文件变更通知（无 sessionId，系统级广播）
    if (event === 'system.filer_change') {
        if (typeof onFilerChange === 'function') {
            onFilerChange(p);
        }
        return;
    }

    if (!sid) return;

    // 即使 sess 不存在，也优先处理 todowrite（更新左侧 todo 进度）
    if (event === 'tool.end' && p.name === 'todowrite') {
        if (window._todoChunkHandlers) {
            var todoEvent = { toolName: p.name, text: p.result, args: p.args, sessionId: sid };
            window._todoChunkHandlers.forEach(function(h) { h(todoEvent); });
        }
    }

    // Loop/Goal 等后端新开流信号：重置流关闭状态，使后续文本能够正常开新气泡
    if (event === 'system.reset') {
        var sess = getOrCreateSession(sid);
        sess._streamClosed = false;
        sess._closedRunId = null;
        sess.acceptingStream = true;
        sess.stopRequested = false;
        // 后端 beginStreamTurn 已摘掉残留插话邮箱并紧随其后广播 dropped，
        // 此处撤掉防御定时器，避免与该 dropped 事件重复入队
        if (sess._steerFallbackTimer) { clearTimeout(sess._steerFallbackTimer); sess._steerFallbackTimer = null; }
        return;
    }

    // 运行中插话状态：已注入生效（落气泡）/ 任务结束未消费（转排队）
    if (event === 'system.steer_applied' || event === 'system.steer_dropped') {
        var steerSess = getOrCreateSession(sid);
        handleSteerEvent(steerSess, event, p);
        return;
    }

    // Loop/微信 等后端推送的用户提示词
    if (event === 'system.user_input') {
        var userSess = getOrCreateSession(sid);
        userSess._streamClosed = false;
        userSess._closedRunId = null;
        userSess.acceptingStream = true;
        userSess.stopRequested = false;
        if (typeof ensureChatInHistory === 'function') {
            ensureChatInHistory(sid, p.text, true);
        }
        appendUserMessage(userSess, p.text, null, null, p.createdAt, p.sourceLabel);
        if (userSess.sessionId === activeSessionId) {
            if (!inChatMode) switchToChatMode();
            scrollToBottom(true);
        }
        return;
    }

    var sess2 = getOrCreateSession(sid);

    // 历史加载中：先缓存，避免 loadMessages 重建 DOM 时丢内容
    if (sess2._loadingHistory) {
        bufferPendingStreamChunk(sess2, webEvt);
        return;
    }

    if (!sess2.isStreaming) {
        if (sess2.stopRequested) return;
        // 本页已正常 finishStream 的迟到包丢弃；刷新后 _streamClosed 未设置，有流就显示
        if (!sess2.acceptingStream) {
            if (sess2._streamClosed && !canReopenClosedStream(sess2, webEvt)) return;
            if (!openStreamFromIncoming(sess2)) return;
        } else if (!openStreamFromIncoming(sess2)) {
            return;
        }
    }
                onWebEvent(sess2, webEvt);
}

function connectWebGate() {
    if (webGateSocket && webGateSocket.readyState === WebSocket.OPEN) return;
    try {
        var protocol = (window.location.protocol === 'https:') ? 'wss:' : 'ws:';
        var wsUrl = protocol + '//' + window.location.host + '/web/gate?_t=1' + window.wsAndSuffix();
        // 用户认证启用时，将 user_token 传递给 WebSocket 握手验证
        var utk = window.getCookie ? window.getCookie('user_token') : null;
        if (utk) { wsUrl += '&user_token=' + encodeURIComponent(utk); }
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
        hideNetworkBar();
        // 重连后刷新文件树
        if (typeof loadTree === 'function') {
            loadTree();
        }
    };

    webGateSocket.onmessage = function(event) {
        var raw = event.data;
        if (raw === 'pong') return; // 心跳回复
        try {
            handleWebGateChunk(JSON.parse(raw));
        } catch(e) {
            // 非 JSON 消息忽略
        }
    };

    webGateSocket.onclose = function() {
        console.log('[WebGate] closed');
        stopWebGateHeartbeat();
        showNetworkBar('disconnected', I18n.t('streaming.wsDisconnected'));
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
    showNetworkBar('reconnecting', I18n.t('streaming.wsReconnecting', {attempt: webGateReconnectAttempts, max: WEBGATE_MAX_RECONNECT}));
    setTimeout(function() {
        connectWebGate();
    }, delay);
}

// 页面可见性控制心跳
$(document).on('visibilitychange', function() {
    if (document.hidden) {
        stopWebGateHeartbeat();
    } else {
        startWebGateHeartbeat();
    }
});

// 页面加载后自动建立 WebSocket 连接
connectWebGate();

/* ===== WeChat ClawBot Channel ===== */
var wechatHeaderBtn = $('#wechatHeaderBtn');
var wechatHeaderLabel = $('#wechatHeaderLabel');
var wechatModalOverlay = null;
var wechatPollTimer = null;

function updateWechatUI() {
    if (!activeSessionId) return;
    $.get('/web/chat/wechat/status?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
        try {
            var bound = resp.data && resp.data.bound;
            wechatHeaderBtn.toggleClass('bound', !!bound);
            wechatHeaderLabel.text(bound ? I18n.t('im.connected') : '');
            wechatHeaderBtn.attr('title', bound ? I18n.t('im.wechatBoundUnbind') : I18n.t('im.wechatBind'));
        } catch(e) {}
    }, 'json');
}

// 首屏：IM 状态属于次要请求，延后到空闲时再拉，避免与 sessions/meta/ws 抢带宽
function scheduleIdle(fn, timeoutMs) {
    if (window.requestIdleCallback) {
        requestIdleCallback(function() { fn(); }, { timeout: timeoutMs || 2000 });
    } else {
        setTimeout(fn, timeoutMs || 800);
    }
}
scheduleIdle(function() {
    updateWechatUI();
    updateFeishuUI();
    updateDingTalkUI();
}, 1500);

var origSetActiveSession = setActiveSession;
var _sessionSwitchTimer = null;
setActiveSession = function(sid) {
    origSetActiveSession(sid);
    if (_sessionSwitchTimer) {
        clearTimeout(_sessionSwitchTimer);
    }
    // 将非关键请求延迟执行，让 UI 先完成切换
    _sessionSwitchTimer = setTimeout(function() {
        _sessionSwitchTimer = null;
        updateWechatUI();
        updateFeishuUI();
        updateDingTalkUI();
        // 切换会话时刷新任务面板
        if (window.loadTodos) window.loadTodos();
        // 切换会话时恢复任务排队（queue-tasks.json，冷恢复不自动发送）
        var qSess = sessionMap[sid];
        if (qSess && typeof window.loadMessageQueue === 'function') {
            window.loadMessageQueue(qSess);
        }
        // 切换会话时重置上下文指示器
        if (typeof resetContextIndicator === 'function') resetContextIndicator();
    }, 50);
};

wechatHeaderBtn.on('click', function() {
    if (!activeSessionId) return;
    // If already bound, unbind
    if (wechatHeaderBtn.hasClass('bound')) {
        layer.confirm(I18n.t('im.wechatUnbindConfirm'), { title: I18n.t('im.confirmUnbind'), btn: [I18n.t('im.unbind'), I18n.t('common.cancel')], icon: 3, offset: '120px' }, function(index) {
            layer.close(index);
            $.post('/web/chat/wechat/unbind?sessionId=' + encodeURIComponent(activeSessionId)).always(function() {
                updateWechatUI();
            });
        });
        return;
    }
    // Not bound: show QR modal
    showWechatModal();
});

function showWechatModal() {
    if (wechatModalOverlay) return;

    wechatModalOverlay = $('<div>').addClass('wechat-modal-overlay').html(
        '<div class="wechat-modal">'
        + '<div class="wechat-modal-title">' + I18n.t('im.wechatScanBind') + '</div>'
        + '<div class="wechat-modal-subtitle">' + I18n.t('im.wechatScanSubtitle') + '</div>'
        + '<div class="wechat-qr-wrap" id="wechatQrWrap"><span style="color:#999;font-size:13px">' + I18n.t('common.loading') + '...</span></div>'
        + '<div class="wechat-status" id="wechatQrStatus">' + I18n.t('im.waitingScan') + '...</div>'
        + '<div class="im-bind-hint">' + I18n.t('im.wechatBindHint') + '</div>'
        + '<button class="wechat-modal-close" id="wechatModalClose">' + I18n.t('common.cancel') + '</button>'
        + '</div>'
    );
    $('body').append(wechatModalOverlay);

    $('#wechatModalClose').on('click', closeWechatModal);
    wechatModalOverlay.on('click', function(e) {
        if ($(e.target).is(wechatModalOverlay)) closeWechatModal();
    });

    // Fetch QR code
    $.get('/web/chat/wechat/qrcode?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
        try {
            if (resp.code !== 200 || !resp.data) {
                $('#wechatQrStatus').text(resp.message || I18n.t('im.qrcodeFailed')).addClass('error');
                return;
            }
            var $qrWrap = $('#wechatQrWrap');
            $qrWrap.html('');
            var qrContent = resp.data.qrcode_img_content || resp.data.qrcode;
            if (qrContent) {
                var renderQr = function(err) {
                    if (err || typeof QRCode === 'undefined') {
                        $qrWrap.html('<span style="font-size:12px;color:#666;padding:10px">' + escapeHtml(qrContent) + '</span>');
                        return;
                    }
                    try {
                        new QRCode($qrWrap[0], { text: qrContent, width: 180, height: 180 });
                    } catch(e) {
                        $qrWrap.html('<span style="font-size:12px;color:#666;padding:10px">' + escapeHtml(qrContent) + '</span>');
                    }
                };
                if (typeof ensureQrcode === 'function') ensureQrcode(renderQr);
                else renderQr(null);
            }
            // Start polling
            startWechatPoll(resp.data.qrcode, activeSessionId);
        } catch(e) {
            $('#wechatQrStatus').text(I18n.t('im.parseFailed')).addClass('error');
        }
    }, 'json');
}

function startWechatPoll(qrcode, sessionId) {
    if (wechatPollTimer) clearInterval(wechatPollTimer);
    wechatPollTimer = setInterval(function() {
        $.get('/web/chat/wechat/qrcode/status?qrcode=' + encodeURIComponent(qrcode) + '&sessionId=' + encodeURIComponent(sessionId), function(resp) {
            try {
                var data = resp.data || {};
                var $statusEl = $('#wechatQrStatus');
                if (!$statusEl.length) return;

                var status = data.status;
                if (status === 'wait') {
                    $statusEl.text(I18n.t('im.waitingScan') + '...').removeClass('error scanned');
                } else if (status === 'scaned') {
                    $statusEl.text(I18n.t('im.scannedConfirm')).removeClass('error').addClass('scanned');
                } else if (status === 'confirmed') {
                    $statusEl.text(I18n.t('im.connectSuccess')).removeClass('error').addClass('scanned');
                    clearInterval(wechatPollTimer);
                    wechatPollTimer = null;
                    setTimeout(function() {
                        closeWechatModal();
                        updateWechatUI();
                        switchToChatMode();
                        var initSess = getOrCreateSession(SESSION_ID);
                        if (!initSess._wechatInited) {
                            initSess._wechatInited = true;
                            appendSystemNotice(initSess, I18n.t('im.wechatConnectedNotice'));
                        }
                    }, 1200);
                } else if (status === 'expired') {
                    $statusEl.text(I18n.t('im.qrcodeExpired')).removeClass('scanned').addClass('error');
                    clearInterval(wechatPollTimer);
                    wechatPollTimer = null;
                } else {
                    // 临时错误或未知状态：继续轮询，扫码过程中的API短暂波动不应打断流程
                    if (wechatPollTimer) {
                        $statusEl.text(I18n.t('im.scanProcessing') + '...').removeClass('error scanned');
                    }
                }
            } catch(e) {}
        }, 'json');
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
var feishuHeaderBtn = $('#feishuHeaderBtn');
var feishuHeaderLabel = $('#feishuHeaderLabel');
var feishuModalOverlay = null;
var feishuPollTimer = null;

function updateFeishuUI() {
    if (!activeSessionId) return;
    $.get('/web/chat/feishu/status?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
        try {
            var data = resp.data || {};
            var bound = !!data.bound;
            feishuHeaderBtn.toggleClass('bound', bound);
            feishuHeaderLabel.text(bound ? I18n.t('im.connected') : '');
            feishuHeaderBtn.attr('title', bound ? I18n.t('im.feishuBoundUnbind') : I18n.t('im.feishuBind'));
        } catch(e) {}
    }, 'json');
}

// Page load: refresh status
updateFeishuUI();

feishuHeaderBtn.on('click', function() {
    if (!activeSessionId) return;
    // If already bound, unbind
    if (feishuHeaderBtn.hasClass('bound')) {
        layer.confirm(I18n.t('im.feishuUnbindConfirm'), { title: I18n.t('im.confirmUnbind'), btn: [I18n.t('im.unbind'), I18n.t('common.cancel')], icon: 3, offset: '120px' }, function(index) {
            layer.close(index);
            $.post('/web/chat/feishu/unbind?sessionId=' + encodeURIComponent(activeSessionId)).always(function() {
                updateFeishuUI();
            });
        });
        return;
    }
    // Not bound: show bind modal
    showFeishuModal();
});

function showFeishuModal() {
    if (feishuModalOverlay) return;

    feishuModalOverlay = $('<div>').addClass('im-bind-modal-overlay').html(
        '<div class="im-bind-modal" style="min-width:360px">'
        + '<div class="im-bind-modal-title" style="color:#3370ff">' + I18n.t('im.feishuBind') + '</div>'
        + '<div class="im-bind-tabs">'
        + '  <button class="im-bind-tab active" data-tab="qrcode">' + I18n.t('im.scanBind') + '</button>'
        + '  <button class="im-bind-tab" data-tab="credential">' + I18n.t('im.manualInput') + '</button>'
        + '</div>'
        /* === 手动输入 Tab === */
        + '<div class="im-bind-tab-content" id="feishuTabCredential" style="display:none">'
        + '<div class="im-bind-modal-subtitle">' + I18n.t('im.feishuCredentialSubtitle') + '</div>'
        + '<div class="im-bind-input-group">'
        + '  <label class="im-bind-input-label">App ID</label>'
        + '  <input class="im-bind-input" id="feishuAppIdInput" placeholder="' + I18n.t('im.feishuAppIdPlaceholder') + '" />'
        + '</div>'
        + '<div class="im-bind-input-group">'
        + '  <label class="im-bind-input-label">App Secret</label>'
        + '  <input class="im-bind-input" id="feishuAppSecretInput" type="password" placeholder="' + I18n.t('im.feishuAppSecretPlaceholder') + '" />'
        + '</div>'
        + '<div class="im-bind-status" id="feishuBindStatus">&nbsp;</div>'
        + '<button class="im-bind-confirm-btn feishu" id="feishuBindConfirmBtn">' + I18n.t('im.connect') + '</button>'
        + '<div class="im-bind-hint">' + I18n.t('im.feishuBindHint') + '</div>'
        + '</div>'
        /* === 扫码绑定 Tab === */
        + '<div class="im-bind-tab-content" id="feishuTabQrcode">'
        + '<div class="im-bind-modal-subtitle">' + I18n.t('im.feishuScanSubtitle') + '</div>'
        + '<div class="feishu-qr-wrap" id="feishuQrWrap"><span class="feishu-qr-loading">' + I18n.t('im.fetchingQrcode') + '...</span></div>'
        + '<div class="im-bind-status" id="feishuQrStatus">&nbsp;</div>'
        + '<button class="im-bind-confirm-btn feishu" id="feishuQrRefreshBtn" style="display:none">' + I18n.t('im.refreshQrcode') + '</button>'
        + '<div class="im-bind-hint">' + I18n.t('im.feishuChatHint') + '</div>'
        + '</div>'
        + '<button class="im-bind-modal-close" id="feishuModalClose">' + I18n.t('common.cancel') + '</button>'
        + '</div>'
    );
    $('body').append(feishuModalOverlay);

    // Tab切换
    feishuModalOverlay.find('.im-bind-tab').on('click', function() {
        var tab = $(this).data('tab');
        feishuModalOverlay.find('.im-bind-tab').removeClass('active');
        $(this).addClass('active');
        feishuModalOverlay.find('.im-bind-tab-content').hide();
        $('#feishuTab' + tab.charAt(0).toUpperCase() + tab.slice(1)).show();
        if (tab === 'qrcode') {
            startFeishuQrBinding();
        }
    });

    $('#feishuModalClose').on('click', closeFeishuModal);
    feishuModalOverlay.on('click', function(e) {
        if ($(e.target).is(feishuModalOverlay)) closeFeishuModal();
    });

    // 默认扫码 tab，自动获取二维码
    startFeishuQrBinding();

    /* ---- 手动输入 Tab 逻辑 ---- */
    var $appIdInput = $('#feishuAppIdInput');
    var $appSecretInput = $('#feishuAppSecretInput');
    var $statusEl = $('#feishuBindStatus');
    var $confirmBtn = $('#feishuBindConfirmBtn');

    $appIdInput.focus();

    $confirmBtn.on('click', function() {
        var appId = $appIdInput.val().trim();
        var appSecret = $appSecretInput.val().trim();
        if (!appId) {
            $statusEl.text(I18n.t('im.inputAppId')).addClass('error');
            return;
        }
        if (!appSecret) {
            $statusEl.text(I18n.t('im.inputAppSecret')).addClass('error');
            return;
        }
        $statusEl.text(I18n.t('im.startingWsConnection') + '...').removeClass('error scanned');
        $confirmBtn.prop('disabled', true);
        $appIdInput.prop('disabled', true);
        $appSecretInput.prop('disabled', true);

        var params = 'sessionId=' + encodeURIComponent(activeSessionId)
            + '&appId=' + encodeURIComponent(appId)
            + '&appSecret=' + encodeURIComponent(appSecret);

        $.ajax({
            url: '/web/chat/feishu/bind?' + params,
            method: 'POST',
            dataType: 'json'
        }).done(function(resp) {
            if (resp.code === 200) {
                // WebSocket 启动成功，进入等待飞书消息状态
                $statusEl.text(I18n.t('im.feishuConnectSuccessHint') + '...').removeClass('error');
                $confirmBtn.hide();
                // 开始轮询绑定状态
                startFeishuPoll();
            } else {
                $statusEl.text(resp.message || I18n.t('im.connectFailed')).addClass('error');
                $confirmBtn.prop('disabled', false);
                $appIdInput.prop('disabled', false);
                $appSecretInput.prop('disabled', false);
            }
        }).fail(function(jqXhr) {
            if (jqXhr.status) {
                $statusEl.text(I18n.t('im.requestFailed', {status: jqXhr.status})).addClass('error');
            } else {
                $statusEl.text(I18n.t('im.connectFailed')).addClass('error');
            }
            $confirmBtn.prop('disabled', false);
            $appIdInput.prop('disabled', false);
            $appSecretInput.prop('disabled', false);
        });
    });

    // Enter key to confirm
    $appIdInput.add($appSecretInput).on('keydown', function(e) {
        if (e.key === 'Enter' && !isInputComposing(e)) {
            e.preventDefault();
            $confirmBtn.click();
        }
    });

    /* ---- 手动输入绑定轮询逻辑 ---- */
    function startFeishuPoll() {
        if (feishuPollTimer) clearInterval(feishuPollTimer);
        var dotCount = 0;
        feishuPollTimer = setInterval(function() {
            dotCount = (dotCount + 1) % 4;
            var dots = '.'.repeat(dotCount);
            $statusEl.text(I18n.t('im.waitingFeishuMsg') + dots);

            $.get('/web/chat/feishu/status?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
                try {
                    var data = resp.data || {};
                    if (data.bound) {
                        clearInterval(feishuPollTimer);
                        feishuPollTimer = null;
                        $statusEl.text(I18n.t('im.bindSuccess')).removeClass('error').addClass('scanned');
                        setTimeout(function() {
                            closeFeishuModal();
                            updateFeishuUI();
                            switchToChatMode();
                        }, 1000);
                    }
                } catch(e) {}
            }, 'json');
        }, 2000);
    }

    /* ---- 扫码绑定 Tab 逻辑 ---- */
    function startFeishuQrBinding() {
        var $qrWrap = $('#feishuQrWrap');
        var $qrStatus = $('#feishuQrStatus');
        var $refreshBtn = $('#feishuQrRefreshBtn');

        $qrStatus.text('').removeClass('error scanned');
        $refreshBtn.hide();

        $.ajax({
            url: '/web/chat/feishu/qrcode?sessionId=' + encodeURIComponent(activeSessionId),
            method: 'POST',
            dataType: 'json'
        }).done(function(resp) {
            if (resp.code !== 200 || !resp.data) {
                var errMsg = resp.message || I18n.t('im.qrcodeFailed');
                $qrWrap.html('<span style="font-size:13px;color:#666">' + escapeHtml(errMsg) + '</span>');
                $qrStatus.text(errMsg).addClass('error');
                $refreshBtn.show();
                return;
            }
            var qrUrl = resp.data.qrUrl;
            $qrWrap.html('');
            if (qrUrl) {
                var renderFeishuQr = function(err) {
                    if (err || typeof QRCode === 'undefined') {
                        $qrWrap.html('<span style="font-size:12px;color:#666;padding:10px;word-break:break-all">' + escapeHtml(qrUrl) + '</span>');
                        $qrStatus.text(I18n.t('im.qrcodeLibFailed')).addClass('error');
                        return;
                    }
                    try {
                        new QRCode($qrWrap[0], { text: qrUrl, width: 180, height: 180 });
                        $qrStatus.text(I18n.t('im.feishuScanQr')).removeClass('error scanned');
                    } catch(e) {
                        $qrWrap.html('<span style="font-size:12px;color:#666;padding:10px;word-break:break-all">' + escapeHtml(qrUrl) + '</span>');
                    }
                };
                if (typeof ensureQrcode === 'function') ensureQrcode(renderFeishuQr);
                else renderFeishuQr(null);
            }
            // 开始轮询扫码状态
            startFeishuQrPoll();
        }).fail(function(jqXhr) {
            $qrWrap.html('<span style="font-size:13px;color:#666">' + I18n.t('im.networkFailed') + '</span>');
            $qrStatus.text(I18n.t('im.networkFailed')).addClass('error');
            $refreshBtn.show();
        });
    }

    function startFeishuQrPoll() {
        if (feishuPollTimer) clearInterval(feishuPollTimer);
        var dotCount = 0;
        feishuPollTimer = setInterval(function() {
            $.get('/web/chat/feishu/qrcode/status?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
                try {
                    var data = resp.data || {};
                    var $qrStatus = $('#feishuQrStatus');
                    if (!$qrStatus.length) return;

                    var status = data.status;
                    if (status === 'waiting') {
                        dotCount = (dotCount + 1) % 4;
                        var dots = '.'.repeat(dotCount);
                        $qrStatus.text(I18n.t('im.waitingScan') + dots).removeClass('error scanned');
                    } else if (status === 'success') {
                        $qrStatus.text(I18n.t('im.bindSuccess')).removeClass('error').addClass('scanned');
                        clearInterval(feishuPollTimer);
                        feishuPollTimer = null;
                        setTimeout(function() {
                            closeFeishuModal();
                            updateFeishuUI();
                            switchToChatMode();
                        }, 1200);
                    } else if (status === 'failed') {
                        $qrStatus.text(data.message || I18n.t('im.bindFailed')).addClass('error');
                        clearInterval(feishuPollTimer);
                        feishuPollTimer = null;
                        $('#feishuQrRefreshBtn').show();
                    } else if (status === 'error') {
                        $qrStatus.text(data.message || I18n.t('im.queryStatusFailed')).addClass('error');
                        clearInterval(feishuPollTimer);
                        feishuPollTimer = null;
                        $('#feishuQrRefreshBtn').show();
                    }
                } catch(e) {}
            }, 'json');
        }, 2000);
    }

    // 刷新二维码
    $('#feishuQrRefreshBtn').on('click', function() {
        if (feishuPollTimer) {
            clearInterval(feishuPollTimer);
            feishuPollTimer = null;
        }
        startFeishuQrBinding();
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
var dingtalkHeaderBtn = $('#dingtalkHeaderBtn');
var dingtalkHeaderLabel = $('#dingtalkHeaderLabel');
var dingtalkModalOverlay = null;
var dingtalkPollTimer = null;
var dingtalkStatusTimer = null;
var dingtalkBindCheckTimer = null;

function updateDingTalkUI() {
    if (!activeSessionId) return;
    $.get('/web/chat/dingtalk/status?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
        try {
            var data = resp.data || {};
            var bound = !!data.bound;
            var pending = !!data.pending;
            if (bound && !pending) {
                // 完全绑定（用户已在钉上发过消息）
                dingtalkHeaderBtn.toggleClass('bound', true).removeClass('pending');
                dingtalkHeaderLabel.text(I18n.t('im.connected'));
                dingtalkHeaderBtn.attr('title', I18n.t('im.dingtalkBoundUnbind'));
            } else if (bound && pending) {
                // 半绑定（扫码成功，等待用户发第一条消息）
                dingtalkHeaderBtn.toggleClass('pending', true).removeClass('bound');
                dingtalkHeaderLabel.text(I18n.t('im.connecting') + '...');
                dingtalkHeaderBtn.attr('title', I18n.t('im.dingtalkWaitingMsg'));
            } else {
                dingtalkHeaderBtn.removeClass('bound pending');
                dingtalkHeaderLabel.text('');
                dingtalkHeaderBtn.attr('title', I18n.t('im.dingtalkBind'));
            }
        } catch(e) {}
    }, 'json');
}

/**
 * 后台轮询钉钉绑定状态。
 * 扫码绑定成功后，等待用户给钉钉机器人发消息完成真正绑定，
 * 一旦检测到 bound=true 自动更新按钮为"已连接"（无需刷新页面）。
 */
function startDingtalkStatusPoll() {
    if (dingtalkStatusTimer) return;
    dingtalkStatusTimer = setInterval(function() {
        if (!activeSessionId) {
            clearInterval(dingtalkStatusTimer);
            dingtalkStatusTimer = null;
            return;
        }
        $.get('/web/chat/dingtalk/status?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
            try {
                var data = resp.data || {};
                // 只在完全绑定（pending=false）时才停止轮询
                if (data.bound && !data.pending) {
                    clearInterval(dingtalkStatusTimer);
                    dingtalkStatusTimer = null;
                    dingtalkHeaderBtn.toggleClass('bound', true).removeClass('pending');
                    dingtalkHeaderLabel.text(I18n.t('im.connected'));
                    dingtalkHeaderBtn.attr('title', I18n.t('im.dingtalkBoundUnbind'));
                }
            } catch(e) {}
        }, 'json');
    }, 3000);
}

// Page load: refresh status
updateDingTalkUI();

dingtalkHeaderBtn.on('click', function() {
    if (!activeSessionId) return;
    // If already bound, unbind
    if (dingtalkHeaderBtn.hasClass('bound')) {
        layer.confirm(I18n.t('im.dingtalkUnbindConfirm'), { title: I18n.t('im.confirmUnbind'), btn: [I18n.t('im.unbind'), I18n.t('common.cancel')], icon: 3, offset: '120px' }, function(index) {
            layer.close(index);
            $.post('/web/chat/dingtalk/unbind?sessionId=' + encodeURIComponent(activeSessionId)).always(function() {
                updateDingTalkUI();
            });
        });
        return;
    }
    // Not bound: show bind modal
    showDingTalkModal();
});

function showDingTalkModal() {
    if (dingtalkModalOverlay) return;

    dingtalkModalOverlay = $('<div>').addClass('im-bind-modal-overlay').html(
        '<div class="im-bind-modal" style="min-width:360px">'
        + '<div class="im-bind-modal-title" style="color:#0089FF">' + I18n.t('im.dingtalkBind') + '</div>'
        + '<div class="im-bind-tabs">'
        + '  <button class="im-bind-tab active" data-tab="qrcode">' + I18n.t('im.scanBind') + '</button>'
        + '  <button class="im-bind-tab" data-tab="credential">' + I18n.t('im.manualInput') + '</button>'
        + '</div>'
        /* === 手动输入 Tab === */
        + '<div class="im-bind-tab-content" id="dingtalkTabCredential" style="display:none">'
        + '<div class="im-bind-modal-subtitle">' + I18n.t('im.dingtalkCredentialSubtitle') + '</div>'
        + '<div class="im-bind-input-group">'
        + '  <label class="im-bind-input-label">' + I18n.t('im.appKeyLabel') + '</label>'
        + '  <input class="im-bind-input" id="dingtalkAppKeyInput" placeholder="' + I18n.t('im.dingtalkAppKeyPlaceholder') + '" />'
        + '</div>'
        + '<div class="im-bind-input-group">'
        + '  <label class="im-bind-input-label">' + I18n.t('im.appSecretLabel') + '</label>'
        + '  <input class="im-bind-input" id="dingtalkAppSecretInput" type="password" placeholder="' + I18n.t('im.dingtalkAppSecretPlaceholder') + '" />'
        + '</div>'
        + '<div class="im-bind-status" id="dingtalkBindStatus">&nbsp;</div>'
        + '<button class="im-bind-confirm-btn dingtalk" id="dingtalkBindConfirmBtn">' + I18n.t('im.connect') + '</button>'
        + '<div class="im-bind-hint">' + I18n.t('im.dingtalkBindHint') + '</div>'
        + '</div>'
        /* === 扫码绑定 Tab === */
        + '<div class="im-bind-tab-content" id="dingtalkTabQrcode">'
        + '<div class="im-bind-modal-subtitle">' + I18n.t('im.dingtalkScanSubtitle') + '</div>'
        + '<div class="feishu-qr-wrap" id="dingtalkQrWrap"><span class="feishu-qr-loading">' + I18n.t('im.fetchingQrcode') + '...</span></div>'
        + '<div class="im-bind-status" id="dingtalkQrStatus">&nbsp;</div>'
        + '<button class="im-bind-confirm-btn dingtalk" id="dingtalkQrRefreshBtn" style="display:none">' + I18n.t('im.refreshQrcode') + '</button>'
        + '<div class="im-bind-hint">' + I18n.t('im.dingtalkChatHint') + '</div>'
        + '</div>'
        + '<button class="im-bind-modal-close" id="dingtalkModalClose">' + I18n.t('common.cancel') + '</button>'
        + '</div>'
    );
    $('body').append(dingtalkModalOverlay);

    // Tab切换
    dingtalkModalOverlay.find('.im-bind-tab').on('click', function() {
        var tab = $(this).data('tab');
        dingtalkModalOverlay.find('.im-bind-tab').removeClass('active');
        $(this).addClass('active');
        dingtalkModalOverlay.find('.im-bind-tab-content').hide();
        $('#dingtalkTab' + tab.charAt(0).toUpperCase() + tab.slice(1)).show();
        if (tab === 'qrcode') {
            startDingtalkQrBinding();
        }
    });

    $('#dingtalkModalClose').on('click', closeDingTalkModal);
    dingtalkModalOverlay.on('click', function(e) {
        if ($(e.target).is(dingtalkModalOverlay)) closeDingTalkModal();
    });

    // 默认扫码 tab，自动获取二维码
    startDingtalkQrBinding();

    /* ---- 手动输入 Tab 逻辑 ---- */
    var $appKeyInput = $('#dingtalkAppKeyInput');
    var $appSecretInput = $('#dingtalkAppSecretInput');
    var $statusEl = $('#dingtalkBindStatus');
    var $confirmBtn = $('#dingtalkBindConfirmBtn');

    $appKeyInput.focus();

    $confirmBtn.on('click', function() {
        var appKey = $appKeyInput.val().trim();
        var appSecret = $appSecretInput.val().trim();
        if (!appKey) {
            $statusEl.text(I18n.t('im.inputAppKey')).addClass('error');
            return;
        }
        if (!appSecret) {
            $statusEl.text(I18n.t('im.inputAppSecret')).addClass('error');
            return;
        }
        $statusEl.text(I18n.t('im.startingStreamConnection') + '...').removeClass('error scanned');
        $confirmBtn.prop('disabled', true);
        $appKeyInput.prop('disabled', true);
        $appSecretInput.prop('disabled', true);

        var params = 'sessionId=' + encodeURIComponent(activeSessionId)
            + '&appKey=' + encodeURIComponent(appKey)
            + '&appSecret=' + encodeURIComponent(appSecret);

        $.ajax({
            url: '/web/chat/dingtalk/bind?' + params,
            method: 'POST',
            dataType: 'json'
        }).done(function(resp) {
            if (resp.code === 200) {
                // Stream 启动成功，进入等待钉钉消息状态
                $statusEl.text(I18n.t('im.dingtalkConnectSuccessHint') + '...').removeClass('error');
                $confirmBtn.hide();
                // 开始轮询绑定状态
                startDingTalkPoll();
            } else {
                $statusEl.text(resp.message || I18n.t('im.connectFailed')).addClass('error');
                $confirmBtn.prop('disabled', false);
                $appKeyInput.prop('disabled', false);
                $appSecretInput.prop('disabled', false);
            }
        }).fail(function(jqXhr) {
            if (jqXhr.status) {
                $statusEl.text(I18n.t('im.requestFailed', {status: jqXhr.status})).addClass('error');
            } else {
                $statusEl.text(I18n.t('im.connectFailed')).addClass('error');
            }
            $confirmBtn.prop('disabled', false);
            $appKeyInput.prop('disabled', false);
            $appSecretInput.prop('disabled', false);
        });
    });

    function startDingTalkPoll() {
        if (dingtalkPollTimer) clearInterval(dingtalkPollTimer);
        var dotCount = 0;
        dingtalkPollTimer = setInterval(function() {
            dotCount = (dotCount + 1) % 4;
            var dots = '.'.repeat(dotCount);
            $statusEl.text(I18n.t('im.waitingDingtalkMsg') + dots);

            $.get('/web/chat/dingtalk/status?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
                try {
                    var data = resp.data || {};
                    if (data.bound) {
                        // 绑定成功！
                        clearInterval(dingtalkPollTimer);
                        dingtalkPollTimer = null;
                        $statusEl.text(I18n.t('im.bindSuccess')).removeClass('error').addClass('scanned');
                        setTimeout(function() {
                            closeDingTalkModal();
                            updateDingTalkUI();
                            switchToChatMode();
                        }, 1000);
                    }
                } catch(e) {}
            }, 'json');
        }, 2000);
    }

    // Enter key to confirm
    $appKeyInput.add($appSecretInput).on('keydown', function(e) {
        if (e.key === 'Enter' && !isInputComposing(e)) {
            e.preventDefault();
            $confirmBtn.click();
        }
    });

    /* ---- 扫码绑定 Tab 逻辑 ---- */
    function startDingtalkQrBinding() {
        var $qrWrap = $('#dingtalkQrWrap');
        var $qrStatus = $('#dingtalkQrStatus');
        var $refreshBtn = $('#dingtalkQrRefreshBtn');

        // 防止已在轮询中再次触发
        if ($qrWrap.find('canvas').length > 0) return;

        $qrStatus.text('').removeClass('error scanned');
        $refreshBtn.hide();

        $.ajax({
            url: '/web/chat/dingtalk/qrcode?sessionId=' + encodeURIComponent(activeSessionId),
            method: 'POST',
            dataType: 'json'
        }).done(function(resp) {
            if (resp.code !== 200 || !resp.data) {
                var errMsg = resp.message || I18n.t('im.qrcodeFailed');
                $qrWrap.html('<span style="font-size:13px;color:#666">' + escapeHtml(errMsg) + '</span>');
                $qrStatus.text(errMsg).addClass('error');
                $refreshBtn.show();
                return;
            }
            var qrUrl = resp.data.qrUrl;
            $qrWrap.html('');
            if (qrUrl) {
                var renderDingtalkQr = function(err) {
                    if (err || typeof QRCode === 'undefined') {
                        $qrWrap.html('<span style="font-size:12px;color:#666;padding:10px;word-break:break-all">' + escapeHtml(qrUrl) + '</span>');
                        $qrStatus.text(I18n.t('im.qrcodeLibFailed')).addClass('error');
                        return;
                    }
                    try {
                        new QRCode($qrWrap[0], { text: qrUrl, width: 180, height: 180 });
                        $qrStatus.text(I18n.t('im.dingtalkScanQr')).removeClass('error scanned');
                    } catch(e) {
                        $qrWrap.html('<span style="font-size:12px;color:#666;padding:10px;word-break:break-all">' + escapeHtml(qrUrl) + '</span>');
                    }
                };
                if (typeof ensureQrcode === 'function') ensureQrcode(renderDingtalkQr);
                else renderDingtalkQr(null);
            }
            // 开始轮询扫码状态
            startDingtalkQrPoll();
        }).fail(function(jqXhr) {
            $qrWrap.html('<span style="font-size:13px;color:#666">' + I18n.t('im.networkFailed') + '</span>');
            $qrStatus.text(I18n.t('im.networkFailed')).addClass('error');
            $refreshBtn.show();
        });
    }

    function startDingtalkQrPoll() {
        if (dingtalkPollTimer) clearInterval(dingtalkPollTimer);
        var dotCount = 0;
        dingtalkPollTimer = setInterval(function() {
            $.get('/web/chat/dingtalk/qrcode/status?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
                try {
                    var data = resp.data || {};
                    var $qrStatus = $('#dingtalkQrStatus');
                    if (!$qrStatus.length) return;

                    var status = data.status;
                    if (status === 'waiting') {
                        dotCount = (dotCount + 1) % 4;
                        var dots = '.'.repeat(dotCount);
                        $qrStatus.text(I18n.t('im.waitingScan') + dots).removeClass('error scanned');
                    } else if (status === 'success') {
                        clearInterval(dingtalkPollTimer);
                        dingtalkPollTimer = null;
                        $qrStatus.text(I18n.t('im.dingtalkScanSuccess')).removeClass('error').addClass('scanned');
                        $('#dingtalkQrRefreshBtn').hide();
                        // 遮罩层变透明、不阻断页面交互，弹窗保持可见等待真正绑定
                        dingtalkModalOverlay.css({ pointerEvents: 'none', background: 'transparent' });
                        dingtalkModalOverlay.find('.im-bind-modal').css('pointerEvents', 'auto');
                        // 开始轮询真正绑定状态
                        dingtalkBindCheckTimer = setInterval(function() {
                            $.get('/web/chat/dingtalk/status?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
                                try {
                                    var data = resp.data || {};
                                    // bound=true + pending=false 表示用户已在钉钉上发消息完成了绑定
                                    if (data.bound && !data.pending) {
                                        clearInterval(dingtalkBindCheckTimer);
                                        dingtalkBindCheckTimer = null;
                                        $qrStatus.text(I18n.t('im.bindSuccess')).removeClass('error').addClass('scanned');
                                        setTimeout(function() {
                                            closeDingTalkModal();
                                            updateDingTalkUI();
                                            switchToChatMode();
                                            startDingtalkStatusPoll();
                                        }, 800);
                                    }
                                } catch(e) {}
                            }, 'json');
                        }, 2000);
                    } else if (status === 'failed') {
                        $qrStatus.text(data.message || I18n.t('im.bindFailed')).addClass('error');
                        clearInterval(dingtalkPollTimer);
                        dingtalkPollTimer = null;
                        $('#dingtalkQrRefreshBtn').show();
                    } else if (status === 'error') {
                        $qrStatus.text(data.message || I18n.t('im.queryStatusFailed')).addClass('error');
                        clearInterval(dingtalkPollTimer);
                        dingtalkPollTimer = null;
                        $('#dingtalkQrRefreshBtn').show();
                    }
                } catch(e) {}
            }, 'json');
        }, 2000);
    }

    // 刷新二维码
    $('#dingtalkQrRefreshBtn').on('click', function() {
        if (dingtalkPollTimer) {
            clearInterval(dingtalkPollTimer);
            dingtalkPollTimer = null;
        }
        startDingtalkQrBinding();
    });
}

function closeDingTalkModal() {
    if (dingtalkPollTimer) {
        clearInterval(dingtalkPollTimer);
        dingtalkPollTimer = null;
    }
    if (dingtalkBindCheckTimer) {
        clearInterval(dingtalkBindCheckTimer);
        dingtalkBindCheckTimer = null;
    }
    if (dingtalkModalOverlay) {
        dingtalkModalOverlay.remove();
        dingtalkModalOverlay = null;
    }
}

/* ===== 初始化：注册回调 + 激活默认会话 ===== */
onFinishStream = finishStream;
setActiveSession(SESSION_ID);

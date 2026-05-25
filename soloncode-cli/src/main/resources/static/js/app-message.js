/* ===== app-message.js ===== */
/* 消息渲染：消息气泡 + 思考动画 + 命令输出 + HITL + 回退 */
/* 依赖：app-base.js */

/* ===== Message Rendering (Session-Aware) ===== */
function appendUserMessage(sess, text, imageDataUrls, fileAttachments, createdAt) {
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
    span.className = 'user-msg-text';
    span.textContent = text;
    bubble.appendChild(span);

    var copyBtn = row.querySelector('.user-copy-btn');
    copyBtn.addEventListener('click', function() {
        var txt = bubble.querySelector('.user-msg-text');
        txt = txt ? txt.textContent : '';
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

    addImageLightbox(bubble);
    sess.container.appendChild(row);
    if (sess.sessionId === activeSessionId) scrollToBottom(true);
}

function appendSystemNotice(sess, text) {
    var row = document.createElement('div');
    row.className = 'msg-row system-notice';
    row.innerHTML = '<div class="system-notice-bubble">' + escapeHtml(text) + '</div>';
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
            + '<div class="msg-time" style="display:none"></div>'
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

function setAssistantTime(sess, ts) {
    var row = sess.currentBubbleEl ? sess.currentBubbleEl.closest('.msg-row') : null;
    if (!row) return;
    var timeEl = row.querySelector('.msg-time');
    if (!timeEl) return;
    timeEl.textContent = formatMsgTime(ts || Date.now());
    timeEl.style.display = '';
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
            addCodeBlockButtons(el);
            if (typeof highlightCodeBlocks === 'function') highlightCodeBlocks(el);

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
        card.style.borderColor = 'var(--color-success)';
        handleHitlResponse(sess, 'approve');
    });

    rejectBtn.addEventListener('click', function() {
        approveBtn.disabled = true;
        rejectBtn.disabled = true;
        rejectBtn.textContent = '\u5df2\u62d2\u7edd';
        approveBtn.style.display = 'none';
        card.style.borderColor = 'var(--color-danger)';
        handleHitlResponse(sess, 'reject');
    });

    if (sess.sessionId === activeSessionId) scrollToBottom();
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
        // 通过回调占位调用 finishStream（由 app-streaming.js 注册）
        if (onFinishStream) onFinishStream(sess);
    });
}

/* ===== Rewind Handling ===== */
function handleRewind(sess, count) {
    if (count <= 0) return;
    // count = 要删除的消息条数，从末尾倒序删除
    var toRemove = count;
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

/* ===== Code Block Copy Buttons ===== */
function addCodeBlockButtons(container) {
    if (!container) return;
    var pres = container.querySelectorAll('pre');
    for (var i = 0; i < pres.length; i++) {
        if (pres[i].querySelector('.code-copy-btn')) continue;
        var btn = document.createElement('button');
        btn.className = 'code-copy-btn';
        btn.textContent = '复制';
        btn.addEventListener('click', function(e) {
            e.stopPropagation();
            var pre = this.closest('pre');
            var code = pre ? pre.querySelector('code') : null;
            var text = code ? code.textContent : (pre ? pre.textContent : '');
            var self = this;
            if (navigator.clipboard) {
                navigator.clipboard.writeText(text).then(function() {
                    self.textContent = '已复制';
                    self.classList.add('copied');
                    setTimeout(function() {
                        self.textContent = '复制';
                        self.classList.remove('copied');
                    }, 1500);
                });
            }
        });
        pres[i].appendChild(btn);
    }
}

/* ===== Image Lightbox ===== */
function addImageLightbox(container) {
    if (!container) return;
    var imgs = container.querySelectorAll('.msg-bubble img, .md-content img');
    for (var i = 0; i < imgs.length; i++) {
        if (imgs[i].dataset.lightbox) continue;
        imgs[i].dataset.lightbox = '1';
        imgs[i].style.cursor = 'zoom-in';
        imgs[i].addEventListener('click', function(e) {
            e.stopPropagation();
            openLightbox(this.src);
        });
    }
}

function openLightbox(src) {
    var overlay = document.createElement('div');
    overlay.className = 'lightbox-overlay';
    var img = document.createElement('img');
    img.src = src;
    overlay.appendChild(img);
    overlay.addEventListener('click', function() {
        overlay.remove();
    });
    document.addEventListener('keydown', function handler(e) {
        if (e.key === 'Escape') {
            overlay.remove();
            document.removeEventListener('keydown', handler);
        }
    });
    document.body.appendChild(overlay);
}



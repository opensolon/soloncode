/* ===== app-message.js ===== */
/* 消息渲染：消息气泡 + 思考动画 + 命令输出 + HITL + 回退 */
/* 依赖：app-base.js */

/* ===== Message Rendering (Session-Aware) ===== */
function appendUserMessage(sess, text, imageDataUrls, fileAttachments, createdAt) {
    var row = $('<div>').addClass('msg-row user')[0];
    row.setAttribute('data-user-msg-idx', sess.userMsgCounter++);
    row.innerHTML = '<button class="user-copy-btn" title="复制"><i class="layui-icon layui-icon-file"></i></button><div class="msg-bubble"></div>';
    var bubble = $(row).find('.msg-bubble')[0];

    // Multiple images
    if (imageDataUrls && imageDataUrls.length > 0) {
        var imgWrap = $('<div>').attr('style', 'display:flex;flex-wrap:wrap;gap:6px;margin-bottom:8px;')[0];
        for (var i = 0; i < imageDataUrls.length; i++) {
            var img = $('<img>').attr('src', imageDataUrls[i].dataUrl || imageDataUrls[i])
                .attr('style', 'max-height:120px;max-width:200px;border-radius:8px;object-fit:cover;')[0];
            $(imgWrap).append(img);
        }
        $(bubble).append(imgWrap);
    }

    // Multiple file attachments
    if (fileAttachments && fileAttachments.length > 0) {
        for (var j = 0; j < fileAttachments.length; j++) {
            var tag = $('<div>').attr('style', 'display:flex;align-items:center;gap:6px;padding:6px 10px;background:rgba(255,255,255,0.18);border:1px solid rgba(255,255,255,0.25);border-radius:6px;margin-bottom:6px;font-size:13px;color:#fff;')[0];
            tag.innerHTML = '<span>📎</span>'
                + '<span style="font-weight:500">' + escapeHtml(fileAttachments[j].name) + '</span>'
                + '<span style="opacity:0.7;font-size:11px">(' + formatFileSize(fileAttachments[j].size) + ')</span>';
            $(bubble).append(tag);
        }
    }

    var span = $('<span>').addClass('user-msg-text').text(text)[0];
    $(bubble).append(span);

    var copyBtn = $(row).find('.user-copy-btn')[0];
    $(copyBtn).on('click', function() {
        var txtEl = $(bubble).find('.user-msg-text')[0];
        var txt = txtEl ? $(txtEl).text() : '';
        if (navigator.clipboard) {
            navigator.clipboard.writeText(txt).then(function() {
                $(copyBtn).addClass('copied');
                copyBtn.innerHTML = '<i class="layui-icon layui-icon-ok" style="font-size:14px"></i>';
                setTimeout(function() {
                    $(copyBtn).removeClass('copied');
                    copyBtn.innerHTML = '<i class="layui-icon layui-icon-file"></i>';
                }, 1500);
            });
        }
    });

    // 时间戳
    if (createdAt) {
        var timeEl = $('<div>').addClass('msg-time').text(formatMsgTime(createdAt))[0];
        $(bubble).append(timeEl);
    }

    addImageLightbox(bubble);
    $(sess.container).append(row);
    if (sess.sessionId === activeSessionId) scrollToBottom(true);
}

function appendSystemNotice(sess, text) {
    var row = $('<div>').addClass('msg-row system-notice')[0];
    row.innerHTML = '<div class="system-notice-bubble">' + escapeHtml(text) + '</div>';
    $(sess.container).append(row);
    if (sess.sessionId === activeSessionId) scrollToBottom(true);
}

function ensureAssistantBubble(sess) {
    if (!sess.currentBubbleEl) {
        console.log('[ensureAssistantBubble] 新建 AI bubble, isStreaming=%s', sess.isStreaming, new Error().stack.split('\n').slice(1,4).join('\n'));
        removeThinking(sess);
        var row = $('<div>').addClass('msg-row assistant')[0];
        row.innerHTML = '<div class="msg-bubble"><div class="md-content"></div>'
            + '<div class="msg-time" style="display:none"></div>'
            + '<div class="msg-actions">'
            + '<button class="msg-action-btn copy-btn" title="复制"><i class="layui-icon layui-icon-file"></i> 复制</button>'
            + '</div></div>';
        $(sess.container).append(row);
        sess.currentBubbleEl = $(row).find('.md-content')[0];
        var copyBtn = $(row).find('.copy-btn')[0];
        var mdRef = sess.currentBubbleEl;
        $(copyBtn).on('click', function() {
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
        var block = $('<div>').addClass('thinking-block streaming expanded')[0];
        block.innerHTML = '<div class="thinking-block-header">'
            + '<span class="thinking-block-label">思考中...</span>'
            + '<span class="thinking-timer" style="margin-left:4px">0s</span>'
            + '<span class="thinking-block-dots"><span></span><span></span><span></span></span>'
            + '<i class="layui-icon layui-icon-right thinking-block-toggle"></i>'
            + '</div>'
            + '<div class="thinking-block-body"><div class="md-content"></div></div>';
        $(sess.currentBubbleEl).before(block);
        $(block).find('.thinking-block-header').on('click', function() {
            $(block).toggleClass('expanded');
        });
        sess.thinkingBlockEl = block;
        sess.thinkingBodyMdEl = $(block).find('.thinking-block-body .md-content')[0];
        sess.thinkingBodyWrapEl = $(block).find('.thinking-block-body')[0];
        sess.thinkingBuffer = '';
        var timerSpan = $(block).find('.thinking-timer')[0];
        startThinkingTimer(sess, 'thinkingBlockTimerId', 'thinkingBlockStartTime', timerSpan);
    }
    return sess.thinkingBlockEl;
}

function setAssistantTime(sess, ts) {
    var row = sess.currentBubbleEl ? $(sess.currentBubbleEl).closest('.msg-row')[0] : null;
    if (!row) return;
    var timeEl = $(row).find('.msg-time')[0];
    if (!timeEl) return;
    $(timeEl).text(formatMsgTime(ts || Date.now()));
    timeEl.style.display = '';
}

function insertBeforeActions(sess, el) {
    $(sess.currentBubbleEl.parentNode).find('.msg-actions').first().before(el);
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
        $(sess.thinkingBlockEl).removeClass('streaming expanded');
        var elapsed = '';
        if (sess.thinkingBlockStartTime) {
            elapsed = ' (' + Math.floor((Date.now() - sess.thinkingBlockStartTime) / 1000) + 's)';
        }
        var label = $(sess.thinkingBlockEl).find('.thinking-block-label')[0];
        if (label) $(label).text('思考结束' + elapsed);
        $(sess.thinkingBlockEl).find('.thinking-block-dots').remove();
        $(sess.thinkingBlockEl).find('.thinking-timer').remove();
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
        var icon = $(sess.pendingToolCard).find('.tool-status-icon')[0];
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

    var card = $('<div>').addClass('tool-card')[0];
    card.innerHTML = '<div class="tool-card-header">'
        + '<span class="tool-status-icon loading"></span>'
        + '<span class="tool-name">' + escapeHtml(toolName || 'tool') + '</span>'
        + argsHtml
        + '<i class="layui-icon layui-icon-right tool-toggle"></i>'
        + '</div>'
        + '<div class="tool-card-body"></div>';

    $(card).find('.tool-card-body').text(text || '');
    $(card).find('.tool-card-header').on('click', function() {
        $(card).toggleClass('expanded');
    });

    insertBeforeActions(sess, card);
    sess.pendingToolCard = card;

    sess.reasonBuffer = '';
    var newMd = $('<div>').addClass('md-content')[0];
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
    var errEl = $('<div>').addClass('chunk-error').text(text)[0];
    insertBeforeActions(sess, errEl);
    if (sess.sessionId === activeSessionId) scrollToBottom();
}

/* ===== Trace Badge ===== */
function appendTraceBadge(sess, chunk) {
    ensureAssistantBubble(sess);
    function fmt(n) { return n.toString().replace(/\B(?=(\d{3})+(?!\d))/g, '_'); }
    var parts = [];
    if (chunk.model) parts.push(chunk.model);
    if (chunk.totalTokens != null) parts.push(fmt(chunk.totalTokens) + 'tk');
    if (chunk.elapsedSeconds != null) parts.push(chunk.elapsedSeconds + 's');
    if (parts.length === 0) return;
    var badge = $('<div>').addClass('msg-trace').text(parts.join(' \u00b7 '));
    insertBeforeActions(sess, badge[0]);
    if (sess.sessionId === activeSessionId) scrollToBottom();
}

/* ===== Command Output ===== */
function appendCommandOutput(sess, text) {
    ensureAssistantBubble(sess);
    var mdEl = $('<div>').addClass('md-content')[0];
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
        $(labelEl).text(elapsed + 's');
    }, 1000);
}

function stopThinkingTimer(sess, timerKey, startTimeKey) {
    if (sess[timerKey]) { clearInterval(sess[timerKey]); sess[timerKey] = null; }
    sess[startTimeKey] = null;
}

function showThinking(sess) {
    console.log('[showThinking] isStreaming=%s', sess.isStreaming, new Error().stack.split('\n').slice(1,4).join('\n'));
    removeThinking(sess);
    sess.thinkingEl = $('<div>').addClass('thinking-row')[0];
    sess.thinkingEl.innerHTML = '<div class="thinking-bubble">思考中' + DOTS_HTML + '<span class="thinking-timer">0s</span></div>';
    $(sess.container).append(sess.thinkingEl);
    var timerSpan = $(sess.thinkingEl).find('.thinking-timer')[0];
    startThinkingTimer(sess, 'thinkingTimerId', 'thinkingStartTime', timerSpan);
    if (sess.sessionId === activeSessionId) scrollToBottom(true);
}
function removeThinking(sess) {
    stopThinkingTimer(sess, 'thinkingTimerId', 'thinkingStartTime');
    if (sess.thinkingEl) { $(sess.thinkingEl).remove(); sess.thinkingEl = null; }
}

function showInlineThinking(sess) {
    if (sess.inlineThinkingEl || !sess.currentBubbleEl) return;
    sess.inlineThinkingEl = $('<div>').addClass('inline-thinking')[0];
    sess.inlineThinkingEl.innerHTML = '思考中 ' + DOTS_HTML + '<span class="thinking-timer">0s</span>';
    insertBeforeActions(sess, sess.inlineThinkingEl);
    var timerSpan = $(sess.inlineThinkingEl).find('.thinking-timer')[0];
    startThinkingTimer(sess, 'inlineThinkingTimerId', 'inlineThinkingStartTime', timerSpan);
    if (sess.sessionId === activeSessionId) scrollToBottom();
}
function removeInlineThinking(sess) {
    stopThinkingTimer(sess, 'inlineThinkingTimerId', 'inlineThinkingStartTime');
    if (sess.inlineThinkingEl) { $(sess.inlineThinkingEl).remove(); }
    sess.inlineThinkingEl = null;
}

/* ===== HITL ===== */
function appendHitlCard(sess, toolName, command) {
    ensureAssistantBubble(sess);

    var card = $('<div>').addClass('hitl-card')[0];
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

    var approveBtn = $(card).find('.hitl-btn-approve')[0];
    var rejectBtn = $(card).find('.hitl-btn-reject')[0];

    $(approveBtn).on('click', function() {
        approveBtn.disabled = true;
        rejectBtn.disabled = true;
        $(approveBtn).text('\u5df2\u6279\u51c6');
        $(rejectBtn).hide();
        card.style.borderColor = 'var(--color-success)';
        handleHitlResponse(sess, 'approve');
    });

    $(rejectBtn).on('click', function() {
        approveBtn.disabled = true;
        rejectBtn.disabled = true;
        $(rejectBtn).text('\u5df2\u62d2\u7edd');
        $(approveBtn).hide();
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
    var rows = $(sess.container).find('.msg-row');
    var actual = Math.min(toRemove, rows.length);
    for (var i = 0; i < actual; i++) {
        $(rows[rows.length - 1]).remove();
        rows = $(sess.container).find('.msg-row');
    }
    resetStreamState(sess);
    if (sess.sessionId === activeSessionId) scrollToBottom(true);
}

/* ===== Code Block Copy Buttons ===== */
function addCodeBlockButtons(container) {
    if (!container) return;
    var pres = $(container).find('pre');
    for (var i = 0; i < pres.length; i++) {
        if ($(pres[i]).find('.code-copy-btn').length) continue;
        var btn = $('<button>').addClass('code-copy-btn').text('复制')[0];
        $(btn).on('click', function(e) {
            e.stopPropagation();
            var pre = $(this).closest('pre')[0];
            var code = pre ? $(pre).find('code')[0] : null;
            var text = code ? $(code).text() : (pre ? $(pre).text() : '');
            var self = this;
            if (navigator.clipboard) {
                navigator.clipboard.writeText(text).then(function() {
                    $(self).text('已复制').addClass('copied');
                    setTimeout(function() {
                        $(self).text('复制').removeClass('copied');
                    }, 1500);
                });
            }
        });
        $(pres[i]).append(btn);
    }
}

/* ===== Image Lightbox ===== */
function addImageLightbox(container) {
    if (!container) return;
    var imgs = $(container).find('.msg-bubble img, .md-content img');
    for (var i = 0; i < imgs.length; i++) {
        if ($(imgs[i]).data('lightbox')) continue;
        $(imgs[i]).data('lightbox', '1');
        imgs[i].style.cursor = 'zoom-in';
        $(imgs[i]).on('click', function(e) {
            e.stopPropagation();
            openLightbox(this.src);
        });
    }
}

function openLightbox(src) {
    var overlay = $('<div>').addClass('lightbox-overlay')[0];
    var img = $('<img>').attr('src', src)[0];
    $(overlay).append(img);
    $(overlay).on('click', function() {
        $(overlay).remove();
    });
    $(document).on('keydown', function handler(e) {
        if (e.key === 'Escape') {
            $(overlay).remove();
            $(document).off('keydown', handler);
        }
    });
    $(document.body).append(overlay);
}

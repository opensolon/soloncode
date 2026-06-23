/* ===== app-message.js ===== */
/* 消息渲染：消息气泡 + 思考动画 + 命令输出 + HITL + 回退 */
/* 依赖：app-base.js */

/* 复制图标（icon-only，用户与 AI 消息共用） */
var COPY_SVG = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>';
var OK_SVG = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>';
/* 重新运行（循环箭头）与继续运行（快进）图标 */
var RERUN_SVG = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"></polyline><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"></path></svg>';
var CONTINUE_SVG = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="5 4 15 12 5 20 5 4"></polygon><line x1="19" y1="5" x2="19" y2="19"></line></svg>';

/* ===== Message Rendering (Session-Aware) ===== */
function appendUserMessage(sess, text, imageDataUrls, fileAttachments, createdAt) {
    var row = $('<div>').addClass('msg-row user')[0];
    row.setAttribute('data-user-msg-idx', sess.userMsgCounter++);
    row.innerHTML = '<div class="user-msg-col"><div class="msg-bubble"></div><button class="user-copy-btn" title="复制">' + COPY_SVG + '</button></div>';
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

    var span = $('<span>').addClass('user-msg-text md-content')[0];
    span.setAttribute('data-md-raw', text);
    span.innerHTML = renderMd(text);
    $(bubble).append(span);
    if (typeof addCodeBlockButtons === 'function') addCodeBlockButtons(span);
    if (typeof highlightCodeBlocks === 'function') highlightCodeBlocks(span);
    if (typeof processMermaidBlocks === 'function') processMermaidBlocks(span);

    // 长消息或含代码块时放宽气泡宽度，避免被挤成窄高条
    var hasCodeBlock = $(span).find('pre').length > 0;
    var isLongUserText = text && text.length > 100;
    if (hasCodeBlock || isLongUserText) $(row).addClass('wide-user');

    var copyBtn = $(row).find('.user-copy-btn')[0];
    $(copyBtn).on('click', function() {
        var txtEl = $(bubble).find('.user-msg-text')[0];
        var md = txtEl ? (txtEl.getAttribute('data-md-raw') || txtEl.innerText) : '';
        if (navigator.clipboard) {
            navigator.clipboard.writeText(md).then(function() {
                $(copyBtn).addClass('copied');
                copyBtn.innerHTML = OK_SVG;
                setTimeout(function() {
                    $(copyBtn).removeClass('copied');
                    copyBtn.innerHTML = COPY_SVG;
                }, 1500);
            });
        }
    });

    // 时间戳（实时发送不传 createdAt 时兜底为当前时间，与历史加载行为一致）
    var msgTime = createdAt || Date.now();
    var timeEl = $('<div>').addClass('msg-time').text(formatMsgTime(msgTime))[0];
    $(bubble).append(timeEl);

    addImageLightbox(bubble);
    $(sess.container).append(row);
    // 容器不在 DOM 树中（如 loadMessages 的临时容器阶段）时跳过滚动，避免无效回流
    if (sess.sessionId === activeSessionId && document.contains(sess.container)) scrollToBottom(true);
}

function appendSystemNotice(sess, text) {
    var row = $('<div>').addClass('msg-row system-notice')[0];
    row.innerHTML = '<div class="system-notice-bubble">' + escapeHtml(text) + '</div>';
    $(sess.container).append(row);
    if (sess.sessionId === activeSessionId) scrollToBottom(true);
}

function ensureAssistantBubble(sess) {
    if (!sess.currentBubbleEl) {
        removeThinking(sess);
        var row = $('<div>').addClass('msg-row assistant')[0];
        // 存储当前 runId，用于后续删除同一运行的消息
        if (sess.currentRunId) {
            row.setAttribute('data-run-id', sess.currentRunId);
        }
        row.innerHTML = '<div class="msg-bubble"><div class="md-content"></div>'
            + '<div class="msg-time" style="display:none"></div>'
            + '<div class="msg-actions">'
            + '<button class="user-copy-btn copy-btn" title="复制">' + COPY_SVG + '</button>'
            + '<button class="user-copy-btn rerun-btn" title="重新运行">' + RERUN_SVG + '</button>'
            + '<button class="user-copy-btn continue-btn" title="继续运行">' + CONTINUE_SVG + '</button>'
            + '</div></div>';
        $(sess.container).append(row);
        sess.currentBubbleEl = $(row).find('.md-content')[0];
        var copyBtn = $(row).find('.copy-btn')[0];
        // 复制目标为「最终答案」：统一从 .md-content 的 data-md-raw 读取。
        // 历史消息与流式结束后后端写入的最终答案都带该属性；流式接收过程中不写，故复制不到中间片段。
        // 无 data-md-raw 时（旧数据/异常）回退到尾部首个非空块的 innerText。
        var bubbleEl = $(row).find('.msg-bubble')[0];
        $(copyBtn).on('click', function() {
            var md = '';
            var blocks = $(bubbleEl).children('.md-content');
            for (var bi = blocks.length - 1; bi >= 0; bi--) {
                var raw = blocks[bi].getAttribute('data-md-raw');
                if (raw != null && raw.trim()) { md = raw; break; }
            }
            if (!md) {
                for (var bj = blocks.length - 1; bj >= 0; bj--) {
                    var t = blocks[bj].innerText || '';
                    if (t.trim()) { md = t; break; }
                }
            }
            if (navigator.clipboard) {
                navigator.clipboard.writeText(md).then(function() {
                    $(copyBtn).addClass('copied');
                    copyBtn.innerHTML = OK_SVG;
                    setTimeout(function() {
                        $(copyBtn).removeClass('copied');
                        copyBtn.innerHTML = COPY_SVG;
                    }, 1500);
                });
            }
        });
        // 重新运行 / 继续运行：复用后端已有的 /rerun、/continue 命令。
        // rerun：删除同一 runId 的所有 AI 消息行（旧回复），新回复流式渲染到新气泡，与后端回退保持一致。
        // continue：保留当前气泡，新内容自然追加到新气泡，呈现“接着往下写”的效果。
        var rerunBtn = $(row).find('.rerun-btn')[0];
        var continueBtn = $(row).find('.continue-btn')[0];
        function triggerCommand(cmd, removeRow) {
            if (sess.isStreaming) return;
            if (typeof sendCommandSilent !== 'function') return;
            sendCommandSilent(cmd, function() {
                if (removeRow) {
                    // 删除同一 runId 的所有元素（消息行、工具卡片、思考块等）
                    var runId = row.getAttribute('data-run-id');
                    if (runId) {
                        // 删除所有具有相同 runId 的元素
                        $(sess.container).find('[data-run-id="' + runId + '"]').remove();
                    } else {
                        // 兼容旧数据：如果没有 runId，只删除当前行
                        $(row).remove();
                    }
                    // 重置会话状态
                    sess.currentBubbleEl = null;
                    sess.thinkingBlockEl = null;
                    sess.pendingToolCard = null;
                }
            });
        }
        if (rerunBtn) $(rerunBtn).on('click', function() { triggerCommand('/rerun', true); });
        if (continueBtn) $(continueBtn).on('click', function() { triggerCommand('/continue', false); });
        // 流式输出过程中隐藏复制按钮，待 finishStream 收尾后再显示；
        // 非流式（历史加载）保持原有显示逻辑。
        if (sess.isStreaming) {
            $(row).find('.msg-actions').hide();
            // 流式中提前创建常驻的内联等待指示器（默认不可见但占位），避免后续显隐造成跳动。
            ensureInlineThinking(sess);
        }
    }
    return sess.currentBubbleEl;
}

function ensureThinkingBlock(sess) {
    if (!sess.thinkingBlockEl) {
        ensureAssistantBubble(sess);
        var parent = sess.currentBubbleEl.parentNode;
        var block = $('<div>').addClass('thinking-block streaming expanded')[0];
        // 存储当前 runId，用于后续删除同一运行的消息
        if (sess.currentRunId) {
            block.setAttribute('data-run-id', sess.currentRunId);
        }
        block.innerHTML = '<div class="thinking-block-header">'
            + '<span class="thinking-block-label">思考中</span>'
            + '<span class="thinking-timer-wrap" style="margin-left:4px">'
            + '<span class="thinking-current-timer">0s</span>'
            + '</span>'
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
        var currentTimerSpan = $(block).find('.thinking-current-timer')[0];
        startThinkingTimerDual(sess, 'thinkingBlockTimerId', 'thinkingBlockStartTime', currentTimerSpan, null);
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
    // 若存在常驻的内联等待指示器，新内容应插在其上方，保证指示器始终在气泡底部。
    var anchor = (sess.inlineThinkingEl && sess.inlineThinkingEl.parentNode) ? sess.inlineThinkingEl : null;
    if (anchor) { $(anchor).before(el); return; }
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
        if (sess.thinkingBodyMdEl && typeof processMermaidBlocks === 'function') processMermaidBlocks(sess.thinkingBodyMdEl);
        $(sess.thinkingBlockEl).removeClass('streaming');
        if (window.cliPrintSimplified !== false) {
            $(sess.thinkingBlockEl).removeClass('expanded');
        }
        var elapsed = '';
        if (sess.thinkingBlockStartTime) {
            elapsed = ' (' + Math.floor((Date.now() - sess.thinkingBlockStartTime) / 1000) + 's)';
        }
        var label = $(sess.thinkingBlockEl).find('.thinking-block-label')[0];
        if (label) $(label).text('思考结束' + elapsed);
        $(sess.thinkingBlockEl).find('.thinking-block-dots').remove();
        $(sess.thinkingBlockEl).find('.thinking-timer-wrap').remove();
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

/* ===== Tool Body Renderer Registry =====
   工具结果渲染注册表：按 toolName 注册专用渲染器，解耦硬编码的 if-else。
   renderer(bodyEl, text, args) 渲染成功返回 true；返回 falsy 则由调用方做纯文本兜底。
   新增工具的专用展示只需 window._toolRenderers[name] = fn，无需改动主流程。 */
window._toolRenderers = window._toolRenderers || {};

/* edit：git-diff 风格逐行着色 + 行号 */
window._toolRenderers.edit = function(bodyEl, text, args) {
    var diff = (args && typeof args.diff === 'string') ? args.diff : null;
    var result = (typeof text === 'string') ? text : null;
    if (!diff && result && result.startsWith('---')) { diff = result; result = null; }
    if (!diff && !result) return false;
    bodyEl.style.padding = '0';
    bodyEl.style.maxHeight = '400px';
    bodyEl.style.overflow = 'auto';
    bodyEl.style.fontFamily = 'var(--font-mono)';
    bodyEl.style.fontSize = '12px';
    bodyEl.style.lineHeight = '1.5';

    var lines = (diff || '').split('\n');
    var html = '';
    var oldLineNo = 0, newLineNo = 0;
    var hunkRe = /^@@\s+-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?\s+@@/;

    for (var i = 0; diff && i < lines.length; i++) {
        var rawLine = lines[i];
        var line = escapeHtml(rawLine);

        if (rawLine.startsWith('+++') || rawLine.startsWith('---')) {
            html += '<div class="git-diff-line git-line-head">'
                + '<span class="git-line-num"></span>'
                + '<span class="git-line-num"></span>'
                + '<span class="git-line-text">' + line + '</span></div>';
        } else if (rawLine.startsWith('@@')) {
            var m = rawLine.match(hunkRe);
            if (m) {
                oldLineNo = parseInt(m[1], 10);
                newLineNo = parseInt(m[2], 10);
            }
            html += '<div class="git-diff-line git-line-hunk">'
                + '<span class="git-line-num"></span>'
                + '<span class="git-line-num"></span>'
                + '<span class="git-line-text">' + line + '</span></div>';
        } else if (rawLine.startsWith('+')) {
            html += '<div class="git-diff-line git-line-add">'
                + '<span class="git-line-num"></span>'
                + '<span class="git-line-num">' + (newLineNo++) + '</span>'
                + '<span class="git-line-text">' + line + '</span></div>';
        } else if (rawLine.startsWith('-')) {
            html += '<div class="git-diff-line git-line-del">'
                + '<span class="git-line-num">' + (oldLineNo++) + '</span>'
                + '<span class="git-line-num"></span>'
                + '<span class="git-line-text">' + line + '</span></div>';
        } else {
            html += '<div class="git-diff-line git-line-ctx">'
                + '<span class="git-line-num">' + (oldLineNo++) + '</span>'
                + '<span class="git-line-num">' + (newLineNo++) + '</span>'
                + '<span class="git-line-text">' + line + '</span></div>';
        }
    }
    // 输出段：成功时仅展示 diff（结果提示与改动重复，显示冗余，已隐藏）；
    // 仅在出错时渲染错误信息，避免编辑失败时卡片体空白。
    if (result && result !== diff) {
        var isErr = result.indexOf("成功完成") < 0;
        if (isErr) {
            if (diff) html += '<div class="edit-result-sep"></div>';
            html += '<div class="edit-result is-error">'
                + '<span class="edit-result-label"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path><line x1="12" y1="9" x2="12" y2="13"></line><line x1="12" y1="17" x2="12.01" y2="17"></line></svg> \u5931\u8d25</span>'
                + '<span class="edit-result-text">' + escapeHtml(result) + '</span></div>';
        }
    }
    bodyEl.innerHTML = html;
    return true;
};

/* write / read：按 file_path 推断语言，hljs 语法高亮 */
function renderHighlightedFile(bodyEl, text, args) {
    if (!text) return false;
    var filePath = (args && args.file_path) || '';
    var lang = (typeof window.guessLang === 'function') ? window.guessLang(filePath) : '';
    if (lang && typeof hljs !== 'undefined') {
        try {
            var highlighted = hljs.highlight(text, { language: lang, ignoreIllegals: true });
            bodyEl.innerHTML = '<pre style="margin:0;padding:10px;overflow:auto;border-radius:0;background:var(--bg-code, #f5f5f5);line-height:1.5"><code class="hljs">' + highlighted.value + '</code></pre>';
            return true;
        } catch(e) {
            return false;
        }
    }
    return false;
}
window._toolRenderers.write = renderHighlightedFile;
window._toolRenderers.read = renderHighlightedFile;

/* grep：按 '路径:行号: 内容' 逐行解析，同一文件归组，行号高亮、内容等宽。
   命中"未找到结果。"等非结果文本则交还兜底。 */
window._toolRenderers.grep = function(bodyEl, text, args) {
    if (!text) return false;
    var lineRe = /^(.*?):(\d+):\s?(.*)$/;
    var lines = text.split('\n');
    var groups = [];
    var index = {};
    var matched = 0;
    for (var i = 0; i < lines.length; i++) {
        var raw = lines[i];
        if (!raw) continue;
        var m = raw.match(lineRe);
        if (!m) {
            if (groups.length && (raw.indexOf('\u672a\u5b8c') >= 0 || raw.indexOf('\u8b66\u544a') >= 0 || raw.indexOf('\u622a\u65ad') >= 0)) {
                groups[groups.length - 1].note = (groups[groups.length - 1].note || '') + raw + ' ';
            }
            continue;
        }
        matched++;
        var p = m[1];
        if (!(p in index)) { index[p] = groups.length; groups.push({ path: p, hits: [] }); }
        groups[index[p]].hits.push({ ln: m[2], content: m[3] });
    }
    if (matched === 0) return false;
    var html = '<div class="grep-result">';
    var totalHits = 0;
    groups.forEach(function(g) { totalHits += g.hits.length; });
    html += '<div class="tool-summary">' + groups.length + ' \u4e2a\u6587\u4ef6 / ' + totalHits + ' \u5904\u5339\u914d</div>';
    groups.forEach(function(g) {
        html += '<div class="grep-file"><span class="grep-file-icon"><svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M4 1.5h4.75L12.5 5.75V13.5a1 1 0 01-1 1H4a1 1 0 01-1-1V2.5a1 1 0 011-1z" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/><path d="M8.75 1.5v4.25H12.5" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/></svg></span>' + escapeHtml(g.path) + '</div>';
        g.hits.forEach(function(h) {
            html += '<div class="grep-hit"><span class="grep-ln">' + escapeHtml(h.ln) + '</span>'
                + '<span class="grep-code">' + escapeHtml(h.content) + '</span></div>';
        });
        if (g.note) html += '<div class="grep-note">' + escapeHtml(g.note.trim()) + '</div>';
    });
    html += '</div>';
    bodyEl.innerHTML = html;
    return true;
};

/* glob / ls：按 '[FILE] path' / '[DIR] path/' 解析为带图标的文件列表；
   ls 递归 tree（缩进 + 树形字符）走兜底等宽展示，避免破坏对齐。 */
function renderFileListing(bodyEl, text, args) {
    if (!text) return false;
    if (text.indexOf('\u672a\u627e\u5230') >= 0 && text.indexOf('[') < 0) return false;
    var lines = text.split('\n');
    var entryRe = /^\[(FILE|DIR)\]\s+(.*)$/;
    var items = [];
    var hasTree = false;
    for (var i = 0; i < lines.length; i++) {
        var raw = lines[i];
        if (!raw) continue;
        var m = raw.match(entryRe);
        if (m) { items.push({ dir: m[1] === 'DIR', path: m[2] }); }
        else if (/[\u2502\u251c\u2514]/.test(raw)) { hasTree = true; break; }
    }
    if (hasTree || items.length === 0) return false;
    var html = '<div class="file-listing"><div class="tool-summary">' + items.length + ' \u9879</div>';
    items.forEach(function(it) {
        var icon = it.dir
            ? '<svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M2 4a1 1 0 011-1h3.5l1.5 1.5H13a1 1 0 011 1V12a1 1 0 01-1 1H3a1 1 0 01-1-1V4z" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/></svg>'
            : '<svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M4 1.5h4.75L12.5 5.75V13.5a1 1 0 01-1 1H4a1 1 0 01-1-1V2.5a1 1 0 011-1z" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/><path d="M8.75 1.5v4.25H12.5" stroke="currentColor" stroke-width="1" stroke-linejoin="round"/></svg>';
        html += '<div class="file-entry' + (it.dir ? ' is-dir' : '') + '">'
            + '<span class="file-entry-icon">' + icon + '</span>'
            + '<span class="file-entry-path">' + escapeHtml(it.path) + '</span></div>';
    });
    html += '</div>';
    bodyEl.innerHTML = html;
    return true;
}
window._toolRenderers.glob = renderFileListing;
window._toolRenderers.ls = renderFileListing;

/* bash：终端风格输出块，等宽、深色、保留换行 */
window._toolRenderers.bash = function(bodyEl, text, args) {
    bodyEl.style.padding = '0';
    var cmd = (args && args.command) ? args.command : '';
    var html = '<div class="bash-output">';
    if (cmd) html += '<div class="bash-cmd"><span class="bash-prompt">$</span> ' + escapeHtml(cmd) + '</div>';
    html += '<pre class="bash-stdout">' + escapeHtml(text || '(\u65e0\u8f93\u51fa)') + '</pre>';
    html += '</div>';
    bodyEl.innerHTML = html;
    return true;
};

/* todowrite / todoread：内容为 markdown 任务清单，按 markdown 语法高亮展示原文（不做 HTML 渲染，保留 #、-、[ ] 等原始符号）。
   todowrite 优先取入参 todos（提交的清单原文），todoread 取返回值 text。 */
function renderTodoMarkdown(bodyEl, text, args) {
    var md = (args && typeof args.todos === 'string' && args.todos.trim()) ? args.todos : text;
    if (!md || typeof md !== 'string' || !md.trim()) return false;
    var inner;
    if (typeof hljs !== 'undefined') {
        try { inner = hljs.highlight(md, { language: 'markdown' }).value; } catch(e) {}
    }
    if (!inner) inner = md.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    bodyEl.innerHTML = '<pre style="margin:0;padding:10px"><code class="hljs language-markdown">' + inner + '</code></pre>';
    return true;
}
window._toolRenderers.todowrite = renderTodoMarkdown;
window._toolRenderers.todoread = renderTodoMarkdown;

/* 分发：命中专用 renderer 且渲染成功返回 true，否则交由调用方做纯文本兜底 */
function renderToolBody(bodyEl, toolName, text, args) {
    var renderer = window._toolRenderers[toolName];
    if (typeof renderer === 'function') {
        try {
            if (renderer(bodyEl, text, args)) return true;
        } catch(e) {}
    }
    return false;
}

/* 抽取：把 args 对象格式化为短字符串（供卡片头部 tool-args 展示）。
   与 appendActionEndChunk 内的实现保持一致，供 action_start 复用。 */
function formatToolArgsStr(args) {
    function formatArgValue(v) {
        if (v === null) return 'null';
        if (v === undefined) return 'undefined';
        if (typeof v === 'string') return v.replace(/\n/g, ' ');
        if (typeof v === 'number' || typeof v === 'boolean') return String(v);
        if (Array.isArray(v)) return '[' + v.length + '\u9879]';
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
    if (!args || typeof args !== 'object') return '';
    var parts = [];
    // 跳过大体积字段（由 body 渲染器专门展示），避免头部塞入整段 diff/内容
    var skip = { diff: 1, content: 1, todos: 1 };
    Object.keys(args).forEach(function(k) { if (skip[k]) return; parts.push(k + '=' + formatArgValue(args[k])); });
    var argsStr = parts.join(' ');
    if (argsStr.length > 80) argsStr = argsStr.substring(0, 77) + '...';
    return argsStr;
}

/* action_start：工具调用前（来源引擎 ActionChunk）提前渲染 loading 卡片骨架。
   存为 sess.pendingToolCard，待 action（ObservationChunk 结果）到达时由
   appendActionEndChunk 复用此卡片填充结果体并转完成态。 */
function appendActionStartChunk(sess, toolName, args, toolTitle) {
    // 若已有未完成的 pending 卡（异常时序/重复 start），先收尾避免悬挂
    finishPendingTool(sess);
    ensureAssistantBubble(sess);

    var argsStr = formatToolArgsStr(args);
    var argsHtml = argsStr ? '<span class="tool-args">' + escapeHtml(argsStr) + '</span>' : '';

    var card = $('<div>').addClass('tool-card')[0];
    // 存储当前 runId，用于后续删除同一运行的消息
    if (sess.currentRunId) {
        card.setAttribute('data-run-id', sess.currentRunId);
    }
    if (window.cliPrintSimplified === false) $(card).addClass('expanded');
    card.innerHTML = '<div class="tool-card-header">'
        + '<span class="tool-status-icon loading"></span>'
        + '<span class="tool-name">' + escapeHtml(toolTitle || toolName || 'tool') + '</span>'
        + argsHtml
        + '<i class="layui-icon layui-icon-right tool-toggle"></i>'
        + '</div>'
        + '<div class="tool-card-body"></div>';

    $(card).find('.tool-card-header').on('click', function() {
        $(card).toggleClass('expanded');
    });

    insertBeforeActions(sess, card);
    sess.pendingToolCard = card;
    // 标记该卡由 action_start 提前创建，等待结果填充
    sess.pendingToolStarted = true;
    if (sess.sessionId === activeSessionId) scrollToBottom();
}

function appendActionEndChunk(sess, toolName, text, args, toolTitle) {
    // 复用分支：若该工具卡由 action_start 提前创建（loading 中），直接填充结果体并转完成态，避免重复建卡
    if (sess.pendingToolStarted && sess.pendingToolCard) {
        var pc = sess.pendingToolCard;
        sess.pendingToolStarted = false;
        var pcArgsStr = formatToolArgsStr(args);
        $(pc).find('.tool-name').text(toolTitle || toolName || 'tool');
        var pcArgsEl = $(pc).find('.tool-args')[0];
        if (pcArgsStr) {
            if (pcArgsEl) { pcArgsEl.textContent = pcArgsStr; }
            else { $('<span>').addClass('tool-args').text(pcArgsStr).insertAfter($(pc).find('.tool-name')); }
        }
        var pcBody = $(pc).find('.tool-card-body')[0];
        if (pcBody) {
            pcBody.removeAttribute('style');
            pcBody.innerHTML = '';
            if (!renderToolBody(pcBody, toolName, text, args)) { pcBody.textContent = text || ''; }
        }
        finishPendingTool(sess);
        if (window._todoChunkHandlers) { /* todo 由 streaming 层单独处理，这里不重复 */ }
        sess.reasonBuffer = '';
        var pcMd = $('<div>').addClass('md-content')[0];
        insertBeforeActions(sess, pcMd);
        sess.currentBubbleEl = pcMd;
        if (sess.sessionId === activeSessionId) scrollToBottom();
        return;
    }
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
        var skipArgs = { diff: 1, content: 1, todos: 1 };
        Object.keys(args).forEach(function(k) {
            if (skipArgs[k]) return; parts.push(k + '=' + formatArgValue(args[k]));
        });
        var argsStr = parts.join(' ');
        if (argsStr.length > 80) argsStr = argsStr.substring(0, 77) + '...';
        if (argsStr) argsHtml = '<span class="tool-args">' + escapeHtml(argsStr) + '</span>';
    }

    // 复用分支：若刚批准过 HITL，结果渲染进同一张审批卡片，避免出现两张卡
    if (sess.approvedToolCard) {
        var rc = sess.approvedToolCard;
        sess.approvedToolCard = null;
        $(rc).find('.tool-name').text(toolTitle || toolName || 'tool');
        var rcArgsEl = $(rc).find('.tool-args')[0];
        if (argsStr) {
            if (rcArgsEl) { rcArgsEl.textContent = argsStr; }
            else { $('<span>').addClass('tool-args').text(argsStr).insertAfter($(rc).find('.tool-name')); }
        }
        var rcBody = $(rc).find('.tool-card-body')[0];
        if (rcBody) {
            rcBody.removeAttribute('style');
            rcBody.innerHTML = '';
            if (!renderToolBody(rcBody, toolName, text, args)) { rcBody.textContent = text || ''; }
        }
        if (window.cliPrintSimplified === false) $(rc).addClass('expanded');
        else $(rc).removeClass('expanded');
        sess.pendingToolCard = rc;
        sess.reasonBuffer = '';
        var rcMd = $('<div>').addClass('md-content')[0];
        insertBeforeActions(sess, rcMd);
        sess.currentBubbleEl = rcMd;
        if (sess.sessionId === activeSessionId) scrollToBottom();
        return;
    }

    var card = $('<div>').addClass('tool-card')[0];
    // 存储当前 runId，用于后续删除同一运行的消息
    if (sess.currentRunId) {
        card.setAttribute('data-run-id', sess.currentRunId);
    }
    if (window.cliPrintSimplified === false) $(card).addClass('expanded');
    card.innerHTML = '<div class="tool-card-header">'
        + '<span class="tool-status-icon loading"></span>'
        + '<span class="tool-name">' + escapeHtml(toolTitle || toolName || 'tool') + '</span>'
        + argsHtml
        + '<i class="layui-icon layui-icon-right tool-toggle"></i>'
        + '</div>'
        + '<div class="tool-card-body"></div>';

    // 工具结果渲染：委托注册表分发，未命中专用 renderer 则纯文本兜底
    var toolBody = $(card).find('.tool-card-body')[0];
    if (!renderToolBody(toolBody, toolName, text, args)) {
        toolBody.textContent = text || '';
    }

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
            // 流式接收过程中不写 data-md-raw（该属性是复制源，仅由 finishStream 后后端最终答案写入）；
            // 避免复制到被工具调用切开的中间片段。
            // 流式过程中仅渲染 Markdown，跳过代码高亮和复制按钮以避免每帧全量重建 DOM；
            // 这两项在 finishStream 中会做最终的完整处理。
            el.innerHTML = renderMd(sess.reasonBuffer);

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
    // 后端携带的最终答案为权威复制源，写到当前 .md-content 的 data-md-raw（与历史消息统一属性名），供复制按钮读取。
    if (chunk.finalAnswer != null && sess.currentBubbleEl) {
        sess.currentBubbleEl.setAttribute('data-md-raw', chunk.finalAnswer);
    }
    function fmtK(n) {
        if (n >= 1000000 && n % 1000000 === 0) return (n / 1000000) + 'm';
        if (n >= 1000) return (n / 1000).toFixed(n % 1000 === 0 ? 0 : 1).replace(/\.0$/, '') + 'k';
        return n.toString();
    }
    var parts = [];
    if (chunk.model) parts.push(chunk.model);
    if (chunk.totalTokens != null) parts.push(fmtK(chunk.totalTokens));
    if (chunk.elapsedSeconds != null) parts.push(chunk.elapsedSeconds + 's');
    var now = new Date();
    parts.push(formatMsgTime(now.getTime()));
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
    if (typeof processMermaidBlocks === 'function') processMermaidBlocks(mdEl);
    insertBeforeActions(sess, mdEl);
    if (sess.sessionId === activeSessionId) scrollToBottom();
}

/* ===== Thinking Indicators ===== */
function startThinkingTimer(sess, timerKey, startTimeKey, labelEl, anchorTime) {
    // anchorTime 用于让计时锚定整段响应起点（sess.messageStartTime），
    // 这样指示器反复显隐时秒数保持连续，不会从 0 重来。
    sess[startTimeKey] = anchorTime || Date.now();
    if (sess[timerKey]) clearInterval(sess[timerKey]);
    function tick() {
        if (!labelEl || !labelEl.parentNode) { clearInterval(sess[timerKey]); sess[timerKey] = null; return; }
        var elapsed = Math.floor((Date.now() - sess[startTimeKey]) / 1000);
        $(labelEl).text(elapsed + 's');
    }
    tick();
    sess[timerKey] = setInterval(tick, 1000);
}

function stopThinkingTimer(sess, timerKey, startTimeKey) {
    if (sess[timerKey]) { clearInterval(sess[timerKey]); sess[timerKey] = null; }
    sess[startTimeKey] = null;
}

// 双计时器版本：同时更新当前思考计时和总时间计时
// currentTimerSpan: 显示当前思考阶段的时间
// totalTimerSpan: 显示从消息开始到现在的总时间
function startThinkingTimerDual(sess, timerKey, startTimeKey, currentTimerSpan, totalTimerSpan) {
    sess[startTimeKey] = Date.now();
    if (sess[timerKey]) clearInterval(sess[timerKey]);
    function tick() {
        if (!currentTimerSpan || !currentTimerSpan.parentNode) { clearInterval(sess[timerKey]); sess[timerKey] = null; return; }
        var now = Date.now();
        // 当前思考阶段时间
        var currentElapsed = Math.floor((now - sess[startTimeKey]) / 1000);
        $(currentTimerSpan).text(currentElapsed + 's');
        // 总时间（从消息发送开始）
        if (totalTimerSpan && sess.messageStartTime) {
            var totalElapsed = Math.floor((now - sess.messageStartTime) / 1000);
            $(totalTimerSpan).text(totalElapsed + 's');
        }
    }
    tick();
    sess[timerKey] = setInterval(tick, 1000);
}

// 启动等待指示器：尚无气泡时，在消息区独立显示一行「圆点 + Ns」（无文字）
function showThinking(sess) {
    removeThinking(sess);
    sess.thinkingEl = $('<div>').addClass('thinking-row')[0];
    sess.thinkingEl.innerHTML = '<div class="thinking-bubble">' + DOTS_HTML 
        + '<span class="thinking-timer-wrap">'
        + '<span class="thinking-current-timer">0s</span>'
        + '</span></div>';
    $(sess.container).append(sess.thinkingEl);
    var currentTimerSpan = $(sess.thinkingEl).find('.thinking-current-timer')[0];
    startThinkingTimerDual(sess, 'thinkingTimerId', 'thinkingStartTime', currentTimerSpan, null);
    if (sess.sessionId === activeSessionId) scrollToBottom(true);
}
function removeThinking(sess) {
    stopThinkingTimer(sess, 'thinkingTimerId', 'thinkingStartTime');
    if (sess.thinkingEl) { $(sess.thinkingEl).remove(); sess.thinkingEl = null; }
}

// 气泡内的间隙等待指示器（「圆点 + Ns」，无文字）。
// 关键：元素一旦创建便常驻气泡底部（actions 之前），不可见时用 visibility:hidden 占位，
// 避免显隐导致的高度跳动；流式结束时再由 purgeInlineThinking 彻底移除。
function ensureInlineThinking(sess) {
    if (!sess.currentBubbleEl) return null;
    if (sess.inlineThinkingEl && sess.inlineThinkingEl.parentNode) return sess.inlineThinkingEl;
    var el = $('<div>').addClass('inline-thinking hidden-reserve')[0];
    el.innerHTML = DOTS_HTML + '<span class="thinking-timer-wrap">'
        + '<span class="thinking-current-timer">0s</span>'
        + '</span>';
    sess.inlineThinkingEl = el;
    $(sess.currentBubbleEl.parentNode).find('.msg-actions').first().before(el);
    return el;
}
function showInlineThinking(sess) {
    var el = ensureInlineThinking(sess);
    if (!el) return;
    $(el).removeClass('hidden-reserve');
    var currentTimerSpan = $(el).find('.thinking-current-timer')[0];
    startThinkingTimerDual(sess, 'inlineThinkingTimerId', 'inlineThinkingStartTime', currentTimerSpan, null);
    if (sess.sessionId === activeSessionId) scrollToBottom();
}
function removeInlineThinking(sess) {
    stopThinkingTimer(sess, 'inlineThinkingTimerId', 'inlineThinkingStartTime');
    if (sess.inlineThinkingEl) { $(sess.inlineThinkingEl).addClass('hidden-reserve'); }
}
function purgeInlineThinking(sess) {
    stopThinkingTimer(sess, 'inlineThinkingTimerId', 'inlineThinkingStartTime');
    if (sess.inlineThinkingEl) { $(sess.inlineThinkingEl).remove(); sess.inlineThinkingEl = null; }
}

/* ===== HITL ===== */
function appendHitlCard(sess, toolName, command) {
    ensureAssistantBubble(sess);

    // 采用 tool-card 视觉体系：审批通过后原地复用为工具结果卡片
    var argsHtml = command ? '<span class="tool-args">' + escapeHtml(command) + '</span>' : '';
    var card = $('<div>').addClass('tool-card hitl-pending expanded')[0];
    // 存储当前 runId，用于后续删除同一运行的消息
    if (sess.currentRunId) {
        card.setAttribute('data-run-id', sess.currentRunId);
    }
    card.innerHTML = '<div class="tool-card-header">'
        + '<span class="tool-status-icon warn"><i class="layui-icon layui-icon-tips" style="font-size:13px"></i></span>'
        + '<span class="tool-name">\u9700\u8981\u6388\u6743\uff1a' + escapeHtml(toolName || 'unknown') + '</span>'
        + argsHtml
        + '<i class="layui-icon layui-icon-right tool-toggle"></i>'
        + '</div>'
        + '<div class="tool-card-body">' + (command ? escapeHtml(command) : '\u7b49\u5f85\u6388\u6743\u4ee5\u6267\u884c\u8be5\u5de5\u5177') + '</div>'
        + '<div class="hitl-card-actions">'
        + '<button class="hitl-btn hitl-btn-approve">\u6279\u51c6</button>'
        + '<button class="hitl-btn hitl-btn-reject">\u62d2\u7edd</button>'
        + '</div>';

    $(card).find('.tool-card-header').on('click', function() {
        $(card).toggleClass('expanded');
    });

    insertBeforeActions(sess, card);

    var approveBtn = $(card).find('.hitl-btn-approve')[0];
    var rejectBtn = $(card).find('.hitl-btn-reject')[0];

    $(approveBtn).on('click', function() {
        approveBtn.disabled = true;
        rejectBtn.disabled = true;
        // 转为"执行中"，标记后续 action 结果复用此卡片
        var icon = $(card).find('.tool-status-icon')[0];
        if (icon) { icon.className = 'tool-status-icon loading'; icon.innerHTML = ''; }
        $(card).find('.hitl-card-actions').remove();
        $(card).removeClass('hitl-pending');
        sess.approvedToolCard = card;
        handleHitlResponse(sess, 'approve');
    });

    $(rejectBtn).on('click', function() {
        approveBtn.disabled = true;
        rejectBtn.disabled = true;
        var icon = $(card).find('.tool-status-icon')[0];
        if (icon) { icon.className = 'tool-status-icon reject'; icon.innerHTML = '<i class="layui-icon layui-icon-close" style="font-size:12px"></i>'; }
        $(card).find('.tool-name').text('\u5df2\u62d2\u7edd\uff1a' + (toolName || 'unknown'));
        $(card).find('.hitl-card-actions').remove();
        $(card).removeClass('hitl-pending expanded');
        sess.approvedToolCard = null;
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

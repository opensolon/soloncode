/* ===== app-message.js ===== */
/* 消息渲染：消息气泡 + 思考动画 + 命令输出 + HITL + 回退 */
/* 依赖：app-base.js */

/* 复制图标（icon-only，用户与 AI 消息共用） */
var COPY_SVG = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>';
var OK_SVG = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>';
/* 重新运行（循环箭头）与继续运行（快进）图标 */
var RERUN_SVG = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"></polyline><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"></path></svg>';
var CONTINUE_SVG = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="5 4 15 12 5 20 5 4"></polygon><line x1="19" y1="5" x2="19" y2="19"></line></svg>';
/* 删除图标 */
var DELETE_SVG = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg>';

/* ===== Message Rendering (Session-Aware) ===== */
function appendUserMessage(sess, text, imageDataUrls, fileAttachments, createdAt, sourceLabel) {
    var row = $('<div>').addClass('msg-row user')[0];
    row.setAttribute('data-user-msg-idx', sess.userMsgCounter++);
    row.setAttribute('data-session-id', sess.sessionId);
    row.innerHTML = '<div class="user-msg-col"><div class="msg-bubble"></div><div class="msg-actions"><button class="user-copy-btn" title="复制">' + COPY_SVG + '</button><button class="user-del-btn" title="删除此处及之后消息">' + DELETE_SVG + '</button></div></div>';
    var bubble = $(row).find('.msg-bubble')[0];

    // 来源标签（仅非空且非 "Web" 时显示；会在时间戳左侧追加）

    // Multiple images
    if (imageDataUrls && imageDataUrls.length > 0) {
        var imgWrap = $('<div>').addClass('user-attach-imgs')[0];
        for (var i = 0; i < imageDataUrls.length; i++) {
            var img = $('<img>').attr('src', imageDataUrls[i].dataUrl || imageDataUrls[i])
                .attr('style', 'max-height:120px;max-width:200px;border-radius:8px;object-fit:cover;')[0];
            $(imgWrap).append(img);
        }
        $(bubble).append(imgWrap);
    }

    // Multiple file / image attachment tags（来自实时上传的 {name,size,type} 或历史元数据 {name,type}）
    if (fileAttachments && fileAttachments.length > 0) {
        for (var j = 0; j < fileAttachments.length; j++) {
            var att = fileAttachments[j];
            var tag = $('<div>').addClass('user-attach-file')[0];
            var sizeHtml = att.size != null ? '<span class="user-attach-file-size">(' + formatFileSize(att.size) + ')</span>' : '';
            tag.innerHTML = '<span class="user-attach-file-name">' + escapeHtml(att.name) + '</span>'
                + sizeHtml;
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

    var delBtn = $(row).find('.user-del-btn')[0];
    $(delBtn).on('click', function() {
        layer.confirm('确认删除此消息及之后的所有消息？此操作不可撤销。', {
            title: '确认删除',
            btn: ['删除', '取消'],
            icon: 3,
            offset: '120px'
        }, function(index) {
            var rows = $(sess.container).find('.msg-row');
            var idx = rows.index(row);
            if (idx < 0) { layer.close(index); return; }
            // 后端只删有 ndjson 记录的消息（排除命令消息），避免多删
            var serverCount = calcServerCount(sess.container, row);
            $.post('/web/chat/rewind', {
                sessionId: sess.sessionId,
                count: serverCount
            });
            // 前端删所有可视行（含命令消息的无记录行），保持界面干净
            handleRewind(sess, rows.length - idx);
            layer.close(index);
        });
    });

    // 时间戳（实时发送不传 createdAt 时兜底为当前时间，与历史加载行为一致）
    var msgTime = createdAt || Date.now();
    var timeEl = $('<div>').addClass('msg-time')[0];
    // 来源标签放在时间左侧，同样浅色
    if (sourceLabel && sourceLabel !== 'Web') {
        var srcSpan = $('<span>').addClass('msg-source-label').text(sourceLabel)[0];
        $(timeEl).append(srcSpan);
    }
    timeEl.appendChild(document.createTextNode(formatMsgTime(msgTime)));
    $(bubble).append(timeEl);

    addImageLightbox(bubble);
    $(sess.container).append(row);
    // 容器不在 DOM 树中（如 loadMessages 的临时容器阶段）时跳过滚动，避免无效回流
    if (sess.sessionId === activeSessionId && document.contains(sess.container)) scrollToBottom(true);
}

/* 刷新用户消息的时间戳，在编辑/重发时调用 */
function refreshUserMessageTime(container, sessionId) {
    $(container).find('.msg-row.user').each(function() {
        var timeEl = $(this).find('.msg-time')[0];
        if (timeEl && !$(timeEl).data('refreshed')) {
            $(timeEl).data('refreshed', true);
            // 仅更新纯文本节点（时间），保留来源标签
            var textNodes = Array.from(timeEl.childNodes).filter(function(n) {
                return n.nodeType === Node.TEXT_NODE;
            });
            if (textNodes.length > 0) {
                textNodes[0].textContent = formatMsgTime(Date.now());
            }
        }
    });
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
        var row = $('<div>').addClass('msg-row assistant ' + (sess.isStreaming ? 'streaming' : 'done'))[0];
        // 存储当前 runId，用于后续删除同一运行的消息
        if (sess.currentRunId) {
            row.setAttribute('data-run-id', sess.currentRunId);
        }
        row.setAttribute('data-session-id', sess.sessionId);
        row.innerHTML = '<div class="msg-bubble"><div class="msg-content"><div class="md-content"></div></div>'
            + '<div class="msg-time" style="display:none"></div>'
            + '<div class="msg-actions">'
            + '<button class="user-copy-btn copy-btn" title="复制">' + COPY_SVG + '</button>'
            + '<button class="user-copy-btn rerun-btn" title="重新运行">' + RERUN_SVG + '</button>'
            + '<button class="user-copy-btn continue-btn" title="继续运行">' + CONTINUE_SVG + '</button>'
            + '<button class="user-copy-btn del-btn" title="删除此处及之后消息">' + DELETE_SVG + '</button>'
            + '</div></div>';
        $(sess.container).append(row);
        sess.currentBubbleEl = $(row).find('.md-content')[0];

        // AI 回复不显示来源标签
        var copyBtn = $(row).find('.copy-btn')[0];
        // 复制目标为「最终答案」：统一从 .md-content 的 data-md-raw 读取。
        // 历史消息与流式结束后后端写入的最终答案都带该属性；流式接收过程中不写，故复制不到中间片段。
        // 无 data-md-raw 时（旧数据/异常）回退到尾部首个非空块的 innerText。
        var bubbleEl = $(row).find('.msg-bubble')[0];
        $(copyBtn).on('click', function() {
            var md = '';
            var blocks = $(bubbleEl).find('.md-content');
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
        var delBtn = $(row).find('.del-btn')[0];
        if (delBtn) $(delBtn).on('click', function() {
            layer.confirm('确认删除此消息及之后的所有消息？此操作不可撤销。', {
                title: '确认删除',
                btn: ['删除', '取消'],
                icon: 3,
                offset: '120px'
            }, function(index) {
                var rows = $(sess.container).find('.msg-row');
                var idx = rows.index(row);
                if (idx < 0) { layer.close(index); return; }
                // 后端只删有 ndjson 记录的消息（排除命令消息），避免多删
                var serverCount = calcServerCount(sess.container, row);
                $.post('/web/chat/rewind', {
                    sessionId: sess.sessionId,
                    count: serverCount
                });
                // 前端删所有可视行（含命令消息的无记录行），保持界面干净
                handleRewind(sess, rows.length - idx);
                layer.close(index);
            });
        });
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

function streamReasonKey(segment, reasonId) {
    return segment.id + '::' + reasonId;
}

function buildTaskGroupAriaLabel(segment, expanded) {
    var title = segment.taskDescription || segment.agentName || '\u5b50\u4efb\u52a1';
    var stateLabel = expanded ? '\u5df2\u5c55\u5f00' : '\u5df2\u6536\u8d77';
    if (segment.status === 'error') {
        return title + ' \u5931\u8d25\uff0c' + stateLabel + (expanded ? '' : '\uff0c\u70b9\u51fb\u5c55\u5f00\u67e5\u770b');
    }
    if (segment.status === 'done') {
        return title + ' \u5df2\u5b8c\u6210\uff0c' + stateLabel;
    }
    if (!expanded && segment.groupEl && $(segment.groupEl).hasClass('has-new-output')) {
        return title + ' \u6709\u65b0\u8f93\u51fa\uff0c\u70b9\u51fb\u5c55\u5f00\u67e5\u770b';
    }
    return title + ' \u8be6\u60c5\uff0c' + stateLabel;
}

function formatTaskGroupMeta(segment) {
    if (!segment) return '';
    var parts = [];
    if (segment.status === 'error') {
        parts.push('\u5931\u8d25');
        if (segment.errorCount > 0) parts.push(segment.errorCount + ' err');
    } else if (segment.status === 'done') {
        parts.push('\u5df2\u5b8c\u6210');
        if (segment.createdAt) {
            var endAt = segment.finishedAt || Date.now();
            var secs = Math.max(0, Math.floor((endAt - segment.createdAt) / 1000));
            parts.push(secs + 's');
        }
    } else {
        parts.push('\u8fd0\u884c\u4e2d');
    }
    if (segment.toolCount > 0) parts.push(segment.toolCount + ' tools');
    // lastActionLabel 易变长，不进主文案，改由 updateTaskGroupMeta 挂 tooltip
    return parts.join(' \u00b7 ');
}

function updateTaskGroupMeta(segment) {
    if (!segment || !segment.groupEl) return;
    var metaEl = $(segment.groupEl).find('.task-group-meta')[0];
    if (!metaEl) return;
    var text = formatTaskGroupMeta(segment);
    $(metaEl).text(text);
    metaEl.style.display = text ? '' : 'none';
    // 最近动作仅作 tooltip，避免 L2 被 path/参数扯长
    if (segment.lastActionLabel) {
        metaEl.setAttribute('title', segment.lastActionLabel);
    } else {
        metaEl.removeAttribute('title');
    }
    var header = $(segment.groupEl).find('.task-group-header')[0];
    if (header) {
        header.setAttribute('aria-label', buildTaskGroupAriaLabel(segment, $(segment.groupEl).hasClass('expanded')));
    }
}

function scheduleTaskGroupMetaUpdate(segment) {
    if (!segment || !segment.taskId) return;
    if (segment._metaRafId) return;
    segment._metaRafId = requestAnimationFrame(function() {
        segment._metaRafId = null;
        updateTaskGroupMeta(segment);
    });
}

function setTaskGroupStatus(segment, status) {
    if (!segment || !segment.groupEl || !status) return;
    // error 优先；done 不覆盖 error
    if (segment.status === 'error' && status !== 'error') return;
    if (segment.status === status && status !== 'running') {
        scheduleTaskGroupMetaUpdate(segment);
        return;
    }
    segment.status = status;
    segment.updatedAt = Date.now();
    if (status === 'done' || status === 'error') {
        segment.finishedAt = segment.finishedAt || Date.now();
    }
    $(segment.groupEl).removeClass('is-running is-done is-error');
    $(segment.groupEl).addClass('is-' + status);
    scheduleTaskGroupMetaUpdate(segment);
}

function recordTaskGroupToolStart(segment, toolName, toolTitle, args) {
    if (!segment || !segment.taskId) return;
    segment.toolCount = (segment.toolCount || 0) + 1;
    segment.hasPendingTools = (segment.hasPendingTools || 0) + 1;
    segment.lastToolName = toolTitle || toolName || null;
    var label = toolTitle || toolName || 'tool';
    if (args && typeof args === 'object') {
        var hint = args.path || args.file_path || args.pattern || args.command || args.query || args.url || args.name;
        if (typeof hint === 'string' && hint) {
            var shortHint = hint.length > 24 ? hint.substring(0, 21) + '...' : hint;
            label = (toolName || toolTitle || 'tool') + ' ' + shortHint;
        }
    }
    if (label.length > 36) label = label.substring(0, 33) + '...';
    segment.lastActionLabel = label;
    segment.updatedAt = Date.now();
    if (segment.status !== 'error') setTaskGroupStatus(segment, 'running');
    else scheduleTaskGroupMetaUpdate(segment);
}

function recordTaskGroupToolEnd(segment) {
    if (!segment || !segment.taskId) return;
    segment.hasPendingTools = Math.max(0, (segment.hasPendingTools || 0) - 1);
    segment.updatedAt = Date.now();
    scheduleTaskGroupMetaUpdate(segment);
}

function recordTaskGroupReason(segment) {
    if (!segment || !segment.taskId) return;
    segment.reasonCount = (segment.reasonCount || 0) + 1;
    segment.updatedAt = Date.now();
    if (segment.status !== 'error') setTaskGroupStatus(segment, 'running');
    else scheduleTaskGroupMetaUpdate(segment);
}

function finalizeTaskGroups(sess) {
    if (!sess || !sess.taskSegments) return;
    for (var taskId in sess.taskSegments) {
        if (!Object.prototype.hasOwnProperty.call(sess.taskSegments, taskId)) continue;
        var segment = sess.taskSegments[taskId];
        if (!segment || !segment.groupEl) continue;
        if (segment.status !== 'error') setTaskGroupStatus(segment, 'done');
        else {
            segment.finishedAt = segment.finishedAt || Date.now();
            scheduleTaskGroupMetaUpdate(segment);
        }
        // 流结束后清掉“新输出”语义，error 保留 is-error 强提示样式
        $(segment.groupEl).removeClass('has-new-output');
        var header = $(segment.groupEl).find('.task-group-header')[0];
        if (header) header.setAttribute('aria-label', buildTaskGroupAriaLabel(segment, $(segment.groupEl).hasClass('expanded')));
    }
}

function createTaskGroupElement(sess, segment) {
    var group = $('<div>').addClass('task-group is-running')[0];
    group.setAttribute('data-task-id', segment.taskId);
    group.setAttribute('data-stream-segment-id', segment.id);
    if (sess.currentRunId) group.setAttribute('data-run-id', sess.currentRunId);
    // L1 固定身份：title + agent-badge + toggle(右)；L2 动态状态 + 更新点。
    // 有 description 时 badge 展示 agentName；仅 agentName 时直接作标题，避免重复。
    var titleText = segment.taskDescription || segment.agentName || '\u5b50\u4efb\u52a1';
    var agentHtml = (segment.taskDescription && segment.agentName)
        ? '<span class="agent-badge">' + escapeHtml(segment.agentName) + '</span>'
        : '';
    var header = $('<div>').addClass('task-group-header')[0];
    // task-group 本级一律默认收起（单/多任务相同），展开由用户手动触发
    var bodyId = 'task-body-' + segment.id;
    header.setAttribute('role', 'button');
    header.setAttribute('tabindex', '0');
    header.setAttribute('aria-expanded', 'false');
    header.setAttribute('aria-controls', bodyId);
    header.setAttribute('aria-label', buildTaskGroupAriaLabel(segment, false));
    var metaText = formatTaskGroupMeta(segment);
    var metaTitleAttr = segment.lastActionLabel
        ? ' title="' + escapeHtmlAttr(segment.lastActionLabel) + '"'
        : '';
    // L1 = title + badge + toggle(右，与 tool-card/think 一致)；L2 = meta + 更新点
    header.innerHTML =
        '<div class="task-group-row task-group-row-main">'
        + '<span class="task-group-title" title="' + escapeHtmlAttr(titleText) + '">' + escapeHtml(titleText) + '</span>'
        + agentHtml
        + '<i class="layui-icon layui-icon-right task-group-toggle"></i>'
        + '</div>'
        + '<div class="task-group-row task-group-row-sub">'
        + '<span class="task-group-meta"' + metaTitleAttr + (metaText ? '' : ' style="display:none"') + '>' + escapeHtml(metaText) + '</span>'
        + '<span class="task-group-update-indicator" aria-hidden="true"></span>'
        + '</div>';
    function toggle() {
        var expanded = !$(group).hasClass('expanded');
        segment.userToggled = true;
        $(group).toggleClass('expanded', expanded);
        header.setAttribute('aria-expanded', String(expanded));
        if (expanded) $(group).removeClass('has-new-output');
        header.setAttribute('aria-label', buildTaskGroupAriaLabel(segment, expanded));
    }
    $(header).on('click', function(e) { e.stopPropagation(); toggle(); }).on('keydown', function(e) {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); }
    });
    var body = $('<div>').addClass('task-group-body')[0];
    body.id = bodyId;
    $(group).append(header).append(body);
    return { groupEl: group, bodyEl: body };
}

/* 为 taskId 保持唯一的 task-group；主代理输出仍按连续流片段追加，避免任务组重复创建。 */
function ensureStreamSegment(sess, taskId, taskDescription, agentName) {
    ensureAssistantBubble(sess);
    if (taskId) {
        var taskSegment = sess.taskSegments[taskId];
        if (taskSegment) {
            // 标题首次确定后不再变更（后续 description 仅补内存字段，不刷新 DOM）
            if (!taskSegment.taskDescription && taskDescription) taskSegment.taskDescription = taskDescription;
            if (!taskSegment.agentName && agentName) taskSegment.agentName = agentName;
            sess.currentStreamSegment = taskSegment;
            return taskSegment;
        }
        var now = Date.now();
        taskSegment = {
            id: 'task-' + (++sess.streamSegmentSeq),
            laneKey: 'task:' + taskId,
            taskId: taskId,
            taskDescription: taskDescription || null,
            agentName: agentName || null,
            bodyEl: null,
            groupEl: null,
            reasonEntries: {},
            status: 'running',
            userToggled: false,
            createdAt: now,
            updatedAt: now,
            finishedAt: null,
            toolCount: 0,
            reasonCount: 0,
            errorCount: 0,
            lastToolName: null,
            lastActionLabel: null,
            hasPendingTools: 0,
            _metaRafId: null
        };
        var task = createTaskGroupElement(sess, taskSegment);
        taskSegment.groupEl = task.groupEl;
        taskSegment.bodyEl = task.bodyEl;
        sess.taskGroups[taskId] = task.groupEl;
        sess.taskSegments[taskId] = taskSegment;
        insertBeforeActions(sess, task.groupEl);
        sess.streamSegments.push(taskSegment);
        sess.currentStreamSegment = taskSegment;
        return taskSegment;
    }

    var current = sess.currentStreamSegment;
    if (current && !current.taskId) return current;
    var segment = { id: 'main-' + (++sess.streamSegmentSeq), laneKey: 'main', taskId: null,
        taskDescription: null, agentName: null, bodyEl: null, groupEl: null, reasonEntries: {} };
    var main = $('<div>').addClass('main-stream-segment')[0];
    main.setAttribute('data-stream-segment-id', segment.id);
    if (sess.currentRunId) main.setAttribute('data-run-id', sess.currentRunId);
    segment.groupEl = main;
    segment.bodyEl = main;
    insertBeforeActions(sess, main);
    sess.streamSegments.push(segment);
    sess.currentStreamSegment = segment;
    return segment;
}

function ensureReasonGroup(sess, segment, reasonId) {
    if (!reasonId || !segment) return null;
    var key = streamReasonKey(segment, reasonId);
    if (segment.reasonEntries[reasonId]) return segment.reasonEntries[reasonId];
    var group = $('<div>').addClass('reason-group')[0];
    group.setAttribute('data-reason-id', reasonId);
    group.setAttribute('data-reason-segment-key', key);
    if (sess.currentRunId) group.setAttribute('data-run-id', sess.currentRunId);
    $(segment.bodyEl).append(group);
    var entry = { groupEl: group, thinkingBlockEl: null, thinkingBodyMdEl: null, thinkingBodyWrapEl: null,
        thinkingBuffer: '', reasonRafId: null, groupContentEl: null, groupBuffer: '', groupRafId: null,
        textRuns: [], activeTextRun: null, activeKind: null };
    segment.reasonEntries[reasonId] = entry;
    sess.reasonGroups[key] = entry;
    return entry;
}

function ensureThinkingBlockInGroup(sess, group) {
    if (group.thinkingBlockEl) return group.thinkingBlockEl;
    // 仅 task-group 本身固定收起；其内部和外部的思考块都遵循“工具调用显示简化”配置。
    var initiallyExpanded = window.cliPrintSimplified === false;
    var block = $('<div>').addClass('reason-group-think streaming')[0];
    if (initiallyExpanded) $(block).addClass('expanded');
    block.innerHTML = '<div class="reason-group-think-header" aria-expanded="' + initiallyExpanded + '"><span class="reason-group-think-label">思考</span>'
        + '<span class="thinking-timer-wrap" style="margin-left:4px"><span class="thinking-current-timer">0s</span></span>'
        + '<i class="layui-icon layui-icon-right reason-group-think-toggle"></i></div>'
        + '<div class="reason-group-think-body"><div class="md-content"></div></div>';
    $(group.groupEl).append(block);
    $(block).find('.reason-group-think-header').on('click', function() {
        var expanded = !$(block).hasClass('expanded');
        $(block).toggleClass('expanded', expanded);
        this.setAttribute('aria-expanded', String(expanded));
    });
    group.thinkingBlockEl = block;
    group.thinkingBodyMdEl = $(block).find('.reason-group-think-body .md-content')[0];
    group.thinkingBodyWrapEl = $(block).find('.reason-group-think-body')[0];
    group.thinkingStartTime = Date.now();
    return block;
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
    // 插入到 msg-content 容器末尾（内容元素与 msg-time/msg-actions 物理隔离）
    var bubble = sess.currentBubbleEl ? $(sess.currentBubbleEl).closest('.msg-bubble')[0] : null;
    if (bubble) {
        var content = $(bubble).children('.msg-content')[0];
        if (content) { $(content).append(el); return; }
    }
    $(sess.currentBubbleEl.parentNode).find('.msg-actions').first().before(el);
}

function finishThinkingBlock(sess, reasonId) {
    // 如果指定了 reasonId，只结束该 reasonId 对应的思考块
    if (reasonId && sess.reasonGroups[reasonId]) {
        var group = sess.reasonGroups[reasonId];
        if (!group.thinkingBlockEl) {
            // 思考块已被处理（如双重回调），直接返回
            sess.thinkingBuffer = '';
            return;
        }
        // 保存计时起始时间，防止 stopThinkingTimer 清空后引用丢失
        var blockStartTime = sess.thinkingBlockStartTime;
        stopThinkingTimer(sess, 'thinkingBlockTimerId', 'thinkingBlockStartTime');
        if (group.reasonRafId) {
            cancelAnimationFrame(group.reasonRafId);
            group.reasonRafId = null;
            if (group.thinkingBodyMdEl) {
                group.thinkingBodyMdEl.innerHTML = renderMd(group.thinkingBuffer || '');
            }
        }
        if (group.thinkingBodyMdEl && typeof processMermaidBlocks === 'function') processMermaidBlocks(group.thinkingBodyMdEl);
        // 检查思考块内容是否超出高度
        if (group.thinkingBodyWrapEl) { checkOverflow(group.thinkingBodyWrapEl, 300); }
        $(group.thinkingBlockEl).removeClass('streaming');
        if (window.cliPrintSimplified !== false) {
            $(group.thinkingBlockEl).removeClass('expanded');
        }
        var elapsed = '';
        if (blockStartTime) {
            elapsed = ' (' + Math.floor((Date.now() - blockStartTime) / 1000) + 's)';
        }
        var label = $(group.thinkingBlockEl).find('.reason-group-think-label')[0];
        if (label) $(label).text('思考' + elapsed);
        $(group.thinkingBlockEl).find('.reason-group-think-dots').remove();
        $(group.thinkingBlockEl).find('.thinking-timer-wrap').remove();

        // ★ 清空组内引用 + 顶层引用，防止 finishStream 再次包裹
        group.thinkingBlockEl = null;
        group.thinkingBodyMdEl = null;
        group.thinkingBodyWrapEl = null;
        group.groupContentEl = null;
        group.groupBuffer = '';
        group.thinkingBuffer = '';
        sess.thinkingBlockEl = null;
        sess.thinkingBodyMdEl = null;
        sess.thinkingBodyWrapEl = null;
        sess.thinkingBuffer = '';
        return;
    }

    // 旧式逻辑（无 reasonId 时）：结束当前 thinkingBlockEl 并包裹分组
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
        // 检查思考块内容是否超出高度
        if (sess.thinkingBodyWrapEl) { checkOverflow(sess.thinkingBodyWrapEl, 300); }
        $(sess.thinkingBlockEl).removeClass('streaming');
        if (window.cliPrintSimplified !== false) {
            $(sess.thinkingBlockEl).removeClass('expanded');
        }
        var elapsed = '';
        if (sess.thinkingBlockStartTime) {
            elapsed = ' (' + Math.floor((Date.now() - sess.thinkingBlockStartTime) / 1000) + 's)';
        }
        var label = $(sess.thinkingBlockEl).find('.reason-group-think-label')[0];
        if (label) $(label).text('思考' + elapsed);
        $(sess.thinkingBlockEl).find('.reason-group-think-dots').remove();
        $(sess.thinkingBlockEl).find('.thinking-timer-wrap').remove();

        // reason-group 已在 ensureThinkingBlock 中预创建，无需再做 DOM 包裹
        sess.thinkingGroupEl = sess.thinkingBlockEl.parentNode;

        sess.thinkingBlockEl = null;
        sess.thinkingBodyMdEl = null;
        sess.thinkingBodyWrapEl = null;
        sess.thinkingBuffer = '';
    }
}

/**
 * 收起态 task-group 的新输出提示；同时刷新 meta 摘要。
 * 展开状态始终尊重用户手动切换（userToggled），不在此处强制展开。
 */
function markTaskGroupUpdated(sess, segment) {
    if (!segment || !segment.groupEl || !segment.taskId) return;
    segment.updatedAt = Date.now();
    if (segment.status !== 'error' && segment.status !== 'done') {
        setTaskGroupStatus(segment, 'running');
    } else {
        scheduleTaskGroupMetaUpdate(segment);
    }
    if ($(segment.groupEl).hasClass('expanded')) return;
    $(segment.groupEl).addClass('has-new-output');
    var header = $(segment.groupEl).find('.task-group-header')[0];
    if (header) header.setAttribute('aria-label', buildTaskGroupAriaLabel(segment, false));
}

function clearThinkTags(text) {
    return text.replace(/<\s*\/?think\s*>/gi, '');
}

function appendReasonChunk(sess, segment, text, reasonId, agentName) {
    var clean = clearThinkTags(text || '');
    if (!clean) return;
    var group = ensureReasonGroup(sess, segment, reasonId);
    if (!group) return;
    group.activeKind = 'reason';
    var block = ensureThinkingBlockInGroup(sess, group);
    if (agentName) {
        $(block).addClass('is-subagent');
        var label = $(block).find('.reason-group-think-label')[0];
        if (label && !$(label).next('.agent-badge').length) $(label).after('<span class="agent-badge">' + escapeHtml(agentName) + '</span>');
    }
    // 每个 reason-group 首次进入思考时计一次，避免同一 reason 流式重复累加
    if (segment && segment.taskId && !group._taskReasonCounted) {
        group._taskReasonCounted = true;
        recordTaskGroupReason(segment);
    }
    group.thinkingBuffer += clean;
    if (!group.reasonRafId) group.reasonRafId = requestAnimationFrame(function() {
        group.reasonRafId = null;
        if (group.thinkingBodyMdEl) group.thinkingBodyMdEl.innerHTML = renderMd(group.thinkingBuffer);
        if (sess.sessionId === activeSessionId) scrollToBottom();
    });
}

function finishPendingTool(sess) {
    // 兼容旧单槽：标记并清除
    if (sess.pendingToolCard) {
        var icon = $(sess.pendingToolCard).find('.tool-status-icon')[0];
        if (icon) { icon.className = 'tool-status-icon done'; icon.innerHTML = '<i class="layui-icon layui-icon-ok" style="font-size:12px"></i>'; }
        sess.pendingToolCard = null;
    }
    // 多槽 map：标记所有未完成的 pending 卡片为 done
    for (var _key in sess.pendingToolCards) {
        var pending = sess.pendingToolCards[_key];
        if (pending && pending.card) {
            var icon = $(pending.card).find('.tool-status-icon')[0];
            if (icon) { icon.className = 'tool-status-icon done'; icon.innerHTML = '<i class="layui-icon layui-icon-ok" style="font-size:12px"></i>'; }
        }
        delete sess.pendingToolCards[_key];
    }
    sess.pendingToolCards = {};
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
            bodyEl.innerHTML = '<pre style="margin:0;padding:10px;overflow:auto;border-radius:0;line-height:1.5"><code class="hljs">' + highlighted.value + '</code></pre>';
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
        } catch(e) {
            console.warn('[toolRenderer] renderer "' + toolName + '" threw:', e);
        }
    }
    return false;
}

/* 检查容器内容是否超出高度，若超出则添加溢出指示器 */
function checkOverflow(el, maxHeight) {
    if (!el) return;
    var hasOverflow = el.scrollHeight > maxHeight;
    $(el).toggleClass('has-overflow', hasOverflow);
    if (hasOverflow && !el._overflowBtn) {
        el._overflowBtn = true;
        $(el).on('click', function(e) {
            if (e.target === el || $(e.target).parents().is(el)) {
                $(el).toggleClass('expand-all');
                $(el).removeClass('has-overflow');
            }
        });
    }
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

/* 为工具调用注册 pending 卡片：有 callId 时必须以 callId 作为唯一键；
 * 旧流缺少 callId 时才使用带序号的兼容键。 */
function registerPendingToolCard(sess, card, callId, reasonId) {
    if (!sess.pendingToolCards) sess.pendingToolCards = {};
    var key;
    if (callId) {
        key = callId;
    } else {
        if (!sess._toolCallSeq) sess._toolCallSeq = 0;
        key = '__legacy__:' + (reasonId || '__default') + ':' + (++sess._toolCallSeq);
    }
    sess.pendingToolCards[key] = { card: card, started: true };
    return key;
}

/* 按 callId 精确查找 pending 卡片。缺少 callId 的旧流只能按同一 reasonId 的到达顺序兜底。 */
function findPendingToolCard(sess, callId, reasonId) {
    var cards = sess.pendingToolCards || {};
    var key = null;
    var pending = null;
    if (callId) {
        key = callId;
        pending = cards[key];
    } else {
        var legacyPrefix = '__legacy__:' + (reasonId || '__default') + ':';
        for (var candidateKey in cards) {
            if (candidateKey.indexOf(legacyPrefix) === 0) {
                key = candidateKey;
                pending = cards[candidateKey];
                break;
            }
        }
    }
    return { key: key, pending: pending };
}

/* action_start：工具调用前（来源引擎 ActionChunk）提前渲染 loading 卡片骨架。
   存为 pendingToolCards，待 action_end（ObservationChunk 结果）到达时由
   appendActionEndChunk 复用此卡片填充结果体并转完成态。 */
function appendActionStartChunk(sess, segment, toolName, args, toolTitle, reasonId, agentName, callId) {
    var group = ensureReasonGroup(sess, segment, reasonId);
    if (group && group.thinkingBlockEl) finishThinkingBlock(sess, streamReasonKey(segment, reasonId));
    var argsStr = formatToolArgsStr(args);
    var card = $('<div>').addClass('tool-card')[0];
    if (window.cliPrintSimplified === false) $(card).addClass('expanded');
    if (sess.currentRunId) card.setAttribute('data-run-id', sess.currentRunId);
    card.innerHTML = '<div class="tool-card-header"><span class="tool-status-icon loading"></span><span class="tool-name">'
        + escapeHtml(toolTitle || toolName || 'tool') + '</span>' + (argsStr ? '<span class="tool-args">' + escapeHtml(argsStr) + '</span>' : '')
        + '<i class="layui-icon layui-icon-right tool-toggle"></i></div><div class="tool-card-body"></div>';
    if (agentName) { $(card).addClass('is-subagent'); $(card).find('.tool-name').after('<span class="agent-badge">' + escapeHtml(agentName) + '</span>'); }
    $(card).find('.tool-card-header').on('click', function() { $(card).toggleClass('expanded'); });
    if (group) { group.activeKind = 'tool'; $(group.groupEl).append(card); } else $(segment.bodyEl).append(card);
    if (callId) card.setAttribute('data-call-id', callId);
    registerPendingToolCard(sess, card, callId, streamReasonKey(segment, reasonId));
    if (segment && segment.taskId) recordTaskGroupToolStart(segment, toolName, toolTitle, args);
    if (sess.sessionId === activeSessionId) scrollToBottom();
}

function resolveTaskSegmentFromCard(sess, card) {
    if (!sess || !card || !sess.taskSegments) return null;
    var group = $(card).closest('.task-group')[0];
    if (!group) return null;
    var taskId = group.getAttribute('data-task-id');
    return (taskId && sess.taskSegments[taskId]) || null;
}

function appendActionEndChunk(sess, segment, toolName, text, args, toolTitle, reasonId, agentName, callId) {
    var pendingMatch = findPendingToolCard(sess, callId, null);
    var card = pendingMatch.pending && pendingMatch.pending.started ? pendingMatch.pending.card : null;
    if (card) delete sess.pendingToolCards[pendingMatch.key];
    if (!card) {
        if (!segment) segment = ensureStreamSegment(sess, null, null, null);
        var group = ensureReasonGroup(sess, segment, reasonId);
        card = $('<div>').addClass('tool-card')[0];
        if (window.cliPrintSimplified === false) $(card).addClass('expanded');
        card.innerHTML = '<div class="tool-card-header"><span class="tool-status-icon done"><i class="layui-icon layui-icon-ok" style="font-size:12px"></i></span><span class="tool-name">'
            + escapeHtml(toolTitle || toolName || 'tool') + '</span><i class="layui-icon layui-icon-right tool-toggle"></i></div><div class="tool-card-body"></div>';
        $(card).find('.tool-card-header').on('click', function() { $(card).toggleClass('expanded'); });
        if (group) { group.activeKind = 'tool'; $(group.groupEl).append(card); } else $(segment.bodyEl).append(card);
        // 无 action_start 的终态卡也计入 task 摘要
        if (segment && segment.taskId) recordTaskGroupToolStart(segment, toolName, toolTitle, args);
    }
    var body = $(card).find('.tool-card-body')[0];
    if (body) { body.innerHTML = ''; if (!renderToolBody(body, toolName, text, args)) body.textContent = text || ''; checkOverflow(body, 200); }
    var icon = $(card).find('.tool-status-icon')[0];
    if (icon) { icon.className = 'tool-status-icon done'; icon.innerHTML = '<i class="layui-icon layui-icon-ok" style="font-size:12px"></i>'; }
    if (callId) card.setAttribute('data-call-id', callId);
    // 优先用 chunk 上的 task segment；若 action_end 缺 taskId，则从已挂载的 tool-card 反查归属
    var taskSegment = (segment && segment.taskId) ? segment : resolveTaskSegmentFromCard(sess, card);
    if (taskSegment) {
        recordTaskGroupToolEnd(taskSegment);
        // onWebChunk 仅在 segment.taskId 时 mark；此处覆盖 action_end 无 taskId 的反查场景
        if (!segment || !segment.taskId) markTaskGroupUpdated(sess, taskSegment);
    }
    if (sess.sessionId === activeSessionId) scrollToBottom();
}

function appendContentChunk(sess, segment, text, append, reasonId) {
    var clean = clearThinkTags(text || '');
    if (!clean) return;
    var group = ensureReasonGroup(sess, segment, reasonId);
    if (!group) return;
    // 仅连续 text chunk 复用同一节点；工具/思考出现后必建新 text run，保持真实时序。
    var run = group.activeTextRun;
    if (group.activeKind !== 'text' || !run) {
        run = { el: $('<div>').addClass('md-content reason-group-text')[0], buffer: '', rafId: null };
        group.textRuns.push(run);
        group.activeTextRun = run;
        group.groupContentEl = run.el;
        $(group.groupEl).append(run.el);
    }
    group.activeKind = 'text';
    run.buffer = append ? run.buffer + clean : clean;
    group.groupBuffer = run.buffer;
    if (!run.rafId) run.rafId = requestAnimationFrame(function() {
        run.rafId = null;
        if (run.el) run.el.innerHTML = renderMd(run.buffer);
        if (sess.sessionId === activeSessionId) scrollToBottom();
    });
}

function appendErrorChunkToSegment(sess, segment, text) {
    if (!segment || !segment.bodyEl) {
        appendErrorChunk(sess, text);
        return;
    }
    var errEl = $('<div>').addClass('chunk-error').text(text)[0];
    $(segment.bodyEl).append(errEl);
    segment.errorCount = (segment.errorCount || 0) + 1;
    segment.updatedAt = Date.now();
    setTaskGroupStatus(segment, 'error');
    // 强提示但不自动展开
    if (!$(segment.groupEl).hasClass('expanded')) {
        $(segment.groupEl).addClass('has-new-output');
    }
    var header = $(segment.groupEl).find('.task-group-header')[0];
    if (header) header.setAttribute('aria-label', buildTaskGroupAriaLabel(segment, $(segment.groupEl).hasClass('expanded')));
    if (sess.sessionId === activeSessionId) scrollToBottom();
}

function appendErrorChunk(sess, text, taskId, taskDescription, agentName) {
    // 仅显式 taskId 才归入 task-group；无 taskId 一律挂气泡根级，绝不弱归属
    if (taskId) {
        var segment = ensureStreamSegment(sess, taskId, taskDescription, agentName);
        if (segment && segment.taskId) {
            appendErrorChunkToSegment(sess, segment, text);
            return;
        }
    }
    ensureAssistantBubble(sess);
    var errEl = $('<div>').addClass('chunk-error').text(text)[0];
    insertBeforeActions(sess, errEl);
    if (sess.sessionId === activeSessionId) scrollToBottom();
}

/* ===== Trace Badge ===== */
function appendTraceBadge(sess, chunk) {
    ensureAssistantBubble(sess);
    // 后端携带的最终答案为权威复制源，写到实际承载正文的 .md-content。
    // 工具结束后若尚未收到正文，nextContentBlock 表示当前节点仍是工具前的内容，
    // 此时不能把最终答案错误挂到旧节点，更不能为 trace 预建空节点。
    if (chunk.finalAnswer != null && sess.currentBubbleEl && !sess.nextContentBlock) {
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
    sess.currentBubbleEl = mdEl;
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
    var bubble = $(sess.currentBubbleEl).closest('.msg-bubble')[0];
    if (bubble) {
        var content = $(bubble).children('.msg-content')[0];
        if (content) { $(content).append(el); return el; }
    }
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
    var card = $('<div>').addClass('tool-card hitl-pending')[0];
    if (window.cliPrintSimplified === false) $(card).addClass('expanded');
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

/* ===== Server Record Count =====
 * 计算从 startRow 到末尾、在 ndjson 中有服务端记录的消息数量。
 * 命令消息（以 / 开头）在 ndjson 中无记录，不计入，避免后端多删。 */
function calcServerCount(container, startRow) {
    var rows = $(container).find('.msg-row');
    var idx = rows.index(startRow);
    if (idx < 0) return 0;
    var count = 0;
    for (var i = idx; i < rows.length; i++) {
        var r = rows[i];
        // 用户消息中，以 / 开头的命令在 ndjson 中无记录，跳过
        if ($(r).hasClass('user')) {
            var textEl = $(r).find('.user-msg-text')[0];
            if (textEl) {
                var raw = textEl.getAttribute('data-md-raw') || textEl.innerText;
                if (raw.trim().startsWith('/') && /^\/[a-zA-Z][a-zA-Z0-9_-]*(\s.*)?$/.test(raw.trim())) continue;
            }
        }
        // 系统通知也可能无记录，但删除按钮不存在于系统通知上，无需处理
        count++;
    }
    return count;
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

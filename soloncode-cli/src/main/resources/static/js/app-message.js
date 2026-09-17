/* ===== app-message.js ===== */
/* 消息渲染：消息气泡 + 思考动画 + 命令输出 + HITL + 回退 */
/* 依赖：app-base.js */

/* 复制图标（icon-only，用户与 AI 消息共用） */
var COPY_SVG = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>';
var OK_SVG = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>';
/* 重新运行（循环箭头）与继续运行（快进）图标 */
var RERUN_SVG = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"></polyline><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"></path></svg>';
var CONTINUE_SVG = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="5 4 15 12 5 20 5 4"></polygon><line x1="19" y1="5" x2="19" y2="19"></line></svg>';
/* 删除图标：全局统一常量（app-base.js），此处仅保留旧名兼容 */
var DELETE_SVG = SVG_TRASH;

/* 更新用户消息的「重做/继续」按钮：重做仅最后一条用户消息显示；
   继续运行仅在最后一条用户消息同时是整个消息列表末尾（其后无 AI 回复）、
   且本会话当前不在流式运行中时显示（发出后到首段输出前不该出现「继续运行」） */
function updateUserRerunButtons(container) {
    // 插话行（.steer）不参与“最后一条用户消息”的认定：它自身的重做/继续/删除已由 CSS 隐藏，
    // 若计入则会把真正的末条用户消息挤成“非末条”，导致其重做按钮消失。
    var userRows = $(container).find('.msg-row.user:not(.steer)');
    // 回放行（data-replay）是无 ndjson 记录的过程展示，不能算作「最后一行」：
    // 否则中断的任务回放后，末条用户消息上的「继续运行」会被错误隐藏
    var allRows = $(container).find('.msg-row:not([data-replay])');
    var lastRowEl = allRows.length ? allRows[allRows.length - 1] : null;
    var sid = lastRowEl ? lastRowEl.getAttribute('data-session-id') : null;
    var lastSess = (sid && window.sessionMap) ? window.sessionMap[sid] : null;
    var busy = !!(lastSess && lastSess.isStreaming);
    userRows.each(function(i) {
        var isLastUserRow = (i === userRows.length - 1);
        var btn = $(this).find('.rerun-btn')[0];
        if (btn) {
            btn.style.display = isLastUserRow ? '' : 'none';
        }
        var cBtn = $(this).find('.user-continue-btn')[0];
        if (cBtn) {
            cBtn.style.display = (isLastUserRow && lastRowEl === this && !busy) ? '' : 'none';
        }
    });
}

/* ===== Message Rendering (Session-Aware) ===== */
/* 来源徽标一律原样展示服务端下发的 sourceLabel（WebEvent.toSourceLabel 直通 source，仅空值归一为 "Web"），
 * 前端不做任何映射或 i18n 转换：文案归服务端一处决定，换语言不影响渠道名。
 * STEER_SOURCE 仅用于识别（判断该行是不是插话），比较前统一转小写，
 * 从而一次覆盖「历史走 sourceLabel」与「实时兜底走 source」两条路。 */
var STEER_SOURCE = 'steer';
/* 纯前端产生的插话（appendSteerNote / 无 AI 气泡时的回落行）没有服务端标签，用此兜底 */
var STEER_SOURCE_LABEL = 'Steer';
/* Web 端直接输入不展示来源徽标（对应后端 WebEvent.SOURCE_LABEL_WEB） */
var WEB_SOURCE = 'web';

/* isSteer 为真表示这是运行中插话（steer）：仅用于打 data-steer 标记（隐藏重发/继续按钮）。
 * 插话标识本身走 sourceLabel 通道渲染。
 * 注意：新产生的插话已不再走本函数（改为 appendSteerNote 渲染进 AI 流式气泡内），
 * 此分支只为两个回落场景保留：
 *   a) 存量会话——历史改动前已写入 ndjson 的 source=steer 行，回放时仍按独立行展示，
 *      且仍需计入 calcServerCount（它有对应的服务端记录），否则 rewind 会少删导致尾部残留；
 *   b) 实时路径下 appendSteerNote 返回 false（无 AI 气泡可挂）时的上屏兼底。 */
function appendUserMessage(sess, text, imageDataUrls, fileAttachments, createdAt, sourceLabel, agentName, isSteer) {
    // 来源键：历史加载传后端 sourceLabel（"WeChat"/"steer"…），实时推送传 sourceLabel 或原始 source。
    // 仅用于判定（是否插话、是否 Web 自家输入），展示始终用未加工的 sourceLabel
    var sourceKey = sourceLabel ? String(sourceLabel).toLowerCase() : '';
    // 插话归一：两路（sourceKey 命中 / 实时的 isSteer）在此汇聚为同一种 data-steer 行为与样式
    if (sourceKey === STEER_SOURCE) isSteer = true;
    // 服务端给了标签就照原样显示；仅前端自造的插话行（无标签可用）才回落到常量
    if (isSteer && !sourceLabel) sourceLabel = STEER_SOURCE_LABEL;
    var row = $('<div>').addClass('msg-row user' + (isSteer ? ' steer' : ''))[0];
    row.setAttribute('data-user-msg-idx', sess.userMsgCounter++);
    row.setAttribute('data-session-id', sess.sessionId);
    if (isSteer) row.setAttribute('data-steer', '1');
    row.innerHTML = '<div class="user-msg-col"><div class="msg-bubble"></div><div class="msg-actions"><button class="user-copy-btn" data-i18n-title="common.copy">' + COPY_SVG + '</button><button class="user-copy-btn rerun-btn" data-i18n-title="msg.redo" style="display:none">' + RERUN_SVG + '</button><button class="user-copy-btn user-continue-btn" data-i18n-title="msg.continue" style="display:none">' + CONTINUE_SVG + '</button><button class="user-del-btn" data-i18n-title="msg.deleteHereAndAfter">' + DELETE_SVG + '</button></div></div>';
    if (window.I18n) window.I18n.apply(row);
    var bubble = $(row).find('.msg-bubble')[0];

    // 来源标签（仅非空且非 "Web" 时显示；会在时间戳左侧追加）

    // 子代理标记：这条消息实际交给了哪个子代理（主 Agent 不显示）
    // 独立成行放在气泡顶部，不写入 data-md-raw，复制/重发仍是用户原文
    if (agentName) {
        var agentTag = $('<div>').addClass('user-agent-tag')[0];
        agentTag.innerHTML = '<span class="user-agent-tag-at">@</span>' + escapeHtml(agentName);
        agentTag.setAttribute('title', (window.I18n ? I18n.t('history.subagentLabel') : '') + agentName);
        $(bubble).append(agentTag);
    }

    // Multiple images（解码完成后再补滚，避免占位高度 0 导致贴底失效）
    if (imageDataUrls && imageDataUrls.length > 0) {
        var imgWrap = $('<div>').addClass('user-attach-imgs')[0];
        for (var i = 0; i < imageDataUrls.length; i++) {
            var img = $('<img>').attr('src', imageDataUrls[i].dataUrl || imageDataUrls[i])
                .attr('style', 'max-height:120px;max-width:200px;border-radius:8px;object-fit:cover;')[0];
            (function(imgEl) {
                function onImgLayout() {
                    if (typeof scheduleScrollToBottom === 'function') scheduleScrollToBottom();
                    else if (typeof scrollToBottom === 'function') scrollToBottom(false);
                }
                if (imgEl.complete) {
                    // 缓存图可能已 complete，下一帧再补一次高度
                    requestAnimationFrame(onImgLayout);
                } else {
                    $(imgEl).one('load error', onImgLayout);
                }
            })(img);
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

    var rerunUserBtn = $(row).find('.rerun-btn')[0];
    $(rerunUserBtn).on('click', function() {
        if (sess.isStreaming) return;
        var txtEl = $(bubble).find('.user-msg-text')[0];
        var md = txtEl ? (txtEl.getAttribute('data-md-raw') || txtEl.innerText) : '';
        if (!md || !md.trim()) return;
        // 先保存文本，再删除消息，然后重新发送
        var text = md;
        var rows = $(sess.container).find('.msg-row');
        var idx = rows.index(row);
        if (idx < 0) return;
        var serverCount = calcServerCount(sess.container, row);
        /* 必须等回退回调再发新消息：旧实现不等，新任务一旦先启动，回退会被服务端
         * busy 守卫拒成 409，而那个 post 既无回调也无 fail 处理 —— 静默失败，旧消息留在
         * ndjson 里形成重复。AI 行的删除一直是等回调的，两边应当一致。 */
        $.post('/web/chat/rewind', buildRewindPayload(sess, row), function(resp) {
            if (!resp || resp.code !== 200) {
                handleRewindFailure(sess, resp);
                return;
            }
            handleRewind(sess, rows.length - idx);
            // 将用户消息填入输入框并发送
            if (inChatMode) {
                chatInput.value = text;
            } else {
                newChatInput.value = text;
            }
            if (typeof sendMessage === 'function') {
                sendMessage();
            }
        }).fail(function() {
            layer.msg(I18n.t('msg.deleteFailed') + I18n.t('toast.networkError'), { icon: 2, time: 3000, offset: '120px' });
        });
    });

    // 继续运行：仅当用户消息是列表末尾（无后续 AI 回复）时可见，
    // 复用后端 /continue 命令，不删除任何消息，新回复自然追加。
    var continueUserBtn = $(row).find('.user-continue-btn')[0];
    $(continueUserBtn).on('click', function() {
        if (sess.isStreaming) return;
        if (typeof sendCommandSilent === 'function') {
            sendCommandSilent('/continue');
        }
    });

    var delBtn = $(row).find('.user-del-btn')[0];
    $(delBtn).on('click', function() {
        layer.confirm(I18n.t('msg.confirmDeleteMsg'), {
            title: I18n.t('msg.confirmDeleteTitle'),
            btn: [I18n.t('common.delete'), I18n.t('common.cancel')],
            icon: 3,
            offset: '120px'
        }, function(index) {
            var rows = $(sess.container).find('.msg-row');
            var idx = rows.index(row);
            if (idx < 0) { layer.close(index); return; }
            layer.close(index);
            // 删除起点由 runId 锚点定位，服务端在真实消息列表上算条数
            // 后端删除成功后，前端才删除；失败则保留界面并提示
            $.post('/web/chat/rewind', buildRewindPayload(sess, row), function(resp) {
                if (resp && resp.code === 200) {
                    // 前端删所有可视行（含命令消息的无记录行），保持界面干净
                    handleRewind(sess, rows.length - idx);
                    updateUserRerunButtons(sess.container);
                } else {
                    handleRewindFailure(sess, resp);
                }
            }).fail(function() {
                layer.msg(I18n.t('msg.deleteFailed') + I18n.t('toast.networkError'), { icon: 2, time: 3000, offset: '120px' });
            });
        });
    });

    // 时间戳（实时发送不传 createdAt 时兜底为当前时间，与历史加载行为一致）
    var msgTime = createdAt || Date.now();
    var timeEl = $('<div>').addClass('msg-time')[0];
    // 来源标签放在时间左侧，同样浅色；Web 端自家输入不标注（大小写不敏感，兼容原始 source 直传）
    if (sourceLabel && sourceKey !== WEB_SOURCE) {
        var srcSpan = $('<span>').addClass('msg-source-label').text(sourceLabel)[0];
        $(timeEl).append(srcSpan);
    }
    timeEl.appendChild(document.createTextNode(formatMsgTime(msgTime)));
    $(bubble).append(timeEl);

    addImageLightbox(bubble);
    $(sess.container).append(row);
    if (typeof observeMessagesHeight === 'function') observeMessagesHeight(row);
    // 容器不在 DOM 树中（如 loadMessages 的临时容器阶段）时跳过滚动，避免无效回流
    if (sess.sessionId === activeSessionId && document.contains(sess.container)) {
        scrollToBottom(true);
        // 再补一帧：欢迎页首条 / 宽用户气泡布局稳定后贴底
        requestAnimationFrame(function() {
            if (sess.sessionId === activeSessionId && !userScrolledUp) scrollToBottom(true);
        });
    }
    updateUserRerunButtons(sess.container);
    if (typeof scheduleMsgNavRebuild === 'function') scheduleMsgNavRebuild();
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
            + '<button class="user-copy-btn copy-btn" data-i18n-title="common.copy">' + COPY_SVG + '</button>'
            + '<button class="user-copy-btn rerun-btn" data-i18n-title="msg.rerun">' + RERUN_SVG + '</button>'
            + '<button class="user-copy-btn continue-btn" data-i18n-title="msg.continue">' + CONTINUE_SVG + '</button>'
            + '<button class="user-copy-btn del-btn" data-i18n-title="msg.deleteHereAndAfter">' + DELETE_SVG + '</button>'
            + '</div></div>';
        if (window.I18n) window.I18n.apply(row);
        $(sess.container).append(row);
        // AI 回复出现后，隐藏末尾用户消息上的「继续运行」按钮
        updateUserRerunButtons(sess.container);
        if (typeof observeMessagesHeight === 'function') observeMessagesHeight(row);
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
                        /* ★ 只回收本轮「AI 侧」的可视元素，user 行必须留在屏上：
                         * 历史加载与 trace 回放会把同一轮的 runId 也补到 user 行上
                         * （app-history.js 补锚点 / mergeReplayRowInto），此时按 runId 无差别删除
                         * 会把用户的提问一起删掉；而后端 /rerun 恰恰是取这条提问重跑（消息仍在会话里），
                         * 且 sendCommandSilent 不渲染用户气泡，界面上就再也没有它了 —— 前端凭空少一条。 */
                        $(sess.container).find('[data-run-id="' + runId + '"]').each(function() {
                            if ($(this).hasClass('msg-row') && $(this).hasClass('user')) {
                                /* 旧 runId 已随本轮消息被后端删除，留着会让后续 rewind 撞上
                                 * ANCHOR_NOT_FOUND；先摘掉，等新一轮 runId 到达时补回（见 app-streaming.js）。 */
                                this.removeAttribute('data-run-id');
                                sess.pendingRunIdRow = this;
                                return;
                            }
                            $(this).remove();
                        });
                    } else {
                        // 兼容旧数据：如果没有 runId，只删除当前行
                        $(row).remove();
                    }
                    // 重置会话状态
                    sess.currentBubbleEl = null;
                    sess.thinkingBlockEl = null;
                    sess.pendingToolCard = null;
                    // 末条 AI 行被删后，末条 user 消息重新变成列表末尾，按钮可见性需重算
                    updateUserRerunButtons(sess.container);
                }
            });
        }
        if (rerunBtn) $(rerunBtn).on('click', function() { triggerCommand('/rerun', true); });
        if (continueBtn) $(continueBtn).on('click', function() { triggerCommand('/continue', false); });
        var delBtn = $(row).find('.del-btn')[0];
        if (delBtn) $(delBtn).on('click', function() {
            layer.confirm(I18n.t('msg.confirmDeleteMsg'), {
                title: I18n.t('msg.confirmDeleteTitle'),
                btn: [I18n.t('common.delete'), I18n.t('common.cancel')],
                icon: 3,
                offset: '120px'
            }, function(index) {
                var rows = $(sess.container).find('.msg-row');
                var idx = rows.index(row);
                if (idx < 0) { layer.close(index); return; }
                layer.close(index);
                // 删除起点由 runId 锚点定位，服务端在真实消息列表上算条数
                // 后端删除成功后，前端才删除；失败则保留界面并提示
                $.post('/web/chat/rewind', buildRewindPayload(sess, row), function(resp) {
                    if (resp && resp.code === 200) {
                        // 前端删所有可视行（含命令消息的无记录行），保持界面干净
                        handleRewind(sess, rows.length - idx);
                    } else {
                        handleRewindFailure(sess, resp);
                    }
                }).fail(function() {
                    layer.msg(I18n.t('msg.deleteFailed') + I18n.t('toast.networkError'), { icon: 2, time: 3000, offset: '120px' });
                });
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
    return segment.id + '::' + (reasonId || '__default__');
}

function buildTaskGroupAriaLabel(segment, expanded) {
    var title = segment.taskDescription || segment.agentName || I18n.t('msg.subTask');
    // 双字段时补读 agent，避免仅读 description 丢失「谁在跑」
    if (segment.taskDescription && segment.agentName) {
        title = segment.taskDescription + I18n.t('msg.comma') + segment.agentName;
    }
    var stateLabel = expanded ? I18n.t('msg.expanded') : I18n.t('msg.collapsed');
    var stats = formatTaskGroupStats(segment);
    var action = formatTaskGroupMeta(segment);
    var detailParts = [];
    if (stats) detailParts.push(stats);
    if (action) detailParts.push(action);
    var detail = detailParts.length ? detailParts.join(' \u00b7 ') + I18n.t('msg.comma') : '';
    if (segment.status === 'error') {
        return title + I18n.t('msg.taskFailed', {detail: detail}) + stateLabel + (expanded ? '' : I18n.t('msg.clickToExpand'));
    }
    if (segment.status === 'done') {
        return title + I18n.t('msg.taskDone', {detail: detail}) + stateLabel;
    }
    return title + I18n.t('msg.taskRunning', {detail: detail}) + stateLabel + (expanded ? '' : I18n.t('msg.clickToExpand'));
}

/** 与 tool-card 同系的 22px 状态圆点：running 转圈 / done 绿勾 / error 红叉 */
function updateTaskGroupStatusIcon(segment) {
    if (!segment || !segment.groupEl) return;
    var icon = $(segment.groupEl).find('.task-group-row-main > .tool-status-icon')[0];
    if (!icon) return;
    var status = segment.status || 'running';
    if (status === 'done') {
        icon.className = 'tool-status-icon done';
        icon.innerHTML = '<i class="layui-icon layui-icon-ok" style="font-size:12px"></i>';
    } else if (status === 'error') {
        icon.className = 'tool-status-icon reject';
        icon.innerHTML = '<i class="layui-icon layui-icon-close" style="font-size:12px"></i>';
    } else {
        icon.className = 'tool-status-icon loading';
        icon.innerHTML = '';
    }
}

/** L1 右侧：仅 tool 计数（整轮耗时放 Context 条） */
function formatTaskGroupStats(segment) {
    if (!segment) return '';
    if (segment.toolCount > 0) return segment.toolCount + ' tools';
    return '';
}

/** L2：仅最近 toolName + args（running/done/error 均保留，方便收起回顾） */
function formatTaskGroupMeta(segment) {
    if (!segment) return '';
    return segment.lastActionLabel || '';
}

function updateTaskGroupMeta(segment) {
    if (!segment || !segment.groupEl) return;
    var $g = $(segment.groupEl);

    var statsText = formatTaskGroupStats(segment);
    var statsEl = $g.find('.task-group-stats')[0];
    if (statsEl) {
        $(statsEl).text(statsText);
        statsEl.style.display = statsText ? '' : 'none';
        if (statsText) statsEl.setAttribute('title', statsText);
        else statsEl.removeAttribute('title');
    }

    var actionText = formatTaskGroupMeta(segment);
    var metaEl = $g.find('.task-group-meta')[0];
    if (metaEl) {
        $(metaEl).text(actionText);
        metaEl.style.display = actionText ? '' : 'none';
        // 主文案可被 ellipsis 截断；title 挂完整 L2，方便 hover 看全
        if (actionText) metaEl.setAttribute('title', actionText);
        else metaEl.removeAttribute('title');
    }

    // 无最近动作时隐藏 L2 整行，避免空白缝
    var subRow = $g.find('.task-group-row-sub')[0];
    if (subRow) subRow.style.display = actionText ? '' : 'none';

    var header = $g.find('.task-group-header')[0];
    if (header) {
        header.setAttribute('aria-label', buildTaskGroupAriaLabel(segment, $g.hasClass('expanded')));
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
    updateTaskGroupStatusIcon(segment);
    scheduleTaskGroupMetaUpdate(segment);
}

function recordTaskGroupToolStart(segment, toolName, toolTitle, args) {
    if (!segment || !segment.taskId) return;
    segment.toolCount = (segment.toolCount || 0) + 1;
    segment.hasPendingTools = (segment.hasPendingTools || 0) + 1;
    segment.lastToolName = toolTitle || toolName || null;
    // L2 文案对齐 tool-card：name + formatToolArgsStr；宽度不够由 CSS ellipsis 处理
    var name = toolTitle || toolName || 'tool';
    var argsStr = formatToolArgsStr(args);
    segment.lastActionLabel = argsStr ? (name + ' ' + argsStr) : name;
    segment.updatedAt = Date.now();
    // 已 task_done 的终态不因迟到 action_start 打回 running
    if (segment.status !== 'error' && segment.status !== 'done') setTaskGroupStatus(segment, 'running');
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
    // 已 task_done 的终态不因迟到 reason 打回 running
    if (segment.status !== 'error' && segment.status !== 'done') setTaskGroupStatus(segment, 'running');
    else scheduleTaskGroupMetaUpdate(segment);
}

function finalizeTaskGroups(sess) {
    if (!sess || !sess.taskSegments) return;
    for (var taskId in sess.taskSegments) {
        if (!Object.prototype.hasOwnProperty.call(sess.taskSegments, taskId)) continue;
        var segment = sess.taskSegments[taskId];
        if (!segment || !segment.groupEl) continue;
        // 非 error → done（绿勾）；error 保留红叉与 is-error 左边框
        // 已由 task_done 提前结算的 segment 会走 status 短路，只刷新 meta
        if (segment.status !== 'error') setTaskGroupStatus(segment, 'done');
        else {
            segment.finishedAt = segment.finishedAt || Date.now();
            updateTaskGroupStatusIcon(segment);
            scheduleTaskGroupMetaUpdate(segment);
        }
    }
}

/**
 * 处理后端 task_done WebEvent：子代理任务结束时立即结算对应 task-group。
 * status=error → 红叉（可附带错误文本）；其它 → 绿勾。
 * 不依赖主流 done；主流 finalizeTaskGroups 仍作兜底。
 */
function applyTaskDoneChunk(sess, chunk) {
    if (!sess || !chunk || !chunk.taskId) return;
    var segment = ensureStreamSegment(sess, chunk.taskId, chunk.taskDescription, chunk.agentName);
    if (!segment || !segment.taskId) return;

    var status = (chunk.status === 'error') ? 'error' : 'done';

    if (status === 'error' && chunk.text) {
        // 异常正文写入 task-group，避免只有图标没有原因
        appendErrorChunkToSegment(sess, segment, chunk.text);
        // appendErrorChunkToSegment 已 set error；再兜底一次状态
        setTaskGroupStatus(segment, 'error');
    } else {
        setTaskGroupStatus(segment, status);
    }
}

/** L1 标题簇文案：有 description 时 badge 展示 agentName；仅 agentName 时直接作标题，避免重复。 */
function buildTaskGroupTitleParts(segment) {
    var titleText = segment.taskDescription || segment.agentName || I18n.t('msg.subTask');
    var hasBoth = !!(segment.taskDescription && segment.agentName);
    return {
        titleText: titleText,
        agentHtml: hasBoth ? '<span class="agent-badge">' + escapeHtml(segment.agentName) + '</span>' : '',
        // hover：双字段时补全身份（badge 可能被窄屏裁进 max-width）
        titleAttr: hasBoth
            ? titleText + I18n.t('msg.parenLeft') + segment.agentName + I18n.t('msg.parenRight')
            : titleText
    };
}

/**
 * 描述迟到时重绘 L1 标题：建组时可能只有 agentName（首个事件未带 taskDescription），
 * 若不重绘，后续拿到的描述只能停在内存字段上，界面永远显示代理名。
 */
function updateTaskGroupTitle(segment) {
    if (!segment || !segment.groupEl) return;
    var parts = buildTaskGroupTitleParts(segment);
    var $g = $(segment.groupEl);

    var textEl = $g.find('.task-group-title-text')[0];
    if (textEl) $(textEl).text(parts.titleText);

    var titleEl = $g.find('.task-group-title')[0];
    if (titleEl) {
        titleEl.setAttribute('title', parts.titleAttr);
        var badge = $(titleEl).children('.agent-badge')[0];
        if (parts.agentHtml) {
            if (badge) $(badge).text(segment.agentName);
            else $(titleEl).append(parts.agentHtml);
        } else if (badge) {
            $(badge).remove();
        }
    }

    var header = $g.find('.task-group-header')[0];
    if (header) {
        header.setAttribute('aria-label', buildTaskGroupAriaLabel(segment, $g.hasClass('expanded')));
    }
}

function createTaskGroupElement(sess, segment) {
    var group = $('<div>').addClass('task-group is-running')[0];
    group.setAttribute('data-task-id', segment.taskId);
    group.setAttribute('data-stream-segment-id', segment.id);
    if (sess.currentRunId) group.setAttribute('data-run-id', sess.currentRunId);
    // L1：状态图标(22px) + title(文本+可选 agent-badge 贴字) + stats + toggle(右)；L2：最近 tool 动作。
    var titleParts = buildTaskGroupTitleParts(segment);
    var titleText = titleParts.titleText;
    var agentHtml = titleParts.agentHtml;
    var titleAttr = titleParts.titleAttr;
    var header = $('<div>').addClass('task-group-header')[0];
    // task-group 本级一律默认收起（单/多任务相同），展开由用户手动触发
    var bodyId = 'task-body-' + segment.id;
    header.setAttribute('role', 'button');
    header.setAttribute('tabindex', '0');
    header.setAttribute('aria-expanded', 'false');
    header.setAttribute('aria-controls', bodyId);
    header.setAttribute('aria-label', buildTaskGroupAriaLabel(segment, false));
    var statsText = formatTaskGroupStats(segment);
    var statsTitleAttr = statsText
        ? ' title="' + escapeHtmlAttr(statsText) + '"'
        : '';
    var metaText = formatTaskGroupMeta(segment);
    var metaTitleAttr = metaText
        ? ' title="' + escapeHtmlAttr(metaText) + '"'
        : '';
    // L1：22px 图标 + 标题簇(文字 ellipsis + 贴字 badge) + 耗时/计数 + toggle
    // L2：仅最近动作（无则隐藏整行）
    header.innerHTML =
        '<div class="task-group-row task-group-row-main">'
        + '<span class="tool-status-icon loading" aria-hidden="true"></span>'
        + '<span class="task-group-title" title="' + escapeHtmlAttr(titleAttr) + '">'
        + '<span class="task-group-title-text">' + escapeHtml(titleText) + '</span>'
        + agentHtml
        + '</span>'
        + '<span class="task-group-stats"' + statsTitleAttr + (statsText ? '' : ' style="display:none"') + '>' + escapeHtml(statsText) + '</span>'
        + '<i class="layui-icon layui-icon-right task-group-toggle"></i>'
        + '</div>'
        + '<div class="task-group-row task-group-row-sub"' + (metaText ? '' : ' style="display:none"') + '>'
        + '<span class="task-group-meta"' + metaTitleAttr + (metaText ? '' : ' style="display:none"') + '>' + escapeHtml(metaText) + '</span>'
        + '</div>';
    // 左侧边线仅作为鼠标热区；可见标题是唯一键盘入口。
    var rail = $('<div>').addClass('task-group-rail')[0];
    rail.setAttribute('aria-hidden', 'true');
    rail.setAttribute('title', I18n.t('msg.expand'));
    function toggle() {
        var expanded = !$(group).hasClass('expanded');
        segment.userToggled = true;
        $(group).toggleClass('expanded', expanded);
        header.setAttribute('aria-expanded', String(expanded));
        if (!expanded) {
            // 长内容收起后，若 group 顶部已离开视口，滚回可见，避免空白跳变
            requestAnimationFrame(function() {
                var wrap = document.querySelector('.msg-wrap');
                if (!wrap || !document.contains(group)) return;
                var gr = group.getBoundingClientRect();
                var wr = wrap.getBoundingClientRect();
                if (gr.top < wr.top || gr.top > wr.bottom) {
                    group.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
                }
            });
        }
        header.setAttribute('aria-label', buildTaskGroupAriaLabel(segment, expanded));
        rail.setAttribute('title', expanded ? I18n.t('msg.collapse') : I18n.t('msg.expand'));
    }
    $(header).on('click', function(e) { e.stopPropagation(); toggle(); }).on('keydown', function(e) {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); }
    });
    $(rail).on('click', function(e) { e.stopPropagation(); toggle(); });
    var body = $('<div>').addClass('task-group-body')[0];
    body.id = bodyId;
    $(group).append(rail).append(header).append(body);
    return { groupEl: group, bodyEl: body };
}

/* 在已渲染 DOM 中查找指定 taskId 的 task-group（resetStreamState 清索引但保留 DOM 的迟到场景） */
function findTaskGroupEl(sess, taskId) {
    if (!sess || !sess.container || !taskId) return null;
    var els = sess.container.querySelectorAll('.task-group[data-task-id]');
    for (var i = 0; i < els.length; i++) {
        if (els[i].getAttribute('data-task-id') === taskId) return els[i];
    }
    return null;
}

/* 为 taskId 保持唯一的 task-group；主代理输出仍按连续流片段追加，避免任务组重复创建。 */
function ensureStreamSegment(sess, taskId, taskDescription, agentName) {
    ensureAssistantBubble(sess);
    if (taskId) {
        var taskSegment = sess.taskSegments[taskId];
        if (taskSegment) {
            // 描述/代理名可能晚于建组到达（首个事件未带），补上后同步重绘 L1 标题
            var titleChanged = false;
            if (!taskSegment.taskDescription && taskDescription) {
                taskSegment.taskDescription = taskDescription;
                titleChanged = true;
            }
            if (!taskSegment.agentName && agentName) {
                taskSegment.agentName = agentName;
                titleChanged = true;
            }
            if (titleChanged) updateTaskGroupTitle(taskSegment);
            sess.currentStreamSegment = taskSegment;
            return taskSegment;
        }
        // ★ 迟到 chunk 场景：resetStreamState 清空了内存索引但保留了已渲染 DOM。
        //   若该 taskId 的 task-group 已存在，复用外壳重建索引（不新建 DOM、不重复建组），
        //   避免“每个思考消息新建一个分组 + 同一 taskId 出现多个 task-group”。
        var existingGroup = findTaskGroupEl(sess, taskId);
        if (existingGroup) {
            var now = Date.now();
            var bodyEl = $(existingGroup).find('.task-group-body')[0] || null;
            taskSegment = {
                id: 'task-' + (++sess.streamSegmentSeq),
                laneKey: 'task:' + taskId,
                taskId: taskId,
                taskDescription: taskDescription || null,
                agentName: agentName || null,
                bodyEl: bodyEl,
                groupEl: existingGroup,
                reasonEntries: {},
                // 从 DOM 状态 class 恢复终态，避免已 done/error 的分组被迟到 chunk 打回 running
                status: existingGroup.classList.contains('is-error') ? 'error'
                    : (existingGroup.classList.contains('is-done') ? 'done' : 'running'),
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
            sess.taskGroups[taskId] = existingGroup;
            sess.taskSegments[taskId] = taskSegment;
            sess.streamSegments.push(taskSegment);
            sess.currentStreamSegment = taskSegment;
            // 旧 DOM 的标题可能建于描述到达之前，重建索引后按最新字段刷一次
            if (taskSegment.taskDescription || taskSegment.agentName) updateTaskGroupTitle(taskSegment);
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
    if (!segment) return null;
    reasonId = reasonId || '__default__';
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
    block.innerHTML = '<div class="reason-group-think-header" aria-expanded="' + initiallyExpanded + '"><span class="reason-group-think-label">' + I18n.t('msg.thinking') + '</span>'
        + '<svg class="reason-group-think-spinner" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>'
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

/* 运行中插话（steer）就地渲染：作为 AI 流式气泡内的一个流片段节点，而非独立的 .msg-row。
 * 零持久化（后端不写 ndjson），故刷新即消失，也不进 calcServerCount 视野。
 *
 * 两个必须遵守的约束：
 * 1) 必须插进 main 段的 bodyEl 内部。若插在段外（.msg-content 尾部），ensureStreamSegment 的
 *    短路会复用仍在上方的旧 main 段，后续 reason-group 追加进去会把插话反复下推造成抖动。
 *    插在段内时，下一轮 reasonId 必为新值（ReasonTask 先跑完 onReasonStart 才 newCurrentReasonId），
 *    reason-group 必定新建并 append 到插话下方，增长点在下，位置稳定。
 * 2) 文本节点不得带 .md-content 与 data-md-raw。AI 气泡的复制按钮会逆序扫 .md-content 取
 *    首个非空 data-md-raw（无则回退 innerText），带了会把插话文本当成最终答案复制出去；
 *    finishStream 的 hasTextOutput 与空节点回收同样以 .md-content 为凭。故此处渲染纯文本。
 * 返回 false 表示无气泡可挂，由调用方回落为独立行上屏。 */
function appendSteerNote(sess, text) {
    if (!sess || !sess.container) return false;
    ensureAssistantBubble(sess);
    if (!sess.currentBubbleEl) return false;

    var el = $('<div>').addClass('steer-note')[0];
    el.setAttribute('data-steer', '1');
    if (sess.currentRunId) el.setAttribute('data-run-id', sess.currentRunId);
    // 运行中插话是纯前端渲染（后端不写 ndjson、也无 sourceLabel 下发），使用流式消息专用 i18n 文案
    var label = I18n.t('streaming.steerLabel');
    el.innerHTML = '<span class="steer-note-badge">' + escapeHtml(label) + '</span>'
        + '<span class="steer-note-text">' + escapeHtml(text) + '</span>';

    // 当前段是子代理 task 段时不能插进去：插话属主 trace，放进 .task-group-body 会随分组收起而隐藏。
    // 此时落气泡内容尾部，下一个主段由 ensureStreamSegment 新建并追加在其下方，同样不抖。
    var seg = sess.currentStreamSegment;
    if (seg && !seg.taskId && seg.bodyEl) $(seg.bodyEl).append(el);
    else insertBeforeActions(sess, el);

    if (sess.sessionId === activeSessionId && document.contains(sess.container)) scrollToBottom(true);
    return true;
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
        if (group.reasonRafId) {
            cancelAnimationFrame(group.reasonRafId);
            group.reasonRafId = null;
        }
        // 流结束：把轻量文本升级为完整 Markdown
        if (group.thinkingBodyMdEl) {
            if (typeof finalizeMdElement === 'function') {
                finalizeMdElement(group.thinkingBodyMdEl, group.thinkingBuffer || '');
            } else {
                group.thinkingBodyMdEl.innerHTML = renderMd(group.thinkingBuffer || '');
            }
        }
        // 检查思考块内容是否超出高度
        if (group.thinkingBodyWrapEl) { checkOverflow(group.thinkingBodyWrapEl, 300); }
        $(group.thinkingBlockEl).removeClass('streaming');
        if (window.cliPrintSimplified !== false) {
            $(group.thinkingBlockEl).removeClass('expanded');
        }
        var label = $(group.thinkingBlockEl).find('.reason-group-think-label')[0];
        if (label) $(label).text(I18n.t('msg.thinking'));
        $(group.thinkingBlockEl).find('.reason-group-think-dots').remove();

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
        if (sess.reasonRafId) {
            cancelAnimationFrame(sess.reasonRafId);
            sess.reasonRafId = null;
        }
        if (sess.thinkingBodyMdEl) {
            if (typeof finalizeMdElement === 'function') {
                finalizeMdElement(sess.thinkingBodyMdEl, sess.thinkingBuffer || '');
            } else {
                sess.thinkingBodyMdEl.innerHTML = renderMd(sess.thinkingBuffer || '');
            }
        }
        // 检查思考块内容是否超出高度
        if (sess.thinkingBodyWrapEl) { checkOverflow(sess.thinkingBodyWrapEl, 300); }
        $(sess.thinkingBlockEl).removeClass('streaming');
        if (window.cliPrintSimplified !== false) {
            $(sess.thinkingBlockEl).removeClass('expanded');
        }
        var label = $(sess.thinkingBlockEl).find('.reason-group-think-label')[0];
        if (label) $(label).text(I18n.t('msg.thinking'));
        $(sess.thinkingBlockEl).find('.reason-group-think-dots').remove();
        
        // reason-group 已在 ensureThinkingBlock 中预创建，无需再做 DOM 包裹
        sess.thinkingGroupEl = sess.thinkingBlockEl.parentNode;
        
        sess.thinkingBlockEl = null;
        sess.thinkingBodyMdEl = null;
        sess.thinkingBodyWrapEl = null;
        sess.thinkingBuffer = '';
    }
}

/**
 * 子任务有新输出时刷新状态与 meta。
 * 展开状态始终尊重用户手动切换（userToggled），不在此处强制展开。
 * 状态由 L1 的 tool-status-icon 表达（转圈/勾/叉），不再使用更新点。
 */
function markTaskGroupUpdated(sess, segment) {
    if (!segment || !segment.groupEl || !segment.taskId) return;
    segment.updatedAt = Date.now();
    // 已 task_done 的终态不再被后续迟到 chunk 打回 running
    if (segment.status !== 'error' && segment.status !== 'done') {
        setTaskGroupStatus(segment, 'running');
    } else {
        scheduleTaskGroupMetaUpdate(segment);
    }
}

function clearThinkTags(text) {
    return text.replace(/<\s*\/?think\s*>/gi, '');
}

function appendReasonChunk(sess, segment, text, reasonId, agentName) {
    var clean = clearThinkTags(text || '');
    if (!clean) return;
    // 新 reasonId 到来时，先结束同 segment 内其他 reasonId 的思考块，
    // 防止上一个思考块的 spinner 无限旋转（与 appendContentChunk / appendActionStartChunk 行为一致）。
    // ★ 必须按 segment 隔离（遍历 segment.reasonEntries），不能遍历全局 sess.reasonGroups：
    //   multitask 并行时多个 task-group 的思考流会交错到达，全局遍历会把其它子任务正在
    //   流式输出的思考块强行结束，造成思考流被反复截断、重建，视觉上表现为“每个思考消息一个分组”。
    if (segment) {
        for (var _rid in segment.reasonEntries) {
            if (_rid !== reasonId) {
                var _entry = segment.reasonEntries[_rid];
                if (_entry && _entry.thinkingBlockEl) {
                    finishThinkingBlock(sess, streamReasonKey(segment, _rid));
                }
            }
        }
    }
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
    // 队列 drain 已在 RAF 批处理；此处直接走节流 Markdown 渲染（与主流 coding agent 一致）
    if (group.thinkingBodyMdEl) {
        if (typeof renderMdStreaming === 'function') {
            renderMdStreaming(group.thinkingBodyMdEl, group.thinkingBuffer);
        } else {
            group.thinkingBodyMdEl.textContent = group.thinkingBuffer;
        }
    }
    return block;
}

function finishPendingTool(sess) {
    // 兼容旧单槽：标记并清除
    if (sess.pendingToolCard) {
        var icon = $(sess.pendingToolCard).find('.tool-status-icon')[0];
        if (icon) { icon.className = 'tool-status-icon done'; icon.innerHTML = '<i class="layui-icon layui-icon-ok"></i>'; }
        sess.pendingToolCard = null;
    }
    // 多槽 map：标记所有未完成的 pending 卡片为 done
    for (var _key in sess.pendingToolCards) {
        var pending = sess.pendingToolCards[_key];
        if (pending && pending.card) {
            var icon = $(pending.card).find('.tool-status-icon')[0];
            if (icon) { icon.className = 'tool-status-icon done'; icon.innerHTML = '<i class="layui-icon layui-icon-ok"></i>'; }
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
    bodyEl.classList.add('tool-body-diff');

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
            html += '<div class="edit-result is-error">'
                + '<span class="edit-result-label"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path><line x1="12" y1="9" x2="12" y2="13"></line><line x1="12" y1="17" x2="12.01" y2="17"></line></svg> ' + I18n.t('msg.failed') + '</span>'
                + '<span class="edit-result-text">' + escapeHtml(result) + '</span></div>';
        }
    }
    bodyEl.innerHTML = html;
    return true;
};

/* write / read：按 file_path 推断语言，hljs 语法高亮（hljs 按需加载） */
function renderHighlightedFile(bodyEl, text, args) {
    if (!text) return false;
    var filePath = (args && args.file_path) || '';
    var lang = (typeof window.guessLang === 'function') ? window.guessLang(filePath) : '';
    bodyEl.classList.add('tool-body-code');
    function applyHighlight() {
        if (!(lang && typeof hljs !== 'undefined')) {
            bodyEl.textContent = text;
            return;
        }
        try {
            var highlighted = hljs.highlight(text, { language: lang, ignoreIllegals: true });
            bodyEl.innerHTML = '<pre class="tool-code-pre"><code class="hljs">' + highlighted.value + '</code></pre>';
        } catch (e) {
            bodyEl.textContent = text;
        }
    }
    if (lang && typeof hljs === 'undefined' && typeof ensureHljs === 'function') {
        // 先纯文本占位，库就绪后升级高亮（不阻塞工具卡展示）
        bodyEl.textContent = text;
        ensureHljs(function(err) {
            if (!err) applyHighlight();
        });
        return true;
    }
    if (lang && typeof hljs !== 'undefined') {
        applyHighlight();
        return true;
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
    html += '<div class="tool-summary">' + I18n.t('msg.grepSummary', {files: groups.length, hits: totalHits}) + '</div>';
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
    var html = '<div class="file-listing"><div class="tool-summary">' + I18n.t('msg.itemCount', {count: items.length}) + '</div>';
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

/* ===== bash 系列工具（bash / bash_start / bash_wait / bash_stdin / bash_stop）=====
   全部按同一套终端风格展示入参：头部摘要、tool.start 执行中占位、tool.end 结果体三处共用
   formatBashInvoke 的解析结果，保证同一次调用在各阶段呈现一致。 */
function isBashTool(toolName) {
    var name = String(toolName || '').toLowerCase();
    return name === 'bash' || name.indexOf('bash_') === 0;
}

function formatBashMillis(v) {
    if (v == null || v === '' || !(Number(v) > 0)) return '';
    var n = Number(v);
    return n >= 60000 ? (n / 60000) + 'm' : (n / 1000) + 's';
}

/* 解析 bash 系列入参为 { text, notes[] }：text 为主命令行内容（保留换行），
   notes 为附注（超时 / 会话 / 工作目录等）。无可展示入参时返回 null，交调用方兜底。 */
function formatBashInvoke(toolName, args) {
    if (!args || typeof args !== 'object') return null;
    var name = String(toolName || '').toLowerCase();
    var notes = [];
    function pushNote(label, v) { if (v != null && v !== '') notes.push(label + ' ' + v); }
    var text = '';
    if (name === 'bash' || name === 'bash_start') {
        if (!args.command) return null;
        text = String(args.command);
        pushNote('cwd', args.workdir);
        pushNote('timeout', formatBashMillis(args.timeout));
        pushNote('yield', formatBashMillis(args.yield_time_ms));
        pushNote('hard timeout', formatBashMillis(args.hard_timeout_ms));
    } else if (name === 'bash_stdin') {
        text = 'stdin' + (args.chars != null && args.chars !== '' ? ' ' + String(args.chars) : '');
        pushNote('session', args.session_id);
        pushNote('yield', formatBashMillis(args.yield_time_ms));
    } else if (name === 'bash_wait') {
        text = 'wait' + (args.session_id ? ' ' + args.session_id : '');
        pushNote('yield', formatBashMillis(args.yield_time_ms));
    } else if (name === 'bash_stop') {
        text = 'stop' + (args.session_id ? ' ' + args.session_id : '');
        pushNote('reason', args.reason);
    } else if (name.indexOf('bash_') === 0) {
        // 未知的 bash_ 系列工具：优先 command，否则退化为通用参数串
        text = args.command ? String(args.command) : formatToolArgsStr(args);
        pushNote('session', args.session_id);
    } else {
        return null;
    }
    if (!text) return null;
    return { text: text, notes: notes };
}

/* 紧凑终端风格输出块：命令行 + 附注 + 输出，保留换行但不增加命令/输出分隔线 */
function buildBashOutputHtml(toolName, args, outputText) {
    var invoke = formatBashInvoke(toolName, args);
    var html = '<div class="bash-output">';
    if (invoke) {
        html += '<div class="bash-cmd"><span class="bash-prompt">$</span> ' + escapeHtml(invoke.text);
        if (invoke.notes.length) {
            html += ' <span class="bash-timeout"># ' + escapeHtml(invoke.notes.join(' · ')) + '</span>';
        }
        html += '</div>';
    }
    html += '<pre class="bash-stdout">' + escapeHtml(outputText) + '</pre>';
    html += '</div>';
    return html;
}

function makeBashToolRenderer(toolName) {
    return function(bodyEl, text, args) {
        bodyEl.classList.add('tool-body-terminal');
        bodyEl.innerHTML = buildBashOutputHtml(toolName, args, text || '(' + I18n.t('msg.noOutput') + ')');
        return true;
    };
}
['bash', 'bash_start', 'bash_wait', 'bash_stdin', 'bash_stop'].forEach(function(bashToolName) {
    window._toolRenderers[bashToolName] = makeBashToolRenderer(bashToolName);
});

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
    bodyEl.classList.add('tool-body-todo');
    bodyEl.innerHTML = '<pre class="tool-code-pre"><code class="hljs language-markdown">' + inner + '</code></pre>';
    return true;
}
window._toolRenderers.todowrite = renderTodoMarkdown;
window._toolRenderers.todoread = renderTodoMarkdown;

/* 分发：命中专用 renderer 且渲染成功返回 true，否则交由调用方做纯文本兜底 */
function renderToolBody(bodyEl, toolName, text, args) {
    var renderer = window._toolRenderers[toolName];
    // 未登记的 bash_ 系列工具（后续新增的）同样按终端风格渲染
    if (typeof renderer !== 'function' && isBashTool(toolName)) renderer = makeBashToolRenderer(toolName);
    if (typeof renderer === 'function') {
        try {
            if (renderer(bodyEl, text, args)) return true;
        } catch(e) {
            console.warn('[toolRenderer] renderer "' + toolName + '" threw:', e);
        }
    }
    return false;
}

/* ===== LSP 诊断展示 =====
   payload.lsp 是后端下发的结构化字段（errorCount / items / truncated / file），
   前端不解析工具输出文本：诊断文本是给模型看的，措辞会随 prompt 调优变化。
   errorCount === 0 表示语言服务器检查过且无错误；lsp 为空表示没有语言服务器覆盖该文件。 */
function hasLspErrors(lsp) {
    return !!(lsp && lsp.errorCount > 0);
}

/* 卡片体尾部追加诊断区块：与工具种类无关，故挂在分发层而非各 renderer */
function appendLspPanel(bodyEl, lsp) {
    if (!bodyEl || !hasLspErrors(lsp)) return;

    var items = lsp.items || [];
    var html = '<div class="tool-lsp-panel">'
        + '<div class="tool-lsp-panel-head">'
        + '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path><line x1="12" y1="9" x2="12" y2="13"></line><line x1="12" y1="17" x2="12.01" y2="17"></line></svg>'
        + '<span>' + escapeHtml(I18n.t('msg.lspErrors', { n: lsp.errorCount })) + '</span>';
    if (lsp.file) {
        html += '<span class="tool-lsp-file" title="' + escapeHtml(lsp.file) + '">' + escapeHtml(lsp.file) + '</span>';
    }
    html += '</div><ul class="tool-lsp-list">';

    for (var i = 0; i < items.length; i++) {
        var it = items[i] || {};
        var pos = (it.line || 0) + ':' + (it.column || 0);
        html += '<li class="tool-lsp-item">'
            + '<span class="tool-lsp-pos">' + escapeHtml(pos) + '</span>'
            + '<span class="tool-lsp-msg">' + escapeHtml(it.message || '') + '</span>';
        if (it.source) {
            html += '<span class="tool-lsp-source">' + escapeHtml(it.source) + '</span>';
        }
        html += '</li>';
    }

    if (lsp.truncated && items.length < lsp.errorCount) {
        html += '<li class="tool-lsp-item tool-lsp-more">'
            + escapeHtml(I18n.t('msg.lspMore', { n: lsp.errorCount - items.length })) + '</li>';
    }
    html += '</ul></div>';

    var panel = document.createElement('div');
    panel.innerHTML = html;
    bodyEl.appendChild(panel.firstChild);
}

/* 卡片头部徽标：让用户不展开卡片就能看出这次调用触发了语言服务器。
   有错误 → 警示徽标 + warn 状态图标 + 自动展开；已检查无错 → 极轻量的 ✓ 徽标。 */
function applyLspBadge(card, lsp) {
    if (!card || !lsp) return;
    var group = $(card).find('.tool-card-title-group')[0];
    if (!group) return;

    $(group).find('.tool-lsp-badge').remove();
    //结论未知（冷启动索引中，等待超时）：无信息量，不渲染徽标，
    //也不显示 ✓——不能冒充「检查过且没问题」
    var hasErr = hasLspErrors(lsp);
    if (!hasErr && lsp.pending) return;

    var badge = document.createElement('span');

    badge.className = 'tool-lsp-badge ' + (hasErr ? 'is-error' : 'is-clean');
    if (hasErr) {
        badge.textContent = 'LSP ' + lsp.errorCount;
        badge.title = I18n.t('msg.lspErrors', { n: lsp.errorCount });
    } else {
        badge.textContent = 'LSP ✓';
        badge.title = I18n.t('msg.lspClean');
    }
    group.appendChild(badge);

    if (hasErr && window.cliPrintSimplified === false) {
        // 错误值得被看到：仅在关闭精简模式（cliPrintSimplified = false）时自动展开
        if (!$(card).hasClass('expanded')) {
            $(card).addClass('expanded');
            $(card).find('.tool-card-header').attr('aria-expanded', 'true');
            if (card._pendingToolRender && !card._toolBodyRendered) card._pendingToolRender();
        }
    }
}

/* 检查容器内容是否超出当前 CSS 限高，若超出则添加溢出指示器 */
function checkOverflow(el) {
    if (!el) return;
    var hasOverflow = el.clientHeight > 0 && el.scrollHeight > el.clientHeight + 1;
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
        if (Array.isArray(v)) return '[' + v.length + I18n.t('msg.items') + ']';
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

/* 根据工具语义生成头部摘要，避免通用 key=value 抢占视觉空间。 */
function formatToolSummary(toolName, args) {
    if (!args || typeof args !== 'object') return '';
    var name = String(toolName || '').toLowerCase();
    if (isBashTool(name)) {
        var bashInvoke = formatBashInvoke(name, args);
        if (bashInvoke) {
            var bashSummary = bashInvoke.text.replace(/\n/g, ' ');
            if (bashInvoke.notes.length) bashSummary += ' · ' + bashInvoke.notes.join(' · ');
            return bashSummary;
        }
    }
    if ((name === 'read' || name === 'write' || name === 'edit') && args.file_path) {
        var fileSummary = String(args.file_path);
        if (name === 'read' && args.offset) {
            fileSummary += ' · ' + args.offset;
            if (args.limit) fileSummary += '–' + (Number(args.offset) + Number(args.limit) - 1);
            fileSummary += ' ' + I18n.t('msg.lines');
        }
        return fileSummary;
    }
    if (name === 'grep') {
        var grepSummary = args.pattern ? '“' + String(args.pattern).replace(/\n/g, ' ') + '”' : '';
        if (args.path) grepSummary += (grepSummary ? ' · ' : '') + args.path;
        return grepSummary;
    }
    if (name === 'glob') {
        var globSummary = args.pattern ? String(args.pattern) : '';
        if (args.path) globSummary += (globSummary ? ' · ' : '') + args.path;
        return globSummary;
    }
    if (name === 'ls') return (args.path || '') + (args.recursive ? ' · ' + I18n.t('msg.recursive') : '');
    return formatToolArgsStr(args);
}

function getToolKind(toolName) {
    var name = String(toolName || '').toLowerCase();
    if (isBashTool(name)) return 'terminal';
    if (name === 'edit') return 'diff';
    if (name === 'read' || name === 'write') return 'code';
    if (name === 'grep') return 'search';
    if (name === 'glob' || name === 'ls') return 'files';
    if (name === 'todowrite' || name === 'todoread') return 'todo';
    return 'generic';
}

function createToolCard(toolName, args, toolTitle, agentName, statusClass) {
    var card = $('<div>').addClass('tool-card tool-kind-' + getToolKind(toolName))[0];
    var summary = formatToolSummary(toolName, args);
    var titleHtml = '<span class="tool-name">' + escapeHtml(toolTitle || toolName || 'tool') + '</span>';
    if (agentName) titleHtml += '<span class="agent-badge">' + escapeHtml(agentName) + '</span>';
    if (summary) titleHtml += '<span class="tool-args" title="' + escapeHtml(summary) + '">' + escapeHtml(summary) + '</span>';
    var statusIcon = statusClass === 'done' ? '<i class="layui-icon layui-icon-ok"></i>'
        : (statusClass === 'warn' ? '<i class="layui-icon layui-icon-tips"></i>' : '');
    card.innerHTML = '<div class="tool-card-header" role="button" tabindex="0" aria-expanded="false">'
        + '<span class="tool-status-icon ' + (statusClass || 'loading') + '">' + statusIcon + '</span>'
        + '<span class="tool-card-title-group">' + titleHtml + '</span>'
        + '<i class="layui-icon layui-icon-right tool-toggle"></i></div><div class="tool-card-body"></div>';
    if (agentName) $(card).addClass('is-subagent');
    if (window.cliPrintSimplified === false) $(card).addClass('expanded');
    bindToolCardToggle(card);
    return card;
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
/* 工具卡 body：默认折叠时延迟重渲染（diff/hljs），展开时再填充，避免 action_end 瞬时卡顿 */
function bindToolCardToggle(card) {
    var header = $(card).find('.tool-card-header');
    function setExpanded(expanded) {
        $(card).toggleClass('expanded', expanded);
        header.attr('aria-expanded', expanded ? 'true' : 'false');
        if (expanded && card._pendingToolRender && !card._toolBodyRendered) card._pendingToolRender();
    }
    header.off('.toolcard')
        .on('click.toolcard', function() { setExpanded(!$(card).hasClass('expanded')); })
        .on('keydown.toolcard', function(e) {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                setExpanded(!$(card).hasClass('expanded'));
            }
        });
    header.attr('aria-expanded', $(card).hasClass('expanded') ? 'true' : 'false');
}

function fillToolCardBody(card, toolName, text, args, lsp) {
    var body = $(card).find('.tool-card-body')[0];
    if (!body) return;
    function doRender() {
        if (card._toolBodyRendered) return;
        card._toolBodyRendered = true;
        body.className = 'tool-card-body';
        if (!renderToolBody(body, toolName, text, args)) body.textContent = text || '';
        appendLspPanel(body, lsp);
        checkOverflow(body);
    }
    // 展开态或没有简化开关时立即渲染；折叠态延迟到用户展开
    if ($(card).hasClass('expanded') || window.cliPrintSimplified === false) {
        doRender();
    } else {
        card._pendingToolRender = doRender;
        // 先放一段轻量摘要，避免 body 完全空白
        var preview = (text || '');
        if (preview.length > 240) preview = preview.substring(0, 240) + '...';
        body.textContent = preview;
        body.classList.add('tool-body-deferred');
    }
}

/* bash 系列工具（bash / bash_start / bash_wait / bash_stdin / bash_stop）在 tool.start 时
   即把入参渲染进 body（终端样式 + 执行中占位），不必等 tool.end 才看到。
   不改动展开态：卡片展开与否仍由「工具调用显示简化」配置在 createToolCard 中决定，
   简化模式下折叠 body 不可见（命令仍在头部 tool-args 摘要中），展开后立即可见。
   tool.end 时 fillToolCardBody 会把 body 重渲染为命令 + 实际输出。 */
function renderBashRunningBody(card, toolName, args) {
    if (!isBashTool(toolName)) return;
    if (!formatBashInvoke(toolName, args)) return;
    var body = $(card).find('.tool-card-body')[0];
    if (!body) return;
    body.className = 'tool-card-body tool-body-terminal';
    body.innerHTML = buildBashOutputHtml(toolName, args, '(' + I18n.t('msg.executing') + ')');
}

function appendActionStartChunk(sess, segment, toolName, args, toolTitle, reasonId, agentName, callId) {
    // 复用同 callId 的既有卡片（典型场景：HITL 批准后转执行的卡）：只把它转 loading 并
    // 刷新标题/参数，避免恢复流后 action_start 再新建一张、导致 HITL 卡沦为孤儿双卡。
    // 正常工具首个 action_start 时 pendingToolCards 无此 callId，命中不了，走原新建逻辑，不受影响。
    var reuse = callId ? findPendingToolCard(sess, callId, null) : { pending: null };
    if (reuse.pending && reuse.pending.card) {
        var reused = reuse.pending.card;
        $(reused).removeClass('hitl-pending');
        var reuseIcon = $(reused).find('.tool-status-icon')[0];
        if (reuseIcon) { reuseIcon.className = 'tool-status-icon loading'; reuseIcon.innerHTML = ''; }
        var reuseTitle = $(reused).find('.tool-name')[0];
        if (reuseTitle) reuseTitle.textContent = toolTitle || toolName || 'unknown';
        renderBashRunningBody(reused, toolName, args);
        if (segment && segment.taskId) recordTaskGroupToolStart(segment, toolName, toolTitle, args);
        return reused;
    }
    var group = ensureReasonGroup(sess, segment, reasonId);
    if (group && group.thinkingBlockEl) finishThinkingBlock(sess, streamReasonKey(segment, reasonId));
    var card = createToolCard(toolName, args, toolTitle, agentName, 'loading');
    if (sess.currentRunId) card.setAttribute('data-run-id', sess.currentRunId);
    renderBashRunningBody(card, toolName, args);
    if (group) { group.activeKind = 'tool'; $(group.groupEl).append(card); } else $(segment.bodyEl).append(card);
    if (callId) card.setAttribute('data-call-id', callId);
    registerPendingToolCard(sess, card, callId, streamReasonKey(segment, reasonId));
    if (segment && segment.taskId) recordTaskGroupToolStart(segment, toolName, toolTitle, args);
    return card;
}

function resolveTaskSegmentFromCard(sess, card) {
    if (!sess || !card || !sess.taskSegments) return null;
    var group = $(card).closest('.task-group')[0];
    if (!group) return null;
    var taskId = group.getAttribute('data-task-id');
    return (taskId && sess.taskSegments[taskId]) || null;
}

function appendActionEndChunk(sess, segment, toolName, text, args, toolTitle, reasonId, agentName, callId, lsp) {
    var pendingMatch = findPendingToolCard(sess, callId, null);
    var card = pendingMatch.pending && pendingMatch.pending.started ? pendingMatch.pending.card : null;
    if (card) delete sess.pendingToolCards[pendingMatch.key];
    if (!card) {
        if (!segment) segment = ensureStreamSegment(sess, null, null, null);
        var group = ensureReasonGroup(sess, segment, reasonId);
        card = createToolCard(toolName, args, toolTitle, agentName, 'done');
        if (group) { group.activeKind = 'tool'; $(group.groupEl).append(card); } else $(segment.bodyEl).append(card);
        // 无 action_start 的终态卡也计入 task 摘要
        if (segment && segment.taskId) recordTaskGroupToolStart(segment, toolName, toolTitle, args);
    } else {
        bindToolCardToggle(card);
    }
    card._toolBodyRendered = false;
    fillToolCardBody(card, toolName, text, args, lsp);
    var icon = $(card).find('.tool-status-icon')[0];
    // 工具本身执行成功，但 LSP 报错时用 warn 图标提醒（区别于工具失败的红色路径）
    if (icon) {
        if (hasLspErrors(lsp)) {
            icon.className = 'tool-status-icon warn';
            icon.innerHTML = '<i class="layui-icon layui-icon-tips"></i>';
        } else {
            icon.className = 'tool-status-icon done';
            icon.innerHTML = '<i class="layui-icon layui-icon-ok"></i>';
        }
    }
    applyLspBadge(card, lsp);
    if (callId) card.setAttribute('data-call-id', callId);
    // 优先用 chunk 上的 task segment；若 action_end 缺 taskId，则从已挂载的 tool-card 反查归属
    var taskSegment = (segment && segment.taskId) ? segment : resolveTaskSegmentFromCard(sess, card);
    if (taskSegment) {
        recordTaskGroupToolEnd(taskSegment);
    // onWebEvent 仅在 segment.taskId 时 mark；此处覆盖 action_end 无 taskId 的反查场景
        if (!segment || !segment.taskId) markTaskGroupUpdated(sess, taskSegment);
    }
    return card;
}

function appendContentChunk(sess, segment, text, append, reasonId) {
    var clean = clearThinkTags(text || '');
    if (!clean) return;
    var group = ensureReasonGroup(sess, segment, reasonId);
    if (!group) return;
    // 正文开始即视为该 reasonId 的思考流结束：收起思考块并移除 streaming（spinner 停转），
    // 与 appendActionStartChunk 一致，避免思考→正文切换时转圈图标残留。
    if (group.thinkingBlockEl) finishThinkingBlock(sess, streamReasonKey(segment, reasonId));
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
    // 队列 drain 已在 RAF 批处理；此处直接走节流 Markdown 渲染，边出边排
    if (run.el) {
        if (typeof renderMdStreaming === 'function') {
            renderMdStreaming(run.el, run.buffer);
        } else {
            run.el.textContent = run.buffer;
        }
    }
    return run.el;
}

function appendErrorChunkToSegment(sess, segment, text) {
    if (!segment || !segment.bodyEl) {
        return appendErrorChunk(sess, text);
    }
    var errEl = $('<div>').addClass('chunk-error').text(text)[0];
    $(segment.bodyEl).append(errEl);
    segment.errorCount = (segment.errorCount || 0) + 1;
    segment.updatedAt = Date.now();
    // error → 红叉 + is-error 左边框；不自动展开
    setTaskGroupStatus(segment, 'error');
    return errEl;
}

function appendErrorChunk(sess, text, taskId, taskDescription, agentName) {
    // 仅显式 taskId 才归入 task-group；无 taskId 一律挂气泡根级，绝不弱归属
    if (taskId) {
        var segment = ensureStreamSegment(sess, taskId, taskDescription, agentName);
        if (segment && segment.taskId) {
            return appendErrorChunkToSegment(sess, segment, text);
        }
    }
    ensureAssistantBubble(sess);
    var errEl = $('<div>').addClass('chunk-error').text(text)[0];
    insertBeforeActions(sess, errEl);
    return errEl;
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
    return badge[0];
}

/* ===== Command Output ===== */
function appendCommandOutput(sess, text) {
    ensureAssistantBubble(sess);
    var mdEl = $('<div>').addClass('md-content')[0];
    if (typeof finalizeMdElement === 'function') {
        finalizeMdElement(mdEl, text || '');
    } else {
        mdEl.innerHTML = renderMd(text);
        if (typeof processMermaidBlocks === 'function') processMermaidBlocks(mdEl);
    }
    insertBeforeActions(sess, mdEl);
    sess.currentBubbleEl = mdEl;
    return mdEl;
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
    if (typeof observeMessagesHeight === 'function') observeMessagesHeight(sess.thinkingEl);
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
    if (sess.sessionId === activeSessionId) scrollForStreamEvent(sess, null, el, false);
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
function appendHitlCard(sess, toolName, command, callId, args, toolTitle, comment) {
    ensureAssistantBubble(sess);

    // 本批挂起卡片计数：每收一张 +1，决策一张 -1，归零时才恢复流
    sess.pendingHitlCount = (sess.pendingHitlCount || 0) + 1;

    var cardArgs = (args && typeof args === 'object') ? args : (command ? { command: command } : {});
    var card = createToolCard(toolName, cardArgs, I18n.t('msg.needApproval') + (toolName || 'unknown'), null, 'warn');
    $(card).addClass('hitl-pending');
    if (sess.currentRunId) card.setAttribute('data-run-id', sess.currentRunId);
    if (callId) card.setAttribute('data-call-id', callId);
    var body = $(card).find('.tool-card-body')[0];
    if (body) {
        // 优先 command，其次 args 摘要，都没有时用默认提示
        var bodyText = command;
        if (!bodyText && args && typeof args === 'object') {
            try { bodyText = JSON.stringify(args); } catch (e) { bodyText = ''; }
        }
        body.textContent = bodyText || I18n.t('msg.waitingForApproval');
    }
    // 拦截理由作为副标题
    if (comment) {
        var titleEl = $(card).find('.tool-name')[0];
        if (titleEl) titleEl.setAttribute('title', comment);
    }
    card.insertAdjacentHTML('beforeend', '<div class="hitl-card-actions">'
        + '<button class="hitl-btn hitl-btn-approve">' + I18n.t('msg.approve') + '</button>'
        + '<button class="hitl-btn hitl-btn-reject">' + I18n.t('msg.reject') + '</button>'
        + '</div>');

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
        // 按 callId 暂存已批准卡，供流恢复后重新登记进 pendingToolCards，让同 callId 的 action_start 复用它。
        if (callId) {
            if (!sess.hitlApprovedCards) sess.hitlApprovedCards = {};
            sess.hitlApprovedCards[callId] = card;
        }
        handleHitlResponse(sess, 'approve', callId);
    });

    $(rejectBtn).on('click', function() {
        approveBtn.disabled = true;
        rejectBtn.disabled = true;
        var icon = $(card).find('.tool-status-icon')[0];
        if (icon) { icon.className = 'tool-status-icon reject'; icon.innerHTML = '<i class="layui-icon layui-icon-close"></i>'; }
        $(card).find('.tool-name').text(I18n.t('msg.rejected') + (toolName || 'unknown'));
        $(card).find('.hitl-card-actions').remove();
        $(card).removeClass('hitl-pending expanded');
        if (callId && sess.hitlApprovedCards) delete sess.hitlApprovedCards[callId];
        handleHitlResponse(sess, 'reject', callId);
    });

    if (sess.sessionId === activeSessionId) scrollForStreamEvent(sess, null, card, false);
    return card;
}

function handleHitlResponse(sess, action, callId) {
    // 本批剩余未决策卡片数减一；仅当归零（本批最后一张决策）时才恢复流。
    // 批量场景下前面几张卡只发决策 POST，不重建流、不重置状态，避免冲掉未点的其他卡。
    sess.pendingHitlCount = Math.max(0, (sess.pendingHitlCount || 1) - 1);
    var isLast = (sess.pendingHitlCount === 0);

    if (isLast) {
        if (sess.eventSource) { sess.eventSource.close(); sess.eventSource = null; }
        resetStreamState(sess);

        // resetStreamState 清空了 pendingToolCards；把本批已批准的 HITL 卡重新登记，
        // 使恢复流后同 callId 的 action_start 能复用它们，而不是另建新卡。
        if (sess.hitlApprovedCards) {
            for (var _hcid in sess.hitlApprovedCards) {
                registerPendingToolCard(sess, sess.hitlApprovedCards[_hcid], _hcid, null);
            }
            sess.hitlApprovedCards = {};
        }

        sess.isStreaming = true;
        sess.stopRequested = false;
        sess.acceptingStream = true;
        sess._streamClosed = false;
        sess._closedRunId = null;
        if (sess.sessionId === activeSessionId) {
            isStreaming = true;
            setBtnStopMode();
        }
        showThinking(sess);
    }

    // 通过 HTTP POST 发送 HITL 决策，结果通过 WebSocket 推送
    var formData = new FormData();
    formData.append('hitlAction', action);
    formData.append('sessionId', sess.sessionId);
    if (callId) formData.append('hitlCallId', callId);

    fetch(SSE_ENDPOINT, {
        method: 'POST',
        body: formData
    }).then(function(resp) {
        // HTTP 响应只有 {"status":"ok"}，实际数据通过 WebSocket 推送
    }).catch(function(err) {
        console.error('HITL error:', err);
        // 通过回调占位调用 finishStream（由 app-streaming.js 注册）
        if (isLast && onFinishStream) onFinishStream(sess);
    });
}

/* ===== Rewind Anchor =====
 * 回退锚点：以「同一轮任务的 runId」定位删除起点，条数交由服务端在真实消息列表上算。
 * 前端能数的 DOM 行与 ndjson 行并非一一对应（系统通知行、被中断轮次的空气泡无服务端记录；
 * 连续 assistant 会被历史渲染合并成一个气泡），按行数删必然多删或少删。 */
function buildRewindPayload(sess, row) {
    var payload = { sessionId: sess.sessionId };
    var runId = row && row.getAttribute ? row.getAttribute('data-run-id') : null;
    if (runId) {
        payload.anchorRunId = runId;
        payload.anchorRole = $(row).hasClass('user') ? 'user' : 'assistant';
    }
    // count 仅作老数据（无 runId）降级用；服务端有 anchorRunId 时不看它
    payload.count = calcServerCount(sess.container, row);
    return payload;
}

/* 回退失败的统一处理：锚点对不上时不猜条数，改为重载历史让界面回到与服务端一致的状态。 */
function handleRewindFailure(sess, resp) {
    var desc = (resp && (resp.description || resp.message)) || I18n.t('msg.backendNotSucceeded');
    layer.msg(I18n.t('msg.deleteFailed') + desc, { icon: 2, time: 3000, offset: '120px' });
    if (resp && resp.code === 409 && String(desc).indexOf('ANCHOR_NOT_FOUND') >= 0) {
        // 服务端没找到锚点（一条未删）：界面可能已与服务端不同步，重载以对齐
        if (typeof loadMessages === 'function') loadMessages(sess.sessionId);
    }
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
        // 最后一轮执行过程的回放行（data-replay）源于 ReActTrace 而非 ndjson，服务端无对应记录，
        // 计入则 rewind 会每行多删一条真实消息
        if (r.getAttribute('data-replay')) continue;
        // 系统通知行（.system-notice）由前端就地生成，ndjson 里没有它：计入会让降级删除多删一条真实消息。
        // 它自身没有删除按钮，但循环是从锚点一路数到末尾的，删其它行时会把它扫进来。
        if ($(r).hasClass('system-notice')) continue;
        // 存量会话的插话行（改动前已写入 ndjson）有服务端记录，须计入不可跳过，否则 rewind 会少删；
        // 新产生的插话已零持久化、且渲染为 AI 气泡内的 .steer-note（不是 .msg-row），不进本函数视野
        // 用户消息中，以 / 开头的命令在 ndjson 中无记录，跳过
        if ($(r).hasClass('user')) {
            var textEl = $(r).find('.user-msg-text')[0];
            if (textEl) {
                var raw = textEl.getAttribute('data-md-raw') || textEl.innerText;
                if (raw.trim().startsWith('/') && /^\/[a-zA-Z][a-zA-Z0-9_-]*(\s.*)?$/.test(raw.trim())) continue;
            }
        }
        // 系统通知无服务端记录，已在上面按 .system-notice 跳过
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
    updateUserRerunButtons(sess.container);
}

/* ===== Code Block Copy Buttons ===== */
function addCodeBlockButtons(container) {
    if (!container) return;
    var pres = $(container).find('pre');
    for (var i = 0; i < pres.length; i++) {
        if ($(pres[i]).find('.code-copy-btn').length) continue;
        var btn = $('<button>').addClass('code-copy-btn').attr('data-i18n', 'common.copy')[0];
        if (window.I18n) window.I18n.apply(btn);
        $(btn).on('click', function(e) {
            e.stopPropagation();
            var pre = $(this).closest('pre')[0];
            var code = pre ? $(pre).find('code')[0] : null;
            var text = code ? $(code).text() : (pre ? $(pre).text() : '');
            var self = this;
            if (navigator.clipboard) {
                navigator.clipboard.writeText(text).then(function() {
                    $(self).text(I18n.t('msg.copied')).addClass('copied');
                    setTimeout(function() {
                        $(self).text(I18n.t('common.copy')).removeClass('copied');
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
        // MD 内嵌图解码后补滚（用户附件图在 appendUserMessage 已绑）
        (function(imgEl) {
            function onImgLayout() {
                if (typeof scheduleScrollToBottom === 'function') scheduleScrollToBottom();
                else if (typeof scrollToBottom === 'function') scrollToBottom(false);
            }
            if (imgEl.complete) {
                // 缓存图可能已 complete，但刚插入时高度尚未参与布局
                requestAnimationFrame(onImgLayout);
            } else {
                $(imgEl).one('load error', onImgLayout);
            }
        })(imgs[i]);
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

/* ===== 消息区选中文本右键菜单：复制 / 在对话中引用 =====
 * 仅当右键点击落在 .msg-area 内且有选中文本时拦截默认菜单；
 * 未选中或点在输入框/代码块时不拦截，交回浏览器原生菜单。
 * 「在对话中引用」把选中内容以 markdown 引用块插入当前输入框（跟随 inChatMode），
 * 多行逐行加 > 前缀，发送后可被 AI 正确还原为上下文。 */
var selectionMenuEl = null;
var selectionMenuRange = null;

function getSelectionMenuTarget() {
    var input = (typeof inChatMode !== 'undefined' && inChatMode) ? chatInput : newChatInput;
    if (!input) input = document.getElementById('chatInput') || document.getElementById('newChatInput');
    return input;
}

function buildQuoteBlock(text) {
    var lines = String(text).replace(/\r\n/g, '\n').split('\n');
    var quoted = [];
    for (var i = 0; i < lines.length; i++) {
        quoted.push('> ' + lines[i]);
    }
    return quoted.join('\n');
}

function insertQuoteToInput(text) {
    var input = getSelectionMenuTarget();
    if (!input) return;
    var quote = buildQuoteBlock(text);
    var currentVal = input.value || '';
    var cursorPos = input.selectionStart != null ? input.selectionStart : currentVal.length;
    var before = currentVal.substring(0, cursorPos);
    var after = currentVal.substring(cursorPos);
    var sep = '';
    if (before.length > 0 && !before.endsWith('\n')) sep = '\n\n';
    var insert = sep + quote + '\n\n';
    input.value = before + insert + after;
    input.focus();
    var newPos = (before + insert).length;
    input.setSelectionRange(newPos, newPos);
    if (typeof autoResize === 'function') autoResize(input);
}

function closeSelectionMenu() {
    if (selectionMenuEl) {
        $(selectionMenuEl).remove();
        selectionMenuEl = null;
    }
    selectionMenuRange = null;
}

function showSelectionMenu(x, y, selectedText) {
    closeSelectionMenu();
    var menu = $('<div>').addClass('selection-menu')[0];
    menu.setAttribute('role', 'menu');
    menu.innerHTML = ''
        + '<button type="button" class="selection-menu-item" data-act="copy">'
        + '<svg class="more-menu-icon" viewBox="0 0 24 24"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>'
        + '<span data-i18n="common.copy">复制</span></button>'
        + '<button type="button" class="selection-menu-item" data-act="quote">'
        + '<svg class="more-menu-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>'
        + '<span data-i18n="msg.addToChat">加入对话</span></button>';
    if (window.I18n) window.I18n.apply(menu);
    $(document.body).append(menu);
    selectionMenuEl = menu;
    selectionMenuRange = selectedText;

    // 边界翻转：右/下溢出时反向展开，避免被视口裁切
    var $menu = $(menu);
    var mw = $menu.outerWidth();
    var mh = $menu.outerHeight();
    var vw = window.innerWidth;
    var vh = window.innerHeight;
    var left = x + 2, top = y + 2;
    if (left + mw > vw - 8) left = Math.max(8, x - mw - 2);
    if (top + mh > vh - 8) top = Math.max(8, y - mh - 2);
    menu.style.left = left + 'px';
    menu.style.top = top + 'px';

    $menu.on('mousedown', function(e) { e.preventDefault(); });
    $menu.find('.selection-menu-item').on('click', function() {
        var act = this.getAttribute('data-act');
        var text = selectionMenuRange;
        closeSelectionMenu();
        if (act === 'copy') {
            if (navigator.clipboard) {
                navigator.clipboard.writeText(text).then(function() {
                    showToast(I18n.t('msg.copied'), 'success');
                }, function() {
                    showToast(I18n.t('msg.copyFailed'), 'error');
                });
            }
        } else if (act === 'quote') {
            insertQuoteToInput(text);
        }
    });
}

(function initSelectionContextMenu() {
    var msgArea = document.getElementById('msgArea') || document.querySelector('.msg-area');
    if (!msgArea) return;
    $(msgArea).on('contextmenu', function(e) {
        // 输入框、代码块内的右键交回原生菜单（代码区有复制按钮，且原生菜单更符合直觉）
        if (e.target && (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA' || $(e.target).closest('pre, code, .code-block').length > 0)) return;
        var sel = window.getSelection ? window.getSelection() : null;
        var text = sel ? String(sel.toString()) : '';
        if (!text || !text.trim()) return; // 未选中文字：保持原生菜单
        var range = sel.getRangeAt ? sel.getRangeAt(0) : null;
        // 选区必须落在消息区内，才弹自定义菜单
        if (range && msgArea.contains(range.commonAncestorContainer)) {
            e.preventDefault();
            showSelectionMenu(e.clientX, e.clientY, text);
        }
    });
    $(document).on('mousedown', function(e) {
        if (!selectionMenuEl) return;
        if (e.target === selectionMenuEl || $(e.target).closest(selectionMenuEl).length > 0) return;
        closeSelectionMenu();
    });
    $(document).on('keydown', function(e) {
        if (!selectionMenuEl) return;
        if (e.key === 'Escape') closeSelectionMenu();
    });
    $(window).on('resize scroll blur', closeSelectionMenu);
})();

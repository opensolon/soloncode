/* ===== 上下文状态指示器（输入框上方居中） ===== */
/* Context 用量 + 本轮总计时（用户发送后起算，finishStream 定格） */

var _roundElapsedTimerId = null;

function fmtContextTokens(n) {
    if (n >= 1000000 && n % 1000000 === 0) return (n / 1000000) + 'm';
    if (n >= 1000) return (n / 1000).toFixed(n % 1000 === 0 ? 0 : 1).replace(/\.0$/, '') + 'k';
    return n.toString();
}

function formatRoundElapsedText(sess) {
    if (!sess || !sess.roundStartedAt) return '';
    var endAt = sess.roundEndedAt || Date.now();
    var secs = Math.max(0, Math.floor((endAt - sess.roundStartedAt) / 1000));
    return secs + 's';
}

/**
 * 刷新 Context 条文案：Context 段 + 可选 · Ns
 * 仅驱动 activeSession 的 DOM。
 */
function renderContextStatus(sess) {
    var $status = $('.context-status');
    if (!$status.length) return;

    if (!sess || sess.sessionId !== activeSessionId) {
        // 非活动会话：若当前活动会话有状态则由 switch 路径处理；此处不误清
        return;
    }

    var hasContext = !!(sess.contextTokens != null || sess.contextLength != null);
    var elapsedText = formatRoundElapsedText(sess);
    if (!hasContext && !elapsedText) {
        $status.hide();
        $('.context-status-text').text('Context: -- / -- (--%)');
        $('.context-status-elapsed').text('').hide();
        return;
    }

    var contextText;
    if (hasContext) {
        var tokens = Math.round(sess.contextTokens || 0);
        var contextLength = Math.round(sess.contextLength || 0);
        var percent = contextLength > 0 ? Math.round(tokens / contextLength * 100) : 0;
        contextText = 'Context: ' + fmtContextTokens(tokens) + ' / ' + fmtContextTokens(contextLength) + ' (' + percent + '%)';
    } else {
        contextText = 'Context: -- / -- (--%)';
    }
    $('.context-status-text').text(contextText);

    var $elapsed = $('.context-status-elapsed');
    if (elapsedText) {
        $elapsed.text('\u00b7 ' + elapsedText).show();
    } else {
        $elapsed.text('').hide();
    }
    $status.show();
}

/**
 * 更新上下文状态 UI
 * @param {Object} chunk - type 为 context_size 的 WebChunk
 * @param {Object} [sess] - 可选；缺省写 activeSession
 */
function updateContextIndicator(chunk, sess) {
    if (!sess) {
        if (!activeSessionId || !sessionMap[activeSessionId]) return;
        sess = sessionMap[activeSessionId];
    }
    sess.contextTokens = Math.round(chunk.totalTokens || 0);
    sess.contextLength = 0;
    if (chunk.args && chunk.args.contextLength) {
        sess.contextLength = Math.round(chunk.args.contextLength);
    }
    // 仅刷新当前可见会话的条；后台会话数据已写入 sess 供切换时恢复
    if (sess.sessionId === activeSessionId) renderContextStatus(sess);
}

/**
 * 用户发送成功进入流式后启动本轮总计时。
 * 锚定 sess.roundStartedAt；已在跑则不重置（避免 sendWithFormData 重复调用）。
 */
function startRoundElapsed(sess) {
    if (!sess) return;
    // 本轮已在计时中：不重置（sendMessage + sendWithFormData 会各调一次）
    if (sess.roundStartedAt && !sess.roundEndedAt && sess.isStreaming) {
        ensureRoundElapsedTimer();
        if (sess.sessionId === activeSessionId) renderContextStatus(sess);
        return;
    }
    sess.roundStartedAt = sess.messageStartTime || Date.now();
    sess.roundEndedAt = null;
    ensureRoundElapsedTimer();
    if (sess.sessionId === activeSessionId) renderContextStatus(sess);
}

/**
 * 流结束：定格秒数，停止全局 tick；条保留展示最终值。
 */
function stopRoundElapsed(sess) {
    if (!sess) return;
    if (sess.roundStartedAt && !sess.roundEndedAt) {
        sess.roundEndedAt = Date.now();
    }
    // 若无任何会话仍在流式计时，停掉共享 timer
    var anyRunning = false;
    if (typeof sessionMap !== 'undefined') {
        for (var sid in sessionMap) {
            if (!Object.prototype.hasOwnProperty.call(sessionMap, sid)) continue;
            var s = sessionMap[sid];
            if (s && s.roundStartedAt && !s.roundEndedAt) {
                anyRunning = true;
                break;
            }
        }
    }
    if (!anyRunning) stopRoundElapsedTimer();
    if (sess.sessionId === activeSessionId) renderContextStatus(sess);
}

function ensureRoundElapsedTimer() {
    if (_roundElapsedTimerId) return;
    _roundElapsedTimerId = setInterval(function() {
        tickRoundElapsed();
    }, 1000);
}

function tickRoundElapsed() {
    if (!activeSessionId || !sessionMap[activeSessionId]) return;
    var sess = sessionMap[activeSessionId];
    if (!sess.roundStartedAt || sess.roundEndedAt) return;
    renderContextStatus(sess);
}

function stopRoundElapsedTimer() {
    if (_roundElapsedTimerId) {
        clearInterval(_roundElapsedTimerId);
        _roundElapsedTimerId = null;
    }
}

/**
 * 重置/切换会话时刷新 Context 条：
 * - 目标会话有缓存 context 或本轮计时 → 恢复展示
 * - 否则 hide
 */
function resetContextIndicator() {
    stopRoundElapsedTimer();
    var sess = (activeSessionId && sessionMap[activeSessionId]) ? sessionMap[activeSessionId] : null;
    if (sess && sess.roundStartedAt && !sess.roundEndedAt) {
        ensureRoundElapsedTimer();
    }
    if (sess) {
        renderContextStatus(sess);
        return;
    }
    var $status = $('.context-status');
    if ($status.length) {
        $status.hide();
        $('.context-status-text').text('Context: -- / -- (--%)');
        $('.context-status-elapsed').text('').hide();
    }
}

/* ===== 上下文状态指示器（输入框上方居中） ===== */

/**
 * 更新上下文状态 UI
 * @param {Object} chunk - type 为 context_size 的 WebChunk
 */
function updateContextIndicator(chunk) {
    var $status = $('.context-status');
    if (!$status.length) return;

    var tokens = Math.round(chunk.totalTokens || 0);
    var contextLength = 0;
    if (chunk.args && chunk.args.contextLength) {
        contextLength = Math.round(chunk.args.contextLength);
    }
    var percent = contextLength > 0 ? Math.round(tokens / contextLength * 100) : 0;

    function fmtK(n) {
        if (n >= 1000) return (n / 1000).toFixed(n % 1000 === 0 ? 0 : 1).replace(/\.0$/, '') + 'k';
        return n.toString();
    }
    $('.context-status-text').text('Context: ' + fmtK(tokens) + ' / ' + fmtK(contextLength) + ' (' + percent + '%)');
    $status.show();
}

/**
 * 重置上下文状态指示器（切换会话时调用）
 */
function resetContextIndicator() {
    hideContextThinking();
    var $status = $('.context-status');
    if ($status.length) {
        $status.hide();
        $('.context-status-text').text('Context: -- / -- (--%)');
    }
}

/* ===== 思考中指示器（挂在 context 行右侧，仅显示「圆点 + Ns」） ===== */
var _ctxThinkingTimerId = null;
var _ctxThinkingStartTime = null;

/**
 * 在 context 行右侧显示思考指示器并开始计时。
 * @param {number} startTime - 计时起点（通常为 sess.messageStartTime），用于显示整段响应的连续耗时
 */
function showContextThinking(startTime) {
    var $status = $('.context-status');
    var $thinking = $('#chatContextThinking');
    if (!$status.length || !$thinking.length) return;

    $status.show();
    $thinking.show();

    _ctxThinkingStartTime = startTime || _ctxThinkingStartTime || Date.now();
    var $timer = $thinking.find('.thinking-timer');
    function tick() {
        var elapsed = Math.floor((Date.now() - _ctxThinkingStartTime) / 1000);
        $timer.text(elapsed + 's');
    }
    tick();
    if (_ctxThinkingTimerId) clearInterval(_ctxThinkingTimerId);
    _ctxThinkingTimerId = setInterval(tick, 1000);
}

/**
 * 隐藏 context 行的思考指示器并停止计时。
 */
function hideContextThinking() {
    if (_ctxThinkingTimerId) { clearInterval(_ctxThinkingTimerId); _ctxThinkingTimerId = null; }
    _ctxThinkingStartTime = null;
    $('#chatContextThinking').hide();
}

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

    function fmt(n) { return n.toString().replace(/\B(?=(\d{3})+(?!\d))/g, '_'); }
    $status.text('Context: ' + fmt(tokens) + ' / ' + fmt(contextLength) + ' (' + percent + '%)');
    $status.show();
}

/**
 * 重置上下文状态指示器（切换会话时调用）
 */
function resetContextIndicator() {
    var $status = $('.context-status');
    if ($status.length) {
        $status.hide();
        $status.text('Context: -- / -- (--%)');
    }
}

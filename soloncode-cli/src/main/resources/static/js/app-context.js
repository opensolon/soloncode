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
        if (n >= 1000000 && n % 1000000 === 0) return (n / 1000000) + 'm';
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
    var $status = $('.context-status');
    if ($status.length) {
        $status.hide();
        $('.context-status-text').text('Context: -- / -- (--%)');
    }
}

/* 思考/等待指示器已迁回消息区（thinking-row / inline-thinking），由 app-message.js 统一管理。 */

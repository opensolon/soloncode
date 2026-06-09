/* ===== 上下文使用情况指示器 ===== */

/**
 * 更新上下文指示器 UI
 * @param {Object} chunk - type 为 context_size 的 WebChunk
 */
function updateContextIndicator(chunk) {
    var $indicator = $('.context-indicator');
    if (!$indicator.length) return;

    var tokenCount = chunk.totalTokens || 0;
    var msgCount = chunk.text ? parseInt(chunk.text) : 0;

    // 格式化 token 数
    var tokenText = tokenCount >= 1000
        ? (tokenCount / 1000).toFixed(1) + 'k'
        : String(tokenCount);

    $indicator.find('.ctx-tokens').text(tokenText + ' tk');
    $indicator.find('.ctx-msgs').text(msgCount);
    $indicator.show();

    // 如果发生了压缩，短暂高亮提示
    if (chunk.args && chunk.args.compressed) {
        $indicator.addClass('ctx-compressed');
        var beforeTk = chunk.args.beforeTokenCount || 0;
        var afterTk = chunk.args.afterTokenCount || 0;
        var beforeMsg = chunk.args.beforeMessageCount || 0;
        var afterMsg = chunk.args.afterMessageCount || 0;
        $indicator.attr('title',
            '上下文已压缩: ' + beforeMsg + '→' + afterMsg + ' msgs, ' +
            (beforeTk >= 1000 ? (beforeTk / 1000).toFixed(1) + 'k' : beforeTk) + '→' +
            (afterTk >= 1000 ? (afterTk / 1000).toFixed(1) + 'k' : afterTk) + ' tokens');
        setTimeout(function() {
            $indicator.removeClass('ctx-compressed');
        }, 3000);
    } else {
        $indicator.attr('title', '上下文: ' + msgCount + ' 条消息, ' + tokenText + ' tokens');
        $indicator.removeClass('ctx-compressed');
    }
}

/**
 * 重置上下文指示器（切换会话时调用）
 */
function resetContextIndicator() {
    var $indicator = $('.context-indicator');
    if ($indicator.length) {
        $indicator.hide();
        $indicator.find('.ctx-msgs').text('0');
        $indicator.find('.ctx-tokens').text('0 tk');
        $indicator.removeClass('ctx-compressed');
        $indicator.attr('title', '');
    }
}

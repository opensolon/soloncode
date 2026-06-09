/* ===== 上下文使用情况指示器（仅显示 token 数） ===== */

/**
 * 更新上下文指示器 UI
 * @param {Object} chunk - type 为 context_size 的 WebChunk
 */
function updateContextIndicator(chunk) {
    var $indicator = $('.context-indicator');
    if (!$indicator.length) return;

    var tokenCount = chunk.totalTokens || 0;

    // 显示原始 token 数（无小数，不转 k），单位 tk
    var tokenText = String(Math.round(tokenCount));

    $indicator.find('.ctx-tokens').text(tokenText);
    $indicator.show();

    // 如果发生了压缩，短暂高亮提示
    if (chunk.args && chunk.args.compressed) {
        $indicator.addClass('ctx-compressed');
        var beforeTk = Math.round(chunk.args.beforeTokenCount || 0);
        var afterTk = Math.round(chunk.args.afterTokenCount || 0);
        $indicator.attr('title',
            '上下文已压缩: ' + beforeTk + '→' + afterTk + ' tokens');
        setTimeout(function() {
            $indicator.removeClass('ctx-compressed');
        }, 3000);
    } else {
        $indicator.attr('title', '上下文: ' + tokenText + ' tokens');
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
        $indicator.find('.ctx-tokens').text('0');
        $indicator.removeClass('ctx-compressed');
        $indicator.attr('title', '');
    }
}

/* ===== app-base.js ===== */
/* DOM引用 + 状态 + 工具函数（最先加载，无依赖） */

/* ===== DOM ===== */
var welcomeView = document.getElementById('welcomeView');
var chatView = document.getElementById('chatView');
var messagesWrap = document.getElementById('messagesWrap');
var welcomeInput = document.getElementById('welcomeInput');
var welcomeSendBtn = document.getElementById('welcomeSendBtn');
var chatInput = document.getElementById('chatInput');
var chatSendBtn = document.getElementById('chatSendBtn');
var themeBtn = document.getElementById('themeBtn');
var themeIcon = document.getElementById('themeIcon');
var newChatBtn = document.getElementById('newChatBtn');
var historyList = document.getElementById('historyList');
var feishuHeaderBtn = document.getElementById('feishuHeaderBtn');
var dingtalkHeaderBtn = document.getElementById('dingtalkHeaderBtn');

/* ===== Constants ===== */
var DOTS_HTML = '<span class="thinking-dots"><span></span><span></span><span></span></span>';

/* ===== Per-Session State ===== */
function SessionState(sessionId) {
    this.sessionId = sessionId;
    this.container = $('<div>')[0];
    $(this.container).addClass('messages-inner');
    $(this.container).hide();
    $(messagesWrap).append(this.container);
    // 监听会话容器高度变化，异步内容增高时保持贴底
    if (typeof observeMessagesHeight === 'function') {
        observeMessagesHeight(this.container);
    }
    this.eventSource = null;
    this.isStreaming = false;
    // 用户点 Stop 后等待服务端 error/trace/done；期间禁止迟到 chunk 再拉起新流
    this.stopRequested = false;
    // 是否接受 agent 流 chunk。finishStream 后关闭，仅 send / user_input / 主动开流时打开，
    // 防止 done 之后的迟到 error/trace/text 把 UI 再次拉起。
    this.acceptingStream = false;
    this.currentBubbleEl = null;
    this.nextContentBlock = false;
    this.reasonBuffer = '';
    this.thinkingBlockEl = null;
    this.thinkingBodyMdEl = null;
    this.thinkingBodyWrapEl = null;
    this.thinkingBuffer = '';
    this.pendingToolCard = null;
    this.pendingToolStarted = false;
    this.approvedToolCard = null;
    this.thinkingEl = null;
    this.inlineThinkingEl = null;
    this.silenceTimer = null;
    this.contentRafId = null;
    this.reasonRafId = null;
    this.thinkingTimerId = null;
    this.thinkingStartTime = null;
    this.inlineThinkingTimerId = null;
    this.inlineThinkingStartTime = null;
    this.messageStartTime = null;
    // Context 条本轮总计时：发送起算，finishStream 定格
    this.roundStartedAt = null;
    this.roundEndedAt = null;
    this.contextTokens = null;
    this.contextLength = null;
    this.userMsgCounter = 0;
    this.reasonGroups = {};  // streamSegmentId::reasonId → { groupEl, thinkingBlockEl }
    this.thinkingGroupEl = null;
    this.taskGroups = {};  // taskId → task-group DOM
    this.taskSegments = {}; // taskId → 聚合输出段；同一任务始终复用同一个 task-group
    // 流事件的主代理展示段。taskId/reasonId 用于归属，主代理段用于保留连续主输出的顺序。
    this.streamSegments = [];
    this.currentStreamSegment = null;
    this.streamSegmentSeq = 0;
}

var sessionMap = {};
var activeSessionId = null;

/* ===== Global State ===== */
var SESSION_ID = 'web-' + Date.now().toString(36);
var isStreaming = false;
var inChatMode = false;
var chatHistory = [];
var currentChatIndex = -1;
var pendingFiles = [];
var MAX_ATTACHMENTS = 10;
var userScrolledUp = false;

var onFinishStream = null;

/* 控制台打印简化开关（与后端 cliPrintSimplified 对齐）。
   true：流式工具卡默认收起；false：默认展开。启动时由 /web/settings/general 回填。 */
var cliPrintSimplified = true;

/* ===== Session Helpers ===== */
function getOrCreateSession(sessionId) {
    if (!sessionMap[sessionId]) {
        sessionMap[sessionId] = new SessionState(sessionId);
    }
    return sessionMap[sessionId];
}

function setActiveSession(sessionId) {
    if (activeSessionId && sessionMap[activeSessionId]) {
        $(sessionMap[activeSessionId].container).hide();
    }
    var sess = getOrCreateSession(sessionId);
    $(sess.container).show();
    activeSessionId = sessionId;
    SESSION_ID = sessionId;
    isStreaming = sess.isStreaming;
    userScrolledUp = false;
    if (isStreaming) setBtnStopMode();
    else setBtnSendMode();
    if (typeof modelsLoaded !== 'undefined' && modelsLoaded) refreshSessionModel(sessionId);
    if (typeof resetContextIndicator === 'function') resetContextIndicator();
}

function deactivateSession() {
    if (activeSessionId && sessionMap[activeSessionId]) {
        $(sessionMap[activeSessionId].container).hide();
    }
    activeSessionId = null;
    isStreaming = false;
    setBtnSendMode();
}

/* ===== Helpers ===== */
/* 程序化贴底期间忽略被动 scroll 事件，避免布局未稳时把 userScrolledUp 误判为 true。
   典型场景：思考组同时追加多张 tool-card、流式 MD 节流晚一帧增高。
   用户真实上滑用 wheel/touch/pointer 识别，不被程序化窗口吞掉。 */
var _programmaticScrollUntil = 0;
var _scrollStickUntil = 0;
var scrollRafPending = false;
var scrollFollowTimer = null;
/* force 贴底默认窗口：覆盖首条切页布局、图片解码、hljs/mermaid 异步增高 */
var SCROLL_FORCE_STICK_MS = 1200;
var SCROLL_STREAM_STICK_MS = 280;
/* 图片 onload / mermaid / hljs / ResizeObserver 等异步增高：单次信号也要跟够一会儿 */
var SCROLL_ASYNC_STICK_MS = 600;
var SCROLL_PROGRAMMATIC_MS = 160;

function _markUserScrolledUp() {
    userScrolledUp = true;
    _scrollStickUntil = 0;
    if (scrollFollowTimer) {
        clearTimeout(scrollFollowTimer);
        scrollFollowTimer = null;
    }
}

function _syncUserScrollFromGap() {
    if (!messagesWrap) return;
    var gap = messagesWrap.scrollHeight - messagesWrap.scrollTop - messagesWrap.clientHeight;
    if (gap > 80) {
        _markUserScrolledUp();
    } else {
        userScrolledUp = false;
    }
}

// 滚轮 / 触控：立即识别用户意图（优先于程序化贴底）
$(messagesWrap).on('wheel', function(e) {
    var dy = (e.originalEvent && e.originalEvent.deltaY) || 0;
    if (dy < 0) {
        _markUserScrolledUp();
    } else if (dy > 0) {
        // 向下滚时按实际 gap 同步；到了底部则恢复粘底
        requestAnimationFrame(_syncUserScrollFromGap);
    }
});
$(messagesWrap).on('touchstart', function() {
    // 触控开始后的 scroll 视为用户操作，短暂关闭程序化忽略
    _programmaticScrollUntil = 0;
});
$(messagesWrap).on('scroll', function() {
    if (Date.now() < _programmaticScrollUntil) return;
    _syncUserScrollFromGap();
});

function _applyScrollBottom() {
    if (!messagesWrap || userScrolledUp) return;
    _programmaticScrollUntil = Date.now() + SCROLL_PROGRAMMATIC_MS;
    messagesWrap.scrollTop = messagesWrap.scrollHeight;
}

/**
 * 内容异步增高时补贴底（图片 onload / mermaid / hljs / ResizeObserver）。
 * 仅在用户未主动上滑时生效，不 force 清掉 userScrolledUp。
 */
function scheduleScrollToBottom() {
    if (userScrolledUp) return;
    // 异步增高可能只触发一次信号（如图片 load），用更长窗口让 followTick 跟上
    _scrollStickUntil = Math.max(_scrollStickUntil, Date.now() + SCROLL_ASYNC_STICK_MS);
    scrollToBottom(false);
}

/**
 * 监听消息容器高度变化：图片解码、异步图表、字体回流后自动贴底。
 * 用户上滑后不打扰；回到底部后由 scroll 逻辑恢复粘底。
 */
var _messagesResizeObserver = null;
function observeMessagesHeight(el) {
    if (!el || typeof ResizeObserver === 'undefined') return;
    if (!_messagesResizeObserver) {
        _messagesResizeObserver = new ResizeObserver(function() {
            if (userScrolledUp) return;
            // 只延长粘底窗口并请求贴底；scrollToBottom 内部合并 RAF
            scheduleScrollToBottom();
        });
    }
    try {
        _messagesResizeObserver.observe(el);
    } catch (e) {}
}
// 列表根容器：会话增高、滚动条出现导致 clientHeight 变化时也补贴底
if (messagesWrap) observeMessagesHeight(messagesWrap);
 
 /**
 * 贴底滚动（流式粘底）。
 * - force：强制贴底并清除 userScrolledUp
 * - 同一帧多次调用合并为一次 RAF
 * - 粘底窗口内会在高度继续变化时再补滚（多 tool-call / 思考收起 / 节流 MD 增高）
 */
function scrollToBottom(force) {
    if (!force && userScrolledUp) return;
    if (force) {
        userScrolledUp = false;
        // force 时给更长粘底窗口，覆盖首条切页、图片解码、finishThinking + 多 tool 连续插入
        _scrollStickUntil = Math.max(_scrollStickUntil, Date.now() + SCROLL_FORCE_STICK_MS);
    } else if (!userScrolledUp) {
        // 普通流式输出：短粘底，覆盖同帧后到的布局增高
        _scrollStickUntil = Math.max(_scrollStickUntil, Date.now() + SCROLL_STREAM_STICK_MS);
    }
    if (scrollRafPending) return;
    scrollRafPending = true;
    requestAnimationFrame(function() {
        scrollRafPending = false;
        if (userScrolledUp) return;
        _applyScrollBottom();
        // 双 RAF：等本帧布局（多 DOM 插入）完成后再贴一次
        requestAnimationFrame(function() {
            if (userScrolledUp) return;
            _applyScrollBottom();
        });
        // 短窗口跟随：处理节流 MD / 多 tool 连续增高 / 异步渲染
        // 每次 tick 读最新 _scrollStickUntil，便于图片/ResizeObserver 延长窗口后继续跟
        if (scrollFollowTimer) clearTimeout(scrollFollowTimer);
        function followTick() {
            scrollFollowTimer = null;
            if (userScrolledUp) return;
            if (Date.now() > _scrollStickUntil) return;
            _applyScrollBottom();
            if (Date.now() < _scrollStickUntil && !userScrolledUp) {
                scrollFollowTimer = setTimeout(followTick, 48);
            }
        }
        scrollFollowTimer = setTimeout(followTick, 48);
    });
}

function resetStreamState(sess) {
    sess.currentBubbleEl = null;
    sess.nextContentBlock = false;
    sess.pendingToolStarted = false;
    sess.pendingToolCards = {};
    sess.reasonBuffer = '';
    sess.thinkingBlockEl = null;
    sess.thinkingBodyMdEl = null;
    sess.thinkingBodyWrapEl = null;
    sess.thinkingBuffer = '';
    sess.thinkingGroupEl = null;
    sess.taskGroups = {};
    sess.taskSegments = {};
    sess.streamSegments = [];
    sess.currentStreamSegment = null;
    sess.streamSegmentSeq = 0;
    // 清空流式 chunk 批处理队列
    sess._chunkQueue = [];
    sess._chunkDrainScheduled = false;
    // Cancel per-reasonId RAF IDs before clearing
    for (var _rid in sess.reasonGroups) {
        if (sess.reasonGroups[_rid].reasonRafId) {
            cancelAnimationFrame(sess.reasonGroups[_rid].reasonRafId);
        }
    }
    sess.reasonGroups = {};
    sess.pendingReasonWhitespace = {};
    sess.pendingGroupWhitespace = {};
    sess.pendingThinkingWhitespace = '';
    if (sess.contentRafId) { cancelAnimationFrame(sess.contentRafId); sess.contentRafId = null; }
    if (sess.reasonRafId) { cancelAnimationFrame(sess.reasonRafId); sess.reasonRafId = null; }
}

function setBtnStopMode() {
    chatSendBtn.disabled = false;
    $(chatSendBtn).addClass('stop-mode');
    $(chatSendBtn).html('<div class="stop-icon"></div>');
    chatSendBtn.title = '停止生成';
}
function setBtnSendMode() {
    $(chatSendBtn).removeClass('stop-mode');
    $(chatSendBtn).html('<i class="layui-icon layui-icon-release"></i>');
    chatSendBtn.title = '发送';
    chatSendBtn.disabled = false;
}

function autoResize(el) {
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 140) + 'px';
}

function escapeHtml(str) {
    // 字符串替换，避免热路径反复 createElement
    return String(str == null ? '' : str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function formatFileSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function formatMsgTime(ts) {
    if (!ts) return '';
    var d = new Date(typeof ts === 'number' ? ts : parseInt(ts));
    if (isNaN(d.getTime())) return '';
    var hh = String(d.getHours()).padStart(2, '0');
    var mm = String(d.getMinutes()).padStart(2, '0');
    var now = new Date();
    var sameDay = d.getFullYear() === now.getFullYear()
        && d.getMonth() === now.getMonth()
        && d.getDate() === now.getDate();
    if (sameDay) return hh + ':' + mm;
    var yyyy = d.getFullYear();
    var MM = String(d.getMonth() + 1).padStart(2, '0');
    var dd = String(d.getDate()).padStart(2, '0');
    return yyyy + '-' + MM + '-' + dd + ' ' + hh + ':' + mm;
}

function getInputText() {
    if (inChatMode) return chatInput.value.trim();
    return welcomeInput.value.trim();
}
function clearInput() {
    if (inChatMode) { chatInput.value = ''; chatInput.style.height = 'auto'; }
    else { welcomeInput.value = ''; welcomeInput.style.height = 'auto'; }
}

/* ===== Toast Notification ===== */
var toastContainer = null;
function showToast(message, type, duration) {
    if (!toastContainer) {
        toastContainer = $('<div>')[0];
        $(toastContainer).addClass('toast-container');
        $('body').append(toastContainer);
    }
    var item = $('<div>')[0];
    $(item).addClass('toast-item ' + (type || 'info'));
    var icons = { success: '\u2714', error: '\u2716', info: '\u2139' };
    $(item).html('<span>' + (icons[type] || icons.info) + '</span><span>' + escapeHtml(message) + '</span>');
    $(toastContainer).append(item);
    setTimeout(function() {
        $(item).addClass('leaving');
        setTimeout(function() {
            if (item.parentNode) $(item).remove();
        }, 250);
    }, duration || 3000);
}

/* ===== Network Status Bar ===== */
var networkBar = null;
function showNetworkBar(type, message) {
    if (!networkBar) {
        networkBar = $('<div>')[0];
        $(networkBar).addClass('network-bar');
        $('body').append(networkBar);
    }
    $(networkBar).attr('class', 'network-bar show ' + type);
    $(networkBar).text(message);
}
function hideNetworkBar() {
    if (networkBar) {
        $(networkBar).attr('class', 'network-bar');
    }
}

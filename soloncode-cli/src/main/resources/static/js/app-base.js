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
    this.eventSource = null;
    this.isStreaming = false;
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
    this.thinkingBlockTimerId = null;
    this.thinkingBlockStartTime = null;
    this.messageStartTime = null;
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
$(messagesWrap).on('scroll', function() {
    var gap = messagesWrap.scrollHeight - messagesWrap.scrollTop - messagesWrap.clientHeight;
    userScrolledUp = gap > 80;
});
var scrollRafPending = false;
function scrollToBottom(force) {
    if (!force && userScrolledUp) return;
    if (force) userScrolledUp = false;
    if (scrollRafPending) return;
    scrollRafPending = true;
    requestAnimationFrame(function() {
        scrollRafPending = false;
        messagesWrap.scrollTop = messagesWrap.scrollHeight;
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
    var div = $('<div>')[0];
    $(div).text(str);
    return div.innerHTML;
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

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
    this.container = document.createElement('div');
    this.container.className = 'messages-inner';
    this.container.style.display = 'none';
    messagesWrap.appendChild(this.container);
    this.eventSource = null;
    this.isStreaming = false;
    this.currentBubbleEl = null;
    this.reasonBuffer = '';
    this.thinkingBlockEl = null;
    this.thinkingBodyMdEl = null;
    this.thinkingBodyWrapEl = null;
    this.thinkingBuffer = '';
    this.pendingToolCard = null;
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
}

var sessionMap = {};
var activeSessionId = null;

/* ===== Global State ===== */
var SESSION_ID = 'web-' + Date.now().toString(36);
var isStreaming = false;
var inChatMode = false;
var chatHistory = [];
var currentChatIndex = -1;
var pendingFiles = []; // [{ type: 'image'|'file', name, size, file, dataUrl?, attachmentsType: 'image'|'file' }, ...]
var MAX_ATTACHMENTS = 10;
var userScrolledUp = false;

// 回调占位：由 app-streaming.js 注册，供 app-message.js 中 HITL 调用
var onFinishStream = null;

/* ===== Session Helpers ===== */
function getOrCreateSession(sessionId) {
    if (!sessionMap[sessionId]) {
        sessionMap[sessionId] = new SessionState(sessionId);
    }
    return sessionMap[sessionId];
}

function setActiveSession(sessionId) {
    if (activeSessionId && sessionMap[activeSessionId]) {
        sessionMap[activeSessionId].container.style.display = 'none';
    }
    var sess = getOrCreateSession(sessionId);
    sess.container.style.display = '';
    activeSessionId = sessionId;
    SESSION_ID = sessionId;
    isStreaming = sess.isStreaming;
    userScrolledUp = false;
    if (isStreaming) setBtnStopMode();
    else setBtnSendMode();
    // Refresh model selector for this session
    if (typeof modelsLoaded !== 'undefined' && modelsLoaded) refreshSessionModel(sessionId);
}

function deactivateSession() {
    if (activeSessionId && sessionMap[activeSessionId]) {
        sessionMap[activeSessionId].container.style.display = 'none';
    }
    activeSessionId = null;
    isStreaming = false;
    setBtnSendMode();
}

/* ===== Helpers ===== */
messagesWrap.addEventListener('scroll', function() {
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
    sess.reasonBuffer = '';
    sess.thinkingBlockEl = null;
    sess.thinkingBodyMdEl = null;
    sess.thinkingBodyWrapEl = null;
    sess.thinkingBuffer = '';
    if (sess.contentRafId) { cancelAnimationFrame(sess.contentRafId); sess.contentRafId = null; }
    if (sess.reasonRafId) { cancelAnimationFrame(sess.reasonRafId); sess.reasonRafId = null; }
}

function setBtnStopMode() {
    chatSendBtn.disabled = false;
    chatSendBtn.classList.add('stop-mode');
    chatSendBtn.innerHTML = '<div class="stop-icon"></div>';
    chatSendBtn.title = '停止生成';
}
function setBtnSendMode() {
    chatSendBtn.classList.remove('stop-mode');
    chatSendBtn.innerHTML = '<i class="layui-icon layui-icon-release"></i>';
    chatSendBtn.title = '发送';
    chatSendBtn.disabled = false;
}

function autoResize(el) {
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 140) + 'px';
}

function escapeHtml(str) {
    var div = document.createElement('div');
    div.textContent = str;
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
    return hh + ':' + mm;
}

function getInputText() {
    if (inChatMode) return chatInput.value.trim();
    return welcomeInput.value.trim();
}
function clearInput() {
    if (inChatMode) { chatInput.value = ''; chatInput.style.height = 'auto'; }
    else { welcomeInput.value = ''; welcomeInput.style.height = 'auto'; }
}

// 初始化默认会话（延迟到最后一个文件加载完毕后由 app-streaming.js 调用）
// setActiveSession(SESSION_ID);

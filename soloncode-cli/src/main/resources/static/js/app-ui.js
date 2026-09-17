/* ===== app-ui.js ===== */
/* 界面交互：附件 + 主题 + 视图 + 语音 + 侧栏 + Markdown */
/* 依赖：app-base.js */

/* ===== Attachment Helpers ===== */
var newChatAttachmentsWrap = $('#newChatAttachmentsWrap');
var chatAttachmentsWrap = $('#chatAttachmentsWrap');

function handlePaste(e) {
    var clipboard = e.clipboardData || e.originalEvent.clipboardData;
    if (!clipboard) return;

    var items = clipboard.items;
    for (var i = 0; i < items.length; i++) {
        if (items[i].type.indexOf('image') !== -1) {
            e.preventDefault();
            var file = items[i].getAsFile();
            processSelectedFile(file, 'image');
            return;
        }
    }

    // Handle HTML paste: convert to text preserving formatting
    var htmlData = clipboard.getData('text/html');
    if (htmlData) {
        e.preventDefault();
        var text = clipboard.getData('text/plain') || '';
        // If plain text has content, use it directly (preserves newlines/indentation)
        // textarea.value = text already preserves formatting
        var textarea = e.target;
        var start = textarea.selectionStart;
        var end = textarea.selectionEnd;
        var before = textarea.value.substring(0, start);
        var after = textarea.value.substring(end);
        textarea.value = before + text + after;
        textarea.selectionStart = textarea.selectionEnd = start + text.length;
        autoResize(textarea);
        // Trigger input event for command completion
        $(textarea).trigger('input');
    }
}

function getAttachmentsWrap() {
    return inChatMode ? chatAttachmentsWrap : newChatAttachmentsWrap;
}

function renderAttachments() {
    // Render both wraps to keep them in sync when switching views
    renderAttachmentsWrap(newChatAttachmentsWrap);
    renderAttachmentsWrap(chatAttachmentsWrap);
}

function renderAttachmentsWrap(wrap) {
    wrap.html('');
    if (pendingFiles.length === 0) {
        wrap.removeClass('has-items');
        return;
    }
    wrap.addClass('has-items');
    for (var i = 0; i < pendingFiles.length; i++) {
        var item = pendingFiles[i];
        var el = document.createElement('div');
        el.className = 'attachment-item';
        var typeTag = '<span class="attachment-type-tag ' + (item.attachmentsType || 'file') + '">' + (item.attachmentsType === 'image' ? I18n.t('attach.typeMultimodal') : I18n.t('attach.typeFile')) + '</span>';
        if (item.type === 'image') {
            $(el).html('<img src="' + item.dataUrl + '"/>'
                + typeTag
                + '<button class="attachment-item-remove" data-idx="' + i + '">&times;</button>');
        } else {
            $(el).html('<div class="attachment-item-file">'
                + '<span class="file-icon">📎</span>'
                + '<span class="file-name">' + escapeHtml(item.name) + '</span>'
                + '</div>'
                + typeTag
                + '<button class="attachment-item-remove" data-idx="' + i + '">&times;</button>');
        }
        wrap.append(el);
    }
}

function clearAttachmentPreview() {
    pendingFiles = [];
    renderAttachments();
}

function removeAttachment(idx) {
    pendingFiles.splice(idx, 1);
    renderAttachments();
}

function processSelectedFile(file, attachmentsType) {
    if (!file) return;
    if (pendingFiles.length >= MAX_ATTACHMENTS) return;

    if (attachmentsType === 'image') {
        // Image attachment: always treated as multimodal image
        var reader = new FileReader();
        reader.onload = function(evt) {
            pendingFiles.push({ type: 'image', name: file.name, size: file.size, file: file, dataUrl: evt.target.result, attachmentsType: 'image' });
            renderAttachments();
        };
        reader.readAsDataURL(file);
    } else if (file.type.indexOf('image') !== -1) {
        // File attachment + image file: show preview but mark as file type
        var reader = new FileReader();
        reader.onload = function(evt) {
            pendingFiles.push({ type: 'image', name: file.name, size: file.size, file: file, dataUrl: evt.target.result, attachmentsType: 'file' });
            renderAttachments();
        };
        reader.readAsDataURL(file);
    } else {
        pendingFiles.push({ type: 'file', name: file.name, size: file.size, file: file, attachmentsType: 'file' });
        renderAttachments();
    }
}

function processSelectedFiles(fileList, attachmentsType) {
    for (var i = 0; i < fileList.length; i++) {
        if (pendingFiles.length >= MAX_ATTACHMENTS) break;
        processSelectedFile(fileList[i], attachmentsType);
    }
}

$(newChatInput).on('paste', handlePaste);
$(chatInput).on('paste', handlePaste);

/* ===== Drag & Drop File Upload ===== */
(function() {
    var newChatDropZone = $('#newChatDropZone');
    var chatDropZone = $('#chatDropZone');
    var newChatDropOverlay = $('#newChatDropOverlay');
    var chatDropOverlay = $('#chatDropOverlay');

    // Counter to track nested enter/leave events (child elements fire their own events)
    var newChatDragCounter = 0;
    var chatDragCounter = 0;

    function showOverlay(overlay) {
        overlay.addClass('active');
    }

    function hideOverlay(overlay) {
        overlay.removeClass('active');
    }

    function handleDrop(e, overlay, counterReset) {
        e.preventDefault();
        e.stopPropagation();
        counterReset.val = 0;
        hideOverlay(overlay);

        var dt = e.dataTransfer || (e.originalEvent && e.originalEvent.dataTransfer);
        var files = dt && dt.files;
        if (!files || files.length === 0) return;

        if (pendingFiles.length >= MAX_ATTACHMENTS) {
            showToast((window.I18n ? window.I18n.t('toast.attachLimit', { max: MAX_ATTACHMENTS }) : ('\u9644\u4ef6\u6570\u91cf\u5df2\u8fbe\u4e0a\u9650\uff08' + MAX_ATTACHMENTS + '\u4e2a\uff09')), 'error');
            return;
        }

        // Separate files into images and non-images for proper processing
        for (var i = 0; i < files.length; i++) {
            if (pendingFiles.length >= MAX_ATTACHMENTS) {
            showToast((window.I18n ? window.I18n.t('toast.attachLimitPartial', { max: MAX_ATTACHMENTS }) : ('\u90e8\u5206\u6587\u4ef6\u672a\u6dfb\u52a0\uff0c\u9644\u4ef6\u6570\u91cf\u5df2\u8fbe\u4e0a\u9650\uff08' + MAX_ATTACHMENTS + '\u4e2a\uff09')), 'error');
                break;
            }
            var file = files[i];
            var isImage = file.type.indexOf('image/') === 0;
            processSelectedFile(file, isImage ? 'image' : 'file');
        }
    }

    function bindDropZone(zone, overlay, counter) {
        // Prevent default browser behavior (opening the file)
        zone.on('dragenter', function(e) {
            e.preventDefault();
            e.stopPropagation();
            counter.val++;
            showOverlay(overlay);
        });

        zone.on('dragover', function(e) {
            e.preventDefault();
            e.stopPropagation();
            // Keep overlay visible during drag over
        });

        zone.on('dragleave', function(e) {
            e.preventDefault();
            e.stopPropagation();
            counter.val--;
            if (counter.val <= 0) {
                counter.val = 0;
                hideOverlay(overlay);
            }
        });

        zone.on('drop', function(e) {
            handleDrop(e, overlay, counter);
        });
    }

    bindDropZone(newChatDropZone, newChatDropOverlay, { val: newChatDragCounter });
    bindDropZone(chatDropZone, chatDropOverlay, { val: chatDragCounter });
})();

// Attachment remove buttons - use event delegation on both wraps
newChatAttachmentsWrap.on('click', function(e) {
    var btn = e.target.closest('.attachment-item-remove');
    if (btn) removeAttachment(parseInt(btn.getAttribute('data-idx')));
});
chatAttachmentsWrap.on('click', function(e) {
    var btn = e.target.closest('.attachment-item-remove');
    if (btn) removeAttachment(parseInt(btn.getAttribute('data-idx')));
});

// Attach button handlers
$('#newChatAttachBtn').on('click', function(e) {
    e.stopPropagation();
    $('#newChatAttachInput')[0].click();
});
$('#chatAttachBtn').on('click', function(e) {
    e.stopPropagation();
    $('#chatAttachInput')[0].click();
});
$('#newChatAttachInput').on('change', function(e) {
    if (e.target.files && e.target.files.length > 0) processSelectedFiles(e.target.files, 'file');
    e.target.value = '';
});
$('#chatAttachInput').on('change', function(e) {
    if (e.target.files && e.target.files.length > 0) processSelectedFiles(e.target.files, 'file');
    e.target.value = '';
});

// Image button handlers
$('#newChatImageBtn').on('click', function(e) {
    e.stopPropagation();
    $('#newChatImageInput')[0].click();
});
$('#chatImageBtn').on('click', function(e) {
    e.stopPropagation();
    $('#chatImageInput')[0].click();
});
$('#newChatImageInput').on('change', function(e) {
    if (e.target.files && e.target.files.length > 0) processSelectedFiles(e.target.files, 'image');
    e.target.value = '';
});
$('#chatImageInput').on('change', function(e) {
    if (e.target.files && e.target.files.length > 0) processSelectedFiles(e.target.files, 'image');
    e.target.value = '';
});

/* ===== Marked ===== */
function escapeHtmlAttr(value) {
    return String(value == null ? '' : value)
        .replace(/&/g, '&amp;')
        .replace(/"/g, '&quot;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

function createMarkdownRenderer() {
    var renderer = new marked.Renderer();

    renderer.link = function (token) {
        var href = token && typeof token === 'object' ? token.href : token;
        var title = token && typeof token === 'object' ? token.title : '';
        var text = token && typeof token === 'object' ? token.text : '';
        var safeHref = href || '';
        var safeTitle = title ? ' title="' + escapeHtmlAttr(title) + '"' : '';
        var safeText = text || '';

        return '<a href="' + escapeHtmlAttr(safeHref) + '" target="_blank" rel="noopener noreferrer"' + safeTitle + '>' + safeText + '</a>';
    };

    // 多工作区隔离：Markdown 内嵌图片 ![](path) 的 <img src> 由浏览器直发，
    // 绕过 fetch/XHR 劫持层，必须重写为 read-raw 接口并显式携带 workspaceId。
    renderer.image = function (token) {
        var href = token && typeof token === 'object' ? token.href : token;
        var title = (token && typeof token === 'object' ? token.title : '') || '';
        var text = (token && typeof token === 'object' ? token.text : '') || '';
        var src = String(href || '');
        // 已是完整 URL（http/https/data）则不重写
        if (!/^(https?:|data:)/i.test(src)) {
            src = '/web/chat/filer/read-raw?path=' + encodeURIComponent(src) + window.wsAndSuffix();
        }
        return '<img src="' + escapeHtmlAttr(src) + '" alt="' + escapeHtmlAttr(text) + '"'
            + (title ? ' title="' + escapeHtmlAttr(title) + '"' : '') + ' style="max-width:100%">';
    };

    // 防止原始 HTML 标签破坏页面布局：转义 < 和 >，避免被浏览器解析为 DOM 元素
    // marked v15 中 renderer 方法接收 token 对象，需通过 .text 获取原始内容
    renderer.html = function (token) {
        var text = (token && typeof token === 'object' ? (token.text || token.raw || '') : (token || ''));
        return text.replace(/</g, '&lt;').replace(/>/g, '&gt;');
    };

    return renderer;
}

if (typeof marked !== 'undefined') { marked.setOptions({ breaks: true, gfm: true, renderer: createMarkdownRenderer() }); }

/* ===== Lazy script loader (mermaid / highlight / qrcode / settings) ===== */
var _scriptLoaders = {};
var _loadedScripts = window.__loadedScripts || (window.__loadedScripts = {});
/**
 * 按需加载脚本（全局去重）。
 * cb(err)：成功 err 为 null；失败也会回调，便于上层提示/重试。
 * 失败不写入成功标记，允许后续 loadScriptOnce 重试。
 */
function loadScriptOnce(src, cb) {
    if (_loadedScripts[src] === true) {
        if (cb) cb(null);
        return;
    }
    if (_scriptLoaders[src]) {
        if (cb) _scriptLoaders[src].push(cb);
        return;
    }
    _scriptLoaders[src] = cb ? [cb] : [];
    var s = document.createElement('script');
    s.src = src;
    s.async = true;
    s.onload = function() {
        _loadedScripts[src] = true;
        var cbs = _scriptLoaders[src] || [];
        delete _scriptLoaders[src];
        for (var i = 0; i < cbs.length; i++) {
            if (cbs[i]) {
                try { cbs[i](null); } catch (e) { console.warn('[loadScriptOnce]', src, e); }
            }
        }
    };
    s.onerror = function() {
        console.warn('[loadScriptOnce] failed:', src);
        var cbs = _scriptLoaders[src] || [];
        delete _scriptLoaders[src];
        // 不标记成功，允许下次重试
        delete _loadedScripts[src];
        var err = new Error('Failed to load ' + src);
        for (var i = 0; i < cbs.length; i++) {
            if (cbs[i]) {
                try { cbs[i](err); } catch (e) { console.warn('[loadScriptOnce]', src, e); }
            }
        }
    };
    (document.head || document.documentElement).appendChild(s);
}

var _mdCache = new Map();
var _MD_CACHE_MAX = 80;
var _MD_CACHE_MAX_LEN = 12000; // 仅缓存完成态中等长度消息，避免流式碎片污染
function renderMd(text) {
    if (!text) return '';
    if (typeof marked === 'undefined') {
        return String(text).replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\n/g, '<br>');
    }
    // 完成态缓存：流式路径已改走轻量渲染，这里只服务历史/结束态稳定文本
    if (text.length <= _MD_CACHE_MAX_LEN) {
        var cached = _mdCache.get(text);
        if (cached) return cached;
        var html = marked.parse(text);
        _mdCache.set(text, html);
        if (_mdCache.size > _MD_CACHE_MAX) {
            var firstKey = _mdCache.keys().next().value;
            _mdCache.delete(firstKey);
        }
        return html;
    }
    return marked.parse(text);
}

/* 流式降级 HTML：仅当 marked 不可用时作 fallback */
function lightStreamHtml(text) {
    var s = String(text == null ? '' : text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
    s = s.replace(/`([^`\n]+)`/g, '<code>$1</code>');
    s = s.replace(/\*\*([^\*\n]+)\*\*/g, '<strong>$1</strong>');
    s = s.replace(/\n/g, '<br>');
    return s;
}

/**
 * 补全流式中尚未闭合的 Markdown 结构，使 marked 能产出与主流 coding agent
 * 一致的结构化预览（代码块/标题/列表等边出边排）。
 * 仅用于预览，不会写回 buffer。
 */
function prepareStreamMarkdown(text) {
    var s = String(text == null ? '' : text);
    if (!s) return s;

    // 未闭合栅栏代码块：补一个闭合栅栏，便于渲成 <pre><code>
    // 支持 ``` 与 ~~~ ；按行首统计，避免误伤行内反引号
    var fenceLines = s.match(/^(?:```|~~~)/gm);
    if (fenceLines && fenceLines.length % 2 === 1) {
        var lastFence = fenceLines[fenceLines.length - 1];
        var fenceMark = lastFence.indexOf('~') === 0 ? '~~~' : '```';
        if (!/\n$/.test(s)) s += '\n';
        s += fenceMark;
    }

    return s;
}

/* 流式渲染节流状态：各元素独立节流，避免每 token 全量 parse */
var _streamMdState = typeof WeakMap !== 'undefined' ? new WeakMap() : null;
var _streamMdStateFallback = _streamMdState ? null : [];
function getStreamMdState(el) {
    if (_streamMdState) {
        var st = _streamMdState.get(el);
        if (!st) {
            st = { lastAt: 0, timer: null, pending: null, lastLen: 0 };
            _streamMdState.set(el, st);
        }
        return st;
    }
    for (var i = 0; i < _streamMdStateFallback.length; i++) {
        if (_streamMdStateFallback[i].el === el) return _streamMdStateFallback[i].st;
    }
    var created = { lastAt: 0, timer: null, pending: null, lastLen: 0 };
    _streamMdStateFallback.push({ el: el, st: created });
    return created;
}
function clearStreamMdState(el) {
    if (!el) return;
    var st = null;
    if (_streamMdState) {
        st = _streamMdState.get(el);
        if (st) _streamMdState.delete(el);
    } else if (_streamMdStateFallback) {
        for (var i = _streamMdStateFallback.length - 1; i >= 0; i--) {
            if (_streamMdStateFallback[i].el === el) {
                st = _streamMdStateFallback[i].st;
                _streamMdStateFallback.splice(i, 1);
                break;
            }
        }
    }
    if (st && st.timer) {
        clearTimeout(st.timer);
        st.timer = null;
    }
}

/** 根据文本长度动态调节流间隔：短文更跟手，长文降低 parse 频率 */
function streamMdIntervalMs(len) {
    if (len > 12000) return 160;
    if (len > 6000) return 120;
    if (len > 2500) return 90;
    return 50; // 短回复更贴近主流 agent 的“边出边排”
}

function paintStreamMarkdown(el, text) {
    var raw = text == null ? '' : String(text);
    if (typeof marked === 'undefined') {
        el.innerHTML = lightStreamHtml(raw);
        return;
    }
    try {
        // 流式不走 renderMd 缓存，避免不完整文本污染完成态缓存
        el.innerHTML = marked.parse(prepareStreamMarkdown(raw));
    } catch (e) {
        el.innerHTML = lightStreamHtml(raw);
    }
}

/**
 * 流式阶段：节流后的完整 GFM Markdown（与 Cursor / Claude / ChatGPT 一致）。
 * - 有：标题、列表、代码块外框、链接、表格等结构
 * - 无：语法高亮、mermaid、代码块按钮（结束态 finalize 再补）
 */
function renderMdStreaming(el, text) {
    if (!el) return;
    el.classList.add('md-streaming');
    var raw = text == null ? '' : String(text);
    el.setAttribute('data-md-stream-raw', raw);

    var st = getStreamMdState(el);
    st.pending = raw;
    st.lastLen = raw.length;

    var now = Date.now();
    var interval = streamMdIntervalMs(raw.length);
    var elapsed = now - (st.lastAt || 0);

    function flush() {
        st.timer = null;
        st.lastAt = Date.now();
        if (st.pending == null) return;
        // finalize 后可能已离开流式，避免覆盖完成态 DOM
        if (!el.classList.contains('md-streaming')) return;
        paintStreamMarkdown(el, st.pending);
        // 节流延迟绘制后补一次贴底，避免高度变化后停在半截
        if (typeof scrollToBottom === 'function' && typeof activeSessionId !== 'undefined') {
            try { scrollToBottom(); } catch (e) {}
        }
    }

    // 首帧或间隔已到：立即刷
    if (!st.lastAt || elapsed >= interval) {
        if (st.timer) {
            clearTimeout(st.timer);
            st.timer = null;
        }
        flush();
        return;
    }
    // 否则合并到下一个节流窗口（保留最新 pending）
    if (!st.timer) {
        st.timer = setTimeout(flush, Math.max(0, interval - elapsed));
    }
}

/* 流结束/历史消息：升级为完整 Markdown + 高亮 + mermaid（同文案可幂等跳过） */
function finalizeMdElement(el, text) {
    if (!el) return;
    // 取消流式节流，防止迟迟到来的 paint 覆盖完成态
    clearStreamMdState(el);

    var raw = text == null ? '' : String(text);
    var alreadyDone = !el.classList.contains('md-streaming')
        && el.getAttribute('data-md-raw') === raw
        && el.innerHTML
        && raw !== '';
    if (alreadyDone) return;

    el.classList.remove('md-streaming');
    el.removeAttribute('data-md-stream-raw');
    if (text != null) {
        el.setAttribute('data-md-raw', raw);
        el.innerHTML = renderMd(raw);
    }
    if (typeof addCodeBlockButtons === 'function') addCodeBlockButtons(el);
    if (typeof highlightCodeBlocks === 'function') highlightCodeBlocks(el);
    if (typeof processMermaidBlocks === 'function') processMermaidBlocks(el);
    // 完成态 MD 替换后高度常变；hljs/mermaid 内部还会再 schedule，这里先补一次
    if (typeof scheduleScrollToBottom === 'function') scheduleScrollToBottom();
}
window.renderMdStreaming = renderMdStreaming;
window.finalizeMdElement = finalizeMdElement;
window.loadScriptOnce = loadScriptOnce;
window.prepareStreamMarkdown = prepareStreamMarkdown;

/* ===== Highlight.js（按需加载） ===== */
function ensureHljs(cb) {
    if (typeof hljs !== 'undefined') {
        if (cb) cb(null);
        return;
    }
    loadScriptOnce('/highlight/highlight.min.js', function(err) {
        if (cb) cb(err || null);
    });
}
window.ensureHljs = ensureHljs;

function highlightCodeBlocks(container) {
    if (!container) return;
    var hasBlocks = container.querySelectorAll
        ? container.querySelectorAll('pre code:not([data-hljs-collected])').length > 0
        : $(container).find('pre code:not([data-hljs-collected])').length > 0;
    if (!hasBlocks) return;

    ensureHljs(function(err) {
        if (err || typeof hljs === 'undefined') return;
        var blocks = $(container).find('pre code:not([data-hljs-collected])');
        if (blocks.length === 0) return;
        blocks.each(function() { this.dataset.hljsCollected = 'true'; });
        function doHighlight() {
            blocks.each(function() {
                if (!this.dataset.hljsHighlighted) {
                    this.dataset.hljsHighlighted = 'true';
                    try { hljs.highlightElement(this); } catch (e) {}
                }
            });
            // 高亮可能改变 pre 高度，补贴底
            if (typeof scheduleScrollToBottom === 'function') scheduleScrollToBottom();
        }
        if (window.requestIdleCallback) {
            requestIdleCallback(doHighlight, { timeout: 300 });
        } else {
            setTimeout(doHighlight, 50);
        }
    });
}

/* ===== Mermaid（按需加载；仅当存在 mermaid 代码块时才下载 3MB 库） ===== */
var __mermaidInited = false;
function initMermaidIfNeeded() {
    if (typeof mermaid === 'undefined' || __mermaidInited) return;
    mermaid.initialize({
        startOnLoad: false,
        theme: (typeof currentTheme !== 'undefined' && currentTheme === 'dark') ? 'dark' : 'default',
        securityLevel: 'loose',
        fontFamily: 'var(--font-sans)',
    });
    __mermaidInited = true;
}
function ensureMermaid(cb) {
    if (typeof mermaid !== 'undefined') {
        initMermaidIfNeeded();
        if (cb) cb(null);
        return;
    }
    loadScriptOnce('/js/mermaid.min.js', function(err) {
        if (err) {
            if (cb) cb(err);
            return;
        }
        initMermaidIfNeeded();
        if (cb) cb(null);
    });
}
window.ensureMermaid = ensureMermaid;

function processMermaidBlocks(container) {
    if (!container) return;
    var blocks = container.querySelectorAll
        ? container.querySelectorAll('pre code.language-mermaid:not([data-mermaid-processed])')
        : [];
    if (!blocks || blocks.length === 0) return;

    ensureMermaid(function(err) {
        if (err || typeof mermaid === 'undefined') return;
        var fresh = container.querySelectorAll('pre code.language-mermaid:not([data-mermaid-processed])');
        if (!fresh.length) return;

        var nodes = [];
        for (var i = 0; i < fresh.length; i++) {
            var codeEl = fresh[i];
            codeEl.setAttribute('data-mermaid-processed', 'true');
            var preEl = codeEl.parentNode;
            var txt = codeEl.textContent.trim();
            if (!txt) continue;

            var div = document.createElement('div');
            div.id = 'm-' + Date.now().toString(36) + '-' + Math.random().toString(36).substr(2, 8);
            div.className = 'mermaid-svg';
            div.style.cssText = 'text-align:center;padding:10px 0;overflow-x:auto;';
            div.textContent = txt;
            preEl.parentNode.replaceChild(div, preEl);
            nodes.push(div);
        }

        if (nodes.length > 0 && mermaid.run) {
            mermaid.run({ nodes: nodes, suppressErrors: true })
                .then(function() {
                    if (typeof scheduleScrollToBottom === 'function') scheduleScrollToBottom();
                })
                .catch(function() {
                    if (typeof scheduleScrollToBottom === 'function') scheduleScrollToBottom();
                });
        } else if (nodes.length > 0) {
            if (typeof scheduleScrollToBottom === 'function') scheduleScrollToBottom();
        }
    });
}

/* ===== QRCode（仅扫码绑定时加载） ===== */
/**
 * 修正 qrcode.min.js 的呈现方式。
 *
 * 库画完 canvas 后，会用 toDataURL() 生成一张 <img> 顶替、并把 canvas 设为
 * display:none。一旦 data: 协议被 CSP、企业网关或浏览器策略拦截，页面上就只
 * 剩一张破图——而 canvas 里其实已经画好了。这里把 makeImage 置空，始终以
 * canvas 作为最终呈现，绕开 data: 依赖。
 */
function patchQrcodeLib() {
    if (typeof QRCode === 'undefined' || !QRCode.prototype) return;
    if (QRCode.prototype.__scKeepCanvas) return;
    QRCode.prototype.makeImage = function() {};
    QRCode.prototype.__scKeepCanvas = true;
}

function ensureQrcode(cb) {
    if (typeof QRCode !== 'undefined') {
        patchQrcodeLib();
        if (cb) cb(null);
        return;
    }
    loadScriptOnce('/js/qrcode.min.js', function(err) {
        if (err) {
            // 懒加载在弱网/代理环境下容易偶发失败，重试一次再判定
            loadScriptOnce('/js/qrcode.min.js', function(err2) {
                if (!err2) patchQrcodeLib();
                if (cb) cb(err2 || null);
            });
            return;
        }
        patchQrcodeLib();
        if (cb) cb(null);
    });
}
window.ensureQrcode = ensureQrcode;

var QR_DATA_IMAGE_RE = /^data:image\//i;
var QR_IMAGE_URL_RE = /\.(png|jpe?g|gif|webp|svg)(\?|#|$)/i;

/**
 * 把二维码内容渲染进容器，自动适配内容形态：
 *
 * <ul>
 *   <li>data:image/... 或图片直链：服务端已给出成品图，直接展示，不做二次编码；</li>
 *   <li>普通 URL / 文本：由 qrcode.min.js 在本地编码成图；</li>
 *   <li>库加载失败或编码失败：退化成可点击链接，用户仍可手动打开完成绑定。</li>
 * </ul>
 *
 * @param $wrap   jQuery 容器
 * @param content 二维码内容
 * @param onFail  失败回调 onFail(reason)，reason 取 'lib'（库加载失败）
 *                / 'encode'（编码失败）/ 'image'（图片加载失败）
 * @return 容器存在且内容非空时返回 true
 */
function renderQrcodeInto($wrap, content, onFail) {
    if (!$wrap || !$wrap.length || !content) return false;

    var fallback = function(reason) {
        if (!$wrap.length) return;
        var style = 'font-size:12px;padding:10px;word-break:break-all;line-height:1.6';
        var html = /^https?:\/\//i.test(content)
            ? '<a href="' + escapeHtmlAttr(content) + '" target="_blank" rel="noopener noreferrer" style="' + style + '">'
              + escapeHtml(content) + '</a>'
            : '<span style="' + style + ';color:#666">' + escapeHtml(content) + '</span>';
        $wrap.html(html);
        if (onFail) onFail(reason);
    };

    // 服务端直接给图：二次编码必然失败（图片数据远超二维码容量），直接展示
    if (QR_DATA_IMAGE_RE.test(content) || QR_IMAGE_URL_RE.test(content)) {
        var $img = $('<img>').attr('alt', 'QR Code')
            .css({ width: '180px', height: '180px', display: 'block' })
            .on('error', function() { fallback('image'); });
        $wrap.html('').append($img);
        $img.attr('src', content);
        return true;
    }

    $wrap.html('');
    ensureQrcode(function(err) {
        if (!$wrap.length) return;
        if (err || typeof QRCode === 'undefined') {
            fallback('lib');
            return;
        }
        try {
            new QRCode($wrap[0], { text: content, width: 180, height: 180 });
        } catch (e) {
            console.warn('[qrcode] encode failed:', e && e.message);
            fallback('encode');
        }
    });
    return true;
}
window.renderQrcodeInto = renderQrcodeInto;

function applyHljsTheme(theme) {
    var $lightLink = $('#hljsLightTheme');
    var $darkLink = $('#hljsDarkTheme');
    if (!$lightLink.length || !$darkLink.length) return;
    if (theme === 'dark') {
        $lightLink.prop('disabled', true).prop('media', 'not all');
        $darkLink.prop('disabled', false).prop('media', 'all');
    } else {
        $darkLink.prop('disabled', true).prop('media', 'not all');
        $lightLink.prop('disabled', false).prop('media', 'all');
    }
}
window.applyHljsTheme = applyHljsTheme;

/* ===== Theme ===== */
var currentTheme = localStorage.getItem('chat-theme') || 'light';
window.currentTheme = currentTheme;
$('body').attr('data-theme', currentTheme);

// Apply initial hljs theme (after currentTheme is defined)
applyHljsTheme(currentTheme);

updateThemeIcon();

$(themeBtn).on('click', function() {
    currentTheme = currentTheme === 'light' ? 'dark' : 'light';
    window.currentTheme = currentTheme;
    $('body').attr('data-theme', currentTheme);
    localStorage.setItem('chat-theme', currentTheme);
    updateThemeIcon();
    applyHljsTheme(currentTheme);
    if (typeof mermaid !== 'undefined') {
        __mermaidInited = false;
        initMermaidIfNeeded();
    }
});
function updateThemeIcon() {
    $(themeIcon).html(currentTheme === 'light' ? '&#xe6c2;' : '&#xe748;');
    $(themeBtn).prop('title', currentTheme === 'light' ? (window.I18n ? window.I18n.t('header.switchToDark') : '\u5207\u6362\u81f3\u6697\u8272') : (window.I18n ? window.I18n.t('header.switchToLight') : '\u5207\u6362\u81f3\u6d45\u8272'));
}
window.updateThemeIcon = updateThemeIcon;
document.addEventListener('i18n:loaded', updateThemeIcon);
document.addEventListener('i18n:switched', updateThemeIcon);

/* ===== Font (user font family + size scale) =====
 * 优先级：用户显式设置（inline style） > 皮肤 CSS > 主题默认（theme.css :root）
 * 字号不直接写死，统一由 theme.css 的 --fs-* 阶梯 * --font-scale 得出。
 *
 * 落点差异（关键，勿随意调整）：
 * - --font-sans / --font-mono 写在 body：皮肤规则的选择器是 body[data-skin][data-theme]，
 *   写在 body 的 inline style 才能盖过皮肤；写到 html 会被 body 上的皮肤声明反超。
 * - --font-scale 必须写在 html(:root)：自定义属性中的 var() 在“声明它的元素”上完成替换，
 *   --fs-* 声明在 :root，替换时只会读取 html 上的 --font-scale。写在 body 对 --fs-* 完全无效
 *   （皮肤若把 --fs-* 声明在 body 上则又会生效，行为随皮肤翻转）。写在 html 两种情况都正确。
 */
var FONT_SCALE_MIN = 0.85;
var FONT_SCALE_MAX = 1.5;
var FONT_FAMILY_MAX = 200;

/**
 * 字族名过滤：黑名单式。
 * 早期用的是 [\w\u4e00-\u9fa5...] 白名单，只放过拉丁与中日韩汉字，日文假名（ヒラギノ角ゴ）、
 * 韩文谚文（맑은 고딕）、西里尔字母等会被整条丢弃 —— 在 22 语言界面里不可接受。
 * 这里只拦真正能越出声明的字符（; { } ( ) 与注释起止），其余非法写法由 CSS 解析器自行丢弃：
 * setProperty 对自定义属性走解析器，塞不进第二条声明，最坏结果只是这条 font-family 无效。
 */
function sanitizeFontFamily(v) {
    if (!v) return '';
    var s = String(v).trim();
    if (!s) return '';
    if (/[;{}()<>\\]/.test(s)) return '';
    if (s.indexOf('/*') >= 0 || s.indexOf('*/') >= 0) return '';
    return s.slice(0, FONT_FAMILY_MAX);
}
window.sanitizeFontFamily = sanitizeFontFamily;

/**
 * 用户输入是否是浏览器能接受的 font-family 值（供设置面板做可见反馈）。
 * 空值合法（= 用默认栈）。
 */
function isValidFontFamily(v) {
    if (!v) return true;
    var s = sanitizeFontFamily(v);
    if (!s) return false;
    if (typeof CSS === 'undefined' || !CSS.supports) return true;
    return CSS.supports('font-family', s);
}
window.isValidFontFamily = isValidFontFamily;

function clampFontScale(v) {
    var n = parseFloat(v);
    if (isNaN(n)) return 1;
    if (n < FONT_SCALE_MIN) return FONT_SCALE_MIN;
    if (n > FONT_SCALE_MAX) return FONT_SCALE_MAX;
    return Math.round(n * 100) / 100;
}

/**
 * 应用字体设置。opts: { family, mono, scale }
 * 传入空值 / null 表示回退到主题或皮肤定义（移除 inline 覆盖）。
 * opts 中未出现的键保持当前值不变。
 */
function applyFont(opts) {
    opts = opts || {};
    var st = document.body.style;
    var rootSt = document.documentElement.style;

    // 用户字体只做“前插”，尾部始终接 theme.css 的 *-fallback 链：
    // 否则用户选了本机没装的字族（如 Windows 选 PingFang SC）会直接掉到 generic family，
    // 丢掉整条跨平台回退。
    if ('family' in opts) {
        var fam = sanitizeFontFamily(opts.family);
        if (fam) st.setProperty('--font-sans', fam + ', var(--font-sans-fallback)');
        else st.removeProperty('--font-sans');
        window.currentFontFamily = fam;
        localStorage.setItem('chat-font-family', fam);
    }

    if ('mono' in opts) {
        var mono = sanitizeFontFamily(opts.mono);
        if (mono) st.setProperty('--font-mono', mono + ', var(--font-mono-fallback)');
        else st.removeProperty('--font-mono');
        window.currentFontMono = mono;
        localStorage.setItem('chat-font-mono', mono);
    }

    if ('scale' in opts) {
        var scale = clampFontScale(opts.scale);
        // 注意：落在 html 上，--fs-* 阶梯才会跟着变（见上方注释）
        if (scale !== 1) rootSt.setProperty('--font-scale', String(scale));
        else rootSt.removeProperty('--font-scale');
        window.currentFontScale = scale;
        localStorage.setItem('chat-font-scale', String(scale));
    }
}
window.applyFont = applyFont;
window.FONT_SCALE_MIN = FONT_SCALE_MIN;
window.FONT_SCALE_MAX = FONT_SCALE_MAX;

// 启动：先读 localStorage 立即应用（避免字号闪烁），随后由服务端配置校准
applyFont({
    family: localStorage.getItem('chat-font-family') || '',
    mono: localStorage.getItem('chat-font-mono') || '',
    scale: localStorage.getItem('chat-font-scale') || 1
});

/* ===== Skin (static/skin/<name>/skin.css + local zip) ===== */
var BUILTIN_SKINS = {
    default:  { name: 'default',  get displayName() { return I18n.t('skin.default'); },   source: 'builtin' },
    eyecare:  { name: 'eyecare',  get displayName() { return I18n.t('skin.eyecare'); },    source: 'builtin' },
    contrast: { name: 'contrast', get displayName() { return I18n.t('skin.contrast'); },   source: 'builtin' }
};
window.BUILTIN_SKINS = BUILTIN_SKINS;

/** 本地皮肤注册表 name -> meta（由设置页 list 填充，启动时也可为空） */
var LOCAL_SKINS = window.LOCAL_SKINS || {};
window.LOCAL_SKINS = LOCAL_SKINS;

function isBuiltinSkin(name) {
    return !!(name && BUILTIN_SKINS[name]);
}

function ensureSkinStyleLink() {
    var el = document.getElementById('skinStyle');
    if (!el) {
        el = document.createElement('link');
        el.id = 'skinStyle';
        el.rel = 'stylesheet';
        document.head.appendChild(el);
    }
    return el;
}

/** 当前工作区查询串（统一入口 window.wsAndSuffix，见 app-base.js） */
function skinWsQuery() {
    return window.wsAndSuffix();
}

/** 预置皮肤：static/skin/<name>/skin.css */
function builtinSkinCssUrl(skinName) {
    return '/skin/' + encodeURIComponent(skinName || 'default') + '/skin.css';
}

/** 本地安装皮肤：经服务端代理（含相对 url 改写） */
function localSkinCssUrl(skinName) {
    return '/web/settings/skins/file?name=' + encodeURIComponent(skinName) +
        '&file=skin.css&_=' + Date.now() + skinWsQuery();
}

function loadSkinCss(skinName, source) {
    var el = ensureSkinStyleLink();
    if (source === 'local') {
        el.href = localSkinCssUrl(skinName);
    } else {
        el.href = builtinSkinCssUrl(skinName) + '?_=' + Date.now();
    }
}

function clearSkinCss() {
    var el = document.getElementById('skinStyle');
    if (el) {
        // 回到默认目录下的空 skin.css，避免残留本地/其它皮肤样式
        el.href = builtinSkinCssUrl('default');
    }
}

/**
 * 应用皮肤。options.source: 'builtin'|'local'（可选，缺省时自动判断）
 * options.persistServer: 是否同步到后端 activeSkin（默认 false，由调用方决定）
 */
function applySkin(skinName, options) {
    options = options || {};
    if (!skinName) skinName = 'default';

    var source = options.source;
    if (!source) {
        if (isBuiltinSkin(skinName)) source = 'builtin';
        else if (LOCAL_SKINS[skinName] || options.forceLocal) source = 'local';
        else source = 'builtin';
    }

    // 未知本地皮肤：若明确 forceLocal 仍尝试加载；否则回退 default
    if (source === 'local') {
        // ok
    } else if (!isBuiltinSkin(skinName)) {
        skinName = 'default';
        source = 'builtin';
    }

    if (skinName === 'default') {
        $('body').removeAttr('data-skin');
        clearSkinCss();
    } else if (source === 'local') {
        $('body').attr('data-skin', skinName);
        loadSkinCss(skinName, 'local');
    } else {
        // 预置皮肤：static/skin/<name>/skin.css
        $('body').attr('data-skin', skinName);
        loadSkinCss(skinName, 'builtin');
    }

    localStorage.setItem('chat-skin', skinName);
    window.currentSkin = skinName;
    window.currentSkinSource = source;
}
window.applySkin = applySkin;
window.isBuiltinSkin = isBuiltinSkin;

// 启动：先读 localStorage 快速应用；随后用服务端 activeSkin 校准
var currentSkin = localStorage.getItem('chat-skin') || 'default';
if (isBuiltinSkin(currentSkin)) {
    applySkin(currentSkin, { source: 'builtin' });
} else {
    // 可能是本地皮肤：先尝试加载，失败由后续 list/activate 校准
    applySkin(currentSkin, { source: 'local', forceLocal: true });
}

// 异步与服务端对齐（不阻塞首屏）
try {
    $.get('/web/settings/skins/list').done(function (resp) {
        if (!resp || resp.code !== 200 || !resp.data) return;
        var active = resp.data.activeSkin || 'default';
        var skins = resp.data.skins || [];
        LOCAL_SKINS = window.LOCAL_SKINS = {};
        skins.forEach(function (s) {
            if (s && s.source === 'local' && s.name) {
                LOCAL_SKINS[s.name] = s;
            }
        });
        var localMeta = null;
        for (var i = 0; i < skins.length; i++) {
            if (skins[i].name === active) {
                localMeta = skins[i];
                break;
            }
        }
        var src = (localMeta && localMeta.source === 'local') ? 'local' : 'builtin';
        if (active !== window.currentSkin || src !== window.currentSkinSource) {
            applySkin(active, { source: src, forceLocal: src === 'local' });
        }
    });
} catch (e) { /* ignore */ }

// 字体：用服务端配置校准首屏的 localStorage 值
// 同时记录“已持久化基线”，供设置面板放弃预览时回滚（面板自身的 GET 可能还没回来）
try {
    $.get('/web/settings/general').done(function (resp) {
        if (!resp || resp.code !== 200 || !resp.data) return;
        var d = resp.data;
        var baseline = {
            family: d.uiFontFamily || '',
            mono: d.uiFontMono || '',
            scale: d.uiFontScale != null ? d.uiFontScale : 1
        };
        window.savedFontBaseline = baseline;
        // 若用户已在设置面板里预览过，别用服务端值把预览覆盖掉（仅补基线）
        if (!window._fontPreviewDirty) applyFont(baseline);
    });
} catch (e) { /* ignore */ }

/* ===== View Switch ===== */
function switchToChatMode() {
    if (inChatMode) return;
    inChatMode = true;
    $(newChatView).hide();
    $(chatView).addClass('active');
    chatInput.focus();
    // 欢迎页 → 聊天页后布局/clientHeight 可能晚几帧才稳定，双 rAF + 多次短延时强制贴底
    if (typeof scrollToBottom === 'function') {
        requestAnimationFrame(function() {
            scrollToBottom(true);
            requestAnimationFrame(function() {
                scrollToBottom(true);
            });
        });
        setTimeout(function() {
            if (typeof scrollToBottom === 'function') scrollToBottom(true);
        }, 80);
        // 慢设备/首条带图：再补一次，仍尊重后续用户上滑（force 仅在未上滑意图时由调用方保证）
        setTimeout(function() {
            if (typeof scrollToBottom === 'function' && !userScrolledUp) scrollToBottom(true);
        }, 320);
    }
}
function switchToWelcomeMode() {
    inChatMode = false;
    if (typeof forgetActiveSession === 'function') forgetActiveSession();
    SESSION_ID = 'web-' + Date.now().toString(36);
    setActiveSession(SESSION_ID);
    $(newChatView).show();
    $(chatView).removeClass('active');
    newChatInput.focus();
    // 新对话时禁用“历史消息”按钮（循环任务按钮保持可用）
    $('#newChatLoopBtn').prop('disabled', false);
    // Reset model UI to new session
    if (typeof modelsLoaded !== 'undefined' && modelsLoaded) renderModelUI();
    // 重新渲染欢迎标题
    if (typeof window._renderGreeting === 'function') window._renderGreeting();
}

/* ===== Auto-resize ===== */
$(newChatInput).on('input', function() { autoResize(this); });
$(chatInput).on('input', function() { autoResize(this); });

/* ===== Voice Input (Web Speech API) - 按住说话（类似微信） ===== */
var SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
var recognition = null;
var voiceRecording = false;
var voiceTargetInput = null; // 当前语音目标 textarea
var voiceBaseText = '';      // 开始录音时 textarea 已有文本
var voiceFinalTranscript = ''; // 累计的最终识别文本

var newChatVoiceBtn = $('#newChatVoiceBtn');
var chatVoiceBtn = $('#chatVoiceBtn');

var voiceRafPending = false; // 限制 DOM 更新频率

function initVoice() {
    if (!SpeechRecognition) return; // 浏览器不支持
    recognition = new SpeechRecognition();
    recognition.lang = 'zh-CN';
    recognition.continuous = true; // 按住期间持续识别
    recognition.interimResults = true;
    recognition.maxAlternatives = 1;

    recognition.onresult = function(event) {
        var interimTranscript = '';
        var finalTranscript = '';
        for (var i = event.resultIndex; i < event.results.length; i++) {
            var transcript = event.results[i][0].transcript;
            if (event.results[i].isFinal) {
                finalTranscript += transcript;
            } else {
                interimTranscript += transcript;
            }
        }
        // 累积最终结果
        if (finalTranscript) {
            voiceFinalTranscript += finalTranscript;
        }
        // 用 RAF 节流 DOM 更新，避免频繁重绘拖慢感知
        if (!voiceRafPending && voiceTargetInput) {
            voiceRafPending = true;
            requestAnimationFrame(function() {
                voiceRafPending = false;
                if (voiceTargetInput) {
                    voiceTargetInput.value = voiceBaseText + voiceFinalTranscript + interimTranscript;
                    autoResize(voiceTargetInput);
                }
            });
        }
    };

    recognition.onerror = function(event) {
        console.warn('Speech recognition error:', event.error);
        stopVoiceRecording();
    };

    recognition.onend = function() {
        // 如果还在按住状态（voiceRecording），自动重启继续识别
        if (voiceRecording) {
            try { recognition.start(); } catch(e) {}
        } else {
            stopVoiceRecording();
        }
    };

    // 显示语音按钮
    newChatVoiceBtn.removeClass('hidden');
    chatVoiceBtn.removeClass('hidden');
}

function startVoiceRecording(inputEl) {
    if (!recognition) return;
    if (voiceRecording) return;

    voiceTargetInput = inputEl;
    voiceBaseText = inputEl.value;
    voiceFinalTranscript = '';
    voiceRecording = true;

    try { recognition.start(); } catch(e) {}

    // 更新按钮状态
    var btn = (inputEl === newChatInput) ? newChatVoiceBtn : chatVoiceBtn;
    btn.addClass('recording');
        btn.prop('title', (window.I18n ? window.I18n.t('voice.releaseToStop') : '\u677e\u5f00\u7ed3\u675f'));
}

function stopVoiceRecording() {
    if (!voiceRecording && !recognition) return;
    voiceRecording = false;
    try { if (recognition) recognition.stop(); } catch(e) {}

    // 更新按钮状态
    newChatVoiceBtn.removeClass('recording');
    chatVoiceBtn.removeClass('recording');
        newChatVoiceBtn.prop('title', (window.I18n ? window.I18n.t('voice.holdToSpeak') : '\u6309\u4f4f\u8bf4\u8bdd'));
        chatVoiceBtn.prop('title', (window.I18n ? window.I18n.t('voice.holdToSpeak') : '\u6309\u4f4f\u8bf4\u8bdd'));

    // 保留识别到的文本，重置基线以便下次追加
    if (voiceTargetInput) {
        voiceBaseText = voiceTargetInput.value;
    }
    voiceFinalTranscript = '';
    voiceTargetInput = null;
}

// --- 按住说话：按下开始录音，松开结束（类似微信） ---
function bindVoiceHold(btn, inputEl) {
    // 鼠标：按下开始，松开结束
    btn.on('mousedown', function(e) {
        e.preventDefault();
        startVoiceRecording(inputEl);
    });
    btn.on('mouseup', function(e) {
        e.preventDefault();
        stopVoiceRecording();
    });
    btn.on('mouseleave', function() {
        if (voiceRecording) stopVoiceRecording();
    });

    // 触摸：按下开始，松开结束
    btn.on('touchstart', function(e) {
        e.preventDefault();
        startVoiceRecording(inputEl);
    });
    btn.on('touchend', function(e) {
        e.preventDefault();
        stopVoiceRecording();
    });
    btn.on('touchcancel', function() {
        if (voiceRecording) stopVoiceRecording();
    });
}

bindVoiceHold(newChatVoiceBtn, newChatInput);
bindVoiceHold(chatVoiceBtn, chatInput);

initVoice();

/* ===== Sidebar Collapse Toggle ===== */
(function() {
    var btn = $('#sidebarToggleBtn');
    if (!btn.length) return;
    btn.on('click', function() {
        var sidebar = $('.sidebar');
        sidebar.toggleClass('collapsed');
        var collapsed = sidebar.hasClass('collapsed');
        btn.toggleClass('collapsed', collapsed);
        var svgPath = btn.find('path');
        if (svgPath.length) {
            svgPath.attr('d', collapsed ? 'm9 18 6-6-6-6' : 'm15 18-6-6 6-6');
        }
        btn.prop('title', collapsed ? (window.I18n ? window.I18n.t('sidebar.expand') : '\u5c55\u5f00\u4fa7\u8fb9\u680f') : (window.I18n ? window.I18n.t('sidebar.collapse') : '\u6536\u8d77\u4fa7\u8fb9\u680f'));
        localStorage.setItem('sidebar-collapsed', collapsed ? '1' : '0');
    });
    // Restore state
    if (localStorage.getItem('sidebar-collapsed') === '1') {
        $('.sidebar').addClass('collapsed');
        btn.addClass('collapsed');
        var svgPath = btn.find('path');
        if (svgPath.length) {
            svgPath.attr('d', 'm9 18 6-6-6-6');
        }
        btn.prop('title', (window.I18n ? window.I18n.t('sidebar.expand') : '\u5c55\u5f00\u4fa7\u8fb9\u680f'));
    }
})();

/* ===== Sidebar Resize ===== */
(function() {
    var $sidebar = $('.sidebar');
    var $handle = $('#sidebarResizeHandle');
    var $toggleBtn = $('#sidebarToggleBtn');

    if (!$handle.length || !$sidebar.length) return;

    var SIDEBAR_MIN_WIDTH = 180;
    var SIDEBAR_MAX_WIDTH = 600;

    function syncTogglePosition() {
        if (!$toggleBtn.length) return;
        if ($sidebar.hasClass('collapsed')) {
            $toggleBtn.css('left', '4px');
        } else {
            var w = $sidebar[0].offsetWidth;
            $toggleBtn.css('left', (w - 14) + 'px');
        }
    }

    // Init resize dragging
    (function initResize() {
        var isDragging = false;
        var startX = 0;
        var startWidth = 0;

        $handle.on('mousedown', function(e) {
            if ($sidebar.hasClass('collapsed')) return;
            isDragging = true;
            startX = e.clientX;
            startWidth = $sidebar[0].offsetWidth;
            $handle.addClass('dragging');
            $(document.body).css({ cursor: 'col-resize', userSelect: 'none' });
            e.preventDefault();
        });

        $(document).on('mousemove', function(e) {
            if (!isDragging) return;
            var dx = e.clientX - startX;
            var newWidth = Math.max(SIDEBAR_MIN_WIDTH, Math.min(SIDEBAR_MAX_WIDTH, startWidth + dx));
            $sidebar.css('width', newWidth + 'px');
            localStorage.setItem('sidebar-width', newWidth);
            syncTogglePosition();
        });

        $(document).on('mouseup', function() {
            if (!isDragging) return;
            isDragging = false;
            $handle.removeClass('dragging');
            $(document.body).css({ cursor: '', userSelect: '' });
        });
    })();

    // Restore saved width
    (function restoreWidth() {
        var savedWidth = localStorage.getItem('sidebar-width');
        if (savedWidth) {
            var w = parseInt(savedWidth, 10);
            if (w >= SIDEBAR_MIN_WIDTH && w <= SIDEBAR_MAX_WIDTH) {
                $sidebar.css('width', w + 'px');
            }
        }
        syncTogglePosition();
    })();

    // Patch toggle button: replace original click handler to include position sync
    if ($toggleBtn.length) {
        $toggleBtn.off('click').on('click', function() {
            $sidebar.toggleClass('collapsed');
            var collapsed = $sidebar.hasClass('collapsed');
            $toggleBtn.toggleClass('collapsed', collapsed);
            var $svgPath = $toggleBtn.find('path');
            if ($svgPath.length) {
                $svgPath.attr('d', collapsed ? 'm9 18 6-6-6-6' : 'm15 18-6-6 6-6');
            }
            $toggleBtn.prop('title', collapsed ? (window.I18n ? window.I18n.t('sidebar.expand') : '\u5c55\u5f00\u4fa7\u8fb9\u680f') : (window.I18n ? window.I18n.t('sidebar.collapse') : '\u6536\u8d77\u4fa7\u8fb9\u680f'));
            localStorage.setItem('sidebar-collapsed', collapsed ? '1' : '0');
            syncTogglePosition();
        });

        // Re-apply collapsed state with sync
        if (localStorage.getItem('sidebar-collapsed') === '1') {
            $sidebar.addClass('collapsed');
            $toggleBtn.addClass('collapsed');
            var $svgPath = $toggleBtn.find('path');
            if ($svgPath.length) {
                $svgPath.attr('d', 'm9 18 6-6-6-6');
            }
            $toggleBtn.prop('title', (window.I18n ? window.I18n.t('sidebar.expand') : '\u5c55\u5f00\u4fa7\u8fb9\u680f'));
            syncTogglePosition();
        }
    }
})();

/* ===== Mobile Sidebar Drawer ===== */
(function() {
    var mobileMenuBtn = $('#mobileMenuBtn');
    var mobileOverlay = $('#mobileOverlay');
    var sidebar = $('.sidebar');
    if (!mobileMenuBtn.length || !sidebar.length) return;

    mobileMenuBtn.on('click', function() {
        sidebar.toggleClass('mobile-open');
        if (mobileOverlay.length) mobileOverlay.toggleClass('show');
    });

    if (mobileOverlay.length) {
        mobileOverlay.on('click', function() {
            sidebar.removeClass('mobile-open');
            mobileOverlay.removeClass('show');
        });
    }

    // Close sidebar when selecting a chat on mobile
    var sidebarList = $('.sidebar-list');
    if (sidebarList.length) {
        sidebarList.on('click', function(e) {
            var item = e.target.closest('.sidebar-item');
            if (item && window.innerWidth <= 768) {
                sidebar.removeClass('mobile-open');
                if (mobileOverlay.length) mobileOverlay.removeClass('show');
            }
        });
    }
})();

/* ===== Keyboard Shortcuts ===== */
$(document).on('keydown', function(e) {
    // Ctrl/Cmd + N: New chat
    if ((e.ctrlKey || e.metaKey) && e.key === 'n') {
        e.preventDefault();
        if (typeof newChatBtn !== 'undefined') newChatBtn.click();
    }
    // Escape: close modals, lightbox
    if (e.key === 'Escape') {
        var $lightbox = $('.lightbox-overlay');
        if ($lightbox.length) $lightbox.remove();
    }
});

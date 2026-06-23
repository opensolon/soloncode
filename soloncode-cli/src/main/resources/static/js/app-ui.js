/* ===== app-ui.js ===== */
/* 界面交互：附件 + 主题 + 视图 + 语音 + 侧栏 + Markdown */
/* 依赖：app-base.js */

/* ===== Attachment Helpers ===== */
var welcomeAttachmentsWrap = $('#welcomeAttachmentsWrap');
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
    return inChatMode ? chatAttachmentsWrap : welcomeAttachmentsWrap;
}

function renderAttachments() {
    // Render both wraps to keep them in sync when switching views
    renderAttachmentsWrap(welcomeAttachmentsWrap);
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
        var typeTag = '<span class="attachment-type-tag ' + (item.attachmentsType || 'file') + '">' + (item.attachmentsType === 'image' ? '多模态' : '文件') + '</span>';
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

$(welcomeInput).on('paste', handlePaste);
$(chatInput).on('paste', handlePaste);

/* ===== Drag & Drop File Upload ===== */
(function() {
    var welcomeDropZone = $('#welcomeDropZone');
    var chatDropZone = $('#chatDropZone');
    var welcomeDropOverlay = $('#welcomeDropOverlay');
    var chatDropOverlay = $('#chatDropOverlay');

    // Counter to track nested enter/leave events (child elements fire their own events)
    var welcomeDragCounter = 0;
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
            showToast('附件数量已达上限（' + MAX_ATTACHMENTS + '个）', 'error');
            return;
        }

        // Separate files into images and non-images for proper processing
        for (var i = 0; i < files.length; i++) {
            if (pendingFiles.length >= MAX_ATTACHMENTS) {
                showToast('部分文件未添加，附件数量已达上限（' + MAX_ATTACHMENTS + '个）', 'error');
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

    bindDropZone(welcomeDropZone, welcomeDropOverlay, { val: welcomeDragCounter });
    bindDropZone(chatDropZone, chatDropOverlay, { val: chatDragCounter });
})();

// Attachment remove buttons - use event delegation on both wraps
welcomeAttachmentsWrap.on('click', function(e) {
    var btn = e.target.closest('.attachment-item-remove');
    if (btn) removeAttachment(parseInt(btn.getAttribute('data-idx')));
});
chatAttachmentsWrap.on('click', function(e) {
    var btn = e.target.closest('.attachment-item-remove');
    if (btn) removeAttachment(parseInt(btn.getAttribute('data-idx')));
});

// Attach button handlers
$('#welcomeAttachBtn').on('click', function(e) {
    e.stopPropagation();
    $('#welcomeAttachInput')[0].click();
});
$('#chatAttachBtn').on('click', function(e) {
    e.stopPropagation();
    $('#chatAttachInput')[0].click();
});
$('#welcomeAttachInput').on('change', function(e) {
    if (e.target.files && e.target.files.length > 0) processSelectedFiles(e.target.files, 'file');
    e.target.value = '';
});
$('#chatAttachInput').on('change', function(e) {
    if (e.target.files && e.target.files.length > 0) processSelectedFiles(e.target.files, 'file');
    e.target.value = '';
});

// Image button handlers
$('#welcomeImageBtn').on('click', function(e) {
    e.stopPropagation();
    $('#welcomeImageInput')[0].click();
});
$('#chatImageBtn').on('click', function(e) {
    e.stopPropagation();
    $('#chatImageInput')[0].click();
});
$('#welcomeImageInput').on('change', function(e) {
    if (e.target.files && e.target.files.length > 0) processSelectedFiles(e.target.files, 'image');
    e.target.value = '';
});
$('#chatImageInput').on('change', function(e) {
    if (e.target.files && e.target.files.length > 0) processSelectedFiles(e.target.files, 'image');
    e.target.value = '';
});

/* ===== Marked ===== */
if (typeof marked !== 'undefined') { marked.setOptions({ breaks: true, gfm: true }); }
var _mdCache = new Map();
var _MD_CACHE_MAX = 100;
function renderMd(text) {
    if (typeof marked !== 'undefined') {
        if (!text) return '';
        if (text.length < 5000) {
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
    return text.replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\n/g,'<br>');
}

/* ===== Highlight.js ===== */
function highlightCodeBlocks(container) {
    if (!container || typeof hljs === 'undefined') return;
    var blocks = $(container).find('pre code:not([data-hljs-collected])');
    if (blocks.length === 0) return;
    blocks.each(function() { this.dataset.hljsCollected = 'true'; });
    function doHighlight() {
        blocks.each(function() {
            if (!this.dataset.hljsHighlighted) {
                this.dataset.hljsHighlighted = 'true';
                try { hljs.highlightElement(this); } catch(e) {}
            }
        });
    }
    if (window.requestIdleCallback) {
        requestIdleCallback(doHighlight, { timeout: 300 });
    } else {
        setTimeout(doHighlight, 50);
    }
}

/* ===== Mermaid ===== */
function processMermaidBlocks(container) {
    if (!container || typeof mermaid === 'undefined') return;
    var blocks = container.querySelectorAll('pre code.language-mermaid:not([data-mermaid-processed])');
    if (blocks.length === 0) return;

    var nodes = [];
    for (var i = 0; i < blocks.length; i++) {
        var codeEl = blocks[i];
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
        mermaid.run({ nodes: nodes, suppressErrors: true }).catch(function() {});
    }
}

function applyHljsTheme(theme) {
    var $lightLink = $('#hljs-light-theme');
    var $darkLink = $('#hljs-dark-theme');
    if (!$lightLink.length || !$darkLink.length) return;
    if (theme === 'dark') {
        $lightLink.prop('disabled', true).prop('media', 'not all');
        $darkLink.prop('disabled', false).prop('media', 'all');
    } else {
        $darkLink.prop('disabled', true).prop('media', 'not all');
        $lightLink.prop('disabled', false).prop('media', 'all');
    }
}

/* ===== Theme ===== */
var currentTheme = localStorage.getItem('chat-theme') || 'light';
$('body').attr('data-theme', currentTheme);

// Apply initial hljs theme (after currentTheme is defined)
applyHljsTheme(currentTheme);

updateThemeIcon();

/* ===== Mermaid Init ===== */
if (typeof mermaid !== 'undefined') {
    mermaid.initialize({
        startOnLoad: false,
        theme: currentTheme === 'dark' ? 'dark' : 'default',
        securityLevel: 'loose',
        fontFamily: 'var(--font-sans)',
    });
}

$(themeBtn).on('click', function() {
    currentTheme = currentTheme === 'light' ? 'dark' : 'light';
    $('body').attr('data-theme', currentTheme);
    localStorage.setItem('chat-theme', currentTheme);
    updateThemeIcon();
    applyHljsTheme(currentTheme);
    if (typeof mermaid !== 'undefined') {
        mermaid.initialize({ theme: currentTheme === 'dark' ? 'dark' : 'default' });
    }
});
function updateThemeIcon() {
    $(themeIcon).html(currentTheme === 'light' ? '&#xe6c2;' : '&#xe748;');
    $(themeBtn).prop('title', currentTheme === 'light' ? '切换至暗色' : '切换至浅色');
}

/* ===== View Switch ===== */
function switchToChatMode() {
    if (inChatMode) return;
    inChatMode = true;
    $(welcomeView).hide();
    $(chatView).addClass('active');
    chatInput.focus();
}
function switchToWelcomeMode() {
    inChatMode = false;
    if (typeof forgetActiveSession === 'function') forgetActiveSession();
    SESSION_ID = 'web-' + Date.now().toString(36);
    setActiveSession(SESSION_ID);
    $(welcomeView).show();
    $(chatView).removeClass('active');
    welcomeInput.focus();
    // 新对话时禁用“历史消息”按钮（循环任务按钮保持可用）
    $('#welcomeHistoryBtn').prop('disabled', true);
    $('#welcomeLoopBtn').prop('disabled', false);
    // Reset model UI to new session
    if (typeof modelsLoaded !== 'undefined' && modelsLoaded) renderModelUI();
}

/* ===== Auto-resize ===== */
$(welcomeInput).on('input', function() { autoResize(this); });
$(chatInput).on('input', function() { autoResize(this); });

/* ===== Voice Input (Web Speech API) - 按住说话（类似微信） ===== */
var SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
var recognition = null;
var voiceRecording = false;
var voiceTargetInput = null; // 当前语音目标 textarea
var voiceBaseText = '';      // 开始录音时 textarea 已有文本
var voiceFinalTranscript = ''; // 累计的最终识别文本

var welcomeVoiceBtn = $('#welcomeVoiceBtn');
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
    welcomeVoiceBtn.removeClass('hidden');
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
    var btn = (inputEl === welcomeInput) ? welcomeVoiceBtn : chatVoiceBtn;
    btn.addClass('recording');
    btn.prop('title', '松开结束');
}

function stopVoiceRecording() {
    if (!voiceRecording && !recognition) return;
    voiceRecording = false;
    try { if (recognition) recognition.stop(); } catch(e) {}

    // 更新按钮状态
    welcomeVoiceBtn.removeClass('recording');
    chatVoiceBtn.removeClass('recording');
    welcomeVoiceBtn.prop('title', '按住说话');
    chatVoiceBtn.prop('title', '按住说话');

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

bindVoiceHold(welcomeVoiceBtn, welcomeInput);
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
        btn.html(collapsed ? '›' : '‹');
        btn.prop('title', collapsed ? '展开侧边栏' : '收起侧边栏');
        localStorage.setItem('sidebar-collapsed', collapsed ? '1' : '0');
    });
    // Restore state
    if (localStorage.getItem('sidebar-collapsed') === '1') {
        $('.sidebar').addClass('collapsed');
        btn.addClass('collapsed');
        btn.html('›');
        btn.prop('title', '展开侧边栏');
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
            $toggleBtn.html(collapsed ? '\u203A' : '\u2039');
            $toggleBtn.prop('title', collapsed ? '展开侧边栏' : '收起侧边栏');
            localStorage.setItem('sidebar-collapsed', collapsed ? '1' : '0');
            syncTogglePosition();
        });

        // Re-apply collapsed state with sync
        if (localStorage.getItem('sidebar-collapsed') === '1') {
            $sidebar.addClass('collapsed');
            $toggleBtn.addClass('collapsed');
            $toggleBtn.html('\u203A');
            $toggleBtn.prop('title', '展开侧边栏');
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

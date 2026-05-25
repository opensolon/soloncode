/* ===== app-ui.js ===== */
/* 界面交互：附件 + 主题 + 视图 + 语音 + 侧栏 + Markdown */
/* 依赖：app-base.js */

/* ===== Attachment Helpers ===== */
var welcomeAttachmentsWrap = document.getElementById('welcomeAttachmentsWrap');
var chatAttachmentsWrap = document.getElementById('chatAttachmentsWrap');

function handlePasteImage(e) {
    var items = (e.clipboardData || e.originalEvent.clipboardData).items;
    for (var i = 0; i < items.length; i++) {
        if (items[i].type.indexOf('image') !== -1) {
            e.preventDefault();
            var file = items[i].getAsFile();
            processSelectedFile(file, 'image');
            return;
        }
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
    wrap.innerHTML = '';
    if (pendingFiles.length === 0) {
        wrap.classList.remove('has-items');
        return;
    }
    wrap.classList.add('has-items');
    for (var i = 0; i < pendingFiles.length; i++) {
        var item = pendingFiles[i];
        var el = document.createElement('div');
        el.className = 'attachment-item';
        var typeTag = '<span class="attachment-type-tag ' + (item.attachmentsType || 'file') + '">' + (item.attachmentsType === 'image' ? '多模态' : '文件') + '</span>';
        if (item.type === 'image') {
            el.innerHTML = '<img src="' + item.dataUrl + '"/>'
                + typeTag
                + '<button class="attachment-item-remove" data-idx="' + i + '">&times;</button>';
        } else {
            el.innerHTML = '<div class="attachment-item-file">'
                + '<span class="file-icon">📎</span>'
                + '<span class="file-name">' + escapeHtml(item.name) + '</span>'
                + '</div>'
                + typeTag
                + '<button class="attachment-item-remove" data-idx="' + i + '">&times;</button>';
        }
        wrap.appendChild(el);
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

welcomeInput.addEventListener('paste', handlePasteImage);
chatInput.addEventListener('paste', handlePasteImage);

/* ===== Drag & Drop File Upload ===== */
(function() {
    var welcomeDropZone = document.getElementById('welcomeDropZone');
    var chatDropZone = document.getElementById('chatDropZone');
    var welcomeDropOverlay = document.getElementById('welcomeDropOverlay');
    var chatDropOverlay = document.getElementById('chatDropOverlay');

    // Counter to track nested enter/leave events (child elements fire their own events)
    var welcomeDragCounter = 0;
    var chatDragCounter = 0;

    function showOverlay(overlay) {
        overlay.classList.add('active');
    }

    function hideOverlay(overlay) {
        overlay.classList.remove('active');
    }

    function handleDrop(e, overlay, counterReset) {
        e.preventDefault();
        e.stopPropagation();
        counterReset.val = 0;
        hideOverlay(overlay);

        var files = e.dataTransfer && e.dataTransfer.files;
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
        zone.addEventListener('dragenter', function(e) {
            e.preventDefault();
            e.stopPropagation();
            counter.val++;
            showOverlay(overlay);
        });

        zone.addEventListener('dragover', function(e) {
            e.preventDefault();
            e.stopPropagation();
            // Keep overlay visible during drag over
        });

        zone.addEventListener('dragleave', function(e) {
            e.preventDefault();
            e.stopPropagation();
            counter.val--;
            if (counter.val <= 0) {
                counter.val = 0;
                hideOverlay(overlay);
            }
        });

        zone.addEventListener('drop', function(e) {
            handleDrop(e, overlay, counter);
        });
    }

    bindDropZone(welcomeDropZone, welcomeDropOverlay, { val: welcomeDragCounter });
    bindDropZone(chatDropZone, chatDropOverlay, { val: chatDragCounter });
})();

// Attachment remove buttons - use event delegation on both wraps
welcomeAttachmentsWrap.addEventListener('click', function(e) {
    var btn = e.target.closest('.attachment-item-remove');
    if (btn) removeAttachment(parseInt(btn.getAttribute('data-idx')));
});
chatAttachmentsWrap.addEventListener('click', function(e) {
    var btn = e.target.closest('.attachment-item-remove');
    if (btn) removeAttachment(parseInt(btn.getAttribute('data-idx')));
});

// Attach button handlers
document.getElementById('welcomeAttachBtn').addEventListener('click', function(e) {
    e.stopPropagation();
    document.getElementById('welcomeAttachInput').click();
});
document.getElementById('chatAttachBtn').addEventListener('click', function(e) {
    e.stopPropagation();
    document.getElementById('chatAttachInput').click();
});
document.getElementById('welcomeAttachInput').addEventListener('change', function(e) {
    if (e.target.files && e.target.files.length > 0) processSelectedFiles(e.target.files, 'file');
    e.target.value = '';
});
document.getElementById('chatAttachInput').addEventListener('change', function(e) {
    if (e.target.files && e.target.files.length > 0) processSelectedFiles(e.target.files, 'file');
    e.target.value = '';
});

// Image button handlers
document.getElementById('welcomeImageBtn').addEventListener('click', function(e) {
    e.stopPropagation();
    document.getElementById('welcomeImageInput').click();
});
document.getElementById('chatImageBtn').addEventListener('click', function(e) {
    e.stopPropagation();
    document.getElementById('chatImageInput').click();
});
document.getElementById('welcomeImageInput').addEventListener('change', function(e) {
    if (e.target.files && e.target.files.length > 0) processSelectedFiles(e.target.files, 'image');
    e.target.value = '';
});
document.getElementById('chatImageInput').addEventListener('change', function(e) {
    if (e.target.files && e.target.files.length > 0) processSelectedFiles(e.target.files, 'image');
    e.target.value = '';
});

/* ===== Marked ===== */
if (typeof marked !== 'undefined') { marked.setOptions({ breaks: true, gfm: true }); }
function renderMd(text) {
    if (typeof marked !== 'undefined') return marked.parse(text);
    return text.replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\n/g,'<br>');
}

/* ===== Highlight.js ===== */
function highlightCodeBlocks(container) {
    if (!container || typeof hljs === 'undefined') return;
    var blocks = container.querySelectorAll('pre code');
    for (var i = 0; i < blocks.length; i++) {
        if (blocks[i].dataset.hljsHighlighted) continue;
        blocks[i].dataset.hljsHighlighted = 'true';
        try { hljs.highlightElement(blocks[i]); } catch(e) {}
    }
}

function applyHljsTheme(theme) {
    var lightLink = document.getElementById('hljs-light-theme');
    var darkLink = document.getElementById('hljs-dark-theme');
    if (!lightLink || !darkLink) return;
    if (theme === 'dark') {
        lightLink.disabled = true;
        lightLink.media = 'not all';
        darkLink.disabled = false;
        darkLink.media = 'all';
    } else {
        darkLink.disabled = true;
        darkLink.media = 'not all';
        lightLink.disabled = false;
        lightLink.media = 'all';
    }
}

/* ===== Theme ===== */
var currentTheme = localStorage.getItem('chat-theme') || 'light';
document.body.setAttribute('data-theme', currentTheme);

// Apply initial hljs theme (after currentTheme is defined)
applyHljsTheme(currentTheme);

updateThemeIcon();
themeBtn.addEventListener('click', function() {
    currentTheme = currentTheme === 'light' ? 'dark' : 'light';
    document.body.setAttribute('data-theme', currentTheme);
    localStorage.setItem('chat-theme', currentTheme);
    updateThemeIcon();
    applyHljsTheme(currentTheme);
});
function updateThemeIcon() {
    themeIcon.innerHTML = currentTheme === 'light' ? '&#xe6c2;' : '&#xe748;';
    themeBtn.title = currentTheme === 'light' ? '切换至暗色' : '切换至浅色';
}

/* ===== View Switch ===== */
function switchToChatMode() {
    if (inChatMode) return;
    inChatMode = true;
    welcomeView.style.display = 'none';
    chatView.classList.add('active');
    chatInput.focus();
}
function switchToWelcomeMode() {
    inChatMode = false;
    SESSION_ID = 'web-' + Date.now().toString(36);
    setActiveSession(SESSION_ID);
    welcomeView.style.display = '';
    chatView.classList.remove('active');
    welcomeInput.focus();
    // Reset model UI to new session
    if (typeof modelsLoaded !== 'undefined' && modelsLoaded) renderModelUI();
}

/* ===== Auto-resize ===== */
welcomeInput.addEventListener('input', function() { autoResize(this); });
chatInput.addEventListener('input', function() { autoResize(this); });

/* ===== Voice Input (Web Speech API) - 按住说话（类似微信） ===== */
var SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
var recognition = null;
var voiceRecording = false;
var voiceTargetInput = null; // 当前语音目标 textarea
var voiceBaseText = '';      // 开始录音时 textarea 已有文本
var voiceFinalTranscript = ''; // 累计的最终识别文本

var welcomeVoiceBtn = document.getElementById('welcomeVoiceBtn');
var chatVoiceBtn = document.getElementById('chatVoiceBtn');

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
    welcomeVoiceBtn.classList.remove('hidden');
    chatVoiceBtn.classList.remove('hidden');
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
    btn.classList.add('recording');
    btn.title = '松开结束';
}

function stopVoiceRecording() {
    if (!voiceRecording && !recognition) return;
    voiceRecording = false;
    try { if (recognition) recognition.stop(); } catch(e) {}

    // 更新按钮状态
    welcomeVoiceBtn.classList.remove('recording');
    chatVoiceBtn.classList.remove('recording');
    welcomeVoiceBtn.title = '按住说话';
    chatVoiceBtn.title = '按住说话';

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
    btn.addEventListener('mousedown', function(e) {
        e.preventDefault();
        startVoiceRecording(inputEl);
    });
    btn.addEventListener('mouseup', function(e) {
        e.preventDefault();
        stopVoiceRecording();
    });
    btn.addEventListener('mouseleave', function() {
        if (voiceRecording) stopVoiceRecording();
    });

    // 触摸：按下开始，松开结束
    btn.addEventListener('touchstart', function(e) {
        e.preventDefault();
        startVoiceRecording(inputEl);
    });
    btn.addEventListener('touchend', function(e) {
        e.preventDefault();
        stopVoiceRecording();
    });
    btn.addEventListener('touchcancel', function() {
        if (voiceRecording) stopVoiceRecording();
    });
}

bindVoiceHold(welcomeVoiceBtn, welcomeInput);
bindVoiceHold(chatVoiceBtn, chatInput);

initVoice();

/* ===== Sidebar Collapse Toggle ===== */
(function() {
    var btn = document.getElementById('sidebarToggleBtn');
    if (!btn) return;
    btn.addEventListener('click', function() {
        var sidebar = document.querySelector('.sidebar');
        sidebar.classList.toggle('collapsed');
        var collapsed = sidebar.classList.contains('collapsed');
        btn.classList.toggle('collapsed', collapsed);
        btn.innerHTML = collapsed ? '›' : '‹';
        btn.title = collapsed ? '展开侧边栏' : '收起侧边栏';
        localStorage.setItem('sidebar-collapsed', collapsed ? '1' : '0');
    });
    // Restore state
    if (localStorage.getItem('sidebar-collapsed') === '1') {
        document.querySelector('.sidebar').classList.add('collapsed');
        btn.classList.add('collapsed');
        btn.innerHTML = '›';
        btn.title = '展开侧边栏';
    }
})();

/* ===== Mobile Sidebar Drawer ===== */
(function() {
    var mobileMenuBtn = document.getElementById('mobileMenuBtn');
    var mobileOverlay = document.getElementById('mobileOverlay');
    var sidebar = document.querySelector('.sidebar');
    if (!mobileMenuBtn || !sidebar) return;

    mobileMenuBtn.addEventListener('click', function() {
        sidebar.classList.toggle('mobile-open');
        if (mobileOverlay) mobileOverlay.classList.toggle('show');
    });

    if (mobileOverlay) {
        mobileOverlay.addEventListener('click', function() {
            sidebar.classList.remove('mobile-open');
            mobileOverlay.classList.remove('show');
        });
    }

    // Close sidebar when selecting a chat on mobile
    var sidebarList = document.querySelector('.sidebar-list');
    if (sidebarList) {
        sidebarList.addEventListener('click', function(e) {
            var item = e.target.closest('.sidebar-item');
            if (item && window.innerWidth <= 768) {
                sidebar.classList.remove('mobile-open');
                if (mobileOverlay) mobileOverlay.classList.remove('show');
            }
        });
    }
})();

/* ===== Keyboard Shortcuts ===== */
document.addEventListener('keydown', function(e) {
    // Ctrl/Cmd + N: New chat
    if ((e.ctrlKey || e.metaKey) && e.key === 'n') {
        e.preventDefault();
        if (typeof newChatBtn !== 'undefined') newChatBtn.click();
    }
    // Escape: close modals, lightbox
    if (e.key === 'Escape') {
        var lightbox = document.querySelector('.lightbox-overlay');
        if (lightbox) lightbox.remove();
    }
});

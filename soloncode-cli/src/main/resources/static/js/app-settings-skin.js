/**
 * app-settings-skin.js — 皮肤管理（预置 + 本地 Zip）
 *
 * 依赖：layui.js（jQuery）、app-ui.js（applySkin / BUILTIN_SKINS / LOCAL_SKINS）
 *
 * 后端接口：
 *   GET  /web/settings/skins/list
 *   POST /web/settings/skins/install   multipart file  或  ?file=workspace相对路径.zip
 *   POST /web/settings/skins/activate  {name}
 *   POST /web/settings/skins/uninstall {name}
 *   GET  /web/settings/skins/export?name=xxx  导出本地皮肤 zip
 *
 * 聊天一键安装链接（Markdown）：
 *   [点击安装](/web/settings/skins/install?file=aurora.zip)
 */
(function () {
    'use strict';

    function showToast(msg, type) {
        if (typeof layer !== 'undefined' && layer.msg) {
            layer.msg(msg, { icon: type === 'error' ? 2 : 1, time: 2500, offset: '120px' });
        } else {
            alert(msg);
        }
    }

    function escapeHtml(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function escapeAttr(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
    }

    var _skinsCache = [];
    var _activeSkin = 'default';

    function sourceOf(name) {
        if (window.BUILTIN_SKINS && window.BUILTIN_SKINS[name]) return 'builtin';
        return 'local';
    }

    function renderUploadCard() {
        return '<div class="skin-card skin-card--upload" id="skinUploadCard" title="上传 Zip 自定义皮肤">' +
            '<div class="skin-card-preview skin-card-preview--upload" aria-hidden="true">+</div>' +
            '<div class="skin-card-body">' +
            '<div class="skin-card-title">上传皮肤</div>' +
            '<div class="skin-card-desc">Zip 安装，立即加入列表</div>' +
            '<div class="skin-card-actions">' +
            '<span class="skin-manage-hint" style="margin:0">自定义</span>' +
            '</div></div></div>';
    }

    function renderCards(skins, active) {
        var $list = $('#skinCardList');
        if (!$list.length) return;

        var html = '';
        (skins || []).forEach(function (s) {
            var isActive = s.name === active;
            var isLocal = s.source === 'local';
            var badge = isLocal
                ? '<span class="skin-badge local">本地</span>'
                : '<span class="skin-badge">预置</span>';
            var preview = '';
            if (isLocal && s.hasPreview) {
                preview = '<div class="skin-card-preview" style="background-image:url(\'/web/settings/skins/file?name=' +
                    encodeURIComponent(s.name) + '&file=preview.png\')"></div>';
            } else {
                preview = '<div class="skin-card-preview skin-card-preview--' + escapeAttr(s.name) + '"></div>';
            }
            // 点击整卡启用；选中态用左上角勾；本地皮肤提供卸载 + 导出
            html += '<div class="skin-card' + (isActive ? ' active' : '') + '" data-name="' +
                escapeAttr(s.name) + '" data-source="' + escapeAttr(s.source || '') + '">' +
                (isActive ? '<span class="skin-check" aria-hidden="true">✓</span>' : '') +
                preview +
                '<div class="skin-card-body">' +
                '<div class="skin-card-title">' + escapeHtml(s.displayName || s.name) + badge + '</div>' +
                '<div class="skin-card-desc">' + escapeHtml(s.description || s.name) + '</div>' +
                (isLocal
                    ? '<div class="skin-card-actions skin-card-actions--local">' +
                      '<button type="button" class="settings-mini-btn skin-uninstall-btn">卸载</button>' +
                      '<button type="button" class="settings-mini-btn skin-export-btn">导出</button>' +
                      '</div>'
                    : '') +
                '</div></div>';
        });

        // 始终追加「上传」块：与默认/护眼/高对比同形，凑满一行四格
        html += renderUploadCard();
        $list.html(html);
    }

    function applyLocalRegistry(skins) {
        window.LOCAL_SKINS = window.LOCAL_SKINS || {};
        // 重建本地注册表
        var next = {};
        (skins || []).forEach(function (s) {
            if (s && s.source === 'local' && s.name) next[s.name] = s;
        });
        window.LOCAL_SKINS = next;
    }

    function loadSkins() {
        $.get('/web/settings/skins/list')
            .done(function (resp) {
                if (!resp || resp.code !== 200 || !resp.data) {
                    return;
                }
                _skinsCache = resp.data.skins || [];
                _activeSkin = resp.data.activeSkin || 'default';
                applyLocalRegistry(_skinsCache);
                renderCards(_skinsCache, _activeSkin);

                // 与服务端对齐当前皮肤
                var meta = null;
                for (var i = 0; i < _skinsCache.length; i++) {
                    if (_skinsCache[i].name === _activeSkin) {
                        meta = _skinsCache[i];
                        break;
                    }
                }
                var src = meta && meta.source === 'local' ? 'local' : 'builtin';
                if (typeof window.applySkin === 'function') {
                    if (window.currentSkin !== _activeSkin || window.currentSkinSource !== src) {
                        window.applySkin(_activeSkin, { source: src, forceLocal: src === 'local' });
                    }
                }
            })
            .fail(function () {
                console.error('[Settings] Failed to load skins');
            });
    }

    function activateSkin(name) {
        if (!name) name = 'default';
        var src = sourceOf(name);
        // 若在列表缓存里能找到更准
        for (var i = 0; i < _skinsCache.length; i++) {
            if (_skinsCache[i].name === name) {
                src = _skinsCache[i].source === 'local' ? 'local' : 'builtin';
                break;
            }
        }

        $.ajax({
            url: '/web/settings/skins/activate',
            method: 'POST',
            contentType: 'application/json',
            dataType: 'json',
            data: JSON.stringify({ name: name })
        }).done(function (resp) {
            if (resp && resp.code === 200) {
                _activeSkin = (resp.data && resp.data.activeSkin) || name;
                if (typeof window.applySkin === 'function') {
                    window.applySkin(_activeSkin, { source: src, forceLocal: src === 'local' });
                }
                renderCards(_skinsCache, _activeSkin);
                showToast('已切换皮肤');
            } else {
                showToast((resp && resp.message) || '切换失败', 'error');
            }
        }).fail(function () {
            showToast('网络错误', 'error');
        });
    }

    function uninstallSkin(name) {
        if (!name) return;
        $.ajax({
            url: '/web/settings/skins/uninstall',
            method: 'POST',
            contentType: 'application/json',
            dataType: 'json',
            data: JSON.stringify({ name: name })
        }).done(function (resp) {
            if (resp && resp.code === 200) {
                showToast('已卸载');
                if (resp.data && resp.data.activeSkin) {
                    _activeSkin = resp.data.activeSkin;
                }
                loadSkins();
                if (typeof window.applySkin === 'function') {
                    var active = _activeSkin || 'default';
                    var src = sourceOf(active);
                    window.applySkin(active, { source: src, forceLocal: src === 'local' });
                }
            } else {
                showToast((resp && resp.message) || '卸载失败', 'error');
            }
        }).fail(function () {
            showToast('网络错误', 'error');
        });
    }

    function onInstallSuccess(resp) {
        if (resp && resp.code === 200) {
            var name = resp.data && resp.data.name;
            showToast('安装成功' + (name ? (': ' + name) : ''));
            // 先刷新列表，再自动启用（避免 LOCAL_SKINS 尚未更新）
            $.get('/web/settings/skins/list').done(function (listResp) {
                if (listResp && listResp.code === 200 && listResp.data) {
                    _skinsCache = listResp.data.skins || [];
                    _activeSkin = listResp.data.activeSkin || 'default';
                    applyLocalRegistry(_skinsCache);
                    renderCards(_skinsCache, _activeSkin);
                }
                if (name) activateSkin(name);
                else loadSkins();
            }).fail(function () {
                loadSkins();
                if (name) activateSkin(name);
            });
            return true;
        }
        showToast((resp && resp.message) || '安装失败', 'error');
        return false;
    }

    function installSkinFile(file) {
        if (!file) return;
        var fd = new FormData();
        fd.append('file', file);
        $.ajax({
            url: '/web/settings/skins/install',
            method: 'POST',
            data: fd,
            processData: false,
            contentType: false,
            dataType: 'json'
        }).done(function (resp) {
            onInstallSuccess(resp);
        }).fail(function () {
            showToast('上传失败', 'error');
        });
    }

    /**
     * 从工作区相对路径一键安装（聊天链接 / 技能输出协议）。
     * @param {string} relativeFile 相对 workspace 的 zip 路径，推荐 .uploads/aurora.zip
     */
    function installSkinFromPath(relativeFile) {
        if (!relativeFile) return;
        var file = String(relativeFile).trim().replace(/^\.\//, '');
        if (!file || file.indexOf('..') >= 0) {
            showToast('非法皮肤路径', 'error');
            return;
        }
        showToast('正在安装皮肤…');
        $.ajax({
            url: '/web/settings/skins/install?file=' + encodeURIComponent(file),
            method: 'POST',
            dataType: 'json'
        }).done(function (resp) {
            onInstallSuccess(resp);
        }).fail(function (xhr) {
            var msg = '安装失败';
            try {
                if (xhr && xhr.responseJSON && xhr.responseJSON.message) {
                    msg = xhr.responseJSON.message;
                }
            } catch (e) {}
            showToast(msg, 'error');
        });
    }

    /**
     * 解析 href 是否为皮肤一键安装链接，返回 file 参数或 null。
     * 支持：/web/settings/skins/install?file=xxx.zip （含相对路径、绝对同源路径）
     */
    function parseSkinInstallHref(href) {
        if (!href) return null;
        try {
            var raw = String(href).trim();
            // 允许相对路径与同源绝对路径
            var url;
            if (/^https?:\/\//i.test(raw)) {
                url = new URL(raw);
                if (url.origin !== window.location.origin) return null;
            } else {
                url = new URL(raw, window.location.origin);
            }
            var path = url.pathname || '';
            if (path !== '/web/settings/skins/install' && !path.endsWith('/web/settings/skins/install')) {
                return null;
            }
            var file = url.searchParams.get('file');
            if (!file) return null;
            file = String(file).trim().replace(/^\.\//, '');
            if (!file || !/\.zip$/i.test(file) || file.indexOf('..') >= 0) return null;
            return file;
        } catch (e) {
            return null;
        }
    }

    // ===== 事件绑定 =====

    function openSkinZipPicker() {
        $('#skinZipInput').val('');
        $('#skinZipInput').trigger('click');
    }

    $(document).on('click', '#skinUploadCard, #skinInstallBtn', function (e) {
        e.preventDefault();
        e.stopPropagation();
        openSkinZipPicker();
    });

    $(document).on('change', '#skinZipInput', function () {
        var f = this.files && this.files[0];
        if (f) installSkinFile(f);
    });

    $(document).on('click', '.skin-activate-btn', function (e) {
        e.preventDefault();
        e.stopPropagation();
        var name = $(this).closest('.skin-card').data('name');
        activateSkin(name);
    });

    $(document).on('click', '.skin-uninstall-btn', function (e) {
        e.preventDefault();
        e.stopPropagation();
        var name = $(this).closest('.skin-card').data('name');
        if (name && confirm('确认卸载本地皮肤「' + name + '」？')) {
            uninstallSkin(name);
        }
    });
    
    $(document).on('click', '.skin-export-btn', function (e) {
        e.preventDefault();
        e.stopPropagation();
        var name = $(this).closest('.skin-card').data('name');
        if (!name) return;
        // 触发浏览器下载本地皮肤 zip，便于分享给朋友导入
        var a = document.createElement('a');
        a.href = '/web/settings/skins/export?name=' + encodeURIComponent(name);
        a.download = name + '.zip';
        a.style.display = 'none';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        showToast('开始导出: ' + name + '.zip');
    });

    $(document).on('click', '.skin-card', function (e) {
        if ($(e.target).closest('button').length) return;
        if ($(this).hasClass('skin-card--upload') || this.id === 'skinUploadCard') {
            openSkinZipPicker();
            return;
        }
        var name = $(this).data('name');
        if (name && name !== _activeSkin) activateSkin(name);
    });

    // 聊天消息中的一键安装链接：拦截默认跳转，改为 POST 安装并自动启用
    $(document).on('click', 'a[href]', function (e) {
        var href = this.getAttribute('href') || '';
        var file = parseSkinInstallHref(href);
        if (!file) return;
        e.preventDefault();
        e.stopPropagation();
        installSkinFromPath(file);
    });

    // 挂在「通用 → 界面效果」下，进入通用 Tab 时调用 loadSkins；切换/安装统一走 activate API

    window._settingsSkin = {
        load: loadSkins,
        installFromPath: installSkinFromPath
    };
})();

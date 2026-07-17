/**
 * app-settings-skin.js — 皮肤管理（预置 + 本地 Zip）
 *
 * 依赖：layui.js（jQuery）、app-ui.js（applySkin / BUILTIN_SKINS / LOCAL_SKINS）
 *
 * 后端接口：
 *   GET  /web/settings/skins/list
 *   POST /web/settings/skins/install   multipart file
 *   POST /web/settings/skins/activate  {name}
 *   POST /web/settings/skins/uninstall {name}
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

    function renderCards(skins, active) {
        var $list = $('#skinCardList');
        if (!$list.length) return;
        if (!skins || !skins.length) {
            $list.html('<div class="skin-empty">暂无皮肤</div>');
            return;
        }
        var html = '';
        skins.forEach(function (s) {
            var isActive = s.name === active;
            var badge = s.source === 'local' ? '<span class="skin-badge local">本地</span>' : '<span class="skin-badge">预置</span>';
            var preview = '';
            if (s.source === 'local' && s.hasPreview) {
                preview = '<div class="skin-card-preview" style="background-image:url(\'/web/settings/skins/file?name=' +
                    encodeURIComponent(s.name) + '&file=preview.png\')"></div>';
            } else {
                preview = '<div class="skin-card-preview skin-card-preview--' + escapeAttr(s.name) + '"></div>';
            }
            html += '<div class="skin-card' + (isActive ? ' active' : '') + '" data-name="' + escapeAttr(s.name) + '" data-source="' + escapeAttr(s.source || '') + '">' +
                preview +
                '<div class="skin-card-body">' +
                '<div class="skin-card-title">' + escapeHtml(s.displayName || s.name) + badge + '</div>' +
                '<div class="skin-card-desc">' + escapeHtml(s.description || s.name) + '</div>' +
                '<div class="skin-card-actions">' +
                (isActive
                    ? '<span class="skin-active-label">使用中</span>'
                    : '<button type="button" class="settings-mini-btn skin-activate-btn">启用</button>') +
                (s.source === 'local'
                    ? '<button type="button" class="settings-mini-btn skin-uninstall-btn">卸载</button>'
                    : '') +
                '</div></div></div>';
        });
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
            } else {
                showToast((resp && resp.message) || '安装失败', 'error');
            }
        }).fail(function () {
            showToast('上传失败', 'error');
        });
    }

    // ===== 事件绑定 =====

    $(document).on('click', '#skinInstallBtn', function () {
        $('#skinZipInput').val('');
        $('#skinZipInput').trigger('click');
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

    $(document).on('click', '.skin-card', function (e) {
        if ($(e.target).closest('button').length) return;
        var name = $(this).data('name');
        if (name && name !== _activeSkin) activateSkin(name);
    });

    // 独立「皮肤」Tab 加载时调用 loadSkins；切换/安装统一走 activate API

    window._settingsSkin = {
        load: loadSkins
    };
})();

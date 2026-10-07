/**
 * 设置 - 配置备份（导出/导入 zip）
 *
 * @author noear 2026/9/5
 */
(function () {
    'use strict';

    var STORAGE_KEY = 'soloncode.backup.checkedKeys';

    function showToast(msg, type) {
        if (window.showToast) {
            window.showToast(msg, type);
        } else {
            console.log('[backup]', type || 'info', msg);
        }
    }

    // ---------- 勾选状态持久化 ----------

    function loadCheckedKeys() {
        //后端记忆（localStorage 按 origin 隔离，随机端口重启即丢）
        if (window.UiState) {
            var v = UiState.get(STORAGE_KEY, null);
            try {
                return v ? JSON.parse(v) : null;
            } catch (e) {
                return null;
            }
        }
        try {
            var raw = localStorage.getItem(STORAGE_KEY);
            return raw ? JSON.parse(raw) : null;
        } catch (e) {
            return null;
        }
    }

    function saveCheckedKeys(keys) {
        var json = JSON.stringify(keys);
        if (window.UiState) {
            UiState.set(STORAGE_KEY, json);
            return;
        }
        try {
            localStorage.setItem(STORAGE_KEY, json);
        } catch (e) {
            /* ignore */
        }
    }

    function checkedKeys() {
        var keys = [];
        $('#backupItemList input[type="checkbox"][data-key]:checked').each(function () {
            keys.push($(this).data('key'));
        });
        return keys;
    }

    // ---------- 渲染清单 ----------

    function itemLabel(item) {
        // nameKey 形如 profile.item.memory，回落到 key
        var label = I18n.t(item.nameKey);
        return label === item.nameKey ? item.key : label;
    }

    // 导入预览里按 key 反查条目名（key 与 manifest 一致）
    function itemLabelByKey(key) {
        var nameKeyMap = {
            settings: 'profile.item.settings', skills: 'profile.item.skills',
            agents: 'profile.item.agents', commands: 'profile.item.commands',
            memory: 'profile.item.memory', skins: 'profile.item.skins'
        };
        var nk = nameKeyMap[key];
        if (!nk) return key;
        var label = I18n.t(nk);
        return label === nk ? key : label;
    }

    function countBadge(item) {
        var c = item.count || {};
        if (c.files !== undefined) {
            return I18n.t('backup.countFiles').replace('{n}', c.files);
        }
        return '';
    }

    // settings 项的多段统计（models/mcpServers/...），单独成行展示
    function statParts(item) {
        var c = item.count || {};
        var parts = [];
        ['models', 'mcpServers', 'apiServers', 'lspServers', 'providers', 'mountPools'].forEach(function (k) {
            if (c[k] !== undefined && c[k] > 0) {
                parts.push(k + ': ' + c[k]);
            }
        });
        return parts;
    }

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (ch) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
        });
    }

    function renderItems(items) {
        var saved = loadCheckedKeys() || [];
        var html = '';
        (items || []).forEach(function (item) {
            var checked = saved.length ? saved.indexOf(item.key) >= 0 : !!item.defaultChecked;
            var badge = countBadge(item);
            var stats = statParts(item)
                .map(function (p) { return '<span class="backup-item-stat">' + esc(p) + '</span>'; })
                .join('');
            html += '<div class="backup-item">'
                + '<label class="backup-item-checkbox">'
                + '<input type="checkbox" data-key="' + esc(item.key) + '"' + (checked ? ' checked' : '') + '/>'
                + '<span class="backup-item-checkmark"></span></label>'
                + '<div class="backup-item-info">'
                + '<div class="backup-item-name">' + esc(itemLabel(item))
                + (badge ? '<span class="backup-item-count">' + esc(badge) + '</span>' : '') + '</div>'
                + '<span class="backup-item-path">' + esc(item.sourcePath) + '</span>'
                + (stats ? '<div class="backup-item-stats">' + stats + '</div>' : '')
                + '</div></div>';
        });
        $('#backupItemList').html(html);
    }

    function workspaceSuffix() {
        return typeof window.wsAndSuffix === 'function' ? window.wsAndSuffix() : '';
    }

    function loadManifest() {
        $.ajax({
            url: '/web/admin/backup/manifest?_t=' + Date.now() + workspaceSuffix(),
            method: 'GET',
            dataType: 'json'
        }).done(function (resp) {
            if (resp && resp.code === 200 && resp.data) {
                renderItems(resp.data.items);
            } else {
                showToast((resp && resp.message) || I18n.t('backup.loadManifestFailed'), 'error');
            }
        }).fail(function () {
            showToast(I18n.t('toast.networkError'), 'error');
        });
    }

    // ---------- 导出 ----------

    function exportBackup() {
        var keys = checkedKeys();
        if (!keys.length) {
            showToast(I18n.t('backup.noItemsSelected'), 'error');
            return;
        }
        saveCheckedKeys(keys);
        var includeSecrets = $('#backupIncludeSecrets').is(':checked');
        function startDownload() {
            var ts = new Date().toISOString().replace(/[-:T]/g, '').substring(0, 14);
            var a = document.createElement('a');
            a.href = '/web/admin/backup/export?keys=' + encodeURIComponent(keys.join(','))
                + '&includeSecrets=' + includeSecrets + workspaceSuffix();
            a.download = 'soloncode-backup-' + ts + '.zip';
            a.style.display = 'none';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            showToast(I18n.t('backup.exportStart'));
        }
        if (includeSecrets) {
            confirmBox(I18n.t('backup.confirmSecrets'), startDownload);
        } else {
            startDownload();
        }
    }

    // ---------- 导入（三步式：选文件 → 预览 → 提交） ----------

    var _pendingFile = null;

    // layer.confirm 异步回调风格封装；layer 不可用时降级 window.confirm
    function confirmBox(msg, onOk) {
        if (typeof layer !== 'undefined' && layer.confirm) {
            layer.confirm(msg, {
                title: I18n.t('common.confirm'),
                btn: [I18n.t('common.confirm'), I18n.t('common.cancel')],
                icon: 3,
                offset: '120px'
            }, function (index) {
                layer.close(index);
                onOk();
            });
            return;
        }
        if (window.confirm(String(msg).replace(/<br>/g, '\n'))) {
            onOk();
        }
    }

    function importParse(file) {
        _pendingFile = file;
        var fd = new FormData();
        fd.append('file', file);
        $.ajax({
            url: '/web/admin/backup/import/parse?_t=' + Date.now() + workspaceSuffix(),
            method: 'POST',
            data: fd,
            processData: false,
            contentType: false,
            dataType: 'json'
        }).done(function (resp) {
            if (resp && resp.code === 200 && resp.data) {
                showPreview(resp.data);
            } else {
                showToast((resp && resp.message) || I18n.t('backup.parseFailed'), 'error');
            }
        }).fail(function () {
            showToast(I18n.t('toast.networkError'), 'error');
        });
    }

    function previewLines(data) {
        var lines = [];
        if (data.hasSettings) {
            (data.settingsGroups || []).forEach(function (g) {
                lines.push('+ settings/' + g.group + ' (' + g.count + ')');
            });
        }
        (data.assets || []).forEach(function (a) {
            lines.push('+ ' + itemLabelByKey(a.key) + ': '
                + I18n.t('backup.assetFiles', { n: a.files, added: a.newFiles, overwritten: a.overwriteFiles }));
        });
        return lines;
    }

    function showPreview(data) {
        var lines = previewLines(data);
        if (!lines.length) {
            showToast(I18n.t('backup.emptyBackup'), 'error');
            return;
        }
        var msg = I18n.t('backup.previewTitle') + '<br>' + lines.map(function (l) { return esc(l); }).join('<br>')
            + (data.includeSecrets === false ? '<br><br>' + esc(I18n.t('backup.maskedNote')) : '');
        confirmBox(msg, importCommit);
    }

    function importCommit() {
        if (!_pendingFile) return;
        var keys = checkedKeys();
        if (!keys.length) {
            showToast(I18n.t('backup.noItemsSelected'), 'error');
            return;
        }
        var fd = new FormData();
        fd.append('file', _pendingFile);
        $.ajax({
            url: '/web/admin/backup/import/commit?keys=' + encodeURIComponent(keys.join(',')) + workspaceSuffix(),
            method: 'POST',
            data: fd,
            processData: false,
            contentType: false,
            dataType: 'json'
        }).done(function (resp) {
            if (resp && resp.code === 200 && resp.data) {
                var detail = (resp.data.applied || []).join('\n');
                if (resp.data.warnings && resp.data.warnings.length) {
                    detail += '\n' + resp.data.warnings.join('\n');
                }
                showToast(detail || I18n.t('backup.importDone'), 'success');
                // settings 已变更：管理台没有加载完整设置面板，直接调用热重载接口。
                if (typeof window.settingsReloadFromDisk === 'function') {
                    window.settingsReloadFromDisk();
                } else {
                    $.ajax({ url: '/web/admin/backup/reload', method: 'POST', dataType: 'json', timeout: 60000 })
                        .done(function (reloadResp) {
                            if (!reloadResp || reloadResp.code !== 200) {
                                showToast(I18n.t('settings.reloadFailed'), 'error');
                            }
                        })
                        .fail(function () { showToast(I18n.t('settings.reloadFailed'), 'error'); });
                }
                loadManifest();
            } else {
                showToast((resp && resp.message) || I18n.t('backup.importFailed'), 'error');
            }
        }).fail(function () {
            showToast(I18n.t('toast.networkError'), 'error');
        }).always(function () {
            _pendingFile = null;
        });
    }

    // ---------- 事件绑定 ----------

    $(document).on('click', '#backupExportBtn', function (e) {
        e.preventDefault();
        exportBackup();
    });

    $(document).on('click', '#backupImportBtn', function (e) {
        e.preventDefault();
        $('#backupImportFileInput').val('').trigger('click');
    });

    $(document).on('change', '#backupImportFileInput', function () {
        var file = this.files && this.files[0];
        if (file) {
            importParse(file);
        }
    });

    // 首次进入 backup Tab 时加载清单（懒加载）
    $(document).on('click', '.settings-tab[data-tab="backup"]', function () {
        if (!$('#backupItemList').children().length) {
            loadManifest();
        }
    });

    // 导出时可下载；供 app-settings.js 的 loadActiveTabData 调用
    window.backupExportBackup = exportBackup;
    window.backupLoadManifest = loadManifest;
})();

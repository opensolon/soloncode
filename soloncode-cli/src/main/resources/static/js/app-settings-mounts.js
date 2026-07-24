/**
 * app-settings-mounts.js — 设置面板子模块
 */
(function () {
    'use strict';

    var core = window._settingsCore;
    var escapeHtml = core.escapeHtml;
    var escapeAttr = core.escapeAttr;
    var parseKvLines = core.parseKvLines;
    var postJson = core.postJson;
    var showToast = core.showToast;
    var setScopeValue = core.setScopeValue;
    var setScopeReadonly = core.setScopeReadonly;

    var $mountsList = $('#mountsList');
    var $mountsListView = $('#mountsListView');
    var $mountsFormView = $('#mountsFormView');
    var $mountsSkillsView = $('#mountsSkillsView');
    var $mountsSkillsList = $('#mountsSkillsList');
    var $mountsFormTitle = $('#mountsFormTitle');
    var $mountsSkillsTitle = $('#mountsSkillsTitle');
    var $mountsSaveBtn = $('#mountsSaveBtn');
    var mountsCachedList = [];
    var mountsCurrentAlias = null;
    var mountsCurrentType = null;
    var mountsCurrentRealPath = null;
    var mountsEditAlias = null;
    var $mountsTypeBtns = $('#mountsTypeToggle .settings-segmented-btn');

    function showMountsListView() { $mountsFormView.hide(); $mountsSkillsView.hide(); $mountsListView.addClass('slide-back').show(); setTimeout(function(){ $mountsListView.removeClass('slide-back'); }, 260); }
    function showMountsFormView(title) { $mountsFormTitle.text(title || '添加挂载'); $mountsListView.hide(); $mountsSkillsView.hide(); $mountsFormView.show(); }
    function showMountsSkillsView() { $mountsListView.hide(); $mountsFormView.hide(); $mountsSkillsView.show(); }
    function setMountsType(type) {
        $mountsTypeBtns.removeClass('active');
        $mountsTypeBtns.filter('[data-type="' + type + '"]').addClass('active');
    }
    function getMountsType() {
        return $mountsTypeBtns.filter('.active').attr('data-type') || 'SKILLS';
    }

    // ==================== 挂载管理 ====================

    function loadMountsList() {
        $.get('/web/settings/mounts', function (resp) {
            if (resp.code === 200 && resp.data) {
                mountsCachedList = resp.data;
                renderMountsList(resp.data);
            }
        });
    }

    function refreshPanels() {
        if (typeof window.loadTree === 'function') window.loadTree();
        if (typeof window.loadGitWorkspaces === 'function') window.loadGitWorkspaces();
        if (typeof window.loadGitStatus === 'function') window.loadGitStatus();
    }

    function renderMountsList(list) {
        var html = '';
        if (!list || list.length === 0) {
            html = '<div class="settings-empty-state">'
                + '<div class="settings-empty-icon"><i class="fa-solid fa-inbox fa-3x"></i></div>'
                + '<div class="settings-empty-title">暂无挂载</div>'
                + '<div class="settings-empty-desc">挂载是本地目录映射，为 AI 提供技能加载路径</div></div>';
        } else {
            // 系统挂载排前面
            var sorted = list.slice().sort(function (a, b) {
                var as = a.system === true ? 0 : 1;
                var bs = b.system === true ? 0 : 1;
                return as - bs;
            });
            sorted.forEach(function (item) {
                var alias = item.alias || '';
                var path = item.path || '';
                var isSystem = item.system === true;
                var typeMap = { SKILLS: 'S', FILES: 'F', AGENTS: 'A' };
                var iconText = typeMap[item.type] || (item.type ? item.type.charAt(0).toUpperCase() : 'M');
                html += '<div class="settings-list-item mounts-pool-item' + (isSystem ? ' mounts-system' : '') + (item.enabled === false ? ' disabled' : '') + '" data-alias="' + escapeAttr(alias) + '">'
                    + '<div class="settings-list-icon">' + escapeHtml(iconText) + '</div>'
                    + '<div class="settings-list-info">'
                    + '<div class="settings-list-title">' + escapeHtml(alias)
                    + (isSystem ? ' <span class="mounts-system-badge">系统</span>' : '')
                    + (item.scope === 'workspace' ? ' <span class="mounts-scope-badge scope-workspace">工作区</span>' : '')
                    + (item.writeable ? ' <span class="mounts-writeable-badge">可写</span>' : '')
                    + '</div>'
                    + (item.description ? '<div class="settings-list-desc settings-muted-text">' + escapeHtml(item.description) + '</div>' : '')
                    + (path ? '<div class="settings-list-desc">' + escapeHtml(path) + '</div>' : '')
                    + '</div><div class="settings-list-actions">'
                    + '<button class="settings-action-btn edit mounts-edit-btn" data-alias="' + escapeAttr(alias) + '" title="编辑"><i class="fa-regular fa-pen-to-square"></i></button>'
                    + '<label class="toggle-switch" title="' + ((item.enabled !== false) ? '停用' : '启用') + '">'
                    + '<input type="checkbox" ' + (item.enabled !== false ? 'checked' : '') + ' data-alias="' + escapeAttr(alias) + '" class="mounts-toggle"/> '
                    + '<span class="toggle-slider"></span>'
                    + '</label>'
                    + '</div></div>';
            });
        }
        $mountsList.html(html);
    }

    // 从缓存查找挂载类型
    function getMountType(alias) {
        var item = mountsCachedList.find(function (m) { return m.alias === alias; });
        return item ? (item.type || 'SKILLS') : 'SKILLS';
    }

    // 编辑挂载（只允许编辑描述和可写）
    function mountsEditPool(alias) {
        var item = mountsCachedList.find(function (m) { return m.alias === alias; });
        if (!item) return;
        mountsEditAlias = alias;
        var isSystem = item.system === true;

        // 填充表单（别名和路径只读）
        $('#mountsAlias').val(item.alias || '').prop('readOnly', true);
        $('#mountsPath').val(item.path || '').prop('readOnly', true);
        var isSystemScope = isSystem;
        setMountsType(item.type || 'SKILLS');
        $mountsTypeBtns.prop('disabled', true).addClass('disabled');
        setScopeValue('mountsScope', item.scope || 'user');
        setScopeReadonly('mountsScope', isSystemScope);
        $('#mountsWriteable').prop('checked', !!item.writeable).prop('disabled', isSystem);
        $('#mountsDescription').val(item.description || '').prop('readOnly', isSystem);

        // 编辑模式：隐藏保存按钮（系统挂载）、预设区；非系统：显示删除按钮
        $mountsSaveBtn.toggle(!isSystem);
        $('#mountsFormActions').toggle(!isSystem);
        $('#mountsPresetsDivider, .mounts-presets').hide(); // 编辑时始终隐藏预设区

        // 只读输入控件浅灰底色
        $('#mountsAlias, #mountsPath').addClass('readonly-gray');
        if (isSystem) { $('#mountsDescription').addClass('readonly-gray'); }
        else { $('#mountsDescription').removeClass('readonly-gray'); }

        $mountsSaveBtn.text('更新');
        showMountsFormView('编辑挂载');
    }

    // 池列表事件委托
    $mountsList
        .on('click', '.settings-action-btn.edit.mounts-edit-btn', function (e) {
            e.stopPropagation();
            var alias = $(this).attr('data-alias');
            mountsEditPool(alias);
        })
        .on('click', '.mounts-pool-item', function (e) {
            if ($(e.target).closest('.toggle-switch').length) return;
            if ($(e.target).closest('.settings-action-btn').length) return;
            var alias = $(this).attr('data-alias');
            loadMountsContent(alias, getMountType(alias));
        })
        .on('change', '.mounts-toggle', function () {
            var alias = $(this).attr('data-alias');
            var enabled = this.checked;
            $.ajax({
                url: '/web/settings/mounts/toggle',
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({ alias: alias, enabled: enabled }),
                success: function (resp) {
                    if (resp.code === 200) {
                        layer.msg(enabled ? '已启用' : '已停用', { icon: 1, time: 1500, offset: '120px' });
                        refreshPanels();
                        loadMountsList();
                    } else {
                        layer.msg(resp.message || '操作失败', { icon: 2, time: 3000, offset: '120px' });
                    }
                },
                error: function () {
                    layer.msg('操作失败，请检查网络', { icon: 2, time: 3000, offset: '120px' });
                }
            });
        });

    // 池内容加载与渲染（按类型分发）
    function loadMountsContent(alias, type) {
        mountsCurrentAlias = alias;
        mountsCurrentType = type || 'SKILLS';

        // 从缓存列表中查找 realPath
        var mountItem = mountsCachedList.find(function (m) { return m.alias === alias; });
        mountsCurrentRealPath = mountItem ? (mountItem.realPath || '') : '';

        var titleMap = { SKILLS: '技能包列表', AGENTS: '子代理列表', FILES: '文件列表' };
        $mountsSkillsTitle.text(alias + ' - ' + (titleMap[mountsCurrentType] || '内容列表'));
        showMountsSkillsView();
        $mountsSkillsList.html('<div class="settings-empty-state"><div class="skills-loading" style="display:block"><i class="fa-solid fa-circle-notch" style="animation:spin 1s linear infinite"></i><span>加载中...</span></div></div>');

        $.get('/web/settings/mounts/content', { alias: alias, type: mountsCurrentType }, function (resp) {
            if (resp.code === 200 && resp.data) renderMountsContent(resp.data, mountsCurrentType);
            else {
                $mountsSkillsList.html('<div class="settings-empty-state"><div class="settings-empty-title">' + escapeHtml(resp.message || '加载失败') + '</div></div>');
                layer.msg(resp.message || '加载失败', { icon: 2, time: 3000, offset: '120px' });
            }
        }).fail(function () {
            $mountsSkillsList.html('<div class="settings-empty-state"><div class="settings-empty-title">加载失败</div></div>');
            layer.msg('加载失败，请检查网络', { icon: 2, time: 3000, offset: '120px' });
        });
    }

    function renderMountsContent(list, type) {
        if (type === 'AGENTS') { renderAgentsList(list); return; }
        if (type === 'FILES') {
            $mountsSkillsList.html('<div class="settings-empty-state">'
                + '<div class="settings-empty-icon"><i class="fa-solid fa-inbox fa-3x"></i></div>'
                + '<div class="settings-empty-title">暂不展示文件</div>'
                + '<div class="settings-empty-desc">文件浏览功能将在后续版本支持</div></div>');
            return;
        }
        renderSkillsList(list);
    }

    function renderSkillsList(list) {
        var html = '';
        if (!list || list.length === 0) {
            html = '<div class="settings-empty-state">'
                + '<div class="settings-empty-icon"><i class="fa-solid fa-inbox fa-3x"></i></div>'
                + '<div class="settings-empty-title">该池暂无技能包</div>'
                + '<div class="settings-empty-desc">通过技能市场安装，或手动将技能文件放入池目录</div></div>';
        } else {
            html += '<div class="mounts-skills-count">' + list.length + ' 个技能包</div>';
            list.forEach(function (skill) {
                html += '<div class="settings-list-item mounts-skill-item" data-real-path="' + escapeAttr(skill.realPath || '') + '">'
                    + '<div class="settings-list-info">'
                    + '<div class="settings-list-title">' + escapeHtml(skill.name) + '</div>'
                    + (skill.realPath ? '<div class="settings-list-desc">' + escapeHtml(skill.realPath) + '</div>' : '')
                    + (skill.description ? '<div class="settings-list-desc">' + escapeHtml(skill.description) + '</div>' : '')
                    + '</div><div class="settings-list-actions">'
                    + '<button class="settings-action-btn delete" data-skill="' + escapeAttr(skill.name) + '" title="删除技能包"><i class="fa-regular fa-trash-can"></i></button>'
                    + '</div></div>';
            });
        }
        $mountsSkillsList.html(html);
    }

    function renderAgentsList(list) {
        var html = '';
        if (!list || list.length === 0) {
            html = '<div class="settings-empty-state">'
                + '<div class="settings-empty-icon"><i class="fa-solid fa-inbox fa-3x"></i></div>'
                + '<div class="settings-empty-title">该池暂无子代理</div>'
                + '<div class="settings-empty-desc">将代理配置文件放入池目录即可自动加载</div></div>';
        } else {
            html += '<div class="mounts-skills-count">' + list.length + ' 个子代理</div>';
            list.forEach(function (agent) {
                var name = agent.name || '';
                var filePath = agent.filePath || '';
                html += '<div class="settings-list-item mounts-skill-item" data-real-path="' + escapeAttr(filePath) + '">'
                    + '<div class="settings-list-info">'
                    + '<div class="settings-list-title">' + escapeHtml(name) + '</div>'
                    + (filePath ? '<div class="settings-list-desc">' + escapeHtml(filePath) + '</div>' : '')
                    + '</div></div>';
            });
        }
        $mountsSkillsList.html(html);
    }

    // 技能包删除事件
    $mountsSkillsList.on('click', '.settings-action-btn.delete', function (e) {
        e.stopPropagation();
        var skillName = $(this).attr('data-skill');
        layer.confirm('确定删除技能包 "' + skillName + '"？此操作不可恢复。', { title: '确认删除', btn: ['删除', '取消'], icon: 3, offset: '120px' }, function(index) {
            layer.close(index);
            postJson('/web/settings/mounts/skills/remove', { alias: mountsCurrentAlias, skillName: skillName }, function (resp) {
                if (resp.code === 200) { showToast('删除成功'); loadMountsContent(mountsCurrentAlias, mountsCurrentType); }
                else showToast('删除失败: ' + (resp.message || ''), 'error');
            });
        });
    });

    // 点击技能/子代理条目 → 打开其所在目录
    $mountsSkillsList.on('click', '.mounts-skill-item', function (e) {
        if ($(e.target).closest('.settings-action-btn').length) return;
        var realPath = $(this).data('real-path') || '';
        if (realPath) {
            $.get('/web/settings/mounts/open', { path: realPath }, function (resp) {
                if (resp && resp.code !== 200) {
                    layer.msg(resp.message || '打开目录失败', { icon: 2, time: 3000, offset: '120px' });
                }
            }).fail(function () {
                layer.msg('打开目录失败', { icon: 2, time: 3000, offset: '120px' });
            });
        }
    });

    // 打开挂载根目录按钮
    $('#mountsOpenDirBtn').on('click', function () {
        if (mountsCurrentRealPath) {
            $.get('/web/settings/mounts/open', { path: mountsCurrentRealPath }, function (resp) {
                if (resp && resp.code !== 200) {
                    layer.msg(resp.message || '打开目录失败', { icon: 2, time: 3000, offset: '120px' });
                }
            }).fail(function () {
                layer.msg('打开目录失败', { icon: 2, time: 3000, offset: '120px' });
            });
        }
    });

    // 刷新挂载内容按钮
    $('#mountsRefreshBtn').on('click', function () {
        if (mountsCurrentAlias) {
            var alias = mountsCurrentAlias;
            var type = mountsCurrentType;
            mountsCurrentAlias = null; // 强制重新加载
            loadMountsContent(alias, type);
        }
    });

    // 删除按钮（在二次编辑页）
    $('#mountsFormDeleteBtn').on('click', function () {
        var alias = mountsEditAlias;
        if (!alias) return;
        layer.confirm('确定移除挂载 "' + alias + '"？（磁盘文件不会被删除）', { title: '确认移除', btn: ['移除', '取消'], icon: 3, offset: '120px' }, function(index) {
            layer.close(index);
            $.post('/web/settings/mounts/remove', { alias: alias }, function (resp) {
                if (resp && resp.code === 200) { showToast('已移除'); mountsEditAlias = null; showMountsListView(); loadMountsList(); refreshPanels(); }
                else showToast('移除失败: ' + ((resp && resp.message) || '未知错误'), 'error');
            }, 'json').fail(function () { showToast('网络错误', 'error'); });
        });
    });

    // 添加/返回/保存按钮
    $('#mountsAddBtn').on('click', function () {
        mountsEditAlias = null;
        $('#mountsAlias').val('').prop('readOnly', false).removeClass('readonly-gray');
        $('#mountsPath').val('').prop('readOnly', false).removeClass('readonly-gray');
        setMountsType('SKILLS');
        $mountsTypeBtns.prop('disabled', false).removeClass('disabled');
        $('#mountsWriteable').prop('checked', false).prop('disabled', false);
        $('#mountsDescription').val('').prop('readOnly', false).removeClass('readonly-gray');
        setScopeValue('mountsScope', 'user');
        setScopeReadonly('mountsScope', false);
        $('#mountsFormActions').hide();
        $('#mountsPresetsDivider, .mounts-presets').show();
        $mountsSaveBtn.text('保存').show();
        showMountsFormView('添加挂载');
    });
    $('#mountsBackBtn').on('click', function () { mountsEditAlias = null; showMountsListView(); });
    $('#mountsSkillsBackBtn').on('click', function () { showMountsListView(); loadMountsList(); });

    $mountsSaveBtn.on('click', function () {
        var alias = $('#mountsAlias').val().trim();
        var path = $('#mountsPath').val().trim();
        if (!alias) { showToast('别名为必填项', 'error'); return; }
        if (!/^@/.test(alias)) { showToast('别名必须以 @ 开头', 'error'); return; }
        if (!path) { showToast('路径为必填项', 'error'); return; }

        var type = getMountsType();
        var writeable = $('#mountsWriteable').is(':checked');
        var description = $('#mountsDescription').val().trim();

        var isEdit = !!mountsEditAlias;
        var url = isEdit ? '/web/settings/mounts/update' : '/web/settings/mounts/add';
        var actionText = isEdit ? '更新' : '添加';

        var bodyObj = { alias: alias, path: path, type: type, writeable: writeable, description: description, scope: $('#mountsScope').val() || 'user' };

        $mountsSaveBtn.prop('disabled', true);
        $.ajax({ url: url, method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json' })
            .done(function (resp) {
                if (resp.code === 200) { showToast(actionText + '成功'); mountsEditAlias = null; loadMountsList(); showMountsListView(); refreshPanels(); }
                else showToast(actionText + '失败: ' + (resp.message || ''), 'error');
            })
            .fail(function () { showToast('网络错误', 'error'); })
            .always(function () { $mountsSaveBtn.prop('disabled', false); });
    });

    // 常见挂载预设按钮 - 仅在添加模式下可用，编辑模式下禁止点击
    $(document).on('click', '.mounts-preset-btn', function (e) {
        if (mountsEditAlias) { e.preventDefault(); return; }
        var alias = $(this).data('alias');
        var path = $(this).data('path');
        $('#mountsAlias').val(alias);
        $('#mountsPath').val(path);
        setMountsType('SKILLS');
        $('#mountsWriteable').prop('checked', false);
        $('#mountsDescription').val('');
    });

    // 类型联动
    $('#mountsTypeToggle').on('click', '.settings-segmented-btn', function () {
        if ($(this).prop('disabled')) return;
        $mountsTypeBtns.removeClass('active');
        $(this).addClass('active');
    });


    window._settingsMounts = { load: loadMountsList, reset: function(){ mountsEditAlias = null; }, showList: showMountsListView };
})();

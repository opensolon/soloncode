/* ===== app-loop.js ===== */
/* 循环任务面板交互 */
/* 依赖: app-base.js */

(function() {
    var $welcomeLoopBtn = $('#welcomeLoopBtn');
    var $chatLoopBtn = $('#chatLoopBtn');
    var $welcomeLoopPanel = $('#welcomeLoopPanel');
    var $chatLoopPanel = $('#chatLoopPanel');
    var loopPanelVisible = false;
    var loopEditId = null; // 当前编辑的任务 ID，null 表示新建

    // 获取当前激活的面板和按钮
    function getActivePanel() {
        return inChatMode ? $chatLoopPanel : $welcomeLoopPanel;
    }
    function getActiveBtn() {
        return inChatMode ? $chatLoopBtn : $welcomeLoopBtn;
    }

    // ========== 面板开关 ==========
    function toggleLoopPanel() {
        var $panel = getActivePanel();
        if ($panel.is(':visible')) {
            $panel.hide();
            loopPanelVisible = false;
            loopEditId = null;
        } else {
            closeAllToolbarPanels();
            $panel.show();
            loopPanelVisible = true;
            renderLoopList();
        }
    }

    function hideLoopPanel() {
        $welcomeLoopPanel.hide();
        $chatLoopPanel.hide();
        loopPanelVisible = false;
        loopEditId = null;
    }

    $welcomeLoopBtn.on('click', function(e) {
        e.stopPropagation();
        toggleLoopPanel();
    });
    $chatLoopBtn.on('click', function(e) {
        e.stopPropagation();
        toggleLoopPanel();
    });

    // 点击面板外部关闭
    $(document).on('click', function(e) {
        if (loopPanelVisible) {
            var $panel = getActivePanel();
            if (!$(e.target).closest('#chatLoopPanel, #welcomeLoopPanel, .loop-panel').length &&
                !$(e.target).closest('#chatLoopBtn, #welcomeLoopBtn').length) {
                hideLoopPanel();
            }
        }
    });

    // ========== API 调用 ==========
    function loopApi(action, params, callback) {
        var data = params || {};
        data.sessionId = SESSION_ID;
        $.ajax({
            url: '/web/chat/loop/' + action,
            method: action === 'list' ? 'GET' : 'POST',
            data: data,
            dataType: 'json',
            success: function(res) {
                if (callback) callback(res);
            },
            error: function() {
                showToast('操作失败', 'error');
            }
        });
    }

    // ========== 列表渲染 ==========
    function renderLoopList() {
        loopApi('list', null, function(res) {
            var items = (res && res.data) ? res.data : [];
            var html = '<div class="loop-panel-header">';
            html += '<span class="loop-panel-title">循环任务 (' + items.length + ')</span>';
            html += '<button class="loop-panel-add-btn" id="loopAddNewBtn">+ 新建</button>';
            html += '<button class="loop-panel-close-btn" id="loopCloseBtn">✕</button>';
            html += '</div>';
            html += '<div class="loop-panel-list">';

            if (items.length === 0) {
                html += '<div class="loop-panel-empty">暂无循环任务</div>';
            } else {
                for (var i = 0; i < items.length; i++) {
                    var t = items[i];
                    var statusText = t.cancelled ? '已取消' : (!t.enabled ? '已停用' : (t.running ? '运行中' : '就绪'));
                    var statusClass = t.cancelled ? 'cancelled' : (!t.enabled ? 'disabled' : (t.running ? 'running' : 'ready'));
                    var scheduleText = t.cron ? ('cron: ' + t.cron) : ('每' + t.intervalMinutes + '分钟');
                    var lastInfo = '';
                    if (t.lastExecutedAt) {
                        var ago = formatTimeAgo(t.lastExecutedAt);
                        lastInfo = '<span class="loop-item-meta">上次: ' + ago + '</span>';
                    }
                    if (t.currentIteration > 0) {
                        lastInfo += '<span class="loop-item-meta">第' + t.currentIteration + '次</span>';
                    }

                    html += '<div class="loop-item" data-id="' + t.id + '">';
                    html += '<div class="loop-item-row">';
                    html += '<span class="loop-item-dot ' + statusClass + '"></span>';
                    if (t.name) {
                        html += '<span class="loop-item-name">' + escapeHtml(t.name) + '</span>';
                        html += '<span class="loop-item-id">#' + escapeHtml(t.id) + '</span>';
                    } else {
                        html += '<span class="loop-item-name">' + escapeHtml(t.id) + '</span>';
                    }
                    html += '<span class="loop-item-schedule">' + scheduleText + '</span>';
                    html += '<span class="loop-item-status ' + statusClass + '">' + statusText + '</span>';
                    html += '<div class="loop-item-actions">';
                    if (!t.cancelled) {
                        html += '<button class="loop-action-btn" data-action="toggle" data-id="' + t.id + '" data-enabled="' + t.enabled + '" title="' + (t.enabled ? '停用' : '启用') + '">' + (t.enabled ? '⏸' : '▶') + '</button>';
                        html += '<button class="loop-action-btn" data-action="trigger" data-id="' + t.id + '" title="手动触发">▶</button>';
                        html += '<button class="loop-action-btn" data-action="edit" data-id="' + t.id + '" title="编辑"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>';
                    }
                    html += '<button class="loop-action-btn danger" data-action="remove" data-id="' + t.id + '" title="删除">✕</button>';
                    html += '</div>';
                    html += '</div>';
                    html += '<div class="loop-item-prompt">' + escapeHtml(t.prompt) + '</div>';
                    if (lastInfo) {
                        html += '<div class="loop-item-info">' + lastInfo + '</div>';
                    }
                    html += '</div>';
                }
            }

            html += '</div>';

            var $panel = getActivePanel();
            $panel.html(html);
            bindListEvents();
        });
    }

    // ========== 列表事件绑定 ==========
    function bindListEvents() {
        $('#loopCloseBtn').on('click', function() {
            hideLoopPanel();
        });
        $('#loopAddNewBtn').on('click', function() {
            loopEditId = null;
            renderLoopForm();
        });
        $(document).off('click.loopaction').on('click.loopaction', '.loop-action-btn', function(e) {
            e.stopPropagation();
            var action = $(this).data('action');
            var id = $(this).data('id');

            if (action === 'toggle') {
                loopApi('toggle', { taskId: id }, function() {
                    renderLoopList();
                    showToast('操作成功', 'success');
                });
            } else if (action === 'trigger') {
                loopApi('trigger', { taskId: id }, function() {
                    showToast('已触发执行', 'success');
                });
            } else if (action === 'remove') {
                if (!confirm('确定要删除该循环任务吗？')) return;
                loopApi('remove', { taskId: id }, function() {
                    renderLoopList();
                    showToast('已删除', 'success');
                });
            } else if (action === 'edit') {
                loopEditId = id;
                renderLoopForm();
            }
        });
    }

    // ========== 表单渲染 ==========
    function renderLoopForm() {
        var html = '<div class="loop-panel-header">';
        html += '<button class="loop-panel-back-btn" id="loopBackBtn">← 返回列表</button>';
        html += '<span class="loop-panel-title">' + (loopEditId ? '编辑任务' : '新建循环任务') + '</span>';
        html += '</div>';
        html += '<div class="loop-form">';
        html += '<div class="loop-form-group">';
        html += '<label>名称</label>';
        html += '<input type="text" class="loop-input" id="loopFormName" placeholder="例如: morning-triage"/>';
        html += '</div>';
        html += '<div class="loop-form-group">';
        html += '<label>提示词 <span class="loop-required">*</span></label>';
        html += '<textarea class="loop-textarea" id="loopFormPrompt" rows="2" placeholder="例如: 检查CI状态并汇总失败用例"></textarea>';
        html += '</div>';
        html += '<div class="loop-form-group">';
        html += '<label>间隔</label>';
        html += '<div class="loop-interval-row">';
        html += '<label class="loop-radio"><input type="radio" name="loopScheduleType" value="interval" checked/> 固定间隔</label>';
        html += '<input type="number" class="loop-input loop-input-sm" id="loopFormInterval" value="5" min="1" max="1440"/>';
        html += '<select class="loop-input loop-input-sm" id="loopFormIntervalUnit"><option value="m" selected>分钟</option><option value="h">小时</option></select>';
        html += '</div>';
        html += '<div class="loop-interval-row">';
        html += '<label class="loop-radio"><input type="radio" name="loopScheduleType" value="cron"/> Cron 表达式</label>';
        html += '<input type="text" class="loop-input" id="loopFormCron" placeholder="0 */5 * * * ?"/>';
        html += '</div>';
        html += '</div>';
        html += '<div class="loop-form-advanced-toggle" id="loopAdvancedToggle">▸ 高级选项</div>';
        html += '<div class="loop-form-advanced" id="loopAdvanced" style="display:none">';
        html += '<div class="loop-form-group"><label>技能引用</label><input type="text" class="loop-input" id="loopFormSkill" placeholder="$skill-name"/></div>';
        html += '<div class="loop-form-group"><label>目标条件</label><input type="text" class="loop-input" id="loopFormGoal" placeholder="all tests pass"/></div>';
        html += '<div class="loop-form-group"><label>执行者</label><input type="text" class="loop-input" id="loopFormMaker" placeholder="coder"/></div>';
        html += '<div class="loop-form-group"><label>验证者</label><input type="text" class="loop-input" id="loopFormChecker" placeholder="reviewer"/></div>';
        html += '<div class="loop-form-group"><label>通知通道</label><input type="text" class="loop-input" id="loopFormNotify" placeholder="feishu"/></div>';
        html += '<div class="loop-form-group"><label>最大迭代</label><input type="number" class="loop-input loop-input-sm" id="loopFormMaxIter" value="20" min="1"/></div>';
        html += '</div>';
        html += '<div class="loop-form-actions">';
        html += '<button class="loop-btn-secondary" id="loopFormTriggerBtn" style="display:' + (loopEditId ? 'inline-block' : 'none') + '">测试运行</button>';
        html += '<button class="loop-btn-primary" id="loopFormSaveBtn">保存</button>';
        html += '</div>';
        html += '</div>';

        var $panel = getActivePanel();
        $panel.html(html);
        bindFormEvents();

        // 如果是编辑，先加载数据
        if (loopEditId) {
            loopApi('list', null, function(res) {
                var items = (res && res.data) ? res.data : [];
                for (var i = 0; i < items.length; i++) {
                    if (items[i].id === loopEditId) {
                        fillFormData(items[i]);
                        break;
                    }
                }
            });
        }
    }

    function fillFormData(t) {
        $('#loopFormName').val(t.name || '');
        $('#loopFormPrompt').val(t.prompt || '');
        if (t.cron) {
            $('input[name=loopScheduleType][value=cron]').prop('checked', true);
            $('#loopFormCron').val(t.cron);
        } else {
            $('input[name=loopScheduleType][value=interval]').prop('checked', true);
            var mins = t.intervalMinutes || 5;
            if (mins >= 60 && mins % 60 === 0) {
                $('#loopFormInterval').val(mins / 60);
                $('#loopFormIntervalUnit').val('h');
            } else {
                $('#loopFormInterval').val(mins);
                $('#loopFormIntervalUnit').val('m');
            }
        }
        if (t.skillRef) $('#loopFormSkill').val(t.skillRef);
        if (t.goalCondition) $('#loopFormGoal').val(t.goalCondition);
        if (t.makerAgent) $('#loopFormMaker').val(t.makerAgent);
        if (t.checkerAgent) $('#loopFormChecker').val(t.checkerAgent);
        if (t.channelNotify) $('#loopFormNotify').val(t.channelNotify);
        if (t.maxIterations) $('#loopFormMaxIter').val(t.maxIterations);
    }

    // ========== 表单事件绑定 ==========
    function bindFormEvents() {
        $('#loopBackBtn').on('click', function() {
            loopEditId = null;
            renderLoopList();
        });

        $('#loopAdvancedToggle').on('click', function() {
            var $adv = $('#loopAdvanced');
            if ($adv.is(':visible')) {
                $adv.hide();
                $(this).text('▸ 高级选项');
            } else {
                $adv.show();
                $(this).text('▾ 高级选项');
            }
        });

        // 间隔类型切换
        $('input[name=loopScheduleType]').on('change', function() {
            var isCron = $(this).val() === 'cron';
            $('#loopFormInterval').prop('disabled', isCron);
            $('#loopFormIntervalUnit').prop('disabled', isCron);
            $('#loopFormCron').prop('disabled', !isCron);
        });

        // 保存
        $('#loopFormSaveBtn').on('click', function() {
            var prompt = $('#loopFormPrompt').val().trim();
            if (!prompt) {
                showToast('请输入提示词', 'error');
                return;
            }

            var isCron = $('input[name=loopScheduleType]:checked').val() === 'cron';
            var cronVal = isCron ? $('#loopFormCron').val().trim() : null;
            var intervalVal = null;
            if (!isCron) {
                var num = parseInt($('#loopFormInterval').val()) || 5;
                var unit = $('#loopFormIntervalUnit').val();
                intervalVal = unit === 'h' ? num * 60 : num;
            }

            var params = {
                name: $('#loopFormName').val().trim(),
                prompt: prompt,
                intervalMinutes: intervalVal,
                cron: cronVal,
                skillRef: $('#loopFormSkill').val().trim() || null,
                goalCondition: $('#loopFormGoal').val().trim() || null,
                makerAgent: $('#loopFormMaker').val().trim() || null,
                checkerAgent: $('#loopFormChecker').val().trim() || null,
                channelNotify: $('#loopFormNotify').val().trim() || null,
                maxIterations: parseInt($('#loopFormMaxIter').val()) || null
            };

            if (loopEditId) {
                params.taskId = loopEditId;
                loopApi('update', params, function(res) {
                    if (res && res.code === 200) {
                        showToast('已更新', 'success');
                        loopEditId = null;
                        renderLoopList();
                    } else {
                        showToast((res && res.message) || '更新失败', 'error');
                    }
                });
            } else {
                loopApi('add', params, function(res) {
                    if (res && res.code === 200) {
                        showToast('已创建', 'success');
                        loopEditId = null;
                        renderLoopList();
                    } else {
                        showToast((res && res.message) || '创建失败', 'error');
                    }
                });
            }
        });

        // 测试运行
        $('#loopFormTriggerBtn').on('click', function() {
            if (loopEditId) {
                loopApi('trigger', { taskId: loopEditId }, function() {
                    showToast('已触发执行', 'success');
                });
            }
        });
    }

    // ========== 工具函数 ==========
    function formatTimeAgo(isoStr) {
        if (!isoStr) return '';
        try {
            var date = new Date(isoStr);
            var now = new Date();
            var diffMs = now - date;
            var diffSec = Math.floor(diffMs / 1000);
            if (diffSec < 60) return diffSec + '秒前';
            var diffMin = Math.floor(diffSec / 60);
            if (diffMin < 60) return diffMin + '分钟前';
            var diffHour = Math.floor(diffMin / 60);
            if (diffHour < 24) return diffHour + '小时前';
            return Math.floor(diffHour / 24) + '天前';
        } catch (e) {
            return isoStr;
        }
    }

    // ========== 公开 API ==========
    window.refreshLoopPanel = function() {
        if (loopPanelVisible) renderLoopList();
    };
})();

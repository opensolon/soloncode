/* ===== app-loop.js ===== */
/* 循环任务面板交互 — v2 Goal 增强版 */
/* 依赖: app-base.js */

(function() {
    var $newChatLoopBtn = $('#newChatLoopBtn');
    var $chatLoopBtn = $('#chatLoopBtn');
    var $newChatLoopPanel = $('#newChatLoopPanel');
    var $chatLoopPanel = $('#chatLoopPanel');
    var loopPanelVisible = false;
    var loopEditId = null;

    // 使用 layui layer 风格的浮动提示
    function showToast(msg, type) {
        if (typeof layer !== 'undefined' && layer.msg) {
            layer.msg(msg, { icon: type === 'error' ? 2 : 1, time: 2500, offset: '120px' });
        }
    }

    function getActivePanel() {
        return inChatMode ? $chatLoopPanel : $newChatLoopPanel;
    }
    function getActiveBtn() {
        return inChatMode ? $chatLoopBtn : $newChatLoopBtn;
    }

    // ========== 预设模板 v3（精选真实场景版） ==========
    function getLoopTemplates() {
        return [
            {
                id: 'auto-fix',
                icon: 'AF',
                name: I18n.t('loop.tpl.autoFix.name'),
                desc: I18n.t('loop.tpl.autoFix.desc'),
                data: {
                    prompt: I18n.t('loop.tpl.autoFix.prompt'),
                    intervalMinutes: 10,
                    type: 'GOAL',
                    runNow: true
                }
            },
            {
                id: 'code-review',
                icon: 'CR',
                name: I18n.t('loop.tpl.codeReview.name'),
                desc: I18n.t('loop.tpl.codeReview.desc'),
                data: {
                    prompt: I18n.t('loop.tpl.codeReview.prompt'),
                    cron: '0 0 9 * * ? *',
                    type: 'HEARTBEAT',
                    runNow: false
                }
            },
            {
                id: 'dep-security',
                icon: 'DS',
                name: I18n.t('loop.tpl.depSecurity.name'),
                desc: I18n.t('loop.tpl.depSecurity.desc'),
                data: {
                    prompt: I18n.t('loop.tpl.depSecurity.prompt'),
                    intervalMinutes: 60,
                    type: 'HEARTBEAT',
                    runNow: false
                }
            },
            {
                id: 'test-coverage',
                icon: 'TC',
                name: I18n.t('loop.tpl.testCoverage.name'),
                desc: I18n.t('loop.tpl.testCoverage.desc'),
                data: {
                    prompt: I18n.t('loop.tpl.testCoverage.prompt'),
                    intervalMinutes: 15,
                    type: 'GOAL',
                    runNow: true
                }
            }
        ];
    }

    // ========== 工具函数 ==========
    function formatTimeAgo(isoStr) {
        if (!isoStr) return '';
        try {
            var date = new Date(isoStr);
            var now = new Date();
            var diffMs = now - date;
            var diffSec = Math.floor(diffMs / 1000);
            if (diffSec < 60) return I18n.t('loop.timeAgo.secondsAgo', {n: diffSec});
            var diffMin = Math.floor(diffSec / 60);
            if (diffMin < 60) return I18n.t('loop.timeAgo.minutesAgo', {n: diffMin});
            var diffHour = Math.floor(diffMin / 60);
            if (diffHour < 24) return I18n.t('loop.timeAgo.hoursAgo', {n: diffHour});
            return I18n.t('loop.timeAgo.daysAgo', {n: Math.floor(diffHour / 24)});
        } catch (e) {
            return isoStr;
        }
    }

    // ========== 预算/间隔 单位解析与格式化 ==========
    function parseTokenBudget(val) {
        if (!val) return null;
        val = val.trim().toLowerCase();
        if (val.endsWith('m')) return parseInt(val, 10) * 1000000;
        if (val.endsWith('k')) return parseInt(val, 10) * 1000;
        return parseInt(val, 10) || null;
    }

    function parseDurationMs(val) {
        if (!val) return null;
        val = val.trim().toLowerCase();
        if (val.endsWith('h')) return parseInt(val, 10) * 3600000;
        if (val.endsWith('m')) return parseInt(val, 10) * 60000;
        // 纯数字默认分钟
        return (parseInt(val, 10) || 0) * 60000;
    }

    function parseIntervalMinutes(val) {
        if (!val) return 5;
        val = val.trim().toLowerCase();
        if (val.endsWith('h')) return (parseInt(val, 10) || 1) * 60;
        if (val.endsWith('m')) return parseInt(val, 10) || 5;
        return parseInt(val, 10) || 5;
    }

    function formatTokenBudget(val) {
        if (!val) return '';
        if (val % 1000000 === 0) return (val / 1000000) + 'm';
        if (val % 1000 === 0) return (val / 1000) + 'k';
        return String(val);
    }

    function formatDurationBudget(ms) {
        if (!ms) return '';
        var mins = Math.floor(ms / 60000);
        if (mins >= 60 && mins % 60 === 0) return (mins / 60) + 'h';
        return mins + 'm';
    }

    // Goal 状态映射（与 GoalState.Status 对齐）
    function getGoalStatusLabel() {
        return {
            PURSUING: I18n.t('loop.status.pursuing'),
            PAUSED: I18n.t('loop.status.paused'),
            ACHIEVED: I18n.t('loop.status.achieved'),
            BUDGET_LIMITED: I18n.t('loop.status.budgetLimited'),
            BLOCKED: I18n.t('loop.status.blocked')
        };
    }

    // ========== 统一状态解析（合并 running / goal 两套机制）==========
    // 无论任务是 HEARTBEAT 还是 GOAL，都通过此函数获取单一状态
    function resolveTaskState(t) {
        // 终态/禁用态优先
        if (t.cancelled) return { text: I18n.t('loop.status.cancelled'), cls: 'cancelled' };
        if (!t.enabled) return { text: I18n.t('loop.status.disabled'), cls: 'disabled' };

        // Goal 任务：以 goal.status 为唯一依据（running 在其面前是冗余的）
        if (t.goal && t.goal.status) {
            var label = getGoalStatusLabel()[t.goal.status] || t.goal.status;
            // 将 goal 状态映射到已有的 CSS 语义（部分复用，部分新增）
            var clsMap = {
                PURSUING: 'running',
                PAUSED: 'paused',
                ACHIEVED: 'achieved',
                BLOCKED: 'cancelled',
                BUDGET_LIMITED: 'cancelled'
            };
            return { text: label, cls: clsMap[t.goal.status] || 'ready' };
        }

        // Heartbeat 任务：使用 running 标记
        if (t.running) return { text: I18n.t('loop.status.running'), cls: 'running' };
        return { text: I18n.t('loop.status.ready'), cls: 'ready' };
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

    $(document).on('keydown.loopesc', function(e) {
        if (e.key === 'Escape' && loopPanelVisible) {
            hideLoopPanel();
        }
    });

    function hideLoopPanel() {
        $newChatLoopPanel.hide();
        $chatLoopPanel.hide();
        loopPanelVisible = false;
        loopEditId = null;
    }

    $newChatLoopBtn.on('click', function(e) {
        e.stopPropagation();
        toggleLoopPanel();
    });
    $chatLoopBtn.on('click', function(e) {
        e.stopPropagation();
        toggleLoopPanel();
    });

    // 整理记忆：点击后向当前输入框填入“整理记忆”，并关闭“更多”菜单
    function closeMoreMenus() {
        $('#chatMoreMenu, #newChatMoreMenu').removeClass('open');
        $('#chatMoreBtn, #newChatMoreBtn').attr('aria-expanded', 'false');
    }
    function fillMemoryText() {
        closeMoreMenus();
        var input = (typeof inChatMode !== 'undefined' && inChatMode) ? chatInput : newChatInput;
        if (!input) return;
        input.value = I18n.t('toolbar.organizeMemory');
        if (typeof autoResize === 'function') autoResize(input);
        input.focus();
    }
    $('#newChatMemoryBtn').on('click', function(e) {
        e.stopPropagation();
        fillMemoryText();
    });
    $('#chatMemoryBtn').on('click', function(e) {
        e.stopPropagation();
        fillMemoryText();
    });

    $newChatLoopPanel.add($chatLoopPanel).on('click', function(e) {
        e.stopPropagation();
    });

    $(document).on('mousedown', function(e) {
        if (loopPanelVisible) {
            if (!$(e.target).closest('#chatLoopPanel, #newChatLoopPanel').length &&
                !$(e.target).closest('#chatLoopBtn, #newChatLoopBtn').length) {
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
            method: (action === 'list' || action === 'get') ? 'GET' : 'POST',
            data: data,
            dataType: 'json',
            success: function(res) {
                if (callback) callback(res);
            },
            error: function() {
                showToast(I18n.t('toast.operateFailed'), 'error');
                if (callback) callback(null);
            }
        });
    }

    // ========== 列表渲染 ==========
    function renderLoopList() {
        loopApi('list', null, function(res) {
            var items = (res && res.data) ? res.data : [];
            var html = buildListHeader(items.length);
            html += '<div class="loop-panel-list">';

            if (items.length === 0) {
                html += '<div class="loop-panel-empty">' + I18n.t('loop.empty') + '</div>';
            } else {
                for (var i = 0; i < items.length; i++) {
                    html += buildListItem(items[i]);
                }
            }

            html += '</div>';

            var $panel = getActivePanel();
            $panel.html(html);
            bindListEvents();

            // 有运行中任务时自动刷新列表
            scheduleListAutoRefresh(items);
        });
    }

    function buildListHeader(count) {
        var html = '<div class="loop-panel-header">';
        html += '<span class="loop-panel-title">' + I18n.t('loop.title') + ' (' + count + ')</span>';
        html += '<button class="loop-panel-add-btn" id="loopAddNewBtn" title="' + I18n.t('loop.addNew') + '">' +
            '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
            '<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg></button>';
        html += '</div>';
        return html;
    }

    function buildListItem(t) {
        // ★ 统一通过 resolveTaskState 获取状态，不再分别读 running / goal
        var state = resolveTaskState(t);
        var scheduleText = t.cron ? ('cron: ' + t.cron) : (I18n.t('loop.every') + t.intervalMinutes + I18n.t('loop.minutesSuffix'));

        // 标签
        var tags = [];
        if (t.runNow) tags.push('<span class="loop-tag loop-tag-now">now</span>');

        // 底部信息
        var lastInfo = '';
        if (t.lastExecutedAt) {
            lastInfo += '<span class="loop-item-meta">' + I18n.t('loop.lastRun') + formatTimeAgo(t.lastExecutedAt) + '</span>';
        }
        if (t.currentIteration > 0) {
            lastInfo += '<span class="loop-item-meta">' + I18n.t('loop.iteration', {n: t.currentIteration}) + '</span>';
        }

        // ★ 统一的状态标签：Goal 任务始终展示（PAUSED/ACHIEVED/BLOCKED 等都是有信息量的状态）
        //   Heartbeat 任务为减少视觉噪音，仅在运行/取消时展示
        var showBadge = (t.goal && t.goal.status) || state.cls === 'running' || state.cls === 'cancelled';
        var statusHtml = showBadge
            ? '<span class="loop-item-status ' + state.cls + '">' + state.text + '</span>'
            : '';

        var html = '<div class="loop-item" data-id="' + t.id + '">';
        html += '<div class="loop-item-row">';
        html += '<span class="loop-item-dot ' + state.cls + '"></span>';
        html += '<span class="loop-item-name">#' + escapeHtml(t.id) + '</span>';
        html += '<span class="loop-item-schedule">' + scheduleText + '</span>';
        html += statusHtml;
        if (tags.length) html += '<span class="loop-item-tags">' + tags.join('') + '</span>';
        html += '<div class="loop-item-actions">';
        if (!t.cancelled) {
            html += '<button class="loop-action-btn" data-action="toggle" data-id="' + t.id + '" data-enabled="' + t.enabled + '" title="' + (t.enabled ? I18n.t('loop.disable') : I18n.t('loop.enable')) + '">' +
                (t.enabled
                    ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>'
                    : '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><polygon points="5,3 19,12 5,21"/></svg>') +
                '</button>';
            html += '<button class="loop-action-btn" data-action="trigger" data-id="' + t.id + '" title="' + I18n.t('loop.trigger') + '">' +
                '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg></button>';
            html += '<button class="loop-action-btn" data-action="edit" data-id="' + t.id + '" title="' + I18n.t('loop.edit') + '">' +
                '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>';
        }
        html += '<button class="loop-action-btn danger" data-action="remove" data-id="' + t.id + '" title="' + I18n.t('common.delete') + '">' +
            '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg></button>';
        html += '</div>';
        html += '</div>';
        html += '<div class="loop-item-prompt" title="' + escapeHtml(t.prompt) + '">' + escapeHtml(t.prompt) + '</div>';

        if (lastInfo) {
            html += '<div class="loop-item-info">' + lastInfo + '</div>';
        }
        html += '</div>';

        return html;
    }

    // ========== 列表自动刷新 ==========
    var listRefreshTimerId = null;

    function scheduleListAutoRefresh(items) {
        if (listRefreshTimerId) { clearInterval(listRefreshTimerId); listRefreshTimerId = null; }
        var hasRunning = items && items.some(function(t) { return t.running && !t.cancelled; });
        if (hasRunning) {
            listRefreshTimerId = setInterval(function() {
                if (loopPanelVisible && !loopEditId) {
                    renderLoopList();
                }
            }, 10000);
        }
    }

    // ========== 列表事件绑定 ==========
    function bindListEvents() {
        var $panel = getActivePanel();

        $panel.find('#loopAddNewBtn').on('click', function(e) {
            e.stopPropagation();
            loopEditId = null;
            renderLoopForm();
        });

        $(document).off('click.loopaction');
        $panel.off('click.loopaction').on('click.loopaction', '.loop-action-btn', function(e) {
            e.stopPropagation();
            var action = $(this).data('action');
            var id = $(this).data('id');

            if (action === 'toggle') {
                loopApi('toggle', { taskId: id }, function(res) {
                    if (res) { renderLoopList(); showToast(I18n.t('loop.operateSuccess'), 'success'); }
                });
            } else if (action === 'trigger') {
                loopApi('trigger', { taskId: id }, function(res) {
                    if (res) {
                        showToast(I18n.t('loop.triggered'), 'success');
                        if (typeof switchToChatMode === 'function') switchToChatMode();
                        hideLoopPanel();
                        var $item = $panel.find('.loop-item[data-id="' + id + '"]');
                        $item.css('background', 'var(--accent-light)');
                        setTimeout(function() { $item.css('background', ''); }, 600);
                    }
                });
            } else if (action === 'remove') {
                var doRemove = function() {
                    loopApi('remove', { taskId: id }, function(res) {
                        if (res) { renderLoopList(); showToast(I18n.t('loop.deleted'), 'success'); }
                    });
                };
                if (typeof layer !== 'undefined' && layer.confirm) {
                    layer.confirm(I18n.t('loop.confirmDelete'), {
                        title: I18n.t('loop.confirmDeleteTitle'), btn: [I18n.t('common.delete'), I18n.t('common.cancel')], icon: 3, offset: '120px'
                    }, function(index) {
                        layer.close(index);
                        doRemove();
                    });
                }
            } else if (action === 'edit') {
                loopEditId = id;
                renderLoopForm();
            }
        });

    }

    // ========== 表单渲染 ==========
    function renderLoopForm() {
        var html = '<div class="loop-panel-header">';
        html += '<button class="loop-panel-back-btn" id="loopBackBtn">' +
            '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg></button>';
        html += '<span class="loop-panel-title">' + (loopEditId ? I18n.t('loop.editLoop', {id: escapeHtml(loopEditId)}) : I18n.t('loop.newLoop')) + '</span>';
        // 模板按钮（仅新建时显示）
        if (!loopEditId) {
            html += '<div class="loop-tpl-dropdown" id="loopTplDropdown">';
            html += '<button class="loop-tpl-trigger" id="loopTplBtn" title="' + I18n.t('loop.fillTemplate') + '">' +
                '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/><polyline points="10 9 9 9 8 9"/></svg></button>';
            html += '<div class="loop-tpl-menu" id="loopTplMenu">';
            var _tpls = getLoopTemplates();
            for (var i = 0; i < _tpls.length; i++) {
                var tpl = _tpls[i];
                html += '<div class="loop-tpl-item" data-tpl="' + tpl.id + '">';
                html += '<span class="loop-tpl-icon">' + tpl.icon + '</span>';
                html += '<div class="loop-tpl-info">';
                html += '<span class="loop-tpl-name">' + escapeHtml(tpl.name) + '</span>';
                html += '<span class="loop-tpl-desc">' + escapeHtml(tpl.desc) + '</span>';
                html += '</div>';
                html += '</div>';
            }
            html += '</div>';
            html += '</div>';
        }
        html += '</div>';

        // ★ 表单：Goal 从折叠面板提升到主表单
        html += '<div class="loop-form">';

        // ★ Tab 栏（放在最上方，对应 LoopTask.TaskType）
        html += '<div class="loop-tab-bar">';
        html += '<div class="loop-tab active" data-tab="heartbeat">' + I18n.t('loop.tabHeartbeat') + '</div>';
        html += '<div class="loop-tab" data-tab="goal">' + I18n.t('loop.tabGoal') + '</div>';
        html += '</div>';

        // 任务描述
        html += '<div class="loop-form-group">';
        html += '<label>' + I18n.t('loop.taskDesc') + ' <span class="loop-required">*</span></label>';
        html += '<textarea class="loop-input loop-textarea" id="loopFormPrompt" rows="2" placeholder=""></textarea>';
        html += '<div class="loop-form-goal-hint" id="loopFormGoalHint" style="display:none;">' + I18n.t('loop.goalHint') + '</div>';
        html += '</div>';

        // ===== Heartbeat 表单区：调度方式 =====
        html += '<div class="loop-form-section active" data-section="heartbeat">';
        html += '<div class="loop-form-schedule">';
        html += '<div class="loop-form-group">';
        html += '<label>' + I18n.t('loop.scheduleMethod') + '</label>';
        html += '<div class="loop-interval-row" style="flex-wrap:wrap;">';
        html += '<label class="loop-radio"><input type="radio" name="loopScheduleType" value="interval" checked/> ' + I18n.t('loop.fixedInterval') + '</label>';
        html += '<input type="text" class="loop-input loop-input-sm" id="loopFormInterval" value="5m" placeholder="' + I18n.t('loop.intervalPlaceholder') + '"/>';
        html += '<label class="loop-checkbox" style="margin-left:8px;white-space:nowrap;"><input type="checkbox" id="loopFormRunNow" checked/> ' + I18n.t('loop.runNow') + '</label>';
        html += '</div>';
        html += '<div class="loop-interval-row">';
        html += '<label class="loop-radio"><input type="radio" name="loopScheduleType" value="cron"/> ' + I18n.t('loop.cronExpression') + '</label>';
        html += '<input type="text" class="loop-input loop-input-sm" id="loopFormCron" placeholder="0 */5 * * * ? *"/>';
        html += '<span class="loop-cron-hint">' + I18n.t('loop.cronExamples') + '</span> ';
        html += '<a class="loop-cron-link" data-cron="0 0 */2 * * ? *">' + I18n.t('loop.cronEvery2h') + '</a>';
        html += '<a class="loop-cron-link" data-cron="0 0 22 * * ? *">' + I18n.t('loop.cronDaily22') + '</a> ';
        html += '<a class="loop-cron-link" data-cron="0 0 0 ? * 1 *">' + I18n.t('loop.cronWeeklyMon') + '</a> ';
        html += '</div>';
        html += '</div>';
        html += '</div>';  // 结束 loop-form-schedule



        html += '</div>';  // 结束 heartbeat section

        // ===== Goal 表单区（简洁，无调度字段）=====
        html += '<div class="loop-form-section" data-section="goal">';

        // ★ Goal 预算控制（同一行）
        html += '<div class="loop-form-group" style="margin-top:12px;">';
        html += '<div class="loop-form-inline">';
        html += '<div class="loop-form-inline-item" style="flex:1;">';
        html += '<label>' + I18n.t('loop.tokenBudget') + '</label>';
        html += '<input type="text" inputmode="numeric" class="loop-input" id="loopFormMaxTokens" placeholder="' + I18n.t('loop.tokenBudgetPlaceholder') + '" list="loopMaxTokensList" autocomplete="off"/>' +
            '<datalist id="loopMaxTokensList">' +
            '<option value="512k"><option value="5m"><option value="10m">' +
            '</datalist></div>';
        html += '<div class="loop-form-inline-item" style="flex:1;margin-left:12px;">';
        html += '<label>' + I18n.t('loop.durationBudget') + '</label>';
        html += '<input type="text" class="loop-input" id="loopFormMaxDuration" placeholder="' + I18n.t('loop.durationBudgetPlaceholder') + '"/>';
        html += '</div>';
        html += '</div>';  // 结束 loop-form-inline
        html += '</div>';



        html += '</div>';  // 结束 goal section
        html += '</div>';  // 结束 loop-form（scrollable 区）

        // 操作按钮（在 loop-form 外部，固定在面板底部）
        html += '<div class="loop-form-actions">';
        html += '<button class="loop-btn-secondary" id="loopFormCancelBtn">' + I18n.t('common.cancel') + '</button>';
        html += '<button class="loop-btn-primary" id="loopFormSaveBtn">' + I18n.t('common.save') + '</button>';
        html += '</div>';

        var $panel = getActivePanel();
        $panel.addClass('mode-form');
        $panel.css('height', '400px');
        $panel.html(html);
        bindFormEvents();

        // 编辑模式：加载数据
        if (loopEditId) {
            var editTaskId = loopEditId;
            var $inputs = $panel.find('.loop-input, .loop-checkbox input, select');
            $inputs.prop('disabled', true);
            $panel.find('#loopFormSaveBtn').prop('disabled', true).text(I18n.t('common.loading') + '...');
            loopApi('get', { taskId: editTaskId }, function(res) {
                // 校验是否仍在编辑同一个任务（用户可能已点击返回）
                if (loopEditId !== editTaskId) return;
                var $p = getActivePanel();
                var t = (res && res.data) ? res.data : null;
                if (t) {
                    fillFormData(t);
                    // ★ 统一通过 resolveTaskState 获取状态
                    var state = resolveTaskState(t);
                    var $title = $p.find('.loop-panel-title');
                    var titleHtml = I18n.t('loop.editLoop', {id: escapeHtml(editTaskId)}) +
                        ' <span class="loop-item-status ' + state.cls + '" style="margin-left:6px;font-size:11px">' + state.text + '</span>' +
                        (t.currentIteration > 0 ? '<span class="loop-item-meta" style="margin-left:6px">' + I18n.t('loop.executedTimes', {n: t.currentIteration}) + '</span>' : '');
                    $title.html(titleHtml);
                } else if (res !== null) {
                    showToast(I18n.t('loop.taskNotFound'), 'error');
                }
                $p.find('.loop-input, .loop-checkbox input, select').prop('disabled', false);
                $p.find('input[name=loopScheduleType]:checked').trigger('change');
                $p.find('#loopFormSaveBtn').prop('disabled', false).text(I18n.t('common.save'));
            });
        }
    }

    function fillFormData(t) {
        var $panel = getActivePanel();
        $panel.find('#loopFormPrompt').val(t.prompt || '');
        if (t.cron) {
            $panel.find('input[name=loopScheduleType][value=cron]').prop('checked', true);
            $panel.find('#loopFormCron').val(t.cron);
        } else {
            $panel.find('input[name=loopScheduleType][value=interval]').prop('checked', true);
            var mins = t.intervalMinutes || 5;
            if (mins >= 60 && mins % 60 === 0) {
                $panel.find('#loopFormInterval').val((mins / 60) + 'h');
            } else {
                $panel.find('#loopFormInterval').val(mins + 'm');
            }
            $panel.find('#loopFormCron').val('');
        }
        // ★ 根据 type / t.goal 自动选择任务类型
        var hasGoal = t.type === 'GOAL' || t.goal;
        // 激活对应 tab
        $panel.find('.loop-tab').removeClass('active');
        $panel.find('.loop-form-section').removeClass('active');
        if (hasGoal) {
            $panel.find('.loop-tab[data-tab="goal"]').addClass('active');
            $panel.find('.loop-form-section[data-section="goal"]').addClass('active');
            $panel.find('#loopFormPrompt').closest('.loop-form-group').find('label').html(I18n.t('loop.goalDesc') + ' <span class="loop-required">*</span>');
        } else {
            $panel.find('.loop-tab[data-tab="heartbeat"]').addClass('active');
            $panel.find('.loop-form-section[data-section="heartbeat"]').addClass('active');
            $panel.find('#loopFormPrompt').closest('.loop-form-group').find('label').html(I18n.t('loop.taskDesc') + ' <span class="loop-required">*</span>');
        }
        // ★ cron 模式：runNow 不勾选且禁用（后端不支持）
        if (t.cron) {
            $panel.find('#loopFormRunNow').prop('checked', false);
        } else {
            $panel.find('#loopFormRunNow').prop('checked', !!t.runNow);
        }

        // ★ 预算字段
        if (t.maxTokens) $panel.find('#loopFormMaxTokens').val(formatTokenBudget(t.maxTokens));
        if (t.maxDurationMs) $panel.find('#loopFormMaxDuration').val(formatDurationBudget(t.maxDurationMs));

        // ★ 触发联动：radio change -> disabled 状态刷新
        $panel.find('input[name=loopScheduleType]:checked').trigger('change');
    }

    // ========== 表单事件绑定 ==========
    function bindFormEvents() {
        var $panel = getActivePanel();

        $panel.find('#loopBackBtn').on('click', function() {
            loopEditId = null;
            renderLoopList();
        });

        // 模板下拉菜单
        var $tplBtn = $panel.find('#loopTplBtn');
        var $tplMenu = $panel.find('#loopTplMenu');
        if ($tplBtn.length) {
            $tplBtn.on('click', function(e) {
                e.stopPropagation();
                $tplMenu.toggleClass('show');
            });
            $tplMenu.on('click', '.loop-tpl-item', function(e) {
                e.stopPropagation();
                var tplId = $(this).data('tpl');
                var tpl = null;
                var _tpls2 = getLoopTemplates();
                for (var i = 0; i < _tpls2.length; i++) {
                    if (_tpls2[i].id === tplId) { tpl = _tpls2[i]; break; }
                }
                if (tpl && tpl.data) fillFormData(tpl.data);
                $tplMenu.removeClass('show');
            });
            $(document).off('mousedown.looptpl').on('mousedown.looptpl', function(e) {
                if (!$(e.target).closest('#loopTplDropdown').length) {
                    $tplMenu.removeClass('show');
                }
            });
        }



        $panel.find('input[name=loopScheduleType]').on('change', function() {
            var isCron = $(this).val() === 'cron';
            $panel.find('#loopFormInterval').prop('disabled', isCron);
            $panel.find('#loopFormRunNow').prop('disabled', isCron);
            $panel.find('#loopFormCron').prop('disabled', !isCron);
        });

        // ★ Tab 切换（对应 LoopTask.TaskType）
        $panel.find('.loop-tab').on('click', function() {
            var tab = $(this).data('tab');
            var isGoal = tab === 'goal';
            // 切换 tab 激活态
            $panel.find('.loop-tab').removeClass('active');
            $(this).addClass('active');
            // 切换表单区
            $panel.find('.loop-form-section').removeClass('active');
            $panel.find('.loop-form-section[data-section="' + tab + '"]').addClass('active');

            // runNow 已移到 heartbeat section 内部，切换 section 时自动隐藏
            // 更新 prompt 标签
            $panel.find('#loopFormPrompt').closest('.loop-form-group').find('label').html(
                isGoal ? I18n.t('loop.goalDesc') + ' <span class="loop-required">*</span>' : I18n.t('loop.taskDesc') + ' <span class="loop-required">*</span>'
            );
            // 显示/隐藏目标提示
            var $hint = $panel.find('#loopFormGoalHint');
            if ($hint.length) {
                $hint.css('display', isGoal ? '' : 'none');
            }
        });

        // Cron 快捷示例
        $panel.find('.loop-cron-link').on('click', function(e) {
            e.preventDefault();
            var cron = $(this).data('cron');
            $panel.find('#loopFormCron').val(cron);
            $panel.find('input[name=loopScheduleType][value=cron]').prop('checked', true).trigger('change');
        });

        // 取消按钮
        $panel.find('#loopFormCancelBtn').on('click', function() {
            loopEditId = null;
            renderLoopList();
        });

        // 保存
        var $saveBtn = $panel.find('#loopFormSaveBtn');
        $saveBtn.on('click', function() {
            if ($saveBtn.prop('disabled')) return;

            var prompt = $panel.find('#loopFormPrompt').val().trim();
            if (!prompt) {
                showToast(I18n.t('loop.pleaseInputDesc'), 'error');
                return;
            }

            $saveBtn.prop('disabled', true).text(I18n.t('loop.saving') + '...');

            // ★ 根据活跃 tab 确定任务类型
            var activeTab = $panel.find('.loop-tab.active').data('tab');
            var isGoal = activeTab === 'goal';

            // ★ 收集预算字段（仅 Goal 模式有效）
            var maxTokensVal = isGoal ? $panel.find('#loopFormMaxTokens').val().trim() : null;
            var maxDurationVal = isGoal ? $panel.find('#loopFormMaxDuration').val().trim() : null;

            var effectiveRunNow = false;
            var effectiveInterval = null;
            var effectiveType = null;
            var cronVal = null;

            if (isGoal) {
                effectiveType = 'GOAL';
                effectiveRunNow = true;   // Goal 模式恒为 true
                effectiveInterval = 0;    // 后端转 5 秒安全网
            } else {
                effectiveType = 'HEARTBEAT';
                var isCron = $panel.find('input[name=loopScheduleType]:checked').val() === 'cron';
                cronVal = isCron ? $panel.find('#loopFormCron').val().trim() : null;
                if (!isCron) {
                    effectiveInterval = parseIntervalMinutes($panel.find('#loopFormInterval').val());
                    effectiveRunNow = $panel.find('#loopFormRunNow').is(':checked');
                } else {
                    effectiveRunNow = false;
                }
            }

            var params = {
                prompt: prompt,
                intervalMinutes: effectiveInterval,
                cron: cronVal,
                type: effectiveType,
                runNow: effectiveRunNow,

                maxTokens: parseTokenBudget(maxTokensVal),
                maxDurationMs: parseDurationMs(maxDurationVal)
            };

            function restoreBtn() {
                $saveBtn.prop('disabled', false).text(I18n.t('common.save'));
            }

            if (loopEditId) {
                params.taskId = loopEditId;
                loopApi('update', params, function(res) {
                    if (res && res.code === 200) {
                        showToast(I18n.t('loop.updated'), 'success');
                        loopEditId = null;
                        renderLoopList();
                    } else {
                        restoreBtn();
                        showToast((res && res.message) || I18n.t('loop.updateFailed'), 'error');
                    }
                });
            } else {
                loopApi('add', params, function(res) {
                    if (res && res.code === 200) {
                        showToast(I18n.t('loop.created'), 'success');
                        loopEditId = null;
                        renderLoopList();
                    } else {
                        restoreBtn();
                        showToast((res && res.message) || I18n.t('loop.createFailed'), 'error');
                    }
                });
            }
        });


    }

    // ========== 公开 API ==========
    window.refreshLoopPanel = function() {
        if (loopPanelVisible) renderLoopList();
    };
    window.toggleLoopPanel = toggleLoopPanel;

    // 面板显示时移除表单模式 class
    var _origRenderLoopList = renderLoopList;
    renderLoopList = function() {
        var $p = getActivePanel();
        $p.removeClass('mode-form');
        $p.css('max-height', '');
        if (listRefreshTimerId) { clearInterval(listRefreshTimerId); listRefreshTimerId = null; }
        _origRenderLoopList();
    };
})();

/* ===== app-loop.js ===== */
/* 循环任务面板交互 — v2 Goal 增强版 */
/* 依赖: app-base.js */

(function() {
    var $welcomeLoopBtn = $('#welcomeLoopBtn');
    var $chatLoopBtn = $('#chatLoopBtn');
    var $welcomeLoopPanel = $('#welcomeLoopPanel');
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
        return inChatMode ? $chatLoopPanel : $welcomeLoopPanel;
    }
    function getActiveBtn() {
        return inChatMode ? $chatLoopBtn : $welcomeLoopBtn;
    }

    // ========== 预设模板 ==========
    var LOOP_TEMPLATES = [
        {
            id: 'auto-fix',
            icon: 'AF',
            name: '自动修复循环',
            desc: '反复尝试修复目标直到测试通过',
            data: {
                prompt: '运行测试套件，如果有失败的测试则分析原因并尝试修复代码',
                intervalMinutes: 10,
                goalCondition: 'all tests pass',
                worktreeEnabled: true,
                maxIterations: 10,
                runNow: true
            }
        },
        {
            id: 'daily-review',
            icon: 'CR',
            name: '每日代码审查',
            desc: '每天定时审查代码提交并汇总风险',
            data: {
                prompt: '审查昨天的所有代码提交，总结变更摘要和潜在风险点',
                cron: '0 9 * * *',
                goalCondition: null,
                worktreeEnabled: false,
                maxIterations: 20,
                runNow: false
            }
        },
        {
            id: 'daily-memory',
            icon: 'MR',
            name: '每晚记忆整理',
            desc: '每晚定时整理对话记忆，归纳用户偏好',
            data: {
                prompt: '回顾今天的所有对话记录，提取用户的偏好习惯、技术决策和重要约束，归纳整理后存入长期记忆',
                cron: '0 22 * * *',
                goalCondition: null,
                worktreeEnabled: false,
                maxIterations: 10,
                runNow: false
            }
        },
        {
            id: 'ci-monitor',
            icon: 'CI',
            name: 'CI 监控',
            desc: '定时检查构建状态，失败时分析原因',
            data: {
                prompt: '检查最近的 CI 构建状态，如果有失败的用例则分析失败原因并汇总报告',
                intervalMinutes: 30,
                goalCondition: null,
                worktreeEnabled: false,
                maxIterations: 20,
                runNow: false
            }
        },
        {
            id: 'health-check',
            icon: 'HC',
            name: '服务健康巡检',
            desc: '定时探测服务状态，异常时告警',
            data: {
                prompt: '检查所有核心服务的健康状态（HTTP 端点），如果有异常则汇总告警信息',
                intervalMinutes: 5,
                goalCondition: 'all services healthy',
                worktreeEnabled: false,
                maxIterations: 100,
                runNow: true
            }
        }
    ];

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

    function formatDuration(startedAt) {
        if (!startedAt) return '';
        try {
            var diffMs = Date.now() - new Date(startedAt).getTime();
            if (diffMs < 0) return '';
            var totalSec = Math.floor(diffMs / 1000);
            var m = Math.floor(totalSec / 60);
            var s = totalSec % 60;
            if (m >= 60) {
                var h = Math.floor(m / 60);
                m = m % 60;
                return h + 'h ' + m + 'm';
            }
            return m + 'm ' + s + 's';
        } catch (e) {
            return '';
        }
    }

    // Goal 状态中文映射
    var GOAL_STATUS_LABEL = {
        CREATING: '创建中',
        PURSUING: '执行中',
        PAUSED: '已暂停',
        ACHIEVED: '已达成',
        UNMET: '未达成',
        BUDGET_LIMITED: '预算耗尽',
        BLOCKED: '已阻塞',
        TERMINATED: '已结束'
    };

    // Goal 状态 CSS class 映射（转为小写）
    function goalStatusClass(status) {
        return (status || '').toLowerCase();
    }

    // 生成 goal 状态徽章 HTML
    function renderGoalBadge(g) {
        if (!g) return '';
        var cls = goalStatusClass(g.status);
        var label = GOAL_STATUS_LABEL[g.status] || g.status;
        var iterInfo = ' (' + (g.currentIteration || 0) + '/' + (g.maxIterations || '∞') + ')';
        return '<span class="loop-goal-badge ' + cls + '">' +
            '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>' +
            label + iterInfo + '</span>';
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

    $welcomeLoopPanel.add($chatLoopPanel).on('click', function(e) {
        e.stopPropagation();
    });

    $(document).on('mousedown', function(e) {
        if (loopPanelVisible) {
            if (!$(e.target).closest('#chatLoopPanel, #welcomeLoopPanel').length &&
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
            method: (action === 'list' || action === 'get') ? 'GET' : 'POST',
            data: data,
            dataType: 'json',
            success: function(res) {
                if (callback) callback(res);
            },
            error: function() {
                showToast('操作失败', 'error');
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
                html += '<div class="loop-panel-empty">暂无循环任务</div>';
            } else {
                for (var i = 0; i < items.length; i++) {
                    html += buildListItem(items[i]);
                }
            }

            html += '</div>';

            var $panel = getActivePanel();
            $panel.html(html);
            bindListEvents();

            // 定时器刷新时长（仅在有活跃 goal 的列表页）
            scheduleElapsedUpdater();
        });
    }

    function buildListHeader(count) {
        var html = '<div class="loop-panel-header">';
        html += '<span class="loop-panel-title">循环任务 (' + count + ')</span>';
        html += '<button class="loop-panel-add-btn" id="loopAddNewBtn" title="新建任务">' +
            '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
            '<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg></button>';
        html += '</div>';
        return html;
    }

    function buildListItem(t) {
        var statusText = t.cancelled ? '已取消' : (!t.enabled ? '已停用' : (t.running ? '运行中' : '就绪'));
        var statusClass = t.cancelled ? 'cancelled' : (!t.enabled ? 'disabled' : (t.running ? 'running' : 'ready'));
        var scheduleText = t.cron ? ('cron: ' + t.cron) : ('每' + t.intervalMinutes + '分钟');

        // 标签
        var tags = [];
        if (t.worktreeEnabled) tags.push('<span class="loop-tag loop-tag-wt">wt</span>');
        if (t.runNow) tags.push('<span class="loop-tag loop-tag-now">now</span>');

        // 底部信息
        var lastInfo = '';
        if (t.lastExecutedAt) {
            lastInfo += '<span class="loop-item-meta">上次: ' + formatTimeAgo(t.lastExecutedAt) + '</span>';
        }
        if (t.currentIteration > 0) {
            lastInfo += '<span class="loop-item-meta">第' + t.currentIteration + '次</span>';
        }

        // ★ Goal 行内标签（替代原卡片）
        var goalInlineHtml = '';
        var g = t.goal;
        if (g) {
            var cls = goalStatusClass(g.status);
            var label = GOAL_STATUS_LABEL[g.status] || g.status;
            var iterInfo = ' (' + (g.currentIteration || 0) + '/' + (g.maxIterations || '∞') + ')';
            var elapsed = formatDuration(g.startedAt);
            var elapsedHtml = elapsed ? '⏱' + elapsed : '';
            // 终态图标后缀
            var suffix = '';
            if (g.status === 'ACHIEVED') suffix = '✓';
            else if (g.status === 'UNMET') suffix = '✗';
            else if (g.status === 'BUDGET_LIMITED') suffix = '⚠';
            else if (g.status === 'BLOCKED') suffix = '⊘';
            goalInlineHtml = '<span class="loop-item-goal-inline ' + cls + '">' +
                '🎯' + label + iterInfo +
                (suffix ? suffix : '') +
                (elapsedHtml ? ' ' + elapsedHtml : '') + '</span>';
        }

        // 拼装完整 item
        var runningStatusHtml = (statusClass === 'running' || statusClass === 'cancelled')
            ? '<span class="loop-item-status ' + statusClass + '">' + statusText + '</span>'
            : '';

        var html = '<div class="loop-item" data-id="' + t.id + '">';
        html += '<div class="loop-item-row">';
        html += '<span class="loop-item-dot ' + statusClass + '"></span>';
        html += goalInlineHtml;
        html += '<span class="loop-item-name">#' + escapeHtml(t.id) + '</span>';
        html += '<span class="loop-item-schedule">' + scheduleText + '</span>';
        html += runningStatusHtml;
        if (tags.length) html += '<span class="loop-item-tags">' + tags.join('') + '</span>';
        html += '<div class="loop-item-actions">';
        if (!t.cancelled) {
            html += '<button class="loop-action-btn" data-action="toggle" data-id="' + t.id + '" data-enabled="' + t.enabled + '" title="' + (t.enabled ? '停用' : '启用') + '">' +
                (t.enabled
                    ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>'
                    : '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><polygon points="5,3 19,12 5,21"/></svg>') +
                '</button>';
            html += '<button class="loop-action-btn" data-action="trigger" data-id="' + t.id + '" title="手动触发">' +
                '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg></button>';
            html += '<button class="loop-action-btn" data-action="edit" data-id="' + t.id + '" title="编辑">' +
                '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>';
        }
        html += '<button class="loop-action-btn danger" data-action="remove" data-id="' + t.id + '" title="删除">' +
            '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg></button>';
        html += '</div>';
        html += '</div>';
        html += '<div class="loop-item-prompt">' + escapeHtml(t.prompt) + '</div>';

        if (lastInfo) {
            html += '<div class="loop-item-info">' + lastInfo + '</div>';
        }
        html += '</div>';

        return html;
    }

    // ========== 时长定时刷新 ==========
    var elapsedTimerId = null;

    function scheduleElapsedUpdater() {
        if (elapsedTimerId) clearInterval(elapsedTimerId);
        // 行内标签时长固定，无需定时刷新（每次列表渲染时重新计算）
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
                    if (res) { renderLoopList(); showToast('操作成功', 'success'); }
                });
            } else if (action === 'trigger') {
                loopApi('trigger', { taskId: id }, function(res) {
                    if (res) {
                        showToast('已触发执行', 'success');
                        if (typeof switchToChatMode === 'function') switchToChatMode();
                        hideLoopPanel();
                        var $item = $panel.find('.loop-item[data-id="' + id + '"]');
                        $item.css('background', 'var(--accent-light)');
                        setTimeout(function() { $item.css('background', ''); }, 600);
                    }
                });
            } else if (action === 'remove') {
                if (!confirm('确定要删除该循环任务吗？')) return;
                loopApi('remove', { taskId: id }, function(res) {
                    if (res) { renderLoopList(); showToast('已删除', 'success'); }
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
        html += '<button class="loop-panel-back-btn" id="loopBackBtn">' +
            '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg></button>';
        html += '<span class="loop-panel-title">' + (loopEditId ? '编辑循环 #' + escapeHtml(loopEditId) : '新建循环') + '</span>';
        // 模板按钮（仅新建时显示）
        if (!loopEditId) {
            html += '<div class="loop-tpl-dropdown" id="loopTplDropdown">';
            html += '<button class="loop-tpl-trigger" id="loopTplBtn" title="填充模板">' +
                '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/><polyline points="10 9 9 9 8 9"/></svg></button>';
            html += '<div class="loop-tpl-menu" id="loopTplMenu">';
            for (var i = 0; i < LOOP_TEMPLATES.length; i++) {
                var tpl = LOOP_TEMPLATES[i];
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

        // 任务描述
        html += '<div class="loop-form-group">';
        html += '<label>任务描述 <span class="loop-required">*</span></label>';
        html += '<input type="text" class="loop-input" id="loopFormPrompt" placeholder=""/>';
        html += '</div>';

        // ★ Goal 条件（直接在主表单中，不再隐藏）
        html += '<div class="loop-form-group">';
        html += '<label>Goal <span style="font-weight:400;color:var(--text-secondary);font-size:11px;">（可选，满足条件后自动停止）</span></label>';
        html += '<div class="loop-form-goal-row">';
        html += '<input type="text" class="loop-input" id="loopFormGoal" placeholder="例如: all tests pass"/>';
        html += '</div>';
        html += '</div>';

        // 间隔
        html += '<div class="loop-form-group">';
        html += '<label>调度方式</label>';
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

        // 高级选项（折叠：Worktree / RunNow / MaxIter / 预算）
        html += '<div class="loop-form-advanced-toggle collapsed" id="loopAdvancedToggle">' +
            '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 6 8 10 12 6"/></svg>' +
            '<span>高级选项</span></div>';
        html += '<div class="loop-form-advanced" id="loopAdvanced">';
        html += '<div class="loop-form-group loop-form-inline">';
        html += '<div class="loop-form-inline-item"><label>Worktree 隔离</label><label class="loop-checkbox"><input type="checkbox" id="loopFormWorktree"/> 在独立分支执行</label></div>';
        html += '<div class="loop-form-inline-item"><label>首次立即执行</label><label class="loop-checkbox"><input type="checkbox" id="loopFormRunNow"/> 保存后立即执行一次</label></div>';
        html += '<div class="loop-form-inline-item"><label>最大迭代</label><input type="number" class="loop-input loop-input-sm" id="loopFormMaxIter" value="20" min="1"/></div>';
        html += '</div>';

        // 预算控制
        html += '<div class="loop-form-group">';
        html += '<div class="loop-budget-row">';
        html += '<div class="loop-form-group"><label style="font-size:11px;">Token 预算</label><input type="number" class="loop-input loop-input-sm" id="loopFormMaxTokens" placeholder="留空不限制" min="0" style="width:100%;"/></div>';
        html += '<div class="loop-form-group"><label style="font-size:11px;">时间预算（分钟）</label><input type="number" class="loop-input loop-input-sm" id="loopFormMaxDuration" placeholder="留空不限制" min="0" style="width:100%;"/></div>';
        html += '</div>';
        html += '</div>';

        html += '</div>';

        // 操作按钮
        html += '<div class="loop-form-actions">';
        html += '<button class="loop-btn-secondary" id="loopFormTriggerBtn" style="display:' + (loopEditId ? 'inline-block' : 'none') + '">测试运行</button>';
        html += '<button class="loop-btn-primary" id="loopFormSaveBtn">保存</button>';
        html += '</div>';
        html += '</div>';

        var $panel = getActivePanel();
        $panel.addClass('mode-form');
        var panelTop = $panel[0].getBoundingClientRect().top;
        var safeMaxH = Math.max(460, Math.floor(panelTop) - 16);
        $panel.css('max-height', Math.min(safeMaxH, 640) + 'px');
        $panel.html(html);
        bindFormEvents();

        // 编辑模式：加载数据
        if (loopEditId) {
            var $inputs = $panel.find('.loop-input, .loop-checkbox input, select');
            $inputs.prop('disabled', true);
            $panel.find('#loopFormSaveBtn').prop('disabled', true).text('加载中...');
            loopApi('get', { taskId: loopEditId }, function(res) {
                var t = (res && res.data) ? res.data : null;
                if (t) {
                    fillFormData(t);
                    var statusText = t.cancelled ? '已取消' : (!t.enabled ? '已停用' : (t.running ? '运行中' : '就绪'));
                    var statusClass = t.cancelled ? 'cancelled' : (!t.enabled ? 'disabled' : (t.running ? 'running' : 'ready'));
                    var $title = $panel.find('.loop-panel-title');
                    $title.html('编辑循环 #' + escapeHtml(loopEditId) +
                        ' <span class="loop-item-status ' + statusClass + '" style="margin-left:6px;font-size:11px">' + statusText + '</span>' +
                        (t.currentIteration > 0 ? '<span class="loop-item-meta" style="margin-left:6px">已执行' + t.currentIteration + '次</span>' : ''));
                    // 如果有 goal 且非初始态，显示当前状态
                    var g = t.goal;
                    if (g) {
                        var badge = renderGoalBadge(g);
                        $title.append(' ' + badge);
                    }
                } else if (res !== null) {
                    showToast('未找到任务数据', 'error');
                }
                $inputs.prop('disabled', false);
                $panel.find('#loopFormSaveBtn').prop('disabled', false).text('保存');
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
                $panel.find('#loopFormInterval').val(mins / 60);
                $panel.find('#loopFormIntervalUnit').val('h');
            } else {
                $panel.find('#loopFormInterval').val(mins);
                $panel.find('#loopFormIntervalUnit').val('m');
            }
            $panel.find('#loopFormCron').val('');
        }
        // ★ goal 条件（从 goal 对象或旧字段读取）
        var g = t.goal;
        var goalVal = g ? g.condition : (t.goalCondition || '');
        $panel.find('#loopFormGoal').val(goalVal);
        $panel.find('#loopFormWorktree').prop('checked', !!t.worktreeEnabled);
        $panel.find('#loopFormRunNow').prop('checked', !!t.runNow);
        $panel.find('#loopFormMaxIter').val(t.maxIterations || '');

        // ★ 预算字段
        if (t.maxTokens) $panel.find('#loopFormMaxTokens').val(t.maxTokens);
        if (t.maxDurationMs) $panel.find('#loopFormMaxDuration').val(Math.floor(t.maxDurationMs / 60000));

        // 有高级字段时自动展开
        if (t.goalCondition || t.worktreeEnabled || t.runNow || t.maxTokens || t.maxDurationMs) {
            $panel.find('#loopAdvanced').show();
            $panel.find('#loopAdvancedToggle').removeClass('collapsed');
        } else {
            $panel.find('#loopAdvanced').hide();
            $panel.find('#loopAdvancedToggle').addClass('collapsed');
        }
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
                for (var i = 0; i < LOOP_TEMPLATES.length; i++) {
                    if (LOOP_TEMPLATES[i].id === tplId) { tpl = LOOP_TEMPLATES[i]; break; }
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

        $panel.find('#loopAdvancedToggle').on('click', function() {
            var $adv = $panel.find('#loopAdvanced');
            if ($adv.is(':visible')) {
                $adv.hide();
                $(this).addClass('collapsed');
            } else {
                $adv.show();
                $(this).removeClass('collapsed');
            }
        });

        $panel.find('input[name=loopScheduleType]').on('change', function() {
            var isCron = $(this).val() === 'cron';
            $panel.find('#loopFormInterval').prop('disabled', isCron);
            $panel.find('#loopFormIntervalUnit').prop('disabled', isCron);
            $panel.find('#loopFormCron').prop('disabled', !isCron);
        });

        // 保存
        var $saveBtn = $panel.find('#loopFormSaveBtn');
        $saveBtn.on('click', function() {
            if ($saveBtn.prop('disabled')) return;

            var prompt = $panel.find('#loopFormPrompt').val().trim();
            if (!prompt) {
                showToast('请输入任务描述', 'error');
                return;
            }

            $saveBtn.prop('disabled', true).text('保存中...');

            var isCron = $panel.find('input[name=loopScheduleType]:checked').val() === 'cron';
            var cronVal = isCron ? $panel.find('#loopFormCron').val().trim() : null;
            var intervalVal = null;
            if (!isCron) {
                var num = parseInt($panel.find('#loopFormInterval').val()) || 5;
                var unit = $panel.find('#loopFormIntervalUnit').val();
                intervalVal = unit === 'h' ? num * 60 : num;
            }

            // ★ 收集预算字段
            var maxTokensVal = $panel.find('#loopFormMaxTokens').val().trim();
            var maxDurationVal = $panel.find('#loopFormMaxDuration').val().trim();

            var params = {
                prompt: prompt,
                intervalMinutes: intervalVal,
                cron: cronVal,
                goalCondition: $panel.find('#loopFormGoal').val().trim() || null,
                worktreeEnabled: $panel.find('#loopFormWorktree').is(':checked'),
                runNow: $panel.find('#loopFormRunNow').is(':checked'),
                maxIterations: parseInt($panel.find('#loopFormMaxIter').val()) || null,
                maxTokens: maxTokensVal ? parseInt(maxTokensVal) : null,
                maxDurationMs: maxDurationVal ? parseInt(maxDurationVal) * 60000 : null
            };

            function restoreBtn() {
                $saveBtn.prop('disabled', false).text('保存');
            }

            if (loopEditId) {
                params.taskId = loopEditId;
                loopApi('update', params, function(res) {
                    if (res && res.code === 200) {
                        showToast('已更新', 'success');
                        loopEditId = null;
                        renderLoopList();
                    } else {
                        restoreBtn();
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
                        restoreBtn();
                        showToast((res && res.message) || '创建失败', 'error');
                    }
                });
            }
        });

        // 测试运行
        $panel.find('#loopFormTriggerBtn').on('click', function() {
            if (loopEditId) {
                loopApi('trigger', { taskId: loopEditId }, function() {
                    showToast('已触发执行', 'success');
                    if (typeof switchToChatMode === 'function') switchToChatMode();
                    hideLoopPanel();
                });
            }
        });
    }

    // ========== 公开 API ==========
    window.refreshLoopPanel = function() {
        if (loopPanelVisible) renderLoopList();
    };

    // 面板显示时移除表单模式 class
    var _origRenderLoopList = renderLoopList;
    renderLoopList = function() {
        var $p = getActivePanel();
        $p.removeClass('mode-form');
        $p.css('max-height', '');
        if (elapsedTimerId) clearInterval(elapsedTimerId);
        _origRenderLoopList();
    };
})();

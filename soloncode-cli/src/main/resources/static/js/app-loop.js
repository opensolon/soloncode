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

    // 使用 layui layer 风格的浮动提示（与设置面板保存成功一致）
    function showToast(msg, type) {
        if (typeof layer !== 'undefined' && layer.msg) {
            layer.msg(msg, { icon: type === 'error' ? 2 : 1, time: 2500, offset: '120px' });
        }
    }

    // 获取当前激活的面板和按钮
    function getActivePanel() {
        return inChatMode ? $chatLoopPanel : $welcomeLoopPanel;
    }
    function getActiveBtn() {
        return inChatMode ? $chatLoopBtn : $welcomeLoopBtn;
    }

    // ========== 预设模板 ==========
    var LOOP_TEMPLATES = {
        'ci-monitor': {
            prompt: '检查最近的 CI 构建状态，如果有失败的用例则分析失败原因并汇总报告',
            intervalMinutes: 30,
            goalCondition: null,
            makerAgent: null,
            checkerAgent: null,
            worktreeEnabled: false,
            maxIterations: 20
        },
        'daily-review': {
            prompt: '审查昨天的所有代码提交，总结变更摘要和潜在风险点',
            cron: '0 9 * * *',
            goalCondition: null,
            makerAgent: null,
            checkerAgent: 'reviewer',
            worktreeEnabled: false,
            maxIterations: 20
        },
        'issue-triage': {
            prompt: '扫描新创建的 issue，自动分类标签并分配优先级',
            intervalMinutes: 15,
            goalCondition: 'all new issues triaged',
            makerAgent: 'explorer',
            checkerAgent: 'reviewer',
            worktreeEnabled: true,
            maxIterations: 30
        }
    };

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

    // 面板内所有 mousedown/click 不冒泡到 .input-box 和 document
    // 防止 click-to-focus、cmd-complete、model-dropdown 等全局处理器干扰面板交互
    $welcomeLoopPanel.add($chatLoopPanel).on('mousedown click', function(e) {
        e.stopPropagation();
    });

    // 点击面板外部关闭（用 mousedown 避免选择文字时误触关闭）
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
            html += '<button class="loop-panel-add-btn" id="loopAddNewBtn" title="新建任务"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg></button>';
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
                    html += '<span class="loop-item-name">#' + escapeHtml(t.id) + '</span>';
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
                    // 功能标签（始终显示）
                    var tags = [];
                    if (t.goalCondition) tags.push('<span class="loop-tag loop-tag-goal">goal</span>');
                    if (t.makerAgent) tags.push('<span class="loop-tag loop-tag-mc">m/c</span>');
                    if (t.worktreeEnabled) tags.push('<span class="loop-tag loop-tag-wt">wt</span>');

                    if (t.skillRef) tags.push('<span class="loop-tag loop-tag-skill">' + escapeHtml(t.skillRef) + '</span>');
                    var tagsHtml = tags.length ? '<span class="loop-item-tags">' + tags.join('') + '</span>' : '';

                    // 底部信息行：时间 + 迭代 + 标签
                    if (lastInfo || tagsHtml) {
                        html += '<div class="loop-item-info">';
                        if (lastInfo) html += lastInfo;
                        html += tagsHtml;
                        html += '</div>';
                    }
                    // 执行结果摘要
                    if (t.lastResult) {
                        var resultShort = t.lastResult.length > 80 ? t.lastResult.substring(0, 80) + '...' : t.lastResult;
                        var resultClass = t.lastResult.indexOf('[GOAL_ACHIEVED]') >= 0 ? 'achieved' : (t.lastResult.indexOf('error') >= 0 ? 'error' : '');
                        html += '<div class="loop-item-result ' + resultClass + '">' + escapeHtml(resultShort) + '</div>';
                    }
                    // goal 进度条
                    if (t.goalCondition && t.maxIterations > 0) {
                        var pct = Math.min(100, Math.round(t.currentIteration / t.maxIterations * 100));
                        html += '<div class="loop-item-progress">';
                        html += '<div class="loop-progress-bar" style="width:' + pct + '%"></div>';
                        html += '<span class="loop-progress-text">' + t.currentIteration + '/' + t.maxIterations + '</span>';
                        html += '</div>';
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
        $('#loopAddNewBtn').on('click', function(e) {
            e.stopPropagation();
            loopEditId = null;
            renderLoopForm();
        });
        var $panel = getActivePanel();
        // 委托事件绑定在面板自身而非 document（面板已拦截冒泡，document 收不到）
        $(document).off('click.loopaction');
        $panel.off('click.loopaction').on('click.loopaction', '.loop-action-btn', function(e) {
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

        // 点击任务行进入详情面板
        $panel.on('click.loopitem', '.loop-item', function(e) {
            if ($(e.target).closest('.loop-action-btn').length) return;
            var id = $(this).data('id');
            renderLoopDetail(id);
        });
    }

    // ========== 表单渲染 ==========
    function renderLoopForm() {
        var html = '<div class="loop-panel-header">';
        html += '<button class="loop-panel-back-btn" id="loopBackBtn">← 返回列表</button>';
        html += '<span class="loop-panel-title">' + (loopEditId ? '编辑循环' : '新建循环') + '</span>';
        html += '</div>';
        html += '<div class="loop-form">';
        html += '<div class="loop-form-group">';
        html += '<label>提示词 <span class="loop-required">*</span></label>';
        html += '<input type="text" class="loop-input" id="loopFormPrompt" placeholder="例如: 检查CI状态并汇总失败用例"/>';
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
        // 预设模板（仅新建模式）
        if (!loopEditId) {
            html += '<div class="loop-form-group">';
            html += '<label>快速开始</label>';
            html += '<div class="loop-templates">';
            html += '<button type="button" class="loop-template-btn" data-tpl="ci-monitor"><span class="loop-tpl-icon">CI</span><span class="loop-tpl-info"><span class="loop-tpl-name">CI 监控</span><span class="loop-tpl-desc">每30分钟检查 CI 状态</span></span></button>';
            html += '<button type="button" class="loop-template-btn" data-tpl="daily-review"><span class="loop-tpl-icon">CR</span><span class="loop-tpl-info"><span class="loop-tpl-name">每日代码审查</span><span class="loop-tpl-desc">每天9点审查提交</span></span></button>';
            html += '<button type="button" class="loop-template-btn" data-tpl="issue-triage"><span class="loop-tpl-icon">IT</span><span class="loop-tpl-info"><span class="loop-tpl-name">Issue 分诊</span><span class="loop-tpl-desc">每15分钟扫描新 issue</span></span></button>';
            html += '</div></div>';
        }

        html += '<div class="loop-form-advanced-toggle" id="loopAdvancedToggle">▾ 执行策略</div>';
        html += '<div class="loop-form-advanced" id="loopAdvanced">';
        html += '<div class="loop-form-group"><label>目标条件</label><input type="text" class="loop-input" id="loopFormGoal" placeholder="all tests pass"/></div>';
        html += '<div class="loop-form-group"><label>执行者</label><input type="text" class="loop-input" id="loopFormMaker" placeholder="coder"/></div>';
        html += '<div class="loop-form-group"><label>验证者</label><input type="text" class="loop-input" id="loopFormChecker" placeholder="reviewer"/></div>';
        html += '<div class="loop-form-group loop-form-inline">';
        html += '<div class="loop-form-inline-item"><label>Worktree 隔离</label><label class="loop-checkbox"><input type="checkbox" id="loopFormWorktree"/> 在独立分支执行</label></div>';
        html += '</div>';
        html += '<div class="loop-form-group"><label>引用技能</label><input type="text" class="loop-input" id="loopFormSkillRef" placeholder="skill-name"/></div>';
        html += '<div class="loop-form-group"><label>最大迭代</label><input type="number" class="loop-input loop-input-sm" id="loopFormMaxIter" value="20" min="1"/></div>';
        html += '</div>';
        html += '<div class="loop-form-actions">';
        html += '<button class="loop-btn-secondary" id="loopFormTriggerBtn" style="display:' + (loopEditId ? 'inline-block' : 'none') + '">测试运行</button>';
        html += '<button class="loop-btn-primary" id="loopFormSaveBtn">保存</button>';
        html += '</div>';
        html += '</div>';

        var $panel = getActivePanel();
        $panel.addClass('mode-form');
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
        if (t.goalCondition) $('#loopFormGoal').val(t.goalCondition);
        if (t.makerAgent) $('#loopFormMaker').val(t.makerAgent);
        if (t.checkerAgent) $('#loopFormChecker').val(t.checkerAgent);
        if (t.worktreeEnabled) $('#loopFormWorktree').prop('checked', true);
        if (t.skillRef) $('#loopFormSkillRef').val(t.skillRef);

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
                $(this).text('▸ 执行策略');
            } else {
                $adv.show();
                $(this).text('▾ 执行策略');
            }
        });

        // 模板点击填充
        getActivePanel().on('click', '.loop-template-btn', function() {
            var tpl = LOOP_TEMPLATES[$(this).data('tpl')];
            if (!tpl) return;
            $('#loopFormPrompt').val(tpl.prompt);
            if (tpl.cron) {
                $('input[name=loopScheduleType][value=cron]').prop('checked', true).trigger('change');
                $('#loopFormCron').val(tpl.cron);
            } else {
                $('input[name=loopScheduleType][value=interval]').prop('checked', true).trigger('change');
                $('#loopFormInterval').val(tpl.intervalMinutes || 5);
                $('#loopFormIntervalUnit').val('m');
            }
            if (tpl.goalCondition) $('#loopFormGoal').val(tpl.goalCondition);
            if (tpl.makerAgent) $('#loopFormMaker').val(tpl.makerAgent);
            if (tpl.checkerAgent) $('#loopFormChecker').val(tpl.checkerAgent);
            if (tpl.worktreeEnabled) $('#loopFormWorktree').prop('checked', true);
            if (tpl.maxIterations) $('#loopFormMaxIter').val(tpl.maxIterations);
            showToast('已填充模板，可按需修改后保存', 'success');
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
                prompt: prompt,
                intervalMinutes: intervalVal,
                cron: cronVal,
                goalCondition: $('#loopFormGoal').val().trim() || null,
                makerAgent: $('#loopFormMaker').val().trim() || null,
                checkerAgent: $('#loopFormChecker').val().trim() || null,
                worktreeEnabled: $('#loopFormWorktree').is(':checked'),
                skillRef: $('#loopFormSkillRef').val().trim() || null,
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

    // ========== 详情面板渲染 ==========
    function renderLoopDetail(taskId) {
        var $panel = getActivePanel();
        $panel.addClass('mode-detail');

        var html = '<div class="loop-panel-header">';
        html += '<button class="loop-panel-back-btn" id="loopDetailBack">← 返回列表</button>';
        html += '<span class="loop-panel-title">#' + escapeHtml(taskId) + ' 详情</span>';
        html += '</div>';
        html += '<div class="loop-detail-tabs">';
        html += '<button class="loop-detail-tab active" data-dtab="history">执行历史</button>';
        html += '<button class="loop-detail-tab" data-dtab="state">状态文件</button>';
        html += '</div>';
        html += '<div class="loop-detail-content" id="loopDetailContent">';
        html += '<div class="loop-detail-loading">加载中...</div>';
        html += '</div>';

        $panel.html(html);

        // 返回按钮
        $('#loopDetailBack').on('click', function() {
            $panel.removeClass('mode-detail');
            renderLoopList();
        });

        // Tab 切换
        $panel.on('click.looptab', '.loop-detail-tab', function() {
            var tab = $(this).data('dtab');
            $panel.find('.loop-detail-tab').removeClass('active');
            $(this).addClass('active');
            if (tab === 'history') {
                loopApi('history', { taskId: taskId }, function(res) {
                    renderHistoryTab(res);
                });
            } else {
                loopApi('state', { taskId: taskId }, function(res) {
                    renderStateTab(res);
                });
            }
        });

        // 默认加载历史
        loopApi('history', { taskId: taskId }, function(res) {
            renderHistoryTab(res);
        });
    }

    function renderHistoryTab(res) {
        var $content = $('#loopDetailContent');
        if (!$content.length) return;
        var items = (res && res.data) ? res.data : [];
        if (items.length === 0) {
            $content.html('<div class="loop-detail-empty">暂无执行历史</div>');
            return;
        }
        var html = '';
        // 倒序显示（最新在前）
        for (var i = items.length - 1; i >= 0; i--) {
            var h = items[i];
            var isPass = h.result && (h.result.indexOf('[PASS]') >= 0 || h.result.indexOf('ok') === 0);
            var isFail = h.result && (h.result.indexOf('[FAIL]') >= 0 || h.result.indexOf('error') === 0);
            var statusIcon = isPass ? '✓' : (isFail ? '✗' : '·');
            var statusClass = isPass ? 'pass' : (isFail ? 'fail' : '');
            html += '<div class="loop-history-item">';
            html += '<span class="loop-history-status ' + statusClass + '">' + statusIcon + '</span>';
            html += '<span class="loop-history-iter">#' + (h.iteration || '-') + '</span>';
            html += '<span class="loop-history-time">' + formatTimeAgo(h.time) + '</span>';
            var resultText = h.result || '';
            if (resultText.length > 100) resultText = resultText.substring(0, 100) + '...';
            html += '<span class="loop-history-result">' + escapeHtml(resultText) + '</span>';
            html += '</div>';
        }
        $content.html(html);
    }

    function renderStateTab(res) {
        var $content = $('#loopDetailContent');
        if (!$content.length) return;
        var data = (res && res.data) ? res.data : {};
        var html = '';
        var sections = [
            { key: 'NEXT.md', label: '下一步 (NEXT.md)' },
            { key: 'PROGRESS.md', label: '进度 (PROGRESS.md)' },
            { key: 'DECISIONS.md', label: '决策 (DECISIONS.md)' }
        ];
        for (var i = 0; i < sections.length; i++) {
            var s = sections[i];
            var content = data[s.key] || '';
            html += '<div class="loop-state-section">';
            html += '<div class="loop-state-section-title">' + s.label + '</div>';
            if (content) {
                html += '<pre class="loop-state-pre">' + escapeHtml(content) + '</pre>';
            } else {
                html += '<div class="loop-state-empty">暂无内容</div>';
            }
            html += '</div>';
        }
        $content.html(html || '<div class="loop-detail-empty">暂无状态文件</div>');
    }

    // ========== 公开 API ==========
    window.refreshLoopPanel = function() {
        if (loopPanelVisible) renderLoopList();
    };

    // 面板显示时移除详情模式 class
    var _origRenderLoopList = renderLoopList;
    renderLoopList = function() {
        getActivePanel().removeClass('mode-detail mode-form');
        _origRenderLoopList();
    };
})();

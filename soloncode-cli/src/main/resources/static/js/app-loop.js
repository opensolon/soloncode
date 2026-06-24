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

    // ========== 预设模板 v2（优化版） ==========
    var LOOP_TEMPLATES = [
        {
            id: 'auto-fix',
            icon: 'AF',
            name: '自动修复循环',
            desc: '反复运行测试 → 分析失败 → 修复代码 → 验证通过',
            data: {
                prompt: '运行项目的测试套件，收集所有失败的测试用例；逐一分析失败根因（检查断言、逻辑、依赖），修复对应源码；提交修复并确认所有测试通过。如遇无法修复的阻塞问题，详细记录原因后暂停',
                intervalMinutes: 10,
                type: 'GOAL',
                runNow: true
            }
        },
        {
            id: 'code-refactor',
            icon: 'RF',
            name: '代码重构',
            desc: '逐步重构指定模块，保持测试通过',
            data: {
                prompt: '分析项目中需要重构的模块（高复杂度/重复代码/坏味道），制定重构计划；每次重构一个单元后运行测试确保不引入回归；持续迭代直到目标模块重构完成',
                intervalMinutes: 15,
                type: 'GOAL',
                runNow: true
            }
        },
        {
            id: 'daily-review',
            icon: 'CR',
            name: '每日代码审查',
            desc: '每天 9 点审查昨日提交，生成风险报告',
            data: {
                prompt: '从昨天 0 点到今天 8 点间的所有提交中提取变更摘要（按文件分类），检查是否存在以下风险：未处理的错误、硬编码密钥、SQL 注入隐患、内存泄漏、逻辑漏洞。输出包含风险等级（高/中/低）的汇总报告',
                cron: '0 9 * * *',
                type: 'HEARTBEAT',
                maxIterations: 20,
                runNow: false
            }
        },
        {
            id: 'daily-memory',
            icon: 'MR',
            name: '每晚记忆整理',
            desc: '每晚 22 点归纳当日对话，沉淀长期记忆',
            data: {
                prompt: '回顾当天所有对话记录，按以下维度提取关键信息：1）技术决策（架构选型、依赖引入、配置变更）2）用户偏好（编码风格、命名习惯、工具偏好）3）重要约束（性能要求、兼容性限制、安全规范）4）待办事项（遗留问题、后续计划）。将提炼后的信息整理为结构化记忆条目存入长期记忆',
                cron: '0 22 * * *',
                type: 'HEARTBEAT',
                maxIterations: 10,
                runNow: false
            }
        },
        {
            id: 'test-coverage',
            icon: 'TC',
            name: '测试补全',
            desc: '扫描未覆盖代码，逐步补充单元测试',
            data: {
                prompt: '运行测试覆盖率工具，列出未被测试覆盖的模块/方法；选择优先级最高的模块（核心逻辑 > 边界条件 > 工具函数），编写缺失的单元测试；每次提交后重新检查覆盖率，持续迭代直到目标覆盖率达成',
                intervalMinutes: 15,
                type: 'GOAL',
                runNow: true
            }
        },
        {
            id: 'ci-monitor',
            icon: 'CI',
            name: 'CI 构建监控',
            desc: '每 30 分钟检查 CI 状态，失败时自动诊断',
            data: {
                prompt: '检查项目 CI 的最新构建状态（包括编译、测试、lint、打包等阶段）。如有失败阶段，按以下流程诊断：1）定位失败日志的关键错误行 2）分析根因（代码变更/环境问题/配置错误）3）给出修复建议。如连续 3 次检查同一问题未修复，标记为阻塞',
                intervalMinutes: 30,
                type: 'HEARTBEAT',
                maxIterations: 20,
                runNow: false
            }
        },
        {
            id: 'dep-upkeep',
            icon: 'DU',
            name: '依赖健康检查',
            desc: '检查依赖安全漏洞与版本兼容性',
            data: {
                prompt: '扫描项目所有直接和传递依赖，检查：1）已知安全漏洞（CVE）及其严重程度 2）版本是否过时（最新稳定版 vs 当前版本）3）依赖间的兼容性冲突。汇总为依赖健康报告，标注每个问题的建议操作（升级/降级/替换/忽略）',
                intervalMinutes: 60,
                type: 'HEARTBEAT',
                maxIterations: 10,
                runNow: false
            }
        },
        {
            id: 'health-check',
            icon: 'HC',
            name: '服务健康巡检',
            desc: '每 5 分钟探测核心端点，异常即时告警',
            data: {
                prompt: '依次探测所有核心服务端点的健康状态（HTTP GET /health 或等价端点），记录每个端点的响应状态码、响应时间、返回体摘要。如有异常（非 2xx/超时/连接拒绝），立即输出告警信息并持续监控直到恢复',
                intervalMinutes: 5,
                type: 'HEARTBEAT',
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

    // Goal 状态中文映射（4 态，与 GoalState.Status 对齐）
    var GOAL_STATUS_LABEL = {
        PURSUING: '执行中',
        PAUSED: '已暂停',
        ACHIEVED: '已达成',
        BUDGET_LIMITED: '预算耗尽',
        BLOCKED: '已阻塞'
    };

    // 生成 goal 状态标签
    function renderGoalBadge(g) {
        if (!g) return '';
        var label = GOAL_STATUS_LABEL[g.status] || g.status;
        return '<span class="loop-goal-badge">' + label + '</span>';
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

            // 有运行中任务时自动刷新列表
            scheduleListAutoRefresh(items);
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
        if (t.runNow) tags.push('<span class="loop-tag loop-tag-now">now</span>');

        // 底部信息
        var lastInfo = '';
        if (t.lastExecutedAt) {
            lastInfo += '<span class="loop-item-meta">上次: ' + formatTimeAgo(t.lastExecutedAt) + '</span>';
        }
        if (t.currentIteration > 0) {
            lastInfo += '<span class="loop-item-meta">第' + t.currentIteration + '次</span>';
        }

        // Goal 行内标签
        var goalInlineHtml = '';
        var g = t.goal;
        if (g) {
            var label = GOAL_STATUS_LABEL[g.status] || g.status;
            goalInlineHtml = '<span class="loop-item-goal-inline">' + label + '</span>';
        }

        // 拼装完整 item
        var runningStatusHtml = (statusClass === 'running' || statusClass === 'cancelled')
            ? '<span class="loop-item-status ' + statusClass + '">' + statusText + '</span>'
            : '';

        var html = '<div class="loop-item" data-id="' + t.id + '">';
        html += '<div class="loop-item-row">';
        html += '<span class="loop-item-dot ' + statusClass + '"></span>';
        html += '<span class="loop-item-name">#' + escapeHtml(t.id) + '</span>';
        html += goalInlineHtml;
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
                var doRemove = function() {
                    loopApi('remove', { taskId: id }, function(res) {
                        if (res) { renderLoopList(); showToast('已删除', 'success'); }
                    });
                };
                if (typeof layer !== 'undefined' && layer.confirm) {
                    layer.confirm('确定要删除该循环任务吗？', {
                        title: '确认删除', btn: ['删除', '取消'], icon: 3, offset: '120px'
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

        // ★ Tab 栏（放在最上方，对应 LoopTask.TaskType）
        html += '<div class="loop-tab-bar">';
        html += '<div class="loop-tab active" data-tab="heartbeat">定时心跳</div>';
        html += '<div class="loop-tab" data-tab="goal">目标驱动</div>';
        html += '</div>';

        // 任务描述
        html += '<div class="loop-form-group">';
        html += '<label>任务描述 <span class="loop-required">*</span></label>';
        html += '<textarea class="loop-input loop-textarea" id="loopFormPrompt" rows="2" placeholder=""></textarea>';
        html += '<div class="loop-form-goal-hint" id="loopFormGoalHint" style="display:none;">设定目标后，AI 将自动循环执行直至达成目标，无需配置调度计划</div>';
        html += '</div>';

        // ===== Heartbeat 表单区：调度方式 =====
        html += '<div class="loop-form-section active" data-section="heartbeat">';
        html += '<div class="loop-form-schedule">';
        html += '<div class="loop-form-group">';
        html += '<label>调度方式</label>';
        html += '<div class="loop-interval-row" style="flex-wrap:wrap;">';
        html += '<label class="loop-radio"><input type="radio" name="loopScheduleType" value="interval" checked/> 固定间隔</label>';
        html += '<input type="text" class="loop-input loop-input-sm" id="loopFormInterval" value="5m" placeholder="如 5m、1h"/>';
        html += '<label class="loop-checkbox" style="margin-left:8px;white-space:nowrap;"><input type="checkbox" id="loopFormRunNow" checked/> 首次立即执行</label>';
        html += '</div>';
        html += '<div class="loop-interval-row">';
        html += '<label class="loop-radio"><input type="radio" name="loopScheduleType" value="cron"/> Cron 表达式</label>';
        html += '<input type="text" class="loop-input loop-input-sm" id="loopFormCron" placeholder="0 */5 * * * ?"/>';
        html += '<span class="loop-cron-hint">示例:</span> ';
        html += '<a class="loop-cron-link" data-cron="0 */2 * * *">每2小时</a>';
        html += '<a class="loop-cron-link" data-cron="0 22 * * *">每天22点</a> ';
        html += '<a class="loop-cron-link" data-cron="0 0 * * 1">每周一</a> ';
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
        html += '<label>Token 预算</label>';
        html += '<input type="text" inputmode="numeric" class="loop-input" id="loopFormMaxTokens" placeholder="留空不限制（如 32k、1m）" list="loopMaxTokensList" autocomplete="off"/>' +
            '<datalist id="loopMaxTokensList">' +
            '<option value="512k"><option value="5m"><option value="10m">' +
            '</datalist></div>';
        html += '<div class="loop-form-inline-item" style="flex:1;margin-left:12px;">';
        html += '<label>时间预算</label>';
        html += '<input type="text" class="loop-input" id="loopFormMaxDuration" placeholder="留空不限制（如 30m、2h）"/>';
        html += '</div>';
        html += '</div>';  // 结束 loop-form-inline
        html += '</div>';



        html += '</div>';  // 结束 goal section
        html += '</div>';  // 结束 loop-form（scrollable 区）

        // 操作按钮（在 loop-form 外部，固定在面板底部）
        html += '<div class="loop-form-actions">';
        html += '<button class="loop-btn-secondary" id="loopFormCancelBtn">取消</button>';
        html += '<button class="loop-btn-secondary" id="loopFormTriggerBtn" style="display:' + (loopEditId ? 'inline-block' : 'none') + '">测试运行</button>';
        html += '<button class="loop-btn-primary" id="loopFormSaveBtn">保存</button>';
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
            $panel.find('#loopFormSaveBtn').prop('disabled', true).text('加载中...');
            loopApi('get', { taskId: editTaskId }, function(res) {
                // 校验是否仍在编辑同一个任务（用户可能已点击返回）
                if (loopEditId !== editTaskId) return;
                var $p = getActivePanel();
                var t = (res && res.data) ? res.data : null;
                if (t) {
                    fillFormData(t);
                    var statusText = t.cancelled ? '已取消' : (!t.enabled ? '已停用' : (t.running ? '运行中' : '就绪'));
                    var statusClass = t.cancelled ? 'cancelled' : (!t.enabled ? 'disabled' : (t.running ? 'running' : 'ready'));
                    var $title = $p.find('.loop-panel-title');
                    $title.html('编辑循环 #' + escapeHtml(editTaskId) +
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
                $p.find('.loop-input, .loop-checkbox input, select').prop('disabled', false);
                $p.find('#loopFormSaveBtn').prop('disabled', false).text('保存');
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
            $panel.find('#loopFormPrompt').closest('.loop-form-group').find('label').html('目标描述 <span class="loop-required">*</span>');
        } else {
            $panel.find('.loop-tab[data-tab="heartbeat"]').addClass('active');
            $panel.find('.loop-form-section[data-section="heartbeat"]').addClass('active');
            $panel.find('#loopFormPrompt').closest('.loop-form-group').find('label').html('任务描述 <span class="loop-required">*</span>');
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
                isGoal ? '目标描述 <span class="loop-required">*</span>' : '任务描述 <span class="loop-required">*</span>'
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
                showToast('请输入任务描述', 'error');
                return;
            }

            $saveBtn.prop('disabled', true).text('保存中...');

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
        if (listRefreshTimerId) { clearInterval(listRefreshTimerId); listRefreshTimerId = null; }
        _origRenderLoopList();
    };
})();

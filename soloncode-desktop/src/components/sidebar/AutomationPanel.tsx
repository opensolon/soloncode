import { useCallback, useEffect, useMemo, useState } from 'react';
import { Icon } from '../common/Icon';
import {
  UNLINKED_PROJECT,
  deleteAutomation,
  getAllAutomations,
  getAutomationRuns,
  type DbAutomation,
  type DbAutomationRun,
} from '../../db';
import type { Project } from './SessionsPanel';
import { getCronValidationError, getNextCronRun } from '../../utils/cron';
import './AutomationPanel.css';

export interface AutomationModelOption {
  id: string;
  name: string;
  label: string;
}

export interface AutomationUpdateInput {
  title: string;
  prompt: string;
  projectId: string;
  modelId: string;
  reasoningEffort: DbAutomation['reasoningEffort'];
  scheduleEnabled: boolean;
  cron: string;
}

interface AutomationPanelProps {
  projects: Project[];
  refreshKey?: number;
  selectedAutomationId?: number | null;
  onCreateWithPrompt: () => void;
  onSelectAutomation: (automation: DbAutomation) => void;
  onAutomationDeleted?: (automationId: number) => void;
}

interface AutomationDetailProps {
  automation: DbAutomation;
  projects: Project[];
  models: AutomationModelOption[];
  running?: boolean;
  runDisabled?: boolean;
  runRefreshKey?: number;
  onRun: (automation: DbAutomation) => void;
  onOpenRun: (run: DbAutomationRun) => void;
  onSave: (automation: DbAutomation, updates: AutomationUpdateInput) => Promise<boolean>;
  onDelete: (automation: DbAutomation) => void;
  onClose: () => void;
}

function formatTime(value?: string) {
  if (!value) return '尚未运行';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '未知' : date.toLocaleString();
}

function reasoningLabel(effort: DbAutomation['reasoningEffort']) {
  return ({ low: '低', medium: '中', high: '高', max: '最高' } as const)[effort];
}

function runStatusLabel(status: DbAutomationRun['status']) {
  return ({ running: '运行中', completed: '已完成', error: '失败' } as const)[status];
}

function formatDuration(run: DbAutomationRun) {
  if (!run.completedAt) return run.status === 'running' ? '进行中' : '-';
  const startedAt = new Date(run.startedAt).getTime();
  const completedAt = new Date(run.completedAt).getTime();
  if (!Number.isFinite(startedAt) || !Number.isFinite(completedAt)) return '-';
  const seconds = Math.max(0, Math.round((completedAt - startedAt) / 1000));
  if (seconds < 60) return `${seconds} 秒`;
  const minutes = Math.floor(seconds / 60);
  return `${minutes} 分 ${seconds % 60} 秒`;
}

function createAutomationDraft(automation: DbAutomation): AutomationUpdateInput {
  return {
    title: automation.title,
    prompt: automation.prompt,
    projectId: automation.projectId,
    modelId: automation.modelId,
    reasoningEffort: automation.reasoningEffort,
    scheduleEnabled: automation.scheduleEnabled ?? false,
    cron: automation.cron || '0 9 * * *',
  };
}

function automationDraftChanged(automation: DbAutomation, draft: AutomationUpdateInput): boolean {
  return draft.title !== automation.title
    || draft.prompt !== automation.prompt
    || draft.projectId !== automation.projectId
    || draft.modelId !== automation.modelId
    || draft.reasoningEffort !== automation.reasoningEffort
    || draft.scheduleEnabled !== (automation.scheduleEnabled ?? false)
    || draft.cron !== (automation.cron || '0 9 * * *');
}

export function AutomationPanel({
  projects,
  refreshKey = 0,
  selectedAutomationId,
  onCreateWithPrompt,
  onSelectAutomation,
  onAutomationDeleted,
}: AutomationPanelProps) {
  const [automations, setAutomations] = useState<DbAutomation[]>([]);
  const [error, setError] = useState('');

  useEffect(() => {
    getAllAutomations()
      .then(items => {
        setAutomations(items);
        if (selectedAutomationId) {
          const selected = items.find(item => item.id === selectedAutomationId);
          if (selected) onSelectAutomation(selected);
        }
      })
      .catch(err => {
        console.error('[AutomationPanel] 加载自动化失败:', err);
        setError('加载自动化失败');
      });
  }, [onSelectAutomation, refreshKey, selectedAutomationId]);

  const handleDelete = useCallback(async (automation: DbAutomation) => {
    if (!automation.id) return;
    try {
      await deleteAutomation(automation.id);
      setAutomations(current => current.filter(item => item.id !== automation.id));
      onAutomationDeleted?.(automation.id);
    } catch (err) {
      console.error('[AutomationPanel] 删除自动化失败:', err);
      setError('删除自动化失败');
    }
  }, [onAutomationDeleted]);

  return (
    <div className="automation-panel">
      <div className="automation-header">
        <div className="automation-header-label">
          <span className="automation-header-title">自动化任务</span>
          <span className="automation-count">{automations.length}</span>
        </div>
        <button
          type="button"
          className="automation-header-add"
          title="根据提示词新建自动化"
          onClick={() => {
            setError('');
            onCreateWithPrompt();
          }}
        >
          <Icon name="add" size={14} />
        </button>
      </div>

      {error && <div className="automation-error">{error}</div>}

      <div className="automation-list">
        {automations.length === 0 && (
          <div className="automation-empty">
            <Icon name="automation" size={28} />
            <span>暂无自动化任务</span>
            <small>点击右上角新增，通过提示词创建任务</small>
          </div>
        )}

        {automations.map(automation => {
          const projectAvailable = automation.projectId === UNLINKED_PROJECT
            || projects.some(project => project.id === automation.projectId);
          const selected = automation.id === selectedAutomationId;
          return (
            <div
              key={automation.id}
              className={`automation-task-row${selected ? ' selected' : ''}`}
              role="button"
              tabIndex={0}
              aria-selected={selected}
              onClick={() => onSelectAutomation(automation)}
              onKeyDown={event => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  onSelectAutomation(automation);
                }
              }}
            >
              <Icon name="automation" size={15} className="automation-task-icon" />
              <div className="automation-task-content">
                <span className="automation-task-title" title={automation.title}>{automation.title}</span>
                <span className={`automation-task-meta${projectAvailable ? '' : ' missing'}`} title={automation.projectId}>
                  <span className={`automation-task-schedule-dot${automation.scheduleEnabled ? ' enabled' : ''}`} />
                  {automation.scheduleEnabled ? automation.cron : automation.projectName} · {automation.runCount > 0 ? `已运行 ${automation.runCount} 次` : '未运行'}
                </span>
              </div>
              <button
                type="button"
                className="automation-delete-btn"
                title="删除自动化"
                onClick={event => {
                  event.stopPropagation();
                  void handleDelete(automation);
                }}
              >
                <Icon name="delete" size={12} />
              </button>
            </div>
          );
        })}
      </div>
    </div>
  );
}

export function AutomationDetail({
  automation,
  projects,
  models,
  running = false,
  runDisabled = false,
  runRefreshKey = 0,
  onRun,
  onOpenRun,
  onSave,
  onDelete,
  onClose,
}: AutomationDetailProps) {
  const [runs, setRuns] = useState<DbAutomationRun[]>([]);
  const [runsLoading, setRunsLoading] = useState(false);
  const [draft, setDraft] = useState<AutomationUpdateInput>(() => createAutomationDraft(automation));
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState('');

  useEffect(() => {
    setDraft(createAutomationDraft(automation));
    setSaveError('');
  }, [automation.id, automation.updatedAt]);

  const dirty = automationDraftChanged(automation, draft);
  const projectAvailable = draft.projectId === UNLINKED_PROJECT
    || projects.some(project => project.id === draft.projectId);
  const modelAvailable = models.some(model => model.id === draft.modelId);
  const cronError = useMemo(() => getCronValidationError(draft.cron), [draft.cron]);
  const nextCronRun = useMemo(() => {
    if (!draft.scheduleEnabled || cronError) return null;
    try {
      return getNextCronRun(draft.cron);
    } catch {
      return null;
    }
  }, [cronError, draft.cron, draft.scheduleEnabled]);
  const modelOptions = useMemo(() => {
    if (models.some(model => model.id === draft.modelId)) return models;
    return [{ id: automation.modelId, name: automation.modelName, label: `${automation.modelName}（当前不可用）` }, ...models];
  }, [automation.modelId, automation.modelName, draft.modelId, models]);
  const validationError = !draft.title.trim()
    ? '任务名称不能为空'
    : !draft.prompt.trim()
      ? '任务提示词不能为空'
      : !projectAvailable
        ? '请选择一个可用项目'
        : !modelAvailable
          ? '请选择一个可用模型'
          : cronError;

  const handleSave = useCallback(async () => {
    if (!dirty || validationError || saving || running) return;
    const normalized: AutomationUpdateInput = {
      ...draft,
      title: draft.title.trim(),
      prompt: draft.prompt.trim(),
      cron: draft.cron.trim().replace(/\s+/g, ' '),
    };
    setSaving(true);
    setSaveError('');
    try {
      const saved = await onSave(automation, normalized);
      if (!saved) setSaveError('保存失败，请检查配置后重试');
    } catch (error) {
      console.error('[AutomationDetail] 保存自动化失败:', error);
      setSaveError('保存失败，请稍后重试');
    } finally {
      setSaving(false);
    }
  }, [automation, dirty, draft, onSave, running, saving, validationError]);

  useEffect(() => {
    let cancelled = false;
    if (!automation.id) {
      setRuns([]);
      return () => { cancelled = true; };
    }

    setRunsLoading(true);
    getAutomationRuns(automation.id)
      .then(items => {
        if (!cancelled) setRuns(items);
      })
      .catch(err => {
        console.error('[AutomationDetail] 加载运行记录失败:', err);
        if (!cancelled) setRuns([]);
      })
      .finally(() => {
        if (!cancelled) setRunsLoading(false);
      });

    return () => { cancelled = true; };
  }, [automation.id, runRefreshKey]);

  return (
    <div className="automation-detail">
      <div className="automation-detail-header">
        <div className="automation-detail-heading">
          <Icon name="automation" size={16} />
          <span>自动化详情</span>
        </div>
        <button type="button" className="automation-detail-close" title="关闭详情" onClick={onClose}>
          <Icon name="close" size={14} />
        </button>
      </div>

      <div className="automation-detail-scroll">
        <div className="automation-detail-hero">
          <div className="automation-detail-title-field">
            <label htmlFor={`automation-title-${automation.id}`}>任务名称</label>
            <input
              id={`automation-title-${automation.id}`}
              value={draft.title}
              maxLength={100}
              disabled={running || saving}
              onChange={event => setDraft(current => ({ ...current, title: event.target.value }))}
            />
            <p>{projectAvailable ? '已关联项目，可以运行' : '关联项目已不可用'}</p>
          </div>
          <span className={`automation-detail-status${!projectAvailable ? ' missing' : draft.scheduleEnabled ? ' scheduled' : ''}`}>
            {!projectAvailable ? '项目缺失' : draft.scheduleEnabled ? '定时已开启' : '定时关闭'}
          </span>
        </div>

        <section className="automation-detail-section">
          <h3>任务提示词</h3>
          <textarea
            className="automation-detail-prompt automation-detail-prompt-input"
            value={draft.prompt}
            maxLength={10000}
            rows={5}
            disabled={running || saving}
            onChange={event => setDraft(current => ({ ...current, prompt: event.target.value }))}
          />
        </section>

        <section className="automation-detail-section">
          <h3>关联配置</h3>
          <div className="automation-detail-grid">
            <div className="automation-detail-field">
              <label htmlFor={`automation-project-${automation.id}`}>项目</label>
              <select
                id={`automation-project-${automation.id}`}
                value={draft.projectId}
                disabled={running || saving}
                onChange={event => setDraft(current => ({ ...current, projectId: event.target.value }))}
              >
                <option value={UNLINKED_PROJECT}>未关联项目</option>
                {draft.projectId !== UNLINKED_PROJECT && !projects.some(project => project.id === draft.projectId) && (
                  <option value={draft.projectId}>{automation.projectName}（不可用）</option>
                )}
                {projects.map(project => <option key={project.id} value={project.id}>{project.name}</option>)}
              </select>
              <small title={draft.projectId}>{draft.projectId === UNLINKED_PROJECT ? '不使用项目目录' : draft.projectId}</small>
            </div>
            <div className="automation-detail-field">
              <label htmlFor={`automation-model-${automation.id}`}>模型</label>
              <select
                id={`automation-model-${automation.id}`}
                value={draft.modelId}
                disabled={running || saving}
                onChange={event => setDraft(current => ({ ...current, modelId: event.target.value }))}
              >
                {modelOptions.map(model => <option key={model.id} value={model.id}>{model.label}</option>)}
              </select>
              <small title={draft.modelId}>{draft.modelId}</small>
            </div>
            <div className="automation-detail-field">
              <label htmlFor={`automation-reasoning-${automation.id}`}>推理程度</label>
              <select
                id={`automation-reasoning-${automation.id}`}
                value={draft.reasoningEffort}
                disabled={running || saving}
                onChange={event => setDraft(current => ({ ...current, reasoningEffort: event.target.value as DbAutomation['reasoningEffort'] }))}
              >
                <option value="low">低（low）</option>
                <option value="medium">中（medium）</option>
                <option value="high">高（high）</option>
                <option value="max">最高（max）</option>
              </select>
              <small className="automation-detail-reasoning"><span className={`automation-reasoning-dot ${draft.reasoningEffort}`} />{reasoningLabel(draft.reasoningEffort)}</small>
            </div>
            <div className="automation-detail-field">
              <span>运行次数</span>
              <strong>{automation.runCount} 次</strong>
              <small>上次运行：{formatTime(automation.lastRunAt)}</small>
            </div>
          </div>
        </section>

        <section className="automation-detail-section automation-schedule-section">
          <div className="automation-schedule-heading">
            <div>
              <h3>定时执行</h3>
              <p>桌面应用运行期间，按本机时区自动触发</p>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={draft.scheduleEnabled}
              className={`automation-schedule-switch${draft.scheduleEnabled ? ' enabled' : ''}`}
              disabled={running || saving}
              onClick={() => setDraft(current => ({ ...current, scheduleEnabled: !current.scheduleEnabled }))}
            >
              <span />
            </button>
          </div>
          <div className="automation-cron-field">
            <label htmlFor={`automation-cron-${automation.id}`}>Cron 表达式</label>
            <input
              id={`automation-cron-${automation.id}`}
              value={draft.cron}
              maxLength={100}
              spellCheck={false}
              disabled={running || saving}
              aria-invalid={Boolean(cronError)}
              onChange={event => setDraft(current => ({ ...current, cron: event.target.value }))}
            />
            <small>分钟　小时　日　月　星期（0 和 7 均表示星期日）</small>
            {cronError ? (
              <div className="automation-field-error">{cronError}</div>
            ) : draft.scheduleEnabled ? (
              <div className="automation-next-run">下次运行：{nextCronRun ? nextCronRun.toLocaleString() : '一年内无匹配时间'}</div>
            ) : null}
          </div>
          <div className="automation-cron-presets">
            <button type="button" disabled={running || saving} onClick={() => setDraft(current => ({ ...current, cron: '*/15 * * * *' }))}>每 15 分钟</button>
            <button type="button" disabled={running || saving} onClick={() => setDraft(current => ({ ...current, cron: '0 * * * *' }))}>每小时</button>
            <button type="button" disabled={running || saving} onClick={() => setDraft(current => ({ ...current, cron: '0 9 * * *' }))}>每天 9:00</button>
            <button type="button" disabled={running || saving} onClick={() => setDraft(current => ({ ...current, cron: '0 9 * * 1-5' }))}>工作日 9:00</button>
          </div>
          {automation.lastScheduledAt && <div className="automation-last-scheduled">上次计划触发：{formatTime(automation.lastScheduledAt)}</div>}
        </section>

        <section className="automation-detail-section">
          <h3>时间信息</h3>
          <div className="automation-detail-times">
            <span>创建于 {formatTime(automation.createdAt)}</span>
            <span>更新于 {formatTime(automation.updatedAt)}</span>
          </div>
        </section>

        <section className="automation-detail-section automation-run-history-section">
          <div className="automation-run-history-heading">
            <h3>运行记录</h3>
            <span>{runs.length}</span>
          </div>
          {runsLoading ? (
            <div className="automation-run-history-empty">加载运行记录...</div>
          ) : runs.length === 0 ? (
            <div className="automation-run-history-empty">暂无运行记录</div>
          ) : (
            <div className="automation-run-history">
              {runs.map(run => (
                <button
                  key={run.id}
                  type="button"
                  className={`automation-run-record${run.sessionId ? ' clickable' : ''}`}
                  disabled={!run.sessionId}
                  title={run.sessionId ? '打开本次运行的对话' : '该运行记录没有关联会话'}
                  aria-label={run.sessionId ? `打开 ${formatTime(run.startedAt)} 的运行对话` : undefined}
                  onClick={() => onOpenRun(run)}
                >
                  <div className="automation-run-record-marker">
                    <span className={`automation-run-status-dot ${run.status}`} />
                    <span className={`automation-run-status ${run.status}`}>{runStatusLabel(run.status)}</span>
                  </div>
                  <div className="automation-run-record-content">
                    <div className="automation-run-record-primary">
                      <strong>{formatTime(run.startedAt)}</strong>
                      <span>{formatDuration(run)}</span>
                      <span>{run.trigger === 'scheduled' ? '定时触发' : '手动触发'}</span>
                    </div>
                    <div className="automation-run-record-meta">
                      <span title={run.projectId}>{run.projectName}</span>
                      <span title={run.modelId}>{run.modelName}</span>
                      <span>推理 {reasoningLabel(run.reasoningEffort)}</span>
                    </div>
                    {run.sessionId && <div className="automation-run-session" title={run.sessionId}>打开对话 · 会话：{run.sessionId}</div>}
                    {run.error && <div className="automation-run-error">{run.error}</div>}
                  </div>
                </button>
              ))}
            </div>
          )}
        </section>
      </div>

      <div className="automation-detail-actions">
        {(saveError || (dirty && validationError)) && <span className="automation-detail-save-error">{saveError || validationError}</span>}
        <button type="button" className="automation-detail-delete" disabled={running} onClick={() => onDelete(automation)}>
          <Icon name="delete" size={13} />删除任务
        </button>
        <button
          type="button"
          className="automation-detail-save"
          disabled={!dirty || Boolean(validationError) || running || saving}
          onClick={() => { void handleSave(); }}
        >
          <Icon name={saving ? 'loading' : 'save'} size={13} />{saving ? '保存中' : '保存更改'}
        </button>
        <button
          type="button"
          className="automation-detail-run"
          disabled={!projectAvailable || !modelAvailable || dirty || running || runDisabled}
          onClick={() => onRun(automation)}
          title={dirty ? '请先保存更改' : !modelAvailable ? '当前模型不可用' : undefined}
        >
          <Icon name={running ? 'loading' : 'send'} size={13} />
          {running ? '运行中' : '立即运行'}
        </button>
      </div>
    </div>
  );
}

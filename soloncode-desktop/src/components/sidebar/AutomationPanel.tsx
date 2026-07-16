import { useCallback, useEffect, useState } from 'react';
import { Icon } from '../common/Icon';
import {
  deleteAutomation,
  getAllAutomations,
  type DbAutomation,
} from '../../db';
import type { Project } from './SessionsPanel';
import './AutomationPanel.css';

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
  running?: boolean;
  onRun: (automation: DbAutomation) => void;
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
          const projectAvailable = projects.some(project => project.id === automation.projectId);
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
                  {automation.projectName} · {automation.runCount > 0 ? `已运行 ${automation.runCount} 次` : '未运行'}
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
  running = false,
  onRun,
  onDelete,
  onClose,
}: AutomationDetailProps) {
  const projectAvailable = projects.some(project => project.id === automation.projectId);

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
          <div>
            <h2>{automation.title}</h2>
            <p>{projectAvailable ? '已关联项目，可以运行' : '关联项目已不可用'}</p>
          </div>
          <span className={`automation-detail-status${projectAvailable ? '' : ' missing'}`}>
            {projectAvailable ? '可运行' : '项目缺失'}
          </span>
        </div>

        <section className="automation-detail-section">
          <h3>任务提示词</h3>
          <div className="automation-detail-prompt">{automation.prompt}</div>
        </section>

        <section className="automation-detail-section">
          <h3>关联配置</h3>
          <div className="automation-detail-grid">
            <div className="automation-detail-field">
              <span>项目</span>
              <strong className={projectAvailable ? '' : 'missing'}>{automation.projectName}</strong>
              <small title={automation.projectId}>{automation.projectId}</small>
            </div>
            <div className="automation-detail-field">
              <span>模型</span>
              <strong>{automation.modelName}</strong>
              <small title={automation.modelId}>{automation.modelId}</small>
            </div>
            <div className="automation-detail-field">
              <span>推理程度</span>
              <strong className="automation-detail-reasoning">
                <span className={`automation-reasoning-dot ${automation.reasoningEffort}`} />
                {reasoningLabel(automation.reasoningEffort)}
              </strong>
              <small>{automation.reasoningEffort}</small>
            </div>
            <div className="automation-detail-field">
              <span>运行次数</span>
              <strong>{automation.runCount} 次</strong>
              <small>上次运行：{formatTime(automation.lastRunAt)}</small>
            </div>
          </div>
        </section>

        <section className="automation-detail-section">
          <h3>时间信息</h3>
          <div className="automation-detail-times">
            <span>创建于 {formatTime(automation.createdAt)}</span>
            <span>更新于 {formatTime(automation.updatedAt)}</span>
          </div>
        </section>
      </div>

      <div className="automation-detail-actions">
        <button type="button" className="automation-detail-delete" disabled={running} onClick={() => onDelete(automation)}>
          <Icon name="delete" size={13} />删除任务
        </button>
        <button
          type="button"
          className="automation-detail-run"
          disabled={!projectAvailable || running}
          onClick={() => onRun(automation)}
        >
          <Icon name={running ? 'loading' : 'send'} size={13} />
          {running ? '运行中' : '立即运行'}
        </button>
      </div>
    </div>
  );
}

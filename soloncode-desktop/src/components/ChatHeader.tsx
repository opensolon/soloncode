import { useEffect, useMemo, useRef, useState } from 'react';
import { Icon } from './common/Icon';
import './ChatHeader.css';

export interface ChatHeaderTask {
  id: string;
  title: string;
  timestamp: string;
  messageCount: number;
  status?: 'running' | 'completed' | 'error';
}

export interface ChatReviewFile {
  path: string;
  status: 'modified' | 'added' | 'deleted' | 'untracked';
}

interface ChatHeaderProps {
  title: string;
  status: string;
  projectName?: string;
  messageCount?: number;
  startedAt?: string;
  totalTokens?: number;
  totalConversations?: number;
  tasks?: ChatHeaderTask[];
  activeTaskId?: string;
  onSelectTask?: (id: string) => void;
  reviewFiles?: ChatReviewFile[];
  onReviewFileSelect?: (path: string) => void;
  openInfoSignal?: number;
}

function formatNumber(value?: number) {
  return new Intl.NumberFormat('zh-CN').format(Math.max(0, value || 0));
}

function formatDateTime(value?: string) {
  if (!value) return '-';
  const time = new Date(value);
  if (Number.isNaN(time.getTime())) return value;
  return time.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function formatRelativeTime(value?: string) {
  if (!value) return '-';
  const time = new Date(value).getTime();
  if (!Number.isFinite(time)) return value;
  const diff = Date.now() - time;
  const minute = 60 * 1000;
  const hour = 60 * minute;
  const day = 24 * hour;
  if (diff < minute) return '刚刚';
  if (diff < hour) return `${Math.floor(diff / minute)}分钟前`;
  if (diff < day) return `${Math.floor(diff / hour)}小时前`;
  if (diff < day * 30) return `${Math.floor(diff / day)}天前`;
  return new Date(value).toLocaleDateString('zh-CN');
}

function getTaskStatusMeta(status?: ChatHeaderTask['status']) {
  if (status === 'running') {
    return { label: '执行中', tone: 'primary', progress: 50 };
  }
  if (status === 'error') {
    return { label: '失败', tone: 'danger', progress: 100 };
  }
  if (status === 'completed') {
    return { label: '已完成', tone: 'success', progress: 100 };
  }
  return { label: '等待执行', tone: 'info', progress: 0 };
}

function getFileName(path: string) {
  return path.split(/[\\/]/).pop() || path;
}

function getReviewStatusLabel(status: ChatReviewFile['status']) {
  if (status === 'added') return '新增';
  if (status === 'deleted') return '删除';
  if (status === 'untracked') return '未跟踪';
  return '修改';
}

export function ChatHeader({
  title,
  status,
  projectName,
  messageCount = 0,
  startedAt,
  totalTokens = 0,
  totalConversations = 0,
  tasks = [],
  activeTaskId,
  onSelectTask,
  reviewFiles = [],
  onReviewFileSelect,
  openInfoSignal,
}: ChatHeaderProps) {
  const [infoOpen, setInfoOpen] = useState(false);
  const infoRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!infoOpen) return;
    const handlePointerDown = (event: PointerEvent) => {
      if (!infoRef.current?.contains(event.target as Node)) {
        setInfoOpen(false);
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setInfoOpen(false);
    };
    document.addEventListener('pointerdown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [infoOpen]);

  useEffect(() => {
    if (openInfoSignal === undefined) return;
    if (openInfoSignal > 0) setInfoOpen(true);
  }, [openInfoSignal]);

  const sortedTasks = useMemo(() => {
    return [...tasks].sort((a, b) => {
      const aTime = new Date(a.timestamp).getTime();
      const bTime = new Date(b.timestamp).getTime();
      return (Number.isFinite(bTime) ? bTime : 0) - (Number.isFinite(aTime) ? aTime : 0);
    });
  }, [tasks]);

  const handleSelectTask = (id: string) => {
    onSelectTask?.(id);
    setInfoOpen(false);
  };

  return (
    <header className="chat-header">
      <div className="chat-title">
        <h2>{title}</h2>
        <div className="chat-title-meta">
          {projectName && <span className="chat-project-name">{projectName}</span>}
          <span className="chat-status">{status === 'active' ? '进行中' : '已完成'}</span>
        </div>
      </div>
      <div className="chat-header-actions" ref={infoRef}>
        <button
          type="button"
          className={`chat-info-btn${infoOpen ? ' active' : ''}`}
          onClick={() => setInfoOpen(prev => !prev)}
          title="对话信息"
          aria-label="对话信息"
          aria-expanded={infoOpen}
        >
          <Icon name="info" size={16} />
        </button>
        {infoOpen && (
          <div className="chat-info-popover" role="dialog" aria-label="对话信息">
            <div className="chat-info-grid">
              <div className="chat-info-stat">
                <span className="chat-info-label">条数</span>
                <strong>{formatNumber(messageCount)}</strong>
              </div>
              <div className="chat-info-stat">
                <span className="chat-info-label">时间</span>
                <strong>{formatDateTime(startedAt)}</strong>
              </div>
              <div className="chat-info-stat">
                <span className="chat-info-label">总 token</span>
                <strong>{formatNumber(totalTokens)}</strong>
              </div>
              <div className="chat-info-stat">
                <span className="chat-info-label">总对话</span>
                <strong>{formatNumber(totalConversations)}</strong>
              </div>
            </div>
            {reviewFiles.length > 0 && (
              <div className="chat-review-section">
                <div className="chat-task-heading">
                  <span>审查文件</span>
                  <span>{formatNumber(reviewFiles.length)}</span>
                </div>
                <div className="chat-review-file-list">
                  {reviewFiles.map(file => (
                    <button
                      key={`${file.status}:${file.path}`}
                      type="button"
                      className="chat-review-file"
                      onClick={() => {
                        onReviewFileSelect?.(file.path);
                        setInfoOpen(false);
                      }}
                      title={file.path}
                    >
                      <span className={`chat-review-status ${file.status}`}>{getReviewStatusLabel(file.status)}</span>
                      <span className="chat-review-file-name">{getFileName(file.path)}</span>
                      <span className="chat-review-file-path">{file.path}</span>
                    </button>
                  ))}
                </div>
              </div>
            )}
            <div className="chat-task-section">
              <div className="chat-task-heading">
                <span>任务列表</span>
                <span>{formatNumber(sortedTasks.length)}</span>
              </div>
              <div className="chat-task-list">
                {sortedTasks.length === 0 && (
                  <div className="chat-task-empty">暂无任务</div>
                )}
                {sortedTasks.map((task, index) => {
                  const statusMeta = getTaskStatusMeta(task.status);
                  const isActiveTask = activeTaskId === task.id;
                  const progress = isActiveTask && !task.status ? 10 : statusMeta.progress;
                  return (
                    <button
                      key={task.id}
                      type="button"
                      className={`chat-result-card-shell${isActiveTask ? ' selected' : ''}`}
                      onClick={() => handleSelectTask(task.id)}
                    >
                      <span className="chat-task-card-header">
                        <span className="chat-task-card-title">任务 {index + 1} · {task.title}</span>
                        <span className={`chat-task-tag ${statusMeta.tone}`}>
                          {statusMeta.label}
                        </span>
                      </span>
                      <span className="chat-task-card-footer">
                        <span className="chat-task-card-meta">
                          <span>{formatNumber(task.messageCount)} 条消息</span>
                          <span>{formatRelativeTime(task.timestamp)}</span>
                        </span>
                        <span className={`chat-task-progress ${statusMeta.tone}`}>
                          <span style={{ width: `${progress}%` }} />
                        </span>
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>
          </div>
        )}
      </div>
    </header>
  );
}

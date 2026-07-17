import { useId, useMemo, useState } from 'react';
import { Icon } from './common/Icon';
import './ChatTaskList.css';

export interface ChatTask {
  id: string;
  title: string;
  status: 'pending' | 'in_progress' | 'done';
  group?: string;
  line?: number;
}

interface ChatTaskListProps {
  tasks: ChatTask[];
}

function getTaskStatusMeta(status: ChatTask['status']) {
  if (status === 'in_progress') return { label: '进行中', tone: 'primary' };
  if (status === 'done') return { label: '已完成', tone: 'success' };
  return { label: '待处理', tone: 'info' };
}

export function ChatTaskList({ tasks }: ChatTaskListProps) {
  const [expanded, setExpanded] = useState(false);
  const contentId = useId();
  const sortedTasks = useMemo(() => {
    return [...tasks].sort((a, b) => {
      if (typeof a.line === 'number' && typeof b.line === 'number') return a.line - b.line;
      return 0;
    });
  }, [tasks]);
  const completedCount = sortedTasks.filter(task => task.status === 'done').length;
  const inProgressCount = sortedTasks.filter(task => task.status === 'in_progress').length;
  const progress = sortedTasks.length > 0 ? Math.round((completedCount / sortedTasks.length) * 100) : 0;

  if (sortedTasks.length === 0) return null;

  return (
    <section className={`chat-task-panel${expanded ? ' expanded' : ''}`} aria-label="任务列表">
      {expanded && (
        <div id={contentId} className="chat-task-content">
          <ol className="chat-task-items">
            {sortedTasks.map((task, index) => {
              const status = getTaskStatusMeta(task.status);
              return (
                <li key={task.id} className={`chat-task-item ${status.tone}`}>
                  <span className="chat-task-index">{index + 1}</span>
                  <span className="chat-task-item-main">
                    <span className="chat-task-item-title">{task.title}</span>
                    {(task.group || typeof task.line === 'number') && (
                      <span className="chat-task-item-meta">
                        {task.group || '当前任务清单'}
                        {typeof task.line === 'number' && ` · 第 ${task.line} 行`}
                      </span>
                    )}
                  </span>
                  <span className={`chat-task-status ${status.tone}`}>{status.label}</span>
                </li>
              );
            })}
          </ol>
        </div>
      )}
      <button
        type="button"
        className="chat-task-trigger"
        onClick={() => setExpanded(current => !current)}
        aria-expanded={expanded}
        aria-controls={contentId}
      >
        <span className="chat-task-trigger-icon" aria-hidden="true">
          <Icon name="check" size={13} />
        </span>
        <span className="chat-task-trigger-main">
          <span className="chat-task-trigger-title">任务列表</span>
          <span className="chat-task-trigger-summary">
            已完成 {completedCount}/{sortedTasks.length}
            {inProgressCount > 0 && ` · ${inProgressCount} 项进行中`}
          </span>
        </span>
        <span className="chat-task-trigger-progress" aria-hidden="true">
          <span style={{ width: `${progress}%` }} />
        </span>
        <Icon name={expanded ? 'chevron-down' : 'chevron-up'} size={14} />
      </button>
    </section>
  );
}

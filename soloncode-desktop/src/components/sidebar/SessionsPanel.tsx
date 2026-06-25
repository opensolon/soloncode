import { useState, useCallback } from 'react';
import { Icon } from '../common/Icon';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { UNLINKED_PROJECT } from '../../db';
import './SessionsPanel.css';

export interface Session {
  id: string;
  title: string;
  timestamp: string;
  messageCount: number;
  isPermanent?: boolean;
  workspacePath?: string;
}

export interface Project {
  id: string;
  name: string;
  sortOrder: number;
}

interface SessionsPanelProps {
  projects: Project[];
  sessions: Session[];
  currentSessionId: string | null;
  currentProjectId: string | null;
  backendPort?: number | null;
  sessionRunStates?: Record<string, 'running' | 'completed' | 'error'>;
  onSelectSession: (id: string) => void;
  onNewSession: (projectId?: string) => string | void;
  onDeleteSession: (id: string) => void;
  onAddProject: () => void;
  onRemoveProject: (id: string) => void;
  onSyncSession?: (sessionId: string) => Promise<void>;
}

function formatRelativeTime(timestamp: string) {
  const time = new Date(timestamp).getTime();
  if (!Number.isFinite(time)) return timestamp;
  const diff = Date.now() - time;
  const minute = 60 * 1000;
  const hour = 60 * minute;
  const day = 24 * hour;
  if (diff < minute) return '刚刚';
  if (diff < hour) return `${Math.floor(diff / minute)}分钟前`;
  if (diff < day) return `${Math.floor(diff / hour)}小时前`;
  if (diff < day * 30) return `${Math.floor(diff / day)}天前`;
  return new Date(timestamp).toLocaleDateString();
}

export function SessionsPanel({
  projects,
  sessions,
  currentSessionId,
  currentProjectId,
  sessionRunStates = {},
  onSelectSession,
  onNewSession,
  onDeleteSession,
  onAddProject,
  onRemoveProject,
  onSyncSession,
}: SessionsPanelProps) {
  const [syncingIds, setSyncingIds] = useState<Set<string>>(new Set());
  const [confirmSync, setConfirmSync] = useState<{ sessionId: string; title: string } | null>(null);
  const [confirmSyncAll, setConfirmSyncAll] = useState(false);
  const [expandedProjects, setExpandedProjects] = useState<Set<string>>(() => {
    if (currentProjectId) return new Set([currentProjectId]);
    return new Set<string>();
  });

  const toggleExpand = (projectId: string) => {
    setExpandedProjects(prev => {
      const next = new Set(prev);
      if (next.has(projectId)) next.delete(projectId);
      else next.add(projectId);
      return next;
    });
  };

  const sessionsByProject = new Map<string, Session[]>();
  for (const session of sessions) {
    const key = session.workspacePath || UNLINKED_PROJECT;
    if (!sessionsByProject.has(key)) sessionsByProject.set(key, []);
    sessionsByProject.get(key)!.push(session);
  }

  const projectEntries: { id: string; name: string }[] = [
    ...projects.map(project => ({ id: project.id, name: project.name })),
  ];
  for (const [projectId] of sessionsByProject) {
    if (projectId !== UNLINKED_PROJECT && !projects.find(project => project.id === projectId)) {
      const name = projectId.split(/[/\\]/).pop() || projectId;
      projectEntries.push({ id: projectId, name });
    }
  }

  const unlinkedSessions = sessionsByProject.get(UNLINKED_PROJECT) || [];

  const handleSync = useCallback(async (sessionId: string) => {
    if (syncingIds.has(sessionId)) return;
    setSyncingIds(prev => new Set(prev).add(sessionId));
    try {
      await onSyncSession?.(sessionId);
    } finally {
      setSyncingIds(prev => {
        const next = new Set(prev);
        next.delete(sessionId);
        return next;
      });
    }
  }, [syncingIds, onSyncSession]);

  const requestSync = useCallback((sessionId: string, title: string) => {
    setConfirmSync({ sessionId, title });
  }, []);

  const handleSyncAll = useCallback(async () => {
    for (const session of sessions) {
      if (!session.id.startsWith('temp-') && !session.id.startsWith('pending-')) {
        await handleSync(session.id);
      }
    }
  }, [sessions, handleSync]);

  function renderSessionList(sessionList: Session[]) {
    return sessionList.map(session => {
      const runState = sessionRunStates[session.id];
      return (
        <div
          key={session.id}
          className={`session-item${currentSessionId === session.id ? ' active' : ''}${runState ? ` ${runState}` : ''}`}
          onClick={() => onSelectSession(session.id)}
        >
          <div className="session-icon">
            <Icon name={session.isPermanent ? 'bot' : 'chat'} size={14} />
          </div>
          <div className="session-info">
            <div className="session-title">{session.title}</div>
            <div className="session-meta">
              <span>{session.messageCount}</span>
              <span className="separator">·</span>
              <span>{formatRelativeTime(session.timestamp)}</span>
            </div>
          </div>
          {runState && (
            <span
              className={`session-run-dot ${runState}`}
              title={runState === 'running' ? '正在运行' : runState === 'completed' ? '运行完成' : '运行失败'}
            />
          )}
          <button
            className={`sync-btn${syncingIds.has(session.id) ? ' syncing' : ''}`}
            onClick={event => { event.stopPropagation(); requestSync(session.id, session.title); }}
            title="同步消息"
          >
            <Icon name={syncingIds.has(session.id) ? 'loading' : 'refresh'} size={14} />
          </button>
          {!session.isPermanent && (
            <button
              className="delete-btn"
              onClick={event => { event.stopPropagation(); onDeleteSession(session.id); }}
              title="删除"
            >
              <Icon name="delete" size={14} />
            </button>
          )}
        </div>
      );
    });
  }

  return (
    <div className="sessions-panel">
      {confirmSync && (
        <ConfirmDialog
          title="同步确认"
          message={`将从后端重新拉取「${confirmSync.title}」的消息并覆盖本地记录。同步后可能出现消息格式不兼容、内容丢失等异常情况，是否确认？`}
          confirmLabel="确认同步"
          cancelLabel="取消"
          danger
          onConfirm={() => { handleSync(confirmSync.sessionId); setConfirmSync(null); }}
          onCancel={() => setConfirmSync(null)}
        />
      )}
      {confirmSyncAll && (
        <ConfirmDialog
          title="同步全部确认"
          message="将重新拉取所有会话的消息并覆盖本地记录。同步后可能出现消息格式不兼容、内容丢失等异常情况，是否确认？"
          confirmLabel="确认同步全部"
          cancelLabel="取消"
          danger
          onConfirm={() => { handleSyncAll(); setConfirmSyncAll(false); }}
          onCancel={() => setConfirmSyncAll(false)}
        />
      )}

      <div className="panel-header">
        <span className="panel-title">项目</span>
        <div className="panel-header-actions">
          <button className="new-session-btn" onClick={() => onNewSession(currentProjectId || undefined)} title="新建会话">
            <Icon name="add" size={16} />
          </button>
          <button className="new-session-btn" onClick={onAddProject} title="添加项目">
            <Icon name="folder" size={14} />
          </button>
        </div>
      </div>

      <div className="sessions-list">
        {projectEntries.length > 0 && projectEntries.map(entry => {
          const projectSessions = sessionsByProject.get(entry.id) || [];
          const isExpanded = expandedProjects.has(entry.id);
          const isActive = currentProjectId === entry.id;

          return (
            <div key={entry.id} className="project-group">
              <div
                className={`project-header${isActive ? ' active' : ''}`}
                onClick={() => toggleExpand(entry.id)}
              >
                <Icon name={isExpanded ? 'chevron-down' : 'chevron-right'} size={12} />
                <Icon name="folder" size={14} />
                <span className="project-name">{entry.name}</span>
                <span className="project-count">{projectSessions.length}</span>
                <button
                  className="project-remove-btn"
                  onClick={event => { event.stopPropagation(); onRemoveProject(entry.id); }}
                  title="移除项目"
                >
                  <Icon name="delete" size={12} />
                </button>
                <button
                  className="project-add-session-btn"
                  onClick={event => { event.stopPropagation(); onNewSession(entry.id); }}
                  title="新建会话"
                >
                  <Icon name="add" size={12} />
                </button>
              </div>

              {isExpanded && (
                <div className="project-sessions">
                  {projectSessions.length === 0 && (
                    <div className="project-empty">暂无会话</div>
                  )}
                  {renderSessionList(projectSessions)}
                </div>
              )}
            </div>
          );
        })}

        <div className="group-header chat-group-header">
          <span>对话</span>
          <button
            className="chat-add-btn"
            onClick={() => onNewSession(UNLINKED_PROJECT)}
            title="新建对话"
          >
            <Icon name="add" size={12} />
          </button>
        </div>

        {unlinkedSessions.length === 0 && (
          <div className="project-empty">暂无对话</div>
        )}
        {renderSessionList(unlinkedSessions)}
      </div>
    </div>
  );
}

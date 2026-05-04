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
  id: string;       // workspace path
  name: string;
  sortOrder: number;
}

interface SessionsPanelProps {
  projects: Project[];
  sessions: Session[];
  currentSessionId: string | null;
  currentProjectId: string | null;
  backendPort?: number | null;
  onSelectSession: (id: string) => void;
  onNewSession: (projectId?: string) => string | void;
  onDeleteSession: (id: string) => void;
  onAddProject: () => void;
  onRemoveProject: (id: string) => void;
  onSyncSession?: (sessionId: string) => Promise<void>;
}

export function SessionsPanel({
  projects,
  sessions,
  currentSessionId,
  currentProjectId,
  backendPort,
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

  // 按项目分组会话
  const sessionsByProject = new Map<string, Session[]>();
  for (const s of sessions) {
    const key = s.workspacePath || UNLINKED_PROJECT;
    if (!sessionsByProject.has(key)) sessionsByProject.set(key, []);
    sessionsByProject.get(key)!.push(s);
  }

  // 项目列表（不含"对话"）
  const projectEntries: { id: string; name: string }[] = [
    ...projects.map(p => ({ id: p.id, name: p.name })),
  ];
  // 如果某个项目有会话但不在 projects 列表中，也加上
  for (const [projectId] of sessionsByProject) {
    if (projectId !== UNLINKED_PROJECT && !projects.find(p => p.id === projectId)) {
      const name = projectId.split(/[/\\]/).pop() || projectId;
      projectEntries.push({ id: projectId, name });
    }
  }

  const unlinkedSessions = sessionsByProject.get(UNLINKED_PROJECT) || [];
  const isUnlinkedExpanded = expandedProjects.has(UNLINKED_PROJECT);

  // 渲染项目或对话组的会话列表
  function renderSessionList(sessionList: Session[]) {
    return sessionList.map(session => (
      <div
        key={session.id}
        className={`session-item${currentSessionId === session.id ? ' active' : ''}`}
        onClick={() => onSelectSession(session.id)}
      >
        <div className="session-icon">
          <Icon name={session.isPermanent ? 'bot' : 'chat'} size={16} />
        </div>
        <div className="session-info">
          <div className="session-title">{session.title}</div>
          <div className="session-meta">
            <span>{session.messageCount} 条消息</span>
            <span className="separator">·</span>
            <span>{session.timestamp}</span>
          </div>
        </div>
        <button
          className={`sync-btn${syncingIds.has(session.id) ? ' syncing' : ''}`}
          onClick={e => { e.stopPropagation(); requestSync(session.id, session.title); }}
          title="同步消息"
        >
          <Icon name={syncingIds.has(session.id) ? 'loading' : 'refresh'} size={14} />
        </button>
        {!session.isPermanent && (
          <button
            className="delete-btn"
            onClick={e => { e.stopPropagation(); onDeleteSession(session.id); }}
            title="删除"
          >
            <Icon name="delete" size={14} />
          </button>
        )}
      </div>
    ));
  }

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
    for (const s of sessions) {
      if (!s.id.startsWith('temp-') && !s.id.startsWith('pending-')) {
        await handleSync(s.id);
      }
    }
  }, [sessions, handleSync]);

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
          <button className="new-session-btn" onClick={() => setConfirmSyncAll(true)} title="同步全部">
            <Icon name="refresh" size={14} />
          </button>
          <button className="new-session-btn" onClick={() => onNewSession(currentProjectId || undefined)} title="新建会话">
            <Icon name="add" size={16} />
          </button>
          <button className="new-session-btn" onClick={onAddProject} title="添加项目">
            <Icon name="folder" size={14} />
          </button>
        </div>
      </div>

      <div className="sessions-list">
        {/* 上半部分：项目列表 */}
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
                  onClick={e => { e.stopPropagation(); onRemoveProject(entry.id); }}
                  title="移除项目"
                >
                  <Icon name="delete" size={12} />
                </button>
                <button
                  className="project-add-session-btn"
                  onClick={e => { e.stopPropagation(); onNewSession(entry.id); }}
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

        {/* 对话列表：直接平铺，不需要折叠分组 */}
        {unlinkedSessions.length === 0 && (
          <div className="project-empty">暂无对话</div>
        )}
        {renderSessionList(unlinkedSessions)}
      </div>
    </div>
  );
}

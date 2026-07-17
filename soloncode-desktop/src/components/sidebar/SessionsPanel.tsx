import { useState, useCallback, useEffect, useRef } from 'react';
import { revealItemInDir } from '@tauri-apps/plugin-opener';
import { Icon } from '../common/Icon';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { ContextMenu } from '../common/ContextMenu';
import { DropdownMenu } from '../common/DropdownMenu';
import { UNLINKED_PROJECT } from '../../db';
import { copyTextToClipboard } from '../../utils/clipboard';
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
  onCreateProject: () => void;
  onAddProject: () => void;
  onRemoveProject: (id: string) => void;
  onPinProject: (id: string) => void;
  onRenameProject: (id: string, name: string) => void;
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
  onCreateProject,
  onAddProject,
  onRemoveProject,
  onPinProject,
  onRenameProject,
  onSyncSession,
}: SessionsPanelProps) {
  const [syncingIds, setSyncingIds] = useState<Set<string>>(new Set());
  const [confirmSync, setConfirmSync] = useState<{ sessionId: string; title: string } | null>(null);
  const [confirmSyncAll, setConfirmSyncAll] = useState(false);
  const [deleteProjectTarget, setDeleteProjectTarget] = useState<Project | null>(null);
  const [projectActionMessage, setProjectActionMessage] = useState<{ text: string; error?: boolean } | null>(null);
  const [projectContextMenu, setProjectContextMenu] = useState<{
    x: number;
    y: number;
    projectId: string;
  } | null>(null);
  const [projectMenu, setProjectMenu] = useState<{
    x: number;
    y: number;
    projectId: string;
  } | null>(null);
  const projectMenuRef = useRef<HTMLDivElement>(null);
  const projectMenuCloseTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const renameCancelledRef = useRef(false);
  const [renamingProjectId, setRenamingProjectId] = useState<string | null>(null);
  const [renameProjectValue, setRenameProjectValue] = useState('');
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

  const projectEntries = projects.map(project => ({ id: project.id, name: project.name }));
  const projectIds = new Set(projects.map(project => project.id));
  const unlinkedSessions = sessions.filter(session => {
    const projectId = session.workspacePath || UNLINKED_PROJECT;
    return projectId === UNLINKED_PROJECT || !projectIds.has(projectId);
  });
  const menuProject = projectMenu
    ? projects.find(project => project.id === projectMenu.projectId)
    : undefined;

  useEffect(() => {
    if (!projectMenu) return;

    const closeMenu = (event: MouseEvent) => {
      if (event.target instanceof Element && event.target.closest('.project-more-btn')) return;
      if (!projectMenuRef.current?.contains(event.target as Node)) setProjectMenu(null);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setProjectMenu(null);
    };
    const closeOnScroll = () => setProjectMenu(null);

    document.addEventListener('mousedown', closeMenu);
    document.addEventListener('keydown', closeOnEscape);
    document.addEventListener('wheel', closeOnScroll, true);
    return () => {
      document.removeEventListener('mousedown', closeMenu);
      document.removeEventListener('keydown', closeOnEscape);
      document.removeEventListener('wheel', closeOnScroll, true);
    };
  }, [projectMenu]);

  useEffect(() => () => {
    if (projectMenuCloseTimerRef.current) clearTimeout(projectMenuCloseTimerRef.current);
  }, []);

  const showProjectMenu = useCallback((event: React.MouseEvent<HTMLButtonElement>, projectId: string) => {
    if (projectMenuCloseTimerRef.current) clearTimeout(projectMenuCloseTimerRef.current);
    setProjectContextMenu(null);
    const rect = event.currentTarget.getBoundingClientRect();
    const width = 150;
    const height = 156;
    const x = Math.min(rect.right + 4, window.innerWidth - width - 8);
    const y = Math.min(rect.top, window.innerHeight - height - 8);
    setProjectMenu({ x: Math.max(8, x), y: Math.max(8, y), projectId });
  }, []);

  const scheduleProjectMenuClose = useCallback(() => {
    if (projectMenuCloseTimerRef.current) clearTimeout(projectMenuCloseTimerRef.current);
    projectMenuCloseTimerRef.current = setTimeout(() => setProjectMenu(null), 140);
  }, []);

  const keepProjectMenuOpen = useCallback(() => {
    if (projectMenuCloseTimerRef.current) clearTimeout(projectMenuCloseTimerRef.current);
  }, []);

  const beginProjectRename = useCallback((projectId: string) => {
    const project = projects.find(item => item.id === projectId);
    if (!project) return;
    renameCancelledRef.current = false;
    setRenameProjectValue(project.name);
    setRenamingProjectId(projectId);
  }, [projects]);

  const commitProjectRename = useCallback((projectId: string, value: string) => {
    const trimmedValue = value.trim();
    if (trimmedValue) {
      onRenameProject(projectId, trimmedValue);
    }
    setRenamingProjectId(null);
    setRenameProjectValue('');
  }, [onRenameProject, projects]);

  const isCurrentSessionLatest = (sessionList: Session[]) => {
    if (!currentSessionId || sessionList.length === 0) return false;

    const latestSession = sessionList.reduce((latest, session) => {
      const latestTime = Date.parse(latest.timestamp);
      const sessionTime = Date.parse(session.timestamp);
      const normalizedLatestTime = Number.isFinite(latestTime) ? latestTime : 0;
      const normalizedSessionTime = Number.isFinite(sessionTime) ? sessionTime : 0;
      return normalizedSessionTime > normalizedLatestTime ? session : latest;
    });

    return latestSession.id === currentSessionId;
  };

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

  const handleProjectAction = useCallback((itemId: string, projectId?: string) => {
    const targetProjectId = projectId || projectContextMenu?.projectId || projectMenu?.projectId;
    setProjectContextMenu(null);
    setProjectMenu(null);
    if (!targetProjectId) return;

    if (itemId === 'pin') {
      onPinProject(targetProjectId);
    } else if (itemId === 'rename') {
      beginProjectRename(targetProjectId);
    } else if (itemId === 'open-in-explorer') {
      revealItemInDir(targetProjectId).catch(err => {
        console.error('[SessionsPanel] 打开资源管理器失败:', err);
      });
    } else if (itemId === 'copy') {
      const copyPath = async () => {
        await copyTextToClipboard(targetProjectId);
        setProjectActionMessage({ text: '项目路径已复制' });
      };
      void copyPath().catch(error => {
        console.error('[SessionsPanel] 复制项目路径失败:', error);
        setProjectActionMessage({ text: '复制项目路径失败', error: true });
      });
    } else if (itemId === 'delete') {
      const project = projects.find(item => item.id === targetProjectId);
      if (project) setDeleteProjectTarget(project);
    }
  }, [beginProjectRename, onPinProject, projectContextMenu, projectMenu, projects]);

  const handleNewProjectAction = useCallback((itemId: string) => {
    if (itemId === 'new-empty-project') {
      onCreateProject();
    } else if (itemId === 'use-existing-project') {
      onAddProject();
    }
  }, [onCreateProject, onAddProject]);

  function renderSessionList(sessionList: Session[]) {
    return sessionList.map(session => {
      const runState = sessionRunStates[session.id];
      return (
        <div
          key={session.id}
          className={`session-item${currentSessionId === session.id ? ' active' : ''}${runState ? ` ${runState}` : ''}`}
          onClick={() => onSelectSession(session.id)}
        >
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
      {deleteProjectTarget && (
        <ConfirmDialog
          title="删除项目"
          message={`仅将「${deleteProjectTarget.name}」从项目管理中移除，不会删除磁盘上的项目目录和文件。`}
          confirmLabel="删除"
          danger
          onConfirm={() => {
            onRemoveProject(deleteProjectTarget.id);
            setDeleteProjectTarget(null);
            setProjectActionMessage({ text: '项目已从列表删除' });
          }}
          onCancel={() => setDeleteProjectTarget(null)}
        />
      )}

      <div className="panel-header">
        <span className="panel-title">项目</span>
        <div className="panel-header-actions">
          <DropdownMenu
            align="right"
            items={[
              { id: 'new-empty-project', label: '新建空项目' },
              { id: 'use-existing-project', label: '使用现有项目' },
            ]}
            onItemClick={handleNewProjectAction}
            trigger={(
              <button className="new-session-btn" title="新建项目" aria-label="新建项目">
                <Icon name="add" size={16} />
              </button>
            )}
          />
        </div>
      </div>

      <div className="sessions-list">
        {projectActionMessage && <div className={`resource-action-message${projectActionMessage.error ? ' error' : ''}`}>{projectActionMessage.text}</div>}
        {projectEntries.length > 0 && projectEntries.map(entry => {
          const projectSessions = sessionsByProject.get(entry.id) || [];
          const isExpanded = expandedProjects.has(entry.id);
          const isActive = currentProjectId === entry.id;

          return (
            <div key={entry.id} className="project-group">
              <div
                className={`project-header${isActive ? ' active' : ''}${isExpanded ? ' expanded' : ''}`}
                onClick={() => toggleExpand(entry.id)}
                onContextMenu={event => {
                  event.preventDefault();
                  event.stopPropagation();
                  setProjectMenu(null);
                  setProjectContextMenu({ x: event.clientX, y: event.clientY, projectId: entry.id });
                }}
              >
                <Icon name={isExpanded ? 'folder-open' : 'folder'} size={14} />
                {renamingProjectId === entry.id ? (
                  <input
                    className="project-rename-input"
                    value={renameProjectValue}
                    maxLength={64}
                    autoFocus
                    onChange={event => setRenameProjectValue(event.target.value)}
                    onClick={event => event.stopPropagation()}
                    onKeyDown={event => {
                      if (event.key === 'Enter') {
                        event.preventDefault();
                        commitProjectRename(entry.id, renameProjectValue);
                      } else if (event.key === 'Escape') {
                        event.preventDefault();
                        renameCancelledRef.current = true;
                        setRenamingProjectId(null);
                        setRenameProjectValue('');
                      }
                    }}
                    onBlur={() => {
                      if (renameCancelledRef.current) {
                        renameCancelledRef.current = false;
                        return;
                      }
                      commitProjectRename(entry.id, renameProjectValue);
                    }}
                    aria-label={`重命名 ${entry.name}`}
                  />
                ) : (
                  <span className="project-name">{entry.name}</span>
                )}
                <button
                  className="project-more-btn"
                  onMouseEnter={event => showProjectMenu(event, entry.id)}
                  onMouseLeave={scheduleProjectMenuClose}
                  onFocus={event => showProjectMenu(event, entry.id)}
                  onClick={event => event.stopPropagation()}
                  title="项目菜单"
                  aria-label={`${entry.name} 项目菜单`}
                  aria-haspopup="menu"
                >
                  <Icon name="more" size={14} />
                </button>
                <button
                  className="project-add-session-btn"
                  onClick={event => { event.stopPropagation(); onNewSession(entry.id); }}
                  title="新建会话"
                  aria-label={`在 ${entry.name} 中新建会话`}
                >
                  <Icon name="edit" size={14} />
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
          {!isCurrentSessionLatest(unlinkedSessions) && (
            <button
              className="chat-add-btn"
              onClick={() => onNewSession(UNLINKED_PROJECT)}
              title="新建对话"
            >
              <Icon name="add" size={12} />
            </button>
          )}
        </div>

        {unlinkedSessions.length === 0 && (
          <div className="project-empty">暂无对话</div>
        )}
        {renderSessionList(unlinkedSessions)}
      </div>

      {projectContextMenu && (
        <ContextMenu
          x={projectContextMenu.x}
          y={projectContextMenu.y}
          items={[
            {
              id: 'pin',
              label: '置顶',
              disabled: projects[0]?.id === projectContextMenu.projectId,
            },
            { id: 'rename', label: '重命名' },
            { id: 'copy', label: '复制路径' },
            { id: 'open-in-explorer', label: '在资源管理器中打开' },
            { id: 'delete', label: '删除', danger: true },
          ]}
          onItemClick={handleProjectAction}
          onClose={() => setProjectContextMenu(null)}
        />
      )}

      {projectMenu && menuProject && (
        <div
          ref={projectMenuRef}
          className="project-menu-popover"
          style={{ left: projectMenu.x, top: projectMenu.y }}
          role="menu"
          aria-label={`${menuProject.name} 项目菜单`}
          onMouseEnter={keepProjectMenuOpen}
          onMouseLeave={scheduleProjectMenuClose}
          onClick={event => event.stopPropagation()}
        >
          <button
            type="button"
            className="project-menu-item"
            role="menuitem"
            disabled={projects[0]?.id === menuProject.id}
            onClick={() => handleProjectAction('pin', menuProject.id)}
          >
            <Icon name="pin" size={14} />
            <span>置顶</span>
          </button>
          <button
            type="button"
            className="project-menu-item"
            role="menuitem"
            onClick={() => handleProjectAction('rename', menuProject.id)}
          >
            <Icon name="edit" size={14} />
            <span>重命名</span>
          </button>
          <button
            type="button"
            className="project-menu-item"
            role="menuitem"
            onClick={() => handleProjectAction('copy', menuProject.id)}
          >
            <Icon name="copy" size={14} />
            <span>复制路径</span>
          </button>
          <button
            type="button"
            className="project-menu-item"
            role="menuitem"
            onClick={() => handleProjectAction('open-in-explorer', menuProject.id)}
          >
            <Icon name="folder-open" size={14} />
            <span>在资源管理器中打开</span>
          </button>
          <button
            type="button"
            className="project-menu-item danger"
            role="menuitem"
            onClick={() => handleProjectAction('delete', menuProject.id)}
          >
            <Icon name="delete" size={14} />
            <span>删除</span>
          </button>
        </div>
      )}
    </div>
  );
}

import { useState, useCallback, useEffect, useMemo } from 'react';
import {
  getAllConversations, saveConversation, deleteConversation,
  updateConversation, saveLastSessionId, loadLastSessionId,
  migrateConversationsToProjects, reassignMessages,
  getMessageCount,
  UNLINKED_PROJECT,
} from '../db';
import type { Conversation } from '../types';

export interface Session {
  id: string;
  title: string;
  timestamp: string;
  messageCount: number;
  isPermanent?: boolean;
  workspacePath?: string;
}

export function useSessions(
  activeProjectPath: string | null,
  options?: {
    onSessionIdResolved?: (oldId: string, newId: string) => void;
  },
) {
  const [sessions, setSessions] = useState<Session[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<string>();
  const [pendingSessionProject, setPendingSessionProject] = useState<string | null>(null);

  // 初始化加载会话
  useEffect(() => {
    (async () => {
      await migrateConversationsToProjects();
      const convs = await getAllConversations();
      const loaded: Session[] = await Promise.all(convs.map(async c => {
        const id = c.id!.toString();
        return {
          id,
          title: c.title,
          timestamp: c.timestamp,
          messageCount: await getMessageCount(id),
          isPermanent: c.isPermanent,
          workspacePath: c.workspacePath || UNLINKED_PROJECT,
        };
      }));
      setSessions(loaded);
    })();
  }, []);

  // 恢复项目最后会话
  const restoreLastSession = useCallback(async (projectPath: string) => {
    const lastSessionId = await loadLastSessionId(projectPath);
    if (lastSessionId) {
      setCurrentSessionId(lastSessionId);
    }
  }, []);

  // 保存最后会话
  useEffect(() => {
    if (activeProjectPath && currentSessionId) {
      saveLastSessionId(activeProjectPath, currentSessionId);
    }
  }, [activeProjectPath, currentSessionId]);

  const handleNewSession = useCallback((projectId?: string, _title?: string): string => {
    const currentSession = sessions.find(s => s.id === currentSessionId);
    if (currentSessionId && !currentSessionId.startsWith('temp-') && !currentSession) {
      return '';
    }

    if (projectId && projectId !== UNLINKED_PROJECT) {
      setPendingSessionProject(projectId);
    } else {
      setPendingSessionProject(null);
    }

    const tempId = `temp-${Date.now()}`;
    const workspacePath = projectId && projectId !== UNLINKED_PROJECT
      ? projectId
      : (activeProjectPath || UNLINKED_PROJECT);
    const title = _title || '新会话';
    const timestamp = new Date().toISOString();
    setSessions(prev => [
      { id: tempId, title, timestamp, messageCount: 0, workspacePath },
      ...prev.filter(s => s.id !== tempId),
    ]);
    setCurrentSessionId(tempId);
    return tempId;
  }, [activeProjectPath, currentSessionId, sessions]);

  const handleDeleteSession = useCallback((id: string) => {
    const remaining = sessions.filter(s => s.id !== id);
    setSessions(remaining);
    deleteConversation(id);
    if (currentSessionId === id) {
      setCurrentSessionId(remaining.length > 0 ? remaining[0].id : undefined);
    }
  }, [currentSessionId, sessions]);

  const handleUpdateSessionTitle = useCallback((sessionId: string, title: string) => {
    const wsPath = pendingSessionProject || activeProjectPath || UNLINKED_PROJECT;

    setSessions(prev => {
      const exists = prev.find(s => s.id === sessionId);

      if (!sessionId.startsWith('temp-')) {
        if (exists && exists.title === '新会话') {
          updateConversation(sessionId, { title });
          return prev.map(s =>
            s.id === sessionId && s.title === '新会话' ? { ...s, title } : s
          );
        }
        return prev;
      }

      // temp 会话：异步持久化后加入列表并替换为真实 ID
      const sessionWsPath = exists?.workspacePath || wsPath;
      saveConversation({ title, timestamp: exists?.timestamp || new Date().toLocaleString(), status: 'active', workspacePath: sessionWsPath }).then(async (dbId) => {
        const realId = dbId.toString();
        await reassignMessages(sessionId, realId);
        setSessions(p => {
          if (p.find(s => s.id === sessionId)) {
            return p.map(s => s.id === sessionId ? { ...s, id: realId, title, workspacePath: sessionWsPath, timestamp: new Date().toISOString() } : s);
          }
          return [{ id: realId, title, timestamp: new Date().toLocaleString(), messageCount: 0, workspacePath: sessionWsPath }, ...p];
        });
        setCurrentSessionId(current => current === sessionId ? realId : current);
        options?.onSessionIdResolved?.(sessionId, realId);
      });

      if (exists) {
        return prev.map(s => s.id === sessionId ? { ...s, title } : s);
      }
      return prev;
    });
    setPendingSessionProject(null);
  }, [activeProjectPath, pendingSessionProject, options]);

  const incrementSessionMessageCount = useCallback((sessionId: string, count = 1) => {
    setSessions(prev => prev.map(session =>
      session.id === sessionId
        ? { ...session, messageCount: session.messageCount + count, timestamp: new Date().toISOString() }
        : session
    ));
  }, []);

  const currentConversation: Conversation = useMemo(() => {
    const session = sessions.find(s => s.id === currentSessionId);
    return {
      id: currentSessionId,
      title: session?.title || '新会话',
      timestamp: new Date().toLocaleString(),
      status: 'active',
      workspacePath: session?.workspacePath,
    };
  }, [currentSessionId, sessions]);

  return {
    sessions,
    setSessions,
    currentSessionId,
    setCurrentSessionId,
    currentConversation,
    handleNewSession,
    handleDeleteSession,
    handleUpdateSessionTitle,
    incrementSessionMessageCount,
    restoreLastSession,
  };
}

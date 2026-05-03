import { useState, useCallback, useEffect, useMemo } from 'react';
import {
  getAllConversations, saveConversation, deleteConversation,
  updateConversation, saveLastSessionId, loadLastSessionId,
  migrateConversationsToProjects, reassignMessages,
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

export function useSessions(activeProjectPath: string | null) {
  const [sessions, setSessions] = useState<Session[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<string>();
  const [pendingSessionProject, setPendingSessionProject] = useState<string | null>(null);

  // 初始化加载会话
  useEffect(() => {
    (async () => {
      await migrateConversationsToProjects();
      const convs = await getAllConversations();
      const loaded: Session[] = convs.map(c => ({
        id: c.id!.toString(),
        title: c.title,
        timestamp: c.timestamp,
        messageCount: 0,
        isPermanent: c.isPermanent,
        workspacePath: c.workspacePath || UNLINKED_PROJECT,
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
    setCurrentSessionId(tempId);
    return tempId;
  }, [currentSessionId, sessions]);

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

      if (!exists) {
        saveConversation({ title, timestamp: '刚刚', status: 'active', workspacePath: wsPath }).then(dbId => {
          const realId = dbId.toString();
          reassignMessages(sessionId, dbId);
          setSessions(p => [{ id: realId, title, timestamp: '刚刚', messageCount: 0, workspacePath: wsPath }, ...p]);
          setCurrentSessionId(realId);
        });
        return prev;
      }

      if (!sessionId.startsWith('temp-')) {
        if (exists.title === '新会话') {
          updateConversation(sessionId, { title });
        }
        return prev.map(s =>
          s.id === sessionId && s.title === '新会话' ? { ...s, title } : s
        );
      }

      saveConversation({ title, timestamp: exists.timestamp, status: 'active', workspacePath: exists.workspacePath || wsPath }).then(dbId => {
        const realId = dbId.toString();
        reassignMessages(sessionId, dbId);
        setSessions(p => p.map(s => s.id === sessionId ? { ...s, id: realId, title } : s));
        setCurrentSessionId(realId);
      });
      return prev.map(s =>
        s.id === sessionId ? { ...s, title } : s
      );
    });
    setPendingSessionProject(null);
  }, [activeProjectPath, pendingSessionProject]);

  const currentConversation: Conversation = useMemo(() => ({
    id: currentSessionId,
    title: sessions.find(s => s.id === currentSessionId)?.title || '新会话',
    timestamp: new Date().toLocaleString(),
    status: 'active',
  }), [currentSessionId, sessions]);

  return {
    sessions,
    setSessions,
    currentSessionId,
    setCurrentSessionId,
    currentConversation,
    handleNewSession,
    handleDeleteSession,
    handleUpdateSessionTitle,
    restoreLastSession,
  };
}

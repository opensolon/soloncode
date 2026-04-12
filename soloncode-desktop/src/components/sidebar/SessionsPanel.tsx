import { Icon } from '../common/Icon';
import './SessionsPanel.css';

export interface Session {
  id: string;
  title: string;
  timestamp: string;
  messageCount: number;
  isPermanent?: boolean;
}

interface SessionsPanelProps {
  sessions: Session[];
  currentSessionId: string | null;
  onSelectSession: (id: string) => void;
  onNewSession: () => string | void;
  onDeleteSession: (id: string) => void;
}

export function SessionsPanel({
  sessions,
  currentSessionId,
  onSelectSession,
  onNewSession,
  onDeleteSession
}: SessionsPanelProps) {
  const permanentSessions = sessions.filter(s => s.isPermanent);
  const regularSessions = sessions.filter(s => !s.isPermanent);

  return (
    <div className="sessions-panel">
      <div className="panel-header">
        <span className="panel-title">会话</span>
        <button className="new-session-btn" onClick={() => onNewSession()} title="新建会话">
          <Icon name="add" size={16} />
        </button>
      </div>

      <div className="sessions-list">
        {permanentSessions.length > 0 && (
          <div className="session-group">
            {permanentSessions.map(session => (
              <div
                key={session.id}
                className={`session-item permanent${currentSessionId === session.id ? ' active' : ''}`}
                onClick={() => onSelectSession(session.id)}
              >
                <div className="session-icon">
                  <Icon name="bot" size={16} />
                </div>
                <div className="session-info">
                  <div className="session-title">{session.title}</div>
                </div>
              </div>
            ))}
          </div>
        )}

        {regularSessions.length > 0 && (
          <div className="session-group">
            {regularSessions.map(session => (
              <div
                key={session.id}
                className={`session-item${currentSessionId === session.id ? ' active' : ''}`}
                onClick={() => onSelectSession(session.id)}
              >
                <div className="session-icon">
                  <Icon name="chat" size={16} />
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
                  className="delete-btn"
                  onClick={(e) => {
                    e.stopPropagation();
                    onDeleteSession(session.id);
                  }}
                  title="删除"
                >
                  <Icon name="delete" size={14} />
                </button>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

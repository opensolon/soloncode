import { useMemo } from 'react';
import type { Conversation } from '../types';
import './ConversationList.css';

interface ConversationListProps {
  conversations: Conversation[];
  currentId: string | number;
  onSelect: (conv: Conversation) => void;
}

export function ConversationList({ conversations, currentId, onSelect }: ConversationListProps) {
  const permanentConversations = useMemo(() =>
    conversations.filter(conv => conv.isPermanent),
    [conversations]
  );

  const regularConversations = useMemo(() =>
    conversations.filter(conv => !conv.isPermanent),
    [conversations]
  );

  return (
    <>
      {/* 固定会话按钮 */}
      {permanentConversations.map((conv) => (
        <div
          key={conv.id}
          className={`conversation-item permanent${conv.id === currentId ? ' active' : ''}`}
          onClick={() => onSelect(conv)}
        >
          <div className="conversation-title">
            {conv.icon && <span className="conversation-icon">{conv.icon}</span>}
            {conv.title}
          </div>
        </div>
      ))}

      <div className="section-header">
        <span className="section-title">会话</span>
        <span className="section-count">{regularConversations.length}</span>
      </div>

      <div className="conversation-list">
        {/* 普通会话 */}
        {regularConversations.length > 0 && (
          <div className="conversations-section">
            {regularConversations.map((conv) => (
              <div
                key={conv.id}
                className={`conversation-item${conv.id === currentId ? ' active' : ''}`}
                onClick={() => onSelect(conv)}
              >
                <div className="conversation-title">
                  {conv.icon && <span className="conversation-icon">{conv.icon}</span>}
                  {conv.title}
                </div>
                <div className="conversation-time">{conv.timestamp}</div>
              </div>
            ))}
          </div>
        )}

        {/* 空状态 */}
        {conversations.length === 0 && (
          <div className="empty-conversations">
            <span>暂无会话</span>
          </div>
        )}
      </div>
    </>
  );
}

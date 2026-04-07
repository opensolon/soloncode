import { useState } from 'react';
import type { Conversation, Plugin } from '../types';
import { PluginPanel } from './PluginPanel';
import { ConversationList } from './ConversationList';
import './Sidebar.css';

interface SidebarProps {
  conversations: Conversation[];
  currentConversation: Conversation;
  plugins: Plugin[];
  onNewConversation: () => void;
  onSelectConversation: (conv: Conversation) => void;
  onTogglePlugin: (pluginId: string) => void;
}

export function Sidebar({
  conversations,
  currentConversation,
  plugins,
  onNewConversation,
  onSelectConversation,
  onTogglePlugin
}: SidebarProps) {
  const [showPluginPanel, setShowPluginPanel] = useState(false);

  function handleTogglePluginPanel() {
    setShowPluginPanel(!showPluginPanel);
  }

  return (
    <aside className="sidebar">
      {/* 顶部操作区 */}
      <div className="sidebar-top">
        <button className="action-btn" onClick={onNewConversation}>
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
            <path d="M8 1V15M1 8H15" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          </svg>
          <span>新建任务</span>
        </button>
        <button
          className={`plugin-btn${showPluginPanel ? ' active' : ''}`}
          onClick={handleTogglePluginPanel}
        >
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
            <path d="M8 2L10 6H6L8 2Z" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
            <path d="M2 8L6 10L6 6L2 8Z" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
            <path d="M14 8L10 6L10 10L14 8Z" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
            <path d="M8 14L6 10H10L8 14Z" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <span>插件管理</span>
        </button>
      </div>

      {/* 插件面板 */}
      <PluginPanel
        plugins={plugins}
        show={showPluginPanel}
        onToggle={onTogglePlugin}
      />

      {/* 会话列表 */}
      <ConversationList
        conversations={conversations}
        currentId={currentConversation.id}
        onSelect={onSelectConversation}
      />
    </aside>
  );
}

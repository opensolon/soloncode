import { useState, useEffect } from 'react';
import type { Conversation, Plugin } from '../types';
import { saveConversation, getAllConversations } from '../db';
import { Sidebar } from '../components/Sidebar';
import { ChatView } from '../components/ChatView';
import './ChatPage.css';

const INITIAL_PLUGINS: Plugin[] = [
];

const SOLON_CLAW_CONV: Conversation = {
  id: 'SolonCode',
  title: 'SolonCode',
  timestamp: new Date().toLocaleString(),
  status: 'active',
  isPermanent: true,
  icon: '🦊'
};

export function ChatPage() {
  const [plugins, setPlugins] = useState<Plugin[]>(INITIAL_PLUGINS);
  const [currentConversation, setCurrentConversation] = useState<Conversation>(SOLON_CLAW_CONV);
  const [conversations, setConversations] = useState<Conversation[]>([SOLON_CLAW_CONV]);
  const [isInitialized, setIsInitialized] = useState(false);

  async function newConversation() {
    const newConv: Conversation = {
      id: Date.now(),
      title: '新建对话',
      timestamp: new Date().toLocaleString(),
      status: 'active'
    };
    setConversations(prev => [newConv, ...prev]);
    setCurrentConversation(newConv);
    await saveConversation(newConv);
  }

  function selectConversation(conv: Conversation) {
    setCurrentConversation(conv);
  }

  function togglePlugin(pluginId: string) {
    setPlugins(prev => prev.map(p =>
      p.id === pluginId ? { ...p, enabled: !p.enabled } : p
    ));
  }

  useEffect(() => {
    (async () => {
      if (isInitialized) return;

      const stored = await getAllConversations();

      if (stored.length > 0) {
        setConversations(stored as Conversation[]);
        setCurrentConversation(stored[0] as Conversation);
      }

      setIsInitialized(true);
    })();
  }, [isInitialized]);

  return (
    <div className="app-container">
      <Sidebar
        conversations={conversations}
        currentConversation={currentConversation}
        plugins={plugins}
        onNewConversation={newConversation}
        onSelectConversation={selectConversation}
        onTogglePlugin={togglePlugin}
      />
      <ChatView
        currentConversation={currentConversation}
        plugins={plugins}
      />
    </div>
  );
}

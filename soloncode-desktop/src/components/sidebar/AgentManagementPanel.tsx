import { useState, type ReactNode } from 'react';
import './AgentManagementPanel.css';

interface AgentManagementPanelProps {
  agents: ReactNode;
  skills: ReactNode;
}

export function AgentManagementPanel({ agents, skills }: AgentManagementPanelProps) {
  const [activeTab, setActiveTab] = useState<'agents' | 'skills'>('agents');

  return (
    <div className="agent-management-panel">
      <div className="agent-management-tabs" role="group" aria-label="Agent 管理">
        <button
          type="button"
          className={`agent-management-tab${activeTab === 'agents' ? ' active' : ''}`}
          aria-pressed={activeTab === 'agents'}
          onClick={() => setActiveTab('agents')}
        >
          Agents
        </button>
        <button
          type="button"
          className={`agent-management-tab${activeTab === 'skills' ? ' active' : ''}`}
          aria-pressed={activeTab === 'skills'}
          onClick={() => setActiveTab('skills')}
        >
          Skills
        </button>
      </div>
      <div className="agent-management-content">
        {activeTab === 'agents' ? agents : skills}
      </div>
    </div>
  );
}

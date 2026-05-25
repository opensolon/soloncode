import { useState, useEffect, useCallback, useRef } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { Icon, getFileIconName } from '../common/Icon';
import type { AgentConfig } from '../../services/settingsService';
import '../sidebar/ExplorerPanel.css';
import './AgentsPanel.css';

interface AgentsPanelProps {
  agents: AgentConfig[];
  onAgentsChange: (agents: AgentConfig[]) => void;
  activeAgent: string;
  onAgentChange: (name: string) => void;
  onFileSelect: (path: string) => void;
  onCreateWithAI?: (name: string, description: string) => void;
}

export function AgentsPanel({ agents, onAgentsChange, activeAgent, onAgentChange, onFileSelect, onCreateWithAI }: AgentsPanelProps) {
  const [loading, setLoading] = useState(false);
  const [expandedFolders, setExpandedFolders] = useState<Set<string>>(new Set());
  const [showCreate, setShowCreate] = useState(false);
  const [newName, setNewName] = useState('');
  const [newDesc, setNewDesc] = useState('');
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState('');
  const onAgentsChangeRef = useRef(onAgentsChange);
  onAgentsChangeRef.current = onAgentsChange;

  const loadFromBackend = useCallback(async () => {
    setLoading(true);
    try {
      const backendAgents = await invoke<Array<{
        name: string;
        description: string;
        path: string;
        enabled: boolean;
      }>>('list_agents');

      if (backendAgents.length > 0) {
        const mapped: AgentConfig[] = backendAgents.map(a => ({
          name: a.name,
          description: a.description,
          path: a.path,
          enabled: a.enabled,
          source: 'discovered' as const,
        }));
        onAgentsChangeRef.current(mapped);
      }
    } catch (err) {
      console.warn('[AgentsPanel] 加载后端 agents 失败:', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadFromBackend();
  }, [loadFromBackend]);

  const handleCreate = async () => {
    if (!newName.trim()) return;
    setCreating(true);
    setCreateError('');
    try {
      await invoke('create_agent', { name: newName.trim(), description: newDesc.trim() });
      setShowCreate(false);
      setNewName('');
      setNewDesc('');
      await loadFromBackend();
    } catch (err) {
      setCreateError(String(err));
    } finally {
      setCreating(false);
    }
  };

  const handleToggle = async (agent: AgentConfig, index: number) => {
    try {
      await invoke('toggle_agent', { agentPath: agent.path, enabled: !agent.enabled });
      const updated = agents.map((a, i) =>
        i === index ? { ...a, enabled: !a.enabled } : a
      );
      onAgentsChange(updated);
    } catch (err) {
      console.warn('[AgentsPanel] 切换 agent 失败:', err);
    }
  };

  const toggleFolder = (path: string) => {
    setExpandedFolders(prev => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  };

  const enabledCount = agents.filter(a => a.enabled).length;

  function renderAgentNode(agent: AgentConfig, index: number) {
    const isExpanded = expandedFolders.has(agent.path);
    const isActive = activeAgent === agent.name;
    const agentMdPath = `${agent.path}/AGENT.md`;

    return (
      <div key={agent.path}>
        <div
          className={`file-node folder${isExpanded ? ' expanded' : ''}${isActive ? ' file' : ''}${agent.enabled ? '' : ' disabled-item'}`}
          onClick={() => {
            if (agent.enabled) {
              onAgentChange(agent.name);
              toggleFolder(agent.path);
            }
          }}
        >
          <span className="chevron-icon">
            <Icon name={isExpanded ? 'chevron-down' : 'chevron-right'} size={12} />
          </span>
          <Icon name={isExpanded ? 'folder-open' : 'folder'} size={16} className="file-icon" />
          <span className="file-name">{agent.name}</span>
          <input
            type="checkbox"
            className="tree-checkbox"
            checked={agent.enabled}
            onClick={(e) => e.stopPropagation()}
            onChange={() => handleToggle(agent, index)}
          />
        </div>
        {isExpanded && (
          <div
            className="file-node file"
            style={{ paddingLeft: '24px' }}
            onClick={() => onFileSelect(agentMdPath)}
          >
            <span className="chevron-placeholder" />
            <Icon name={getFileIconName('AGENT.md')} size={16} className="file-icon" />
            <span className="file-name">AGENT.md</span>
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="agents-panel">
      <div className="panel-header">
        <span className="panel-title">Agents</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span className="group-count">{enabledCount}/{agents.length}</span>
          <button className="new-session-btn" onClick={() => setShowCreate(!showCreate)} title="新建 Agent">
            <Icon name="add" size={14} />
          </button>
          <button className="new-session-btn" onClick={loadFromBackend} title="刷新">
            <Icon name="refresh" size={14} />
          </button>
        </div>
      </div>

      {showCreate && (
        <div className="create-form">
          <input className="create-form-input" placeholder="Agent 名称" value={newName} onChange={e => setNewName(e.target.value)} autoFocus onKeyDown={e => { if (e.key === 'Enter') handleCreate(); }} />
          <input className="create-form-input" placeholder="描述（可选）" value={newDesc} onChange={e => setNewDesc(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') handleCreate(); }} />
          <div className="create-form-actions">
            <button className="create-form-btn cancel" onClick={() => { setShowCreate(false); setNewName(''); setNewDesc(''); setCreateError(''); }}>取消</button>
            {onCreateWithAI && <button className="create-form-btn ai-gen" onClick={() => { if (!newName.trim()) return; onCreateWithAI(newName.trim(), newDesc.trim()); setShowCreate(false); setNewName(''); setNewDesc(''); setCreateError(''); }} disabled={!newName.trim()} title="AI 生成"><Icon name="bot" size={12} /> AI 生成</button>}
            <button className="create-form-btn confirm" onClick={handleCreate} disabled={creating || !newName.trim()}>{creating ? '创建中...' : '创建'}</button>
          </div>
          {createError && <p className="create-form-error">{createError}</p>}
        </div>
      )}

      <div className="panel-content agents-list">
        {loading && (
          <div className="empty-state">
            <div className="empty-text">加载中...</div>
          </div>
        )}

        {!loading && agents.length === 0 && (
          <div className="empty-state">
            <div className="empty-icon"><Icon name="agents" size={40} /></div>
            <div className="empty-text">暂无 Agent</div>
            <div className="empty-hint">
              在工作区 <code>.soloncode/agents/</code> 目录下
              <br />创建包含 AGENT.md 的子目录即可自动发现
            </div>
          </div>
        )}

        {!loading && agents.map((agent, index) => renderAgentNode(agent, index))}
      </div>
    </div>
  );
}

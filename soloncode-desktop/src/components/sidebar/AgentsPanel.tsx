import { useState, useEffect, useCallback, useRef } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { Icon, getFileIconName } from '../common/Icon';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { ContextMenu } from '../common/ContextMenu';
import type { AgentConfig } from '../../services/settingsService';
import { getManagedResourceNameError, managedResourceService } from '../../services/managedResourceService';
import '../sidebar/ExplorerPanel.css';
import './AgentsPanel.css';

interface AgentsPanelProps {
  agents: AgentConfig[];
  onAgentsChange: (agents: AgentConfig[]) => void;
  activeAgent: string;
  onAgentChange: (name: string) => void;
  onFileSelect: (path: string) => void;
  onCreateWithAI?: () => void;
  refreshKey?: number;
}

export function AgentsPanel({ agents, onAgentsChange, activeAgent, onAgentChange, onFileSelect, onCreateWithAI, refreshKey = 0 }: AgentsPanelProps) {
  const [loading, setLoading] = useState(false);
  const [expandedFolders, setExpandedFolders] = useState<Set<string>>(new Set());
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; agent: AgentConfig } | null>(null);
  const [renameTarget, setRenameTarget] = useState<{ agent: AgentConfig; value: string } | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<AgentConfig | null>(null);
  const [actionMessage, setActionMessage] = useState<{ text: string; error?: boolean } | null>(null);
  const [actionPending, setActionPending] = useState(false);
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

      const mapped: AgentConfig[] = backendAgents.map(a => ({
        name: a.name,
        description: a.description,
        path: a.path,
        enabled: a.enabled,
        source: 'discovered' as const,
      }));
      onAgentsChangeRef.current(mapped);
    } catch (err) {
      console.warn('[AgentsPanel] 加载后端 agents 失败:', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadFromBackend();
  }, [loadFromBackend, refreshKey]);

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
  const renameError = renameTarget ? getManagedResourceNameError(renameTarget.value) : '';

  const handleContextAction = useCallback((action: string) => {
    const target = contextMenu?.agent;
    setContextMenu(null);
    if (!target?.path) return;
    setActionMessage(null);
    if (action === 'rename') {
      setRenameTarget({ agent: target, value: target.name });
    } else if (action === 'copy') {
      setActionPending(true);
      void managedResourceService.copy(target.path, 'agent')
        .then(result => {
          setActionMessage({ text: `已复制为 ${result.name}` });
          return loadFromBackend();
        })
        .catch(error => {
          console.error('[AgentsPanel] 复制 Agent 失败:', error);
          setActionMessage({ text: '复制 Agent 失败', error: true });
        })
        .finally(() => setActionPending(false));
    } else if (action === 'delete') {
      setDeleteTarget(target);
    }
  }, [contextMenu, loadFromBackend]);

  const confirmRename = useCallback(async () => {
    if (!renameTarget?.agent.path || getManagedResourceNameError(renameTarget.value) || actionPending) return;
    setActionPending(true);
    try {
      const wasActive = activeAgent === renameTarget.agent.name;
      const result = await managedResourceService.rename(renameTarget.agent.path, 'agent', renameTarget.value);
      setRenameTarget(null);
      if (wasActive) onAgentChange(result.name);
      setActionMessage({ text: `已重命名为 ${result.name}` });
      await loadFromBackend();
    } catch (error) {
      console.error('[AgentsPanel] 重命名 Agent 失败:', error);
      setActionMessage({ text: '重命名 Agent 失败', error: true });
    } finally {
      setActionPending(false);
    }
  }, [actionPending, activeAgent, loadFromBackend, onAgentChange, renameTarget]);

  const confirmDelete = useCallback(async () => {
    if (!deleteTarget?.path || actionPending) return;
    setActionPending(true);
    try {
      await managedResourceService.delete(deleteTarget.path, 'agent');
      if (activeAgent === deleteTarget.name) onAgentChange('default');
      setDeleteTarget(null);
      setActionMessage({ text: `已删除 ${deleteTarget.name}` });
      await loadFromBackend();
    } catch (error) {
      console.error('[AgentsPanel] 删除 Agent 失败:', error);
      setActionMessage({ text: '删除 Agent 失败', error: true });
    } finally {
      setActionPending(false);
    }
  }, [actionPending, activeAgent, deleteTarget, loadFromBackend, onAgentChange]);

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
          onContextMenu={event => {
            event.preventDefault();
            event.stopPropagation();
            setContextMenu({ x: event.clientX, y: event.clientY, agent });
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
          <button className="new-session-btn" onClick={onCreateWithAI} title="根据提示词创建 Agent">
            <Icon name="add" size={14} />
          </button>
          <button className="new-session-btn" onClick={loadFromBackend} title="刷新">
            <Icon name="refresh" size={14} />
          </button>
        </div>
      </div>

      <div className="panel-content agents-list">
        {actionMessage && <div className={`resource-action-message${actionMessage.error ? ' error' : ''}`}>{actionMessage.text}</div>}
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
      {contextMenu && (
        <ContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          items={[
            { id: 'rename', label: '重命名', disabled: actionPending },
            { id: 'copy', label: '复制', disabled: actionPending },
            { id: 'delete', label: '删除', danger: true, disabled: actionPending },
          ]}
          onItemClick={handleContextAction}
          onClose={() => setContextMenu(null)}
        />
      )}
      {renameTarget && (
        <ConfirmDialog
          title="重命名 Agent"
          message="名称会同步写入 AGENT.md。"
          inputLabel="Agent 名称"
          inputValue={renameTarget.value}
          inputError={renameError}
          confirmLabel={actionPending ? '处理中' : '重命名'}
          confirmDisabled={Boolean(renameError) || actionPending}
          onInputChange={value => setRenameTarget(current => current ? { ...current, value } : null)}
          onConfirm={() => { void confirmRename(); }}
          onCancel={() => { if (!actionPending) setRenameTarget(null); }}
        />
      )}
      {deleteTarget && (
        <ConfirmDialog
          title="删除 Agent"
          message={`将删除「${deleteTarget.name}」及其目录中的全部文件，此操作无法撤销。`}
          confirmLabel={actionPending ? '处理中' : '删除'}
          confirmDisabled={actionPending}
          danger
          onConfirm={() => { void confirmDelete(); }}
          onCancel={() => { if (!actionPending) setDeleteTarget(null); }}
        />
      )}
    </div>
  );
}

import { useState, useEffect, useCallback, useRef } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { revealItemInDir } from '@tauri-apps/plugin-opener';
import { Icon } from '../common/Icon';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { ContextMenu } from '../common/ContextMenu';
import { DropdownMenu } from '../common/DropdownMenu';
import type { AgentConfig } from '../../services/settingsService';
import { settingsService } from '../../services/settingsService';
import { getManagedResourceNameError, managedResourceService } from '../../services/managedResourceService';
import './AgentsPanel.css';

type AgentScope = 'system' | 'project';

interface AgentsPanelProps {
  agents: AgentConfig[];
  projectAgents: AgentConfig[];
  projectPath?: string | null;
  projectName?: string;
  onAgentsChange: (agents: AgentConfig[]) => void;
  onProjectAgentsChange: (agents: AgentConfig[]) => void;
  onOpenSettings: (agent: AgentConfig) => void;
  onAgentDeleted?: (agent: AgentConfig) => void;
  onRuntimeRefresh?: () => void;
  onCreateWithAI?: () => void;
  onCreateManually?: () => void;
  refreshKey?: number;
}

function withScope(agent: AgentConfig, scope: AgentScope, projectPath?: string | null): AgentConfig {
  return {
    ...agent,
    scope,
    projectPath: scope === 'project' ? projectPath || undefined : undefined,
  };
}

export function AgentsPanel({
  agents,
  projectAgents,
  projectPath,
  projectName,
  onAgentsChange,
  onProjectAgentsChange,
  onOpenSettings,
  onAgentDeleted,
  onRuntimeRefresh,
  onCreateWithAI,
  onCreateManually,
  refreshKey = 0,
}: AgentsPanelProps) {
  const [loading, setLoading] = useState(false);
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; agent: AgentConfig } | null>(null);
  const [renameTarget, setRenameTarget] = useState<AgentConfig | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [deleteTarget, setDeleteTarget] = useState<AgentConfig | null>(null);
  const [actionMessage, setActionMessage] = useState<{ text: string; error?: boolean } | null>(null);
  const [actionPending, setActionPending] = useState(false);
  const onAgentsChangeRef = useRef(onAgentsChange);
  const onProjectAgentsChangeRef = useRef(onProjectAgentsChange);
  onAgentsChangeRef.current = onAgentsChange;
  onProjectAgentsChangeRef.current = onProjectAgentsChange;

  const systemAgents = agents
    .filter(agent => agent.scope !== 'project')
    .map(agent => withScope(agent, 'system'));
  const scopedProjectAgents = projectAgents.map(agent => withScope(agent, 'project', projectPath));

  const loadFromBackend = useCallback(async () => {
    setLoading(true);
    try {
      const [backendAgents, discoveredProjectAgents] = await Promise.all([
        invoke<Array<{ name: string; description: string; path: string; enabled: boolean }>>('list_agents'),
        projectPath ? settingsService.scanAgentsDir(projectPath) : Promise.resolve([]),
      ]);
      onAgentsChangeRef.current(backendAgents.map(agent => withScope({ ...agent, source: 'discovered' }, 'system')));
      onProjectAgentsChangeRef.current(discoveredProjectAgents.map(agent => withScope(agent, 'project', projectPath)));
    } catch (error) {
      console.warn('[AgentsPanel] 加载 Agent 列表失败:', error);
      onProjectAgentsChangeRef.current([]);
    } finally {
      setLoading(false);
    }
  }, [projectPath]);

  useEffect(() => {
    void loadFromBackend();
  }, [loadFromBackend, refreshKey]);

  const updateScopeAgents = useCallback((agent: AgentConfig, updater: (items: AgentConfig[]) => AgentConfig[]) => {
    if (agent.scope === 'project') onProjectAgentsChangeRef.current(updater(scopedProjectAgents));
    else onAgentsChangeRef.current(updater(systemAgents));
  }, [scopedProjectAgents, systemAgents]);

  const handleToggle = useCallback(async (agent: AgentConfig) => {
    try {
      await invoke('toggle_agent', {
        agentPath: agent.path,
        agentScope: agent.scope || 'system',
        projectPath: agent.scope === 'project' ? agent.projectPath : undefined,
        enabled: !agent.enabled,
      });
      updateScopeAgents(agent, items => items.map(item => item.path === agent.path ? { ...item, enabled: !item.enabled } : item));
      onRuntimeRefresh?.();
    } catch (error) {
      console.warn('[AgentsPanel] 切换 Agent 失败:', error);
      setActionMessage({ text: '切换 Agent 状态失败', error: true });
    }
  }, [onRuntimeRefresh, updateScopeAgents]);

  const handleContextAction = useCallback((action: string) => {
    const target = contextMenu?.agent;
    setContextMenu(null);
    if (!target?.path) return;
    setActionMessage(null);
    if (action === 'open-in-explorer') {
      void revealItemInDir(target.path).catch(error => {
        console.error('[AgentsPanel] 在资源管理器中打开 Agent 失败:', error);
        setActionMessage({ text: '无法在资源管理器中打开 Agent', error: true });
      });
    } else if (action === 'rename') {
      setRenameTarget(target);
      setRenameValue(target.name);
    } else if (action === 'copy') {
      setActionPending(true);
      void managedResourceService.copy(target.path, 'agent')
        .then(result => {
          setActionMessage({ text: `已复制为 ${result.name}` });
          return loadFromBackend().then(() => onRuntimeRefresh?.());
        })
        .catch(error => {
          console.error('[AgentsPanel] 复制 Agent 失败:', error);
          setActionMessage({ text: '复制 Agent 失败', error: true });
        })
        .finally(() => setActionPending(false));
    } else if (action === 'delete') {
      setDeleteTarget(target);
    }
  }, [contextMenu, loadFromBackend, onRuntimeRefresh]);

  const confirmRename = useCallback(async () => {
    if (!renameTarget?.path || getManagedResourceNameError(renameValue) || actionPending) return;
    setActionPending(true);
    try {
      const result = await managedResourceService.rename(renameTarget.path, 'agent', renameValue);
      setRenameTarget(null);
      setActionMessage({ text: `已重命名为 ${result.name}` });
      await loadFromBackend();
      onRuntimeRefresh?.();
    } catch (error) {
      console.error('[AgentsPanel] 重命名 Agent 失败:', error);
      setActionMessage({ text: '重命名 Agent 失败', error: true });
    } finally {
      setActionPending(false);
    }
  }, [actionPending, loadFromBackend, onRuntimeRefresh, renameTarget, renameValue]);

  const confirmDelete = useCallback(async () => {
    if (!deleteTarget?.path || actionPending) return;
    setActionPending(true);
    try {
      await managedResourceService.delete(deleteTarget.path, 'agent');
      onAgentDeleted?.(deleteTarget);
      setDeleteTarget(null);
      setActionMessage({ text: `已删除 ${deleteTarget.name}` });
      await loadFromBackend();
      onRuntimeRefresh?.();
    } catch (error) {
      console.error('[AgentsPanel] 删除 Agent 失败:', error);
      setActionMessage({ text: '删除 Agent 失败', error: true });
    } finally {
      setActionPending(false);
    }
  }, [actionPending, deleteTarget, loadFromBackend, onAgentDeleted, onRuntimeRefresh]);

  const renderAgent = (agent: AgentConfig) => (
    <div
      key={`${agent.scope}:${agent.path}`}
      className={`agent-config-row${agent.enabled ? '' : ' disabled-item'}`}
      role="button"
      tabIndex={0}
      onClick={() => onOpenSettings(agent)}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onOpenSettings(agent);
        }
      }}
      onContextMenu={event => {
        event.preventDefault();
        setContextMenu({ x: event.clientX, y: event.clientY, agent });
      }}
    >
      <Icon name="agents" size={16} className="agent-config-icon" />
      <div className="agent-config-text">
        <span className="agent-config-name">{agent.name}</span>
        {agent.description && <span className="agent-config-description">{agent.description}</span>}
      </div>
      <input
        type="checkbox"
        className="tree-checkbox"
        checked={agent.enabled}
        aria-label={`${agent.name} ${agent.enabled ? '已启用' : '已停用'}`}
        onClick={event => event.stopPropagation()}
        onChange={() => { void handleToggle(agent); }}
      />
    </div>
  );

  const renderScope = (scope: AgentScope, title: string, items: AgentConfig[], emptyText: string) => (
    <section className="agent-scope" key={scope}>
      <div className="agent-scope-heading">
        <span>{title}</span>
        <span>{items.length}</span>
      </div>
      {items.length > 0 ? items.map(renderAgent) : <div className="agent-scope-empty">{emptyText}</div>}
    </section>
  );

  const enabledCount = [...systemAgents, ...scopedProjectAgents].filter(agent => agent.enabled).length;
  const totalCount = systemAgents.length + scopedProjectAgents.length;
  const renameError = renameTarget ? getManagedResourceNameError(renameValue) : '';

  return (
    <div className="agents-panel">
      <div className="panel-header">
        <span className="panel-title">Agents</span>
        <div className="agents-panel-actions">
          <span className="group-count">{enabledCount}/{totalCount}</span>
          <DropdownMenu
            align="right"
            trigger={<button type="button" className="new-session-btn" title="新建 Agent" aria-label="新建 Agent" aria-haspopup="menu"><Icon name="add" size={14} /></button>}
            items={[
              { id: 'manual', label: '手动配置', disabled: !onCreateManually },
              { id: 'llm', label: 'LLM 对话创建', disabled: !onCreateWithAI },
            ]}
            onItemClick={id => { if (id === 'manual') onCreateManually?.(); else if (id === 'llm') onCreateWithAI?.(); }}
          />
          <button className="new-session-btn" onClick={() => { void loadFromBackend(); }} title="刷新"><Icon name="refresh" size={14} /></button>
        </div>
      </div>

      <div className="panel-content agents-list">
        {actionMessage && <div className={`resource-action-message${actionMessage.error ? ' error' : ''}`}>{actionMessage.text}</div>}
        {loading ? (
          <div className="agent-panel-loading">加载中...</div>
        ) : (
          <>
            {renderScope('system', '系统级别', systemAgents, '暂无系统级 Agent')}
            {scopedProjectAgents.length > 0 && renderScope(
              'project',
              projectName ? `项目级别 · ${projectName}` : '项目级别',
              scopedProjectAgents,
              '',
            )}
          </>
        )}
      </div>

      {contextMenu && (
        <ContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          items={[
            { id: 'open-in-explorer', label: '在资源管理器中打开', disabled: actionPending },
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
          message="名称会同步写入 Agent 配置。"
          inputLabel="Agent 名称"
          inputValue={renameValue}
          inputError={renameError}
          confirmLabel={actionPending ? '处理中' : '重命名'}
          confirmDisabled={Boolean(renameError) || actionPending}
          onInputChange={setRenameValue}
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

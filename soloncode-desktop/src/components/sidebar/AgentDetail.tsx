import { useCallback, useEffect, useMemo, useState } from 'react';
import { Icon } from '../common/Icon';
import type { AgentConfig } from '../../services/settingsService';
import { fileService } from '../../services/fileService';
import { getManagedResourceNameError } from '../../services/managedResourceService';
import { agentConfigFilePath } from '../../utils/agentPath';
import { AGENT_TOOL_OPTIONS, parseAgentContent } from '../../utils/agentConfig';
import { agentService } from '../../services/agentService';
import type { AgentDraft, AgentMcpServer } from '../../types/agent';
import './AgentDetail.css';

interface AgentDetailProps {
  agent?: AgentConfig;
  backendPort?: number | null;
  onSaved: (agent: AgentConfig) => void;
  onClose: () => void;
}

function draftChanged(initial: AgentDraft, draft: AgentDraft) {
  return initial.name !== draft.name
    || initial.description !== draft.description
    || initial.prompt !== draft.prompt
    || initial.tools.join(',') !== draft.tools.join(',')
    || JSON.stringify(initial.mcpServers) !== JSON.stringify(draft.mcpServers);
}

export function AgentDetail({ agent, backendPort, onSaved, onClose }: AgentDetailProps) {
  const [initialDraft, setInitialDraft] = useState<AgentDraft | null>(null);
  const [draft, setDraft] = useState<AgentDraft | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [mcpServers, setMcpServers] = useState<AgentMcpServer[]>([]);
  const [mcpLoading, setMcpLoading] = useState(false);
  const [mcpError, setMcpError] = useState('');
  const [mcpRefreshKey, setMcpRefreshKey] = useState(0);
  const creating = !agent;

  useEffect(() => {
    let cancelled = false;
    setMcpServers([]);
    setMcpError('');
    if (!backendPort) {
      setMcpLoading(false);
      return;
    }
    setMcpLoading(true);
    agentService.listMcpServers(backendPort)
      .then(servers => { if (!cancelled) setMcpServers(servers); })
      .catch(() => { if (!cancelled) setMcpError('无法加载 MCP 服务，请检查后端连接后重试'); })
      .finally(() => { if (!cancelled) setMcpLoading(false); });
    return () => { cancelled = true; };
  }, [backendPort, mcpRefreshKey]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    if (!agent) {
      const empty: AgentDraft = { name: '', description: '', prompt: '', tools: [], mcpServers: [] };
      setInitialDraft(empty);
      setDraft(empty);
      setLoading(false);
      return;
    }
    fileService.readFile(agentConfigFilePath(agent.path))
      .then(content => {
        if (cancelled) return;
        const parsed = parseAgentContent(content, agent);
        setInitialDraft(parsed);
        setDraft(parsed);
      })
      .catch(error => {
        console.error('[AgentDetail] 读取 Agent 配置失败:', error);
        if (!cancelled) setError('无法读取 Agent 配置，请稍后重试');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [agent]);

  const validationError = useMemo(() => {
    if (!draft) return '';
    const nameError = getManagedResourceNameError(draft.name);
    if (nameError) return nameError;
    if (!draft.description.trim()) return 'Agent 简介不能为空';
    if (Array.from(draft.description.trim()).length > 240) return 'Agent 简介不能超过 240 个字符';
    if (!draft.prompt.trim()) return '提示词不能为空';
    if (draft.prompt.length > 20_000) return '提示词不能超过 20,000 个字符';
    return '';
  }, [draft]);

  const dirty = Boolean(initialDraft && draft && draftChanged(initialDraft, draft));

  const toggleTool = useCallback((toolId: string) => {
    setDraft(current => {
      if (!current) return current;
      const selected = new Set(current.tools);
      if (selected.has(toolId)) selected.delete(toolId);
      else selected.add(toolId);
      return {
        ...current,
        tools: [...selected],
      };
    });
  }, []);

  const handleSave = useCallback(async () => {
    if (!draft || !dirty || validationError || saving) return;
    setSaving(true);
    setError('');
    try {
      const saved = agent ? await agentService.update(agent, draft) : await agentService.create(draft);
      const nextDraft = { ...draft, name: saved.name, description: saved.description };
      setInitialDraft(nextDraft);
      setDraft(nextDraft);
      onSaved(saved);
    } catch (error) {
      console.error('[AgentDetail] 保存 Agent 配置失败:', error);
      setError(typeof error === 'string' ? error : '保存失败，请检查名称是否重复及配置是否有效');
    } finally {
      setSaving(false);
    }
  }, [agent, dirty, draft, onSaved, saving, validationError]);

  return (
    <div className="agent-detail">
      <div className="agent-detail-header">
        <div className="agent-detail-heading"><Icon name="agents" size={16} /><span>{creating ? '手动创建 Agent' : 'Agent 设置'}</span></div>
        <button type="button" className="agent-detail-close" title="关闭设置" disabled={saving} onClick={onClose}><Icon name="close" size={14} /></button>
      </div>

      {loading || !draft ? (
        <div className="agent-detail-loading">{error || '加载 Agent 配置...'}</div>
      ) : (
        <>
          <div className="agent-detail-scroll">
            <div className="agent-detail-hero">
              <div className="agent-detail-name-field">
                <label htmlFor="agent-detail-name">Agent 名称</label>
                <input
                  id="agent-detail-name"
                  value={draft.name}
                  maxLength={64}
                  disabled={saving}
                  placeholder="例如 code-reviewer"
                  onChange={event => setDraft(current => current ? { ...current, name: event.target.value } : current)}
                />
                <p>{creating ? '填写角色、提示词和可用能力，创建后可在对话中选择此 Agent。' : '保存名称时将同步更新 Agent 目录和 AGENT.md。'}</p>
              </div>
              <span className={`agent-detail-status${agent && !agent.enabled ? ' disabled' : ''}`}>{creating ? '系统级 Agent' : agent.enabled ? '已启用' : '已停用'}</span>
            </div>

            <section className="agent-detail-section">
              <h3>简介</h3>
              <textarea
                className="agent-detail-description"
                value={draft.description}
                maxLength={240}
                rows={2}
                disabled={saving}
                placeholder="简要说明 Agent 的职责和适用场景"
                onChange={event => setDraft(current => current ? { ...current, description: event.target.value } : current)}
              />
            </section>

            <section className="agent-detail-section">
              <h3>提示词</h3>
              <textarea
                className="agent-detail-prompt"
                value={draft.prompt}
                maxLength={20_000}
                rows={10}
                disabled={saving}
                placeholder="定义 Agent 的角色、行为准则和工作流程"
                onChange={event => setDraft(current => current ? { ...current, prompt: event.target.value } : current)}
              />
            </section>

            <section className="agent-detail-section">
              <div className="agent-tools-heading">
                <div><h3>可用工具</h3><p>只授予此 Agent 完成任务所需的工具权限。</p></div>
                <span>{draft.tools.filter(tool => tool !== 'mcp').length} 个已选择</span>
              </div>
              <div className="agent-tools-grid">
                {AGENT_TOOL_OPTIONS.map(tool => {
                  const selected = draft.tools.includes(tool.id);
                  return (
                    <button
                      key={tool.id}
                      type="button"
                      role="checkbox"
                      aria-checked={selected}
                      disabled={saving}
                      className={`agent-tool-option${selected ? ' selected' : ''}`}
                      onClick={() => toggleTool(tool.id)}
                    >
                      <span className="agent-tool-option-check"><Icon name={selected ? 'check' : 'add'} size={12} /></span>
                      <span><strong>{tool.label}</strong><small>{tool.description}</small></span>
                    </button>
                  );
                })}
              </div>
            </section>

            <section className="agent-detail-section">
              <div className="agent-tools-heading">
                <div><h3>MCP 服务</h3><p>选择设置中已有的服务；新增服务请前往设置中的 MCP 服务器。</p></div>
                <button type="button" className="agent-mcp-refresh" disabled={saving || mcpLoading || !backendPort} onClick={() => setMcpRefreshKey(key => key + 1)}>刷新</button>
              </div>
              {draft.mcpServers === null && draft.tools.includes('mcp') && (
                <label className="agent-mcp-all"><input type="checkbox" checked disabled={saving} onChange={() => setDraft(current => current ? { ...current, mcpServers: [], tools: current.tools.filter(tool => tool !== 'mcp') } : current)} />允许全部 MCP 服务（已有配置）</label>
              )}
              {!backendPort && <p className="agent-mcp-hint">等待后端连接，可先填写其他配置。</p>}
              {mcpLoading && <p className="agent-mcp-hint">加载 MCP 服务...</p>}
              {mcpError && <p className="agent-detail-save-error" role="alert">{mcpError}</p>}
              {backendPort && !mcpLoading && !mcpError && mcpServers.length === 0 && <p className="agent-mcp-hint">暂无 MCP 服务，请先在设置中添加。</p>}
              <div className="agent-tools-grid">
                {mcpServers.map(server => {
                  const selected = draft.mcpServers === null ? draft.tools.includes('mcp') : draft.mcpServers.includes(server.name);
                  return <button key={server.name} type="button" role="checkbox" aria-checked={selected}
                    disabled={saving || (!server.enabled && !selected)}
                    className={`agent-tool-option${selected ? ' selected' : ''}`}
                    onClick={() => setDraft(current => {
                      if (!current) return current;
                      const selectedNames = current.mcpServers ?? (current.tools.includes('mcp') ? mcpServers.filter(item => item.enabled).map(item => item.name) : []);
                      const next = selected ? selectedNames.filter(name => name !== server.name) : [...selectedNames, server.name];
                      return { ...current, mcpServers: next };
                    })}>
                    <span className="agent-tool-option-check"><Icon name={selected ? 'check' : 'add'} size={12} /></span>
                    <span><strong>{server.name}</strong><small>{server.enabled ? (server.type || 'MCP 服务') : '已在设置中停用'}</small></span>
                  </button>;
                })}
              </div>
              {draft.mcpServers?.filter(name => !mcpServers.some(server => server.name === name)).map(name => (
                <div className="agent-mcp-missing" key={name}><span>{name} · 当前不可用</span><button type="button" disabled={saving} onClick={() => setDraft(current => current ? { ...current, mcpServers: current.mcpServers?.filter(item => item !== name) ?? [] } : current)}>移除</button></div>
              ))}
            </section>
          </div>

          <div className="agent-detail-actions">
            {(error || (dirty && validationError)) && <span className="agent-detail-save-error">{error || validationError}</span>}
            {creating && <button type="button" className="agent-detail-cancel" disabled={saving} onClick={onClose}>取消</button>}
            <button type="button" className="agent-detail-save" disabled={!dirty || Boolean(validationError) || saving} onClick={() => { void handleSave(); }}>
              <Icon name={saving ? 'loading' : 'save'} size={13} />{saving ? (creating ? '创建中' : '保存中') : creating ? '创建 Agent' : '保存更改'}
            </button>
          </div>
        </>
      )}
    </div>
  );
}

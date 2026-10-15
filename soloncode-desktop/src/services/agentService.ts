import { invoke } from '@tauri-apps/api/core';
import type { AgentConfig } from './settingsService';
import type { AgentDraft, AgentMcpServer } from '../types/agent';
import { agentDraftTools, serializeAgentDraft } from '../utils/agentConfig';

export const agentService = {
  async create(draft: AgentDraft): Promise<AgentConfig> {
    const name = draft.name.trim();
    const description = draft.description.trim();
    const path = await invoke<string>('create_agent', { name, description, content: serializeAgentDraft(draft) });
    return { name, description, path, enabled: true, source: 'manual', scope: 'system' };
  },

  async update(agent: AgentConfig, draft: AgentDraft): Promise<AgentConfig> {
    const saved = await invoke<Pick<AgentConfig, 'name' | 'description' | 'path' | 'enabled'>>('update_agent_config', {
      agentPath: agent.path,
      agentScope: agent.scope || 'system',
      projectPath: agent.scope === 'project' ? agent.projectPath : undefined,
      name: draft.name.trim(),
      description: draft.description.trim(),
      prompt: draft.prompt.trim(),
      tools: agentDraftTools(draft),
      mcpServers: draft.mcpServers,
    });
    return { ...agent, ...saved };
  },

  async listMcpServers(port: number): Promise<AgentMcpServer[]> {
    const response = await fetch(`http://localhost:${port}/web/settings/mcp/servers`);
    if (!response.ok) throw new Error('无法加载 MCP 服务');
    const result = await response.json();
    if (result.code !== undefined && result.code !== 200) throw new Error(result.description || '无法加载 MCP 服务');
    const servers = result.data ?? result;
    if (!Array.isArray(servers)) throw new Error('MCP 服务列表格式无效');
    return servers.map(server => ({ name: String(server.name), enabled: server.enabled !== false, type: server.type }));
  },
};

import type { AgentDraft } from '../types/agent';

export const AGENT_TOOL_OPTIONS = [
  { id: 'read', label: '读取文件', description: '查看文件内容' },
  { id: 'write', label: '写入文件', description: '创建或覆盖文件' },
  { id: 'edit', label: '编辑文件', description: '修改已有文件' },
  { id: 'bash', label: 'Bash', description: '执行终端命令' },
  { id: 'codesearch', label: '代码搜索', description: '搜索工作区代码' },
  { id: 'websearch', label: '网络搜索', description: '检索公开网页' },
  { id: 'webfetch', label: '网页访问', description: '读取指定网页' },
  { id: 'lsp', label: '代码分析', description: '使用语言服务' },
  { id: 'restapi', label: 'Web API', description: '调用已配置的 API 工具' },
] as const;

function parseScalar(value: string): string {
  const trimmed = value.trim();
  if (trimmed.startsWith('"')) {
    try { return String(JSON.parse(trimmed)); } catch { /* 支持手写 YAML。 */ }
  }
  if (trimmed.startsWith("'")) return trimmed.slice(1, -1).replace(/''/g, "'");
  return trimmed;
}

function parseList(value: string): string[] {
  if (!value.trim()) return [];
  try {
    const parsed: unknown = JSON.parse(value);
    if (Array.isArray(parsed)) return parsed.filter((item): item is string => typeof item === 'string');
  } catch { /* 支持未加引号的 YAML 内联数组。 */ }
  return value.replace(/^\[|\]$/g, '').split(',').map(parseScalar).filter(Boolean);
}

export function parseAgentContent(content: string, agent: { name: string; description: string }): AgentDraft {
  const lines = content.replace(/\r\n/g, '\n').split('\n');
  const draft: AgentDraft = { ...agent, prompt: '', tools: [], mcpServers: null };
  let bodyStart = 0;
  if (lines[0]?.trim() === '---') {
    const end = lines.findIndex((line, index) => index > 0 && line.trim() === '---');
    if (end > 0) {
      for (let index = 1; index < end; index += 1) {
        const match = lines[index].match(/^([A-Za-z][\w-]*):\s*(.*)$/);
        if (!match) continue;
        const [, key, value] = match;
        if (key === 'name') draft.name = parseScalar(value) || draft.name;
        if (key === 'description') draft.description = parseScalar(value);
        if (key === 'tools' || key === 'allowed-tools' || key === 'mcpServers') {
          const items = parseList(value);
          if (!value.trim()) {
            while (index + 1 < end && /^\s+-\s+/.test(lines[index + 1])) {
              index += 1;
              items.push(parseScalar(lines[index].replace(/^\s+-\s+/, '')));
            }
          }
          if (key === 'mcpServers') draft.mcpServers = [...new Set(items)];
          else draft.tools = [...new Set(items)];
        }
      }
      bodyStart = end + 1;
    }
  }
  draft.prompt = lines.slice(bodyStart).join('\n').trim();
  return draft;
}

export function agentDraftTools(draft: AgentDraft): string[] {
  const tools = draft.tools.filter(tool => tool !== 'mcp');
  if (draft.mcpServers === null ? draft.tools.includes('mcp') : draft.mcpServers.length > 0) tools.push('mcp');
  return [...new Set(tools)];
}

export function serializeAgentDraft(draft: AgentDraft): string {
  const lines = [
    '---',
    `name: ${JSON.stringify(draft.name.trim())}`,
    `description: ${JSON.stringify(draft.description.trim())}`,
    `tools: ${JSON.stringify(agentDraftTools(draft))}`,
  ];
  if (draft.mcpServers !== null) lines.push(`mcpServers: ${JSON.stringify(draft.mcpServers)}`);
  return [...lines, '---', '', draft.prompt.trim(), ''].join('\n');
}

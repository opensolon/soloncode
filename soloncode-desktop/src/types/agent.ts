export interface AgentDraft {
  name: string;
  description: string;
  prompt: string;
  tools: string[];
  /** null 保留旧 Agent 的全部 MCP 服务权限；数组表示明确选择的服务。 */
  mcpServers: string[] | null;
}

export interface AgentMcpServer {
  name: string;
  enabled: boolean;
  type?: string;
}

export interface McpTool {
  name: string;
  description?: string;
}

export interface McpToolsResult {
  tools: McpTool[];
  disallowedTools: string[];
}

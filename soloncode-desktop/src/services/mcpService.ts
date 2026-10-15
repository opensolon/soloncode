import type { McpToolsResult } from '../types/mcp';

async function parseResponse(response: Response) {
  if (!response.ok) throw new Error(`MCP 请求失败（${response.status}）`);
  const payload = await response.json();
  if (payload.code !== undefined && payload.code !== 200) {
    throw new Error(payload.description || payload.message || 'MCP 操作失败');
  }
  return payload.data ?? payload;
}

export async function fetchMcpTools(
  port: number,
  serverName: string,
  signal?: AbortSignal,
  fetcher: typeof fetch = fetch,
): Promise<McpToolsResult> {
  const data = await parseResponse(await fetcher(
    `http://localhost:${port}/web/settings/mcp/servers/tools?name=${encodeURIComponent(serverName)}`,
    { cache: 'no-store', signal },
  ));
  if (!Array.isArray(data.tools) || data.tools.some((tool: unknown) =>
    !tool || typeof tool !== 'object' || !('name' in tool) || typeof tool.name !== 'string')) {
    throw new Error('工具列表格式无效');
  }
  return {
    tools: data.tools.map((tool: { name: string; description?: unknown }) => ({
      name: tool.name,
      description: typeof tool.description === 'string' ? tool.description : undefined,
    })),
    disallowedTools: Array.isArray(data.disallowedTools)
      ? data.disallowedTools.filter((name: unknown): name is string => typeof name === 'string') : [],
  };
}

export async function saveMcpToolPermissions(
  port: number,
  serverName: string,
  disallowedTools: Iterable<string>,
  signal?: AbortSignal,
  fetcher: typeof fetch = fetch,
): Promise<void> {
  const body = new URLSearchParams({ serverName });
  for (const name of disallowedTools) body.append('disallowedTools', name);
  await parseResponse(await fetcher(`http://localhost:${port}/web/settings/mcp/servers/tools/save`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    body,
    signal,
  }));
}

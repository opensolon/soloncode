import assert from 'node:assert/strict';
import test from 'node:test';
import { fetchMcpTools, saveMcpToolPermissions } from '../src/services/mcpService.ts';

test('fetches tools for the selected server with its saved permissions', async () => {
  const controller = new AbortController();
  const fetcher: typeof fetch = async (url, init) => {
    const request = new URL(String(url));
    assert.equal(request.port, '4900');
    assert.equal(request.pathname, '/web/settings/mcp/servers/tools');
    assert.equal(request.searchParams.get('name'), '文件 & memory');
    assert.equal(init?.cache, 'no-store');
    assert.equal(init?.signal, controller.signal);
    return Response.json({ code: 200, data: {
      tools: [{ name: 'read', description: 'Read files' }, { name: 'write' }],
      disallowedTools: ['write'],
    } });
  };
  const result = await fetchMcpTools(4900, '文件 & memory', controller.signal, fetcher);
  assert.deepEqual(result.tools.map(tool => tool.name), ['read', 'write']);
  assert.deepEqual(result.disallowedTools, ['write']);
});

test('saves multiple disabled tools as repeated form fields and can clear them', async () => {
  for (const disabled of [['write', 'remove'], []]) {
    const fetcher: typeof fetch = async (url, init) => {
      assert.equal(new URL(String(url)).pathname, '/web/settings/mcp/servers/tools/save');
      assert.equal(init?.method, 'POST');
      const body = new URLSearchParams(String(init?.body));
      assert.equal(body.get('serverName'), 'memory');
      assert.deepEqual(body.getAll('disallowedTools'), disabled);
      return Response.json({ code: 200 });
    };
    await saveMcpToolPermissions(4808, 'memory', disabled, undefined, fetcher);
  }
});

test('reports backend failures for both fetching and saving instead of accepting HTTP 200', async () => {
  const fetcher: typeof fetch = async () => Response.json({ code: 400, description: 'Server not found' });
  await assert.rejects(fetchMcpTools(4808, 'missing', undefined, fetcher), /Server not found/);
  await assert.rejects(saveMcpToolPermissions(4808, 'missing', [], undefined, fetcher), /Server not found/);
});

test('accepts an empty tool list but rejects malformed lists and HTTP errors', async () => {
  const empty: typeof fetch = async () => Response.json({ code: 200, data: { tools: [], disallowedTools: null } });
  assert.deepEqual(await fetchMcpTools(4808, 'empty', undefined, empty), { tools: [], disallowedTools: [] });
  const invalid: typeof fetch = async () => Response.json({ code: 200, data: { tools: [{ description: 'No name' }] } });
  await assert.rejects(fetchMcpTools(4808, 'invalid', undefined, invalid), /工具列表格式无效/);
  const unavailable: typeof fetch = async () => new Response(null, { status: 503 });
  await assert.rejects(fetchMcpTools(4808, 'offline', undefined, unavailable), /503/);
});

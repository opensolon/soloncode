import assert from 'node:assert/strict';
import test from 'node:test';
import { parseAgentContent, serializeAgentDraft } from '../src/utils/agentConfig.ts';

const agent = { name: 'reviewer', description: 'Reviews changes' };

test('round trips prompts, escaped descriptions and selected MCP services', () => {
  const content = serializeAgentDraft({
    ...agent, description: 'Reviews: "safe" changes', prompt: 'Review code.\nKeep examples intact.',
    tools: ['read', 'mcp'], mcpServers: ['docs', 'git-tools'],
  });
  assert.match(content, /tools: \["read","mcp"\]/);
  assert.match(content, /mcpServers: \["docs","git-tools"\]/);
  assert.deepEqual(parseAgentContent(content, agent), {
    ...agent, description: 'Reviews: "safe" changes', prompt: 'Review code.\nKeep examples intact.',
    tools: ['read', 'mcp'], mcpServers: ['docs', 'git-tools'],
  });
});

test('keeps legacy MCP access distinct from an explicitly empty selection', () => {
  const legacy = parseAgentContent('---\nallowed-tools: [read, mcp]\n---\n\nReview.', agent);
  assert.equal(legacy.mcpServers, null);
  assert.deepEqual(legacy.tools, ['read', 'mcp']);
  const restricted = parseAgentContent('---\ntools: [read, mcp]\nmcpServers: []\n---\nReview.', agent);
  assert.deepEqual(restricted.mcpServers, []);
  assert.doesNotMatch(serializeAgentDraft(restricted), /"mcp"/);
});

test('reads YAML block lists produced by LLM creation', () => {
  const parsed = parseAgentContent('---\nname: reviewer\ntools:\n  - read\n  - edit\nmcpServers:\n  - docs\n  - "git-tools"\n---\n\nReview.', agent);
  assert.deepEqual(parsed.tools, ['read', 'edit']);
  assert.deepEqual(parsed.mcpServers, ['docs', 'git-tools']);
});

test('adds MCP access only when a service is selected', () => {
  const draft = { ...agent, prompt: 'Review.', tools: ['read'], mcpServers: ['docs'] };
  assert.match(serializeAgentDraft(draft), /tools: \["read","mcp"\]/);
  assert.match(serializeAgentDraft({ ...draft, mcpServers: [] }), /tools: \["read"\]/);
});

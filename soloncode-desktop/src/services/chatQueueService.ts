import type { SendOptions } from '../components/ChatInput';

export interface QueuedChatMessage {
  id: string;
  text: string;
  displayText: string;
  options: SendOptions;
  createdAt: number;
  restored?: boolean;
  hadFiles?: boolean;
}

type QueueWireItem = {
  id?: string;
  text?: string;
  displayText?: string;
  model?: string;
  modelName?: string;
  reasoningEffort?: SendOptions['reasoningEffort'];
  mode?: SendOptions['mode'];
  goalMaxTokens?: number;
  goalMaxDurationMinutes?: number;
  goalMaxIterations?: number;
  selectedAgent?: string;
  hasFiles?: boolean;
  createdAt?: number;
};

export const MAX_CHAT_QUEUE_SIZE = 10;

function baseUrl(port?: number | null) {
  return `http://localhost:${port || 4808}`;
}

export function serializeQueue(items: QueuedChatMessage[]) {
  return items.slice(0, MAX_CHAT_QUEUE_SIZE).map(item => ({
    id: item.id,
    text: item.text,
    displayText: item.displayText,
    model: item.options.model,
    modelName: item.options.modelName,
    reasoningEffort: item.options.reasoningEffort,
    mode: item.options.mode,
    goalMaxTokens: item.options.goalMaxTokens,
    goalMaxDurationMinutes: item.options.goalMaxDurationMinutes,
    goalMaxIterations: item.options.goalMaxIterations,
    selectedAgent: item.options.agent,
    hasFiles: Boolean(item.hadFiles || item.options.attachments.length > 0 || item.options.contexts.length > 0),
    createdAt: item.createdAt,
  }));
}

function fromWire(item: QueueWireItem): QueuedChatMessage | null {
  const text = String(item.text || '').trim();
  if (!text) return null;
  const effort = item.reasoningEffort;
  return {
    id: String(item.id || `queue-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`),
    text,
    displayText: String(item.displayText || text),
    createdAt: Number(item.createdAt) || Date.now(),
    restored: true,
    hadFiles: Boolean(item.hasFiles),
    options: {
      model: String(item.model || ''),
      modelName: String(item.modelName || item.model || ''),
      agent: String(item.selectedAgent || ''),
      contexts: [],
      attachments: [],
      reasoningEffort: effort === 'low' || effort === 'high' || effort === 'max' ? effort : 'medium',
      mode: item.mode === 'auto' || item.mode === 'full' || item.mode === 'plan' || item.mode === 'goal' ? item.mode : 'default',
      goalMaxTokens: Number(item.goalMaxTokens) > 0 ? Math.floor(Number(item.goalMaxTokens)) : undefined,
      goalMaxDurationMinutes: Number(item.goalMaxDurationMinutes) > 0 ? Math.floor(Number(item.goalMaxDurationMinutes)) : undefined,
      goalMaxIterations: Number(item.goalMaxIterations) > 0 ? Math.floor(Number(item.goalMaxIterations)) : undefined,
      displayText: String(item.displayText || text),
    },
  };
}

export const chatQueueService = {
  async load(port: number | null | undefined, sessionId: string): Promise<QueuedChatMessage[]> {
    const response = await fetch(`${baseUrl(port)}/web/chat/queue?sessionId=${encodeURIComponent(sessionId)}`, { cache: 'no-store' });
    if (!response.ok) throw new Error('队列加载失败');
    const result = await response.json();
    const data = result?.data || result || {};
    const items = Array.isArray(data.items) ? data.items : [];
    return items.map(fromWire).filter((item: QueuedChatMessage | null): item is QueuedChatMessage => Boolean(item)).slice(0, MAX_CHAT_QUEUE_SIZE);
  },

  async save(port: number | null | undefined, sessionId: string, items: QueuedChatMessage[]): Promise<void> {
    const response = await fetch(`${baseUrl(port)}/web/chat/queue`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId, updatedAt: Date.now(), items: serializeQueue(items) }),
    });
    if (!response.ok) throw new Error('队列保存失败');
    const result = await response.json().catch(() => null);
    if (result?.code !== undefined && result.code !== 200) throw new Error('队列保存失败');
  },
};

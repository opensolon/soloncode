import { useState, useEffect, useRef, useCallback, useMemo, type FormEvent } from 'react';
import { invoke } from '@tauri-apps/api/core';
import type { Message, Conversation, Theme, Plugin, ContentType, ContentItem } from '../types';
import { normalizeProviderType, type ModelProvider } from '../services/settingsService';
import { fileService } from '../services/fileService';
import { saveMessage, updateMessage, getMessagesByConversation, truncateConversationMessages } from '../db';
import { ChatHeader, type ChatReviewFile } from './ChatHeader';
import { ChatTaskList, type ChatTask } from './ChatTaskList';
import { ChatMessages } from './ChatMessages';
import { ChatInput, type Attachment, type ChatAgentOption, type ChatSkillOption, type SendOptions, type ReasoningEffort } from './ChatInput';
import { ChatQueueDock } from './ChatQueueDock';
import { Icon } from './common/Icon';
import type { Session } from './sidebar/SessionsPanel';
import {
  buildAutomationPlanningPrompt,
  parseGeneratedAutomationPlan,
  type GeneratedAutomationPlan,
} from '../utils/automationPlan';
import { isTodoToolName } from '../utils/todoTools';
import { buildUserMessageContents, isSafeImageDataUrl, mergeStreamingMessage } from '../utils/messageContent';
import {
  EMPTY_RESPONSE_ERROR,
  RESPONSE_PROTOCOL_ERROR,
  RESPONSE_TIMEOUT_ERROR,
  formatResponseErrorText,
} from '../utils/responseErrors';
import { withRetry } from '../utils/retry';
import { chatQueueService, MAX_CHAT_QUEUE_SIZE, type QueuedChatMessage } from '../services/chatQueueService';
import { checkpointService } from '../services/checkpointService';
import { permissionService } from '../services/permissionService';
import './ChatView.css';

export type PromptCreationType = 'skill' | 'agent' | 'automation';
type HitlAction = 'approve' | 'approve_always' | 'reject';

interface PendingHitlItem {
  id: string;
  sessionId: string;
  item: ContentItem;
}

export interface PromptCreationMode {
  id: string;
  sessionId: string;
  type: PromptCreationType;
  projectId?: string;
  template?: string;
}

const promptCreationCopy: Record<PromptCreationType, { slogan: string; fileName?: string }> = {
  skill: {
    slogan: '描述 Skill 的名称、用途、触发场景和需要遵守的规则',
    fileName: 'SKILL.md',
  },
  agent: {
    slogan: '描述 Agent 的角色、能力、工作流程和行为约束，名称将自动生成',
    fileName: 'AGENT.md',
  },
  automation: {
    slogan: '描述执行频率和工作内容，模型会识别调度并补全实际执行提示词',
  },
};

const AUTO_AGENT_NAME_TOKEN = 'AUTO_GENERATED_AGENT_NAME';

function normalizeResourceName(value: string, type: 'skill' | 'agent'): string {
  const normalized = value
    .trim()
    .replace(/^[`'"“”‘’「」『』]+|[`'"“”‘’「」『』]+$/g, '')
    .normalize('NFKC')
    .replace(/[^\p{L}\p{N}_-]+/gu, '-')
    .replace(/^-+|-+$/g, '');
  const truncated = Array.from(normalized).slice(0, 64).join('').replace(/-+$/g, '');
  const generic = truncated.toLowerCase();
  const reserved = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])$/i.test(truncated);
  if (
    !truncated
    || reserved
    || generic === type
    || generic === `${type}s`
    || generic === AUTO_AGENT_NAME_TOKEN.toLowerCase()
  ) {
    return '';
  }
  return truncated;
}

function createResourceName(prompt: string, type: 'skill' | 'agent'): string {
  const explicitName = prompt.match(/(?:名为|名称(?:为|是)?|named|called)\s*[「『“"']?([A-Za-z0-9\u4e00-\u9fff_-]{1,64})/i)?.[1];
  const normalized = normalizeResourceName(explicitName || prompt, type);
  if (normalized) return normalized;
  return `${type}-${Date.now().toString(36)}`;
}

function buildResourcePrompt(mode: PromptCreationMode, userPrompt: string, resourceName?: string): string {
  if (mode.type === 'agent') {
    const template = mode.template
      ? mode.template
        .replace(/\{name\}/g, AUTO_AGENT_NAME_TOKEN)
        .replace(/\{description\}/g, userPrompt)
      : `请直接输出完整的 AGENT.md 文件内容。\n\n${userPrompt}`;
    return [
      '请根据用户需求自动生成一个简短、清晰且能概括职责的 Agent 名称，不要直接复制整段需求。',
      '名称必须为 1-64 个字符，只能包含文字、数字、短横线和下划线。',
      `请在最终 AGENT.md 的 YAML frontmatter 中输出真实名称，格式为 name: <生成的名称>；不要保留 ${AUTO_AGENT_NAME_TOKEN} 占位符。`,
      '只输出完整的 AGENT.md（纯 Markdown，不要使用代码块包裹）。',
      '',
      template,
    ].join('\n');
  }
  if (mode.template) {
    return mode.template
      .replace(/\{name\}/g, resourceName || '')
      .replace(/\{description\}/g, userPrompt);
  }
  return `请根据以下需求创建 Skill，名称为 ${resourceName}。\n\n${userPrompt}`;
}

function stripOuterMarkdownFence(content: string): string {
  const trimmed = content.trim();
  const match = trimmed.match(/^```(?:markdown|md)?\s*\r?\n([\s\S]*?)\r?\n```$/i);
  return (match?.[1] || trimmed).trim();
}

function extractGeneratedAgentName(content: string): string {
  const markdown = stripOuterMarkdownFence(content);
  const frontmatter = markdown.match(/^---\s*\r?\n([\s\S]*?)\r?\n---/)?.[1] || '';
  const frontmatterName = frontmatter.match(/^\s*name\s*:\s*(.+?)\s*$/im)?.[1] || '';
  const fromFrontmatter = normalizeResourceName(frontmatterName, 'agent');
  if (fromFrontmatter) return fromFrontmatter;

  const heading = markdown.match(/^#\s+(.+?)\s*$/m)?.[1]?.replace(/\s+Agent\s*$/i, '') || '';
  return normalizeResourceName(heading, 'agent');
}

function applyGeneratedAgentName(content: string, name: string): string {
  const markdown = stripOuterMarkdownFence(content);
  const frontmatterMatch = markdown.match(/^---\s*\r?\n([\s\S]*?)\r?\n---/);
  if (!frontmatterMatch) {
    return `---\nname: ${name}\ndescription: ${name}\n---\n\n${markdown}`;
  }

  const frontmatter = frontmatterMatch[1];
  const updatedFrontmatter = /^\s*name\s*:/im.test(frontmatter)
    ? frontmatter.replace(/^\s*name\s*:.*$/im, `name: ${name}`)
    : `name: ${name}\n${frontmatter}`;
  return markdown.replace(frontmatterMatch[0], `---\n${updatedFrontmatter.trim()}\n---`);
}

async function createUniqueAgentName(name: string): Promise<string> {
  try {
    const existingAgents = await invoke<Array<{ name: string }>>('list_agents');
    const existingNames = new Set(existingAgents.map(agent => agent.name.toLocaleLowerCase()));
    if (!existingNames.has(name.toLocaleLowerCase())) return name;

    for (let suffixNumber = 2; suffixNumber <= 999; suffixNumber += 1) {
      const suffix = `-${suffixNumber}`;
      const base = Array.from(name).slice(0, 64 - suffix.length).join('').replace(/-+$/g, '');
      const candidate = `${base}${suffix}`;
      if (!existingNames.has(candidate.toLocaleLowerCase())) return candidate;
    }
  } catch (error) {
    console.warn('[ChatView] 检查 Agent 重名失败，将交由后端校验:', error);
  }
  return name;
}

interface ChatViewProps {
  currentConversation: Conversation;
  plugins?: Plugin[];
  workspacePath?: string;
  projectName?: string;
  theme?: Theme;
  backendPort?: number | null;
  sessions?: Session[];
  sessionRunStates?: Record<string, 'running' | 'completed' | 'error'>;
  maxSteps?: number;
  goalDefaultMaxTokens?: number;
  goalDefaultMaxDuration?: number;
  onUpdateSessionTitle?: (sessionId: string, title: string) => string | void | Promise<string | void>;
  onNewSession?: (title?: string) => string;
  onSelectSession?: (sessionId: string) => void;
  providers?: ModelProvider[];
  agents?: ChatAgentOption[];
  skills?: ChatSkillOption[];
  agentRefreshKey?: number;
  activeProviderId?: string;
  onActiveProviderChange?: (providerId: string) => void;
  activeFileName?: string;
  activeFilePath?: string;
  onNewProject?: () => void;
  onOpenFolder?: () => void;
  onFileSelect?: (path: string) => void;
  reviewFiles?: ChatReviewFile[];
  onReviewFileSelect?: (path: string) => void;
  onReviewFileDiscard?: (path: string) => void | Promise<void>;
  promptCreation?: PromptCreationMode | null;
  onCreateAutomationFromPrompt?: (plan: GeneratedAutomationPlan, options: SendOptions) => Promise<void>;
  automationPrompt?: {
    runId: string;
    sessionId: string;
    prompt: string;
    modelId: string;
    modelName: string;
    reasoningEffort: ReasoningEffort;
  } | null;
  onAutomationPromptConsumed?: (runId: string) => void;
  onAiCreateComplete?: (info: { type: PromptCreationType; name: string; error?: string }) => void;
  newSessionFromProject?: boolean;
  onSessionRunStateChange?: (sessionId: string, status: 'running' | 'completed' | 'error', error?: string) => void;
  onSessionMessageSaved?: (sessionId: string, count?: number) => void;
  onNotify?: (message: string) => void;
}

// 全局 WebSocket 连接管理器（每次请求独立连接�?
const STREAM_BATCH_INTERVAL_MS = 16;
const STREAM_BATCH_CHARS = 24;
const WS_CONNECT_TIMEOUT_MS = 10_000;
const WS_CONNECT_RETRY_DELAYS_MS = [300, 700, 1500];
const WS_RECONNECT_DELAYS_MS = [500, 1000, 2000];
const RESPONSE_TIMEOUT_MS = 120_000;
const RESPONSE_TIMEOUT_MAX_RETRIES = 3;

class WebSocketManager {
  private static instance: WebSocketManager | null = null;
  private activeWs = new Map<string, WebSocket>();
  private intentionallyClosedWs = new WeakSet<WebSocket>();
  private lastSequenceBySession = new Map<string, number>();
  private reconnectAttempts = new Map<string, number>();
  private reconnectTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private terminalSessions = new Set<string>();
  private reconnectingSessions = new Set<string>();
  private messageCallback: ((data: any) => void | Promise<void>) | null = null;
  private statusCallback: ((sessionId: string, status: 'running' | 'completed' | 'error', error?: string) => void) | null = null;
  private backendPort: number | null = null;
  private workspacePath: string | null = null;

  static getInstance(): WebSocketManager {
    if (!WebSocketManager.instance) {
      WebSocketManager.instance = new WebSocketManager();
    }
    return WebSocketManager.instance;
  }

  /** 设置后端端口（由 App.tsx 调用，打开工作区后设置�?*/
  setBackendPort(port: number | null) {
    this.backendPort = port;
  }

  /** 获取后端端口 */
  getBackendPort(): number | null {
    return this.backendPort;
  }

  /** 设置工作区路径（�?App.tsx 调用�?*/
  setWorkspacePath(path: string | null) {
    this.workspacePath = path;
  }

  private getWebSocketUrl(sessionId?: string, resume = false): string {
    const host = this.backendPort
      ? `localhost:${this.backendPort}`
      : (import.meta.env.VITE_WS_HOST || 'localhost:4808');
    const protocol = import.meta.env.VITE_WS_PROTOCOL || 'ws';
    const params = new URLSearchParams();
    if (sessionId) {
      params.set('sessionId', sessionId);
    }
    if (this.workspacePath) {
      params.set('X-Session-Cwd', this.workspacePath);
    }
    if (resume && sessionId) {
      params.set('resume', '1');
      params.set('afterSequence', String(this.lastSequenceBySession.get(sessionId) || 0));
    }
    const query = params.toString();
    return `${protocol}://${host}/desktop/ws${query ? '?' + query : ''}`;
  }

  /** 每次请求创建独立 WebSocket 连接 */
  private createConnection(sessionId?: string, resume = false): Promise<WebSocket> {
    return new Promise((resolve, reject) => {
      const wsUrl = this.getWebSocketUrl(sessionId, resume);
      console.log('[WS] Connecting to:', wsUrl);
      const ws = new WebSocket(wsUrl);
      let settled = false;

      const onOpen = () => {
        if (settled) return;
        settled = true;
        cleanup();
        console.log('[WS] Connected');
        resolve(ws);
      };
      const onError = () => {
        if (settled) return;
        settled = true;
        cleanup();
        console.error('[WS] Connection error');
        reject(new Error('WebSocket connection failed'));
      };
      const onClose = () => {
        if (settled) return;
        settled = true;
        cleanup();
        reject(new Error('WebSocket closed before connected'));
      };
      const cleanup = () => {
        clearTimeout(timeout);
        ws.removeEventListener('open', onOpen);
        ws.removeEventListener('error', onError);
        ws.removeEventListener('close', onClose);
      };
      const timeout = setTimeout(() => {
        if (settled) return;
        settled = true;
        cleanup();
        this.intentionallyClosedWs.add(ws);
        ws.close();
        reject(new Error('WebSocket connection timed out'));
      }, WS_CONNECT_TIMEOUT_MS);
      ws.addEventListener('open', onOpen);
      ws.addEventListener('error', onError);
      ws.addEventListener('close', onClose);
    });
  }

  private async createConnectionWithRetry(sessionId: string): Promise<WebSocket> {
    let lastError: unknown;
    for (let attempt = 0; attempt <= WS_CONNECT_RETRY_DELAYS_MS.length; attempt++) {
      try {
        const ws = await this.createConnection(sessionId);
        if (attempt > 0) this.dispatchReconnected(sessionId);
        return ws;
      } catch (error) {
        lastError = error;
        if (attempt >= WS_CONNECT_RETRY_DELAYS_MS.length) break;
        this.dispatchReconnecting(sessionId, attempt + 1, WS_CONNECT_RETRY_DELAYS_MS.length);
        await new Promise(resolve => setTimeout(resolve, WS_CONNECT_RETRY_DELAYS_MS[attempt]));
      }
    }
    this.reconnectingSessions.delete(sessionId);
    throw lastError instanceof Error ? lastError : new Error('WebSocket connection failed');
  }

  registerCallback(callback: (data: any) => void | Promise<void>) {
    this.messageCallback = callback;
  }

  registerStatusCallback(callback: (sessionId: string, status: 'running' | 'completed' | 'error', error?: string) => void) {
    this.statusCallback = callback;
  }

  unregisterCallback() {
    this.messageCallback = null;
  }

  async sendMessage(request: any): Promise<void> {
    const sessionId = request.sessionId?.toString() || '';
    if (!sessionId) throw new Error('Session ID is required');
    this.closeSession(sessionId);
    this.terminalSessions.delete(sessionId);
    this.lastSequenceBySession.set(sessionId, 0);
    this.reconnectAttempts.set(sessionId, 0);
    const ws = await this.createConnectionWithRetry(sessionId);
    if (sessionId) {
      this.activeWs.set(sessionId, ws);
      this.statusCallback?.(sessionId, 'running');
    }

    this.bindSessionSocket(sessionId, ws);

    // sessionId 已通过 URL 参数传递，从 body 中移除。
    const payload = { ...request };
    delete payload.sessionId;
    try {
      ws.send(JSON.stringify(payload));
    } catch (error) {
      this.terminalSessions.add(sessionId);
      this.closeSession(sessionId);
      throw error;
    }
  }

  private bindSessionSocket(sessionId: string, ws: WebSocket) {
    ws.onmessage = (event) => this.handleSessionMessage(sessionId, ws, event);
    ws.onclose = () => {
      if (this.activeWs.get(sessionId) === ws) {
        this.activeWs.delete(sessionId);
      }
      if (!this.terminalSessions.has(sessionId) && !this.intentionallyClosedWs.has(ws)) {
        this.scheduleReconnect(sessionId);
      }
    };
  }

  private handleSessionMessage(sessionId: string, ws: WebSocket, event: MessageEvent) {
    try {
      const data = event.data;
      if (typeof data !== 'string') throw new Error('Unsupported WebSocket frame type');
      if (data.trim() === '[DONE]') {
        this.finishSession(sessionId, ws, { type: 'done', sessionId });
        return;
      }
      const parsed: unknown = JSON.parse(data);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('Invalid WebSocket message');
      const msg = parsed as Record<string, any>;
      if (typeof msg.type !== 'string') throw new Error('WebSocket message type is required');
      if (msg.text !== undefined && typeof msg.text !== 'string') throw new Error('Invalid WebSocket message text');
      if (msg.sessionId !== undefined && typeof msg.sessionId !== 'string' && typeof msg.sessionId !== 'number') {
        throw new Error('Invalid WebSocket session ID');
      }

      const msgSessionId = (msg.sessionId || sessionId).toString();
      msg.sessionId = msgSessionId;
      this.reconnectAttempts.set(msgSessionId, 0);
      if (typeof msg.sequence === 'number' && Number.isSafeInteger(msg.sequence) && msg.sequence > 0) {
        const previousSequence = this.lastSequenceBySession.get(msgSessionId) || 0;
        if (msg.sequence <= previousSequence) return;
        this.lastSequenceBySession.set(msgSessionId, msg.sequence);
      }

      const messageType = msg.type.toLowerCase();
      msg.type = messageType;
      if (messageType === 'done' || messageType === 'error') {
        this.finishSession(msgSessionId, ws, msg);
        return;
      }
      this.dispatchMessage(msg);
    } catch (error) {
      console.warn('[WS] Failed to parse message:', error);
      this.finishSession(sessionId, ws, {
        type: 'error',
        sessionId,
        text: RESPONSE_PROTOCOL_ERROR,
      });
    }
  }

  private finishSession(sessionId: string, ws: WebSocket, message: Record<string, any>) {
    if (this.terminalSessions.has(sessionId)) return;
    this.terminalSessions.add(sessionId);
    this.reconnectingSessions.delete(sessionId);
    this.clearReconnectTimer(sessionId);
    const messageType = String(message.type || '').toLowerCase();
    if (messageType === 'done') this.statusCallback?.(sessionId, 'completed');
    else this.statusCallback?.(sessionId, 'error', message.text || '会话执行失败');
    this.dispatchMessage(message);
    if (ws.readyState === WebSocket.OPEN) ws.close();
  }

  private scheduleReconnect(sessionId: string) {
    if (this.reconnectTimers.has(sessionId) || this.terminalSessions.has(sessionId)) return;
    const attempt = this.reconnectAttempts.get(sessionId) || 0;
    if (attempt >= WS_RECONNECT_DELAYS_MS.length) {
      this.terminalSessions.add(sessionId);
      this.reconnectingSessions.delete(sessionId);
      const text = '连接恢复失败，请重试';
      this.statusCallback?.(sessionId, 'error', text);
      this.dispatchMessage({ type: 'error', sessionId, text });
      return;
    }

    this.dispatchReconnecting(sessionId, attempt + 1, WS_RECONNECT_DELAYS_MS.length);
    const timer = setTimeout(async () => {
      this.reconnectTimers.delete(sessionId);
      if (this.terminalSessions.has(sessionId)) return;
      this.reconnectAttempts.set(sessionId, attempt + 1);
      try {
        const ws = await this.createConnection(sessionId, true);
        if (this.terminalSessions.has(sessionId)) {
          this.intentionallyClosedWs.add(ws);
          ws.close();
          return;
        }
        this.activeWs.set(sessionId, ws);
        this.bindSessionSocket(sessionId, ws);
        this.dispatchReconnected(sessionId);
        this.statusCallback?.(sessionId, 'running');
      } catch (error) {
        console.warn(`[WS] Reconnect attempt ${attempt + 1} failed:`, error);
        this.scheduleReconnect(sessionId);
      }
    }, WS_RECONNECT_DELAYS_MS[attempt]);
    this.reconnectTimers.set(sessionId, timer);
  }

  private dispatchMessage(message: Record<string, any>) {
    try {
      const result = this.messageCallback?.(message);
      if (result) {
        void result.catch(error => console.error('[WS] Message handler failed:', error));
      }
    } catch (error) {
      console.error('[WS] Message handler failed:', error);
    }
  }

  private dispatchReconnecting(sessionId: string, attempt: number, maxAttempts: number) {
    this.reconnectingSessions.add(sessionId);
    this.dispatchMessage({
      type: 'reconnecting',
      sessionId,
      attempt,
      maxAttempts,
      text: `正在重连 ${attempt}/${maxAttempts}...`,
    });
  }

  private dispatchReconnected(sessionId: string) {
    if (!this.reconnectingSessions.delete(sessionId)) return;
    this.dispatchMessage({ type: 'reconnected', sessionId });
  }

  private dispatchReconnectCleared(sessionId: string) {
    if (!this.reconnectingSessions.delete(sessionId)) return;
    this.dispatchMessage({ type: 'reconnect_cleared', sessionId });
  }

  private clearReconnectTimer(sessionId: string) {
    const timer = this.reconnectTimers.get(sessionId);
    if (timer) clearTimeout(timer);
    this.reconnectTimers.delete(sessionId);
  }

  /** 取消当前请求：关闭连�?*/
  cancel() {
    this.closeActive();
  }

  cancelSession(sessionId: string) {
    this.terminalSessions.add(sessionId);
    this.dispatchReconnectCleared(sessionId);
    this.clearReconnectTimer(sessionId);
    const ws = this.activeWs.get(sessionId);
    if (ws?.readyState === WebSocket.OPEN) {
      try {
        ws.send(JSON.stringify({ input: '[(sec)interrupt]' }));
      } catch {
        // Closing the socket below is still sufficient to stop client-side streaming.
      }
      setTimeout(() => this.closeSession(sessionId), 50);
    } else {
      this.closeSession(sessionId);
    }
  }

  getSessionSocket(sessionId: string): WebSocket | null {
    return this.activeWs.get(sessionId) || null;
  }

  /** 回答长时间无响应时仅重连并续传，避免重新执行已经完成的工具调用。 */
  retrySession(sessionId: string): boolean {
    if (!sessionId || this.terminalSessions.has(sessionId)) return false;
    this.clearReconnectTimer(sessionId);
    const ws = this.activeWs.get(sessionId);
    if (ws) {
      this.activeWs.delete(sessionId);
      this.intentionallyClosedWs.add(ws);
      ws.close();
    }
    this.reconnectAttempts.set(sessionId, 0);
    this.scheduleReconnect(sessionId);
    return true;
  }

  disconnect() {
    this.closeActive();
    this.messageCallback = null;
  }

  /** 通过桌面端 WebSocket 推送配置变更。 */
  async sendConfig(chatModel: { apiUrl?: string; apiKey?: string; model?: string; provider?: string }): Promise<void> {
    const ws = await this.createConnection();
    try {
      ws.send(JSON.stringify({ type: 'config', chatModel }));
    } finally {
      ws.close();
    }
  }

  private closeActive() {
    for (const sessionId of this.reconnectTimers.keys()) this.clearReconnectTimer(sessionId);
    for (const sessionId of Array.from(this.reconnectingSessions)) this.dispatchReconnectCleared(sessionId);
    for (const ws of this.activeWs.values()) {
      this.intentionallyClosedWs.add(ws);
      ws.close();
    }
    this.activeWs.clear();
  }

  private closeSession(sessionId: string) {
    this.clearReconnectTimer(sessionId);
    const ws = this.activeWs.get(sessionId);
    if (ws) {
      this.intentionallyClosedWs.add(ws);
      ws.close();
      this.activeWs.delete(sessionId);
    }
  }

  closeConnection() {
    this.closeActive();
  }
}

// 过滤空标签和 trace 信息的辅助函�?
function filterEmptyTags(text: string): string {
  let result = text;
  // 过滤空的 HTML/XML 标签（包括带属性的�?
  result = result.replace(/<([a-zA-Z][a-zA-Z0-9]*)([^>]*)><\/\1>/g, '');
  result = result.replace(/<([a-zA-Z][a-zA-Z0-9]*)([^>]*)\/>/g, '');
  // 过滤连续的空行（超过2个换行符�?
  result = result.replace(/\n{3,}/g, '\n\n');
  // 过滤末尾的模�?trace 信息，如 `(glm-4.7, 6985tk, 4s)` �?`(gpt-4o, 1s)`
  result = result.replace(/\s*`\?\(?[\w.\-]+(?:,\s*\d+\.?\d*\w+)*\)\s*`?$/gm, '');
  return result;
}

function estimateMessageTokens(messages: Message[]) {
  const text = messages
    .flatMap(message => message.contents)
    .map(item => item.type === 'IMAGE' ? '' : (item.text || ''))
    .join('\n');
  return Math.ceil(text.length / 4);
}

function parseTodoMarkdown(raw: string): ChatTask[] {
  const tasks: ChatTask[] = [];
  let currentGroup = '';
  String(raw || '').split(/\r?\n/).forEach((line, index) => {
    const heading = line.match(/^\s*##\s+(.+)$/);
    if (heading) {
      currentGroup = heading[1].trim();
      return;
    }
    const match = line.match(/^\s*-\s*\[([ xX/])\]\s+(.+)$/);
    if (!match) return;
    const statusChar = match[1];
    const status: ChatTask['status'] = statusChar === ' '
      ? 'pending'
      : (statusChar === '/' ? 'in_progress' : 'done');
    const title = match[2].trim();
    tasks.push({
      id: `todo-${index + 1}-${title}`,
      title,
      status,
      group: currentGroup,
      line: index + 1,
    });
  });
  return tasks;
}

function getTodoMarkdownFromContent(item: ContentItem) {
  if (!isTodoToolName(item.toolName)) return null;
  const todosArg = item.args?.todos;
  if (typeof todosArg === 'string' && todosArg.trim()) return todosArg;
  return item.text || '';
}

function extractLatestTodoTasks(messages: Message[]): ChatTask[] {
  let latest: ChatTask[] | null = null;
  for (const message of messages) {
    for (const item of message.contents || []) {
      const markdown = getTodoMarkdownFromContent(item);
      if (markdown === null) continue;
      latest = parseTodoMarkdown(markdown);
    }
  }
  return latest || [];
}

function mapTodoApiItems(items: any[]): ChatTask[] {
  return (Array.isArray(items) ? items : []).map((item, index) => {
    const status = item.status === 'done'
      ? 'done'
      : (item.status === 'in_progress' ? 'in_progress' : 'pending');
    const line = Number(item.line) || index + 1;
    const title = String(item.text || item.raw || `Task ${index + 1}`);
    return {
      id: `todo-${line}-${title}`,
      title,
      status,
      group: item.group || '',
      line,
    } satisfies ChatTask;
  });
}

async function buildActiveFileContext(filePath: string, workspacePath?: string): Promise<string | null> {
  try {
    const content = await fileService.readFile(filePath);
    const normalizedWorkspace = workspacePath?.replace(/\\/g, '/').replace(/\/$/, '');
    const normalizedPath = filePath.replace(/\\/g, '/');
    const displayPath = normalizedWorkspace && normalizedPath.startsWith(`${normalizedWorkspace}/`)
      ? normalizedPath.slice(normalizedWorkspace.length + 1)
      : normalizedPath;
    const lineCount = content ? content.split(/\r\n|\r|\n/).length : 0;
    const sizeKb = Math.max(0.01, new Blob([content]).size / 1024).toFixed(2);
    const maxChars = 64000;
    const clipped = content.length > maxChars
      ? `${content.slice(0, maxChars)}\n\n[Content truncated: ${content.length - maxChars} characters omitted]`
      : content;
    return [
      `[Current File: ${displayPath} (Lines: ${lineCount}, Size: ${sizeKb} KB)]`,
      '```',
      clipped,
      '```',
    ].join('\n');
  } catch (err) {
    console.warn('[ChatView] 读取当前文件上下文失败:', err);
    return null;
  }
}

/** 设置后端 WebSocket 端口（供 App.tsx 调用�?*/
function stripInjectedPromptContext(text: string) {
  let result = text;
  result = result.replace(/^(?:\[Current File:[^\n]*\]\n```[\s\S]*?```\n*)+/i, '');
  result = result.replace(/^(?:---\s*(?:文件|File):[^\n]*---\n[\s\S]*?\n---\n*)+/i, '');
  return result.trimStart() || text;
}

export function setBackendPort(port: number | null) {
  WebSocketManager.getInstance().setBackendPort(port);
}

/** 设置工作区路径（�?App.tsx 调用，连�?WS 时会作为 X-Session-Cwd 参数传入�?*/
export function setWorkspacePath(path: string | null) {
  WebSocketManager.getInstance().setWorkspacePath(path);
}

const FALLBACK_PORT = 4808;

/** 通过 REST API 注册模型到后�?*/
async function registerModelToBackend(provider: { apiUrl: string; apiKey: string; model: string; type?: string; contextLength?: number; timeout?: string; scope?: string; defaultOptions?: string }, select?: boolean) {
  const port = WebSocketManager.getInstance().getBackendPort() || FALLBACK_PORT;
  try {
    let defaultOptions: Record<string, unknown> | undefined;
    if (provider.defaultOptions?.trim()) {
      try {
        defaultOptions = JSON.parse(provider.defaultOptions);
      } catch {
        console.warn('[ChatView] 默认选项 JSON 无效，已跳过');
      }
    }
    const resp = await fetch(`http://localhost:${port}/desktop/chat/models/add`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: provider.model,
        apiUrl: provider.apiUrl,
        apiKey: provider.apiKey,
        model: provider.model,
        provider: normalizeProviderType(provider.type),
        standard: normalizeProviderType(provider.type),
        scope: provider.scope || 'user',
        contextLength: provider.contextLength,
        timeout: provider.timeout || 'PT120S',
        defaultOptions,
      }),
    });
    if (!resp.ok) {
      console.warn('[ChatView] 注册模型失败:', resp.status, await resp.text());
      return;
    }
    if (select) {
      await fetch(`http://localhost:${port}/desktop/chat/models/select`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: `modelName=${encodeURIComponent(provider.model)}`,
      });
    }
    console.log('[ChatView] 模型已注册:', provider.model);
  } catch (err) {
    console.warn('[ChatView] 注册模型失败:', err);
  }
}

/** 推送模型配置到后端（供 App.tsx 保存设置时调用） */
export async function sendModelConfig(provider: { apiUrl: string; apiKey: string; model: string; type?: string; contextLength?: number; timeout?: string; scope?: string; defaultOptions?: string }) {
  await registerModelToBackend(provider, true);
}

function ReviewPrompt({ files, onReview }: { files: ChatReviewFile[]; onReview: () => void }) {
  return (
    <div className="chat-review-prompt">
      <button type="button" className="chat-review-btn" onClick={onReview}>
        <Icon name="git" size={14} />
        <span>审查</span>
        <span className="chat-review-count">{files.length}</span>
      </button>
    </div>
  );
}

function getReviewFileName(path: string) {
  return path.split(/[\\/]/).pop() || path;
}

function getReviewFileActionLabel(status: ChatReviewFile['status']) {
  if (status === 'added' || status === 'untracked') return '已新增';
  if (status === 'deleted') return '已删除';
  return '已编辑';
}

function ReviewFilesBar({ files, onReview, onDiscard }: { files: ChatReviewFile[]; onReview?: (path: string) => void; onDiscard?: (path: string) => void | Promise<void> }) {
  const [isDiscarding, setIsDiscarding] = useState(false);
  if (files.length === 0) return null;
  const additions = files.reduce((total, file) => total + (file.additions || 0), 0);
  const deletions = files.reduce((total, file) => total + (file.deletions || 0), 0);
  const primaryStatus = files.every(file => file.status === files[0].status) ? files[0].status : 'modified';
  const title = files.length === 1
    ? `${getReviewFileActionLabel(files[0].status)} ${getReviewFileName(files[0].path)}`
    : `${getReviewFileActionLabel(primaryStatus)} ${files.length} 个文件`;
  const handleReviewAll = () => onReview?.(files[0].path);
  const handleDiscardAll = async () => {
    if (!onDiscard || isDiscarding) return;
    setIsDiscarding(true);
    try {
      // Git 会持有索引锁，逐个撤销可以避免多文件并发操作互相冲突。
      for (const file of files) await onDiscard(file.path);
    } catch (error) {
      console.warn('[ChatView] 撤销文件更改失败:', error);
    } finally {
      setIsDiscarding(false);
    }
  };

  return (
    <div className="chat-review-files-bar">
      <div className="chat-review-file-card">
        <div className="chat-review-file-summary">
          <div className="chat-review-file-icon">
            <Icon name={primaryStatus === 'deleted' ? 'deleted' : primaryStatus === 'added' || primaryStatus === 'untracked' ? 'added' : 'modified'} size={18} />
          </div>
          <button type="button" className="chat-review-file-main" onClick={handleReviewAll} title={files[0].path}>
            <span className="chat-review-file-title">{title}</span>
            <span className="chat-review-file-stats">
              <span className="review-additions">+{additions}</span>
              <span className="review-deletions">-{deletions}</span>
            </span>
          </button>
          <div className="chat-review-file-actions">
            <button type="button" className="chat-review-link-btn" onClick={() => { void handleDiscardAll(); }} title="撤销" disabled={isDiscarding}>
              <span>{isDiscarding ? '撤销中' : '撤销'}</span>
              <Icon name="undo" size={13} />
            </button>
            <button type="button" className="chat-review-primary-btn" onClick={handleReviewAll}>
              审核
            </button>
          </div>
        </div>
        {files.length > 1 && (
          <div className="chat-review-file-list-card">
            {files.map(file => (
              <button type="button" key={`${file.status}:${file.path}`} className="chat-review-file-row" onClick={() => onReview?.(file.path)} title={file.path}>
                <span className="chat-review-file-path-text">{file.path}</span>
                <span className="chat-review-file-row-stats">
                  <span className="review-additions">+{file.additions || 0}</span>
                  <span className="review-deletions">-{file.deletions || 0}</span>
                </span>
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function HitlInputCard({ pending, onAction }: { pending: PendingHitlItem; onAction: (pending: PendingHitlItem, action: HitlAction, output?: string) => void }) {
  const [open, setOpen] = useState(true);
  const [selectedAction, setSelectedAction] = useState<HitlAction>('approve');
  const [output, setOutput] = useState('');
  const canRemember = Boolean(pending.item.toolName && permissionService.canRemember(pending.item.toolName));
  const radioName = `${pending.id}-action`;
  const outputId = `${pending.id}-output`;

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    onAction(pending, selectedAction, output.trim() || undefined);
  }

  if (!open) {
    return (
      <button type="button" className="hitl-dock-collapsed" onClick={() => setOpen(true)}>
        <span>人机交互待确认</span>
        {pending.item.toolName && <span>{pending.item.toolName}</span>}
      </button>
    );
  }

  return (
    <form className="hitl-dock-card" onSubmit={handleSubmit}>
      <div className="hitl-dock-header">
        <span>人机交互</span>
        {pending.item.toolName && <span className="hitl-dock-tool">{pending.item.toolName}</span>}
      </div>
      {pending.item.text && <div className="hitl-dock-comment">{pending.item.text}</div>}
      {pending.item.command && <div className="hitl-dock-command"><code>{pending.item.command}</code></div>}
      <div className="hitl-dock-options" role="radiogroup" aria-label="人机交互选项">
        <label className="hitl-dock-option">
          <input
            type="radio"
            name={radioName}
            checked={selectedAction === 'approve'}
            onChange={() => setSelectedAction('approve')}
          />
          <span>允许本次</span>
        </label>
        <label className={`hitl-dock-option${canRemember ? '' : ' disabled'}`}>
          <input
            type="radio"
            name={radioName}
            disabled={!canRemember}
            checked={selectedAction === 'approve_always'}
            onChange={() => setSelectedAction('approve_always')}
          />
          <span>项目内总是允许</span>
        </label>
        <label className="hitl-dock-option">
          <input
            type="radio"
            name={radioName}
            checked={selectedAction === 'reject'}
            onChange={() => setSelectedAction('reject')}
          />
          <span>拒绝</span>
        </label>
      </div>
      <label className="hitl-dock-output-label" htmlFor={outputId}>其他（输出）</label>
      <textarea
        id={outputId}
        className="hitl-dock-output"
        value={output}
        onChange={event => setOutput(event.target.value)}
        placeholder="可填写人工输出、说明或拒绝原因"
        rows={3}
      />
      <div className="hitl-dock-actions">
        <button type="submit" className="hitl-dock-submit">提交</button>
        <button type="button" className="hitl-dock-cancel" onClick={() => setOpen(false)}>取消</button>
      </div>
    </form>
  );
}

function HitlInputDock({ items, onAction }: { items: PendingHitlItem[]; onAction: (pending: PendingHitlItem, action: HitlAction, output?: string) => void }) {
  if (items.length === 0) return null;
  return (
    <div className="hitl-input-dock" role="region" aria-label="待处理人机交互">
      <div className="hitl-input-popover">
        <div className="hitl-input-list">
          {items.map(item => (
            <HitlInputCard key={item.id} pending={item} onAction={onAction} />
          ))}
        </div>
      </div>
    </div>
  );
}

async function sendHitlDecision(sessionId: string, action: 'approve' | 'reject', output?: string, callId?: string): Promise<void> {
  const manager = WebSocketManager.getInstance();
  const activeSocket = manager.getSessionSocket(sessionId);
  const payload = JSON.stringify({ type: 'hitl_action', action, sessionId, output, callId });
  if (activeSocket && activeSocket.readyState === WebSocket.OPEN) {
    activeSocket.send(payload);
    return;
  }
  const connection = await (manager as any).createConnection(sessionId);
  connection.send(payload);
}

export function ChatView({ currentConversation, plugins, workspacePath, projectName, theme = 'dark', backendPort, sessions = [], sessionRunStates = {}, maxSteps = 30, goalDefaultMaxTokens = 0, goalDefaultMaxDuration = 0, onUpdateSessionTitle, onNewSession, onSelectSession, providers = [], agents = [], skills = [], agentRefreshKey, activeProviderId, onActiveProviderChange, activeFileName, activeFilePath, onNewProject, onOpenFolder, onFileSelect, reviewFiles = [], onReviewFileSelect, onReviewFileDiscard, promptCreation, onCreateAutomationFromPrompt, automationPrompt, onAutomationPromptConsumed, onAiCreateComplete, newSessionFromProject, onSessionRunStateChange, onSessionMessageSaved, onNotify }: ChatViewProps) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [planApproval, setPlanApproval] = useState<{ sessionId: string; options: SendOptions } | null>(null);
  const [reviewInfoSignal, setReviewInfoSignal] = useState(0);
  const [thinkingElapsedSeconds, setThinkingElapsedSeconds] = useState(0);
  const [sessionTodoTasks, setSessionTodoTasks] = useState<Record<string, ChatTask[]>>({});
  const [sessionQueues, setSessionQueues] = useState<Record<string, QueuedChatMessage[]>>({});
  const [pendingHitlItems, setPendingHitlItems] = useState<PendingHitlItem[]>([]);
  const sessionQueuesRef = useRef<Record<string, QueuedChatMessage[]>>({});
  const queueArmedSessionsRef = useRef(new Set<string>());
  const queueDrainingRef = useRef(false);
  const chatMessagesRef = useRef<{ scrollToBottom: () => void } | null>(null);
  const sessionIdRef = useRef<string>('');
  const conversationIdRef = useRef<string | number>('');
  const isStreamingRef = useRef(false);
  const streamingSessionIdRef = useRef<string | null>(null);
  const thinkingStartedAtBySessionRef = useRef(new Map<string, number>());
  const aiCreateRef = useRef<
    { type: 'skill'; name: string } | { type: 'agent' } | { type: 'automation'; options: SendOptions } | null
  >(null);
  const automationPromptSentRef = useRef<string | null>(null);
  const onUpdateSessionTitleRef = useRef(onUpdateSessionTitle);
  onUpdateSessionTitleRef.current = onUpdateSessionTitle;
  const onNewSessionRef = useRef(onNewSession);
  onNewSessionRef.current = onNewSession;
  const onSessionRunStateChangeRef = useRef(onSessionRunStateChange);
  onSessionRunStateChangeRef.current = onSessionRunStateChange;
  const onSessionMessageSavedRef = useRef(onSessionMessageSaved);
  onSessionMessageSavedRef.current = onSessionMessageSaved;
  const onCreateAutomationFromPromptRef = useRef(onCreateAutomationFromPrompt);
  onCreateAutomationFromPromptRef.current = onCreateAutomationFromPrompt;
  const onAiCreateCompleteRef = useRef(onAiCreateComplete);
  onAiCreateCompleteRef.current = onAiCreateComplete;
  const workspacePathRef = useRef(workspacePath);
  workspacePathRef.current = workspacePath;
  const backendPortRef = useRef(backendPort);
  backendPortRef.current = backendPort;
  const todoRefreshInFlightRef = useRef(new Set<string>());

  const commitQueue = useCallback((sessionId: string, items: QueuedChatMessage[]) => {
    const next = { ...sessionQueuesRef.current, [sessionId]: items };
    sessionQueuesRef.current = next;
    setSessionQueues(next);
    if (backendPort && sessionId && !sessionId.startsWith('temp-') && !sessionId.startsWith('pending-')) {
      void chatQueueService.save(backendPort, sessionId, items).catch(() => {
        // 保留内存队列；后端恢复失败不能导致运行中追加任务丢失。
      });
    }
  }, [backendPort]);

  useEffect(() => {
    const sessionId = currentConversation.id?.toString();
    if (!backendPort || !sessionId || sessionId.startsWith('temp-') || sessionId.startsWith('pending-')) return;
    if (Object.prototype.hasOwnProperty.call(sessionQueuesRef.current, sessionId)) return;
    let cancelled = false;
    chatQueueService.load(backendPort, sessionId).then(items => {
      if (cancelled) return;
      const next = { ...sessionQueuesRef.current, [sessionId]: items };
      sessionQueuesRef.current = next;
      setSessionQueues(next);
    }).catch(() => {
      if (cancelled) return;
      const next = { ...sessionQueuesRef.current, [sessionId]: [] };
      sessionQueuesRef.current = next;
      setSessionQueues(next);
    });
    return () => { cancelled = true; };
  }, [backendPort, currentConversation.id]);

  // 有序 segment 列表 �?保留 think/action/text 的真实交错顺�?
  type AccSegment =
    | { type: 'THINK'; text: string }
    | { type: 'TEXT'; text: string; agentName?: string }
    | { type: 'ACTION'; text: string; toolName?: string; args?: Record<string, unknown> };

  const accumulatedContentRef = useRef<AccSegment[]>([]);
  const backgroundContentBySessionRef = useRef(new Map<string, AccSegment[]>());
  const liveBaseMessagesBySessionRef = useRef(new Map<string, Message[]>());
  const liveUserMessageBySessionRef = useRef(new Map<string, Message>());
  const liveMessagesBySessionRef = useRef(new Map<string, Message[]>());

  // RAF 节流：流式更新时合并多次 chunk 到一帧渲�?
  const rafIdRef = useRef<number | null>(null);
  const pendingUpdateRef = useRef(false);
  type StreamQueueItem = {
    sessionId: string;
    type: 'THINK' | 'TEXT' | 'ACTION';
    chars: string[];
    index: number;
    agentName?: string;
    toolName?: string;
    args?: Record<string, unknown>;
    forceNewSegment?: boolean;
  };
  const streamQueueRef = useRef<StreamQueueItem[]>([]);
  const streamPumpTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const streamIdleResolversRef = useRef(new Map<string, Array<() => void>>());

  const scheduleMessageUpdate = useCallback(() => {
    if (pendingUpdateRef.current) return;
    pendingUpdateRef.current = true;
    rafIdRef.current = requestAnimationFrame(() => {
      rafIdRef.current = null;
      pendingUpdateRef.current = false;
      const contentItems = buildContentItems();
      const tempMsg: Message = {
        id: assistantMsgIdRef.current,
        role: 'ASSISTANT',
        timestamp: new Date().toLocaleTimeString(),
        contents: contentItems
      };
      setMessages(prev => {
        const existingIndex = prev.findIndex(m => m.id === assistantMsgIdRef.current);
        if (existingIndex >= 0) {
          const updated = [...prev];
          updated[existingIndex] = mergeStreamingMessage(updated[existingIndex], tempMsg);
          return updated;
        }
        return [...prev, tempMsg];
      });
    });
  }, []);

  function cancelScheduledMessageUpdate() {
    if (rafIdRef.current !== null) {
      cancelAnimationFrame(rafIdRef.current);
      rafIdRef.current = null;
    }
    pendingUpdateRef.current = false;
  }

  // 待持久化的首条用户消息（新会话时暂存，done/error 时真正保存）
  function isSessionVisible(sessionId: string) {
    const currentId = conversationIdRef.current?.toString();
    const selectedId = sessionIdRef.current;
    return sessionId === currentId || sessionId === selectedId;
  }

  function getSegmentsForSession(sessionId: string) {
    if (isSessionVisible(sessionId)) {
      return accumulatedContentRef.current;
    }
    let segments = backgroundContentBySessionRef.current.get(sessionId);
    if (!segments) {
      segments = [];
      backgroundContentBySessionRef.current.set(sessionId, segments);
    }
    return segments;
  }

  function getLiveUserMessage(sessionId: string): Message | null {
    const cached = liveUserMessageBySessionRef.current.get(sessionId);
    if (cached) return cached;

    const pending = pendingPersistBySessionRef.current.get(sessionId);
    if (!pending) return null;
    try {
      const contents = JSON.parse(pending.userMessage.contents);
      const message: Message = {
        id: Date.now(),
        role: 'USER',
        timestamp: pending.userMessage.timestamp,
        contents: Array.isArray(contents) ? contents : [],
      };
      liveUserMessageBySessionRef.current.set(sessionId, message);
      return message;
    } catch {
      return null;
    }
  }

  function buildLiveMessages(sessionId: string, segments = getSegmentsForSession(sessionId)) {
    const baseMessages = liveBaseMessagesBySessionRef.current.get(sessionId);
    const userMessage = getLiveUserMessage(sessionId);
    const liveMessages: Message[] = baseMessages ? [...baseMessages] : (userMessage ? [userMessage] : []);
    const cachedAssistant = liveMessagesBySessionRef.current
      .get(sessionId)
      ?.find(message => message.id === assistantMsgIdRef.current);

    const contentItems = buildContentItems(segments);
    if (contentItems.length > 0) {
      const assistantMessage: Message = {
        id: assistantMsgIdRef.current,
        role: 'ASSISTANT',
        timestamp: new Date().toLocaleTimeString(),
        contents: contentItems,
      };
      const existingIndex = liveMessages.findIndex(message => message.id === assistantMsgIdRef.current);
      const mergedAssistant = mergeStreamingMessage(cachedAssistant, assistantMessage);
      if (existingIndex >= 0) liveMessages[existingIndex] = mergedAssistant;
      else liveMessages.push(mergedAssistant);
    }

    if (liveMessages.length > 0) {
      liveMessagesBySessionRef.current.set(sessionId, liveMessages);
    }
    return liveMessages;
  }

  function restoreLiveMessages(sessionId: string) {
    const liveMessages = liveMessagesBySessionRef.current.get(sessionId) || buildLiveMessages(sessionId);
    setMessages(liveMessages);
    chatMessagesRef.current?.scrollToBottom();
    return liveMessages.length > 0;
  }

  function appendStreamChunk(item: StreamQueueItem, chunk: string) {
    const segments = getSegmentsForSession(item.sessionId);
    const last = segments.length > 0 ? segments[segments.length - 1] : null;
    if (item.type === 'THINK') {
      if (last && last.type === 'THINK' && !item.forceNewSegment) {
        last.text += chunk;
      } else {
        segments.push({ type: 'THINK', text: chunk });
        item.forceNewSegment = false;
      }
      buildLiveMessages(item.sessionId, segments);
      scheduleAssistantPersistence(item.sessionId);
      return;
    }
    if (item.type === 'ACTION') {
      if (last && last.type === 'ACTION' && !item.forceNewSegment) {
        last.text += chunk;
      } else {
        segments.push({ type: 'ACTION', text: chunk, toolName: item.toolName, args: item.args });
        item.forceNewSegment = false;
      }
      buildLiveMessages(item.sessionId, segments);
      scheduleAssistantPersistence(item.sessionId);
      return;
    }
    if (last && last.type === 'TEXT' && !item.forceNewSegment) {
      last.text += chunk;
      if (item.agentName) last.agentName = item.agentName;
    } else {
      segments.push({ type: 'TEXT', text: chunk, agentName: item.agentName });
      item.forceNewSegment = false;
    }
    buildLiveMessages(item.sessionId, segments);
    scheduleAssistantPersistence(item.sessionId);
  }

  function hasPendingQueuedChars(sessionId: string) {
    return streamQueueRef.current.some(item => item.sessionId === sessionId && item.index < item.chars.length);
  }

  function resolveStreamIdle(sessionId?: string) {
    const ids = sessionId ? [sessionId] : Array.from(streamIdleResolversRef.current.keys());
    ids.forEach(id => {
      if (hasPendingQueuedChars(id)) return;
      const resolvers = streamIdleResolversRef.current.get(id);
      if (!resolvers) return;
      streamIdleResolversRef.current.delete(id);
      resolvers.forEach(resolve => resolve());
    });
  }

  function pumpStreamQueue() {
    if (streamPumpTimerRef.current) return;

    const tick = () => {
      const item = streamQueueRef.current[0];
      if (!item) {
        streamPumpTimerRef.current = null;
        resolveStreamIdle();
        return;
      }

      const remaining = item.chars.length - item.index;
      const batchSize = Math.min(remaining, STREAM_BATCH_CHARS);
      const chunk = item.chars.slice(item.index, item.index + batchSize).join('');
      item.index += batchSize;
      if (chunk) {
        appendStreamChunk(item, chunk);
        if (isSessionVisible(item.sessionId)) {
          scheduleMessageUpdate();
        }
      }

      if (item.index >= item.chars.length) {
        const finishedSessionId = item.sessionId;
        streamQueueRef.current.shift();
        resolveStreamIdle(finishedSessionId);
      }

      streamPumpTimerRef.current = setTimeout(tick, STREAM_BATCH_INTERVAL_MS);
    };

    streamPumpTimerRef.current = setTimeout(tick, 0);
  }

  function enqueueStreamText(
    sessionId: string,
    type: 'THINK' | 'TEXT' | 'ACTION',
    text: string,
    options: Pick<StreamQueueItem, 'agentName' | 'toolName' | 'args' | 'forceNewSegment'> = {}
  ) {
    const chars = Array.from(text);
    if (chars.length === 0) return;
    streamQueueRef.current.push({
      sessionId,
      type,
      chars,
      index: 0,
      ...options,
    });
    pumpStreamQueue();
  }

  function waitForStreamQueueIdle(sessionId: string) {
    if (!hasPendingQueuedChars(sessionId)) return Promise.resolve();
    return new Promise<void>(resolve => {
      const resolvers = streamIdleResolversRef.current.get(sessionId) || [];
      resolvers.push(resolve);
      streamIdleResolversRef.current.set(sessionId, resolvers);
    });
  }

  function clearStreamQueue(sessionId?: string) {
    streamQueueRef.current = sessionId
      ? streamQueueRef.current.filter(item => item.sessionId !== sessionId)
      : [];
    if (streamPumpTimerRef.current && streamQueueRef.current.length === 0) {
      clearTimeout(streamPumpTimerRef.current);
      streamPumpTimerRef.current = null;
    }
    resolveStreamIdle(sessionId);
  }

  function pauseStreamPump() {
    if (streamPumpTimerRef.current) {
      clearTimeout(streamPumpTimerRef.current);
      streamPumpTimerRef.current = null;
    }
  }

  function clearLiveSession(sessionId: string) {
    backgroundContentBySessionRef.current.delete(sessionId);
    liveBaseMessagesBySessionRef.current.delete(sessionId);
    liveUserMessageBySessionRef.current.delete(sessionId);
    liveMessagesBySessionRef.current.delete(sessionId);
    thinkingStartedAtBySessionRef.current.delete(sessionId);
  }

  type PendingPersist = {
    sessionId: string;
    userMessage: { timestamp: string; contents: string };
    messageText: string;
    userMessageId?: number;
    options: SendOptions;
  };
  type AssistantDraftPersistence = {
    messageId?: number;
    lastContents?: string;
    metadata?: Message['metadata'];
    timer?: ReturnType<typeof setTimeout>;
    writeChain: Promise<void>;
  };
  const pendingPersistRef = useRef<PendingPersist | null>(null);
  const pendingPersistBySessionRef = useRef(new Map<string, PendingPersist>());
  const assistantDraftsBySessionRef = useRef(new Map<string, AssistantDraftPersistence>());
  const reconnectMessageIdsBySessionRef = useRef(new Map<string, number>());

  const clearReconnectMessage = useCallback((sessionId: string) => {
    const messageId = reconnectMessageIdsBySessionRef.current.get(sessionId);
    if (!messageId) return;
    reconnectMessageIdsBySessionRef.current.delete(sessionId);

    const liveMessages = liveMessagesBySessionRef.current.get(sessionId);
    if (liveMessages) {
      liveMessagesBySessionRef.current.set(
        sessionId,
        liveMessages.filter(message => message.id !== messageId),
      );
    }

    if (isSessionVisible(sessionId)) {
      setMessages(prev => prev.filter(message => message.id !== messageId));
    }
  }, []);

  const upsertReconnectMessage = useCallback((sessionId: string, text: string) => {
    const messageId = reconnectMessageIdsBySessionRef.current.get(sessionId)
      || Date.now() * 1000 + Math.floor(Math.random() * 1000);
    reconnectMessageIdsBySessionRef.current.set(sessionId, messageId);

    const reconnectMessage: Message = {
      id: messageId,
      role: 'ASSISTANT',
      timestamp: new Date().toLocaleTimeString(),
      contents: [{ type: 'ERROR', text }],
    };

    const liveMessages = liveMessagesBySessionRef.current.get(sessionId);
    if (liveMessages) {
      liveMessagesBySessionRef.current.set(
        sessionId,
        [...liveMessages.filter(message => message.id !== messageId), reconnectMessage],
      );
    }

    if (isSessionVisible(sessionId)) {
      setMessages(prev => [...prev.filter(message => message.id !== messageId), reconnectMessage]);
      requestAnimationFrame(() => chatMessagesRef.current?.scrollToBottom());
    }
  }, []);

  const appendResponseError = useCallback(async (sessionId: string, reason?: unknown) => {
    clearReconnectMessage(sessionId);
    const errorText = formatResponseErrorText(reason);
    const errorMsg: Message = {
      id: Date.now() * 1000 + Math.floor(Math.random() * 1000),
      role: 'ERROR',
      timestamp: new Date().toLocaleTimeString(),
      contents: [{ type: 'ERROR', text: errorText }],
    };
    const isCurrentSession = sessionId === conversationIdRef.current.toString()
      || sessionId === sessionIdRef.current;

    if (isCurrentSession) {
      setMessages(prev => {
        const last = prev[prev.length - 1];
        if (last?.role === 'ERROR' && last.contents[0]?.text === errorText) return prev;
        return [...prev, errorMsg];
      });
      requestAnimationFrame(() => chatMessagesRef.current?.scrollToBottom());
    }

    try {
      await withRetry(() => saveMessage({
        conversationId: sessionId,
        role: 'ERROR',
        timestamp: errorMsg.timestamp,
        contents: JSON.stringify(errorMsg.contents),
        workspacePath: workspacePathRef.current,
      }));
      onSessionMessageSavedRef.current?.(sessionId, 1);
    } catch (error) {
      console.error('[ChatView] 保存错误消息失败:', error);
    }

    return errorMsg;
  }, [clearReconnectMessage]);

  // 当前 assistant 消息 ID
  const assistantMsgIdRef = useRef<number>(0);

  // 加载超时计时器：收到消息时重置，120秒无新消息自动停�?
  const loadingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const responseTimeoutRetriesRef = useRef(new Map<string, number>());

  const startLoadingTimer = useCallback(() => {
    if (loadingTimerRef.current) clearTimeout(loadingTimerRef.current);

    function armTimer() {
      loadingTimerRef.current = setTimeout(handleTimeout, RESPONSE_TIMEOUT_MS);
    }

    function handleTimeout() {
      const timedOutSessionId = streamingSessionIdRef.current;
      if (!timedOutSessionId) return;

      const retries = responseTimeoutRetriesRef.current.get(timedOutSessionId) || 0;
      if (retries < RESPONSE_TIMEOUT_MAX_RETRIES) {
        const nextRetry = retries + 1;
        responseTimeoutRetriesRef.current.set(timedOutSessionId, nextRetry);
        console.warn(`[ChatView] Response timeout, reconnecting (${nextRetry}/${RESPONSE_TIMEOUT_MAX_RETRIES})`);
        if (WebSocketManager.getInstance().retrySession(timedOutSessionId)) {
          armTimer();
          return;
        }
      }

      console.error(`[ChatView] Response timeout after ${RESPONSE_TIMEOUT_MAX_RETRIES} retries, auto-stopping`);
      responseTimeoutRetriesRef.current.delete(timedOutSessionId);
      if (timedOutSessionId) {
        WebSocketManager.getInstance().cancelSession(timedOutSessionId);
        clearStreamQueue(timedOutSessionId);
        pendingPersistBySessionRef.current.delete(timedOutSessionId);
        if (pendingPersistRef.current?.sessionId === timedOutSessionId) {
          pendingPersistRef.current = null;
        }
        void flushAssistantPersistence(timedOutSessionId)
          .then(() => appendResponseError(timedOutSessionId, RESPONSE_TIMEOUT_ERROR));
        clearLiveSession(timedOutSessionId);
      }
      setIsLoading(false);
      isStreamingRef.current = false;
      streamingSessionIdRef.current = null;
      if (timedOutSessionId) {
        onSessionRunStateChangeRef.current?.(timedOutSessionId, 'error', RESPONSE_TIMEOUT_ERROR);
      }
    }

    armTimer();
  }, [appendResponseError]);

  const clearLoadingTimer = useCallback(() => {
    if (loadingTimerRef.current) {
      clearTimeout(loadingTimerRef.current);
      loadingTimerRef.current = null;
    }
  }, []);
  const refreshSessionTodos = useCallback(async (sessionId: string) => {
    const port = backendPortRef.current;
    if (!port || !sessionId || sessionId.startsWith('temp-') || sessionId.startsWith('pending-')) return;
    if (todoRefreshInFlightRef.current.has(sessionId)) return;
    todoRefreshInFlightRef.current.add(sessionId);
    try {
      const response = await fetch(`http://localhost:${port}/web/chat/todos?sessionId=${encodeURIComponent(sessionId)}`, { cache: 'no-store' });
      if (!response.ok) return;
      const payload = await response.json();
      setSessionTodoTasks(previous => ({
        ...previous,
        [sessionId]: mapTodoApiItems(payload?.data?.items || []),
      }));
    } catch {
      // 工具事件的乐观更新仍然保留；下一轮轮询会继续校准。
    } finally {
      todoRefreshInFlightRef.current.delete(sessionId);
    }
  }, []);

  const updateSessionTodosFromTool = useCallback((sessionId: string, toolName?: string, text?: string, args?: Record<string, unknown>) => {
    if (!sessionId || !isTodoToolName(toolName)) return;
    const todosArg = args?.todos;
    const markdown = typeof todosArg === 'string' && todosArg.trim() ? todosArg : (text || '');
    if (markdown.trim()) {
      setSessionTodoTasks(prev => ({
        ...prev,
        [sessionId]: parseTodoMarkdown(markdown),
      }));
    }
    void refreshSessionTodos(sessionId);
  }, [refreshSessionTodos]);

  // 更新 ref（流式输出期间不更新，避�?temp→real ID 切换导致 WS 回调丢消息）
  useEffect(() => {
    if (!currentConversation.id) return;
    const previousId = sessionIdRef.current;
    if (previousId && streamingSessionIdRef.current === previousId) {
      backgroundContentBySessionRef.current.set(previousId, accumulatedContentRef.current);
    }
    const nextId = currentConversation.id.toString();
    sessionIdRef.current = nextId;
    conversationIdRef.current = currentConversation.id;
    if (streamingSessionIdRef.current === nextId) {
      accumulatedContentRef.current = backgroundContentBySessionRef.current.get(nextId) || accumulatedContentRef.current;
    } else {
      accumulatedContentRef.current = [];
    }
  }, [currentConversation.id]);

  // 构建当前累积内容�?ContentItem 数组 �?直接映射有序 segment
  function buildContentItems(segments = accumulatedContentRef.current): ContentItem[] {
    return segments
      .filter(seg => seg.text.trim())
      .map(seg => {
        if (seg.type === 'THINK') {
          return { type: 'THINK' as const, text: seg.text.trim() };
        }
        if (seg.type === 'ACTION') {
          return {
            type: 'ACTION' as const,
            text: seg.text.trim(),
            toolName: seg.toolName,
            args: seg.args,
          };
        }
        // TEXT �?过滤末尾模型 trace
        let text = seg.text.trim();
        text = text.replace(/`\s*\([\w.\-]+(?:,\s*\d+\.?\d*\w+)*\)\s*`\s*$/, '');
        text = text.replace(/\([\w.\-]+(?:,\s*\d+\.?\d*\w+)*\)\s*$/, '');
        return { type: 'TEXT' as const, text, agentName: seg.agentName };
      })
      .filter(item => item.text.length > 0);
  }

  function getAssistantDraftState(sessionId: string) {
    let state = assistantDraftsBySessionRef.current.get(sessionId);
    if (!state) {
      state = { writeChain: Promise.resolve() };
      assistantDraftsBySessionRef.current.set(sessionId, state);
    }
    return state;
  }

  async function writeAssistantSnapshot(sessionId: string, state: AssistantDraftPersistence) {
    const segments = isSessionVisible(sessionId)
      ? accumulatedContentRef.current
      : (backgroundContentBySessionRef.current.get(sessionId) || []);
    const items = buildContentItems(segments);
    if (items.length === 0) return;
    const contents = JSON.stringify({ items, metadata: state.metadata });
    if (contents === state.lastContents) return;

    if (state.messageId) {
      await withRetry(() => updateMessage(state.messageId!, {
        contents,
      }));
    } else {
      state.messageId = await withRetry(() => saveMessage({
        conversationId: sessionId,
        role: 'ASSISTANT',
        timestamp: new Date().toLocaleTimeString(),
        contents,
        workspacePath: workspacePathRef.current,
      }));
      onSessionMessageSavedRef.current?.(sessionId, 1);
    }
    state.lastContents = contents;
  }

  function scheduleAssistantPersistence(sessionId: string) {
    const state = getAssistantDraftState(sessionId);
    if (state.timer) return;
    const delay = state.messageId ? 200 : 0;
    state.timer = setTimeout(() => {
      state.timer = undefined;
      state.writeChain = state.writeChain
        .then(() => writeAssistantSnapshot(sessionId, state!))
        .catch(error => console.error('[ChatView] 增量保存助手消息失败:', error));
    }, delay);
  }

  async function flushAssistantPersistence(sessionId: string, metadata?: Message['metadata']) {
    const state = getAssistantDraftState(sessionId);
    state.metadata = metadata || state.metadata;
    if (state.timer) {
      clearTimeout(state.timer);
      state.timer = undefined;
    }
    state.writeChain = state.writeChain.then(() => writeAssistantSnapshot(sessionId, state));
    try {
      await state.writeChain;
    } catch (error) {
      console.error('[ChatView] 保存助手消息最终状态失败:', error);
    } finally {
      assistantDraftsBySessionRef.current.delete(sessionId);
    }
  }

  // 注册消息回调（只注册一次，通过 ref 获取当前 sessionId�?
  useEffect(() => {
    const wsManager = WebSocketManager.getInstance();

    // 持久化待保存的用户消息（仅保存消息，不触发会话持久化�?
    // 返回 pending 信息�?done/error 后触发会话持久化
    async function flushPendingUserMessage(sessionId?: string): Promise<{ sessionId: string; title: string; wasNew: boolean; options: SendOptions } | null> {
      const pending = sessionId
        ? pendingPersistBySessionRef.current.get(sessionId)
        : pendingPersistRef.current;
      if (!pending) return null;
      pendingPersistBySessionRef.current.delete(pending.sessionId);
      if (pendingPersistRef.current?.sessionId === pending.sessionId) {
        pendingPersistRef.current = null;
      }

      if (!pending.userMessageId) {
        pending.userMessageId = await withRetry(() => saveMessage({
          conversationId: pending.sessionId,
          role: 'USER',
          timestamp: pending.userMessage.timestamp,
          contents: pending.userMessage.contents,
          workspacePath: workspacePathRef.current,
        }));
        onSessionMessageSavedRef.current?.(pending.sessionId, 1);
      }

      return {
        sessionId: pending.sessionId,
        title: pending.messageText.trim().slice(0, 20) + (pending.messageText.trim().length > 20 ? '...' : ''),
        wasNew: pending.sessionId.startsWith('temp-'),
        options: pending.options,
      };
    }

    const handleMessage = async (data: any) => {
      const msgSessionId = (data.sessionId || conversationIdRef.current.toString()).toString();
      const isCurrentSession = msgSessionId === conversationIdRef.current.toString() || msgSessionId === sessionIdRef.current;

      if (data.type === 'reconnecting') {
        const attempt = Number(data.attempt) || 1;
        const maxAttempts = Number(data.maxAttempts) || WS_RECONNECT_DELAYS_MS.length;
        upsertReconnectMessage(msgSessionId, data.text || `正在重连 ${attempt}/${maxAttempts}...`);
        return;
      }

      if (data.type === 'reconnected') {
        clearReconnectMessage(msgSessionId);
        if (isCurrentSession) startLoadingTimer();
        return;
      }

      if (data.type === 'reconnect_cleared') {
        clearReconnectMessage(msgSessionId);
        return;
      }

      clearReconnectMessage(msgSessionId);
      // 任意有效消息都说明回答流已经恢复；下一次连续无响应重新计算 3 次机会。
      responseTimeoutRetriesRef.current.delete(msgSessionId);

      // done / error 类型必须处理，不�?session 校验限制（保�?loading 状态正确）
      if (data.type === 'done') {
        setPendingHitlItems(prev => prev.filter(item => item.sessionId !== msgSessionId));
        void refreshSessionTodos(msgSessionId);
        if (!isCurrentSession) {
          await waitForStreamQueueIdle(msgSessionId);
          const pending = await flushPendingUserMessage(msgSessionId);
          const backgroundSegments = backgroundContentBySessionRef.current.get(msgSessionId) || [];
          const contentItems = buildContentItems(backgroundSegments);
          await flushAssistantPersistence(msgSessionId, contentItems.length > 0 ? {
            modelName: data.modelName,
            totalTokens: data.totalTokens,
            elapsedMs: data.elapsedMs,
          } : undefined);
          if (contentItems.length === 0) {
            const errorSessionId = pending?.sessionId || msgSessionId;
            await appendResponseError(errorSessionId, EMPTY_RESPONSE_ERROR);
            onSessionRunStateChangeRef.current?.(errorSessionId, 'error', EMPTY_RESPONSE_ERROR);
          }
          if (pending?.wasNew && onUpdateSessionTitleRef.current) {
            onUpdateSessionTitleRef.current(pending.sessionId, pending.title);
          }
          clearLiveSession(msgSessionId);
          if (streamingSessionIdRef.current === msgSessionId) {
            streamingSessionIdRef.current = null;
            isStreamingRef.current = false;
            setIsLoading(false);
          }
          return;
        }
        clearLoadingTimer();
        await waitForStreamQueueIdle(msgSessionId);
        // 最后一批字符会安排 RAF；先取消它，再提交带统计信息的权威最终消息。
        cancelScheduledMessageUpdate();

        // 持久化用户消息（如果是新会话�?
        const pending = await flushPendingUserMessage(msgSessionId);

        // 构建最终消�?
        const contentItems = buildContentItems();
        if (contentItems.length > 0) {
          const finalMsg: Message = {
            id: assistantMsgIdRef.current,
            role: 'ASSISTANT',
            timestamp: new Date().toLocaleTimeString(),
            contents: contentItems,
            metadata: {
              modelName: data.modelName,
              totalTokens: data.totalTokens,
              elapsedMs: data.elapsedMs,
            }
          };

          setMessages(prev => {
            const filtered = prev.filter(m => m.id !== assistantMsgIdRef.current);
            return [...filtered, finalMsg];
          });
          const cachedLiveMessages = liveMessagesBySessionRef.current.get(msgSessionId);
          if (cachedLiveMessages) {
            const filtered = cachedLiveMessages.filter(message => message.id !== assistantMsgIdRef.current);
            liveMessagesBySessionRef.current.set(msgSessionId, [...filtered, finalMsg]);
          }

          await flushAssistantPersistence(msgSessionId, finalMsg.metadata);
        } else {
          await flushAssistantPersistence(msgSessionId);
          const errorSessionId = pending?.sessionId || msgSessionId;
          await appendResponseError(errorSessionId, EMPTY_RESPONSE_ERROR);
          onSessionRunStateChangeRef.current?.(errorSessionId, 'error', EMPTY_RESPONSE_ERROR);
        }

        // 所有消息保存后，触发会话持久化（reassignMessages 会把 temp ID 转为 real ID�?
        if (pending?.wasNew && onUpdateSessionTitleRef.current) {
          onUpdateSessionTitleRef.current(pending.sessionId, pending.title);
        }

        // AI 创建自动保存
        if (aiCreateRef.current) {
          const creation = aiCreateRef.current;
          const { type } = creation;
          const aiContent = accumulatedContentRef.current
            .filter(seg => seg.type === 'TEXT')
            .map(seg => seg.text.trim())
            .join('\n')
            .trim();
          if (aiContent) {
            try {
              if (type === 'skill') {
                const { name } = creation;
                await invoke('create_skill', { name, description: '', content: aiContent });
                onAiCreateCompleteRef.current?.({ type, name });
              } else if (type === 'agent') {
                const generatedName = extractGeneratedAgentName(aiContent);
                if (!generatedName) {
                  throw new Error('AI 未生成有效的 Agent 名称');
                }
                const name = await createUniqueAgentName(generatedName);
                const content = applyGeneratedAgentName(aiContent, name);
                await invoke('create_agent', { name, description: '', content });
                onAiCreateCompleteRef.current?.({ type, name });
              } else {
                const plan = parseGeneratedAutomationPlan(aiContent);
                if (!onCreateAutomationFromPromptRef.current) throw new Error('自动化保存处理器不可用');
                await onCreateAutomationFromPromptRef.current(plan, creation.options);
              }
            } catch (err) {
              console.error('[ChatView] AI 创建自动保存失败:', err);
              const displayName = type === 'skill' ? creation.name : type === 'agent' ? '自动命名 Agent' : '自动化';
              onAiCreateCompleteRef.current?.({ type, name: displayName, error: String(err) });
            }
          } else {
            const displayName = type === 'skill' ? creation.name : type === 'agent' ? '自动命名 Agent' : '自动化';
            onAiCreateCompleteRef.current?.({ type, name: displayName, error: 'AI 未返回可保存的内容' });
          }
          aiCreateRef.current = null;
        }

        // 重置累积�?        accumulatedContentRef.current = [];
        clearLiveSession(msgSessionId);
        cancelScheduledMessageUpdate();

        setIsLoading(false);
        isStreamingRef.current = false;
        if (streamingSessionIdRef.current === msgSessionId) {
          streamingSessionIdRef.current = null;
        }
        if (pending?.options.mode === 'plan') {
          setPlanApproval({
            sessionId: msgSessionId,
            options: { ...pending.options, mode: 'default', contexts: [], attachments: [] },
          });
        }
        chatMessagesRef.current?.scrollToBottom();
        return;
      }

      if (data.type === 'error') {
        setPendingHitlItems(prev => prev.filter(item => item.sessionId !== msgSessionId));
        void refreshSessionTodos(msgSessionId);
        if (aiCreateRef.current) {
          const creation = aiCreateRef.current;
          aiCreateRef.current = null;
          const displayName = creation.type === 'skill'
            ? creation.name
            : (creation.type === 'agent' ? '自动命名 Agent' : '自动化');
          onAiCreateCompleteRef.current?.({
            type: creation.type,
            name: displayName,
            error: data.text || '生成请求失败',
          });
        }
        if (!isCurrentSession) {
          pauseStreamPump();
          const pending = await flushPendingUserMessage(msgSessionId);
          await flushAssistantPersistence(msgSessionId);
          await appendResponseError(pending?.sessionId || msgSessionId, data.text);
          if (pending?.wasNew && onUpdateSessionTitleRef.current) {
            onUpdateSessionTitleRef.current(pending.sessionId, pending.title);
          }
          if (streamingSessionIdRef.current === msgSessionId) {
            clearLoadingTimer();
            streamingSessionIdRef.current = null;
            isStreamingRef.current = false;
            setIsLoading(false);
          }
          onSessionRunStateChangeRef.current?.(msgSessionId, 'error', data.text || '会话执行失败');
          return;
        }
        clearLoadingTimer();
        pauseStreamPump();

        // 即使出错也要持久化用户消息
        const pending = await flushPendingUserMessage(msgSessionId);
        await flushAssistantPersistence(msgSessionId);

        await appendResponseError(pending?.sessionId || msgSessionId, data.text);

        // 所有消息保存后，触发会话持久化
        if (pending?.wasNew && onUpdateSessionTitleRef.current) {
          onUpdateSessionTitleRef.current(pending.sessionId, pending.title);
        }

        setIsLoading(false);
        isStreamingRef.current = false;
        if (streamingSessionIdRef.current === msgSessionId) {
          streamingSessionIdRef.current = null;
        }
        onSessionRunStateChangeRef.current?.(msgSessionId, 'error', data.text || '会话执行失败');
        return;
      }

      if (data.type === 'goal_status') {
        window.dispatchEvent(new CustomEvent('soloncode-goal-updated', {
          detail: { sessionId: msgSessionId, task: data },
        }));
        const status = String(data.status || '').toUpperCase();
        if (status === 'PURSUING') {
          onSessionRunStateChangeRef.current?.(msgSessionId, 'running');
        } else if (status === 'PAUSED' || status === 'BLOCKED') {
          onSessionRunStateChangeRef.current?.(msgSessionId, 'completed');
        }
        if (isCurrentSession) {
          if (status === 'PURSUING') {
            isStreamingRef.current = true;
            streamingSessionIdRef.current = msgSessionId;
            setIsLoading(true);
            startLoadingTimer();
          } else if (status === 'PAUSED' || status === 'BLOCKED') {
            clearLoadingTimer();
            setIsLoading(false);
            void flushAssistantPersistence(msgSessionId);
          }
        }
        return;
      }

      if (data.type === 'goal_round') {
        if (getSegmentsForSession(msgSessionId).length > 0) {
          enqueueStreamText(msgSessionId, 'TEXT', '\n\n', { forceNewSegment: true });
        }
        if (isCurrentSession) {
          startLoadingTimer();
        }
        return;
      }

      // 其他消息类型检查是否属于当前会话（同时接受 temp ID 和重分配后的真实 ID�?
      // HITL 审批请求：显示在输入框上方，避免混入消息正文。
      if (data.type === 'hitl') {
        if (!isCurrentSession) return;
        clearLoadingTimer();
        const toolName = String(data.toolName || '');
        const workspace = workspacePathRef.current || '';
        const callId = data.callId ? String(data.callId) : undefined;
        if (await permissionService.isAlwaysAllowed(workspace, toolName)) {
          await permissionService.audit({
            sessionId: msgSessionId,
            workspacePath: workspace,
            toolName,
            action: 'auto_approve',
            command: data.command,
          }).catch(() => undefined);
          await sendHitlDecision(msgSessionId, 'approve', undefined, callId);
          startLoadingTimer();
          return;
        }
        const hitlItem: ContentItem = {
          type: 'HITL',
          text: data.comment || data.text || '',
          toolName: data.toolName,
          command: data.command,
          callId,
        };
        const id = callId || `${msgSessionId}:${toolName}:${data.command || ''}`;
        setPendingHitlItems(prev => {
          const next = prev.filter(pending => pending.id !== id);
          next.push({ id, sessionId: msgSessionId, item: hitlItem });
          return next;
        });
        return;
      }

      const rawType = (data.type as string).toUpperCase();
      const type = (
        rawType === 'COMMAND'
          ? 'TEXT'
          : (rawType === 'ACTION_START' || rawType === 'ACTION_END' ? 'ACTION' : rawType)
      ) as ContentType;
      let text = filterEmptyTags(data.text || '');
      if (rawType === 'COMMAND') text += '\n';
      if (type === 'ACTION' && isTodoToolName(data.toolName)) {
        updateSessionTodosFromTool(msgSessionId, data.toolName, text, data.args);
      }

      // action_start 只表示工具开始执行，结果由匹配的 action_end 承载。
      // 先刷新超时计时，避免长工具调用期间被误判为无响应。
      if (rawType === 'ACTION_START') {
        if (isCurrentSession) startLoadingTimer();
        return;
      }

      if (rawType === 'ACTION_END') {
      }

      if (text === '') return;

      // 收到任何内容消息，重置加载超时计时器
      if (isCurrentSession) startLoadingTimer();
      if (isCurrentSession) {
        switch (type) {
          case 'THINK':
            enqueueStreamText(msgSessionId, 'THINK', text);
            break;
          case 'TEXT':
          case 'REASON': // 兼容旧 desktop 后端：reason 曾用于普通正文。
            enqueueStreamText(msgSessionId, 'TEXT', text, { agentName: data.agentName });
            break;
          case 'ACTION':
            enqueueStreamText(msgSessionId, 'ACTION', text, {
              toolName: data.toolName,
              args: data.args,
              forceNewSegment: Boolean(data.toolName),
            });
            break;
        }
        return;
      }

      // 累积内容
      // 累积内容 �?保留交错顺序
      let segs = accumulatedContentRef.current;
      if (!isCurrentSession) {
        segs = backgroundContentBySessionRef.current.get(msgSessionId) || [];
        backgroundContentBySessionRef.current.set(msgSessionId, segs);
      }
      const last = segs.length > 0 ? segs[segs.length - 1] : null;

      switch (type) {
        case 'THINK':
          if (last && last.type === 'THINK') {
            last.text += text;
          } else {
            segs.push({ type: 'THINK', text });
          }
          break;
        case 'TEXT':
        case 'REASON': // 兼容旧 desktop 后端：reason 曾用于普通正文。
          if (last && last.type === 'TEXT') {
            last.text += text;
            if (data.agentName) last.agentName = data.agentName;
          } else {
            segs.push({ type: 'TEXT', text, agentName: data.agentName });
          }
          break;
        case 'ACTION':
          if (data.toolName) {
            segs.push({ type: 'ACTION', text, toolName: data.toolName, args: data.args });
          } else if (last && last.type === 'ACTION') {
            last.text += text;
          } else {
            segs.push({ type: 'ACTION', text });
          }
          break;
      }
      scheduleAssistantPersistence(msgSessionId);

      // 实时更新显示（RAF 节流，合并多�?chunk�?
      if (isCurrentSession) {
        // Updates are driven by the character pump.
      }
    };

    wsManager.registerCallback(handleMessage);
    wsManager.registerStatusCallback((sessionId, status, error) => {
      onSessionRunStateChangeRef.current?.(sessionId, status, error);
    });

    return () => {
      wsManager.unregisterCallback();
    };
  }, []);

  const sendMessage = useCallback(async (messageText: string, options: SendOptions, requestText?: string, requestTextIsComplete = false) => {
    let sessionId = currentConversation.id?.toString();
    const displayMessageText = options.displayText?.trim() || messageText;

    // 无会话时，创建新会话（标题取消息�?0字），然后继续发�?
    if (!sessionId) {
      if (!onNewSession) return;
      const title = messageText.trim().slice(0, 20) + (messageText.trim().length > 20 ? '...' : '');
      sessionId = onNewSession(title);
      sessionIdRef.current = sessionId;
      conversationIdRef.current = sessionId;
    }

    let fullMessage = requestText || messageText;
    const contextParts: string[] = [];

    if (!requestTextIsComplete) {
      if (activeFilePath) {
        const activeFileContext = await buildActiveFileContext(activeFilePath, workspacePath);
        if (activeFileContext) contextParts.push(activeFileContext);
      }

      if (options.contexts.length > 0) {
        contextParts.push(options.contexts.map(c => `[${c.name}]`).join(' '));
      }

      if (contextParts.length > 0) {
        fullMessage = `${contextParts.join('\n\n')}\n\n${fullMessage}`;
      }

      // 拼接文本附件内容（图片通过 attachments 字段单独发送）
      if (options.mode !== 'goal' && options.attachments && options.attachments.length > 0) {
        const textParts = options.attachments
          .filter(att => att.type === 'text')
          .map(att => `--- 文件: ${att.name} ---\n${att.content}\n---`);
        if (textParts.length > 0) {
          fullMessage = `${textParts.join('\n\n')}\n\n${fullMessage}`;
        }
      }
    }

    // 第一条消息发送时才把临时会话写入数据库，并切换为正式 ID。
    if (sessionId.startsWith('temp-') && onUpdateSessionTitle) {
      const trimmedMessage = messageText.trim();
      const title = trimmedMessage.slice(0, 20) + (trimmedMessage.length > 20 ? '...' : '');
      const persistedSessionId = await onUpdateSessionTitle(sessionId, title);
      if (persistedSessionId) sessionId = persistedSessionId;
      sessionIdRef.current = sessionId;
      conversationIdRef.current = sessionId;
    }

    const userMessage: Message = {
      id: Date.now(),
      role: 'USER',
      timestamp: new Date().toLocaleTimeString(),
      contents: buildUserMessageContents(displayMessageText, options.attachments),
    };

    setMessages(prev => {
      const nextMessages = [...prev, userMessage];
      liveBaseMessagesBySessionRef.current.set(sessionId!, nextMessages);
      liveMessagesBySessionRef.current.set(sessionId!, nextMessages);
      return nextMessages;
    });

    // 标记流式状态，防止会话 ID 变化时重新加载消�?
    isStreamingRef.current = true;
    streamingSessionIdRef.current = sessionId!;
    thinkingStartedAtBySessionRef.current.set(sessionId!, Date.now());
    responseTimeoutRetriesRef.current.delete(sessionId!);

    setIsLoading(true);
    startLoadingTimer(); // 开始超时计�?

    // 重置累积器
    accumulatedContentRef.current = [];
    clearStreamQueue(sessionId!);
    const streamingSegments: AccSegment[] = [];
    accumulatedContentRef.current = streamingSegments;
    backgroundContentBySessionRef.current.set(sessionId!, streamingSegments);
    liveUserMessageBySessionRef.current.set(sessionId!, userMessage);
    if (!liveBaseMessagesBySessionRef.current.has(sessionId!)) {
      liveBaseMessagesBySessionRef.current.set(sessionId!, [userMessage]);
      liveMessagesBySessionRef.current.set(sessionId!, [userMessage]);
    }

    assistantMsgIdRef.current = Date.now() + Math.floor(Math.random() * 1000);

    chatMessagesRef.current?.scrollToBottom();

    // 暂存用户消息信息，等 done/error 时再真正持久�?
    const pendingPersist = {
      sessionId: sessionId!,
      userMessage: {
        timestamp: userMessage.timestamp,
        contents: JSON.stringify(userMessage.contents),
      },
      messageText,
      options,
    };
    pendingPersistRef.current = pendingPersist;
    pendingPersistBySessionRef.current.set(sessionId!, pendingPersist);

    try {
      const wsManager = WebSocketManager.getInstance();

      if (workspacePath) {
        await checkpointService.create(workspacePath, `Agent 执行前 · ${new Date().toLocaleString('zh-CN')}`).catch(() => undefined);
      }

      pendingPersist.userMessageId = await withRetry(() => saveMessage({
        conversationId: pendingPersist.sessionId,
        role: 'USER',
        timestamp: pendingPersist.userMessage.timestamp,
        contents: pendingPersist.userMessage.contents,
        workspacePath,
      }));
      onSessionMessageSavedRef.current?.(pendingPersist.sessionId, 1);
      getAssistantDraftState(pendingPersist.sessionId);

      // 开启会话时注册模型到后�?
      // options.model 格式: "providerId" �?"providerId__modelId"
      const sepIdx = options.model.indexOf('__');
      const providerId = sepIdx >= 0 ? options.model.substring(0, sepIdx) : options.model;
      const specificModelId = sepIdx >= 0 ? options.model.substring(sepIdx + 2) : null;
      const selectedProvider = providers.find(p => p.id === providerId);
      // 优先使用 availableModels 展开后的具体模型 ID，否则用 provider 默认 model
      const actualModelId = specificModelId || selectedProvider?.model || options.modelName;
      const selectedModelInfo = specificModelId
        ? selectedProvider?.availableModels?.find(m => m.id === specificModelId)
        : undefined;
      if (selectedProvider) {
        await registerModelToBackend({
          ...selectedProvider,
          model: actualModelId,
          contextLength: selectedModelInfo?.contextLength || selectedProvider.contextLength,
        });
      }

      // 用实际模型名发�?
      const modelName = actualModelId;

      const request: Record<string, unknown> = {
        input: fullMessage,
        sessionId: sessionId,
        model: modelName,
        agent: options.agent,
        cwd: workspacePath || undefined,
        maxSteps,
        reasoningEffort: options.reasoningEffort,
        mode: options.mode,
        goalMaxTokens: options.mode === 'goal' ? options.goalMaxTokens : undefined,
        goalMaxDurationMinutes: options.mode === 'goal' ? options.goalMaxDurationMinutes : undefined,
        goalMaxIterations: options.mode === 'goal' ? options.goalMaxIterations : undefined,
        goalObjective: options.mode === 'goal' ? messageText.trim() : undefined,
      };

      // 附件数据（图�?base64，文本内容）
      if (options.attachments && options.attachments.length > 0) {
        const uploadAttachments = options.attachments.filter(att => options.mode === 'goal' || att.type !== 'text');
        if (uploadAttachments.length > 0) request.attachments = uploadAttachments.map(att => {
          if (att.type === 'text') {
            return {
              type: 'file',
              name: att.name,
              data: att.content,
              mimeType: att.mimeType || 'text/plain',
              encoding: 'text',
            };
          }
          if (att.type === 'image') {
            // content 鏄?data URL: "data:image/png;base64,..."
            const match = att.content.match(/^data:([^;]+);base64,(.+)$/);
            return {
              type: 'image',
              name: att.name,
              data: match ? match[2] : att.content,
              mimeType: match ? match[1] : 'image/png',
              encoding: 'base64',
            };
          }
          return {
            type: 'file',
            name: att.name,
            data: att.content,
            mimeType: att.mimeType || 'application/octet-stream',
            encoding: 'base64',
          };
        });
      }

      await wsManager.sendMessage(request);

    } catch (error) {
      console.error('Failed to send message:', error);
      const sendErrorText = `请求失败：${error instanceof Error ? error.message : '未知错误'}`;

      // WS 连接失败时不会有 done/error 回调，直接在此持久化
      const pending = pendingPersistBySessionRef.current.get(sessionId!) || pendingPersistRef.current;
      if (pending) {
        pendingPersistBySessionRef.current.delete(pending.sessionId);
        if (pendingPersistRef.current?.sessionId === pending.sessionId) pendingPersistRef.current = null;
        if (!pending.userMessageId) {
          pending.userMessageId = await withRetry(() => saveMessage({
            conversationId: pending.sessionId,
            role: 'USER',
            timestamp: pending.userMessage.timestamp,
            contents: pending.userMessage.contents,
            workspacePath,
          }));
          onSessionMessageSaved?.(pending.sessionId, 1);
        }
        await flushAssistantPersistence(pending.sessionId);
        await appendResponseError(pending.sessionId, sendErrorText);

        // 触发会话持久化（reassignMessages 会处�?temp→real�?
        if (pending.sessionId.startsWith('temp-') && onUpdateSessionTitle) {
          onUpdateSessionTitle(pending.sessionId, pending.messageText.trim().slice(0, 20) + (pending.messageText.trim().length > 20 ? '...' : ''));
        }
      } else {
        await appendResponseError(sessionId!, sendErrorText);
      }

      clearLiveSession(sessionId!);
      setIsLoading(false);
      isStreamingRef.current = false;
      if (streamingSessionIdRef.current === sessionId) {
        streamingSessionIdRef.current = null;
      }
      if (aiCreateRef.current) {
        const creation = aiCreateRef.current;
        aiCreateRef.current = null;
        const displayName = creation.type === 'skill'
          ? creation.name
          : (creation.type === 'agent' ? '自动命名 Agent' : '自动化');
        onAiCreateCompleteRef.current?.({
          type: creation.type,
          name: displayName,
          error: '生成请求失败',
        });
      }
      onSessionRunStateChangeRef.current?.(
        sessionId!,
        'error',
        sendErrorText,
      );
    }
  }, [appendResponseError, backendPort, currentConversation, onAiCreateComplete, onNewSession, onUpdateSessionTitle, workspacePath, providers, activeFilePath, maxSteps]);

  // 自动化：在绑定项目的新会话中，使用创建时保存的模型与推理等级发送提示词。
  useEffect(() => {
    if (!automationPrompt || automationPromptSentRef.current === automationPrompt.runId) return;
    const conversationId = currentConversation.id?.toString();
    if (!conversationId || conversationId !== automationPrompt.sessionId) return;

    automationPromptSentRef.current = automationPrompt.runId;
    void sendMessage(automationPrompt.prompt, {
      model: automationPrompt.modelId,
      modelName: automationPrompt.modelName,
      agent: '',
      contexts: [],
      attachments: [],
      reasoningEffort: automationPrompt.reasoningEffort,
      mode: 'default',
    }).finally(() => onAutomationPromptConsumed?.(automationPrompt.runId));
  }, [automationPrompt, currentConversation.id, onAutomationPromptConsumed, sendMessage]);

  const handleChatInputSend = useCallback((message: string, options: SendOptions) => {
    if (options.mode !== 'plan') setPlanApproval(null);
    const sessionId = currentConversation.id?.toString();
    const activeCreation = promptCreation?.sessionId === sessionId
      ? promptCreation
      : null;
    if (!activeCreation) {
      const running = Boolean(sessionId) && (
        sessionRunStates[sessionId!] === 'running'
        || (isLoading && streamingSessionIdRef.current === sessionId)
      );
      if (running && sessionId) {
        const current = sessionQueuesRef.current[sessionId] || [];
        if (current.length >= MAX_CHAT_QUEUE_SIZE) return;
        const item: QueuedChatMessage = {
          id: `queue-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
          text: message,
          displayText: options.displayText?.trim() || message.trim(),
          options,
          createdAt: Date.now(),
        };
        queueArmedSessionsRef.current.add(sessionId);
        commitQueue(sessionId, [...current, item]);
        return;
      }
      void sendMessage(message, options);
      return;
    }

    if (activeCreation.type === 'automation') {
      aiCreateRef.current = { type: 'automation', options };
      void sendMessage(message, options, buildAutomationPlanningPrompt(message));
      return;
    }

    const name = activeCreation.type === 'skill'
      ? createResourceName(message, activeCreation.type)
      : undefined;
    aiCreateRef.current = activeCreation.type === 'skill'
      ? { type: 'skill', name: name! }
      : { type: 'agent' };
    const generationPrompt = buildResourcePrompt(activeCreation, message, name);
    void sendMessage(message, options, generationPrompt);
  }, [commitQueue, currentConversation.id, isLoading, onCreateAutomationFromPrompt, promptCreation, sendMessage, sessionRunStates]);

  async function loadConversationMessages(convId: string | number) {
    const storedMessages = await getMessagesByConversation(convId);

    if (storedMessages.length > 0) {
      const parsedMessages = storedMessages.map((msg, index) => {
        let parsed: any = typeof msg.contents === 'string' ? JSON.parse(msg.contents) : msg.contents;
        // 兼容新旧格式：新格式 { items, metadata }，旧格式为数�?
        let contents = Array.isArray(parsed) ? parsed : parsed.items || [];
        let metadata = !Array.isArray(parsed) && parsed.metadata ? parsed.metadata : undefined;
        return {
          id: Date.now() + index,
          role: (msg.role as string).toUpperCase() as Message['role'],
          timestamp: msg.timestamp || new Date().toLocaleTimeString(),
          contents: contents.map((c: any) => {
            const type = (c.type as string).toUpperCase();
            const text = type === 'TEXT' && (msg.role as string).toUpperCase() === 'USER' && typeof c.text === 'string'
              ? stripInjectedPromptContext(c.text)
              : c.text;
            return { ...c, type, text };
          }),
          metadata,
        };
      });
      setMessages(parsedMessages);
      setSessionTodoTasks(prev => ({
        ...prev,
        [convId.toString()]: extractLatestTodoTasks(parsedMessages),
      }));
    } else {
      setMessages([]);
    }
  }

  // 会话切换时加�?清空消息
  // 依赖 currentConversation.id（string | number）而非整个对象，避�?sessions 变化导致误触�?
  const currentConversationId = currentConversation.id;
  useEffect(() => {
    const id = currentConversationId?.toString();

    if (!id) {
      setMessages([]);
      return;
    }

    // 临时会话：清空消�?
    if (streamingSessionIdRef.current === id || liveMessagesBySessionRef.current.has(id) || sessionRunStates[id] === 'running') {
      if (restoreLiveMessages(id)) return;
    }

    if (id.startsWith('temp-') || id.startsWith('pending-')) {
      setMessages([]);
      return;
    }

    // 正在流式输出时（ID �?temp 替换�?real），跳过加载
    // 正常会话：从数据库加载历史消�?
    loadConversationMessages(id);
  }, [currentConversationId, sessionRunStates]);

  useEffect(() => {
    const id = currentConversationId?.toString();
    if (!id || id.startsWith('temp-') || id.startsWith('pending-') || !backendPort) return;
    void refreshSessionTodos(id);
    const running = sessionRunStates[id] === 'running' || isLoading;
    if (!running) return;
    const timer = window.setInterval(() => void refreshSessionTodos(id), 750);
    return () => window.clearInterval(timer);
  }, [backendPort, currentConversationId, isLoading, refreshSessionTodos, sessionRunStates]);

  const handleHitlAction = useCallback(async (pending: PendingHitlItem, action: HitlAction, output?: string) => {
    const sessionId = pending.sessionId;
    const item = pending.item;
    const toolName = item.toolName || '';
    const workspace = workspacePathRef.current || '';
    if (action === 'approve_always') {
      await permissionService.allowAlways(workspace, toolName);
    }
    await permissionService.audit({
      sessionId,
      workspacePath: workspace,
      toolName,
      action: action === 'approve' ? 'approve_once' : action,
      command: item.command,
    }).catch(() => undefined);
    await sendHitlDecision(sessionId, action === 'reject' ? 'reject' : 'approve', output, item.callId);
    setPendingHitlItems(prev => prev.filter(hitl => hitl.id !== pending.id));
    startLoadingTimer();
  }, []);

  // 停止当前请求
  const handleStop = useCallback(async () => {
    const stoppedSessionId = sessionIdRef.current;
    WebSocketManager.getInstance().cancelSession(stoppedSessionId);
    responseTimeoutRetriesRef.current.delete(stoppedSessionId);
    clearLoadingTimer();
    clearStreamQueue(stoppedSessionId);
    setPendingHitlItems(prev => prev.filter(item => item.sessionId !== stoppedSessionId));
    setIsLoading(false);
    if (streamingSessionIdRef.current === stoppedSessionId) {
      streamingSessionIdRef.current = null;
      isStreamingRef.current = false;
      onSessionRunStateChangeRef.current?.(stoppedSessionId, 'completed');
    }

    // 保留当前已累积的内容作为最终消�?
    const contentItems = buildContentItems();
    if (contentItems.length > 0) {
      const finalMsg: Message = {
        id: assistantMsgIdRef.current,
        role: 'ASSISTANT',
        timestamp: new Date().toLocaleTimeString(),
        contents: contentItems,
      };
      setMessages(prev => {
        const filtered = prev.filter(m => m.id !== assistantMsgIdRef.current);
        return [...filtered, finalMsg];
      });
    }

    await flushAssistantPersistence(stoppedSessionId);
    const pending = pendingPersistBySessionRef.current.get(stoppedSessionId);
    if (pending) {
      pendingPersistBySessionRef.current.delete(stoppedSessionId);
      if (pendingPersistRef.current?.sessionId === stoppedSessionId) pendingPersistRef.current = null;
      if (pending.sessionId.startsWith('temp-') && onUpdateSessionTitleRef.current) {
        const title = pending.messageText.trim().slice(0, 20) + (pending.messageText.trim().length > 20 ? '...' : '');
        void onUpdateSessionTitleRef.current(pending.sessionId, title);
      }
    }

    // 重置累积�?
    accumulatedContentRef.current = [];
    clearLiveSession(stoppedSessionId);
  }, []);

  // 模型切换时推送配置到后端
  const handleModelChange = useCallback(async (compositeId: string) => {
    // compositeId 格式: "providerId__modelId" �?"providerId"（无 availableModels 时）
    const sepIdx = compositeId.indexOf('__');
    const providerId = sepIdx >= 0 ? compositeId.substring(0, sepIdx) : compositeId;
    const modelId = sepIdx >= 0 ? compositeId.substring(sepIdx + 2) : null;
    const provider = providers.find(p => p.id === providerId);
    if (provider) {
      onActiveProviderChange?.(compositeId);
      const modelInfo = modelId ? provider.availableModels?.find(m => m.id === modelId) : undefined;
      await registerModelToBackend({
        ...provider,
        model: modelId || provider.model,
        contextLength: modelInfo?.contextLength || provider.contextLength,
      }, true);
    }
  }, [providers, onActiveProviderChange]);

  const currentConversationIdString = currentConversation.id?.toString();
  const currentPendingHitlItems = useMemo(() => (
    currentConversationIdString
      ? pendingHitlItems.filter(item => item.sessionId === currentConversationIdString)
      : []
  ), [currentConversationIdString, pendingHitlItems]);
  const activePromptCreation = promptCreation?.sessionId === currentConversationIdString
    ? promptCreation
    : null;
  const promptCreationUi = activePromptCreation ? promptCreationCopy[activePromptCreation.type] : null;
  const currentRunState = currentConversationIdString ? sessionRunStates[currentConversationIdString] : undefined;
  const isCurrentConversationLoading = currentRunState === 'running' || (isLoading && streamingSessionIdRef.current === currentConversationIdString);
  const currentQueue = currentConversationIdString ? (sessionQueues[currentConversationIdString] || []) : [];
  const showStartWorkEntry = !currentConversationIdString && !workspacePath && !activePromptCreation;

  const sendNextQueuedMessage = useCallback(() => {
    const sessionId = currentConversation.id?.toString();
    if (!sessionId || queueDrainingRef.current || isCurrentConversationLoading) return;
    const queue = sessionQueuesRef.current[sessionId] || [];
    const nextItem = queue[0];
    if (!nextItem) {
      queueArmedSessionsRef.current.delete(sessionId);
      return;
    }
    if (nextItem.hadFiles) {
      window.alert('该恢复任务原本包含附件或上下文，但附件内容不会持久化。请移除后重新发送。');
      return;
    }
    queueDrainingRef.current = true;
    commitQueue(sessionId, queue.slice(1));
    void sendMessage(nextItem.text, nextItem.options).finally(() => {
      window.setTimeout(() => { queueDrainingRef.current = false; }, 150);
    });
  }, [commitQueue, currentConversation.id, isCurrentConversationLoading, sendMessage]);

  useEffect(() => {
    const sessionId = currentConversationIdString;
    if (!sessionId || isCurrentConversationLoading || !queueArmedSessionsRef.current.has(sessionId)) return;
    if ((sessionQueues[sessionId] || []).length === 0) {
      queueArmedSessionsRef.current.delete(sessionId);
      return;
    }
    const timer = window.setTimeout(sendNextQueuedMessage, 80);
    return () => window.clearTimeout(timer);
  }, [currentConversationIdString, isCurrentConversationLoading, sendNextQueuedMessage, sessionQueues]);

  const removeQueuedMessage = useCallback((id: string) => {
    const sessionId = currentConversation.id?.toString();
    if (!sessionId) return;
    commitQueue(sessionId, (sessionQueuesRef.current[sessionId] || []).filter(item => item.id !== id));
  }, [commitQueue, currentConversation.id]);

  const clearQueuedMessages = useCallback(() => {
    const sessionId = currentConversation.id?.toString();
    if (!sessionId) return;
    queueArmedSessionsRef.current.delete(sessionId);
    commitQueue(sessionId, []);
  }, [commitQueue, currentConversation.id]);
  useEffect(() => {
    if (!currentConversationIdString || !isCurrentConversationLoading) {
      setThinkingElapsedSeconds(0);
      return;
    }
    const getElapsed = () => Math.max(0, Math.floor((Date.now() - (thinkingStartedAtBySessionRef.current.get(currentConversationIdString) || Date.now())) / 1000));
    setThinkingElapsedSeconds(getElapsed());
    const timer = window.setInterval(() => setThinkingElapsedSeconds(getElapsed()), 1000);
    return () => window.clearInterval(timer);
  }, [currentConversationIdString, isCurrentConversationLoading]);
  const showReviewFiles = reviewFiles.length > 0 && !isCurrentConversationLoading;
  const handleReviewFileDiscard = useCallback(async (path: string) => {
    if (!onReviewFileDiscard) return;
    try {
      await onReviewFileDiscard(path);
    } catch (error) {
      console.warn('[ChatView] 撤销文件更改失败:', error);
      onNotify?.('撤销失败，文件未被移除，请重试');
      throw error;
    }
  }, [onNotify, onReviewFileDiscard]);
  const isEmpty = messages.length === 0 && !isCurrentConversationLoading && reviewFiles.length === 0;
  const showHeader = !isEmpty;
  const baseContextTokens = useMemo(() => estimateMessageTokens(messages), [messages]);
  const currentSession = useMemo(() => {
    return sessions.find(session => session.id === currentConversationIdString);
  }, [sessions, currentConversationIdString]);
  const metadataTokens = useMemo(() => {
    return messages.reduce((total, message) => total + (message.metadata?.totalTokens || 0), 0);
  }, [messages]);
  const headerTotalTokens = metadataTokens > 0 ? metadataTokens : baseContextTokens;
  const headerMessageCount = Math.max(messages.length, currentSession?.messageCount || 0);
  const headerStartedAt = currentSession?.timestamp || currentConversation.timestamp;
  const totalConversationCount = useMemo(() => {
    const scopedSessions = workspacePath
      ? sessions.filter(session => session.workspacePath === workspacePath)
      : sessions;
    return scopedSessions.length;
  }, [sessions, workspacePath]);
  const messageTodoTasks = useMemo(() => extractLatestTodoTasks(messages), [messages]);
  const currentTodoTasks = currentConversationIdString
    ? (sessionTodoTasks[currentConversationIdString] ?? messageTodoTasks)
    : messageTodoTasks;

  const rewindFromMessage = useCallback(async (id: number, rerun: boolean) => {
    const sessionId = currentConversation.id?.toString();
    const messageIndex = messages.findIndex(message => message.id === id);
    if (!backendPort || !sessionId || messageIndex < 0 || isCurrentConversationLoading) return;
    const selected = messages[messageIndex];
    if (selected.role !== 'USER') return;
    if (!rerun && !window.confirm('确定回退并删除此消息及之后的所有内容吗？')) return;

    try {
      const historyResponse = await fetch(`http://localhost:${backendPort}/desktop/chat/messages?sessionId=${encodeURIComponent(sessionId)}`, { cache: 'no-store' });
      if (!historyResponse.ok) throw new Error('历史记录读取失败');
      const historyPayload = await historyResponse.json();
      const history: Array<{ role?: string; content?: unknown }> = Array.isArray(historyPayload?.data) ? historyPayload.data : [];
      const selectedUserOrdinal = messages.slice(0, messageIndex + 1).filter(message => message.role === 'USER').length - 1;
      let seenUsers = -1;
      const serverIndex = history.findIndex((item: { role?: string }) => {
        if (String(item.role || '').toUpperCase() !== 'USER') return false;
        seenUsers += 1;
        return seenUsers === selectedUserOrdinal;
      });
      if (serverIndex < 0) throw new Error('无法匹配后端历史');

      const rewindResponse = await fetch(`http://localhost:${backendPort}/desktop/chat/rewind`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId, count: history.length - serverIndex }),
      });
      if (!rewindResponse.ok) throw new Error('后端回退失败');
      const rewindPayload = await rewindResponse.json();
      if (rewindPayload?.code !== undefined && rewindPayload.code !== 200) throw new Error('后端回退失败');

      const removedCount = await truncateConversationMessages(sessionId, messageIndex);
      const remainingMessages = messages.slice(0, messageIndex);
      setMessages(remainingMessages);
      liveBaseMessagesBySessionRef.current.set(sessionId, remainingMessages);
      liveMessagesBySessionRef.current.set(sessionId, remainingMessages);
      onSessionMessageSavedRef.current?.(sessionId, -removedCount);

      if (rerun) {
        const text = selected.contents
          .filter(item => item.type !== 'IMAGE')
          .map(item => item.text || '')
          .join('\n')
          .trim();
        if (!text) return;
        const imageAttachments: Attachment[] = selected.contents
          .filter(item => item.type === 'IMAGE' && isSafeImageDataUrl(item.text))
          .map((item, index) => ({
            id: `rerun-${id}-${index}`,
            name: item.name || `image-${index + 1}`,
            type: 'image',
            content: item.text,
          }));
        const originalRequestText = typeof history[serverIndex]?.content === 'string'
          ? history[serverIndex].content as string
          : text;
        const compositeModelId = activeProviderId || providers.find(provider => provider.enabled)?.id || '';
        const separatorIndex = compositeModelId.indexOf('__');
        const providerId = separatorIndex >= 0 ? compositeModelId.slice(0, separatorIndex) : compositeModelId;
        const explicitModel = separatorIndex >= 0 ? compositeModelId.slice(separatorIndex + 2) : '';
        const provider = providers.find(item => item.id === providerId);
        const savedEffort = localStorage.getItem('soloncode-reasoning-effort');
        const reasoningEffort: ReasoningEffort = savedEffort === 'low' || savedEffort === 'high' || savedEffort === 'max' ? savedEffort : 'medium';
        void sendMessage(text, {
          model: compositeModelId,
          modelName: explicitModel || provider?.model || compositeModelId,
          agent: '',
          contexts: [],
          attachments: imageAttachments,
          reasoningEffort,
          mode: 'default',
        }, originalRequestText, true);
      }
    } catch (error) {
      console.warn('[ChatView] 会话回退失败，本地记录保持不变:', error);
      onNotify?.(rerun ? '重新生成失败，请检查后端连接后重试' : '删除失败，请检查后端连接后重试');
    }
  }, [activeProviderId, backendPort, currentConversation.id, isCurrentConversationLoading, messages, onNotify, providers, sendMessage]);

  const handleDeleteMessage = useCallback((id: number) => { void rewindFromMessage(id, false); }, [rewindFromMessage]);
  const handleRerunMessage = useCallback((id: number) => { void rewindFromMessage(id, true); }, [rewindFromMessage]);
  const visiblePlanApproval = planApproval?.sessionId === currentConversation.id?.toString()
    ? planApproval
    : null;
  const handleExecutePlan = useCallback(() => {
    if (!visiblePlanApproval) return;
    const approved = visiblePlanApproval;
    setPlanApproval(null);
    void sendMessage('计划已批准。请严格按照刚才的计划开始执行；完成后总结改动、验证结果和仍需关注的风险。', approved.options);
  }, [sendMessage, visiblePlanApproval]);

  return (
    <main className={`main-content${isEmpty ? ' empty-state' : ''}`}>
      {showHeader && (
        <ChatHeader
          title={currentConversation.title}
          status={currentConversation.status}
          projectName={currentConversation.workspacePath && currentConversation.workspacePath === workspacePath ? projectName : undefined}
          messageCount={headerMessageCount}
          startedAt={headerStartedAt}
          totalTokens={headerTotalTokens}
          totalConversations={totalConversationCount}
          reviewFiles={reviewFiles}
          onReviewFileSelect={onReviewFileSelect}
          openInfoSignal={reviewInfoSignal}
        />
      )}
      <ChatMessages ref={chatMessagesRef} messages={messages} isLoading={isCurrentConversationLoading} thinkingElapsedSeconds={thinkingElapsedSeconds} theme={theme} projectName={projectName} onDeleteMessage={handleDeleteMessage} onRerunMessage={handleRerunMessage} onFileSelect={onFileSelect} />

      {visiblePlanApproval && (
        <div className="plan-approval-bar">
          <div>
            <strong>规划已完成</strong>
            <span>执行前会再次创建工作区检查点，工具操作仍按审批策略处理。</span>
          </div>
          <div className="plan-approval-actions">
            <button type="button" onClick={() => setPlanApproval(null)}>取消</button>
            <button type="button" className="primary" onClick={handleExecutePlan}>批准并执行</button>
          </div>
        </div>
      )}

      {isEmpty ? (
        <div className="empty-center-container">
          <div className="empty-state-hero">
            <div className="hero-logo">SolonCode</div>
            <div className="hero-slogan">
              {promptCreationUi?.slogan || `${newSessionFromProject && projectName ? `在 ${projectName} ` : ''}做你想做的事`}
            </div>
          </div>
          {showReviewFiles && (
            <ReviewFilesBar files={reviewFiles} onReview={onReviewFileSelect} onDiscard={handleReviewFileDiscard} />
          )}
          <ChatTaskList key={currentConversationIdString || 'new'} tasks={currentTodoTasks} />
          <ChatQueueDock items={currentQueue} running={isCurrentConversationLoading} onRemove={removeQueuedMessage} onClear={clearQueuedMessages} onContinue={sendNextQueuedMessage} />
          <HitlInputDock items={currentPendingHitlItems} onAction={handleHitlAction} />
          <ChatInput onSend={handleChatInputSend} isLoading={isCurrentConversationLoading} onStop={handleStop} providers={providers} agents={agents} skills={skills} agentRefreshKey={agentRefreshKey} activeProviderId={activeProviderId} onModelChange={handleModelChange} activeFileName={promptCreationUi?.fileName || activeFileName} backendPort={backendPort} showStartWork={showStartWorkEntry} onNewProject={onNewProject} onOpenFolder={onOpenFolder} workspacePath={workspacePath} baseContextTokens={baseContextTokens} currentSessionId={currentConversation.id?.toString()} goalDefaultMaxTokens={goalDefaultMaxTokens} goalDefaultMaxDuration={goalDefaultMaxDuration} />
        </div>
      ) : (
        <>
          {showReviewFiles && (
            <ReviewFilesBar files={reviewFiles} onReview={onReviewFileSelect} onDiscard={handleReviewFileDiscard} />
          )}
          <ChatTaskList key={currentConversationIdString || 'current'} tasks={currentTodoTasks} />
          <ChatQueueDock items={currentQueue} running={isCurrentConversationLoading} onRemove={removeQueuedMessage} onClear={clearQueuedMessages} onContinue={sendNextQueuedMessage} />
          <HitlInputDock items={currentPendingHitlItems} onAction={handleHitlAction} />
          <ChatInput onSend={handleChatInputSend} isLoading={isCurrentConversationLoading} onStop={handleStop} providers={providers} agents={agents} skills={skills} agentRefreshKey={agentRefreshKey} activeProviderId={activeProviderId} onModelChange={handleModelChange} activeFileName={promptCreationUi?.fileName || activeFileName} backendPort={backendPort} showStartWork={showStartWorkEntry} onNewProject={onNewProject} onOpenFolder={onOpenFolder} workspacePath={workspacePath} baseContextTokens={baseContextTokens} currentSessionId={currentConversation.id?.toString()} goalDefaultMaxTokens={goalDefaultMaxTokens} goalDefaultMaxDuration={goalDefaultMaxDuration} />
        </>
      )}
      {/* 搴曢儴鎻愮ず */}
        <div className="input-footer">
          <span className="input-hint">
            Enter 发送，Shift + Enter 换行，/ 命令，$ Skill，# 引用上下文，@ 选择智能体
          </span>
        </div>
    </main>
  );
}

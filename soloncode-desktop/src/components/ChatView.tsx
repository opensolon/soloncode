import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { invoke } from '@tauri-apps/api/core';
import type { Message, Conversation, Theme, Plugin, ContentType, ContentItem } from '../types';
import { normalizeProviderType, type ModelProvider } from '../services/settingsService';
import { fileService } from '../services/fileService';
import { saveMessage, updateMessage, getMessagesByConversation } from '../db';
import { ChatHeader, type ChatReviewFile } from './ChatHeader';
import { ChatTaskList, type ChatTask } from './ChatTaskList';
import { ChatMessages } from './ChatMessages';
import { ChatInput, type ChatAgentOption, type SendOptions, type ChatMode, type ReasoningEffort } from './ChatInput';
import { Icon } from './common/Icon';
import type { Session } from './sidebar/SessionsPanel';
import {
  buildAutomationPlanningPrompt,
  parseGeneratedAutomationPlan,
  type GeneratedAutomationPlan,
} from '../utils/automationPlan';
import { isTodoToolName } from '../utils/todoTools';
import { withRetry } from '../utils/retry';
import '../views/ChatPage.css';

export type PromptCreationType = 'skill' | 'agent' | 'automation';

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
  onUpdateSessionTitle?: (sessionId: string, title: string) => string | void | Promise<string | void>;
  onNewSession?: (title?: string) => string;
  onSelectSession?: (sessionId: string) => void;
  providers?: ModelProvider[];
  agents?: ChatAgentOption[];
  activeProviderId?: string;
  onActiveProviderChange?: (providerId: string) => void;
  activeFileName?: string;
  activeFilePath?: string;
  onNewProject?: () => void;
  onOpenFolder?: () => void;
  onFileSelect?: (path: string) => void;
  reviewFiles?: ChatReviewFile[];
  onReviewFileSelect?: (path: string) => void;
  onReviewFileDiscard?: (path: string) => void;
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
}

// 全局 WebSocket 连接管理器（每次请求独立连接�?
const STREAM_BATCH_INTERVAL_MS = 16;
const STREAM_BATCH_CHARS = 24;
const WS_CONNECT_RETRY_DELAYS_MS = [300, 700, 1500];
const WS_RECONNECT_DELAYS_MS = [500, 1000, 2000, 4000, 8000];

class WebSocketManager {
  private static instance: WebSocketManager | null = null;
  private activeWs = new Map<string, WebSocket>();
  private intentionallyClosedWs = new WeakSet<WebSocket>();
  private lastSequenceBySession = new Map<string, number>();
  private reconnectAttempts = new Map<string, number>();
  private reconnectTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private terminalSessions = new Set<string>();
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
  private createConnection(sessionId?: string, resume = false, manageSession = false): Promise<WebSocket> {
    return new Promise((resolve, reject) => {
      const wsUrl = this.getWebSocketUrl(sessionId, resume);
      console.log('[WS] Connecting to:', wsUrl);
      const ws = new WebSocket(wsUrl);
      if (sessionId && manageSession) this.bindSessionSocket(sessionId, ws);

      const onOpen = () => {
        cleanup();
        console.log('[WS] Connected');
        resolve(ws);
      };
      const onError = () => {
        cleanup();
        console.error('[WS] Connection error');
        reject(new Error('WebSocket connection failed'));
      };
      const onClose = () => {
        cleanup();
        reject(new Error('WebSocket closed before connected'));
      };
      const cleanup = () => {
        ws.removeEventListener('open', onOpen);
        ws.removeEventListener('error', onError);
        ws.removeEventListener('close', onClose);
      };
      ws.addEventListener('open', onOpen);
      ws.addEventListener('error', onError);
      ws.addEventListener('close', onClose);
    });
  }

  private async createConnectionWithRetry(sessionId: string): Promise<WebSocket> {
    let lastError: unknown;
    for (let attempt = 0; attempt <= WS_CONNECT_RETRY_DELAYS_MS.length; attempt++) {
      try {
        return await this.createConnection(sessionId);
      } catch (error) {
        lastError = error;
        if (attempt >= WS_CONNECT_RETRY_DELAYS_MS.length) break;
        await new Promise(resolve => setTimeout(resolve, WS_CONNECT_RETRY_DELAYS_MS[attempt]));
      }
    }
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
      if (messageType === 'done' || messageType === 'error') {
        this.finishSession(msgSessionId, ws, msg);
        return;
      }
      this.dispatchMessage(msg);
    } catch (error) {
      console.warn('[WS] Failed to parse message:', error);
    }
  }

  private finishSession(sessionId: string, ws: WebSocket, message: Record<string, any>) {
    this.terminalSessions.add(sessionId);
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
      const text = '连接恢复失败，请重试';
      this.statusCallback?.(sessionId, 'error', text);
      this.dispatchMessage({ type: 'error', sessionId, text });
      return;
    }

    const timer = setTimeout(async () => {
      this.reconnectTimers.delete(sessionId);
      if (this.terminalSessions.has(sessionId)) return;
      this.reconnectAttempts.set(sessionId, attempt + 1);
      try {
        const ws = await this.createConnection(sessionId, true, true);
        if (this.terminalSessions.has(sessionId)) {
          this.intentionallyClosedWs.add(ws);
          ws.close();
          return;
        }
        this.activeWs.set(sessionId, ws);
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
    .map(item => item.text || '')
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

function ReviewFilesBar({ files, onReview, onDiscard }: { files: ChatReviewFile[]; onReview?: (path: string) => void; onDiscard?: (path: string) => void }) {
  if (files.length === 0) return null;
  const additions = files.reduce((total, file) => total + (file.additions || 0), 0);
  const deletions = files.reduce((total, file) => total + (file.deletions || 0), 0);
  const primaryStatus = files.every(file => file.status === files[0].status) ? files[0].status : 'modified';
  const title = files.length === 1
    ? `${getReviewFileActionLabel(files[0].status)} ${getReviewFileName(files[0].path)}`
    : `${getReviewFileActionLabel(primaryStatus)} ${files.length} 个文件`;
  const handleReviewAll = () => onReview?.(files[0].path);
  const handleDiscardAll = () => files.forEach(file => onDiscard?.(file.path));

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
            <button type="button" className="chat-review-link-btn" onClick={handleDiscardAll} title="撤销">
              <span>撤销</span>
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

export function ChatView({ currentConversation, plugins, workspacePath, projectName, theme = 'dark', backendPort, sessions = [], sessionRunStates = {}, maxSteps = 30, onUpdateSessionTitle, onNewSession, onSelectSession, providers = [], agents = [], activeProviderId, onActiveProviderChange, activeFileName, activeFilePath, onNewProject, onOpenFolder, onFileSelect, reviewFiles = [], onReviewFileSelect, onReviewFileDiscard, promptCreation, onCreateAutomationFromPrompt, automationPrompt, onAutomationPromptConsumed, onAiCreateComplete, newSessionFromProject, onSessionRunStateChange, onSessionMessageSaved }: ChatViewProps) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [chatMode, setChatMode] = useState<ChatMode>('default');
  const [reviewInfoSignal, setReviewInfoSignal] = useState(0);
  const [thinkingElapsedSeconds, setThinkingElapsedSeconds] = useState(0);
  const [sessionTodoTasks, setSessionTodoTasks] = useState<Record<string, ChatTask[]>>({});
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
          updated[existingIndex] = tempMsg;
          return updated;
        }
        return [...prev, tempMsg];
      });
    });
  }, []);

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

    const contentItems = buildContentItems(segments);
    if (contentItems.length > 0) {
      const assistantMessage: Message = {
        id: assistantMsgIdRef.current,
        role: 'ASSISTANT',
        timestamp: new Date().toLocaleTimeString(),
        contents: contentItems,
      };
      const existingIndex = liveMessages.findIndex(message => message.id === assistantMsgIdRef.current);
      if (existingIndex >= 0) liveMessages[existingIndex] = assistantMessage;
      else liveMessages.push(assistantMessage);
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

  // 当前 assistant 消息 ID
  const assistantMsgIdRef = useRef<number>(0);

  // 加载超时计时器：收到消息时重置，120秒无新消息自动停�?
  const loadingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const startLoadingTimer = useCallback(() => {
    if (loadingTimerRef.current) clearTimeout(loadingTimerRef.current);
    loadingTimerRef.current = setTimeout(() => {
      console.log('[ChatView] Loading timeout (120s), auto-stopping');
      const timedOutSessionId = streamingSessionIdRef.current;
      if (timedOutSessionId) {
        clearStreamQueue(timedOutSessionId);
        clearLiveSession(timedOutSessionId);
      }
      setIsLoading(false);
      isStreamingRef.current = false;
      streamingSessionIdRef.current = null;
      if (timedOutSessionId) {
        onSessionRunStateChangeRef.current?.(timedOutSessionId, 'error', '等待响应超时');
      }
    }, 120000);
  }, []);

  const clearLoadingTimer = useCallback(() => {
    if (loadingTimerRef.current) {
      clearTimeout(loadingTimerRef.current);
      loadingTimerRef.current = null;
    }
  }, []);
  const updateSessionTodosFromTool = useCallback((sessionId: string, toolName?: string, text?: string, args?: Record<string, unknown>) => {
    if (!sessionId || !isTodoToolName(toolName)) return;
    const todosArg = args?.todos;
    const markdown = typeof todosArg === 'string' && todosArg.trim() ? todosArg : (text || '');
    setSessionTodoTasks(prev => ({
      ...prev,
      [sessionId]: parseTodoMarkdown(markdown),
    }));
  }, []);

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
    async function flushPendingUserMessage(sessionId?: string): Promise<{ sessionId: string; title: string; wasNew: boolean } | null> {
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
      };
    }

    const handleMessage = async (data: any) => {
      const msgSessionId = (data.sessionId || conversationIdRef.current.toString()).toString();
      const isCurrentSession = msgSessionId === conversationIdRef.current.toString() || msgSessionId === sessionIdRef.current;

      // done / error 类型必须处理，不�?session 校验限制（保�?loading 状态正确）
      if (data.type === 'done') {
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

          await flushAssistantPersistence(msgSessionId, finalMsg.metadata);
        } else {
          await flushAssistantPersistence(msgSessionId);
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
        if (rafIdRef.current) { cancelAnimationFrame(rafIdRef.current); rafIdRef.current = null; }
        pendingUpdateRef.current = false;

        setIsLoading(false);
        isStreamingRef.current = false;
        if (streamingSessionIdRef.current === msgSessionId) {
          streamingSessionIdRef.current = null;
        }
        chatMessagesRef.current?.scrollToBottom();
        return;
      }

      if (data.type === 'error') {
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
          const pending = await flushPendingUserMessage(msgSessionId);
          await flushAssistantPersistence(msgSessionId);
          await withRetry(() => saveMessage({
            conversationId: pending?.sessionId || msgSessionId,
            role: 'ERROR',
            timestamp: new Date().toLocaleTimeString(),
            contents: JSON.stringify([{ type: 'ERROR', text: data.text || '未知错误' }]),
            workspacePath: workspacePathRef.current,
          }));
          onSessionMessageSavedRef.current?.(pending?.sessionId || msgSessionId, 1);
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
        clearStreamQueue(msgSessionId);

        // 即使出错也要持久化用户消�?
        const pending = await flushPendingUserMessage(msgSessionId);
        await flushAssistantPersistence(msgSessionId);

        const errorText = data.text || '未知错误';
        const errorMsg: Message = {
          id: Date.now(),
          role: 'ERROR',
          timestamp: new Date().toLocaleTimeString(),
          contents: [{ type: 'ERROR', text: errorText }]
        };
        setMessages(prev => [...prev, errorMsg]);

        await withRetry(() => saveMessage({
          conversationId: pending?.sessionId || msgSessionId,
          role: 'ERROR',
          timestamp: errorMsg.timestamp,
          contents: JSON.stringify(errorMsg.contents),
          workspacePath: workspacePathRef.current,
        }));
        onSessionMessageSavedRef.current?.(pending?.sessionId || msgSessionId, 1);

        // 所有消息保存后，触发会话持久化
        if (pending?.wasNew && onUpdateSessionTitleRef.current) {
          onUpdateSessionTitleRef.current(pending.sessionId, pending.title);
        }

        clearLiveSession(msgSessionId);
        setIsLoading(false);
        isStreamingRef.current = false;
        if (streamingSessionIdRef.current === msgSessionId) {
          streamingSessionIdRef.current = null;
        }
        return;
      }

      // 其他消息类型检查是否属于当前会话（同时接受 temp ID 和重分配后的真实 ID�?
      // HITL 审批请求 �?直接追加到当前消�?
      if (data.type === 'hitl') {
        if (!isCurrentSession) return;
        const hitlItem: ContentItem = {
          type: 'HITL',
          text: '',
          toolName: data.toolName,
          command: data.command,
        };
        setMessages(prev => {
          const contentItems = buildContentItems();
          contentItems.push(hitlItem);
          const tempMsg: Message = {
            id: assistantMsgIdRef.current,
            role: 'ASSISTANT',
            timestamp: new Date().toLocaleTimeString(),
            contents: contentItems
          };
          const existingIndex = prev.findIndex(m => m.id === assistantMsgIdRef.current);
          if (existingIndex >= 0) {
            const updated = [...prev];
            updated[existingIndex] = tempMsg;
            return updated;
          }
          return [...prev, tempMsg];
        });
        chatMessagesRef.current?.scrollToBottom();
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

  const sendMessage = useCallback(async (messageText: string, options: SendOptions, requestText?: string) => {
    let sessionId = currentConversation.id?.toString();

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
    if (options.attachments && options.attachments.length > 0) {
      const textParts = options.attachments
        .filter(att => att.type !== 'image')
        .map(att => `--- 文件: ${att.name} ---\n${att.content}\n---`);
      if (textParts.length > 0) {
        fullMessage = `${textParts.join('\n\n')}\n\n${fullMessage}`;
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
      contents: [{ type: 'TEXT', text: messageText }]
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
    };
    pendingPersistRef.current = pendingPersist;
    pendingPersistBySessionRef.current.set(sessionId!, pendingPersist);

    try {
      const wsManager = WebSocketManager.getInstance();

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
        mode: chatMode,
        maxSteps,
        reasoningEffort: options.reasoningEffort,
      };

      // 附件数据（图�?base64，文本内容）
      if (options.attachments && options.attachments.length > 0) {
        request.attachments = options.attachments.map(att => {
          if (att.type === 'image') {
            // content 鏄?data URL: "data:image/png;base64,..."
            const match = att.content.match(/^data:([^;]+);base64,(.+)$/);
            return {
              type: 'image',
              name: att.name,
              data: match ? match[2] : att.content,
              mimeType: match ? match[1] : 'image/png',
            };
          }
          return {
            type: 'file',
            name: att.name,
            data: att.content,
            mimeType: 'text/plain',
          };
        });
      }

      await wsManager.sendMessage(request);

    } catch (error) {
      console.error('Failed to send message:', error);

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

        const errorMessage: Message = {
          id: Date.now() + 1,
          role: 'ERROR',
          timestamp: new Date().toLocaleTimeString(),
          contents: [{ type: 'ERROR', text: `请求失败: ${error instanceof Error ? error.message : '未知错误'}` }]
        };
        setMessages(prev => [...prev, errorMessage]);

        await withRetry(() => saveMessage({
          conversationId: pending.sessionId,
          role: 'ERROR',
          timestamp: errorMessage.timestamp,
          contents: JSON.stringify(errorMessage.contents),
          workspacePath,
        }));
        onSessionMessageSaved?.(pending.sessionId, 1);

        // 触发会话持久化（reassignMessages 会处�?temp→real�?
        if (pending.sessionId.startsWith('temp-') && onUpdateSessionTitle) {
          onUpdateSessionTitle(pending.sessionId, pending.messageText.trim().slice(0, 20) + (pending.messageText.trim().length > 20 ? '...' : ''));
        }
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
        error instanceof Error ? error.message : '命令发送失败',
      );
    }
  }, [currentConversation, onAiCreateComplete, onNewSession, onUpdateSessionTitle, workspacePath, providers, activeFilePath, maxSteps]);

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
    }).finally(() => onAutomationPromptConsumed?.(automationPrompt.runId));
  }, [automationPrompt, currentConversation.id, onAutomationPromptConsumed, sendMessage]);

  const handleChatInputSend = useCallback((message: string, options: SendOptions) => {
    const activeCreation = promptCreation?.sessionId === currentConversation.id?.toString()
      ? promptCreation
      : null;
    if (!activeCreation) {
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
  }, [currentConversation.id, onCreateAutomationFromPrompt, promptCreation, sendMessage]);

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
    let cancelled = false;
    withRetry(async () => {
      const resp = await fetch(`http://localhost:${backendPort}/web/chat/todos?sessionId=${encodeURIComponent(id)}`);
      if (!resp.ok) throw new Error(`Todo request failed: ${resp.status}`);
      return resp.json();
    }, [300, 700, 1500])
      .then(res => {
        if (cancelled) return;
        const items = res?.data?.items || [];
        setSessionTodoTasks(prev => ({
          ...prev,
          [id]: mapTodoApiItems(items),
        }));
      })
      .catch(() => {
        if (cancelled) return;
        setSessionTodoTasks(prev => ({ ...prev, [id]: prev[id] || [] }));
      });
    return () => { cancelled = true; };
  }, [backendPort, currentConversationId]);

  // 停止当前请求
  const handleHitlAction = useCallback(async (action: 'approve' | 'reject') => {
    const manager = WebSocketManager.getInstance();
    const activeSocket = manager.getSessionSocket(sessionIdRef.current);
    if (activeSocket && activeSocket.readyState === WebSocket.OPEN) {
      activeSocket.send(JSON.stringify({
        type: 'hitl_action',
        action,
        sessionId: sessionIdRef.current,
      }));
    } else {
      // 短连接发�?
      const mgr = WebSocketManager.getInstance();
      const conn = await (mgr as any).createConnection(sessionIdRef.current);
      conn.send(JSON.stringify({
        type: 'hitl_action',
        action,
        sessionId: sessionIdRef.current,
      }));
      conn.close();
    }
  }, []);

  const handleStop = useCallback(async () => {
    const stoppedSessionId = sessionIdRef.current;
    WebSocketManager.getInstance().cancelSession(stoppedSessionId);
    clearLoadingTimer();
    clearStreamQueue(stoppedSessionId);
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
  const activePromptCreation = promptCreation?.sessionId === currentConversationIdString
    ? promptCreation
    : null;
  const promptCreationUi = activePromptCreation ? promptCreationCopy[activePromptCreation.type] : null;
  const currentRunState = currentConversationIdString ? sessionRunStates[currentConversationIdString] : undefined;
  const isCurrentConversationLoading = currentRunState === 'running' || (isLoading && streamingSessionIdRef.current === currentConversationIdString);
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

  const handleDeleteMessage = useCallback((id: number) => {
    setMessages(prev => prev.filter(m => m.id !== id));
  }, []);

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
      <ChatMessages ref={chatMessagesRef} messages={messages} isLoading={isCurrentConversationLoading} thinkingElapsedSeconds={thinkingElapsedSeconds} theme={theme} projectName={projectName} onDeleteMessage={handleDeleteMessage} onHitlAction={handleHitlAction} onFileSelect={onFileSelect} />

      {isEmpty ? (
        <div className="empty-center-container">
          <div className="empty-state-hero">
            <div className="hero-logo">SolonCode</div>
            <div className="hero-slogan">
              {promptCreationUi?.slogan || `${newSessionFromProject && projectName ? `在 ${projectName} ` : ''}做你想做的事`}
            </div>
          </div>
          {showReviewFiles && (
            <ReviewFilesBar files={reviewFiles} onReview={onReviewFileSelect} onDiscard={onReviewFileDiscard} />
          )}
          <ChatTaskList key={currentConversationIdString || 'new'} tasks={currentTodoTasks} />
          <ChatInput onSend={handleChatInputSend} isLoading={isCurrentConversationLoading} onStop={handleStop} providers={providers} agents={agents} activeProviderId={activeProviderId} onModelChange={handleModelChange} activeFileName={promptCreationUi?.fileName || activeFileName} backendPort={backendPort} showStartWork={!workspacePath && !activePromptCreation} onNewProject={onNewProject} onOpenFolder={onOpenFolder} workspacePath={workspacePath} mode={chatMode} onModeChange={setChatMode} baseContextTokens={baseContextTokens} />
        </div>
      ) : (
        <>
          {showReviewFiles && (
            <ReviewFilesBar files={reviewFiles} onReview={onReviewFileSelect} onDiscard={onReviewFileDiscard} />
          )}
          <ChatTaskList key={currentConversationIdString || 'current'} tasks={currentTodoTasks} />
          <ChatInput onSend={handleChatInputSend} isLoading={isCurrentConversationLoading} onStop={handleStop} providers={providers} agents={agents} activeProviderId={activeProviderId} onModelChange={handleModelChange} activeFileName={promptCreationUi?.fileName || activeFileName} backendPort={backendPort} showStartWork={!workspacePath && !activePromptCreation} onNewProject={onNewProject} onOpenFolder={onOpenFolder} workspacePath={workspacePath} mode={chatMode} onModeChange={setChatMode} baseContextTokens={baseContextTokens} />
        </>
      )}
      {/* 搴曢儴鎻愮ず */}
        <div className="input-footer">
          <span className="input-hint">
            Enter 发送，Shift + Enter 换行，/ 命令，# 引用上下文，@ 选择智能体
          </span>
        </div>
    </main>
  );
}

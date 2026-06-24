import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { invoke } from '@tauri-apps/api/core';
import type { Message, Conversation, Theme, Plugin, ContentType, ContentItem } from '../types';
import { normalizeProviderType, type ModelProvider } from '../services/settingsService';
import { fileService } from '../services/fileService';
import { saveMessage, getMessagesByConversation } from '../db';
import { ChatHeader, type ChatHeaderTask, type ChatReviewFile } from './ChatHeader';
import { ChatMessages } from './ChatMessages';
import { ChatInput, type SendOptions, type ChatMode } from './ChatInput';
import { Icon } from './common/Icon';
import type { Session } from './sidebar/SessionsPanel';
import '../views/ChatPage.css';

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
  onUpdateSessionTitle?: (sessionId: string, title: string) => void;
  onNewSession?: (title?: string) => string;
  onSelectSession?: (sessionId: string) => void;
  providers?: ModelProvider[];
  activeProviderId?: string;
  onActiveProviderChange?: (providerId: string) => void;
  activeFileName?: string;
  activeFilePath?: string;
  onNewProject?: () => void;
  onOpenFolder?: () => void;
  onFileSelect?: (path: string) => void;
  reviewFiles?: ChatReviewFile[];
  onReviewFileSelect?: (path: string) => void;
  initialPrompt?: {
    prompt: string;
    type: 'skill' | 'agent';
    name: string;
  } | null;
  onAiCreateComplete?: (info: { type: 'skill' | 'agent'; name: string }) => void;
  newSessionFromProject?: boolean;
  onSessionRunStateChange?: (sessionId: string, status: 'running' | 'completed' | 'error') => void;
  onSessionMessageSaved?: (sessionId: string, count?: number) => void;
}

// 全局 WebSocket 连接管理器（每次请求独立连接）
class WebSocketManager {
  private static instance: WebSocketManager | null = null;
  private activeWs = new Map<string, WebSocket>();
  private messageCallback: ((data: any) => void) | null = null;
  private statusCallback: ((sessionId: string, status: 'running' | 'completed' | 'error') => void) | null = null;
  private backendPort: number | null = null;
  private workspacePath: string | null = null;

  static getInstance(): WebSocketManager {
    if (!WebSocketManager.instance) {
      WebSocketManager.instance = new WebSocketManager();
    }
    return WebSocketManager.instance;
  }

  /** 设置后端端口（由 App.tsx 调用，打开工作区后设置） */
  setBackendPort(port: number | null) {
    this.backendPort = port;
  }

  /** 获取后端端口 */
  getBackendPort(): number | null {
    return this.backendPort;
  }

  /** 设置工作区路径（由 App.tsx 调用） */
  setWorkspacePath(path: string | null) {
    this.workspacePath = path;
  }

  private getWebSocketUrl(sessionId?: string): string {
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
    const query = params.toString();
    return `${protocol}://${host}/ws${query ? '?' + query : ''}`;
  }

  /** 每次请求创建独立 WebSocket 连接 */
  private createConnection(sessionId?: string): Promise<WebSocket> {
    return new Promise((resolve, reject) => {
      const wsUrl = this.getWebSocketUrl(sessionId);
      console.log('[WS] Connecting to:', wsUrl);
      const ws = new WebSocket(wsUrl);

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

  registerCallback(callback: (data: any) => void) {
    this.messageCallback = callback;
  }

  registerStatusCallback(callback: (sessionId: string, status: 'running' | 'completed' | 'error') => void) {
    this.statusCallback = callback;
  }

  unregisterCallback() {
    this.messageCallback = null;
  }

  async sendMessage(request: any): Promise<void> {
    const sessionId = request.sessionId?.toString() || '';
    const ws = await this.createConnection(sessionId);
    if (sessionId) {
      this.closeSession(sessionId);
      this.activeWs.set(sessionId, ws);
      this.statusCallback?.(sessionId, 'running');
    }

    ws.onmessage = (event) => {
      try {
        const data = event.data;
        if (data.trim() === '[DONE]') {
          ws.close();
          return;
        }
        const msg = JSON.parse(data);
        const msgSessionId = (msg.sessionId || sessionId || '').toString();
        if (msgSessionId && !msg.sessionId) msg.sessionId = msgSessionId;
        if (msgSessionId && msg.type === 'done') this.statusCallback?.(msgSessionId, 'completed');
        if (msgSessionId && msg.type === 'error') this.statusCallback?.(msgSessionId, 'error');
        this.messageCallback?.(msg);
      } catch (e) {
        console.warn('[WS] Failed to parse message:', event.data, e);
      }
    };

    ws.onclose = () => {
      if (sessionId && this.activeWs.get(sessionId) === ws) {
        this.activeWs.delete(sessionId);
      }
    };

    // sessionId 已通过 URL 参数传递，从 body 中移除
    delete request.sessionId;
    ws.send(JSON.stringify(request));
  }

  /** 取消当前请求：关闭连接 */
  cancel() {
    this.closeActive();
  }

  cancelSession(sessionId: string) {
    this.closeSession(sessionId);
  }

  getSessionSocket(sessionId: string): WebSocket | null {
    return this.activeWs.get(sessionId) || null;
  }

  disconnect() {
    this.closeActive();
    this.messageCallback = null;
  }

  /** 推送配置变更到后端（HTTP POST 代替短连接 WS） */
  async sendConfig(chatModel: { apiUrl?: string; apiKey?: string; model?: string; provider?: string }): Promise<void> {
    const port = this.backendPort || 4808;
    try {
      await fetch(`http://localhost:${port}/chat/config`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ type: 'config', chatModel }),
      });
    } catch {
      // fallback: 短连接 WS
      const ws = await this.createConnection();
      ws.send(JSON.stringify({ type: 'config', chatModel }));
      ws.close();
    }
  }

  private closeActive() {
    for (const ws of this.activeWs.values()) {
      ws.close();
    }
    this.activeWs.clear();
  }

  private closeSession(sessionId: string) {
    const ws = this.activeWs.get(sessionId);
    if (ws) {
      ws.close();
      this.activeWs.delete(sessionId);
    }
  }

  closeConnection() {
    this.closeActive();
  }
}

// 过滤空标签和 trace 信息的辅助函数
function filterEmptyTags(text: string): string {
  let result = text;
  // 过滤空的 HTML/XML 标签（包括带属性的）
  result = result.replace(/<([a-zA-Z][a-zA-Z0-9]*)([^>]*)><\/\1>/g, '');
  result = result.replace(/<([a-zA-Z][a-zA-Z0-9]*)([^>]*)\/>/g, '');
  // 过滤连续的空行（超过2个换行符）
  result = result.replace(/\n{3,}/g, '\n\n');
  // 过滤末尾的模型 trace 信息，如 `(glm-4.7, 6985tk, 4s)` 或 `(gpt-4o, 1s)`
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

/** 设置后端 WebSocket 端口（供 App.tsx 调用） */
export function setBackendPort(port: number | null) {
  WebSocketManager.getInstance().setBackendPort(port);
}

/** 设置工作区路径（供 App.tsx 调用，连接 WS 时会作为 X-Session-Cwd 参数传入） */
export function setWorkspacePath(path: string | null) {
  WebSocketManager.getInstance().setWorkspacePath(path);
}

const FALLBACK_PORT = 4808;

/** 通过 REST API 注册模型到后端 */
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
    const resp = await fetch(`http://localhost:${port}/chat/models/add`, {
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
      await fetch(`http://localhost:${port}/chat/models/select`, {
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

export function ChatView({ currentConversation, plugins, workspacePath, projectName, theme = 'dark', backendPort, sessions = [], sessionRunStates = {}, maxSteps = 30, onUpdateSessionTitle, onNewSession, onSelectSession, providers = [], activeProviderId, onActiveProviderChange, activeFileName, activeFilePath, onNewProject, onOpenFolder, onFileSelect, reviewFiles = [], onReviewFileSelect, initialPrompt, onAiCreateComplete, newSessionFromProject, onSessionRunStateChange, onSessionMessageSaved }: ChatViewProps) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [chatMode, setChatMode] = useState<ChatMode>('default');
  const [reviewInfoSignal, setReviewInfoSignal] = useState(0);
  const chatMessagesRef = useRef<{ scrollToBottom: () => void } | null>(null);
  const sessionIdRef = useRef<string>('');
  const conversationIdRef = useRef<string | number>('');
  const isStreamingRef = useRef(false);
  const streamingSessionIdRef = useRef<string | null>(null);
  const aiCreateRef = useRef<{ type: 'skill' | 'agent'; name: string } | null>(null);
  const initialPromptSentRef = useRef(false);
  const onUpdateSessionTitleRef = useRef(onUpdateSessionTitle);
  onUpdateSessionTitleRef.current = onUpdateSessionTitle;
  const onNewSessionRef = useRef(onNewSession);
  onNewSessionRef.current = onNewSession;
  const onSessionRunStateChangeRef = useRef(onSessionRunStateChange);
  onSessionRunStateChangeRef.current = onSessionRunStateChange;
  const onSessionMessageSavedRef = useRef(onSessionMessageSaved);
  onSessionMessageSavedRef.current = onSessionMessageSaved;
  const workspacePathRef = useRef(workspacePath);
  workspacePathRef.current = workspacePath;

  // 有序 segment 列表 — 保留 think/action/text 的真实交错顺序
  type AccSegment =
    | { type: 'THINK'; text: string }
    | { type: 'TEXT'; text: string; agentName?: string }
    | { type: 'ACTION'; text: string; toolName?: string; args?: Record<string, unknown> };

  const accumulatedContentRef = useRef<AccSegment[]>([]);
  const backgroundContentBySessionRef = useRef(new Map<string, AccSegment[]>());

  // RAF 节流：流式更新时合并多次 chunk 到一帧渲染
  const rafIdRef = useRef<number | null>(null);
  const pendingUpdateRef = useRef(false);

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
      chatMessagesRef.current?.scrollToBottom();
    });
  }, []);

  // 待持久化的首条用户消息（新会话时暂存，done/error 时真正保存）
  const pendingPersistRef = useRef<{
    sessionId: string;
    userMessage: { timestamp: string; contents: string };
    messageText: string;
  } | null>(null);
  const pendingPersistBySessionRef = useRef(new Map<string, {
    sessionId: string;
    userMessage: { timestamp: string; contents: string };
    messageText: string;
  }>());

  // 当前 assistant 消息 ID
  const assistantMsgIdRef = useRef<number>(0);

  // 加载超时计时器：收到消息时重置，120秒无新消息自动停止
  const loadingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const startLoadingTimer = useCallback(() => {
    if (loadingTimerRef.current) clearTimeout(loadingTimerRef.current);
    loadingTimerRef.current = setTimeout(() => {
      console.log('[ChatView] Loading timeout (120s), auto-stopping');
      const timedOutSessionId = streamingSessionIdRef.current;
      setIsLoading(false);
      isStreamingRef.current = false;
      streamingSessionIdRef.current = null;
      if (timedOutSessionId) {
        onSessionRunStateChangeRef.current?.(timedOutSessionId, 'error');
      }
    }, 120000);
  }, []);

  const clearLoadingTimer = useCallback(() => {
    if (loadingTimerRef.current) {
      clearTimeout(loadingTimerRef.current);
      loadingTimerRef.current = null;
    }
  }, []);

  // 更新 ref（流式输出期间不更新，避免 temp→real ID 切换导致 WS 回调丢消息）
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

  // 重置 initialPrompt 状态
  useEffect(() => {
    if (!initialPrompt) {
      initialPromptSentRef.current = false;
      aiCreateRef.current = null;
    }
  }, [initialPrompt]);

  // 构建当前累积内容的 ContentItem 数组 — 直接映射有序 segment
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
        // TEXT — 过滤末尾模型 trace
        let text = seg.text.trim();
        text = text.replace(/`\s*\([\w.\-]+(?:,\s*\d+\.?\d*\w+)*\)\s*`\s*$/, '');
        text = text.replace(/\([\w.\-]+(?:,\s*\d+\.?\d*\w+)*\)\s*$/, '');
        return { type: 'TEXT' as const, text, agentName: seg.agentName };
      })
      .filter(item => item.text.length > 0);
  }

  // 注册消息回调（只注册一次，通过 ref 获取当前 sessionId）
  useEffect(() => {
    const wsManager = WebSocketManager.getInstance();

    // 持久化待保存的用户消息（仅保存消息，不触发会话持久化）
    // 返回 pending 信息供 done/error 后触发会话持久化
    async function flushPendingUserMessage(sessionId?: string): Promise<{ sessionId: string; title: string; wasNew: boolean } | null> {
      const pending = sessionId
        ? pendingPersistBySessionRef.current.get(sessionId)
        : pendingPersistRef.current;
      if (!pending) return null;
      pendingPersistBySessionRef.current.delete(pending.sessionId);
      if (pendingPersistRef.current?.sessionId === pending.sessionId) {
        pendingPersistRef.current = null;
      }

      await saveMessage({
        conversationId: pending.sessionId,
        role: 'USER',
        timestamp: pending.userMessage.timestamp,
        contents: pending.userMessage.contents,
        workspacePath: workspacePathRef.current,
      });
      onSessionMessageSavedRef.current?.(pending.sessionId, 1);

      return {
        sessionId: pending.sessionId,
        title: pending.messageText.trim().slice(0, 20) + (pending.messageText.trim().length > 20 ? '...' : ''),
        wasNew: pending.sessionId.startsWith('temp-'),
      };
    }

    const handleMessage = async (data: any) => {
      const msgSessionId = (data.sessionId || conversationIdRef.current.toString()).toString();
      const isCurrentSession = msgSessionId === conversationIdRef.current.toString() || msgSessionId === sessionIdRef.current;

      // done / error 类型必须处理，不受 session 校验限制（保证 loading 状态正确）
      if (data.type === 'done') {
        if (!isCurrentSession) {
          const pending = await flushPendingUserMessage(msgSessionId);
          const backgroundSegments = backgroundContentBySessionRef.current.get(msgSessionId) || [];
          const contentItems = buildContentItems(backgroundSegments);
          if (contentItems.length > 0) {
            await saveMessage({
              conversationId: pending?.sessionId || msgSessionId,
              role: 'ASSISTANT',
              timestamp: new Date().toLocaleTimeString(),
              contents: JSON.stringify({ items: contentItems, metadata: {
                modelName: data.modelName,
                totalTokens: data.totalTokens,
                elapsedMs: data.elapsedMs,
              } }),
              workspacePath: workspacePathRef.current,
            });
            onSessionMessageSavedRef.current?.(pending?.sessionId || msgSessionId, 1);
          }
          if (pending?.wasNew && onUpdateSessionTitleRef.current) {
            onUpdateSessionTitleRef.current(pending.sessionId, pending.title);
          }
          backgroundContentBySessionRef.current.delete(msgSessionId);
          if (streamingSessionIdRef.current === msgSessionId) {
            streamingSessionIdRef.current = null;
            isStreamingRef.current = false;
            setIsLoading(false);
          }
          return;
        }
        clearLoadingTimer();

        // 持久化用户消息（如果是新会话）
        const pending = await flushPendingUserMessage(msgSessionId);

        // 构建最终消息
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

          // 保存助手消息（用 temp ID，后续 reassignMessages 会统一转换）
          await saveMessage({
            conversationId: pending?.sessionId || msgSessionId,
            role: 'ASSISTANT',
            timestamp: finalMsg.timestamp,
            contents: JSON.stringify({ items: contentItems, metadata: finalMsg.metadata }),
            workspacePath: workspacePathRef.current,
          });
          onSessionMessageSavedRef.current?.(pending?.sessionId || msgSessionId, 1);
        }

        // 所有消息保存后，触发会话持久化（reassignMessages 会把 temp ID 转为 real ID）
        if (pending?.wasNew && onUpdateSessionTitleRef.current) {
          onUpdateSessionTitleRef.current(pending.sessionId, pending.title);
        }

        // AI 创建自动保存
        if (aiCreateRef.current) {
          const { type, name } = aiCreateRef.current;
          const aiContent = accumulatedContentRef.current
            .filter(seg => seg.type === 'TEXT')
            .map(seg => seg.text.trim())
            .join('\n')
            .trim();
          if (aiContent) {
            try {
              if (type === 'skill') {
                await invoke('create_skill', { name, description: '', content: aiContent });
              } else {
                await invoke('create_agent', { name, description: '', content: aiContent });
              }
              onAiCreateComplete?.({ type, name });
            } catch (err) {
              console.error('[ChatView] AI 创建自动保存失败:', err);
            }
          }
          aiCreateRef.current = null;
        }

        // 重置累积器
        accumulatedContentRef.current = [];
        backgroundContentBySessionRef.current.delete(msgSessionId);
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
        if (!isCurrentSession) {
          const pending = await flushPendingUserMessage(msgSessionId);
          await saveMessage({
            conversationId: pending?.sessionId || msgSessionId,
            role: 'ERROR',
            timestamp: new Date().toLocaleTimeString(),
            contents: JSON.stringify([{ type: 'ERROR', text: data.text || '未知错误' }]),
            workspacePath: workspacePathRef.current,
          });
          onSessionMessageSavedRef.current?.(pending?.sessionId || msgSessionId, 1);
          if (pending?.wasNew && onUpdateSessionTitleRef.current) {
            onUpdateSessionTitleRef.current(pending.sessionId, pending.title);
          }
          backgroundContentBySessionRef.current.delete(msgSessionId);
          if (streamingSessionIdRef.current === msgSessionId) {
            streamingSessionIdRef.current = null;
            isStreamingRef.current = false;
            setIsLoading(false);
          }
          return;
        }
        clearLoadingTimer();

        // 即使出错也要持久化用户消息
        const pending = await flushPendingUserMessage(msgSessionId);

        const errorText = data.text || '未知错误';
        const errorMsg: Message = {
          id: Date.now(),
          role: 'ERROR',
          timestamp: new Date().toLocaleTimeString(),
          contents: [{ type: 'ERROR', text: errorText }]
        };
        setMessages(prev => [...prev, errorMsg]);

        await saveMessage({
          conversationId: pending?.sessionId || msgSessionId,
          role: 'ERROR',
          timestamp: errorMsg.timestamp,
          contents: JSON.stringify(errorMsg.contents),
          workspacePath: workspacePathRef.current,
        });
        onSessionMessageSavedRef.current?.(pending?.sessionId || msgSessionId, 1);

        // 所有消息保存后，触发会话持久化
        if (pending?.wasNew && onUpdateSessionTitleRef.current) {
          onUpdateSessionTitleRef.current(pending.sessionId, pending.title);
        }

        backgroundContentBySessionRef.current.delete(msgSessionId);
        setIsLoading(false);
        isStreamingRef.current = false;
        if (streamingSessionIdRef.current === msgSessionId) {
          streamingSessionIdRef.current = null;
        }
        return;
      }

      // 其他消息类型检查是否属于当前会话（同时接受 temp ID 和重分配后的真实 ID）
      // HITL 审批请求 — 直接追加到当前消息
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
      const type = (rawType === 'COMMAND' ? 'TEXT' : rawType) as ContentType;
      let text = filterEmptyTags(data.text || '');
      if (rawType === 'COMMAND') text += '\n';

      if (text === '') return;

      // 收到任何内容消息，重置加载超时计时器
      if (isCurrentSession) startLoadingTimer();

      // 累积内容
      // 累积内容 — 保留交错顺序
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
        case 'REASON':
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

      // 实时更新显示（RAF 节流，合并多次 chunk）
      if (isCurrentSession) scheduleMessageUpdate();
    };

    wsManager.registerCallback(handleMessage);
    wsManager.registerStatusCallback((sessionId, status) => {
      onSessionRunStateChangeRef.current?.(sessionId, status);
    });

    return () => {
      wsManager.unregisterCallback();
    };
  }, []);

  const sendMessage = useCallback(async (messageText: string, options: SendOptions) => {
    let sessionId = currentConversation.id?.toString();

    // 无会话时，创建新会话（标题取消息前20字），然后继续发送
    if (!sessionId) {
      if (!onNewSession) return;
      const title = messageText.trim().slice(0, 20) + (messageText.trim().length > 20 ? '...' : '');
      sessionId = onNewSession(title);
      sessionIdRef.current = sessionId;
      conversationIdRef.current = sessionId;
    }

    let fullMessage = messageText;
    const contextParts: string[] = [];

    if (activeFilePath) {
      const activeFileContext = await buildActiveFileContext(activeFilePath, workspacePath);
      if (activeFileContext) contextParts.push(activeFileContext);
    }

    if (options.contexts.length > 0) {
      contextParts.push(options.contexts.map(c => `[${c.name}]`).join(' '));
    }

    if (contextParts.length > 0) {
      fullMessage = `${contextParts.join('\n\n')}\n\n${messageText}`;
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

    const userMessage: Message = {
      id: Date.now(),
      role: 'USER',
      timestamp: new Date().toLocaleTimeString(),
      contents: [{ type: 'TEXT', text: fullMessage }]
    };

    setMessages(prev => [...prev, userMessage]);

    // 标记流式状态，防止会话 ID 变化时重新加载消息
    isStreamingRef.current = true;
    streamingSessionIdRef.current = sessionId!;

    setIsLoading(true);
    startLoadingTimer(); // 开始超时计时

    // 重置累积器
    accumulatedContentRef.current = [];
    const streamingSegments: AccSegment[] = [];
    accumulatedContentRef.current = streamingSegments;
    backgroundContentBySessionRef.current.set(sessionId!, streamingSegments);

    assistantMsgIdRef.current = Date.now() + Math.floor(Math.random() * 1000);

    chatMessagesRef.current?.scrollToBottom();

    // 暂存用户消息信息，等 done/error 时再真正持久化
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

      // 开启会话时注册模型到后端
      // options.model 格式: "providerId" 或 "providerId__modelId"
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

      // 用实际模型名发送
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

      // 附件数据（图片 base64，文本内容）
      if (options.attachments && options.attachments.length > 0) {
        request.attachments = options.attachments.map(att => {
          if (att.type === 'image') {
            // content 是 data URL: "data:image/png;base64,..."
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
        await saveMessage({
          conversationId: pending.sessionId,
          role: 'USER',
          timestamp: pending.userMessage.timestamp,
          contents: pending.userMessage.contents,
          workspacePath,
        });
        onSessionMessageSaved?.(pending.sessionId, 1);

        const errorMessage: Message = {
          id: Date.now() + 1,
          role: 'ERROR',
          timestamp: new Date().toLocaleTimeString(),
          contents: [{ type: 'ERROR', text: `请求失败: ${error instanceof Error ? error.message : '未知错误'}` }]
        };
        setMessages(prev => [...prev, errorMessage]);

        await saveMessage({
          conversationId: pending.sessionId,
          role: 'ERROR',
          timestamp: errorMessage.timestamp,
          contents: JSON.stringify(errorMessage.contents),
          workspacePath,
        });
        onSessionMessageSaved?.(pending.sessionId, 1);

        // 触发会话持久化（reassignMessages 会处理 temp→real）
        if (pending.sessionId.startsWith('temp-') && onUpdateSessionTitle) {
          onUpdateSessionTitle(pending.sessionId, pending.messageText.trim().slice(0, 20) + (pending.messageText.trim().length > 20 ? '...' : ''));
        }
      }

      setIsLoading(false);
      isStreamingRef.current = false;
      if (streamingSessionIdRef.current === sessionId) {
        streamingSessionIdRef.current = null;
      }
    }
  }, [currentConversation, onNewSession, onUpdateSessionTitle, workspacePath, providers, activeFilePath, maxSteps]);

  // AI 创建：自动发送初始 prompt
  useEffect(() => {
    if (!initialPrompt || initialPromptSentRef.current) return;
    const convId = currentConversation.id?.toString();
    if (!convId) return;

    initialPromptSentRef.current = true;
    aiCreateRef.current = { type: initialPrompt.type, name: initialPrompt.name };

    sendMessage(initialPrompt.prompt, {
      model: activeProviderId || '',
      modelName: '',
      agent: '',
      contexts: [],
      attachments: [],
      reasoningEffort: 'medium',
    });
  }, [initialPrompt, currentConversation.id, sendMessage, activeProviderId]);

  async function loadConversationMessages(convId: string | number) {
    const storedMessages = await getMessagesByConversation(convId);

    if (storedMessages.length > 0) {
      setMessages(storedMessages.map((msg, index) => {
        let parsed: any = typeof msg.contents === 'string' ? JSON.parse(msg.contents) : msg.contents;
        // 兼容新旧格式：新格式 { items, metadata }，旧格式为数组
        let contents = Array.isArray(parsed) ? parsed : parsed.items || [];
        let metadata = !Array.isArray(parsed) && parsed.metadata ? parsed.metadata : undefined;
        return {
          id: Date.now() + index,
          role: (msg.role as string).toUpperCase() as Message['role'],
          timestamp: msg.timestamp || new Date().toLocaleTimeString(),
          contents: contents.map((c: any) => ({ ...c, type: (c.type as string).toUpperCase() })),
          metadata,
        };
      }));
    } else {
      setMessages([]);
    }
  }

  // 会话切换时加载/清空消息
  // 依赖 currentConversation.id（string | number）而非整个对象，避免 sessions 变化导致误触发
  const currentConversationId = currentConversation.id;
  useEffect(() => {
    const id = currentConversationId?.toString();

    if (!id) {
      setMessages([]);
      return;
    }

    // 临时会话：清空消息
    if (streamingSessionIdRef.current === id) {
      const liveSegments = backgroundContentBySessionRef.current.get(id) || accumulatedContentRef.current;
      const liveItems = buildContentItems(liveSegments);
      if (liveItems.length > 0) {
        setMessages([{
          id: assistantMsgIdRef.current,
          role: 'ASSISTANT',
          timestamp: new Date().toLocaleTimeString(),
          contents: liveItems,
        }]);
        return;
      }
    }

    if (id.startsWith('temp-') || id.startsWith('pending-')) {
      setMessages([]);
      return;
    }

    // 正在流式输出时（ID 从 temp 替换为 real），跳过加载
    if (streamingSessionIdRef.current === id) {
      const liveSegments = backgroundContentBySessionRef.current.get(id) || accumulatedContentRef.current;
      const liveItems = buildContentItems(liveSegments);
      if (liveItems.length > 0) {
        setMessages([{
          id: assistantMsgIdRef.current,
          role: 'ASSISTANT',
          timestamp: new Date().toLocaleTimeString(),
          contents: liveItems,
        }]);
        return;
      }
    }


    // 正常会话：从数据库加载历史消息
    loadConversationMessages(id);
  }, [currentConversationId]);

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
      // 短连接发送
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

  const handleStop = useCallback(() => {
    const stoppedSessionId = sessionIdRef.current;
    WebSocketManager.getInstance().cancelSession(stoppedSessionId);
    clearLoadingTimer();
    setIsLoading(false);
    if (streamingSessionIdRef.current === stoppedSessionId) {
      streamingSessionIdRef.current = null;
      isStreamingRef.current = false;
      onSessionRunStateChangeRef.current?.(stoppedSessionId, 'completed');
    }

    // 保留当前已累积的内容作为最终消息
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

    // 重置累积器
    accumulatedContentRef.current = [];
    backgroundContentBySessionRef.current.delete(stoppedSessionId);
  }, []);

  // 模型切换时推送配置到后端
  const handleModelChange = useCallback(async (compositeId: string) => {
    // compositeId 格式: "providerId__modelId" 或 "providerId"（无 availableModels 时）
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
  const currentRunState = currentConversationIdString ? sessionRunStates[currentConversationIdString] : undefined;
  const isCurrentConversationLoading = currentRunState === 'running' || (isLoading && streamingSessionIdRef.current === currentConversationIdString);
  const showReviewButton = reviewFiles.length > 0 && !isCurrentConversationLoading;
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
  const headerTasks = useMemo<ChatHeaderTask[]>(() => {
    const scopedSessions = workspacePath
      ? sessions.filter(session => session.workspacePath === workspacePath)
      : sessions;
    return scopedSessions.map(session => ({
      id: session.id,
      title: session.title,
      timestamp: session.timestamp,
      messageCount: session.messageCount,
      status: sessionRunStates[session.id],
    }));
  }, [sessions, sessionRunStates, workspacePath]);

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
          totalConversations={headerTasks.length}
          tasks={headerTasks}
          activeTaskId={currentConversationIdString}
          onSelectTask={onSelectSession}
          reviewFiles={reviewFiles}
          onReviewFileSelect={onReviewFileSelect}
          openInfoSignal={reviewInfoSignal}
        />
      )}
      <ChatMessages ref={chatMessagesRef} messages={messages} isLoading={isCurrentConversationLoading} theme={theme} projectName={projectName} onDeleteMessage={handleDeleteMessage} onHitlAction={handleHitlAction} onFileSelect={onFileSelect} />

      {isEmpty ? (
        <div className="empty-center-container">
          <div className="empty-state-hero">
            <div className="hero-logo">SolonCode</div>
            <div className="hero-slogan">{newSessionFromProject && projectName ? `在 ${projectName} ` : ''}做你想做的事</div>
          </div>
          {showReviewButton && (
            <ReviewPrompt files={reviewFiles} onReview={() => setReviewInfoSignal(value => value + 1)} />
          )}
          <ChatInput onSend={sendMessage} isLoading={isCurrentConversationLoading} onStop={handleStop} providers={providers} activeProviderId={activeProviderId} onModelChange={handleModelChange} activeFileName={activeFileName} backendPort={backendPort} showStartWork={!workspacePath} onNewProject={onNewProject} onOpenFolder={onOpenFolder} workspacePath={workspacePath} mode={chatMode} onModeChange={setChatMode} baseContextTokens={baseContextTokens} />
        </div>
      ) : (
        <>
          {showReviewButton && (
            <ReviewPrompt files={reviewFiles} onReview={() => setReviewInfoSignal(value => value + 1)} />
          )}
          <ChatInput onSend={sendMessage} isLoading={isCurrentConversationLoading} onStop={handleStop} providers={providers} activeProviderId={activeProviderId} onModelChange={handleModelChange} activeFileName={activeFileName} backendPort={backendPort} showStartWork={!workspacePath} onNewProject={onNewProject} onOpenFolder={onOpenFolder} workspacePath={workspacePath} mode={chatMode} onModeChange={setChatMode} baseContextTokens={baseContextTokens} />
        </>
      )}
      {/* 底部提示 */}
        <div className="input-footer">
          <span className="input-hint">
            Enter 发送，Shift + Enter 换行，/ 命令，# 引用上下文，@ 选择智能体
          </span>
        </div>
    </main>
  );
}

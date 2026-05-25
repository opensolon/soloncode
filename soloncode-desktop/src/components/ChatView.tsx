import { useState, useEffect, useRef, useCallback } from 'react';
import { invoke } from '@tauri-apps/api/core';
import type { Message, Conversation, Theme, Plugin, ContentType, ContentItem } from '../types';
import type { ModelProvider } from '../services/settingsService';
import { saveMessage, getMessagesByConversation } from '../db';
import { ChatHeader } from './ChatHeader';
import { ChatMessages } from './ChatMessages';
import { ChatInput, type SendOptions, type ChatMode } from './ChatInput';
import { Icon } from './common/Icon';
import '../views/ChatPage.css';

interface ChatViewProps {
  currentConversation: Conversation;
  plugins?: Plugin[];
  workspacePath?: string;
  projectName?: string;
  theme?: Theme;
  backendPort?: number | null;
  onUpdateSessionTitle?: (sessionId: string, title: string) => void;
  onNewSession?: (title?: string) => string;
  providers?: ModelProvider[];
  activeProviderId?: string;
  onActiveProviderChange?: (providerId: string) => void;
  activeFileName?: string;
  activeFilePath?: string;
  onNewProject?: () => void;
  onOpenFolder?: () => void;
  initialPrompt?: {
    prompt: string;
    type: 'skill' | 'agent';
    name: string;
  } | null;
  onAiCreateComplete?: (info: { type: 'skill' | 'agent'; name: string }) => void;
  newSessionFromProject?: boolean;
}

// 全局 WebSocket 连接管理器（每次请求独立连接）
class WebSocketManager {
  private static instance: WebSocketManager | null = null;
  private activeWs: WebSocket | null = null;
  private messageCallback: ((data: any) => void) | null = null;
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

  unregisterCallback() {
    this.messageCallback = null;
  }

  async sendMessage(request: any): Promise<void> {
    // 先关闭上一次的连接
    this.closeActive();

    const sessionId = request.sessionId;
    const ws = await this.createConnection(sessionId);
    this.activeWs = ws;

    ws.onmessage = (event) => {
      try {
        const data = event.data;
        if (data.trim() === '[DONE]') {
          ws.close();
          return;
        }
        const msg = JSON.parse(data);
        this.messageCallback?.(msg);
      } catch (e) {
        console.warn('[WS] Failed to parse message:', event.data, e);
      }
    };

    ws.onclose = () => {
      if (this.activeWs === ws) {
        this.activeWs = null;
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

  disconnect() {
    this.closeActive();
    this.messageCallback = null;
  }

  /** 推送配置变更到后端（短连接） */
  async sendConfig(chatModel: { apiUrl?: string; apiKey?: string; model?: string }): Promise<void> {
    const ws = await this.createConnection();
    ws.send(JSON.stringify({ type: 'config', chatModel }));
    ws.close();
  }

  private closeActive() {
    if (this.activeWs) {
      this.activeWs.close();
      this.activeWs = null;
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
async function registerModelToBackend(provider: { apiUrl: string; apiKey: string; model: string; type?: string }, select?: boolean) {
  const port = WebSocketManager.getInstance().getBackendPort() || FALLBACK_PORT;
  try {
    const resp = await fetch(`http://localhost:${port}/chat/models/add`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: provider.model,
        apiUrl: provider.apiUrl,
        apiKey: provider.apiKey,
        model: provider.model,
        provider: provider.type || 'openai',
        timeout: 'PT120S',
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
export async function sendModelConfig(provider: { apiUrl: string; apiKey: string; model: string; type?: string }) {
  await registerModelToBackend(provider, true);
}

export function ChatView({ currentConversation, plugins, workspacePath, projectName, theme = 'dark', backendPort, onUpdateSessionTitle, onNewSession, providers = [], activeProviderId, onActiveProviderChange, activeFileName, activeFilePath, onNewProject, onOpenFolder, initialPrompt, onAiCreateComplete, newSessionFromProject }: ChatViewProps) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [chatMode, setChatMode] = useState<ChatMode>('default');
  const chatMessagesRef = useRef<{ scrollToBottom: () => void } | null>(null);
  const sessionIdRef = useRef<string>('');
  const conversationIdRef = useRef<string | number>('');
  const isStreamingRef = useRef(false);
  const aiCreateRef = useRef<{ type: 'skill' | 'agent'; name: string } | null>(null);
  const initialPromptSentRef = useRef(false);

  // 累积的消息内容 - 只有 think 标签内的才是思考块
  const accumulatedContentRef = useRef<{
    think: string;
    text: string;
    actions: Array<{
      text: string;
      toolName?: string;
      args?: Record<string, unknown>;
    }>;
  }>({
    think: '',
    text: '',
    actions: [],
  });

  // 待持久化的首条用户消息（新会话时暂存，done/error 时真正保存）
  const pendingPersistRef = useRef<{
    sessionId: string;
    userMessage: { timestamp: string; contents: string };
    messageText: string;
  } | null>(null);

  // 当前 assistant 消息 ID
  const assistantMsgIdRef = useRef<number>(0);

  // 加载超时计时器：收到消息时重置，120秒无新消息自动停止
  const loadingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const startLoadingTimer = useCallback(() => {
    if (loadingTimerRef.current) clearTimeout(loadingTimerRef.current);
    loadingTimerRef.current = setTimeout(() => {
      console.log('[ChatView] Loading timeout (120s), auto-stopping');
      setIsLoading(false);
      isStreamingRef.current = false;
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
    if (isStreamingRef.current) return;
    sessionIdRef.current = currentConversation.id.toString();
    conversationIdRef.current = currentConversation.id;
  }, [currentConversation.id]);

  // 重置 initialPrompt 状态
  useEffect(() => {
    if (!initialPrompt) {
      initialPromptSentRef.current = false;
      aiCreateRef.current = null;
    }
  }, [initialPrompt]);

  // 构建当前累积内容的 ContentItem 数组
  function buildContentItems(): ContentItem[] {
    const acc = accumulatedContentRef.current;
    const items: ContentItem[] = [];

    // 只有 think 标签内的内容才是思考块（可折叠）
    if (acc.think.trim()) {
      items.push({ type: 'THINK', text: acc.think.trim() });
    }

    // 每个工具调用独立显示
    for (const act of acc.actions) {
      if (act.text.trim()) {
        items.push({
          type: 'ACTION',
          text: act.text.trim(),
          toolName: act.toolName,
          args: act.args
        });
      }
    }

    // 正文内容（包括 reason 和 text 类型）
    if (acc.text.trim()) {
      let text = acc.text.trim();
      // 过滤末尾的模型 trace 信息，如 (glm-4.7, 6985tk, 4s) 或 `(...)` 包裹
      text = text.replace(/`\s*\([\w.\-]+(?:,\s*\d+\.?\d*\w+)*\)\s*`\s*$/, '');
      text = text.replace(/\([\w.\-]+(?:,\s*\d+\.?\d*\w+)*\)\s*$/, '');
      items.push({ type: 'TEXT', text });
    }

    return items;
  }

  // 注册消息回调（只注册一次，通过 ref 获取当前 sessionId）
  useEffect(() => {
    const wsManager = WebSocketManager.getInstance();

    // 持久化待保存的用户消息（仅保存消息，不触发会话持久化）
    // 返回 pending 信息供 done/error 后触发会话持久化
    async function flushPendingUserMessage(): Promise<{ sessionId: string; title: string; wasNew: boolean } | null> {
      const pending = pendingPersistRef.current;
      if (!pending) return null;
      pendingPersistRef.current = null;

      await saveMessage({
        conversationId: pending.sessionId,
        role: 'USER',
        timestamp: pending.userMessage.timestamp,
        contents: pending.userMessage.contents,
        workspacePath,
      });

      return {
        sessionId: pending.sessionId,
        title: pending.messageText.trim().slice(0, 20) + (pending.messageText.trim().length > 20 ? '...' : ''),
        wasNew: pending.sessionId.startsWith('temp-'),
      };
    }

    const handleMessage = async (data: any) => {
      const msgSessionId = data.sessionId || conversationIdRef.current.toString();

      // done / error 类型必须处理，不受 session 校验限制（保证 loading 状态正确）
      if (data.type === 'done') {
        clearLoadingTimer();

        // 持久化用户消息（如果是新会话）
        const pending = await flushPendingUserMessage();

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
            workspacePath,
          });
        }

        // 所有消息保存后，触发会话持久化（reassignMessages 会把 temp ID 转为 real ID）
        if (pending?.wasNew && onUpdateSessionTitle) {
          onUpdateSessionTitle(pending.sessionId, pending.title);
        }

        // AI 创建自动保存
        if (aiCreateRef.current) {
          const { type, name } = aiCreateRef.current;
          const aiContent = accumulatedContentRef.current.text.trim();
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
        accumulatedContentRef.current = {
          think: '',
          text: '',
          actions: [],
        };

        setIsLoading(false);
        isStreamingRef.current = false;
        chatMessagesRef.current?.scrollToBottom();
        return;
      }

      if (data.type === 'error') {
        clearLoadingTimer();

        // 即使出错也要持久化用户消息
        const pending = await flushPendingUserMessage();

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
          workspacePath,
        });

        // 所有消息保存后，触发会话持久化
        if (pending?.wasNew && onUpdateSessionTitle) {
          onUpdateSessionTitle(pending.sessionId, pending.title);
        }

        setIsLoading(false);
        isStreamingRef.current = false;
        return;
      }

      // 其他消息类型检查是否属于当前会话（同时接受 temp ID 和重分配后的真实 ID）
      if (msgSessionId !== conversationIdRef.current.toString() && msgSessionId !== sessionIdRef.current) {
        return;
      }

      // HITL 审批请求 — 直接追加到当前消息
      if (data.type === 'hitl') {
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
      startLoadingTimer();

      // 累积内容
      // 注意：只有 think 类型（<think/`thinking`> 标签内）才是思考块
      // reason 类型也是正文内容
      const acc = accumulatedContentRef.current;
      switch (type) {
        case 'THINK':
          acc.think += text;
          break;
        case 'TEXT':
          acc.text += text;
          break;
        case 'REASON':
          acc.text += text;
          break;
        case 'ACTION':
          if (data.toolName) {
            // 新的工具调用开始，推入新条目
            acc.actions.push({
              text: text,
              toolName: data.toolName,
              args: data.args
            });
          } else if (acc.actions.length > 0) {
            // 追加到当前最后一个 action
            acc.actions[acc.actions.length - 1].text += text;
          } else {
            // 没有 toolName 且没有已有 action，创建一个
            acc.actions.push({ text });
          }
          break;
        case 'TEXT':
          acc.text += text;
          break;
      }

      // 实时更新显示（显示当前累积的内容）
      setMessages(prev => {
        const contentItems = buildContentItems();
        const tempMsg: Message = {
          id: assistantMsgIdRef.current,
          role: 'ASSISTANT',
          timestamp: new Date().toLocaleTimeString(),
          contents: contentItems
        };

        // 查找是否已有临时消息
        const existingIndex = prev.findIndex(m => m.id === assistantMsgIdRef.current);
        if (existingIndex >= 0) {
          const updated = [...prev];
          updated[existingIndex] = tempMsg;
          return updated;
        }
        return [...prev, tempMsg];
      });

      chatMessagesRef.current?.scrollToBottom();
    };

    wsManager.registerCallback(handleMessage);

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

    if (activeFilePath) {
      // Todo 需要将当前文件加入到上下文中
    }

    if (options.contexts.length > 0) {
      const contextStr = options.contexts.map(c => `[${c.name}]`).join(' ');
      fullMessage = `${contextStr}\n\n${messageText}`;
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

    setIsLoading(true);
    startLoadingTimer(); // 开始超时计时

    // 重置累积器
    accumulatedContentRef.current = {
      think: '',
      text: '',
      actions: [],
    };

    assistantMsgIdRef.current = Date.now() + Math.floor(Math.random() * 1000);

    chatMessagesRef.current?.scrollToBottom();

    // 暂存用户消息信息，等 done/error 时再真正持久化
    pendingPersistRef.current = {
      sessionId: sessionId!,
      userMessage: {
        timestamp: userMessage.timestamp,
        contents: JSON.stringify(userMessage.contents),
      },
      messageText,
    };

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
      if (selectedProvider) {
        await registerModelToBackend({ ...selectedProvider, model: actualModelId });
      }

      // 用实际模型名发送
      const modelName = actualModelId;

      const request: Record<string, unknown> = {
        input: fullMessage,
        sessionId: sessionId,
        model: modelName,
        agent: options.agent,
        cwd: workspacePath || undefined,
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
      const pending = pendingPersistRef.current;
      if (pending) {
        pendingPersistRef.current = null;
        await saveMessage({
          conversationId: pending.sessionId,
          role: 'USER',
          timestamp: pending.userMessage.timestamp,
          contents: pending.userMessage.contents,
          workspacePath,
        });

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

        // 触发会话持久化（reassignMessages 会处理 temp→real）
        if (pending.sessionId.startsWith('temp-') && onUpdateSessionTitle) {
          onUpdateSessionTitle(pending.sessionId, pending.messageText.trim().slice(0, 20) + (pending.messageText.trim().length > 20 ? '...' : ''));
        }
      }

      setIsLoading(false);
      isStreamingRef.current = false;
    }
  }, [currentConversation, onNewSession, onUpdateSessionTitle, workspacePath, providers]);

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
    if (id.startsWith('temp-') || id.startsWith('pending-')) {
      setMessages([]);
      return;
    }

    // 正在流式输出时（ID 从 temp 替换为 real），跳过加载
    if (isStreamingRef.current) {
      return;
    }

    // 正常会话：从数据库加载历史消息
    loadConversationMessages(id);
  }, [currentConversationId]);

  // 停止当前请求
  const handleHitlAction = useCallback(async (action: 'approve' | 'reject') => {
    const ws = WebSocketManager.getInstance() as any;
    if (ws.activeWs && ws.activeWs.readyState === WebSocket.OPEN) {
      ws.activeWs.send(JSON.stringify({
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
    WebSocketManager.getInstance().cancel();
    clearLoadingTimer();
    setIsLoading(false);

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
    accumulatedContentRef.current = {
      think: '',
      text: '',
      actions: [],
    };
  }, []);

  // 模型切换时推送配置到后端
  const handleModelChange = useCallback(async (compositeId: string) => {
    // compositeId 格式: "providerId__modelId" 或 "providerId"（无 availableModels 时）
    const sepIdx = compositeId.indexOf('__');
    const providerId = sepIdx >= 0 ? compositeId.substring(0, sepIdx) : compositeId;
    const provider = providers.find(p => p.id === providerId);
    if (provider) {
      onActiveProviderChange?.(compositeId);
      await registerModelToBackend(provider, true);
    }
  }, [providers, onActiveProviderChange]);

  const isEmpty = messages.length === 0 && !isLoading;
  const showHeader = !isEmpty;

  const handleDeleteMessage = useCallback((id: number) => {
    setMessages(prev => prev.filter(m => m.id !== id));
  }, []);

  return (
    <main className={`main-content${isEmpty ? ' empty-state' : ''}`}>
      {showHeader && (
        <ChatHeader title={currentConversation.title} status={currentConversation.status} projectName={currentConversation.workspacePath && currentConversation.workspacePath === workspacePath ? projectName : undefined} />
      )}
      <ChatMessages ref={chatMessagesRef} messages={messages} isLoading={isLoading} theme={theme} projectName={projectName} onDeleteMessage={handleDeleteMessage} onHitlAction={handleHitlAction} />

      {isEmpty ? (
        <div className="empty-center-container">
          <div className="empty-state-hero">
            <div className="hero-logo">SolonCode</div>
            <div className="hero-slogan">{newSessionFromProject && projectName ? `在 ${projectName} ` : ''}做你想做的事</div>
          </div>
          <ChatInput onSend={sendMessage} isLoading={isLoading} onStop={handleStop} providers={providers} activeProviderId={activeProviderId} onModelChange={handleModelChange} activeFileName={activeFileName} backendPort={backendPort} showStartWork={!workspacePath} onNewProject={onNewProject} onOpenFolder={onOpenFolder} workspacePath={workspacePath} mode={chatMode} onModeChange={setChatMode} />
        </div>
      ) : (
        <ChatInput onSend={sendMessage} isLoading={isLoading} onStop={handleStop} providers={providers} activeProviderId={activeProviderId} onModelChange={handleModelChange} activeFileName={activeFileName} backendPort={backendPort} showStartWork={!workspacePath} onNewProject={onNewProject} onOpenFolder={onOpenFolder} workspacePath={workspacePath} />
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

import { useState, useEffect, useRef, useCallback } from 'react';
import type { Message, Conversation, Theme, Plugin, ContentType, ContentItem } from '../types';
import type { ModelProvider } from '../services/settingsService';
import { saveMessage, getMessagesByConversation } from '../db';
import { ChatHeader } from './ChatHeader';
import { ChatMessages } from './ChatMessages';
import { ChatInput, type SendOptions } from './ChatInput';
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

// 过滤空标签的辅助函数
function filterEmptyTags(text: string): string {
  let result = text;
  // 过滤空的 HTML/XML 标签（包括带属性的）
  result = result.replace(/<([a-zA-Z][a-zA-Z0-9]*)([^>]*)><\/\1>/g, '');
  result = result.replace(/<([a-zA-Z][a-zA-Z0-9]*)([^>]*)\/>/g, '');
  // 过滤只有空白内容（包括空格、换行、回车）的标签
  // result = result.replace(/<([a-zA-Z][a-zA-Z0-9]*)([^>]*)>[\s\n\r]*<\/\1>/g, '');
  // 过滤连续的空行（超过2个换行符）
  result = result.replace(/\n{3,}/g, '\n\n');
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

export function ChatView({ currentConversation, plugins, workspacePath, projectName, theme = 'dark', backendPort, onUpdateSessionTitle, onNewSession, providers = [], activeProviderId, onActiveProviderChange, activeFileName, activeFilePath, onNewProject, onOpenFolder }: ChatViewProps) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const chatMessagesRef = useRef<{ scrollToBottom: () => void } | null>(null);
  const sessionIdRef = useRef<string>('');
  const conversationIdRef = useRef<string | number>('');
  const isStreamingRef = useRef(false);

  // 累积的消息内容 - 只有 think 标签内的才是思考块
  const accumulatedContentRef = useRef<{
    think: string;      // 思考内容（<think/`thinking`> 标签内，累积）
    text: string;       // 正文内容（包括 reason 和 text 类型，累积）
    actions: Array<{    // 每个工具调用独立一个块
      text: string;
      toolName?: string;
      args?: Record<string, unknown>;
    }>;
  }>({
    think: '',
    text: '',
    actions: [],
  });

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
      items.push({ type: 'TEXT', text: acc.text.trim() });
    }

    return items;
  }

  // 注册消息回调（只注册一次，通过 ref 获取当前 sessionId）
  useEffect(() => {
    const wsManager = WebSocketManager.getInstance();

    const handleMessage = (data: any) => {
      const msgSessionId = data.sessionId || conversationIdRef.current.toString();

      // done / error 类型必须处理，不受 session 校验限制（保证 loading 状态正确）
      if (data.type === 'done') {
        clearLoadingTimer();
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
              elapsedMs: data.elapsedMs
            }
          };

          setMessages(prev => {
            // 移除之前的临时消息，添加最终消息
            const filtered = prev.filter(m => m.id !== assistantMsgIdRef.current);
            return [...filtered, finalMsg];
          });

          // 保存到数据库（包含 metadata）
          saveMessage({
            conversationId: msgSessionId,
            role: 'ASSISTANT',
            timestamp: finalMsg.timestamp,
            contents: JSON.stringify({ items: contentItems, metadata: finalMsg.metadata }),
            workspacePath,
          }).catch(err => console.error('Failed to save message:', err));
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
        const errorText = data.text || '未知错误';
        const errorMsg: Message = {
          id: Date.now(),
          role: 'ERROR',
          timestamp: new Date().toLocaleTimeString(),
          contents: [{ type: 'ERROR', text: errorText }]
        };
        setMessages(prev => [...prev, errorMsg]);
        setIsLoading(false);
        isStreamingRef.current = false;
        return;
      }

      // 其他消息类型检查是否属于当前会话（同时接受 temp ID 和重分配后的真实 ID）
      if (msgSessionId !== conversationIdRef.current.toString() && msgSessionId !== sessionIdRef.current) {
        return;
      }

      const type = (data.type as string).toUpperCase() as ContentType;
      let text = filterEmptyTags(data.text || '');

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
        case 'COMMAND':
          acc.text += text + '\n';
          break;
        case 'REASON':
          // reason 也是正文内容
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

    const userMessage: Message = {
      id: Date.now(),
      role: 'USER',
      timestamp: new Date().toLocaleTimeString(),
      contents: [{ type: 'TEXT', text: fullMessage }]
    };

    setMessages(prev => [...prev, userMessage]);

    // 标记流式状态，防止会话 ID 变化时重新加载消息
    isStreamingRef.current = true;

    // 首次发送时将会话保存到列表
    if (onUpdateSessionTitle && !sessionId.startsWith('temp-')) {
      // 已持久化的会话，仅首次更新标题
    } else if (onUpdateSessionTitle) {
      const title = messageText.trim().slice(0, 20) + (messageText.trim().length > 20 ? '...' : '');
      onUpdateSessionTitle(sessionId, title);
    }

    await saveMessage({
      conversationId: sessionId,
      role: 'USER',
      timestamp: userMessage.timestamp,
      contents: JSON.stringify(userMessage.contents),
      workspacePath,
    });

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

      const request = {
        input: fullMessage,
        sessionId: sessionId,
        model: modelName,
        agent: options.agent,
        cwd: workspacePath || undefined,
      };

      await wsManager.sendMessage(request);

    } catch (error) {
      console.error('Failed to send message:', error);
      const errorMessage: Message = {
        id: Date.now() + 1,
        role: 'ERROR',
        timestamp: new Date().toLocaleTimeString(),
        contents: [{ type: 'ERROR', text: `请求失败: ${error instanceof Error ? error.message : '未知错误'}` }]
      };
      setMessages(prev => [...prev, errorMessage]);

      await saveMessage({
        conversationId: sessionId,
        role: 'ERROR',
        timestamp: errorMessage.timestamp,
        contents: JSON.stringify(errorMessage.contents),
        workspacePath,
      });
      setIsLoading(false);
      isStreamingRef.current = false;
    }
  }, [currentConversation, onNewSession, onUpdateSessionTitle, workspacePath, providers]);

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
      <ChatMessages ref={chatMessagesRef} messages={messages} isLoading={isLoading} theme={theme} onDeleteMessage={handleDeleteMessage} />

      {isEmpty ? (
        <div className="empty-center-container">
          <div className="empty-state-hero">
            <div className="hero-logo">SolonCode</div>
            <div className="hero-slogan">做你想做的事</div>
          </div>
          <ChatInput onSend={sendMessage} isLoading={isLoading} onStop={handleStop} providers={providers} activeProviderId={activeProviderId} onModelChange={handleModelChange} activeFileName={activeFileName} backendPort={backendPort} />
          {!workspacePath && (
            <div className="start-work-panel">
              <div className="panel-title">开始工作</div>
              <div className="start-work-buttons">
                <button className="start-work-btn" onClick={onNewProject}>
                  <Icon name="file" size={14} /> 新建项目
                </button>
                <button className="start-work-btn" onClick={onOpenFolder}>
                  <Icon name="folder" size={14} /> 打开项目
                </button>
              </div>
            </div>
          )}
        </div>
      ) : (
        <ChatInput onSend={sendMessage} isLoading={isLoading} onStop={handleStop} providers={providers} activeProviderId={activeProviderId} onModelChange={handleModelChange} activeFileName={activeFileName} backendPort={backendPort} />
      )}
    </main>
  );
}

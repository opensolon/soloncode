import { useState, useEffect, useRef, useCallback } from 'react';
import type { Message, Conversation, Theme, Plugin, ContentType, ContentItem } from '../types';
import { saveMessage, getMessagesByConversation } from '../db';
import { ChatHeader } from './ChatHeader';
import { ChatMessages } from './ChatMessages';
import { ChatInput, type SendOptions } from './ChatInput';
import '../views/ChatPage.css';

interface ChatViewProps {
  currentConversation: Conversation;
  plugins?: Plugin[];
}

// 全局 WebSocket 连接管理器（单例模式）
class WebSocketManager {
  private static instance: WebSocketManager | null = null;
  private ws: WebSocket | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private messageCallbacks: Map<string, (data: any) => void> = new Map();
  private connectingSessionId: string | null = null;

  static getInstance(): WebSocketManager {
    if (!WebSocketManager.instance) {
      WebSocketManager.instance = new WebSocketManager();
    }
    return WebSocketManager.instance;
  }

  private getWebSocketUrl(): string {
    const host = import.meta.env.VITE_WS_HOST || 'localhost:8080';
    const protocol = import.meta.env.VITE_WS_PROTOCOL || 'ws';
    return `${protocol}://${host}/ws`;
  }

  connect(): Promise<WebSocket> {
    return new Promise((resolve, reject) => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        resolve(this.ws);
        return;
      }

      if (this.ws && this.ws.readyState === WebSocket.CONNECTING) {
        const onOpen = () => {
          this.ws?.removeEventListener('open', onOpen);
          this.ws?.removeEventListener('error', onError);
          resolve(this.ws!);
        };
        const onError = (e: Event) => {
          this.ws?.removeEventListener('open', onOpen);
          this.ws?.removeEventListener('error', onError);
          reject(new Error('WebSocket connection failed'));
        };
        this.ws.addEventListener('open', onOpen);
        this.ws.addEventListener('error', onError);
        return;
      }

      const wsUrl = this.getWebSocketUrl();
      this.ws = new WebSocket(wsUrl);

      this.ws.onopen = () => {
        console.log('[WS] Connected');
        resolve(this.ws!);
      };

      this.ws.onerror = (error) => {
        console.error('[WS] Error:', error);
        reject(new Error('WebSocket connection failed'));
      };

      this.ws.onclose = () => {
        console.log('[WS] Disconnected');
        this.ws = null;
      };

      this.ws.onmessage = (event) => {
        this.handleMessage(event.data);
      };
    });
  }

  private handleMessage(data: string) {
    try {
      if (data.trim() === '[DONE]') {
        return;
      }

      const msg = JSON.parse(data);
      const sessionId = msg.sessionId;

      if (sessionId && this.messageCallbacks.has(sessionId)) {
        const callback = this.messageCallbacks.get(sessionId);
        callback?.(msg);
      } else if (this.connectingSessionId && this.messageCallbacks.has(this.connectingSessionId)) {
        const callback = this.messageCallbacks.get(this.connectingSessionId);
        callback?.(msg);
      }
    } catch (e) {
      console.warn('[WS] Failed to parse message:', data, e);
    }
  }

  registerCallback(sessionId: string, callback: (data: any) => void) {
    this.messageCallbacks.set(sessionId, callback);
  }

  unregisterCallback(sessionId: string) {
    this.messageCallbacks.delete(sessionId);
    if (this.connectingSessionId === sessionId) {
      this.connectingSessionId = null;
    }
  }

  async sendMessage(sessionId: string, request: any): Promise<void> {
    this.connectingSessionId = sessionId;
    const ws = await this.connect();
    ws.send(JSON.stringify(request));
  }

  disconnect() {
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
    this.messageCallbacks.clear();
  }
}

// 过滤空标签的辅助函数
function filterEmptyTags(text: string): string {
  let result = text;
  // 过滤空的 HTML/XML 标签（包括带属性的）
  result = result.replace(/<([a-zA-Z][a-zA-Z0-9]*)([^>]*)><\/\1>/g, '');
  result = result.replace(/<([a-zA-Z][a-zA-Z0-9]*)([^>]*)\/>/g, '');
  // 过滤只有空白内容（包括空格、换行、回车）的标签
  result = result.replace(/<([a-zA-Z][a-zA-Z0-9]*)([^>]*)>[\s\n\r]*<\/\1>/g, '');
  // 过滤连续的空行（超过2个换行符）
  result = result.replace(/\n{3,}/g, '\n\n');
  return result;
}

export function ChatView({ currentConversation }: ChatViewProps) {
  const [currentTheme, setCurrentTheme] = useState<Theme>('dark');
  const [messages, setMessages] = useState<Message[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const chatMessagesRef = useRef<{ scrollToBottom: () => void } | null>(null);
  const sessionIdRef = useRef<string>('');
  const conversationIdRef = useRef<string | number>('');

  // 累积的消息内容 - 只有 think 标签内的才是思考块
  const accumulatedContentRef = useRef<{
    think: string;      // 思考内容（<think/`thinking`> 标签内，累积）
    text: string;       // 正文内容（包括 reason 和 text 类型，累积）
    action: string;     // 工具执行内容
    lastActionInfo: { toolName?: string; args?: Record<string, unknown> } | null;
  }>({
    think: '',
    text: '',
    action: '',
    lastActionInfo: null
  });

  // 当前 assistant 消息 ID
  const assistantMsgIdRef = useRef<number>(0);

  // 更新 ref
  useEffect(() => {
    sessionIdRef.current = currentConversation.id.toString();
    conversationIdRef.current = currentConversation.id;
  }, [currentConversation.id]);

  function toggleTheme() {
    const newTheme = currentTheme === 'dark' ? 'light' : 'dark';
    setCurrentTheme(newTheme);
    document.documentElement.setAttribute('data-theme', newTheme);
    localStorage.setItem('soloncode-theme', newTheme);
  }

  function loadTheme() {
    const savedTheme = localStorage.getItem('soloncode-theme') as Theme | null;
    if (savedTheme) {
      setCurrentTheme(savedTheme);
    } else {
      const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
      setCurrentTheme(prefersDark ? 'dark' : 'light');
    }
    const themeToSet = savedTheme || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    document.documentElement.setAttribute('data-theme', themeToSet);
  }

  // 构建当前累积内容的 ContentItem 数组
  function buildContentItems(): ContentItem[] {
    const acc = accumulatedContentRef.current;
    const items: ContentItem[] = [];

    // 只有 think 标签内的内容才是思考块（可折叠）
    if (acc.think.trim()) {
      items.push({ type: 'think', text: acc.think.trim() });
    }

    // action 单独显示
    if (acc.action.trim()) {
      items.push({
        type: 'action',
        text: acc.action.trim(),
        toolName: acc.lastActionInfo?.toolName,
        args: acc.lastActionInfo?.args
      });
    }

    // 正文内容（包括 reason 和 text 类型）
    if (acc.text.trim()) {
      items.push({ type: 'text', text: acc.text.trim() });
    }

    return items;
  }

  // 注册消息回调
  useEffect(() => {
    const sessionId = currentConversation.id.toString();
    const wsManager = WebSocketManager.getInstance();

    const handleMessage = (data: any) => {
      const msgSessionId = data.sessionId || sessionId;

      // 检查消息是否属于当前会话
      if (msgSessionId !== conversationIdRef.current.toString()) {
        console.log('[WS] Message for different session, ignoring:', msgSessionId);
        return;
      }

      if (data.type === 'done') {
        // 构建最终消息
        const contentItems = buildContentItems();
        if (contentItems.length > 0) {
          const finalMsg: Message = {
            id: assistantMsgIdRef.current,
            role: 'assistant',
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

          // 保存到数据库
          saveMessage({
            conversationId: msgSessionId,
            role: 'assistant',
            timestamp: finalMsg.timestamp,
            contents: JSON.stringify(contentItems)
          }).catch(err => console.error('Failed to save message:', err));
        }

        // 重置累积器
        accumulatedContentRef.current = {
          think: '',
          text: '',
          action: '',
          lastActionInfo: null
        };

        setIsLoading(false);
        chatMessagesRef.current?.scrollToBottom();
        return;
      }

      const type = data.type as ContentType;
      let text = filterEmptyTags(data.text || '');

      if (text.trim() === '') return;

      // 累积内容
      // 注意：只有 think 类型（<think/`thinking`> 标签内）才是思考块
      // reason 类型也是正文内容
      const acc = accumulatedContentRef.current;
      switch (type) {
        case 'think':
          acc.think += text;
          break;
        case 'reason':
          // reason 也是正文内容
          acc.text += text;
          break;
        case 'action':
          acc.action += text;
          if (data.toolName) {
            acc.lastActionInfo = {
              toolName: data.toolName,
              args: data.args
            };
          }
          break;
        case 'text':
          acc.text += text;
          break;
      }

      // 实时更新显示（显示当前累积的内容）
      setMessages(prev => {
        const contentItems = buildContentItems();
        const tempMsg: Message = {
          id: assistantMsgIdRef.current,
          role: 'assistant',
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

    wsManager.registerCallback(sessionId, handleMessage);

    return () => {
      wsManager.unregisterCallback(sessionId);
    };
  }, [currentConversation.id]);

  const sendMessage = useCallback(async (messageText: string, options: SendOptions) => {
    let fullMessage = messageText;

    if (options.contexts.length > 0) {
      const contextStr = options.contexts.map(c => `[${c.name}]`).join(' ');
      fullMessage = `${contextStr}\n\n${messageText}`;
    }

    const userMessage: Message = {
      id: Date.now(),
      role: 'user',
      timestamp: new Date().toLocaleTimeString(),
      contents: [{ type: 'text', text: fullMessage }]
    };

    setMessages(prev => [...prev, userMessage]);

    await saveMessage({
      conversationId: currentConversation.id,
      role: 'user',
      timestamp: userMessage.timestamp,
      contents: JSON.stringify(userMessage.contents)
    });

    setIsLoading(true);

    // 重置累积器
    accumulatedContentRef.current = {
      think: '',
      text: '',
      action: '',
      lastActionInfo: null
    };

    assistantMsgIdRef.current = Date.now() + Math.floor(Math.random() * 1000);

    chatMessagesRef.current?.scrollToBottom();

    try {
      const sessionId = currentConversation.id.toString();
      const wsManager = WebSocketManager.getInstance();

      const request = {
        input: fullMessage,
        sessionId: sessionId,
        model: options.model,
        agent: options.agent
      };

      await wsManager.sendMessage(sessionId, request);

    } catch (error) {
      console.error('Failed to send message:', error);
      const errorMessage: Message = {
        id: Date.now() + 1,
        role: 'error',
        timestamp: new Date().toLocaleTimeString(),
        contents: [{ type: 'error', text: `请求失败: ${error instanceof Error ? error.message : '未知错误'}` }]
      };
      setMessages(prev => [...prev, errorMessage]);

      await saveMessage({
        conversationId: currentConversation.id,
        role: 'error',
        timestamp: errorMessage.timestamp,
        contents: JSON.stringify(errorMessage.contents)
      });
      setIsLoading(false);
    }
  }, [currentConversation]);

  async function loadSolonClawMessages() {
    const storedMessages = await getMessagesByConversation('SolonClaw');

    if (storedMessages.length > 0) {
      setMessages(storedMessages.map((msg, index) => ({
        ...msg,
        id: Date.now() + index,
        role: msg.role as Message['role'],
        timestamp: msg.timestamp || new Date().toLocaleTimeString(),
        contents: typeof msg.contents === 'string' ? JSON.parse(msg.contents) : msg.contents
      })));
    } else {
      setMessages([{
        id: 1,
        role: 'assistant',
        timestamp: new Date().toLocaleTimeString(),
        contents: [{
          type: 'text',
          text: '🦊 SolonClaw 已启动\n\n这是一个强大的代码分析和管理工具。我可以帮助你:\n\n• 分析项目结构和依赖关系\n• 检测代码质量问题\n• 生成代码文档\n• 执行代码重构建议\n• 搜索代码\n• 知道项目配置\n\n请告诉我你需要什么帮助?'
        }]
      }]);
    }
  }

  async function loadConversationMessages(convId: string | number) {
    const storedMessages = await getMessagesByConversation(convId);

    if (storedMessages.length > 0) {
      setMessages(storedMessages.map((msg, index) => ({
        ...msg,
        id: Date.now() + index,
        role: msg.role as Message['role'],
        timestamp: msg.timestamp || new Date().toLocaleTimeString(),
        contents: typeof msg.contents === 'string' ? JSON.parse(msg.contents) : msg.contents
      })));
    } else {
      setMessages([{
        id: 1,
        role: 'assistant',
        timestamp: new Date().toLocaleTimeString(),
        contents: [{
          type: 'text',
          text: '你好！我是 SolonCode 助手。有什么我可以帮助你的吗?'
        }]
      }]);
    }
  }

  useEffect(() => {
    loadTheme();
  }, []);

  useEffect(() => {
    if (currentConversation.id === 'SolonClaw' && currentConversation.isPermanent) {
      loadSolonClawMessages();
    } else {
      loadConversationMessages(currentConversation.id);
    }
  }, [currentConversation]);

  return (
    <main className="main-content">
      <ChatHeader
        title={currentConversation.title}
        status={currentConversation.status}
        theme={currentTheme}
        onToggleTheme={toggleTheme}
      />
      <ChatMessages
        ref={chatMessagesRef}
        messages={messages}
        isLoading={isLoading}
        theme={currentTheme}
      />
      <ChatInput onSend={sendMessage} />
    </main>
  );
}

import { memo, useState, useEffect, useRef, forwardRef, useImperativeHandle, useCallback } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkBreaks from 'remark-breaks';
import remarkGfm from 'remark-gfm';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark, oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { Icon } from './common/Icon';
import { ThinkBlock } from './ThinkBlock';
import { ActionBlock } from './ActionBlock';
import type { Message, Theme, ContentItem } from '../types';
import './ChatMessages.css';

interface ChatMessagesProps {
  messages: Message[];
  isLoading: boolean;
  theme?: Theme;
  onDeleteMessage?: (id: number) => void;
}

export interface ChatMessagesRef {
  scrollToBottom: () => void;
}

// Markdown 代码渲染组件（稳定引用）
const markdownComponents = (theme?: Theme) => ({
  code({ node, inline, className, children, ...props }: any) {
    const match = /language-(\w+)/.exec(className || '');
    return !inline && match ? (
      <SyntaxHighlighter
        style={theme === 'dark' ? oneDark : oneLight}
        language={match[1]}
        PreTag="div"
        {...props}
      >
        {String(children).replace(/\n$/, '')}
      </SyntaxHighlighter>
    ) : (
      <code className={className} {...props}>{children}</code>
    );
  }
});

const remarkPlugins = [remarkGfm, remarkBreaks];

// 内容项渲染组件 — memo 化，避免消息不变时重渲染
const ContentItemRenderer = memo(function ContentItemRenderer({ item, theme }: { item: ContentItem; theme?: Theme }) {
  if (item.type === 'THINK') {
    return <ThinkBlock content={item.text} theme={theme} />;
  }

  if (item.type === 'ACTION') {
    return (
      <ActionBlock text={item.text || ''} toolName={item.toolName} args={item.args} theme={theme} />
    );
  }

  if (item.type === 'REASON') {
    return (
      <div className="content-item reason-item">
        <div className="reason-header">
          <span className="reason-icon">🧠</span>
          <span className="reason-label">推理</span>
        </div>
        <div className="reason-content">
          <ReactMarkdown remarkPlugins={remarkPlugins} components={markdownComponents(theme)}>
            {item.text}
          </ReactMarkdown>
        </div>
      </div>
    );
  }

  if (item.type === 'ERROR') {
    return (
      <div className="content-item error-item">
        <span className="error-icon">❌</span>
        <span className="error-text">{item.text}</span>
      </div>
    );
  }

  return (
    <div className="content-item text-item">
      <ReactMarkdown remarkPlugins={remarkPlugins} components={markdownComponents(theme)}>
        {item.text}
      </ReactMarkdown>
    </div>
  );
});

// 消息元数据组件
const MessageMetadata = memo(function MessageMetadata({ metadata }: { metadata: Message['metadata'] }) {
  if (!metadata) return null;
  return (
    <div className="message-metadata">
      {metadata.modelName && (
        <span className="metadata-item">
          <span className="metadata-label">模型:</span>
          <span className="metadata-value">{metadata.modelName}</span>
        </span>
      )}
      {metadata.totalTokens !== undefined && (
        <span className="metadata-item">
          <span className="metadata-label">Token:</span>
          <span className="metadata-value">{metadata.totalTokens}</span>
        </span>
      )}
      {metadata.elapsedMs !== undefined && (
        <span className="metadata-item">
          <span className="metadata-label">耗时:</span>
          <span className="metadata-value">{metadata.elapsedMs}ms</span>
        </span>
      )}
    </div>
  );
});

// 单条消息组件 — memo 化
const MessageRow = memo(function MessageRow({ message, theme, onDelete }: { message: Message; theme?: Theme; onDelete?: (id: number) => void }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = useCallback(() => {
    const text = message.contents
      .map(item => item.text)
      .filter(Boolean)
      .join('\n');
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  }, [message.contents]);

  return (
    <div className={`message ${message.role.toLowerCase()}`}>
      <div className="message-bubble">
        <div className="message-text">
          {message.contents.map((item, index) => (
            <ContentItemRenderer key={index} item={item} theme={theme} />
          ))}
        </div>
      </div>
      <div className="message-footer">
        <div className="message-time">{message.timestamp}</div>
        <div className="message-actions">
          <button className="message-action-btn" onClick={handleCopy} title="复制">
            <Icon name={copied ? 'check' : 'copy'} size={12} />
          </button>
          <button className="message-action-btn" onClick={() => onDelete?.(message.id)} title="删除">
            <Icon name="delete" size={12} />
          </button>
          <MessageMetadata metadata={message.metadata} />
        </div>
      </div>
    </div>
  );
});

export const ChatMessages = forwardRef<ChatMessagesRef, ChatMessagesProps>(
  ({ messages, isLoading, theme, onDeleteMessage }, ref) => {
    const chatContainer = useRef<HTMLDivElement>(null);

    useImperativeHandle(ref, () => ({
      scrollToBottom
    }));

    function scrollToBottom() {
      if (chatContainer.current) {
        chatContainer.current.scrollTop = chatContainer.current.scrollHeight;
      }
    }

    useEffect(() => {
      scrollToBottom();
    }, [messages.length]);

    return (
      <div className="chat-messages" ref={chatContainer}>
        {messages.length === 0 && !isLoading && (
          <div className="empty-messages">
            <div className="empty-logo">SolonCode</div>
            <div className="empty-slogan">做你想做的事</div>
          </div>
        )}

        {messages.map((message) => (
          <MessageRow key={message.id} message={message} theme={theme} onDelete={onDeleteMessage} />
        ))}

        {isLoading && (
          <div className="message assistant loading">
            <div className="message-bubble">
              <div className="message-header">
                <Icon name="bot" size={12} />
                <span className="message-role">助手</span>
              </div>
              <div className="loading-indicator">
                <span></span>
                <span></span>
                <span></span>
              </div>
            </div>
          </div>
        )}
      </div>
    );
  }
);

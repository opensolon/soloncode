import { useEffect, useRef, forwardRef, useImperativeHandle } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkBreaks from 'remark-breaks';
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
}

export interface ChatMessagesRef {
  scrollToBottom: () => void;
}

// 内容项渲染组件
function ContentItemRenderer({ item, theme }: { item: ContentItem; theme?: Theme }) {
  // 思考内容
  if (item.type === 'think') {
    return <ThinkBlock content={item.text} theme={theme} />;
  }

  // 工具执行结果（折叠块）
  if (item.type === 'action') {
    return (
      <ActionBlock
        text={item.text || ''}
        toolName={item.toolName}
        args={item.args}
        theme={theme}
      />
    );
  }

  // 推理内容
  if (item.type === 'reason') {
    return (
      <div className="content-item reason-item">
        <div className="reason-header">
          <span className="reason-icon">🧠</span>
          <span className="reason-label">推理</span>
        </div>
        <div className="reason-content">
          <ReactMarkdown
            remarkPlugins={[remarkBreaks]}
            components={{
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
                  <code className={className} {...props}>
                    {children}
                  </code>
                );
              }
            }}
          >
            {item.text}
          </ReactMarkdown>
        </div>
      </div>
    );
  }

  // 错误内容
  if (item.type === 'error') {
    return (
      <div className="content-item error-item">
        <span className="error-icon">❌</span>
        <span className="error-text">{item.text}</span>
      </div>
    );
  }

  // 普通文本内容
  return (
    <div className="content-item text-item">
      <ReactMarkdown
        remarkPlugins={[remarkBreaks]}
        components={{
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
              <code className={className} {...props}>
                {children}
              </code>
            );
          }
        }}
      >
        {item.text}
      </ReactMarkdown>
    </div>
  );
}

export const ChatMessages = forwardRef<ChatMessagesRef, ChatMessagesProps>(
  ({ messages, isLoading, theme }, ref) => {
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
    }, [messages]);

    function getRoleLabel(role: string): string {
      const labels: Record<string, string> = {
        'user': '你',
        'assistant': '助手',
        'reason': '思考',
        'action': '执行',
        'error': '错误'
      };
      return labels[role] || role;
    }

    function getRoleIcon(role: string): 'user' | 'assistant' | 'bot' | 'warning' | 'error' {
      const icons: Record<string, 'user' | 'assistant' | 'bot' | 'warning' | 'error'> = {
        'user': 'user',
        'assistant': 'bot',
        'reason': 'assistant',
        'action': 'assistant',
        'error': 'error'
      };
      return icons[role] || 'bot';
    }

    return (
      <div className="chat-messages" ref={chatContainer}>
        {messages.map((message) => (
          <div
            key={message.id}
            className={`message ${message.role}`}
          >
            <div className="message-bubble">
              <div className="message-header">
                <Icon name={getRoleIcon(message.role)} size={12} />
                <span className="message-role">{getRoleLabel(message.role)}</span>
              </div>
              <div className="message-text">
                {message.contents.map((item, index) => (
                  <ContentItemRenderer key={index} item={item} theme={theme} />
                ))}
              </div>
              <div className="message-footer">
                <div className="message-time">{message.timestamp}</div>
                {message.metadata && (
                  <div className="message-metadata">
                    {message.metadata.modelName && (
                      <span className="metadata-item">
                        <span className="metadata-label">模型:</span>
                        <span className="metadata-value">{message.metadata.modelName}</span>
                      </span>
                    )}
                    {message.metadata.totalTokens !== undefined && (
                      <span className="metadata-item">
                        <span className="metadata-label">Token:</span>
                        <span className="metadata-value">{message.metadata.totalTokens}</span>
                      </span>
                    )}
                    {message.metadata.elapsedMs !== undefined && (
                      <span className="metadata-item">
                        <span className="metadata-label">耗时:</span>
                        <span className="metadata-value">{message.metadata.elapsedMs}ms</span>
                      </span>
                    )}
                  </div>
                )}
              </div>
            </div>
          </div>
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

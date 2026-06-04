import { memo, useState, useRef, forwardRef, useImperativeHandle, useCallback, useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkBreaks from 'remark-breaks';
import remarkGfm from 'remark-gfm';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark, oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { Virtuoso, type VirtuosoHandle } from 'react-virtuoso';
import { Icon } from './common/Icon';
import { ThinkBlock } from './ThinkBlock';
import { ActionBlock } from './ActionBlock';
import { ActionGroupBlock } from './ActionGroupBlock';
import type { Message, Theme, ContentItem } from '../types';
import './ChatMessages.css';

interface ChatMessagesProps {
  messages: Message[];
  isLoading: boolean;
  theme?: Theme;
  projectName?: string;
  onDeleteMessage?: (id: number) => void;
  onHitlAction?: (action: 'approve' | 'reject') => void;
  onFileSelect?: (path: string) => void;
}

export interface ChatMessagesRef {
  scrollToBottom: () => void;
}

function isExternalHref(href: string): boolean {
  return /^(https?:|mailto:|tel:|data:|blob:|#)/i.test(href);
}

function toFileLinkTarget(href?: string): string | null {
  if (!href || isExternalHref(href)) return null;

  let target = href.trim();
  try {
    target = decodeURIComponent(target);
  } catch {
    // 保持原始 href，继续按文件路径处理
  }

  if (target.startsWith('file:///')) {
    target = target.slice('file:///'.length);
  } else if (target.startsWith('file://')) {
    target = target.slice('file://'.length);
  }

  target = target.replace(/^\/([A-Za-z]:[\\/])/, '$1');
  target = target.replace(/[?#].*$/, '');

  if (!target || isExternalHref(target)) return null;

  const normalized = target.replace(/\\/g, '/');
  if (normalized === '.' || normalized === './' || normalized === '..' || normalized === '../' || normalized.endsWith('/')) return null;
  const basename = normalized.split('/').pop() || '';
  if (!basename.includes('.')) return null;

  return target;
}

// Markdown 代码渲染组件 — 按 onFileSelect 引用缓存，避免每次渲染重建
const markdownComponentsCache = new WeakMap<object, any>();
const noFileSelectKey = {};

function createMarkdownComponents(theme?: Theme, onFileSelect?: (path: string) => void) {
  return {
    a({ href, children, ...props }: any) {
      const fileTarget = toFileLinkTarget(href);
      if (fileTarget && onFileSelect) {
        return (
          <a
            {...props}
            href={href}
            className="chat-file-link"
            onClick={(e: React.MouseEvent) => {
              e.preventDefault();
              e.stopPropagation();
              onFileSelect(fileTarget);
            }}
          >
            {children}
          </a>
        );
      }
      return (
        <a href={href} target="_blank" rel="noreferrer" {...props}>
          {children}
        </a>
      );
    },
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
  };
}

function getMarkdownComponents(theme?: Theme, onFileSelect?: (path: string) => void) {
  const key = (onFileSelect as object) || noFileSelectKey;
  if (markdownComponentsCache.has(key)) return markdownComponentsCache.get(key);
  const components = createMarkdownComponents(theme, onFileSelect);
  markdownComponentsCache.set(key, components);
  return components;
}

const remarkPlugins = [remarkGfm, remarkBreaks];

// 通用可折叠块（无边框、灰色文字、默认折叠）
function CollapsibleBlock({ label, text, theme }: { label: string; text: string; theme?: Theme }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="collapsible-block">
      <div className="collapsible-header" onClick={() => setOpen(!open)}>
        <span className="collapsible-label">{label}</span>
        <span className={`collapsible-arrow ${open ? 'expanded' : ''}`}>▾</span>
      </div>
      {open && (
        <div className="collapsible-content">
          <ReactMarkdown remarkPlugins={remarkPlugins} components={getMarkdownComponents(theme)}>
            {text}
          </ReactMarkdown>
        </div>
      )}
    </div>
  );
}

// 内容项分组：将连续相同 toolName 的 ACTION 合并为一组
type GroupedItem =
  | { kind: 'single'; item: ContentItem }
  | { kind: 'group'; toolName: string; items: ContentItem[] };

function groupConsecutiveActions(items: ContentItem[]): GroupedItem[] {
  const result: GroupedItem[] = [];
  let i = 0;
  while (i < items.length) {
    const item = items[i];
    if (item.type === 'ACTION' && item.toolName) {
      const toolName = item.toolName;
      const group: ContentItem[] = [item];
      let j = i + 1;
      while (j < items.length && items[j].type === 'ACTION' && items[j].toolName === toolName) {
        group.push(items[j]);
        j++;
      }
      if (group.length > 1) {
        result.push({ kind: 'group', toolName, items: group });
      } else {
        result.push({ kind: 'single', item });
      }
      i = j;
    } else {
      result.push({ kind: 'single', item });
      i++;
    }
  }
  return result;
}

// 内容项渲染组件 — memo 化，避免消息不变时重渲染
const ContentItemRenderer = memo(function ContentItemRenderer({ item, theme, onHitlAction, onFileSelect }: { item: ContentItem; theme?: Theme; onHitlAction?: (action: 'approve' | 'reject') => void; onFileSelect?: (path: string) => void }) {
  if (item.type === 'THINK') {
    return <ThinkBlock content={item.text} theme={theme} />;
  }

  if (item.type === 'ACTION') {
    return (
      <ActionBlock text={item.text || ''} toolName={item.toolName} args={item.args} theme={theme} onFileClick={onFileSelect} />
    );
  }

  if (item.type === 'HITL') {
    return (
      <div className="content-item hitl-item">
        <div className="hitl-header">
          <span className="hitl-label">审批</span>
        </div>
        <div className="hitl-body">
          {item.toolName && <div className="hitl-tool">{item.toolName}</div>}
          {item.command && <div className="hitl-command"><code>{item.command}</code></div>}
        </div>
        <div className="hitl-actions">
          <button className="hitl-btn approve" onClick={() => onHitlAction?.('approve')}>允许</button>
          <button className="hitl-btn reject" onClick={() => onHitlAction?.('reject')}>拒绝</button>
        </div>
      </div>
    );
  }

  if (item.type === 'REASON') {
    return <CollapsibleBlock label="推理" text={item.text} theme={theme} />;
  }

  if (item.type === 'ERROR') {
    return (
      <div className="content-item error-item">
        <span className="error-text">{item.text}</span>
      </div>
    );
  }

  return (
    <div className="content-item text-item">
      <ReactMarkdown remarkPlugins={remarkPlugins} components={getMarkdownComponents(theme, onFileSelect)}>
        {item.text}
      </ReactMarkdown>
      {item.agentName && (
        <div className="sub-agent-label">
          <span className="sub-agent-icon">&#9654;</span>
          <span>{item.agentName}</span>
        </div>
      )}
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
const MessageRow = memo(function MessageRow({ message, theme, onDelete, onHitlAction, onFileSelect }: { message: Message; theme?: Theme; onDelete?: (id: number) => void; onHitlAction?: (action: 'approve' | 'reject') => void; onFileSelect?: (path: string) => void }) {
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

  const grouped = useMemo(() => groupConsecutiveActions(message.contents), [message.contents]);

  return (
    <div className={`message ${message.role.toLowerCase()}`}>
      <div className="message-bubble">
        <div className="message-text">
          {grouped.map((g, index) =>
            g.kind === 'group' ? (
              <ActionGroupBlock key={index} toolName={g.toolName} items={g.items} theme={theme} onFileClick={onFileSelect} />
            ) : (
              <ContentItemRenderer key={index} item={g.item} theme={theme} onHitlAction={onHitlAction} onFileSelect={onFileSelect} />
            )
          )}
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
  ({ messages, isLoading, theme, projectName, onDeleteMessage, onHitlAction, onFileSelect }, ref) => {
    const virtuosoRef = useRef<VirtuosoHandle>(null);

    useImperativeHandle(ref, () => ({
      scrollToBottom() {
        virtuosoRef.current?.scrollToIndex({ index: 'LAST', behavior: 'smooth' });
      }
    }));

    const itemContent = useCallback((index: number) => {
      const message = messages[index];
      return (
        <MessageRow message={message} theme={theme} onDelete={onDeleteMessage} onHitlAction={onHitlAction} onFileSelect={onFileSelect} />
      );
    }, [messages, theme, onDeleteMessage, onHitlAction, onFileSelect]);

    if (messages.length === 0 && !isLoading) {
      return (
        <div className="chat-messages">
          <div className="empty-messages">
            <div className="empty-logo">SolonCode</div>
            <div className="empty-slogan">{projectName ? `在${projectName}` : ''}做你想做的事</div>
          </div>
        </div>
      );
    }

    return (
      <div className="chat-messages">
        <Virtuoso
          ref={virtuosoRef}
          totalCount={messages.length}
          itemContent={itemContent}
          followOutput="smooth"
          initialTopMostItemIndex={messages.length - 1}
          computeItemKey={(index) => messages[index]?.id ?? index}
          style={{ height: '100%' }}
        />
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

import { lazy, memo, Suspense, useState, useRef, forwardRef, useImperativeHandle, useCallback, useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkBreaks from 'remark-breaks';
import remarkGfm from 'remark-gfm';
import { Virtuoso, type VirtuosoHandle } from 'react-virtuoso';
import { Icon } from './common/Icon';
import { ThinkBlock } from './ThinkBlock';
import { ActionBlock } from './ActionBlock';
import { ActionGroupBlock } from './ActionGroupBlock';
import type { Message, Theme, ContentItem } from '../types';
import { isTodoToolName } from '../utils/todoTools';
import './ChatMessages.css';

interface ChatMessagesProps {
  messages: Message[];
  isLoading: boolean;
  thinkingElapsedSeconds?: number;
  theme?: Theme;
  projectName?: string;
  onDeleteMessage?: (id: number) => void;
  onHitlAction?: (action: 'approve' | 'reject') => void;
  onFileSelect?: (path: string) => void;
}

export interface ChatMessagesRef {
  scrollToBottom: () => void;
}

interface LazyCodeBlockProps {
  theme?: Theme;
  language: string;
  code: string;
  codeProps: Record<string, unknown>;
}

const LazyCodeBlock = lazy(async () => {
  const [
    highlighterModule,
    styleModule,
    bash,
    css,
    diff,
    go,
    java,
    javascript,
    json,
    jsx,
    markdown,
    powershell,
    python,
    rust,
    sql,
    tsx,
    typescript,
    yaml,
  ] = await Promise.all([
    import('react-syntax-highlighter/dist/esm/prism-light'),
    import('react-syntax-highlighter/dist/esm/styles/prism'),
    import('react-syntax-highlighter/dist/esm/languages/prism/bash'),
    import('react-syntax-highlighter/dist/esm/languages/prism/css'),
    import('react-syntax-highlighter/dist/esm/languages/prism/diff'),
    import('react-syntax-highlighter/dist/esm/languages/prism/go'),
    import('react-syntax-highlighter/dist/esm/languages/prism/java'),
    import('react-syntax-highlighter/dist/esm/languages/prism/javascript'),
    import('react-syntax-highlighter/dist/esm/languages/prism/json'),
    import('react-syntax-highlighter/dist/esm/languages/prism/jsx'),
    import('react-syntax-highlighter/dist/esm/languages/prism/markdown'),
    import('react-syntax-highlighter/dist/esm/languages/prism/powershell'),
    import('react-syntax-highlighter/dist/esm/languages/prism/python'),
    import('react-syntax-highlighter/dist/esm/languages/prism/rust'),
    import('react-syntax-highlighter/dist/esm/languages/prism/sql'),
    import('react-syntax-highlighter/dist/esm/languages/prism/tsx'),
    import('react-syntax-highlighter/dist/esm/languages/prism/typescript'),
    import('react-syntax-highlighter/dist/esm/languages/prism/yaml'),
  ]);
  const SyntaxHighlighter = highlighterModule.default;
  const { oneDark, oneLight } = styleModule;

  SyntaxHighlighter.registerLanguage('bash', bash.default);
  SyntaxHighlighter.registerLanguage('shell', bash.default);
  SyntaxHighlighter.registerLanguage('sh', bash.default);
  SyntaxHighlighter.registerLanguage('css', css.default);
  SyntaxHighlighter.registerLanguage('diff', diff.default);
  SyntaxHighlighter.registerLanguage('go', go.default);
  SyntaxHighlighter.registerLanguage('java', java.default);
  SyntaxHighlighter.registerLanguage('javascript', javascript.default);
  SyntaxHighlighter.registerLanguage('js', javascript.default);
  SyntaxHighlighter.registerLanguage('json', json.default);
  SyntaxHighlighter.registerLanguage('jsx', jsx.default);
  SyntaxHighlighter.registerLanguage('markdown', markdown.default);
  SyntaxHighlighter.registerLanguage('md', markdown.default);
  SyntaxHighlighter.registerLanguage('powershell', powershell.default);
  SyntaxHighlighter.registerLanguage('python', python.default);
  SyntaxHighlighter.registerLanguage('py', python.default);
  SyntaxHighlighter.registerLanguage('rust', rust.default);
  SyntaxHighlighter.registerLanguage('rs', rust.default);
  SyntaxHighlighter.registerLanguage('sql', sql.default);
  SyntaxHighlighter.registerLanguage('tsx', tsx.default);
  SyntaxHighlighter.registerLanguage('typescript', typescript.default);
  SyntaxHighlighter.registerLanguage('ts', typescript.default);
  SyntaxHighlighter.registerLanguage('yaml', yaml.default);
  SyntaxHighlighter.registerLanguage('yml', yaml.default);

  return {
    default: function LazyCodeBlock({ theme, language, code, codeProps }: LazyCodeBlockProps) {
      return (
        <SyntaxHighlighter
          style={theme === 'dark' ? oneDark : oneLight}
          language={language}
          PreTag="div"
          {...codeProps}
        >
          {code}
        </SyntaxHighlighter>
      );
    },
  };
});

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
const FILE_EXTENSION_PATTERN = 'ts|tsx|js|jsx|mjs|cjs|vue|svelte|py|java|kt|kts|go|rs|c|cc|cpp|h|hpp|cs|php|rb|swift|scala|sh|bash|zsh|ps1|bat|cmd|sql|json|jsonc|yaml|yml|toml|xml|html|css|scss|sass|less|md|mdx|txt|csv|tsv|env|ini|properties|gradle|lock';
const FILE_REFERENCE_PATTERN = new RegExp(
  `(^|[\\s([{"'\`，。；：、])((?:[A-Za-z]:[\\\\/])?(?:(?:\\.{1,2}|~)?[\\\\/])?(?:(?:[\\w.@#$%+\\-=]+)[\\\\/])+[\\w.@#$%+\\-=]+\\.(?:${FILE_EXTENSION_PATTERN})|[\\w.@#$%+\\-=]+\\.(?:${FILE_EXTENSION_PATTERN}))(?:[:#](\\d+)(?:[:-](\\d+))?)?`,
  'gi'
);

function fileReferencePlugin() {
  const skipParentTypes = new Set(['link', 'linkReference', 'image', 'imageReference', 'definition']);

  function transformNode(node: any) {
    if (!node || !Array.isArray(node.children)) return;

    const nextChildren: any[] = [];
    for (const child of node.children) {
      if (child?.type === 'text' && typeof child.value === 'string' && !skipParentTypes.has(node.type)) {
        nextChildren.push(...splitFileReferences(child.value));
      } else {
        transformNode(child);
        nextChildren.push(child);
      }
    }
    node.children = nextChildren;
  }

  return (tree: any) => transformNode(tree);
}

function splitFileReferences(value: string) {
  FILE_REFERENCE_PATTERN.lastIndex = 0;
  const nodes: any[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = FILE_REFERENCE_PATTERN.exec(value))) {
    const leading = match[1] || '';
    const filePath = match[2];
    const lineSuffix = match[3] ? match[0].slice(leading.length + filePath.length) : '';
    const linkStart = match.index + leading.length;

    if (linkStart > lastIndex) {
      nodes.push({ type: 'text', value: value.slice(lastIndex, linkStart) });
    }

    const displayText = `${filePath}${lineSuffix}`;
    nodes.push({
      type: 'link',
      url: filePath,
      title: null,
      children: [{ type: 'text', value: displayText }],
    });

    lastIndex = linkStart + displayText.length;
  }

  if (lastIndex < value.length) {
    nodes.push({ type: 'text', value: value.slice(lastIndex) });
  }

  return nodes.length > 0 ? nodes : [{ type: 'text', value }];
}

type MarkdownThemeKey = Theme | 'default';
const markdownComponentsCache = new WeakMap<object, Partial<Record<MarkdownThemeKey, any>>>();
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
        <Suspense fallback={<pre className={className}><code>{String(children).replace(/\n$/, '')}</code></pre>}>
          <LazyCodeBlock
            theme={theme}
            language={match[1]}
            code={String(children).replace(/\n$/, '')}
            codeProps={props}
          />
        </Suspense>
      ) : (
        <code className={className} {...props}>{children}</code>
      );
    }
  };
}

function getMarkdownComponents(theme?: Theme, onFileSelect?: (path: string) => void) {
  const key = (onFileSelect as object) || noFileSelectKey;
  const themeKey: MarkdownThemeKey = theme || 'default';
  const cachedByTheme = markdownComponentsCache.get(key);
  if (cachedByTheme?.[themeKey]) return cachedByTheme[themeKey];

  const components = createMarkdownComponents(theme, onFileSelect);
  markdownComponentsCache.set(key, { ...cachedByTheme, [themeKey]: components });
  return components;
}

const remarkPlugins = [remarkGfm, remarkBreaks, fileReferencePlugin];

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
const ContentItemRenderer = memo(function ContentItemRenderer({ item, theme, onHitlAction, onFileSelect, autoExpanded }: { item: ContentItem; theme?: Theme; onHitlAction?: (action: 'approve' | 'reject') => void; onFileSelect?: (path: string) => void; autoExpanded?: boolean }) {
  if (item.type === 'THINK') {
    return <ThinkBlock content={item.text} theme={theme} />;
  }

  if (item.type === 'ACTION') {
    return (
      <ActionBlock text={item.text || ''} toolName={item.toolName} args={item.args} theme={theme} onFileClick={onFileSelect} autoExpanded={autoExpanded} />
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
const ThinkingRow = memo(function ThinkingRow({ elapsedSeconds }: { elapsedSeconds: number }) {
  return (
    <div className="message thinking-row">
      <div className="thinking-text">正在思考 {elapsedSeconds}s</div>
    </div>
  );
});

const MessageRow = memo(function MessageRow({ message, theme, onDelete, onHitlAction, onFileSelect, isStreaming }: { message: Message; theme?: Theme; onDelete?: (id: number) => void; onHitlAction?: (action: 'approve' | 'reject') => void; onFileSelect?: (path: string) => void; isStreaming?: boolean }) {
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
  const activeActionIndex = useMemo(() => {
    if (!isStreaming) return -1;
    const lastIndex = grouped.length - 1;
    const last = grouped[lastIndex];
    if (!last) return -1;
    return last.kind === 'group' || last.item.type === 'ACTION' ? lastIndex : -1;
  }, [grouped, isStreaming]);

  return (
    <div className={`message ${message.role.toLowerCase()}${isStreaming ? ' streaming' : ''}`}>
      <div className="message-bubble">
        <div className="message-text">
          {grouped.map((g, index) =>
            g.kind === 'group' ? (
              <ActionGroupBlock key={index} toolName={g.toolName} items={g.items} theme={theme} onFileClick={onFileSelect} autoExpanded={index === activeActionIndex} />
            ) : (
              <ContentItemRenderer key={index} item={g.item} theme={theme} onHitlAction={onHitlAction} onFileSelect={onFileSelect} autoExpanded={index === activeActionIndex} />
            )
          )}
        </div>
      </div>
      {!isStreaming && (
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
      )}
    </div>
  );
});

export const ChatMessages = forwardRef<ChatMessagesRef, ChatMessagesProps>(
  ({ messages, isLoading, thinkingElapsedSeconds = 0, theme, projectName, onDeleteMessage, onHitlAction, onFileSelect }, ref) => {
    const virtuosoRef = useRef<VirtuosoHandle>(null);
    const autoFollowRef = useRef(true);
    const visibleMessages = useMemo(() => {
      return messages.reduce<Message[]>((result, message) => {
        const contents = message.contents.filter(item => !isTodoToolName(item.toolName));
        if (contents.length === 0) return result;
        result.push(contents.length === message.contents.length ? message : { ...message, contents });
        return result;
      }, []);
    }, [messages]);

    useImperativeHandle(ref, () => ({
      scrollToBottom() {
        autoFollowRef.current = true;
        virtuosoRef.current?.scrollToIndex({ index: 'LAST', behavior: 'auto' });
      }
    }));

    const showThinkingRow = isLoading && visibleMessages[visibleMessages.length - 1]?.role !== 'ASSISTANT';

    const itemContent = useCallback((index: number) => {
      if (showThinkingRow && index === visibleMessages.length) {
        return <ThinkingRow elapsedSeconds={thinkingElapsedSeconds} />;
      }
      const message = visibleMessages[index];
      const isStreamingMessage = isLoading && index === visibleMessages.length - 1 && message?.role === 'ASSISTANT';
      return (
        <MessageRow message={message} theme={theme} onDelete={onDeleteMessage} onHitlAction={onHitlAction} onFileSelect={onFileSelect} isStreaming={isStreamingMessage} />
      );
    }, [visibleMessages, isLoading, showThinkingRow, thinkingElapsedSeconds, theme, onDeleteMessage, onHitlAction, onFileSelect]);

    if (visibleMessages.length === 0 && !isLoading) {
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
          className="chat-messages-list"
          ref={virtuosoRef}
          totalCount={visibleMessages.length + (showThinkingRow ? 1 : 0)}
          itemContent={itemContent}
          followOutput={(isAtBottom) => autoFollowRef.current && isAtBottom ? 'auto' : false}
          atBottomStateChange={(atBottom) => {
            autoFollowRef.current = atBottom;
          }}
          initialTopMostItemIndex={Math.max(0, visibleMessages.length - 1)}
          computeItemKey={(index) => showThinkingRow && index === visibleMessages.length ? 'thinking' : (visibleMessages[index]?.id ?? index)}
          style={{ height: '100%' }}
        />
        {false && (
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

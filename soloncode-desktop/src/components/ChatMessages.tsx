import { lazy, memo, Suspense, useState, useRef, forwardRef, useImperativeHandle, useCallback, useMemo, type FormEvent } from 'react';
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
import { isSafeImageDataUrl } from '../utils/messageContent';
import { resolveChatAutoFollow } from '../utils/chatAutoFollow';
import { permissionService } from '../services/permissionService';
import './ChatMessages.css';

interface ChatMessagesProps {
  messages: Message[];
  isLoading: boolean;
  thinkingElapsedSeconds?: number;
  theme?: Theme;
  projectName?: string;
  onDeleteMessage?: (id: number) => void;
  onRerunMessage?: (id: number) => void;
  onHitlAction?: (action: HitlAction, item: ContentItem, output?: string) => void;
  onFileSelect?: (path: string) => void;
}

type HitlAction = 'approve' | 'approve_always' | 'reject';

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
    Promise.all([
      import('react-syntax-highlighter/dist/esm/styles/prism/one-dark'),
      import('react-syntax-highlighter/dist/esm/styles/prism/one-light'),
    ]),
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
  const [oneDarkModule, oneLightModule] = styleModule;
  const oneDark = oneDarkModule.default;
  const oneLight = oneLightModule.default;

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

// 内容项分组：连续 ACTION 过多时收束成一组；否则将连续相同 toolName 的 ACTION 合并。
type GroupedItem =
  | { kind: 'single'; item: ContentItem }
  | { kind: 'group'; toolName: string; items: ContentItem[]; title?: string };

const MULTI_ACTION_GROUP_THRESHOLD = 3;

function groupSmallActionRun(actions: ContentItem[]): GroupedItem[] {
  const result: GroupedItem[] = [];
  let i = 0;
  while (i < actions.length) {
    const item = actions[i];
    if (item.toolName) {
      const toolName = item.toolName;
      const group: ContentItem[] = [item];
      let j = i + 1;
      while (j < actions.length && actions[j].toolName === toolName) {
        group.push(actions[j]);
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

function groupConsecutiveActions(items: ContentItem[]): GroupedItem[] {
  const result: GroupedItem[] = [];
  let i = 0;
  while (i < items.length) {
    const item = items[i];
    if (item.type === 'ACTION') {
      const group: ContentItem[] = [item];
      let j = i + 1;
      while (j < items.length && items[j].type === 'ACTION') {
        group.push(items[j]);
        j++;
      }
      if (group.length > MULTI_ACTION_GROUP_THRESHOLD) {
        result.push({ kind: 'group', toolName: 'tools', items: group, title: '运行了多个命令' });
      } else {
        result.push(...groupSmallActionRun(group));
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
const HitlApproval = memo(function HitlApproval({ item, onHitlAction }: { item: ContentItem; onHitlAction?: (action: HitlAction, item: ContentItem, output?: string) => void }) {
  const canRemember = Boolean(item.toolName && permissionService.canRemember(item.toolName));
  const [open, setOpen] = useState(true);
  const [selectedAction, setSelectedAction] = useState<HitlAction>('approve');
  const [output, setOutput] = useState('');
  const instanceIdRef = useRef(`hitl-${Math.random().toString(36).slice(2)}`);
  const outputId = `${instanceIdRef.current}-output`;
  const actionName = `${instanceIdRef.current}-action`;
  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    onHitlAction?.(selectedAction, item, output.trim() || undefined);
  };

  return (
    <div className="content-item hitl-item">
      <button className="hitl-trigger" type="button" onClick={() => setOpen(true)}>
        <span className="hitl-trigger-title">需要人工确认</span>
        {item.toolName && <span className="hitl-trigger-tool">{item.toolName}</span>}
      </button>
      {open && (
        <form className="hitl-popover" onSubmit={handleSubmit}>
          <div className="hitl-popover-header">
            <span className="hitl-label">人机交互</span>
            {item.toolName && <span className="hitl-tool">{item.toolName}</span>}
          </div>
          {item.text && <div className="hitl-comment">{item.text}</div>}
          {item.command && <div className="hitl-command"><code>{item.command}</code></div>}
          <div className="hitl-options" role="radiogroup" aria-label="审批选项">
            <label className="hitl-option">
              <input
                type="radio"
                name={actionName}
                value="approve"
                checked={selectedAction === 'approve'}
                onChange={() => setSelectedAction('approve')}
              />
              <span>允许本次</span>
            </label>
            <label className={`hitl-option${canRemember ? '' : ' disabled'}`}>
              <input
                type="radio"
                name={actionName}
                value="approve_always"
                disabled={!canRemember}
                checked={selectedAction === 'approve_always'}
                onChange={() => setSelectedAction('approve_always')}
              />
              <span>项目内总是允许</span>
            </label>
            <label className="hitl-option">
              <input
                type="radio"
                name={actionName}
                value="reject"
                checked={selectedAction === 'reject'}
                onChange={() => setSelectedAction('reject')}
              />
              <span>拒绝</span>
            </label>
          </div>
          <label className="hitl-output-label" htmlFor={outputId}>其他（输出）</label>
          <textarea
            id={outputId}
            className="hitl-output"
            value={output}
            onChange={event => setOutput(event.target.value)}
            placeholder="可填写人工输出、说明或拒绝原因"
            rows={3}
          />
          <div className="hitl-actions">
            <button className="hitl-btn approve" type="submit">提交</button>
            <button className="hitl-btn neutral" type="button" onClick={() => setOpen(false)}>取消</button>
          </div>
        </form>
      )}
      {!canRemember && open && (
        <div className="hitl-hint">当前工具不能记住为“项目内总是允许”。</div>
      )}
      {!open && (
        <button
          className="hitl-btn approve"
          type="button"
          onClick={() => setOpen(true)}
        >处理</button>
      )}
    </div>
  );
});

const ContentItemRenderer = memo(function ContentItemRenderer({ item, theme, onHitlAction, onFileSelect, activeThinking }: { item: ContentItem; theme?: Theme; onHitlAction?: (action: HitlAction, item: ContentItem, output?: string) => void; onFileSelect?: (path: string) => void; activeThinking?: boolean }) {
  if (item.type === 'FILE') {
    const sizeLabel = item.size == null
      ? ''
      : item.size < 1024
        ? `${item.size} B`
        : item.size < 1024 * 1024
          ? `${(item.size / 1024).toFixed(1)} KB`
          : `${(item.size / (1024 * 1024)).toFixed(1)} MB`;
    return (
      <div className="content-item message-file">
        <Icon name="file" size={14} />
        <span className="message-file-name">{item.name || item.text}</span>
        {sizeLabel && <span className="message-file-size">({sizeLabel})</span>}
      </div>
    );
  }

  if (item.type === 'IMAGE') {
    if (!isSafeImageDataUrl(item.text)) return null;
    return (
      <div className="content-item image-item">
        <img
          className="message-image"
          src={item.text}
          alt={item.name || '图片附件'}
          decoding="async"
        />
        {item.name && <div className="message-image-name">{item.name}</div>}
      </div>
    );
  }

  if (item.type === 'THINK') {
    return <ThinkBlock content={item.text} theme={theme} active={activeThinking} />;
  }

  if (item.type === 'ACTION') {
    return (
      <ActionBlock text={item.text || ''} toolName={item.toolName} args={item.args} theme={theme} onFileClick={onFileSelect} />
    );
  }

  if (item.type === 'HITL') {
    return <HitlApproval item={item} onHitlAction={onHitlAction} />;
  }

  if (item.type === 'REASON') {
    return <CollapsibleBlock label="推理" text={item.text} theme={theme} />;
  }

  if (item.type === 'ERROR') {
    return (
      <div className="content-item error-item">
        <Icon name="error" size={15} className="error-icon" />
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
    <div className="message thinking-row" role="status" aria-live="polite" aria-label={`正在思考，已处理 ${elapsedSeconds} 秒`}>
      <div className="thinking-text">正在思考 {elapsedSeconds}s</div>
    </div>
  );
});

const MessageRow = memo(function MessageRow({ message, theme, onDelete, onRerun, onHitlAction, onFileSelect, isStreaming }: { message: Message; theme?: Theme; onDelete?: (id: number) => void; onRerun?: (id: number) => void; onHitlAction?: (action: HitlAction, item: ContentItem, output?: string) => void; onFileSelect?: (path: string) => void; isStreaming?: boolean }) {
  const [copied, setCopied] = useState(false);
  const handleCopy = useCallback(() => {
    const text = message.contents
      .filter(item => item.type !== 'IMAGE')
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
    <div className={`message ${message.role.toLowerCase()}${isStreaming ? ' streaming' : ''}`}>
      <div className="message-bubble">
        <div className="message-text">
          {grouped.map((g, index) =>
            g.kind === 'group' ? (
              <ActionGroupBlock key={index} toolName={g.toolName} items={g.items} title={g.title} theme={theme} onFileClick={onFileSelect} />
            ) : (
              <ContentItemRenderer
                key={index}
                item={g.item}
                theme={theme}
                onHitlAction={onHitlAction}
                onFileSelect={onFileSelect}
                activeThinking={Boolean(isStreaming && index === grouped.length - 1 && g.item.type === 'THINK')}
              />
            )
          )}
        </div>
      </div>
      {!isStreaming && (
        <div className="message-footer">
          <div className="message-time">{message.timestamp}</div>
          <MessageMetadata metadata={message.metadata} />
          <div className="message-actions">
            <button className="message-action-btn" onClick={handleCopy} title="复制">
              <Icon name={copied ? 'check' : 'copy'} size={12} />
            </button>
            {message.role === 'USER' && <>
              <button className="message-action-btn" onClick={() => onRerun?.(message.id)} title="从此处重做">
                <Icon name="refresh" size={12} />
              </button>
              <button className="message-action-btn" onClick={() => onDelete?.(message.id)} title="回退并删除此处及之后消息">
                <Icon name="delete" size={12} />
              </button>
            </>}
          </div>
        </div>
      )}
    </div>
  );
});

export const ChatMessages = forwardRef<ChatMessagesRef, ChatMessagesProps>(
  ({ messages, isLoading, thinkingElapsedSeconds = 0, theme, projectName, onDeleteMessage, onRerunMessage, onHitlAction, onFileSelect }, ref) => {
    const virtuosoRef = useRef<VirtuosoHandle>(null);
    const autoFollowRef = useRef(true);
    const visibleMessages = useMemo(() => {
      return messages.reduce<Message[]>((result, message) => {
        const contents = message.contents
          .filter(item => item.type !== 'HITL' && !isTodoToolName(item.toolName))
          .reduce<ContentItem[]>((items, item) => {
            const previous = items[items.length - 1];
            if (item.type === 'THINK' && previous?.type === 'THINK') {
              items[items.length - 1] = {
                ...previous,
                text: `${previous.text}\n\n${item.text}`.trim(),
              };
            } else {
              items.push(item);
            }
            return items;
          }, []);
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

    // 运行期间仅在用户停留底部时跟随，避免滚动条被强制拉到最低。
    const showThinkingRow = isLoading;
    const handleListHeightChanged = useCallback(() => {
      const followState = resolveChatAutoFollow({
        atBottom: autoFollowRef.current,
      });
      autoFollowRef.current = followState.enabled;
      if (isLoading && autoFollowRef.current) {
        // 流式响应会原地增高最后一项，totalCount 不变时 followOutput 不会自行触发。
        virtuosoRef.current?.autoscrollToBottom();
      }
    }, [isLoading]);

    const itemContent = useCallback((index: number) => {
      if (showThinkingRow && index === visibleMessages.length) {
        return <ThinkingRow elapsedSeconds={thinkingElapsedSeconds} />;
      }
      const message = visibleMessages[index];
      const isStreamingMessage = isLoading && index === visibleMessages.length - 1 && message?.role === 'ASSISTANT';
      return (
        <MessageRow message={message} theme={theme} onDelete={onDeleteMessage} onRerun={onRerunMessage} onHitlAction={onHitlAction} onFileSelect={onFileSelect} isStreaming={isStreamingMessage} />
      );
    }, [visibleMessages, isLoading, showThinkingRow, thinkingElapsedSeconds, theme, onDeleteMessage, onRerunMessage, onHitlAction, onFileSelect]);

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
          followOutput={(isAtBottom) => resolveChatAutoFollow({
            atBottom: autoFollowRef.current && isAtBottom,
          }).behavior}
          atBottomStateChange={(atBottom) => {
            autoFollowRef.current = resolveChatAutoFollow({ atBottom }).enabled;
          }}
          totalListHeightChanged={handleListHeightChanged}
          initialTopMostItemIndex={Math.max(0, visibleMessages.length + (showThinkingRow ? 1 : 0) - 1)}
          computeItemKey={(index) => showThinkingRow && index === visibleMessages.length ? 'thinking' : (visibleMessages[index]?.id ?? index)}
          style={{ height: '100%' }}
        />
      </div>
    );
  }
);

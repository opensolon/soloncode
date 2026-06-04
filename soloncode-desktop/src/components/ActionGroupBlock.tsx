import { useState, useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkBreaks from 'remark-breaks';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark, oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import type { Theme, ContentItem } from '../types';
import './ActionGroupBlock.css';

interface ActionGroupBlockProps {
  toolName: string;
  items: ContentItem[];
  theme?: Theme;
  onFileClick?: (filePath: string) => void;
}

function capitalize(s: string): string {
  if (!s) return s;
  return s.charAt(0).toUpperCase() + s.slice(1);
}

function isDirectoryPath(p: string): boolean {
  const s = p.trim().replace(/\\/g, '/');
  if (!s || s === '.' || s === './' || s === '..' || s === '../' || s.endsWith('/')) return true;
  const basename = s.split('/').pop() || '';
  if (!basename.includes('.')) return true;
  return false;
}

function toRelativePath(p: string): string {
  return p.trim().replace(/\\/g, '/').replace(/^\.\//, '');
}

function extractFileArg(args?: Record<string, unknown>): string | null {
  if (!args) return null;
  const raw = (args.file_path || args.path || args.filename || args.filePath || null) as string | null;
  if (!raw || isDirectoryPath(raw)) return null;
  return raw;
}

function extractLineInfo(args?: Record<string, unknown>): string | null {
  if (!args) return null;
  const start = args.start_line || args.startLine || args.offset;
  const end = args.end_line || args.endLine || args.limit;
  if (start && end) return `L${start}-${end}`;
  if (start) return `L${start}`;
  return null;
}

function extractDiffStats(toolName?: string, args?: Record<string, unknown>): { added: number; removed: number } | null {
  if (!args || !toolName) return null;
  const t = toolName.toLowerCase();
  if (t === 'edit' || t === 'replace') {
    const oldStr = (args.old_string || args.oldStr || '') as string;
    const newStr = (args.new_string || args.newStr || '') as string;
    if (!oldStr && !newStr) return null;
    const oldLines = oldStr ? oldStr.split('\n').length : 0;
    const newLines = newStr ? newStr.split('\n').length : 0;
    return { added: newLines, removed: oldLines };
  }
  if (t === 'write' || t === 'create_file') {
    const content = (args.content || args.text || '') as string;
    if (!content) return null;
    return { added: content.split('\n').length, removed: 0 };
  }
  return null;
}

export function ActionGroupBlock({ toolName, items, theme, onFileClick }: ActionGroupBlockProps) {
  const [groupExpanded, setGroupExpanded] = useState(false);
  const [expandedIndex, setExpandedIndex] = useState<number | null>(null);

  const name = capitalize(toolName || 'Tool');

  // 从第一个 item 提取文件路径（同组工具通常操作同一文件）
  const firstFilePath = extractFileArg(items[0]?.args);
  const firstLineInfo = extractLineInfo(items[0]?.args);

  const totalDiffStats = useMemo(() => {
    let added = 0, removed = 0;
    for (const item of items) {
      const s = extractDiffStats(toolName, item.args);
      if (s) { added += s.added; removed += s.removed; }
    }
    return (added || removed) ? { added, removed } : null;
  }, [toolName, items]);

  const markdownComponents = {
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

  return (
    <div className="action-group">
      <div className="action-group-header" onClick={() => setGroupExpanded(!groupExpanded)}>
        <span className="action-group-tool">{name}</span>
        {firstFilePath && (
          <span
            className="action-group-file"
            onClick={e => { e.stopPropagation(); onFileClick?.(firstFilePath); }}
          >
            {toRelativePath(firstFilePath)}
          </span>
        )}
        {firstLineInfo && <span className="action-group-lines">{firstLineInfo}</span>}
        {totalDiffStats && (
          <span className="action-block-diff">
            {totalDiffStats.added > 0 && <span className="diff-added">+{totalDiffStats.added}</span>}
            {totalDiffStats.removed > 0 && <span className="diff-removed">-{totalDiffStats.removed}</span>}
          </span>
        )}
        <span className="action-group-badge">{items.length}</span>
        <span className={`action-group-arrow ${groupExpanded ? 'expanded' : ''}`}>▾</span>
      </div>
      {groupExpanded && (
        <div className="action-group-list">
          {items.map((item, idx) => {
            const isOpen = expandedIndex === idx;
            const itemFile = extractFileArg(item.args);
            const itemLine = extractLineInfo(item.args);
            return (
              <div key={idx} className="action-group-item">
                <div className="action-group-item-header" onClick={() => setExpandedIndex(isOpen ? null : idx)}>
                  <span className="action-group-item-icon">{isOpen ? '▾' : '▸'}</span>
                  {itemFile ? (
                    <span
                      className="action-group-item-file"
                      onClick={e => { e.stopPropagation(); onFileClick?.(itemFile!); }}
                    >
                      {toRelativePath(itemFile)}
                    </span>
                  ) : (
                    <span className="action-group-item-idx">#{idx + 1}</span>
                  )}
                  {itemLine && <span className="action-group-item-lines">{itemLine}</span>}
                </div>
                {isOpen && (
                  <div className="action-group-item-content">
                    <ReactMarkdown remarkPlugins={[remarkBreaks]} components={markdownComponents}>
                      {item.text || '执行完成'}
                    </ReactMarkdown>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

import { useEffect, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkBreaks from 'remark-breaks';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark, oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import type { Theme } from '../types';
import './ActionBlock.css';

interface ActionBlockProps {
  text: string;
  toolName?: string;
  args?: Record<string, unknown>;
  theme?: Theme;
  onFileClick?: (filePath: string) => void;
  autoExpanded?: boolean;
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

function extractCommand(args?: Record<string, unknown>): string | null {
  if (!args) return null;
  return (args.command || args.cmd || null) as string | null;
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

interface ReadFileResult {
  filePath: string;
  lines?: string;
  size?: string;
  content: string;
  isMarkdown: boolean;
}

function parseReadFileResult(text: string, toolName?: string): ReadFileResult | null {
  if (!text || toolName?.toLowerCase() !== 'read') return null;

  const lines = text.replace(/\r\n/g, '\n').split('\n');
  const first = lines[0]?.trim();
  const match = first?.match(/^\[File:\s+(.+?)(?:\s+\((.+)\))?\]$/);
  if (!match) return null;

  const filePath = match[1].trim();
  const attrs = match[2] || '';
  const lineMatch = attrs.match(/Lines:\s*([^,]+)/i);
  const sizeMatch = attrs.match(/Size:\s*([^,]+)/i);
  let content = lines.slice(1).join('\n').replace(/^\n+/, '');

  const isMarkdown = /\.(md|markdown|mdx)$/i.test(filePath);
  if (isMarkdown) {
    content = content
      .split('\n')
      .map(line => line.replace(/^\s*\d+\s*\|\s?/, ''))
      .join('\n');
  }

  return {
    filePath,
    lines: lineMatch?.[1]?.trim(),
    size: sizeMatch?.[1]?.trim(),
    content,
    isMarkdown,
  };
}

interface DirEntry {
  type: 'dir' | 'file';
  name: string;
}

function parseDirListing(text: string): DirEntry[] | null {
  const lines = text.trim().split('\n').filter(l => l.trim());
  if (lines.length === 0) return null;
  const entries: DirEntry[] = [];
  for (const line of lines) {
    const m = line.match(/^\[DIR]\s+(.+)$/);
    if (m) { entries.push({ type: 'dir', name: m[1].replace(/\/$/, '') }); continue; }
    const f = line.match(/^\[FILE]\s+(.+)$/);
    if (f) { entries.push({ type: 'file', name: f[1] }); continue; }
    return null;
  }
  return entries.length > 0 ? entries : null;
}

function DirectoryListing({ entries, onFileClick }: { entries: DirEntry[]; onFileClick?: (path: string) => void }) {
  const dirs = entries.filter(e => e.type === 'dir');
  const files = entries.filter(e => e.type === 'file');
  return (
    <div className="dir-listing">
      {dirs.map(e => (
        <div key={e.name} className="dir-listing-item dir-listing-dir">
          <span className="dir-listing-icon">📁</span>
          <span className="dir-listing-name">{e.name}</span>
        </div>
      ))}
      {files.map(e => (
        <div
          key={e.name}
          className={`dir-listing-item dir-listing-file${onFileClick ? ' clickable' : ''}`}
          onClick={() => onFileClick?.(e.name)}
        >
          <span className="dir-listing-icon">📄</span>
          <span className="dir-listing-name">{e.name}</span>
        </div>
      ))}
    </div>
  );
}

export function ActionBlock({ text, toolName, args, theme, onFileClick, autoExpanded = false }: ActionBlockProps) {
  const [isExpanded, setIsExpanded] = useState(false);

  useEffect(() => {
    setIsExpanded(autoExpanded);
  }, [autoExpanded]);

  const name = capitalize(toolName || 'Tool');
  const filePath = extractFileArg(args);
  const lineInfo = extractLineInfo(args);
  const cmd = extractCommand(args);
  const dirEntries = parseDirListing(text || '');
  const diffStats = extractDiffStats(toolName, args);
  const readFileResult = parseReadFileResult(text || '', toolName);
  const displayText = readFileResult?.content || text || '鎵ц瀹屾垚';
  const displayFilePath = readFileResult?.filePath || filePath;
  const displayLineInfo = readFileResult?.lines || lineInfo;

  return (
    <div className="action-block">
      <div className="action-block-header" onClick={() => setIsExpanded(!isExpanded)}>
        <span className="action-block-tool">{name}</span>
        {displayFilePath && (
          <span
            className="action-block-file"
            onClick={e => { e.stopPropagation(); onFileClick?.(displayFilePath); }}
          >
            {toRelativePath(displayFilePath)}
          </span>
        )}
        {displayLineInfo && <span className="action-block-lines">{displayLineInfo}</span>}
        {readFileResult?.size && <span className="action-block-size">{readFileResult.size}</span>}
        {diffStats && (
          <span className="action-block-diff">
            {diffStats.added > 0 && <span className="diff-added">+{diffStats.added}</span>}
            {diffStats.removed > 0 && <span className="diff-removed">-{diffStats.removed}</span>}
          </span>
        )}
        {!displayFilePath && cmd && <span className="action-block-cmd">{(cmd as string).length > 50 ? (cmd as string).slice(0, 50) + '...' : cmd}</span>}
        <span className={`action-block-arrow ${isExpanded ? 'expanded' : ''}`}>▾</span>
      </div>
      {isExpanded && (
        <div className="action-block-content">
          {dirEntries ? (
            <DirectoryListing entries={dirEntries} onFileClick={onFileClick} />
          ) : (
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
              {displayText}
            </ReactMarkdown>
          )}
        </div>
      )}
    </div>
  );
}

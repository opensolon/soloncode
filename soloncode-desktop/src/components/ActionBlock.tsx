import { useState } from 'react';
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

export function ActionBlock({ text, toolName, args, theme, onFileClick }: ActionBlockProps) {
  const [isExpanded, setIsExpanded] = useState(false);

  const name = capitalize(toolName || 'Tool');
  const filePath = extractFileArg(args);
  const lineInfo = extractLineInfo(args);
  const cmd = extractCommand(args);
  const dirEntries = parseDirListing(text || '');
  const diffStats = extractDiffStats(toolName, args);

  return (
    <div className="action-block">
      <div className="action-block-header" onClick={() => setIsExpanded(!isExpanded)}>
        <span className="action-block-tool">{name}</span>
        {filePath && (
          <span
            className="action-block-file"
            onClick={e => { e.stopPropagation(); onFileClick?.(filePath); }}
          >
            {toRelativePath(filePath)}
          </span>
        )}
        {lineInfo && <span className="action-block-lines">{lineInfo}</span>}
        {diffStats && (
          <span className="action-block-diff">
            {diffStats.added > 0 && <span className="diff-added">+{diffStats.added}</span>}
            {diffStats.removed > 0 && <span className="diff-removed">-{diffStats.removed}</span>}
          </span>
        )}
        {!filePath && cmd && <span className="action-block-cmd">{(cmd as string).length > 50 ? (cmd as string).slice(0, 50) + '...' : cmd}</span>}
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
              {text || '执行完成'}
            </ReactMarkdown>
          )}
        </div>
      )}
    </div>
  );
}

import { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import Editor, { DiffEditor, type OnMount } from '@monaco-editor/react';
import type { editor } from 'monaco-editor';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkBreaks from 'remark-breaks';
import { Icon, getFileIconName } from '../common/Icon';
import { ContextMenu } from '../common/ContextMenu';
import type { DiffLine } from '../../services/gitService';
import './EditorPanel.css';

interface OpenFile {
  path: string;
  name: string;
  content: string;
  modified: boolean;
  language: string;
  isImage?: boolean;
  imageBase64?: string;
  imageMimeType?: string;
}

interface EditorPanelProps {
  files: OpenFile[];
  activeFilePath: string | null;
  onFileSelect: (path: string) => void;
  onFileClose: (path: string) => void;
  onContentChange: (path: string, content: string) => void;
  onFileSave: (path: string, contentOverride?: string) => void;
  onFileDelete?: (path: string) => void;
  onFileRename?: (path: string) => void;
  theme?: 'dark' | 'light';
  editorTheme?: string;
  fontSize?: number;
  tabSize?: number;
  autoSave?: boolean;
  formatOnSave?: boolean;
  diffLines?: DiffLine[];
  diffFiles?: Record<string, string>;
}

// 文件扩展名到 Monaco 语言 ID 的映射
function getMonacoLanguage(path: string): string {
  const ext = path.split('.').pop()?.toLowerCase() || '';
  const map: Record<string, string> = {
    ts: 'typescript',
    tsx: 'typescript',
    js: 'javascript',
    jsx: 'javascript',
    json: 'json',
    css: 'css',
    scss: 'scss',
    less: 'less',
    html: 'html',
    md: 'markdown',
    markdown: 'markdown',
    mdx: 'markdown',
    py: 'python',
    java: 'java',
    rs: 'rust',
    go: 'go',
    vue: 'html',
    xml: 'xml',
    yaml: 'yaml',
    yml: 'yaml',
    toml: 'ini',
    sh: 'shell',
    bash: 'shell',
    sql: 'sql',
    properties: 'ini',
    gradle: 'groovy',
    kt: 'kotlin',
    kts: 'kotlin',
  };
  return map[ext] || 'plaintext';
}

function isMarkdownFile(path: string): boolean {
  return /\.(md|markdown|mdx)$/i.test(path);
}

// 从 DOM 读取当前实际主题
function getActiveTheme(): 'dark' | 'light' {
  if (typeof document === 'undefined') return 'dark';
  return document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
}

function resolveMonacoTheme(appTheme: 'dark' | 'light', editorTheme?: string) {
  if (editorTheme && editorTheme !== 'auto') return editorTheme;
  return appTheme === 'dark' ? 'vs-dark' : 'light';
}

export function EditorPanel({
  files,
  activeFilePath,
  onFileSelect,
  onFileClose,
  onContentChange,
  onFileSave,
  theme: _themeProp,
  editorTheme: _editorTheme,
  fontSize = 14,
  tabSize = 2,
  autoSave = false,
  formatOnSave = false,
  diffLines = [],
  diffFiles = {},
}: EditorPanelProps) {
  // 直接从 DOM 读取主题，确保与 ChatView 同步
  const [activeTheme, setActiveTheme] = useState<'dark' | 'light'>(_themeProp || getActiveTheme());

  // 监听 DOM data-theme 变化（MutationObserver）
  useEffect(() => {
    const el = document.documentElement;

    setActiveTheme(_themeProp || getActiveTheme());
    const observer = new MutationObserver(() => {
      setActiveTheme(_themeProp || getActiveTheme());
    });

    observer.observe(el, {
      attributes: true,
      attributeFilter: ['data-theme'],
    });

    return () => observer.disconnect();
  }, [_themeProp]);

  const editorRef = useRef<editor.IStandaloneCodeEditor | null>(null);
  const decorationsRef = useRef<editor.IEditorDecorationsCollection | null>(null);
  const autoSaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [tabContextMenu, setTabContextMenu] = useState<{ x: number; y: number; path: string } | null>(null);
  const [markdownEditFiles, setMarkdownEditFiles] = useState<Set<string>>(() => new Set());

  // 用 ref 保持最新值，避免 addCommand 回调闭包过期
  const activeFilePathRef = useRef(activeFilePath);
  activeFilePathRef.current = activeFilePath;
  const onFileSaveRef = useRef(onFileSave);
  onFileSaveRef.current = onFileSave;
  const onContentChangeRef = useRef(onContentChange);
  onContentChangeRef.current = onContentChange;
  const formatOnSaveRef = useRef(formatOnSave);
  formatOnSaveRef.current = formatOnSave;

  const handleEditorMount: OnMount = (editor) => {
    editorRef.current = editor;
    // 注册 Ctrl+S 保存
    editor.addCommand(2097 /* KeyMod.CtrlCmd | KeyCode.KeyS */, async () => {
      const path = activeFilePathRef.current;
      if (path) {
        if (formatOnSaveRef.current) {
          await editor.getAction('editor.action.formatDocument')?.run();
        }
        const value = editor.getValue();
        onContentChangeRef.current(path, value);
        onFileSaveRef.current(path, value);
      }
    });
  };

  const closeFileAndDiff = useCallback((path: string) => {
    onFileClose(path);
  }, [onFileClose]);

  const handleTabMenuAction = useCallback((itemId: string) => {
    if (!tabContextMenu) return;
    const targetPath = tabContextMenu.path;
    const targetIndex = files.findIndex(file => file.path === targetPath);
    if (itemId === 'close-current') {
      closeFileAndDiff(targetPath);
      return;
    }
    if (itemId === 'close-others') {
      files.filter(file => file.path !== targetPath).forEach(file => closeFileAndDiff(file.path));
      return;
    }
    if (itemId === 'close-left') {
      files.slice(0, Math.max(0, targetIndex)).forEach(file => closeFileAndDiff(file.path));
    }
  }, [closeFileAndDiff, files, tabContextMenu]);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'w') {
        if (!activeFilePath) return;
        event.preventDefault();
        closeFileAndDiff(activeFilePath);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [activeFilePath, closeFileAndDiff]);

  // 根据 diffLines 更新 Monaco decorations
  useEffect(() => {
    const editorInstance = editorRef.current;
    if (!editorInstance) return;

    if (decorationsRef.current) {
      decorationsRef.current.clear();
    }

    if (!diffLines || diffLines.length === 0) {
      return;
    }

    const themeSuffix = activeTheme === 'dark' ? 'dark' : 'light';
    const decorations: editor.IModelDeltaDecoration[] = diffLines.map((d) => {
      const line = d.line;

      if (d.type === 'added') {
        return {
          range: new (window as any).monaco.Range(line, 1, line, 1),
          options: {
            isWholeLine: true,
            className: `diff-added-line-${themeSuffix}`,
            overviewRuler: {
              color: '#60a5fa',
              position: 2,
            },
          },
        };
      } else if (d.type === 'deleted') {
        return {
          range: new (window as any).monaco.Range(line, 1, line, 1),
          options: {
            isWholeLine: true,
            className: `diff-deleted-line-${themeSuffix}`,
            overviewRuler: {
              color: '#ef4444',
              position: 2,
            },
          },
        };
      }
      // modified
      return {
        range: new (window as any).monaco.Range(line, 1, line, 1),
        options: {
          isWholeLine: true,
          className: `diff-modified-line-${themeSuffix}`,
          overviewRuler: {
            color: '#4ade80',
            position: 2,
          },
        },
      };
    });

    decorationsRef.current = editorInstance.createDecorationsCollection(decorations);
  }, [diffLines, activeTheme]);

  const handleEditorChange = useCallback((value: string | undefined) => {
    if (activeFilePath && value !== undefined) {
      onContentChange(activeFilePath, value);
    }
  }, [activeFilePath, onContentChange]);

  const editorOptions = useMemo(() => ({
    fontSize,
    fontFamily: "'Fira Code', 'Cascadia Code', 'Consolas', monospace",
    fontLigatures: true,
    tabSize,
    minimap: { enabled: true },
    wordWrap: 'on' as const,
    lineNumbers: 'on' as const,
    scrollBeyondLastLine: false,
    automaticLayout: true,
    padding: { top: 8 },
    renderWhitespace: 'selection' as const,
    bracketPairColorization: { enabled: true },
    smoothScrolling: true,
    cursorBlinking: 'smooth' as const,
    cursorSmoothCaretAnimation: 'on' as const,
    scrollbar: {
      verticalScrollbarSize: 8,
      horizontalScrollbarSize: 8,
      useShadows: false,
      verticalSliderSize: 8,
      horizontalSliderSize: 8,
    },
  }), [fontSize, tabSize]);

  const monacoTheme = resolveMonacoTheme(activeTheme, _editorTheme);

  const activeFile = files.find(f => f.path === activeFilePath);
  const isActiveMarkdown = !!activeFile && !activeFile.isImage && isMarkdownFile(activeFile.path);
  const isMarkdownPreview = !!activeFile && isActiveMarkdown && !markdownEditFiles.has(activeFile.path) && !(activeFile.path in diffFiles);

  useEffect(() => {
    const openPaths = new Set(files.map(file => file.path));
    setMarkdownEditFiles(prev => {
      const next = new Set([...prev].filter(path => openPaths.has(path)));
      return next.size === prev.size ? prev : next;
    });
  }, [files]);

  useEffect(() => {
    if (isMarkdownPreview) {
      editorRef.current = null;
    }
  }, [isMarkdownPreview, activeFile?.path]);

  const toggleMarkdownPreview = useCallback(() => {
    if (!activeFile || !isActiveMarkdown || activeFile.path in diffFiles) return;
    setMarkdownEditFiles(prev => {
      const next = new Set(prev);
      if (next.has(activeFile.path)) {
        next.delete(activeFile.path);
      } else {
        next.add(activeFile.path);
      }
      return next;
    });
  }, [activeFile, diffFiles, isActiveMarkdown]);

  useEffect(() => {
    if (!autoSave || !activeFile || activeFile.isImage || !activeFile.modified) return;
    if (autoSaveTimerRef.current) clearTimeout(autoSaveTimerRef.current);
    autoSaveTimerRef.current = setTimeout(async () => {
      let value = activeFile.content;
      const editorInstance = editorRef.current;
      if (editorInstance && activeFile.path === activeFilePathRef.current) {
        if (formatOnSaveRef.current) {
          await editorInstance.getAction('editor.action.formatDocument')?.run();
        }
        value = editorInstance.getValue();
        onContentChangeRef.current(activeFile.path, value);
      }
      onFileSaveRef.current(activeFile.path, value);
    }, 800);
    return () => {
      if (autoSaveTimerRef.current) clearTimeout(autoSaveTimerRef.current);
    };
  }, [activeFile?.path, activeFile?.content, activeFile?.modified, activeFile?.isImage, autoSave]);

  const lineCount = useMemo(() => {
    if (!activeFile || activeFile.isImage) return 0;
    return activeFile.content.split('\n').length;
  }, [activeFile?.content, activeFile?.isImage]);

  if (files.length === 0) {
    return (
      <div className="editor-panel empty">
        <div className="empty-state">
          <Icon name="code" size={48} className="empty-icon" />
          <span className="empty-text">打开文件开始编辑</span>
          <span className="empty-hint">使用左侧资源管理器浏览文件</span>
        </div>
      </div>
    );
  }

  return (
    <div className="editor-panel">
      <div className="editor-tabs">
        {files.map(file => (
          <div
            key={file.path}
            className={`editor-tab${file.path === activeFilePath ? ' active' : ''}`}
            onClick={() => onFileSelect(file.path)}
            onContextMenu={(event) => {
              event.preventDefault();
              onFileSelect(file.path);
              setTabContextMenu({ x: event.clientX, y: event.clientY, path: file.path });
            }}
          >
            <Icon name={getFileIconName(file.name)} size={14} className="tab-icon" />
            <span className="tab-name">{file.name}</span>
            {file.path in diffFiles && <span className="tab-diff-badge">DIFF</span>}
            {file.modified && <span className="tab-modified">●</span>}
            <button
              className="tab-close"
              onClick={(e) => {
                e.stopPropagation();
                onFileClose(file.path);
              }}
            >
              <Icon name="close" size={14} />
            </button>
          </div>
        ))}
        {isActiveMarkdown && !(activeFile.path in diffFiles) && (
          <div className="editor-tabs-actions">
            <button
              className={`tab-action-btn markdown-preview-toggle${isMarkdownPreview ? ' active' : ''}`}
              onClick={toggleMarkdownPreview}
              title={isMarkdownPreview ? '切换到源码编辑' : '切换到 Markdown 预览'}
              aria-label={isMarkdownPreview ? '切换到源码编辑' : '切换到 Markdown 预览'}
            >
              <Icon name={isMarkdownPreview ? 'edit' : 'file-md'} size={15} />
            </button>
          </div>
        )}
      </div>

      {tabContextMenu && (
        <ContextMenu
          x={tabContextMenu.x}
          y={tabContextMenu.y}
          items={[
            { id: 'close-current', label: '关闭当前', shortcut: 'Ctrl+W' },
            { id: 'close-others', label: '关闭其他', disabled: files.length <= 1 },
            { id: 'close-left', label: '关闭左侧', disabled: files.findIndex(file => file.path === tabContextMenu.path) <= 0 },
          ]}
          onItemClick={handleTabMenuAction}
          onClose={() => setTabContextMenu(null)}
        />
      )}

      {activeFile && (
        <div className="editor-content">
          {activeFile.isImage && activeFile.imageBase64 ? (
            <div className="image-preview">
              <img
                src={`data:${activeFile.imageMimeType};base64,${activeFile.imageBase64}`}
                alt={activeFile.name}
                className="image-preview-img"
              />
              <div className="image-preview-info">
                <span>{activeFile.name}</span>
                <span>{activeFile.imageMimeType}</span>
              </div>
            </div>
          ) : activeFile.path in diffFiles ? (
            <DiffEditor
              height="100%"
              language={getMonacoLanguage(activeFile.path)}
              original={diffFiles[activeFile.path]}
              modified={activeFile.content}
              theme={monacoTheme}
              options={{
                readOnly: true,
                renderSideBySide: true,
                fontSize: editorOptions.fontSize,
                minimap: { enabled: false },
                scrollbar: editorOptions.scrollbar,
              }}
            />
          ) : isMarkdownPreview ? (
            <div className="markdown-preview">
              <ReactMarkdown
                remarkPlugins={[remarkGfm, remarkBreaks]}
                components={{
                  a: ({ href, children }) => (
                    <a href={href} target={href?.startsWith('#') ? undefined : '_blank'} rel="noreferrer">
                      {children}
                    </a>
                  ),
                }}
              >
                {activeFile.content}
              </ReactMarkdown>
            </div>
          ) : (
          <Editor
            height="100%"
            language={getMonacoLanguage(activeFile.path)}
            value={activeFile.content}
            onChange={handleEditorChange}
            onMount={handleEditorMount}
            theme={monacoTheme}
            options={editorOptions}
            path={activeFile.path}
          />
          )}
        </div>
      )}

      {activeFile && !activeFile.isImage && (
        <div className="editor-status">
          <span className="status-item">{activeFile.language}</span>
          <span className="status-item">UTF-8</span>
          <span className="status-item">行 {lineCount}</span>
        </div>
      )}
    </div>
  );
}

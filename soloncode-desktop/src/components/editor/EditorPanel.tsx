import { useState, useEffect } from 'react';
import Editor, { type OnMount } from '@monaco-editor/react';
import { Icon, getFileIconName } from '../common/Icon';
import './EditorPanel.css';

interface OpenFile {
  path: string;
  name: string;
  content: string;
  modified: boolean;
  language: string;
}

interface EditorPanelProps {
  files: OpenFile[];
  activeFilePath: string | null;
  onFileSelect: (path: string) => void;
  onFileClose: (path: string) => void;
  onContentChange: (path: string, content: string) => void;
  onFileSave: (path: string) => void;
  onFileDelete?: (path: string) => void;
  onFileRename?: (path: string) => void;
  theme?: 'dark' | 'light';
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

// 从 DOM 读取当前实际主题
function getActiveTheme(): 'dark' | 'light' {
  if (typeof document === 'undefined') return 'dark';
  return document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
}

export function EditorPanel({
  files,
  activeFilePath,
  onFileSelect,
  onFileClose,
  onContentChange,
  onFileSave,
  theme: _themeProp,
}: EditorPanelProps) {
  // 直接从 DOM 读取主题，确保与 ChatView 同步
  const [activeTheme, setActiveTheme] = useState<'dark' | 'light'>(getActiveTheme());

  // 监听 DOM data-theme 变化（MutationObserver）
  useEffect(() => {
    const el = document.documentElement;

    const observer = new MutationObserver(() => {
      setActiveTheme(getActiveTheme());
    });

    observer.observe(el, {
      attributes: true,
      attributeFilter: ['data-theme'],
    });

    return () => observer.disconnect();
  }, []);

  const handleEditorMount: OnMount = (editor) => {
    // 注册 Ctrl+S 保存
    editor.addCommand(2097 /* KeyMod.CtrlCmd | KeyCode.KeyS */, () => {
      if (activeFilePath) {
        onFileSave(activeFilePath);
      }
    });
  };

  const handleEditorChange = (value: string | undefined) => {
    if (activeFilePath && value !== undefined) {
      onContentChange(activeFilePath, value);
    }
  };

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

  const activeFile = files.find(f => f.path === activeFilePath);

  return (
    <div className="editor-panel">
      <div className="editor-tabs">
        {files.map(file => (
          <div
            key={file.path}
            className={`editor-tab${file.path === activeFilePath ? ' active' : ''}`}
            onClick={() => onFileSelect(file.path)}
          >
            <Icon name={getFileIconName(file.name)} size={14} className="tab-icon" />
            <span className="tab-name">{file.name}</span>
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
        <div className="editor-tabs-actions">
          <button
            className="tab-action-btn"
            title="保存当前文件 (Ctrl+S)"
            disabled={!activeFilePath}
            onClick={() => activeFilePath && onFileSave(activeFilePath)}
          >
            <Icon name="save" size={14} />
          </button>
          <button
            className="tab-action-btn"
            title="关闭所有文件"
            onClick={() => files.forEach(f => onFileClose(f.path))}
          >
            <Icon name="close" size={14} />
          </button>
        </div>
      </div>

      {activeFile && (
        <div className="editor-content">
          <Editor
            height="100%"
            language={getMonacoLanguage(activeFile.path)}
            value={activeFile.content}
            onChange={handleEditorChange}
            onMount={handleEditorMount}
            theme={activeTheme === 'dark' ? 'vs-dark' : 'light'}
            options={{
              fontSize: 14,
              fontFamily: "'Fira Code', 'Cascadia Code', 'Consolas', monospace",
              fontLigatures: true,
              tabSize: 2,
              minimap: { enabled: true },
              wordWrap: 'on',
              lineNumbers: 'on',
              scrollBeyondLastLine: false,
              automaticLayout: true,
              padding: { top: 8 },
              renderWhitespace: 'selection',
              bracketPairColorization: { enabled: true },
              smoothScrolling: true,
              cursorBlinking: 'smooth',
              cursorSmoothCaretAnimation: 'on',
              scrollbar: {
                verticalScrollbarSize: 8,
                horizontalScrollbarSize: 8,
                useShadows: false,
                verticalSliderSize: 8,
                horizontalSliderSize: 8,
              },
            }}
            path={activeFile.path}
          />
        </div>
      )}

      {activeFile && (
        <div className="editor-status">
          <span className="status-item">{activeFile.language}</span>
          <span className="status-item">UTF-8</span>
          <span className="status-item">行 {activeFile.content.split('\n').length}</span>
        </div>
      )}
    </div>
  );
}

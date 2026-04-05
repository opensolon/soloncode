import { useEffect, useRef, useCallback } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import '@xterm/xterm/css/xterm.css';
import './TerminalPanel.css';

interface TerminalPanelProps {
  // 新增：独立面板模式（用于多终端）
  terminalId?: string;
  title?: string;
  visible?: boolean;
  // 原有：兼容模式
  cwd?: string;
}

// 检测 Tauri 环境
function isTauriEnv(): boolean {
  return typeof window !== 'undefined' &&
    ('__TAURI__' in window || '__TAURI_INTERNALS__' in window);
}

// 从 DOM 读取当前主题
function getActiveTheme(): 'dark' | 'light' {
  if (typeof document === 'undefined') return 'dark';
  return document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
}

const TERM_THEMES = {
  dark: {
    background: '#1e1e1e',
    foreground: '#d4d4d4',
    cursor: '#d4d4d4',
    selectionBackground: '#264f78',
    black: '#000000',
    red: '#cd3131',
    green: '#0dbc79',
    yellow: '#e5e510',
    blue: '#2472c8',
    magenta: '#bc3fbc',
    cyan: '#11a8cd',
    white: '#e5e5e5',
    brightBlack: '#666666',
    brightRed: '#f14c4c',
    brightGreen: '#23d18b',
    brightYellow: '#f5f543',
    brightBlue: '#3b8eea',
    brightMagenta: '#d670d6',
    brightCyan: '#29b8db',
    brightWhite: '#ffffff',
  },
  light: {
    background: '#ffffff',
    foreground: '#383a42',
    cursor: '#383a42',
    selectionBackground: '#add6ff',
    black: '#000000',
    red: '#e45649',
    green: '#50a14f',
    yellow: '#c18401',
    blue: '#4078f2',
    magenta: '#a626a4',
    cyan: '#0184bc',
    white: '#a0a0a0',
    brightBlack: '#4f4f4f',
    brightRed: '#e06c75',
    brightGreen: '#98c379',
    brightYellow: '#e5c07b',
    brightBlue: '#61afef',
    brightMagenta: '#c678dd',
    brightCyan: '#56b6c2',
    brightWhite: '#ffffff',
  },
};

export function TerminalPanel({ terminalId: terminalIdProp, title: titleProp, visible: visibleProp, cwd }: TerminalPanelProps) {
  // 兼容新旧模式：新模式用 terminalId/title/visible，旧模式用 visible
  const _terminalId = terminalIdProp || 'default';
  const title = titleProp || 'TERMINAL';
  const visible = visibleProp !== undefined ? visibleProp : true;
  
  const termRef = useRef<HTMLDivElement>(null);
  const xtermRef = useRef<Terminal | null>(null);
  const fitAddonRef = useRef<FitAddon | null>(null);
  const unlistenRef = useRef<UnlistenFn | null>(null);
  const startedRef = useRef(false);

  const initTerminal = useCallback(async () => {
    if (!termRef.current || xtermRef.current || startedRef.current) return;
    startedRef.current = true;

    const theme = getActiveTheme();

    const term = new Terminal({
      fontSize: 14,
      fontFamily: "'Cascadia Code', 'Consolas', 'Fira Code', monospace",
      cursorBlink: true,
      cursorStyle: 'bar',
      theme: TERM_THEMES[theme],
      allowProposedApi: true,
    });

    const fitAddon = new FitAddon();
    term.loadAddon(fitAddon);
    term.open(termRef.current);
    fitAddon.fit();

    xtermRef.current = term;
    fitAddonRef.current = fitAddon;

    // 监听主题变化
    const observer = new MutationObserver(() => {
      const newTheme = getActiveTheme();
      term.options.theme = TERM_THEMES[newTheme];
    });
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme'],
    });

    // 监听用户输入 → 写入 PTY
    term.onData(async (data) => {
      if (!isTauriEnv()) return;
      try {
        await invoke('terminal_write', { data });
      } catch (e) {
        console.error('[Terminal] write error:', e);
      }
    });

    // 监听 PTY 输出 → 写入 xterm
    if (isTauriEnv()) {
      const unlisten = await listen<string>('terminal-output', (event) => {
        if (event.payload === '' && !event.payload) {
          term.writeln('\r\n[终端已关闭]');
          return;
        }
        term.write(event.payload);
      });
      unlistenRef.current = unlisten;

      const rows = term.rows;
      const cols = term.cols;
      try {
        await invoke('terminal_start', { rows, cols, cwd: cwd || null });
      } catch (e) {
        term.writeln(`\x1b[31m启动终端失败: ${e}\x1b[0m`);
      }
    } else {
      term.writeln('[非 Tauri 环境，终端不可用]');
    }

    // 窗口大小变化时自适应
    const onResize = () => {
      if (fitAddonRef.current && termRef.current) {
        try {
          fitAddonRef.current.fit();
          if (xtermRef.current && isTauriEnv()) {
            invoke('terminal_resize', {
              rows: xtermRef.current.rows,
              cols: xtermRef.current.cols,
            }).catch(() => {});
          }
        } catch {}
      }
    };
    window.addEventListener('resize', onResize);

    let resizeObserver: ResizeObserver | null = null;
    if (termRef.current) {
      resizeObserver = new ResizeObserver(() => onResize());
      resizeObserver.observe(termRef.current);
    }

    return () => {
      window.removeEventListener('resize', onResize);
      resizeObserver?.disconnect();
      observer.disconnect();
    };
  }, [cwd]);

  useEffect(() => {
    if (visible) {
      initTerminal();
      setTimeout(() => {
        if (fitAddonRef.current && termRef.current) {
          try { fitAddonRef.current.fit(); } catch {}
        }
      }, 50);
    }
  }, [visible, initTerminal]);

  useEffect(() => {
    return () => {
      unlistenRef.current?.();
      xtermRef.current?.dispose();
      xtermRef.current = null;
      fitAddonRef.current = null;
      startedRef.current = false;
      if (isTauriEnv()) {
        invoke('terminal_kill').catch(() => {});
      }
    };
  }, []);

  return (
    <div className={`terminal-panel${visible ? '' : ' hidden'}`}>
      <div className="terminal-body" ref={termRef} />
    </div>
  );
}

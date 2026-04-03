/**
 * 文件服务 - 封装 Tauri 文件操作 API
 * @author bai
 */

import { invoke } from '@tauri-apps/api/core';
import { open, save } from '@tauri-apps/plugin-dialog';
import { watch, type WatchEvent } from '@tauri-apps/plugin-fs';

// 文件信息接口
export interface FileInfo {
  name: string;
  path: string;
  isDir: boolean;
  children?: FileInfo[];
}

// 后端返回的原始数据结构（蛇形命名）
interface RawFileInfo {
  name: string;
  path: string;
  is_dir: boolean;
  children?: RawFileInfo[];
}

// 转换后端数据为前端格式
function transformFileInfo(raw: RawFileInfo): FileInfo {
  return {
    name: raw.name,
    path: raw.path,
    isDir: raw.is_dir,
    children: raw.children ? raw.children.map(transformFileInfo) : undefined,
  };
}

// 工作区信息接口
export interface WorkspaceInfo {
  path: string;
  name: string;
}

// 打开的文件接口
export interface OpenFile {
  path: string;
  name: string;
  content: string;
  modified: boolean;
  language: string;
}

// 检测是否在 Tauri 环境中运行
function isTauriEnv(): boolean {
  // Tauri 2.0 的检测方式
  const result = typeof window !== 'undefined' &&
    ('__TAURI__' in window || '__TAURI_INTERNALS__' in window);
  console.log('[fileService] Tauri环境检测:', result, 'window.__TAURI__:', !!(window as any).__TAURI__, 'window.__TAURI_INTERNALS__:', !!(window as any).__TAURI_INTERNALS__);
  return result;
}

// 模拟数据 - 用于开发环境测试
const mockWorkspaceFiles: FileInfo[] = [
  {
    name: 'src',
    path: '/mock-project/src',
    isDir: true,
    children: [
      {
        name: 'components',
        path: '/mock-project/src/components',
        isDir: true,
        children: [
          { name: 'App.tsx', path: '/mock-project/src/components/App.tsx', isDir: false },
          { name: 'Header.tsx', path: '/mock-project/src/components/Header.tsx', isDir: false },
        ],
      },
      { name: 'main.tsx', path: '/mock-project/src/main.tsx', isDir: false },
      { name: 'index.css', path: '/mock-project/src/index.css', isDir: false },
    ],
  },
  { name: 'package.json', path: '/mock-project/package.json', isDir: false },
  { name: 'README.md', path: '/mock-project/README.md', isDir: false },
];

const mockFileContents: Record<string, string> = {
  '/mock-project/src/main.tsx': `import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './components/App';
import './index.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);`,
  '/mock-project/src/components/App.tsx': `import React from 'react';

export default function App() {
  return (
    <div className="app">
      <h1>Hello, SolonCode!</h1>
    </div>
  );
}`,
  '/mock-project/src/components/Header.tsx': `import React from 'react';

export default function Header() {
  return (
    <header>
      <h1>SolonCode Desktop</h1>
    </header>
  );
}`,
  '/mock-project/package.json': `{
  "name": "mock-project",
  "version": "1.0.0",
  "description": "A mock project for testing"
}`,
  '/mock-project/README.md': `# Mock Project

这是一个模拟项目，用于在浏览器开发模式下测试文件功能。
`,
  '/mock-project/src/index.css': `* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: sans-serif;
}`,
};

/**
 * 文件服务
 */
export const fileService = {
  /**
   * 检查是否在 Tauri 环境中
   */
  isTauri(): boolean {
    return isTauriEnv();
  },

  /**
   * 打开文件对话框
   */
  async openFileDialog(options?: {
    multiple?: boolean;
    filters?: Array<{ name: string; extensions: string[] }>;
  }): Promise<string | string[] | null> {
    if (!isTauriEnv()) {
      console.warn('[fileService] 非 Tauri 环境');
      return null;
    }

    try {
      console.log('[fileService] 打开文件对话框...');
      const result = await open({
        multiple: options?.multiple,
        filters: options?.filters,
        directory: false,
      });
      console.log('[fileService] 选择结果:', result);
      return result;
    } catch (err) {
      console.error('[fileService] 打开文件对话框失败:', err);
      return null;
    }
  },

  /**
   * 打开文件夹对话框
   */
  async openFolderDialog(): Promise<string | null> {
    // 直接尝试使用 Tauri API
    try {
      console.log('[fileService] 尝试打开文件夹选择器...');
      const result = await open({
        directory: true,
        multiple: false,
        title: '选择工作区文件夹',
      });
      console.log('[fileService] 选择结果:', result);
      return result as string | null;
    } catch (err) {
      console.error('[fileService] Tauri对话框失败，尝试浏览器方式:', err);
    }

    // 备用方案：使用 HTML input 元素选择文件夹
    return new Promise((resolve) => {
      const input = document.createElement('input');
      input.type = 'file';
      (input as any).webkitdirectory = true;
      input.style.display = 'none';

      input.onchange = (e) => {
        const files = (e.target as HTMLInputElement).files;
        if (files && files.length > 0) {
          // 获取第一个文件的路径，提取文件夹路径
          const firstFile = files[0];
          const path = (firstFile as any).webkitRelativePath;
          const folderName = path.split('/')[0];
          console.log('[fileService] 浏览器选择文件夹:', folderName);
          resolve(folderName);
        } else {
          resolve(null);
        }
        document.body.removeChild(input);
      };

      const handleCancel = () => {
        resolve(null);
        if (document.body.contains(input)) {
          document.body.removeChild(input);
        }
      };

      // 超时处理
      setTimeout(handleCancel, 60000);

      document.body.appendChild(input);
      input.click();
    });
  },

  /**
   * 保存文件对话框
   */
  async saveFileDialog(options?: {
    defaultPath?: string;
    filters?: Array<{ name: string; extensions: string[] }>;
  }): Promise<string | null> {
    if (!isTauriEnv()) {
      console.warn('[fileService] 非 Tauri 环境');
      return null;
    }

    try {
      const result = await save({
        defaultPath: options?.defaultPath,
        filters: options?.filters,
      });
      return result;
    } catch (err) {
      console.error('[fileService] 保存文件对话框失败:', err);
      return null;
    }
  },

  /**
   * 读取文件内容
   */
  async readFile(path: string): Promise<string> {
    try {
      console.log('[fileService] 读取文件:', path);
      const result = await invoke<string>('read_file', { path });
      console.log('[fileService] 文件内容长度:', result.length);
      return result;
    } catch (err) {
      console.error('[fileService] 读取文件失败:', err);
      throw err;
    }
  },

  /**
   * 写入文件内容
   */
  async writeFile(path: string, content: string): Promise<void> {
    if (isTauriEnv()) {
      try {
        await invoke('write_file', { path, content });
      } catch (err) {
        console.error('[fileService] 写入文件失败:', err);
        throw err;
      }
    } else {
      mockFileContents[path] = content;
      console.log('[fileService] 模拟写入文件:', path);
    }
  },

  /**
   * 列出目录内容
   */
  async listDirectory(path: string): Promise<FileInfo[]> {
    if (isTauriEnv()) {
      try {
        const result = await invoke<RawFileInfo[]>('list_directory', { path });
        return result.map(transformFileInfo);
      } catch (err) {
        console.error('[fileService] 列出目录失败:', err);
        return [];
      }
    }
    if (path === '/mock-project' || path === '/mock-project/src') {
      return mockWorkspaceFiles;
    }
    return [];
  },

  /**
   * 递归列出目录树
   */
  async listDirectoryTree(path: string, maxDepth: number = 5): Promise<FileInfo[]> {
    try {
      console.log('[fileService] 调用 list_directory_tree, path:', path, 'max_depth:', maxDepth);
      const result = await invoke<RawFileInfo[]>('list_directory_tree', {
        path,
        maxDepth: maxDepth
      });
      console.log('[fileService] 返回结果数量:', result.length);
      return result.map(transformFileInfo);
    } catch (err) {
      console.error('[fileService] 列出目录树失败:', err);
      return [];
    }
  },

  /**
   * 创建新文件
   */
  async createFile(path: string): Promise<void> {
    if (isTauriEnv()) {
      await invoke('create_file', { path });
    } else {
      mockFileContents[path] = '';
    }
  },

  /**
   * 创建新目录
   */
  async createDirectory(path: string): Promise<void> {
    if (isTauriEnv()) {
      await invoke('create_directory', { path });
    }
  },

  /**
   * 删除文件
   */
  async deleteFile(path: string): Promise<void> {
    if (isTauriEnv()) {
      await invoke('delete_file', { path });
    } else {
      delete mockFileContents[path];
    }
  },

  /**
   * 删除目录
   */
  async deleteDirectory(path: string): Promise<void> {
    if (isTauriEnv()) {
      await invoke('delete_directory', { path });
    }
  },

  /**
   * 复制文件或目录
   */
  async copyItem(sourcePath: string, destPath: string): Promise<void> {
    if (isTauriEnv()) {
      await invoke('copy_item', { sourcePath, destPath });
    }
  },

  /**
   * 移动文件或目录
   */
  async moveItem(sourcePath: string, destPath: string): Promise<void> {
    if (isTauriEnv()) {
      await invoke('move_item', { sourcePath, destPath });
    }
  },

  /**
   * 启动后端 CLI 进程（通过 soloncode 命令）
   */
  async startBackend(workspacePath: string, port: number): Promise<number> {
    if (!isTauriEnv()) {
      console.log('[fileService] mock startBackend');
      return 0;
    }
    return await invoke<number>('start_backend', { workspacePath, port });
  },

  /**
   * 停止后端 CLI 进程
   */
  async stopBackend(): Promise<void> {
    if (!isTauriEnv()) return;
    await invoke('stop_backend');
  },

  /**
   * 检查后端进程状态
   */
  async backendStatus(): Promise<boolean> {
    if (!isTauriEnv()) return false;
    return await invoke<boolean>('backend_status');
  },

  /**
   * 重命名文件或目录
   */
  async renameItem(oldPath: string, newPath: string): Promise<void> {
    if (isTauriEnv()) {
      await invoke('rename_item', { oldPath, newPath });
    } else {
      if (mockFileContents[oldPath]) {
        mockFileContents[newPath] = mockFileContents[oldPath];
        delete mockFileContents[oldPath];
      }
    }
  },

  /**
   * 检查路径是否存在
   */
  async pathExists(path: string): Promise<boolean> {
    if (isTauriEnv()) {
      const result = await invoke<boolean>('path_exists', { path });
      return result;
    }
    return path in mockFileContents || path.startsWith('/mock-project');
  },

  /**
   * 获取工作区信息
   */
  async getWorkspaceInfo(path: string): Promise<WorkspaceInfo> {
    try {
      const result = await invoke<WorkspaceInfo>('get_workspace_info', { path });
      return result;
    } catch (err) {
      console.error('[fileService] 获取工作区信息失败:', err);
      const name = path.split(/[/\\]/).pop() || '工作区';
      return { path, name };
    }
  },

  /**
   * 初始化工作区配置
   * 在项目目录下创建 .soloncode/settings.json
   */
  async initWorkspaceConfig(workspacePath: string): Promise<string | null> {
    try {
      console.log('[fileService] 初始化工作区配置:', workspacePath);
      const settingsPath = await invoke<string>('init_workspace_config', { workspacePath });
      console.log('[fileService] 配置文件路径:', settingsPath);
      return settingsPath;
    } catch (err) {
      console.error('[fileService] 初始化工作区配置失败:', err);
      return null;
    }
  },

  /**
   * 获取文件语言类型
   */
  getLanguageFromPath(path: string): string {
    const ext = path.split('.').pop()?.toLowerCase() || '';
    const langMap: Record<string, string> = {
      'ts': 'TypeScript',
      'tsx': 'TypeScript React',
      'js': 'JavaScript',
      'jsx': 'JavaScript React',
      'json': 'JSON',
      'css': 'CSS',
      'scss': 'SCSS',
      'less': 'Less',
      'html': 'HTML',
      'md': 'Markdown',
      'py': 'Python',
      'java': 'Java',
      'rs': 'Rust',
      'go': 'Go',
      'vue': 'Vue',
      'xml': 'XML',
      'yaml': 'YAML',
      'yml': 'YAML',
      'toml': 'TOML',
      'sh': 'Shell',
      'bash': 'Bash',
    };
    return langMap[ext] || 'Plain Text';
  },

  /**
   * 打开文件并返回 OpenFile 对象
   */
  async openFile(path: string): Promise<OpenFile> {
    const content = await this.readFile(path);
    const name = path.split(/[/\\]/).pop() || '';
    const language = this.getLanguageFromPath(path);

    return {
      path,
      name,
      content,
      modified: false,
      language,
    };
  },

  // ==================== 文件监听 ====================

  /** 活跃的监听器取消函数 */
  _watcherUnsubs: [] as Array<() => void>,

  /** 轮询定时器（非 Tauri 环境备用） */
  _pollingTimers: [] as Array<ReturnType<typeof setInterval>>,

  /**
   * 监听指定路径的文件变化
   * @param path 监听路径（目录）
   * @param callback 变化回调
   * @param options 监听选项
   * @returns 取消监听函数
   */
  async watchPath(
    path: string,
    callback: (events: WatchEvent) => void,
    options?: { recursive?: boolean }
  ): Promise<() => void> {
    const recursive = options?.recursive ?? true;

    if (isTauriEnv()) {
      try {
        console.log('[fileService] 启动文件监听:', path);
        const unwatch = await watch(
          path,
          (event: WatchEvent) => {
            callback(event);
          },
          { recursive }
        );
        const unsub = () => {
          try { unwatch(); } catch (_) { /* ignore */ }
        };
        this._watcherUnsubs.push(unsub);
        return unsub;
      } catch (err) {
        console.error('[fileService] Tauri 文件监听失败，回退到轮询:', err);
        return this._startPolling(path, callback);
      }
    }

    // 非 Tauri 环境：轮询模式
    return this._startPolling(path, callback);
  },

  /**
   * 轮询模式监听（非 Tauri 环境备用方案）
   */
  _startPolling(
    path: string,
    callback: (events: WatchEvent) => void,
    interval: number = 3000
  ): () => void {
    let lastSnapshot: Map<string, number> = new Map();

    const poll = async () => {
      try {
        const files = await this.listDirectoryTree(path, 3);
        const currentSnapshot = new Map<string, number>();

        const flatten = (items: FileInfo[]) => {
          for (const item of items) {
            currentSnapshot.set(item.path, item.isDir ? 1 : 0);
            if (item.children) flatten(item.children);
          }
        };
        flatten(files);

        // 检测变化
        const created: string[] = [];
        const removed: string[] = [];

        for (const [p] of currentSnapshot) {
          if (!lastSnapshot.has(p)) created.push(p);
        }
        for (const [p] of lastSnapshot) {
          if (!currentSnapshot.has(p)) removed.push(p);
        }

        if (created.length > 0 || removed.length > 0) {
          callback({
            type: created.length > 0
              ? (removed.length > 0 ? 'rename' : 'create')
              : 'remove',
            paths: [...created, ...removed],
          } as WatchEvent);
        }

        lastSnapshot = currentSnapshot;
      } catch (err) {
        // 轮询错误静默处理
      }
    };

    // 首次初始化快照
    poll();
    const timer = setInterval(poll, interval);
    this._pollingTimers.push(timer);

    return () => {
      clearInterval(timer);
      const idx = this._pollingTimers.indexOf(timer);
      if (idx >= 0) this._pollingTimers.splice(idx, 1);
    };
  },

  /**
   * 停止所有文件监听
   */
  unwatchAll(): void {
    this._watcherUnsubs.forEach(unsub => { try { unsub(); } catch (_) {} });
    this._watcherUnsubs = [];
    this._pollingTimers.forEach(timer => clearInterval(timer));
    this._pollingTimers = [];
    console.log('[fileService] 已停止所有文件监听');
  },
};

export default fileService;

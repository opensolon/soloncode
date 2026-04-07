/**
 * Git 服务 - 封装 Tauri Git 操作
 * 通过 invoke() 调用 Rust 层 git 命令
 */

import { invoke } from '@tauri-apps/api/core';

// ==================== 类型定义 ====================

export interface GitFileStatus {
  path: string;
  status: 'modified' | 'added' | 'deleted' | 'untracked';
  staged: boolean;
}

export interface GitStatus {
  branch: string;
  ahead: number;
  behind: number;
  files: GitFileStatus[];
}

export interface GitLogEntry {
  hash: string;
  short_hash: string;
  author: string;
  date: string;
  message: string;
}

export interface DiffLine {
  line: number;
  type: 'added' | 'modified' | 'deleted';
}

// 检测 Tauri 环境
function isTauriEnv(): boolean {
  return typeof window !== 'undefined' &&
    ('__TAURI__' in window || '__TAURI_INTERNALS__' in window);
}

// ==================== Mock 数据 ====================

const mockGitStatus: GitStatus = {
  branch: 'main',
  ahead: 0,
  behind: 0,
  files: [],
};

const mockLog: GitLogEntry[] = [
  {
    hash: 'abc1234567890def1234567890abc1234567890',
    short_hash: 'abc1234',
    author: 'Developer',
    date: '2025-01-01 12:00:00 +0800',
    message: '初始提交',
  },
];

// ==================== Git 服务 ====================

export const gitService = {
  /**
   * 获取 Git 状态
   */
  async status(cwd: string): Promise<GitStatus> {
    if (!isTauriEnv()) {
      console.warn('[gitService] 非 Tauri 环境，返回 mock 数据');
      return { ...mockGitStatus };
    }

    try {
      const result = await invoke<GitStatus>('git_status', { cwd });
      return result;
    } catch (err) {
      console.error('[gitService] git_status 失败:', err);
      return { ...mockGitStatus, branch: '', files: [] };
    }
  },

  /**
   * 暂存文件
   */
  async add(cwd: string, paths: string[]): Promise<void> {
    if (!isTauriEnv()) {
      console.log('[gitService] mock add:', paths);
      return;
    }
    await invoke('git_add', { cwd, paths });
  },

  /**
   * 取消暂存文件
   */
  async reset(cwd: string, paths: string[]): Promise<void> {
    if (!isTauriEnv()) {
      console.log('[gitService] mock reset:', paths);
      return;
    }
    await invoke('git_reset', { cwd, paths });
  },

  /**
   * 提交更改
   */
  async commit(cwd: string, message: string): Promise<void> {
    if (!isTauriEnv()) {
      console.log('[gitService] mock commit:', message);
      return;
    }
    await invoke('git_commit', { cwd, message });
  },

  /**
   * 推送到远程
   */
  async push(cwd: string): Promise<void> {
    if (!isTauriEnv()) {
      console.log('[gitService] mock push');
      return;
    }
    await invoke('git_push', { cwd });
  },

  /**
   * 拉取远程
   */
  async pull(cwd: string): Promise<void> {
    if (!isTauriEnv()) {
      console.log('[gitService] mock pull');
      return;
    }
    await invoke('git_pull', { cwd });
  },

  /**
   * 获取提交历史
   */
  async log(cwd: string, count: number = 20): Promise<GitLogEntry[]> {
    if (!isTauriEnv()) {
      return [...mockLog];
    }

    try {
      return await invoke<GitLogEntry[]>('git_log', { cwd, count });
    } catch (err) {
      console.error('[gitService] git_log 失败:', err);
      return [];
    }
  },

  /**
   * 获取分支列表
   */
  async branches(cwd: string): Promise<string[]> {
    if (!isTauriEnv()) {
      return ['main'];
    }

    try {
      return await invoke<string[]>('git_branches', { cwd });
    } catch (err) {
      console.error('[gitService] git_branches 失败:', err);
      return [];
    }
  },

  /**
   * 切换分支
   */
  async checkout(cwd: string, branch: string): Promise<void> {
    if (!isTauriEnv()) {
      console.log('[gitService] mock checkout:', branch);
      return;
    }
    await invoke('git_checkout', { cwd, branch });
  },

  /**
   * 丢弃文件更改
   */
  async discard(cwd: string, paths: string[]): Promise<void> {
    if (!isTauriEnv()) {
      console.log('[gitService] mock discard:', paths);
      return;
    }
    await invoke('git_discard', { cwd, paths });
  },

  /**
   * 获取单个文件的 git diff（与 HEAD 比较）
   */
  async diffFile(cwd: string, filePath: string): Promise<DiffLine[]> {
    if (!isTauriEnv()) {
      console.log('[gitService] mock diffFile:', filePath);
      return [];
    }
    try {
      return await invoke<DiffLine[]>('git_diff_file', { cwd, filePath });
    } catch (err) {
      console.error('[gitService] git_diff_file 失败:', err);
      return [];
    }
  },
};

export default gitService;

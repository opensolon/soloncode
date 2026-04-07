/**
 * 文件监听 Hook - 监听工作区文件变化并自动刷新
 * @author bai
 */
import { useEffect, useRef, useCallback } from 'react';
import { fileService } from '../services/fileService';

interface UseFileWatcherOptions {
  /** 工作区路径 */
  workspacePath: string | null;
  /** 文件变化回调 */
  onChange?: (paths: string[]) => void;
  /** 监听间隔（非 Tauri 环境，毫秒） */
  pollingInterval?: number;
  /** 是否启用 */
  enabled?: boolean;
}

/**
 * 监听工作区文件变化
 * - Tauri 环境使用原生文件监听
 * - 浏览器环境使用轮询
 */
export function useFileWatcher({
  workspacePath,
  onChange,
  pollingInterval = 3000,
  enabled = true,
}: UseFileWatcherOptions) {
  const unwatchRef = useRef<(() => void) | null>(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  // 开始监听
  const startWatching = useCallback(async (path: string) => {
    // 先清理旧的监听
    if (unwatchRef.current) {
      unwatchRef.current();
      unwatchRef.current = null;
    }

    try {
      const unsub = await fileService.watchPath(
        path,
        (event) => {
          const paths = event.paths || [];
          if (paths.length > 0 && onChangeRef.current) {
            onChangeRef.current(paths);
          }
        },
        { recursive: true }
      );
      unwatchRef.current = unsub;
      console.log('[useFileWatcher] 开始监听:', path);
    } catch (err) {
      console.error('[useFileWatcher] 监听失败:', err);
    }
  }, []);

  useEffect(() => {
    if (!workspacePath || !enabled) {
      if (unwatchRef.current) {
        unwatchRef.current();
        unwatchRef.current = null;
      }
      return;
    }

    startWatching(workspacePath);

    return () => {
      if (unwatchRef.current) {
        unwatchRef.current();
        unwatchRef.current = null;
      }
    };
  }, [workspacePath, enabled, startWatching]);

  // 组件卸载时清理所有监听
  useEffect(() => {
    return () => {
      fileService.unwatchAll();
    };
  }, []);
}

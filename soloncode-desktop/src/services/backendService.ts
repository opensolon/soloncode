/**
 * 后端服务管理
 * 通过 soloncode 命令启动/停止后端 CLI 进程
 *
 * Solon 框架端口规则：
 *   server.port = HTTP + WebSocket 共用端口
 */
import { fileService } from './fileService';

const DEFAULT_PORT = 4808;

// 检测 Tauri 环境
function isTauriEnv(): boolean {
  return typeof window !== 'undefined' &&
    ('__TAURI__' in window || '__TAURI_INTERNALS__' in window);
}

/**
 * 轮询等待后端 WebSocket 就绪
 */
function waitForReady(port: number, maxRetries: number = 60): Promise<boolean> {
  return new Promise((resolve) => {
    let retries = 0;

    const check = () => {
      retries++;
      try {
        const ws = new WebSocket(`ws://localhost:${port}/ws`);
        const timeout = setTimeout(() => {
          ws.close();
          if (retries < maxRetries) {
            setTimeout(check, 500);
          } else {
            console.warn('[backendService] 后端就绪超时');
            resolve(false);
          }
        }, 1000);

        ws.onopen = () => {
          clearTimeout(timeout);
          ws.close();
          console.log('[backendService] 后端就绪');
          resolve(true);
        };
        ws.onerror = () => {
          clearTimeout(timeout);
          if (retries < maxRetries) {
            setTimeout(check, 500);
          } else {
            resolve(false);
          }
        };
      } catch {
        if (retries < maxRetries) {
          setTimeout(check, 500);
        } else {
          resolve(false);
        }
      }
    };

    check();
  });
}

export const backendService = {
  /**
   * 启动后端服务
   * @param workspacePath 工作区路径
   * @param port CLI 服务端口（默认 4808）
   * @returns 成功返回端口号，失败返回 null
   */
  async start(workspacePath: string, port: number = DEFAULT_PORT): Promise<number | null> {
    if (!isTauriEnv()) {
      console.warn('[backendService] 非 Tauri 环境，跳过后端启动');
      return null;
    }

    try {
      await fileService.writeLog(`backendService.start called, workspacePath=${workspacePath}, port=${port}`);
      const existingBackend = await fileService.detectBackend(port);
      if (existingBackend) {
        await fileService.writeLog(`existing backend detected on port ${port}, reuse directly`);
        console.log('[backendService] existing backend detected, reuse directly', { port });
        return port;
      }
      console.log('[backendService] 启动后端...', { workspacePath, port });
      const pid = await fileService.startBackend(workspacePath, port);
      await fileService.writeLog(`startBackend returned PID=${pid}`);
      if (pid === 0) {
        await fileService.writeLog(`backend port ${port} was reused by startBackend`);
        return port;
      }
      console.log('[backendService] 后端进程 PID:', pid);

      const ready = await waitForReady(port);
      if (!ready) {
        await fileService.writeLog('waitForReady timeout');
        console.error('[backendService] 后端启动超时');
        return null;
      }

      await fileService.writeLog(`backend ready on port ${port}`);
      return port;
    } catch (err: any) {
      const errMsg = String(err || '');
      await fileService.writeLog(`start failed: ${errMsg}`);
      if (errMsg.includes('端口') && errMsg.includes('已被占用')) {
        alert(errMsg);
      }
      console.warn('[backendService] 后端启动失败:', err);
      return null;
    }
  },

  /**
   * 停止后端服务
   */
  async stop(): Promise<void> {
    console.log('[backendService] 停止后端');
    await fileService.stopBackend();
  },

  /**
   * 检查后端是否运行中
   */
  async isRunning(): Promise<boolean> {
    return await fileService.backendStatus();
  },
};

export default backendService;

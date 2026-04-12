/**
 * 后端服务管理
 * 通过 soloncode 命令启动/停止后端 CLI 进程
 *
 * Solon 框架端口规则：
 *   server.port = HTTP + WebSocket 共用端口
 */
import { fileService } from './fileService';

const SERVER_PORT = 4808;  // HTTP 端口（传给 --server.port）
const WS_PORT = 4808;      // WebSocket 端口（与 HTTP 共用）

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
   * @returns 成功返回 WS 端口号，失败返回 null
   */
  async start(workspacePath: string): Promise<number | null> {
    if (!isTauriEnv()) {
      console.warn('[backendService] 非 Tauri 环境，跳过后端启动');
      return null;
    }

    try {
      console.log('[backendService] 启动后端...', { workspacePath, serverPort: SERVER_PORT, wsPort: WS_PORT });
      const pid = await fileService.startBackend(workspacePath, SERVER_PORT);
      console.log('[backendService] 后端进程 PID:', pid);

      // 等待 WebSocket 端口就绪（Solon WS 端口 = server.port + 10000）
      const ready = await waitForReady(WS_PORT);
      if (!ready) {
        console.error('[backendService] 后端启动超时');
        return null;
      }

      return WS_PORT;
    } catch (err) {
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

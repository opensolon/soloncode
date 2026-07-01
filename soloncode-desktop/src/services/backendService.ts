/**
 * Backend service management.
 *
 * The desktop prefers reusing an existing backend. If none is available, the
 * Tauri layer will start one and then hand the port back to the frontend.
 */
import { fileService } from './fileService';

const DEFAULT_PORT = 4808;

function isTauriEnv(): boolean {
  return typeof window !== 'undefined'
    && ('__TAURI__' in window || '__TAURI_INTERNALS__' in window);
}

export const backendService = {
  /**
   * Connect to or start the backend service.
   * The Rust `start_backend` command encapsulates the reuse-first logic so the
   * frontend does not need to probe the port by itself.
   */
  async start(workspacePath: string, port: number = DEFAULT_PORT): Promise<number | null> {
    if (!isTauriEnv()) {
      console.warn('[backendService] non-Tauri environment, skip backend start');
      return null;
    }

    try {
      await fileService.writeLog(`backendService.start called, workspacePath=${workspacePath}, port=${port}`);
      const pid = await fileService.startBackend(workspacePath, port);
      await fileService.writeLog(`startBackend returned PID=${pid}`);
      return port;
    } catch (err: any) {
      const errMsg = String(err || '');
      await fileService.writeLog(`backend start failed: ${errMsg}`);
      console.warn('[backendService] backend start failed:', err);
      return null;
    }
  },

  async stop(): Promise<void> {
    console.log('[backendService] stop managed backend');
    await fileService.stopBackend();
  },

  async isRunning(): Promise<boolean> {
    return await fileService.backendStatus();
  },
};

export default backendService;

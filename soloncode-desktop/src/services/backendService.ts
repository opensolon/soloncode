/**
 * Backend service management.
 *
 * The desktop prefers reusing an existing backend. If none is available, the
 * Tauri layer will start one and then hand the port back to the frontend.
 */
import { fileService } from './fileService';

const DEFAULT_PORT = 4808;
const BACKEND_READY_TIMEOUT_MS = 15000;
const BACKEND_READY_POLL_MS = 500;

function isTauriEnv(): boolean {
  return typeof window !== 'undefined'
    && ('__TAURI__' in window || '__TAURI_INTERNALS__' in window);
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => window.setTimeout(resolve, ms));
}

async function isBackendReady(port: number): Promise<boolean> {
  try {
    return await fileService.detectBackend(port);
  } catch {
    return false;
  }
}

async function waitForBackendReady(port: number): Promise<boolean> {
  const deadline = Date.now() + BACKEND_READY_TIMEOUT_MS;
  while (Date.now() < deadline) {
    if (await isBackendReady(port)) {
      return true;
    }
    await sleep(BACKEND_READY_POLL_MS);
  }
  return false;
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
      const ready = await waitForBackendReady(port);
      if (!ready) {
        await fileService.writeLog(`backend readiness timed out on port ${port}`);
        return null;
      }
      await fileService.writeLog(`backend ready on port ${port}`);
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

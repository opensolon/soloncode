import { useState, useEffect, useRef, useCallback } from 'react';
import { fileService } from '../services/fileService';
import { settingsService } from '../services/settingsService';
import { backendService } from '../services/backendService';
import { setBackendPort as setChatBackendPort } from '../components/ChatView';
import type { BackendStatus } from '../components/layout/StatusBar';

export function useBackend() {
  const backendPortRef = useRef<number>(4808);
  const [backendPort, setBackendPortState] = useState<number | null>(null);
  const [backendStatus, setBackendStatus] = useState<BackendStatus>('connecting');
  const wsRef = useRef<WebSocket | null>(null);
  const lastConnectedAtRef = useRef<number>(0);
  const failedProbeCountRef = useRef<number>(0);
  const httpProbeInFlightRef = useRef(false);

  const markConnected = useCallback((port: number) => {
    lastConnectedAtRef.current = Date.now();
    failedProbeCountRef.current = 0;
    backendPortRef.current = port;
    setBackendPortState(port);
    setBackendStatus('connected');
    setChatBackendPort(port);
  }, []);

  const markProbeFailed = useCallback(() => {
    failedProbeCountRef.current += 1;
    const lastConnectedAt = lastConnectedAtRef.current;
    const hasRecentSuccess = lastConnectedAt > 0 && Date.now() - lastConnectedAt < 90_000;

    if (failedProbeCountRef.current >= 3 && !hasRecentSuccess) {
      setBackendStatus(prev => prev === 'connecting' ? 'connecting' : 'disconnected');
    }
  }, []);

  useEffect(() => {
    let disposed = false;

    const checkViaHttp = async (port: number) => {
      if (httpProbeInFlightRef.current) return;
      httpProbeInFlightRef.current = true;
      try {
        const resp = await fetch(`http://localhost:${port}/version`, { cache: 'no-store' });
        if (!disposed && resp.ok) {
          markConnected(port);
        } else if (!disposed) {
          markProbeFailed();
        }
      } catch {
        if (!disposed) markProbeFailed();
      } finally {
        httpProbeInFlightRef.current = false;
      }
    };

    const checkViaWs = (port: number): boolean => {
      const ws = wsRef.current;
      if (ws?.readyState === WebSocket.OPEN) {
        markConnected(port);
        return true;
      }
      return ws?.readyState === WebSocket.CONNECTING;
    };

    const connectWs = (port: number) => {
      if (disposed) return;
      const current = wsRef.current;
      if (current && (current.readyState === WebSocket.OPEN || current.readyState === WebSocket.CONNECTING)) {
        return;
      }

      try {
        const ws = new WebSocket(`ws://localhost:${port}/ws`);
        wsRef.current = ws;
        ws.onopen = () => { if (!disposed) markConnected(port); };
        ws.onclose = () => { if (wsRef.current === ws) wsRef.current = null; };
        ws.onerror = () => { if (wsRef.current === ws) wsRef.current = null; };
      } catch {
        wsRef.current = null;
      }
    };

    const heartbeat = () => {
      const port = backendPortRef.current;
      if (!checkViaWs(port)) {
        connectWs(port);
        checkViaHttp(port);
      }
    };

    heartbeat();

    const timer = setInterval(heartbeat, 30000);
    return () => {
      disposed = true;
      clearInterval(timer);
      wsRef.current?.close();
      wsRef.current = null;
    };
  }, [markConnected, markProbeFailed]);

  const startBackend = useCallback(async (cliPort: number, onSettingsUpdate?: (updater: (prev: any) => any) => void) => {
    setBackendStatus('connecting');
    fileService.writeLog(`Starting backend on port ${cliPort}`);

    try {
      const port = await backendService.start('', cliPort);
      if (port) {
        markConnected(port);

        const cliConfig = await fileService.readGlobalChatModel();
        if (cliConfig && cliConfig.apiUrl && onSettingsUpdate) {
          onSettingsUpdate(prev => {
            settingsService.fetchModelsFromBackend(port, cliConfig.apiUrl, cliConfig.apiKey, prev.providers, cliConfig.provider, cliConfig.model)
              .then(result => {
                if (result) {
                  onSettingsUpdate(p => {
                    const updated = { ...p, providers: result.providers };
                    if (result.activeProviderId) {
                      updated.activeProviderId = result.activeProviderId;
                    }
                    settingsService.save(updated);
                    return updated;
                  });
                }
              });
            return prev;
          });
        }
      } else {
        setBackendPortState(null);
        setBackendStatus('disconnected');
      }
    } catch {
      setBackendPortState(null);
      setBackendStatus('disconnected');
    }
  }, [markConnected]);

  useEffect(() => { setChatBackendPort(backendPort); }, [backendPort]);

  const updateWorkspaceForChat = useCallback((_path: string | null) => {
    if (backendPortRef.current) { setChatBackendPort(backendPortRef.current); }
  }, []);

  const reconnectBackend = useCallback(async (onSettingsUpdate?: (updater: (prev: any) => any) => void) => {
    wsRef.current?.close();
    wsRef.current = null;
    const port = backendPortRef.current;
    await startBackend(port, onSettingsUpdate);
  }, [startBackend]);

  return { backendPort, backendPortRef, backendStatus, startBackend, reconnectBackend, updateWorkspaceForChat };
}

import { useState, useEffect, useRef, useCallback } from 'react';
import { fileService } from '../services/fileService';
import { settingsService } from '../services/settingsService';
import { backendService } from '../services/backendService';
import { setBackendPort as setChatBackendPort, sendModelConfig } from '../components/ChatView';
import type { BackendStatus } from '../components/layout/StatusBar';

export function useBackend() {
  const backendPortRef = useRef<number>(4808);
  const [backendPort, setBackendPortState] = useState<number | null>(null);
  const [backendStatus, setBackendStatus] = useState<BackendStatus>('connecting');
  const wsRef = useRef<WebSocket | null>(null);

  // 心跳：通过 WebSocket ping 检测，失败回退 HTTP
  useEffect(() => {
    const port = backendPortRef.current;
    let ws: WebSocket | null = null;

    const checkViaHttp = async () => {
      try {
        const resp = await fetch(`http://localhost:${port}/chat/models`);
        if (resp.ok) {
          setBackendStatus('connected');
          setBackendPortState(prev => prev ?? port);
          setChatBackendPort(port);
        } else {
          setBackendStatus(prev => prev === 'connecting' ? 'connecting' : 'disconnected');
        }
      } catch {
        setBackendStatus(prev => prev === 'connecting' ? 'connecting' : 'disconnected');
      }
    };

    const checkViaWs = (): boolean => {
      if (ws && ws.readyState === WebSocket.OPEN) {
        setBackendStatus('connected');
        return true;
      }
      return false;
    };

    const heartbeat = () => {
      if (!checkViaWs()) {
        checkViaHttp();
      }
    };

    heartbeat();

    const connectWs = () => {
      try {
        ws = new WebSocket(`ws://localhost:${port}/ws`);
        wsRef.current = ws;
        ws.onopen = () => { setBackendStatus('connected'); };
        ws.onclose = () => { ws = null; wsRef.current = null; };
        ws.onerror = () => { ws = null; };
      } catch {
        ws = null;
      }
    };
    connectWs();

    const timer = setInterval(heartbeat, 30000);
    return () => {
      clearInterval(timer);
      if (ws) ws.close();
    };
  }, []);

  // 启动后端
  const startBackend = useCallback(async (cliPort: number, onSettingsUpdate?: (updater: (prev: any) => any) => void) => {
    setBackendStatus('connecting');
    fileService.writeLog(`Starting backend on port ${cliPort}`);

    try {
      const port = await backendService.start('', cliPort);
      if (port) {
        backendPortRef.current = port;
        setBackendPortState(port);
        setBackendStatus('connected');
        setChatBackendPort(port);

        const cliConfig = await fileService.readGlobalChatModel();
        if (cliConfig && cliConfig.apiUrl && onSettingsUpdate) {
          onSettingsUpdate(prev => {
            settingsService.fetchModelsFromBackend(port, cliConfig.apiUrl, cliConfig.apiKey, prev.providers)
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
  }, []);

  useEffect(() => { setChatBackendPort(backendPort); }, [backendPort]);

  const updateWorkspaceForChat = useCallback((path: string | null) => {
    if (backendPortRef.current) { setChatBackendPort(backendPortRef.current); }
  }, []);

  const reconnectBackend = useCallback(async (onSettingsUpdate?: (updater: (prev: any) => any) => void) => {
    const port = backendPortRef.current;
    await startBackend(port, onSettingsUpdate);
  }, [startBackend]);

  return { backendPort, backendPortRef, backendStatus, startBackend, reconnectBackend, updateWorkspaceForChat };
}

import { lazy, Suspense, useState, useCallback, useRef, useEffect, useMemo } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { ActivityBar, type ActivityType } from './components/layout/ActivityBar';
import { TitleBar } from './components/layout/TitleBar';
import { SidePanel } from './components/layout/SidePanel';
import { StatusBar, type BackendStatus } from './components/layout/StatusBar';
import { ExplorerPanel } from './components/sidebar/ExplorerPanel';
import { GitPanel } from './components/sidebar/GitPanel';
import { ExtensionsPanel } from './components/sidebar/ExtensionsPanel';
import { SessionsPanel, type Session, type Project } from './components/sidebar/SessionsPanel';
import {
  AutomationDetail,
  AutomationPanel,
  type AutomationModelOption,
  type AutomationUpdateInput,
} from './components/sidebar/AutomationPanel';
import { SkillsPanel } from './components/sidebar/SkillsPanel';
import { AgentsPanel } from './components/sidebar/AgentsPanel';
import { SettingsPanel, type Settings } from './components/sidebar/SettingsPanel';
import type { ChatReviewFile } from './components/ChatHeader';
import { ChatView, type PromptCreationMode, type PromptCreationType } from './components/ChatView';
import type { SendOptions } from './components/ChatInput';
import { fileService } from './services/fileService';
import { gitService } from './services/gitService';
import { DEFAULT_PROMPTS, settingsService } from './services/settingsService';
import { updateService } from './services/updateService';
import { setBackendPort as setChatBackendPort, setWorkspacePath as setChatWorkspacePath, sendModelConfig } from './components/ChatView';
import { useFileWatcher } from './hooks/useFileWatcher';
import { startWindowDrag, startWindowResize } from './hooks/useWindowDrag';
import { useBackend } from './hooks/useBackend';
import { useGit } from './hooks/useGit';
import { useFileManager } from './hooks/useFileManager';
import { useSessions } from './hooks/useSessions';
import {
  UNLINKED_PROJECT,
  addAutomation,
  addAutomationRun,
  deleteAutomation,
  getAllAutomations,
  getAutomation,
  updateAutomation,
  updateAutomationRun,
  saveMessage,
  db,
  type DbAutomation,
  type DbAutomationRun,
} from './db';
import { cronMatchesDate, getCronMinuteKey, getCronValidationError, getLatestCronRun } from './utils/cron';
import type { GeneratedAutomationPlan } from './utils/automationPlan';
import { useWorkspace } from './hooks/useWorkspace';
import type { Conversation, Plugin, Theme } from './types';
import type { GitFileStatus } from './services/gitService';
import './App.css';

const EditorPanel = lazy(() => import('./components/editor/EditorPanel').then(module => ({ default: module.EditorPanel })));
const TerminalPanel = lazy(() => import('./components/terminal/TerminalPanel').then(module => ({ default: module.TerminalPanel })));

// 模拟扩展
const mockExtensions = [
  { id: '1', name: 'Markdown 渲染器', description: '增强 Markdown 渲染', version: '1.0.0', installed: true, enabled: true, author: 'SolonCode' },
  { id: '2', name: '代码格式化', description: '自动格式化代码', version: '2.1.0', installed: true, enabled: true, author: 'SolonCode' },
];

const plugins: Plugin[] = [];

const defaultSettings: Settings = {
  theme: 'dark', fontSize: 14, language: 'zh-CN',
  autoCheckUpdates: false,
  lastUpdateCheckAt: '',
  editorTheme: 'auto',
  tabSize: 2, autoSave: true, formatOnSave: true,
  shell: 'bash', terminalFontSize: 14,
  providers: [], activeProviderId: '', maxSteps: 30,
  cliPort: 4808,
  sessionWindowSize: 8,
  compressionMaxMessages: 40,
  compressionMaxTokens: 64000,
  sandboxEnabled: true,
  sandboxAllowUserHome: false,
  sandboxSystemRestrict: true,
  memoryEnabled: true,
  memoryIsolation: true,
  modelRetries: 3,
  mcpRetries: 3,
  apiRetries: 3,
  cliPrintSimplified: true,
  webAuthUser: '',
  webAuthPass: '',
  bashAsyncEnabled: false,
  subagentEnabled: true,
  mcpEnabled: true,
  openApiEnabled: true,
  lspEnabled: true,
  loopDefaultMaxTokens: 0,
  loopDefaultMaxDuration: 0,
  loopStagnationThreshold: 3,
  loopMaxConsecutiveErrors: 3,
  loopPauseAutoAbandonHours: 24,
  loopBudgetWarningPercent: 70,
  loopBudgetCriticalPercent: 85,
  loopValidatorEnabled: false,
  disallowedTools: [],
  mounts: [],
  openApiServers: [],
  lspServers: [],
  mcpServers: [],
  skills: [],
  agents: [],
  skillPrompt: DEFAULT_PROMPTS.skillPrompt,
  agentPrompt: DEFAULT_PROMPTS.agentPrompt,
  gitPrompt: DEFAULT_PROMPTS.gitPrompt,
};

type PanelPosition = 'editor' | 'chat';

interface PanelState {
  editorVisible: boolean;
  chatVisible: boolean;
  editorWidth: number;
  chatWidth: number;
  panelOrder: PanelPosition[];
}

function normalizePath(path: string): string {
  return path.trim().replace(/\\/g, '/').replace(/\/+/g, '/');
}

function isAbsolutePath(path: string): boolean {
  return /^[A-Za-z]:\//.test(path) || path.startsWith('/');
}

function resolveWorkspaceFilePath(path: string, workspacePath: string | null): string {
  let filePath = path.trim();
  try {
    filePath = decodeURIComponent(filePath);
  } catch {
    // 保持原始路径
  }

  filePath = filePath.replace(/^file:\/\/\//i, '').replace(/^file:\/\//i, '');
  filePath = filePath.replace(/^\/([A-Za-z]:\/)/, '$1');
  filePath = normalizePath(filePath).replace(/[?#].*$/, '');

  if (!workspacePath || isAbsolutePath(filePath)) {
    return filePath;
  }

  return `${normalizePath(workspacePath).replace(/\/$/, '')}/${filePath.replace(/^\//, '')}`;
}

function applyAppTheme(theme: Theme) {
  document.documentElement.setAttribute('data-theme', theme);
  localStorage.setItem('soloncode-theme', theme);
}

function applyAppFontSize(fontSize: number) {
  const size = Math.min(24, Math.max(10, Number(fontSize) || 14));
  document.documentElement.style.setProperty('--font-size-base', `${size}px`);
}

function normalizeEditorTheme(editorTheme?: string) {
  if (editorTheme === 'vs-dark' || editorTheme === 'light' || !editorTheme) return 'auto';
  return editorTheme;
}

function normalizeLoadedSettings(settings: Settings): Settings {
  const enabledProviders = settings.providers.filter(provider => provider.enabled);
  const hasActiveProvider = settings.activeProviderId
    ? settings.providers.some(provider => provider.id === settings.activeProviderId)
    : false;
  return {
    ...settings,
    editorTheme: normalizeEditorTheme(settings.editorTheme),
    activeProviderId: hasActiveProvider
      ? settings.activeProviderId
      : (enabledProviders[0]?.id || settings.providers[0]?.id || ''),
  };
}

function parseDefaultOptions(value?: string): Record<string, unknown> | undefined {
  if (!value?.trim()) return undefined;
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : undefined;
  } catch {
    console.warn('[App] 默认模型选项 JSON 无效，已跳过');
    return undefined;
  }
}

function countDiffStats(diffText: string) {
  return diffText.split('\n').reduce(
    (stats, line) => {
      if (line.startsWith('+++') || line.startsWith('---')) return stats;
      if (line.startsWith('+')) stats.additions += 1;
      if (line.startsWith('-')) stats.deletions += 1;
      return stats;
    },
    { additions: 0, deletions: 0 }
  );
}

type ReviewBaseline = Record<string, string>;

function isDirectoryLikeReviewPath(path: string) {
  const normalized = path.trim().replace(/\\/g, '/');
  return normalized.length === 0 || normalized.endsWith('/');
}

function isReviewableGitStatus(status: GitFileStatus['status']) {
  return status === 'modified' || status === 'added' || status === 'deleted' || status === 'untracked';
}

function isReviewableGitFile(file: GitFileStatus) {
  return isReviewableGitStatus(file.status) && !isDirectoryLikeReviewPath(file.path);
}

function makeReviewSignature(file: GitFileStatus, stats: { additions: number; deletions: number }) {
  return `${file.status}:${file.staged ? 'staged' : 'unstaged'}:+${stats.additions}:-${stats.deletions}`;
}

function toAbsoluteReviewPath(cwd: string, filePath: string) {
  if (/^[A-Za-z]:[\\/]/.test(filePath) || filePath.startsWith('/') || filePath.startsWith('\\\\')) {
    return filePath;
  }
  const base = cwd.replace(/[\\/]+$/, '');
  const relative = filePath.replace(/^[\\/]+/, '').replace(/\\/g, '/');
  return `${base}/${relative}`;
}

async function resolveReviewStats(cwd: string, file: GitFileStatus) {
  let stats = countDiffStats(await gitService.diffText(cwd, file.path));

  if (stats.additions === 0 && stats.deletions === 0 && (file.status === 'added' || file.status === 'untracked')) {
    try {
      const content = await fileService.readFile(toAbsoluteReviewPath(cwd, file.path));
      const normalized = content.replace(/\r\n/g, '\n');
      stats = {
        additions: normalized.length === 0 ? 0 : normalized.split('\n').length,
        deletions: 0,
      };
    } catch {
      // 保持 diff 结果，交给后续过滤处理
    }
  }

  return stats;
}

function hasMeaningfulReviewChange(file: GitFileStatus, stats: { additions: number; deletions: number }) {
  if (!isReviewableGitFile(file)) return false;
  if (stats.additions > 0 || stats.deletions > 0) return true;
  return file.status === 'deleted';
}

function getAutomationRunError(error: unknown): string {
  const message = error instanceof Error
    ? error.message
    : (typeof error === 'string' ? error : '自动化运行失败');
  return message.trim().replace(/\s+/g, ' ').slice(0, 300) || '自动化运行失败';
}

function App() {
  const [activeActivity, setActiveActivity] = useState<ActivityType>('sessions');
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [gitPanelVisible, setGitPanelVisible] = useState(false);
  const [settings, setSettings] = useState<Settings>(defaultSettings);
  const [settingsVisible, setSettingsVisible] = useState(false);
  const [activeAgent, setActiveAgent] = useState<string>('default');
  const [promptCreation, setPromptCreation] = useState<PromptCreationMode | null>(null);
  const [aiCreateRefreshKey, setAiCreateRefreshKey] = useState(0);
  const [automationRefreshKey, setAutomationRefreshKey] = useState(0);
  const [selectedAutomation, setSelectedAutomation] = useState<DbAutomation | null>(null);
  const [runningAutomationId, setRunningAutomationId] = useState<number | null>(null);
  const runningAutomationIdRef = useRef<number | null>(null);
  const scheduledAutomationQueueRef = useRef<number[]>([]);
  const scheduledAutomationQueueProcessingRef = useRef(false);
  const [scheduledAutomationQueueVersion, setScheduledAutomationQueueVersion] = useState(0);
  const [automationPrompt, setAutomationPrompt] = useState<{
    runId: string;
    sessionId: string;
    prompt: string;
    modelId: string;
    modelName: string;
    reasoningEffort: DbAutomation['reasoningEffort'];
  } | null>(null);
  const [newSessionFromProject, setNewSessionFromProject] = useState(false);
  const [sessionRunStates, setSessionRunStates] = useState<Record<string, 'running' | 'completed' | 'error'>>({});
  const [sessionReviewFiles, setSessionReviewFiles] = useState<Record<string, ChatReviewFile[]>>({});
  const resolvedSessionIdsRef = useRef<Record<string, string>>({});
  const automationRunBySessionRef = useRef<Record<string, number>>({});
  const lastAutomationScheduleCheckRef = useRef<Date | null>(null);
  const sessionReviewBaselinesRef = useRef<Record<string, ReviewBaseline>>({});
  const sessionReviewBaselinePromisesRef = useRef<Record<string, Promise<void>>>({});
  const [currentTheme, setCurrentTheme] = useState<Theme>(() => {
    const saved = localStorage.getItem('soloncode-theme') as Theme | null;
    const theme = saved || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    applyAppTheme(theme);
    return theme;
  });

  const automationModels = useMemo<AutomationModelOption[]>(() => {
    const options = new Map<string, AutomationModelOption>();
    for (const provider of settings.providers) {
      if (!provider.enabled) continue;
      if (provider.availableModels?.length) {
        for (const model of provider.availableModels) {
          const id = `${provider.id}__${model.id}`;
          options.set(id, { id, name: model.id, label: `${model.id} · ${provider.name}` });
        }
      } else if (provider.model) {
        options.set(provider.id, { id: provider.id, name: provider.model, label: `${provider.model} · ${provider.name}` });
      }
    }
    return Array.from(options.values());
  }, [settings.providers]);

  const toggleTheme = useCallback(() => {
    setCurrentTheme(prev => {
      const next = prev === 'dark' ? 'light' : 'dark';
      applyAppTheme(next);
      setSettings(current => {
        const updated = { ...current, theme: next };
        settingsService.save(updated);
        return updated;
      });
      return next;
    });
  }, []);

  useEffect(() => {
    if (import.meta.env.DEV) return;
    const handler = (e: MouseEvent) => e.preventDefault();
    document.addEventListener('contextmenu', handler);
    return () => document.removeEventListener('contextmenu', handler);
  }, []);

  useEffect(() => {
    const handler = (e: ErrorEvent) => console.error('[App] Uncaught error:', e.error || e.message);
    const rejectHandler = (e: PromiseRejectionEvent) => console.error('[App] Unhandled rejection:', e.reason);
    window.addEventListener('error', handler);
    window.addEventListener('unhandledrejection', rejectHandler);
    return () => {
      window.removeEventListener('error', handler);
      window.removeEventListener('unhandledrejection', rejectHandler);
    };
  }, []);

  useEffect(() => {
    settingsService.load().then(async s => {
      const normalizedSettings = normalizeLoadedSettings(s);
      setSettings(normalizedSettings);
      if (normalizedSettings.theme) {
        setCurrentTheme(normalizedSettings.theme);
        applyAppTheme(normalizedSettings.theme);
      }
      applyAppFontSize(normalizedSettings.fontSize);

      try {
        const discoveredAgents = await invoke<Array<{
          name: string;
          description: string;
          path: string;
          enabled: boolean;
        }>>('list_agents');
        setSettings(current => ({
          ...current,
          agents: discoveredAgents.map(agent => ({
            ...agent,
            source: 'discovered' as const,
          })),
        }));
      } catch {
        // Web 开发环境没有 Tauri 命令，继续使用已保存的 Agents。
      }
    });
  }, []);

  const handleSettingsChange = useCallback((newSettings: Settings) => {
    const normalizedSettings = normalizeLoadedSettings(newSettings);
    const prevActive = settings.providers.find(p => p.id === settings.activeProviderId);
    const nextActive = normalizedSettings.providers.find(p => p.id === normalizedSettings.activeProviderId);
    if (normalizedSettings.theme) {
      setCurrentTheme(normalizedSettings.theme);
      applyAppTheme(normalizedSettings.theme);
    }
    applyAppFontSize(normalizedSettings.fontSize);
    setSettings(normalizedSettings);
    settingsService.save(normalizedSettings);
    settingsService.syncRuntimeSettings(normalizedSettings.cliPort || 4808, normalizedSettings);
    if (nextActive && (
      !prevActive ||
      prevActive.apiUrl !== nextActive.apiUrl ||
      prevActive.apiKey !== nextActive.apiKey ||
      prevActive.model !== nextActive.model ||
      prevActive.type !== nextActive.type ||
      prevActive.contextLength !== nextActive.contextLength ||
      prevActive.timeout !== nextActive.timeout ||
      prevActive.scope !== nextActive.scope ||
      prevActive.defaultOptions !== nextActive.defaultOptions ||
      settings.activeProviderId !== normalizedSettings.activeProviderId
    )) {
      sendModelConfig(nextActive);
    }
  }, [settings]);

  // ====== Hooks ======
  const { backendPort, backendPortRef, backendStatus, startBackend, reconnectBackend } = useBackend();
  const {
    openFiles, activeFilePath, activeFile, setActiveFilePath,
    handleFileSelect: handleFileSelectInternal, handleFileClose, handleContentChange,
    handleFileSave, handleSaveCurrentFile, clearEditorState, setOpenFiles,
  } = useFileManager(null, () => {
    setPanelState(prev => ({ ...prev, editorVisible: false }));
  });

  const handleFileSelect = useCallback(async (path: string) => {
    setSelectedAutomation(null);
    await handleFileSelectInternal(path);
  }, [handleFileSelectInternal]);

  const {
    sessions, currentSessionId, setCurrentSessionId, currentConversation,
    handleNewSession, handleDeleteSession, handleUpdateSessionTitle,
    incrementSessionMessageCount,
    remapProjectPath,
    restoreLastSession,
  } = useSessions(null, {
    onSessionIdResolved: (oldId, newId) => {
      resolvedSessionIdsRef.current[oldId] = newId;
      const automationRunId = automationRunBySessionRef.current[oldId];
      if (automationRunId) {
        automationRunBySessionRef.current[newId] = automationRunId;
        delete automationRunBySessionRef.current[oldId];
        void updateAutomationRun(automationRunId, { sessionId: newId }).catch(err => {
          console.error('[App] 更新自动化运行记录会话失败:', err);
        });
      }
      setSessionRunStates(prev => {
        const state = prev[oldId];
        if (!state) return prev;
        const next = { ...prev };
        delete next[oldId];
        next[newId] = state;
        return next;
      });
      setSessionReviewFiles(prev => {
        const files = prev[oldId];
        if (!files) return prev;
        const next = { ...prev };
        delete next[oldId];
        next[newId] = files;
        return next;
      });
      if (sessionReviewBaselinesRef.current[oldId]) {
        sessionReviewBaselinesRef.current[newId] = sessionReviewBaselinesRef.current[oldId];
        delete sessionReviewBaselinesRef.current[oldId];
      }
      if (sessionReviewBaselinePromisesRef.current[oldId]) {
        sessionReviewBaselinePromisesRef.current[newId] = sessionReviewBaselinePromisesRef.current[oldId];
        delete sessionReviewBaselinePromisesRef.current[oldId];
      }
    },
  });

  const {
    activeProjectPath, projectRefreshKey, projects, workspaceName,
    refreshFileTree, openFolderByPath,
    handleSetActiveProject, handleRemoveProject, handlePinProject, handleRenameProject, handleCreateProject,
  } = useWorkspace({
    setOpenFiles, setActiveFilePath,
    setActiveActivity,
    setSettings,
    backendPortRef,
    setCurrentSessionId,
    restoreLastSession,
    onProjectPathChanged: (oldPath, newPath) => {
      remapProjectPath(oldPath, newPath);
      setAutomationRefreshKey(prev => prev + 1);
    },
  });

  const chatWorkspacePath = useMemo(() => {
    if (currentConversation.workspacePath === UNLINKED_PROJECT) return null;
    return currentConversation.workspacePath || null;
  }, [currentConversation.workspacePath]);

  useEffect(() => {
    setChatWorkspacePath(chatWorkspacePath);
  }, [chatWorkspacePath]);

  const { gitStatus, diffLines, refreshGitStatus, setGitStatus } = useGit(activeProjectPath, activeFilePath, gitPanelVisible);

  const [diffFiles, setDiffFiles] = useState<Record<string, string>>({});

  const statusBarModel = useMemo(
    () => settings.providers.find(p => p.id === settings.activeProviderId)?.model,
    [settings.providers, settings.activeProviderId]
  );

  const hasUnsavedChanges = useMemo(
    () => openFiles.some(f => f.modified),
    [openFiles]
  );

  const cursorLine = useMemo(() => {
    if (!activeFile) return undefined;
    return activeFile.content.split('\n').length;
  }, [activeFile?.content]);

  // 面板状态
  const [panelState, setPanelState] = useState<PanelState>({
    editorVisible: false, chatVisible: true,
    editorWidth: 0, chatWidth: 0,
    panelOrder: ['editor', 'chat'],
  });
  const [sidebarWidth, setSidebarWidth] = useState(260);

  useEffect(() => {
    let rafId = 0;
    const updatePanelWidths = () => {
      cancelAnimationFrame(rafId);
      rafId = requestAnimationFrame(() => {
        if (!containerRef.current) return;
        const containerWidth = containerRef.current.clientWidth;
        const activityBarWidth = 48;
        const sw = sidebarCollapsed ? 0 : Math.floor(containerWidth * 0.20);
        setSidebarWidth(sw);
        const remainingWidth = containerWidth - activityBarWidth - (sidebarCollapsed ? 0 : sw);
        const editorWidth = Math.floor(remainingWidth * 0.45 / 0.75);
        const chatWidth = remainingWidth - editorWidth;
        setPanelState(prev => ({ ...prev, editorWidth: Math.max(300, editorWidth), chatWidth: Math.max(200, chatWidth) }));
      });
    };
    updatePanelWidths();
    window.addEventListener('resize', updatePanelWidths);
    return () => { cancelAnimationFrame(rafId); window.removeEventListener('resize', updatePanelWidths); };
  }, [sidebarCollapsed]);

  // 文件监听（仅在项目管理面板可见时启用）
  useFileWatcher({
    workspacePath: activeProjectPath,
    onChange: async () => { refreshFileTree(); },
    enabled: !!activeProjectPath && activeActivity === 'explorer',
  });

  // 配置文件监听
  useFileWatcher({
    workspacePath: activeProjectPath ? `${activeProjectPath}/.soloncode` : null,
    onChange: async () => {
      if (activeProjectPath) {
        const configUpdate = await settingsService.loadConfigFile(activeProjectPath);
        if (configUpdate) { setSettings(prev => ({ ...prev, ...configUpdate })); showToast('配置已重新加载'); }
      }
    },
    enabled: !!activeProjectPath,
  });

  // 启动后端
  useEffect(() => {
    let cancelled = false;
    const port = settings.cliPort || 4808;
    startBackend(port, (updater) => setSettings(updater)).then(async () => {
      if (cancelled) return;
      const runtimeSettings = await settingsService.loadRuntimeSettings(
        port,
        settingsService.runtimeSettingsSections.core,
      );
      if (cancelled) return;
      if (!runtimeSettings) return;
      setSettings(prev => {
        const updated = { ...prev, ...runtimeSettings };
        settingsService.save(updated);
        return updated;
      });
    });
    return () => {
      cancelled = true;
    };
  }, []);

  // 拖拽调整大小
  const lastStartedCliPortRef = useRef(defaultSettings.cliPort);
  useEffect(() => {
    const port = settings.cliPort || 4808;
    if (lastStartedCliPortRef.current === port) return;
    lastStartedCliPortRef.current = port;
    let cancelled = false;
    startBackend(port, (updater) => setSettings(updater)).then(async () => {
      if (cancelled) return;
      await settingsService.syncRuntimeSettings(port, settings);
      if (cancelled) return;
      const runtimeSettings = await settingsService.loadRuntimeSettings(
        port,
        settingsService.runtimeSettingsSections.core,
      );
      if (cancelled) return;
      if (!runtimeSettings) return;
      setSettings(prev => {
        const updated = { ...prev, ...runtimeSettings };
        settingsService.save(updated);
        return updated;
      });
    });
    return () => {
      cancelled = true;
    };
  }, [settings.cliPort, startBackend]);

  const [isResizing, setIsResizing] = useState<string | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!isResizing) return;
    const handleMouseMove = (e: MouseEvent) => {
      if (!containerRef.current) return;
      const containerRect = containerRef.current.getBoundingClientRect();
      const sw = sidebarCollapsed ? 48 : 48 + sidebarWidth;
      const relativeX = e.clientX - containerRect.left - sw;
      if (isResizing === 'editor') {
        setPanelState(prev => ({ ...prev, editorWidth: Math.max(300, relativeX) }));
      } else if (isResizing === 'chat') {
        const totalWidth = containerRect.width - sw;
        setPanelState(prev => ({ ...prev, chatWidth: Math.max(200, totalWidth - relativeX) }));
      }
    };
    const handleMouseUp = () => setIsResizing(null);
    document.addEventListener('mousemove', handleMouseMove);
    document.addEventListener('mouseup', handleMouseUp);
    return () => { document.removeEventListener('mousemove', handleMouseMove); document.removeEventListener('mouseup', handleMouseUp); };
  }, [isResizing, sidebarCollapsed]);

  const startResize = useCallback((panel: string, e: React.MouseEvent) => { e.preventDefault(); setIsResizing(panel); }, []);

  // 文件操作
  const handleNewFile = useCallback(async (projectPath?: string) => {
    const basePath = projectPath || activeProjectPath;
    if (!basePath) return;
    let path = `${basePath}/untitled`, counter = 1;
    while (await fileService.pathExists(path)) { path = `${basePath}/untitled-${counter}`; counter++; }
    await fileService.createFile(path);
    refreshFileTree(basePath);
    handleFileSelect(path);
  }, [activeProjectPath, refreshFileTree, handleFileSelect]);

  const handleNewFolder = useCallback(async (projectPath?: string) => {
    const basePath = projectPath || activeProjectPath;
    if (!basePath) return;
    let path = `${basePath}/new-folder`, counter = 1;
    while (await fileService.pathExists(path)) { path = `${basePath}/new-folder-${counter}`; counter++; }
    await fileService.createDirectory(path);
    refreshFileTree(basePath);
  }, [activeProjectPath, refreshFileTree]);

  const handleRename = useCallback(async (oldPath: string, newPath: string) => {
    await fileService.renameItem(oldPath, newPath);
    setOpenFiles(prev => prev.map(f => f.path === oldPath ? { ...f, path: newPath, name: newPath.split(/[/\\]/).pop() || f.name } : f));
    if (activeFilePath === oldPath) setActiveFilePath(newPath);
    await refreshFileTree();
  }, [refreshFileTree, activeFilePath, setOpenFiles, setActiveFilePath]);

  const handleDelete = useCallback(async (path: string, type: 'file' | 'folder') => {
    if (type === 'folder') await fileService.deleteDirectory(path); else await fileService.deleteFile(path);
    setOpenFiles(prev => {
      const remaining = prev.filter(f => !f.path.startsWith(path));
      if (activeFilePath?.startsWith(path) && remaining.length > 0) setActiveFilePath(remaining[remaining.length - 1].path);
      else if (remaining.length === 0) setActiveFilePath(null);
      return remaining;
    });
    await refreshFileTree();
  }, [refreshFileTree, activeFilePath, setOpenFiles, setActiveFilePath]);

  const handleCopy = useCallback(async (sourcePath: string, destPath: string) => { await fileService.copyItem(sourcePath, destPath); await refreshFileTree(); }, [refreshFileTree]);
  const handleMove = useCallback(async (sourcePath: string, destPath: string) => {
    await fileService.moveItem(sourcePath, destPath);
    setOpenFiles(prev => prev.map(f => f.path === sourcePath ? { ...f, path: destPath, name: destPath.split(/[/\\]/).pop() || f.name } : f));
    if (activeFilePath === sourcePath) setActiveFilePath(destPath);
    await refreshFileTree();
  }, [refreshFileTree, activeFilePath, setOpenFiles, setActiveFilePath]);

  const handleOpenFile = useCallback(async () => {
    try {
      const selectedPath = await fileService.openFileDialog({
        multiple: false,
        filters: [
          { name: '所有文件', extensions: ['*'] },
          { name: 'TypeScript', extensions: ['ts', 'tsx'] },
          { name: 'JavaScript', extensions: ['js', 'jsx'] },
          { name: '文本文件', extensions: ['txt', 'md', 'json'] },
        ],
      });
      if (selectedPath && typeof selectedPath === 'string') {
        setSelectedAutomation(null);
        const file = await fileService.openFile(selectedPath);
        setOpenFiles(prev => prev.some(f => f.path === selectedPath) ? prev : [...prev, file]);
        setActiveFilePath(selectedPath);
      }
    } catch (err) { console.error('打开文件失败:', err); }
  }, [setOpenFiles, setActiveFilePath]);

  const handleOpenFolder = useCallback(async () => {
    const selectedPath = await fileService.openFolderDialog();
    if (selectedPath) await openFolderByPath(selectedPath);
  }, [openFolderByPath]);

  const handleChatFileSelect = useCallback((path: string) => {
    const trimmed = path.trim().replace(/\\/g, '/');
    if (!trimmed || trimmed === '.' || trimmed === './' || trimmed === '..' || trimmed === '../' || trimmed.endsWith('/')) return;
    if (!/\.[a-zA-Z0-9]+$/.test(trimmed) && !trimmed.includes('.')) return;
    const filePath = resolveWorkspaceFilePath(path, activeProjectPath);
    if (activeProjectPath && normalizePath(filePath) === normalizePath(activeProjectPath)) return;
    setPanelState(prev => ({ ...prev, editorVisible: true }));
    handleFileSelect(filePath);
  }, [activeProjectPath, handleFileSelect]);

  const handleDiffFileSelect = useCallback(async (relPath: string) => {
    if (!activeProjectPath) return;
    const absPath = activeProjectPath.replace(/\\/g, '/') + '/' + relPath;
    const original = await gitService.showHead(activeProjectPath, relPath);
    setPanelState(prev => ({ ...prev, editorVisible: true }));
    await handleFileSelect(absPath);
    setDiffFiles(prev => ({ ...prev, [absPath]: original }));
  }, [activeProjectPath, handleFileSelect]);

  const captureReviewBaseline = useCallback(async (sessionId: string, workspacePath?: string | null) => {
    const cwd = workspacePath && workspacePath !== UNLINKED_PROJECT ? workspacePath : null;
    if (!cwd) {
      delete sessionReviewBaselinesRef.current[sessionId];
      delete sessionReviewBaselinePromisesRef.current[sessionId];
      return;
    }

    const promise = (async () => {
      const status = await gitService.status(cwd);
      const files = status.files.filter(isReviewableGitFile);
      const entries = await Promise.all(files.map(async file => {
        const stats = await resolveReviewStats(cwd, file);
        return [file.path, makeReviewSignature(file, stats)] as const;
      }));
      sessionReviewBaselinesRef.current[sessionId] = Object.fromEntries(entries);
    })();
    sessionReviewBaselinePromisesRef.current[sessionId] = promise;
    try {
      await promise;
    } finally {
      if (sessionReviewBaselinePromisesRef.current[sessionId] === promise) {
        delete sessionReviewBaselinePromisesRef.current[sessionId];
      }
    }
  }, []);

  const captureSessionReviewFiles = useCallback(async (sessionId: string, workspacePath?: string | null) => {
    const cwd = workspacePath && workspacePath !== UNLINKED_PROJECT ? workspacePath : null;
    if (!cwd) {
      setSessionReviewFiles(prev => {
        const next = { ...prev };
        delete next[sessionId];
        return next;
      });
      delete sessionReviewBaselinesRef.current[sessionId];
      return;
    }

    const resolvedSessionId = resolvedSessionIdsRef.current[sessionId] || sessionId;
    await (sessionReviewBaselinePromisesRef.current[resolvedSessionId] || sessionReviewBaselinePromisesRef.current[sessionId]);
    await new Promise(resolve => setTimeout(resolve, 300));
    const status = await gitService.status(cwd);
    const baseline = sessionReviewBaselinesRef.current[resolvedSessionId] || sessionReviewBaselinesRef.current[sessionId];
    const changedFiles = status.files
      .filter(isReviewableGitFile);
    const reviewCandidates: Array<ChatReviewFile | null> = await Promise.all(changedFiles.map(async file => {
      const stats = await resolveReviewStats(cwd, file);
      if (!hasMeaningfulReviewChange(file, stats)) return null;
      const signature = makeReviewSignature(file, stats);
      return !baseline || baseline[file.path] === signature ? null : { path: file.path, status: file.status, ...stats };
    }));
    const reviewFiles = reviewCandidates.filter(Boolean) as ChatReviewFile[];

    setSessionReviewFiles(prev => {
      const next = { ...prev };
      if (resolvedSessionId !== sessionId) delete next[sessionId];
      if (reviewFiles.length > 0) next[resolvedSessionId] = reviewFiles;
      else delete next[resolvedSessionId];
      return next;
    });
    delete sessionReviewBaselinesRef.current[sessionId];
    delete sessionReviewBaselinesRef.current[resolvedSessionId];

    if (cwd === activeProjectPath) {
      setGitStatus(status);
    }
  }, [activeProjectPath, setGitStatus]);

  const handleDiscardReviewFile = useCallback(async (relPath: string) => {
    if (!activeProjectPath || !currentSessionId) return;
    await gitService.discard(activeProjectPath, [relPath]);
    setDiffFiles(prev => {
      const next = { ...prev };
      delete next[activeProjectPath.replace(/\\/g, '/') + '/' + relPath];
      return next;
    });
    setSessionReviewFiles(prev => {
      const next = { ...prev };
      const remaining = (next[currentSessionId] || []).filter(file => file.path !== relPath);
      if (remaining.length > 0) next[currentSessionId] = remaining;
      else delete next[currentSessionId];
      return next;
    });
    const status = await gitService.status(activeProjectPath);
    setGitStatus(status);
  }, [activeProjectPath, currentSessionId, setGitStatus]);

  // Toast
  const [terminalVisible, setTerminalVisible] = useState(false);
  const [terminalMounted, setTerminalMounted] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const autoUpdateCheckedRef = useRef(false);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const showToast = useCallback((msg: string) => {
    if (toastTimer.current) clearTimeout(toastTimer.current);
    setToast(msg);
    toastTimer.current = setTimeout(() => setToast(null), 5000);
  }, []);

  const handleSkillInstalled = useCallback((skillName: string) => {
    setAiCreateRefreshKey(current => current + 1);
    showToast(`Skill "${skillName}" 安装成功，列表已刷新`);
  }, [showToast]);

  useEffect(() => {
    if (!backendPort || !settings.autoCheckUpdates || autoUpdateCheckedRef.current) return;

    const lastCheckedAt = settings.lastUpdateCheckAt ? Date.parse(settings.lastUpdateCheckAt) : NaN;
    if (!Number.isNaN(lastCheckedAt) && Date.now() - lastCheckedAt < 12 * 60 * 60 * 1000) {
      autoUpdateCheckedRef.current = true;
      return;
    }

    autoUpdateCheckedRef.current = true;
    let cancelled = false;

    (async () => {
      try {
        const info = await updateService.checkForUpdates(backendPort);
        if (cancelled) return;

        const checkedAt = new Date().toISOString();
        setSettings(prev => {
          const updated = { ...prev, lastUpdateCheckAt: checkedAt };
          settingsService.save(updated);
          return updated;
        });

        if (info.backendUpdateAvailable || info.desktopUpdateAvailable) {
          showToast('检测到新版本，正在启动更新...');
          await updateService.installUpdates(backendPort);
        }
      } catch (err) {
        console.warn('[App] auto update check failed:', err);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [backendPort, settings.autoCheckUpdates, settings.lastUpdateCheckAt, showToast]);

  useEffect(() => {
    if (terminalVisible) setTerminalMounted(true);
  }, [terminalVisible]);

  const handleAddProject = useCallback(async () => {
    const selectedPath = await fileService.openFolderDialog();
    if (selectedPath) await openFolderByPath(selectedPath);
  }, [openFolderByPath]);

  const handleSelectSession = useCallback((id: string) => {
    setCurrentSessionId(id);
    const session = sessions.find(s => s.id === id);
    setChatWorkspacePath(session?.workspacePath && session.workspacePath !== UNLINKED_PROJECT
      ? session.workspacePath
      : null);
    if (session?.workspacePath && session.workspacePath !== UNLINKED_PROJECT && session.workspacePath !== activeProjectPath) {
      void handleSetActiveProject(session.workspacePath);
    }
  }, [sessions, activeProjectPath, handleSetActiveProject, setCurrentSessionId]);

  const handleCreateSessionInProject = useCallback((projectId?: string) => {
    const linked = Boolean(projectId && projectId !== UNLINKED_PROJECT);
    setNewSessionFromProject(linked);
    setChatWorkspacePath(linked ? projectId! : null);
    if (projectId && projectId !== UNLINKED_PROJECT && projectId !== activeProjectPath) {
      void handleSetActiveProject(projectId);
    }
    return handleNewSession(projectId);
  }, [activeProjectPath, handleNewSession, handleSetActiveProject]);

  const handleSyncSession = useCallback(async (sessionId: string) => {
    const port = backendPort || 4808;
    try {
      const resp = await fetch(`http://localhost:${port}/web/chat/messages?sessionId=${encodeURIComponent(sessionId)}`);
      if (!resp.ok) return;
      const json = await resp.json();
      const messages: Array<{ role: string; content: string }> = json.data || json;
      if (!Array.isArray(messages) || messages.length === 0) return;

      // 先清除该会话的旧消息
      await db.messages.where('conversationId').equals(sessionId).delete();

      // 写入新消息
      for (const msg of messages) {
        const contents = JSON.stringify([{ type: 'TEXT', text: msg.content }]);
        await saveMessage({
          conversationId: sessionId,
          role: (msg.role || 'USER').toUpperCase() as any,
          timestamp: new Date().toLocaleTimeString(),
          contents,
        });
      }
    } catch (err) {
      console.error('[SyncSession] Failed:', sessionId, err);
    }
  }, [backendPort]);

  const togglePanel = useCallback((panel: 'editor' | 'chat') => {
    setPanelState(prev => {
      const newVisible = !prev[`${panel}Visible`];
      if (panel === 'chat' && !newVisible) setSidebarCollapsed(true);
      return { ...prev, [`${panel}Visible`]: newVisible };
    });
  }, []);

  const swapPanels = useCallback(() => {
    setPanelState(prev => ({ ...prev, panelOrder: [...prev.panelOrder].reverse() }));
  }, []);

  const handleStartPromptCreation = useCallback((type: PromptCreationType) => {
    setPanelState(prev => ({ ...prev, chatVisible: true }));
    setSidebarCollapsed(false);
    setAutomationPrompt(null);
    setNewSessionFromProject(false);
    setChatWorkspacePath(null);

    const label = type === 'skill' ? 'Skill' : type === 'agent' ? 'Agent' : '自动化';
    const projectId = type === 'automation' ? UNLINKED_PROJECT : undefined;
    const sessionId = handleNewSession(projectId, `创建 ${label}`);
    if (!sessionId) {
      showToast(`无法进入${label}创建模式`);
      return;
    }

    setPromptCreation({
      id: `${type}-${Date.now()}`,
      sessionId,
      type,
      projectId,
      template: type === 'skill' ? settings.skillPrompt : type === 'agent' ? settings.agentPrompt : undefined,
    });
  }, [handleNewSession, settings.agentPrompt, settings.skillPrompt]);

  const handleAiCreateComplete = useCallback(async (info: { type: PromptCreationType; name: string; error?: string }) => {
    setPromptCreation(null);
    if (info.error) {
      if (info.type === 'automation') showToast(`自动化创建失败：${info.error}`);
      else showToast(`${info.type === 'skill' ? 'Skill' : 'Agent'} "${info.name}" 创建失败`);
      return;
    }
    try {
      if (info.type === 'skill') {
        const skills = await invoke<Array<{ name: string; description: string; path: string; enabled: boolean }>>('list_skills');
        setSettings(prev => ({ ...prev, skills: skills.map(s => ({ ...s, source: 'discovered' as const, group: 'global' as const })) }));
      } else if (info.type === 'agent') {
        const agents = await invoke<Array<{ name: string; description: string; path: string; enabled: boolean }>>('list_agents');
        setSettings(prev => ({ ...prev, agents: agents.map(a => ({ ...a, source: 'discovered' as const })) }));
      }
      if (info.type !== 'automation') {
        showToast(`${info.type === 'skill' ? 'Skill' : 'Agent'} "${info.name}" 已创建`);
      }
    } catch (err) {
      console.error('[App] AI 创建完成刷新失败:', err);
    } finally {
      setAiCreateRefreshKey(current => current + 1);
    }
  }, []);

  const handleCreateAutomationFromPrompt = useCallback(async (plan: GeneratedAutomationPlan, options: SendOptions) => {
    const creation = promptCreation?.type === 'automation' ? promptCreation : null;
    if (!creation) {
      throw new Error('自动化创建上下文已失效，请重新进入创建模式');
    }
    const projectId = creation.projectId || UNLINKED_PROJECT;
    const project = projectId === UNLINKED_PROJECT
      ? { id: UNLINKED_PROJECT, name: '未关联项目' }
      : projects.find(item => item.id === projectId);
    if (!project) throw new Error('创建自动化时选择的项目已不可用');
    if (!options.model || !options.modelName) {
      throw new Error('请先选择一个可用模型');
    }

    const now = new Date().toISOString();
    try {
      await addAutomation({
        title: plan.title,
        prompt: plan.prompt,
        projectId: project.id,
        projectName: project.name,
        modelId: options.model,
        modelName: options.modelName,
        reasoningEffort: options.reasoningEffort,
        scheduleEnabled: plan.scheduleEnabled,
        cron: plan.cron,
        createdAt: now,
        updatedAt: now,
        runCount: 0,
      });
      setPromptCreation(null);
      setAutomationRefreshKey(current => current + 1);
      const creationSessionId = resolvedSessionIdsRef.current[creation.sessionId] || creation.sessionId;
      handleDeleteSession(creationSessionId);
      setActiveActivity('automation');
      showToast(plan.scheduleEnabled ? '定时自动化已创建' : '自动化已创建');
    } catch (err) {
      console.error('[App] 创建自动化失败:', err);
      throw err;
    }
  }, [handleDeleteSession, projects, promptCreation]);

  const handleRunAutomation = useCallback(async (automation: DbAutomation, automationRunId?: number) => {
    const unlinked = automation.projectId === UNLINKED_PROJECT;
    const project = unlinked ? null : projects.find(item => item.id === automation.projectId);
    if (!unlinked && !project) throw new Error(`项目已不在列表中：${automation.projectName}`);

    if (unlinked) setChatWorkspacePath(null);
    else await handleSetActiveProject(automation.projectId);
    setPanelState(prev => ({ ...prev, chatVisible: true }));
    setSidebarCollapsed(false);
    setNewSessionFromProject(!unlinked);
    setPromptCreation(null);

    const sessionId = handleNewSession(automation.projectId, automation.title);
    if (!sessionId) throw new Error('无法创建自动化会话');
    if (automationRunId) {
      automationRunBySessionRef.current[sessionId] = automationRunId;
    }

    setAutomationPrompt({
      runId: `${automation.id || 'automation'}-${Date.now()}`,
      sessionId,
      prompt: automation.prompt,
      modelId: automation.modelId,
      modelName: automation.modelName,
      reasoningEffort: automation.reasoningEffort,
    });
    setActiveActivity('sessions');
    return sessionId;
  }, [handleNewSession, handleSetActiveProject, projects]);

  const handleSelectAutomation = useCallback((automation: DbAutomation) => {
    setSelectedAutomation(automation);
    setPanelState(prev => ({ ...prev, editorVisible: true }));
  }, []);

  const handleAutomationDeleted = useCallback((automationId: number) => {
    if (selectedAutomation?.id !== automationId) return;
    setSelectedAutomation(null);
    if (openFiles.length === 0) {
      setPanelState(prev => ({ ...prev, editorVisible: false }));
    }
  }, [openFiles.length, selectedAutomation?.id]);

  const handleRunAutomationFromDetail = useCallback(async (
    automation: DbAutomation,
    trigger: 'manual' | 'scheduled' = 'manual',
  ): Promise<boolean> => {
    if (!automation.id || runningAutomationIdRef.current !== null) return false;
    runningAutomationIdRef.current = automation.id;
    setRunningAutomationId(automation.id);
    const startedAt = new Date().toISOString();
    let automationRunId: number;

    try {
      automationRunId = await addAutomationRun({
        automationId: automation.id,
        status: 'running',
        trigger,
        projectId: automation.projectId,
        projectName: automation.projectName,
        modelId: automation.modelId,
        modelName: automation.modelName,
        reasoningEffort: automation.reasoningEffort,
        startedAt,
      });
    } catch (err) {
      runningAutomationIdRef.current = null;
      setRunningAutomationId(null);
      console.error('[App] 保存自动化运行记录失败:', err);
      showToast('无法保存运行记录，自动化未启动');
      return false;
    }

    try {
      const sessionId = await handleRunAutomation(automation, automationRunId);
      try {
        await updateAutomationRun(automationRunId, { sessionId });
      } catch (err) {
        console.error('[App] 关联自动化运行会话失败:', err);
      }

      const lastRunAt = startedAt;
      const updated = {
        ...automation,
        lastRunAt,
        runCount: automation.runCount + 1,
        updatedAt: lastRunAt,
      };
      try {
        await updateAutomation(automation.id, {
          lastRunAt,
          runCount: updated.runCount,
          updatedAt: lastRunAt,
        });
      } catch (err) {
        console.error('[App] 更新自动化运行统计失败:', err);
      }
      setSelectedAutomation(current => current?.id === updated.id ? updated : current);
      setAutomationRefreshKey(current => current + 1);
      if (trigger === 'scheduled') showToast(`定时任务 "${automation.title}" 已启动`);
      return true;
    } catch (err) {
      for (const [mappedSessionId, mappedRunId] of Object.entries(automationRunBySessionRef.current)) {
        if (mappedRunId === automationRunId) delete automationRunBySessionRef.current[mappedSessionId];
      }
      const completedAt = new Date().toISOString();
      await updateAutomationRun(automationRunId, {
        status: 'error',
        completedAt,
        error: getAutomationRunError(err),
      }).catch(updateErr => {
        console.error('[App] 更新自动化失败记录失败:', updateErr);
      });
      setAutomationRefreshKey(current => current + 1);
      runningAutomationIdRef.current = null;
      setRunningAutomationId(null);
      console.error('[App] 运行自动化失败:', err);
      showToast(err instanceof Error ? err.message : '运行自动化失败');
      return false;
    }
  }, [handleRunAutomation, showToast]);

  const handleSaveAutomation = useCallback(async (
    automation: DbAutomation,
    updates: AutomationUpdateInput,
  ): Promise<boolean> => {
    if (!automation.id || runningAutomationIdRef.current === automation.id) return false;
    const title = updates.title.trim();
    const prompt = updates.prompt.trim();
    const cron = updates.cron.trim().replace(/\s+/g, ' ');
    if (!title || title.length > 100) {
      showToast('任务名称应为 1-100 个字符');
      return false;
    }
    if (!prompt || prompt.length > 10000) {
      showToast('任务提示词应为 1-10000 个字符');
      return false;
    }
    const cronError = getCronValidationError(cron);
    if (cronError) {
      showToast(cronError);
      return false;
    }
    const project = updates.projectId === UNLINKED_PROJECT
      ? { id: UNLINKED_PROJECT, name: '未关联项目' }
      : projects.find(item => item.id === updates.projectId);
    const model = automationModels.find(item => item.id === updates.modelId);
    if (!project || !model) {
      showToast(!project ? '请选择一个可用项目' : '请选择一个可用模型');
      return false;
    }

    const updatedAt = new Date().toISOString();
    const nextAutomation: DbAutomation = {
      ...automation,
      ...updates,
      title,
      prompt,
      cron,
      projectName: project.name,
      modelName: model.name,
      updatedAt,
    };
    try {
      await updateAutomation(automation.id, {
        title,
        prompt,
        projectId: project.id,
        projectName: project.name,
        modelId: model.id,
        modelName: model.name,
        reasoningEffort: updates.reasoningEffort,
        scheduleEnabled: updates.scheduleEnabled,
        cron,
        updatedAt,
      });
      if (!updates.scheduleEnabled) {
        scheduledAutomationQueueRef.current = scheduledAutomationQueueRef.current.filter(id => id !== automation.id);
        setScheduledAutomationQueueVersion(current => current + 1);
      }
      setSelectedAutomation(nextAutomation);
      setAutomationRefreshKey(current => current + 1);
      showToast('自动化配置已保存');
      return true;
    } catch (error) {
      console.error('[App] 保存自动化配置失败:', error);
      showToast('保存自动化配置失败');
      return false;
    }
  }, [automationModels, projects, showToast]);

  useEffect(() => {
    let checking = false;
    const checkSchedules = async () => {
      if (checking) return;
      checking = true;
      try {
        const now = new Date();
        const previousCheck = lastAutomationScheduleCheckRef.current;
        lastAutomationScheduleCheckRef.current = now;
        const automations = await getAllAutomations();
        let queued = false;
        for (const automation of automations) {
          if (!automation.id || !automation.scheduleEnabled) continue;
          let scheduledFor: Date | null = null;
          try {
            const cron = automation.cron || '0 9 * * *';
            scheduledFor = previousCheck
              ? getLatestCronRun(cron, previousCheck, now)
              : (cronMatchesDate(cron, now) ? new Date(Math.floor(now.getTime() / 60_000) * 60_000) : null);
          } catch (error) {
            console.error(`[App] 自动化 ${automation.id} 的 Cron 无效:`, error);
            continue;
          }
          if (!scheduledFor) continue;
          const minuteKey = getCronMinuteKey(scheduledFor);
          if (automation.lastScheduledAt === minuteKey) continue;
          await updateAutomation(automation.id, { lastScheduledAt: minuteKey });
          if (!scheduledAutomationQueueRef.current.includes(automation.id)) {
            scheduledAutomationQueueRef.current.push(automation.id);
            queued = true;
          }
        }
        if (queued) {
          setAutomationRefreshKey(current => current + 1);
          setScheduledAutomationQueueVersion(current => current + 1);
        }
      } catch (error) {
        console.error('[App] 检查自动化定时任务失败:', error);
      } finally {
        checking = false;
      }
    };

    void checkSchedules();
    const timer = window.setInterval(() => { void checkSchedules(); }, 30_000);
    const handleFocus = () => { void checkSchedules(); };
    window.addEventListener('focus', handleFocus);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener('focus', handleFocus);
    };
  }, []);

  useEffect(() => {
    if (runningAutomationIdRef.current !== null || scheduledAutomationQueueProcessingRef.current) return;
    const automationId = scheduledAutomationQueueRef.current.shift();
    if (automationId === undefined) return;
    scheduledAutomationQueueProcessingRef.current = true;
    void (async () => {
      try {
        const automation = await getAutomation(automationId);
        if (automation?.scheduleEnabled) {
          await handleRunAutomationFromDetail(automation, 'scheduled');
        }
      } catch (error) {
        console.error('[App] 启动排队的自动化任务失败:', error);
      } finally {
        scheduledAutomationQueueProcessingRef.current = false;
        setScheduledAutomationQueueVersion(current => current + 1);
      }
    })();
  }, [handleRunAutomationFromDetail, runningAutomationId, scheduledAutomationQueueVersion]);

  const handleDeleteAutomationFromDetail = useCallback(async (automation: DbAutomation) => {
    if (!automation.id || runningAutomationId !== null) return;
    try {
      await deleteAutomation(automation.id);
      setSelectedAutomation(null);
      if (openFiles.length === 0) {
        setPanelState(prev => ({ ...prev, editorVisible: false }));
      }
      setAutomationRefreshKey(current => current + 1);
      showToast(`自动化任务 "${automation.title}" 已删除`);
    } catch (err) {
      console.error('[App] 删除自动化失败:', err);
      showToast('删除自动化失败');
    }
  }, [openFiles.length, runningAutomationId]);

  const handleCloseAutomationDetail = useCallback(() => {
    setSelectedAutomation(null);
    if (openFiles.length === 0) {
      setPanelState(prev => ({ ...prev, editorVisible: false }));
    }
  }, [openFiles.length]);

  const handleOpenAutomationRun = useCallback((run: DbAutomationRun) => {
    if (!run.sessionId) return;
    const sessionId = resolvedSessionIdsRef.current[run.sessionId] || run.sessionId;
    if (!sessions.some(session => session.id === sessionId)) {
      showToast('该运行记录关联的对话已不存在');
      return;
    }
    handleCloseAutomationDetail();
    setPanelState(prev => ({ ...prev, chatVisible: true }));
    setSidebarCollapsed(false);
    setActiveActivity('sessions');
    handleSelectSession(sessionId);
  }, [handleCloseAutomationDetail, handleSelectSession, sessions, showToast]);

  // 渲染侧边栏内容
  const renderSidebarContent = () => {
    if (sidebarCollapsed) return null;
    switch (activeActivity) {
      case 'explorer':
        return (
          <ExplorerPanel
            projects={projects} activeProjectPath={activeProjectPath} refreshKey={projectRefreshKey}
            onFileSelect={handleFileSelect} onOpenFolder={handleOpenFolder} onCreateProject={handleCreateProject}
            onRemoveProject={handleRemoveProject} onRenameProject={handleRenameProject} onSetActiveProject={handleSetActiveProject}
            onRefreshProject={refreshFileTree} onNewFile={handleNewFile} onNewFolder={handleNewFolder}
            onRename={handleRename} onDelete={handleDelete} onCopy={handleCopy} onMove={handleMove}
          />
        );
      case 'extensions':
        return <ExtensionsPanel extensions={mockExtensions} onInstall={async (id) => console.log('安装:', id)} onUninstall={async (id) => console.log('卸载:', id)} onToggle={(id) => console.log('切换:', id)} />;
      case 'sessions':
        return (
          <SessionsPanel
            projects={projects} sessions={sessions} currentSessionId={currentSessionId} currentProjectId={activeProjectPath}
            backendPort={backendPort}
            sessionRunStates={sessionRunStates}
            onSelectSession={handleSelectSession} onNewSession={handleCreateSessionInProject} onDeleteSession={handleDeleteSession}
            onCreateProject={handleCreateProject} onAddProject={handleAddProject} onRemoveProject={handleRemoveProject} onPinProject={handlePinProject} onRenameProject={(projectId, name) => {
              void handleRenameProject(projectId, name).catch(err => {
                showToast(err instanceof Error ? err.message : '重命名项目失败');
              });
            }}
            onSyncSession={handleSyncSession}
          />
        );
      case 'automation':
        return (
          <AutomationPanel
            projects={projects}
            refreshKey={automationRefreshKey}
            selectedAutomationId={selectedAutomation?.id}
            onCreateWithPrompt={() => handleStartPromptCreation('automation')}
            onSelectAutomation={handleSelectAutomation}
            onAutomationDeleted={handleAutomationDeleted}
          />
        );
      case 'skills':
        return <SkillsPanel backendPort={backendPort} refreshKey={aiCreateRefreshKey} onFileSelect={(path) => { setPanelState(prev => ({ ...prev, editorVisible: true })); handleFileSelect(path); }} onCreateWithAI={() => handleStartPromptCreation('skill')} />;
      case 'agents':
        return <AgentsPanel agents={settings.agents} refreshKey={aiCreateRefreshKey} onAgentsChange={(agents) => setSettings(prev => ({ ...prev, agents }))} activeAgent={activeAgent} onAgentChange={setActiveAgent} onFileSelect={(path) => { setPanelState(prev => ({ ...prev, editorVisible: true })); handleFileSelect(path); }} onCreateWithAI={() => handleStartPromptCreation('agent')} />;
      default:
        return null;
    }
  };

  // 渲染面板
  const renderPanel = (panel: PanelPosition) => {
    const bothVisible = panelState.editorVisible && panelState.chatVisible;
    const visiblePanelCount = (panelState.editorVisible ? 1 : 0) + (panelState.chatVisible ? 1 : 0) + (gitPanelVisible ? 1 : 0);

    if (panel === 'editor') {
      if (!panelState.editorVisible) return null;
      return (
        <div key="editor" className="panel-wrapper editor-wrapper" style={bothVisible ? { width: panelState.editorWidth } : undefined}>
          {selectedAutomation ? (
            <AutomationDetail
              automation={selectedAutomation}
              projects={projects}
              models={automationModels}
              running={runningAutomationId === selectedAutomation.id}
              runDisabled={runningAutomationId !== null && runningAutomationId !== selectedAutomation.id}
              runRefreshKey={automationRefreshKey}
              onRun={automation => { void handleRunAutomationFromDetail(automation); }}
              onOpenRun={handleOpenAutomationRun}
              onSave={handleSaveAutomation}
              onDelete={automation => { void handleDeleteAutomationFromDetail(automation); }}
              onClose={handleCloseAutomationDetail}
            />
          ) : (
            <Suspense fallback={<div className="panel-loading">Loading editor...</div>}>
              <EditorPanel files={openFiles} activeFilePath={activeFilePath} onFileSelect={setActiveFilePath} onFileClose={(path) => { handleFileClose(path); setDiffFiles(prev => { const next = { ...prev }; delete next[path]; return next; }); }} onContentChange={handleContentChange} onFileSave={handleFileSave} theme={currentTheme} editorTheme={settings.editorTheme} fontSize={settings.fontSize} tabSize={settings.tabSize} autoSave={settings.autoSave} formatOnSave={settings.formatOnSave} diffLines={diffLines} diffFiles={diffFiles} />
            </Suspense>
          )}
          {bothVisible && <div className="resize-handle vertical" onMouseDown={(e) => startResize('editor', e)} />}
        </div>
      );
    }
    if (panel === 'chat') {
      if (!panelState.chatVisible) return null;
      const inputWidth = 50 + visiblePanelCount * 10;
      return (
        <div key="chat" className="panel-wrapper chat-wrapper" style={{ ...(bothVisible ? { flex: '1 1 auto' } : {}), '--input-max-width': `${inputWidth}%` } as React.CSSProperties}>
          <ChatView
            currentConversation={currentConversation} plugins={plugins} workspacePath={chatWorkspacePath || undefined} projectName={workspaceName || undefined}
            theme={currentTheme} backendPort={backendPort} onUpdateSessionTitle={handleUpdateSessionTitle} onNewSession={(title) => {
              setNewSessionFromProject(false);
              setChatWorkspacePath(null);
              return handleNewSession(UNLINKED_PROJECT, title);
            }}
            sessions={sessions} sessionRunStates={sessionRunStates} maxSteps={settings.maxSteps} onSelectSession={handleSelectSession}
            providers={settings.providers} agents={settings.agents} activeProviderId={settings.activeProviderId} onActiveProviderChange={(providerId: string) => { setSettings(prev => { const updated = { ...prev, activeProviderId: providerId }; settingsService.save(updated); return updated; }); }}
            activeFileName={chatWorkspacePath ? activeFile?.name : undefined}
            activeFilePath={chatWorkspacePath ? (activeFilePath || undefined) : undefined}
            onFileSelect={handleChatFileSelect}
            reviewFiles={currentSessionId ? (sessionReviewFiles[currentSessionId] || []) : []}
            onReviewFileSelect={handleDiffFileSelect}
            onReviewFileDiscard={handleDiscardReviewFile}
            onNewProject={handleCreateProject} onOpenFolder={handleOpenFolder}
            promptCreation={promptCreation}
            onCreateAutomationFromPrompt={handleCreateAutomationFromPrompt}
            onAiCreateComplete={handleAiCreateComplete}
            automationPrompt={automationPrompt}
            onAutomationPromptConsumed={(runId) => {
              setAutomationPrompt(current => current?.runId === runId ? null : current);
            }}
            newSessionFromProject={newSessionFromProject}
            onSessionRunStateChange={(sessionId, status, error) => {
              setSessionRunStates(prev => ({ ...prev, [sessionId]: status }));
              if (status === 'completed' || status === 'error') {
                const resolvedSessionId = resolvedSessionIdsRef.current[sessionId] || sessionId;
                const automationRunId = automationRunBySessionRef.current[sessionId]
                  || automationRunBySessionRef.current[resolvedSessionId];
                if (automationRunId) {
                  for (const [mappedSessionId, mappedRunId] of Object.entries(automationRunBySessionRef.current)) {
                    if (mappedRunId === automationRunId) delete automationRunBySessionRef.current[mappedSessionId];
                  }
                  const completedAt = new Date().toISOString();
                  void updateAutomationRun(automationRunId, {
                    status,
                    completedAt,
                    error: status === 'error' ? getAutomationRunError(error || '会话执行失败') : undefined,
                  }).then(() => {
                    setAutomationRefreshKey(current => current + 1);
                  }).catch(err => {
                    console.error('[App] 完成自动化运行记录失败:', err);
                  });
                  runningAutomationIdRef.current = null;
                  setRunningAutomationId(null);
                }
              }
              if (status === 'running') {
                setSessionReviewFiles(prev => {
                  const next = { ...prev };
                  delete next[sessionId];
                  return next;
                });
                const session = sessions.find(item => item.id === sessionId);
                captureReviewBaseline(sessionId, session?.workspacePath);
              }
              if (status === 'completed') {
                const session = sessions.find(item => item.id === sessionId);
                captureSessionReviewFiles(sessionId, session?.workspacePath);
              }
            }}
            onSessionMessageSaved={incrementSessionMessageCount}
          />
        </div>
      );
    }
    return null;
  };

  return (
    <div className="window-frame">
      <div className="resize-edge resize-top" onMouseDown={e => startWindowResize(e, 'n')} />
      <div className="resize-edge resize-bottom" onMouseDown={e => startWindowResize(e, 's')} />
      <div className="resize-edge resize-left" onMouseDown={e => startWindowResize(e, 'w')} />
      <div className="resize-edge resize-right" onMouseDown={e => startWindowResize(e, 'e')} />
      <div className="resize-edge resize-top-left" onMouseDown={e => startWindowResize(e, 'nw')} />
      <div className="resize-edge resize-top-right" onMouseDown={e => startWindowResize(e, 'ne')} />
      <div className="resize-edge resize-bottom-left" onMouseDown={e => startWindowResize(e, 'sw')} />
      <div className="resize-edge resize-bottom-right" onMouseDown={e => startWindowResize(e, 'se')} />
    <div className="app-container" ref={containerRef}>
      <TitleBar
        workspacePath={activeProjectPath || undefined} workspaceName={workspaceName}
        onNewFile={() => handleNewFile()} onOpenFile={handleOpenFile} onOpenFolder={handleOpenFolder} onNewProject={handleCreateProject}
        onSave={handleSaveCurrentFile} onSaveAll={() => openFiles.forEach(f => handleFileSave(f.path))}
        editorVisible={panelState.editorVisible} chatVisible={panelState.chatVisible}
        terminalVisible={terminalVisible} gitPanelVisible={gitPanelVisible}
        onToggleEditor={() => togglePanel('editor')} onToggleChat={() => togglePanel('chat')}
        onToggleTerminal={() => setTerminalVisible(v => !v)} onSwapPanels={swapPanels}
        onToggleGitPanel={() => setGitPanelVisible(v => !v)}
      />
      <div className="main-area">
        <ActivityBar
          activeActivity={activeActivity} theme={currentTheme} onToggleTheme={toggleTheme}
          onActivityChange={(activity) => {
            if (activity === 'settings') { setSettingsVisible(true); return; }
            if (activity === 'git') { setGitPanelVisible(v => !v); return; }
            if (activeActivity === activity) setSidebarCollapsed(!sidebarCollapsed);
            else { setSidebarCollapsed(false); setActiveActivity(activity); }
          }}
        />
        <div className={`sidebar-container${sidebarCollapsed ? ' collapsed' : ''}`} style={!sidebarCollapsed ? { width: sidebarWidth } : undefined}>
          {!sidebarCollapsed && <SidePanel title="" width={sidebarWidth} minWidth={200} maxWidth={600}>{renderSidebarContent()}</SidePanel>}
        </div>
        <div className="right-area">
          <div className="right-area-top">
            <div className="panels-container">{panelState.panelOrder.map(panel => renderPanel(panel))}</div>
            {gitPanelVisible && (
              <div className="git-right-panel">
                <GitPanel
                  status={gitStatus} cwd={activeProjectPath || undefined} projectName={workspaceName || undefined}
                  onCommit={async (msg) => { if (activeProjectPath) { await gitService.commit(activeProjectPath, msg); refreshGitStatus(); } }}
                  onStage={async (path) => { if (activeProjectPath) { await gitService.add(activeProjectPath, [path]); refreshGitStatus(); } }}
                  onUnstage={async (path) => { if (activeProjectPath) { await gitService.reset(activeProjectPath, [path]); refreshGitStatus(); } }}
                  onPush={async () => { if (activeProjectPath) { await gitService.push(activeProjectPath); refreshGitStatus(); } }}
                  onPull={async () => { if (activeProjectPath) { await gitService.pull(activeProjectPath); refreshGitStatus(); } }}
                  onDiscard={async (path) => { if (activeProjectPath) { await gitService.discard(activeProjectPath, [path]); refreshGitStatus(); } }}
                  onFileClick={(relPath) => { handleDiffFileSelect(relPath); }}
                  onGenerateCommitMessage={async () => {
                    if (!activeProjectPath) throw new Error('无活跃项目');
                    const diff = await gitService.diffStaged(activeProjectPath);
                    if (!diff) throw new Error('没有已暂存的更改');

                    const truncatedDiff = diff.length > 8000 ? diff.substring(0, 8000) + '\n... (diff 已截断)' : diff;
                    const template = settings.gitPrompt;
                    const prompt = template.replace(/\{diff\}/g, truncatedDiff);

                    const compositeId = settings.activeProviderId || '';
                    const sepIdx = compositeId.indexOf('__');
                    const providerId = sepIdx >= 0 ? compositeId.substring(0, sepIdx) : compositeId;
                    const specificModelId = sepIdx >= 0 ? compositeId.substring(sepIdx + 2) : null;
                    const activeProvider = settings.providers.find(p => p.id === providerId);
                    const modelName = specificModelId || activeProvider?.model || '';

                    return new Promise<string>((resolve, reject) => {
                      const wsUrl = `ws://localhost:${backendPort}/desktop/ws?X-Session-Cwd=${encodeURIComponent(activeProjectPath)}`;
                      const ws = new WebSocket(wsUrl);
                      const timeout = setTimeout(() => { ws.close(); reject(new Error('生成超时')); }, 60000);
                      let text = '';

                      ws.onopen = () => {
                        // 先注册模型配置
                        if (activeProvider) {
                          ws.send(JSON.stringify({
                            type: 'config',
                            chatModel: {
                              apiUrl: activeProvider.apiUrl,
                              apiKey: activeProvider.apiKey,
                              model: modelName,
                              provider: activeProvider.type,
                              contextLength: activeProvider.contextLength,
                              timeout: activeProvider.timeout,
                              defaultOptions: parseDefaultOptions(activeProvider.defaultOptions),
                            },
                          }));
                        }
                        ws.send(JSON.stringify({ input: prompt, cwd: activeProjectPath, model: modelName }));
                      };

                      ws.onmessage = (event) => {
                        try {
                          const msg = JSON.parse(event.data);
                          if (msg.type === 'reason') text += msg.text || '';
                          if (msg.type === 'done') {
                            clearTimeout(timeout);
                            ws.close();
                            resolve(text.replace(/\s*`?\(?[\w.\-]+(?:,\s*\d+\.?\d*\w+)*\)\s*`?$/gm, '').trim());
                          }
                          if (msg.type === 'error') {
                            clearTimeout(timeout);
                            ws.close();
                            reject(new Error(msg.text || '生成失败'));
                          }
                        } catch (e) {
                          clearTimeout(timeout);
                          ws.close();
                          reject(e);
                        }
                      };

                      ws.onerror = () => { clearTimeout(timeout); reject(new Error('连接失败')); };
                    });
                  }}
                />
              </div>
            )}
          </div>
          {terminalMounted && (
            <Suspense fallback={<div className="terminal-panel"><div className="terminal-body panel-loading">Loading terminal...</div></div>}>
              <TerminalPanel visible={terminalVisible} cwd={activeProjectPath || undefined} shell={settings.shell} fontSize={settings.terminalFontSize} />
            </Suspense>
          )}
        </div>
      </div>
      <StatusBar
        backendStatus={backendStatus} model={statusBarModel} branch={gitStatus.branch}
        ahead={gitStatus.ahead} behind={gitStatus.behind} warningCount={0} errorCount={0}
        cursorLine={cursorLine} cursorColumn={1}
        encoding="UTF-8" language={activeFile?.language} hasUnsavedChanges={hasUnsavedChanges}
        onReconnect={() => reconnectBackend((updater) => setSettings(updater))}
      />
      {toast && <div className="toast-message">{toast}</div>}
      <SettingsPanel visible={settingsVisible} settings={settings} onSettingsChange={handleSettingsChange} onClose={() => setSettingsVisible(false)} onSkillInstalled={handleSkillInstalled} backendPort={backendPort} workspacePath={activeProjectPath} sessionId={currentSessionId} />
    </div>
    </div>
  );
}

export default App;

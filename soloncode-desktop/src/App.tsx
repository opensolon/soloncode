import { useState, useCallback, useRef, useEffect, useMemo } from 'react';
import { ActivityBar, type ActivityType } from './components/layout/ActivityBar';
import { TitleBar } from './components/layout/TitleBar';
import { SidePanel } from './components/layout/SidePanel';
import { StatusBar, type BackendStatus } from './components/layout/StatusBar';
import { ExplorerPanel } from './components/sidebar/ExplorerPanel';
import { GitPanel } from './components/sidebar/GitPanel';
import { ExtensionsPanel } from './components/sidebar/ExtensionsPanel';
import { SessionsPanel, type Session, type Project } from './components/sidebar/SessionsPanel';
import { SkillsPanel } from './components/sidebar/SkillsPanel';
import { AgentsPanel } from './components/sidebar/AgentsPanel';
import { getAllConversations, saveConversation, deleteConversation, updateConversation, saveLastFolder, loadLastFolder, saveLastSessionId, loadLastSessionId, migrateConversationsToProjects, getAllProjects, addProject as dbAddProject, removeProject as dbRemoveProject, UNLINKED_PROJECT, saveLastActiveProject, loadLastActiveProject, reassignMessages } from './db';
import { SettingsPanel, type Settings } from './components/sidebar/SettingsPanel';
import { EditorPanel } from './components/editor/EditorPanel';
import { ChatView } from './components/ChatView';
import { TerminalPanel } from './components/terminal/TerminalPanel';
import { fileService } from './services/fileService';
import { gitService, type GitStatus, type DiffLine } from './services/gitService';
import { settingsService } from './services/settingsService';
import { backendService } from './services/backendService';
import { setBackendPort as setChatBackendPort, setWorkspacePath as setChatWorkspacePath, sendModelConfig } from './components/ChatView';
import { useFileWatcher } from './hooks/useFileWatcher';
import { startWindowDrag } from './hooks/useWindowDrag';
import type { Conversation, Plugin, Theme } from './types';
import './App.css';

// 空 Git 状态（初始值）
const emptyGitStatus: GitStatus = {
  branch: '',
  ahead: 0,
  behind: 0,
  files: [],
};

// 模拟扩展
const mockExtensions = [
  { id: '1', name: 'Markdown 渲染器', description: '增强 Markdown 渲染', version: '1.0.0', installed: true, enabled: true, author: 'SolonCode' },
  { id: '2', name: '代码格式化', description: '自动格式化代码', version: '2.1.0', installed: true, enabled: true, author: 'SolonCode' },
];

// 插件（不变数据，放组件外）
const plugins: Plugin[] = [
  { id: 'none', name: '插件暂不支持', icon: 'cube', description: '插件暂不支持', enabled: true, version: '1.0.0' }
];

// 默认设置（从 IndexedDB 异步加载）
const defaultSettings: Settings = {
  theme: 'dark', fontSize: 14, language: 'zh-CN',
  tabSize: 2, autoSave: true, formatOnSave: true,
  shell: 'bash', terminalFontSize: 14,
  providers: [], activeProviderId: '', maxSteps: 30,
  mcpServers: [],
  skills: [],
  agents: [],
};

// 面板位置类型
type PanelPosition = 'editor' | 'chat';

interface PanelState {
  editorVisible: boolean;
  chatVisible: boolean;
  editorWidth: number;
  chatWidth: number;
  panelOrder: PanelPosition[];
}

function App() {
  const [activeActivity, setActiveActivity] = useState<ActivityType>('sessions');
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [settings, setSettings] = useState<Settings>(defaultSettings);
  const [settingsVisible, setSettingsVisible] = useState(false);
  const [activeAgent, setActiveAgent] = useState<string>('default');
  const [currentTheme, setCurrentTheme] = useState<Theme>(() => {
    const saved = localStorage.getItem('soloncode-theme') as Theme | null;
    const theme = saved || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    document.documentElement.setAttribute('data-theme', theme);
    return theme;
  });

  const toggleTheme = useCallback(() => {
    setCurrentTheme(prev => {
      const next = prev === 'dark' ? 'light' : 'dark';
      document.documentElement.setAttribute('data-theme', next);
      localStorage.setItem('soloncode-theme', next);
      return next;
    });
  }, []);

  // 非 dev 模式下禁用浏览器默认右键菜单（刷新、另存为、检查元素等）
  useEffect(() => {
    if (import.meta.env.DEV) return;
    const handler = (e: MouseEvent) => e.preventDefault();
    document.addEventListener('contextmenu', handler);
    return () => document.removeEventListener('contextmenu', handler);
  }, []);

  // 全局 JS 错误捕获
  useEffect(() => {
    const handler = (e: ErrorEvent) => {
      console.error('[App] Uncaught error:', e.error || e.message);
    };
    const rejectHandler = (e: PromiseRejectionEvent) => {
      console.error('[App] Unhandled rejection:', e.reason);
    };
    window.addEventListener('error', handler);
    window.addEventListener('unhandledrejection', rejectHandler);
    return () => {
      window.removeEventListener('error', handler);
      window.removeEventListener('unhandledrejection', rejectHandler);
    };
  }, []);

  // 启动时从 IndexedDB 加载设置
  useEffect(() => {
    settingsService.load().then(s => setSettings(s));
  }, []);

  // 设置变化时自动持久化 + 推送配置到后端
  const handleSettingsChange = useCallback((newSettings: Settings) => {
    const prevActive = settings.providers.find(p => p.id === settings.activeProviderId);
    const nextActive = newSettings.providers.find(p => p.id === newSettings.activeProviderId);

    setSettings(newSettings);
    settingsService.save(newSettings);

    // 仅当激活供应商的模型配置实际变更时才注册到后端
    if (nextActive && (
      !prevActive ||
      prevActive.apiUrl !== nextActive.apiUrl ||
      prevActive.apiKey !== nextActive.apiKey ||
      prevActive.model !== nextActive.model ||
      prevActive.type !== nextActive.type ||
      settings.activeProviderId !== newSettings.activeProviderId
    )) {
      sendModelConfig({
        apiUrl: nextActive.apiUrl,
        apiKey: nextActive.apiKey,
        model: nextActive.model,
        type: nextActive.type,
      });
    }
  }, [settings]);

  // 工作区状态 — activeProjectPath 替代原 workspacePath
  const [activeProjectPath, setActiveProjectPath] = useState<string | null>(null);
  const [projectRefreshKey, setProjectRefreshKey] = useState(0);

  // Git 状态
  const [gitStatus, setGitStatus] = useState<GitStatus>(emptyGitStatus);

  // 文件 Diff 行变更缓存
  const [diffLines, setDiffLines] = useState<DiffLine[]>([]);

  // 后端端口（固定值，不因心跳失败清除）
  const backendPortRef = useRef<number>(4808);
  const [backendPort, setBackendPortState] = useState<number | null>(null);

  // 后端连接状态：connecting -> connected / disconnected
  const [backendStatus, setBackendStatus] = useState<BackendStatus>('connecting');

  // 同步后端端口到 ChatView
  useEffect(() => {
    setChatBackendPort(backendPort);
  }, [backendPort]);

  // 心跳：每30s检测后端存活，失败只改状态不清端口，可自动恢复
  useEffect(() => {
    const port = backendPortRef.current;
    const check = async () => {
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
    check();
    const timer = setInterval(check, 30000);
    return () => clearInterval(timer);
  }, []);

  // 刷新 Git 状态
  const refreshGitStatus = useCallback(async () => {
    if (activeProjectPath) {
      const status = await gitService.status(activeProjectPath);
      setGitStatus(status);
    } else {
      setGitStatus(emptyGitStatus);
    }
  }, [activeProjectPath]);

  // 工作区变化时加载 Git 状态 + 定时刷新
  useEffect(() => {
    refreshGitStatus();
    const timer = setInterval(refreshGitStatus, 5000);
    return () => clearInterval(timer);
  }, [refreshGitStatus]);

  // 面板状态 - 默认比例 1:2:5:2 (活动栏:侧边栏:编辑器:对话框)
  const [panelState, setPanelState] = useState<PanelState>({
    editorVisible: false,
    chatVisible: true,
    editorWidth: 0, // 将在 useEffect 中根据比例计算
    chatWidth: 0,   // 将在 useEffect 中根据比例计算
    panelOrder: ['editor', 'chat'],
  });

  // 侧边栏实际宽度（计算值）
  const [sidebarWidth, setSidebarWidth] = useState(260);

  // 计算默认面板宽度比例
  useEffect(() => {
    const updatePanelWidths = () => {
      if (!containerRef.current) return;

      const containerWidth = containerRef.current.clientWidth;
      const activityBarWidth = 48; // 活动栏宽度

      // 侧边栏 20%, 编辑器 50%, 对话框 30%（按总宽度分配）
      const sw = sidebarCollapsed ? 0 : Math.floor(containerWidth * 0.20);
      setSidebarWidth(sw);

      const remainingWidth = containerWidth - activityBarWidth - (sidebarCollapsed ? 0 : sw);
      const editorWidth = Math.floor(remainingWidth * 0.45 / 0.75);
      const chatWidth = remainingWidth - editorWidth;

      setPanelState(prev => ({
        ...prev,
        editorWidth: Math.max(300, editorWidth),
        chatWidth: Math.max(200, chatWidth),
      }));
    };

    updatePanelWidths();
    window.addEventListener('resize', updatePanelWidths);
    return () => window.removeEventListener('resize', updatePanelWidths);
  }, [sidebarCollapsed]);

  // 编辑器状态
  const [openFiles, setOpenFiles] = useState<Array<{
    path: string;
    name: string;
    content: string;
    modified: boolean;
    language: string;
    isImage?: boolean;
    imageBase64?: string;
    imageMimeType?: string;
  }>>([]);
  const [activeFilePath, setActiveFilePath] = useState<string | null>(null);

  // 会话状态
  const [sessions, setSessions] = useState<Session[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<string>();

  // 记住上次新建会话时选择的项目（ChatView 发消息时关联用）
  const [pendingSessionProject, setPendingSessionProject] = useState<string | null>(null);

  // 项目列表
  const [projects, setProjects] = useState<Project[]>([]);

  // 派生：活跃项目名称
  const workspaceName = useMemo(() => {
    const p = projects.find(p => p.id === activeProjectPath);
    return p?.name || '';
  }, [projects, activeProjectPath]);

  // 会话或工作区变化时，保存最后会话 ID
  useEffect(() => {
    if (activeProjectPath && currentSessionId) {
      saveLastSessionId(activeProjectPath, currentSessionId);
    }
  }, [activeProjectPath, currentSessionId]);

  // 从 IndexedDB 加载会话列表和项目
  useEffect(() => {
    // 运行迁移 + 加载数据
    (async () => {
      await migrateConversationsToProjects();

      const [convs, dbProjects] = await Promise.all([
        getAllConversations(),
        getAllProjects(),
      ]);

      const loaded: Session[] = convs.map(c => ({
        id: c.id!.toString(),
        title: c.title,
        timestamp: c.timestamp,
        messageCount: 0,
        isPermanent: c.isPermanent,
        workspacePath: c.workspacePath || UNLINKED_PROJECT,
      }));
      setSessions(loaded);

      setProjects(dbProjects.map(p => ({
        id: p.id,
        name: p.name,
        sortOrder: p.sortOrder,
      })));
    })();

    // 启动后端（与工作区无关，始终启动，异步不阻塞）
    setBackendStatus('connecting');
    fileService.writeLog('Starting backend (no workspace dependency)');
    backendService.start('').then(async (port) => {
        if (port) {
          backendPortRef.current = port;
          setBackendPortState(port);
          setBackendStatus('connected');
          setChatBackendPort(port);

          const cliConfig = await fileService.readGlobalChatModel();
          if (cliConfig && cliConfig.apiUrl) {
            setSettings(prev => {
              settingsService.fetchModelsFromBackend(port, cliConfig.apiUrl, cliConfig.apiKey, prev.providers)
                .then(result => {
                  if (result) {
                    setSettings(p => {
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
      }).catch(() => { setBackendPortState(null); setBackendStatus('disconnected'); });

    // 启动时恢复上次的活跃项目（不扫描文件树，ExplorerPanel 懒加载）
    loadLastActiveProject().then(async (lastActive) => {
      if (!lastActive) {
        fileService.writeLog('No last active project to restore');
        return;
      }
      try {
        fileService.writeLog(`Restoring active project: ${lastActive}`);
        setActiveProjectPath(lastActive);
        setChatWorkspacePath(lastActive);
        setActiveActivity('explorer');

        // 恢复该项目的最后会话
        const lastSessionId = await loadLastSessionId(lastActive);
        if (lastSessionId) {
          setCurrentSessionId(lastSessionId);
        }
      } catch (err) {
        console.warn('[App] 恢复活跃项目失败:', err);
      }
    });
  }, []);

  // 当前活动文件（用于状态栏）
  const activeFile = openFiles.find(f => f.path === activeFilePath);

  // 拖拽调整大小
  const [isResizing, setIsResizing] = useState<string | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  // 文件监听 - 活跃项目文件变化时通知 ExplorerPanel 刷新
  useFileWatcher({
    workspacePath: activeProjectPath,
    onChange: async (_changedPaths) => {
      setProjectRefreshKey(prev => prev + 1);
    },
    enabled: !!activeProjectPath,
  });

  // 配置文件监听 - .soloncode/ 目录变化时自动重载设置
  useFileWatcher({
    workspacePath: activeProjectPath ? `${activeProjectPath}/.soloncode` : null,
    onChange: async (_changedPaths) => {
      if (activeProjectPath) {
        const configUpdate = await settingsService.loadConfigFile(activeProjectPath);
        if (configUpdate) {
          setSettings(prev => ({ ...prev, ...configUpdate }));
          showToast('配置已重新加载');
        }
      }
    },
    enabled: !!activeProjectPath,
  });

  // 获取当前活跃文件的 git diff
  useEffect(() => {
    if (!activeProjectPath || !activeFilePath) {
      setDiffLines([]);
      return;
    }
    const relPath = activeFilePath.replace(activeProjectPath.replace(/\\/g, '/').replace(/\/$/, '') + '/', '');
    gitService.diffFile(activeProjectPath, relPath).then(setDiffLines).catch(() => setDiffLines([]));
  }, [activeProjectPath, activeFilePath, gitStatus]);

  // useMemo 稳定 currentConversation，仅 sessionId/sessions 变化时重建
  const currentConversation: Conversation = useMemo(() => ({
    id: currentSessionId,
    title: sessions.find(s => s.id === currentSessionId)?.title || '新会话',
    timestamp: new Date().toLocaleString(),
    status: 'active',
  }), [currentSessionId, sessions]);

  // 切换面板可见性
  const togglePanel = useCallback((panel: 'editor' | 'chat') => {
    setPanelState(prev => {
      const newVisible = !prev[`${panel}Visible`];
      // 收起对话面板时，同时收起侧边栏
      if (panel === 'chat' && !newVisible) {
        setSidebarCollapsed(true);
      }
      return {
        ...prev,
        [`${panel}Visible`]: newVisible,
      };
    });
  }, []);

  // 交换面板位置
  const swapPanels = useCallback(() => {
    setPanelState(prev => ({
      ...prev,
      panelOrder: [...prev.panelOrder].reverse(),
    }));
  }, []);

  // 开始拖拽调整大小
  const startResize = useCallback((panel: string, e: React.MouseEvent) => {
    e.preventDefault();
    setIsResizing(panel);
  }, []);

  // 处理拖拽
  useEffect(() => {
    if (!isResizing) return;

    const handleMouseMove = (e: MouseEvent) => {
      if (!containerRef.current) return;

      const containerRect = containerRef.current.getBoundingClientRect();
      const sw = sidebarCollapsed ? 48 : 48 + sidebarWidth; // 活动栏 + 侧边栏
      const relativeX = e.clientX - containerRect.left - sw;

      if (isResizing === 'editor') {
        const newEditorWidth = Math.max(300, relativeX);
        setPanelState(prev => ({ ...prev, editorWidth: newEditorWidth }));
      } else if (isResizing === 'chat') {
        const totalWidth = containerRect.width - sw;
        const newChatWidth = Math.max(200, totalWidth - relativeX);
        setPanelState(prev => ({ ...prev, chatWidth: newChatWidth }));
      }
    };

    const handleMouseUp = () => {
      setIsResizing(null);
    };

    document.addEventListener('mousemove', handleMouseMove);
    document.addEventListener('mouseup', handleMouseUp);

    return () => {
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
    };
  }, [isResizing, sidebarCollapsed]);

  // 文件操作
  const handleFileSelect = useCallback(async (path: string) => {
    const existingFile = openFiles.find(f => f.path === path);
    if (existingFile) {
      setActiveFilePath(path);
      return;
    }

    const fileName = path.split(/[/\\]/).pop() || '';
    const ext = fileName.split('.').pop() || '';
    const langMap: Record<string, string> = {
      'ts': 'TypeScript',
      'tsx': 'TypeScript React',
      'js': 'JavaScript',
      'jsx': 'JavaScript React',
      'json': 'JSON',
      'css': 'CSS',
      'html': 'HTML',
      'md': 'Markdown',
      'java': 'Java',
      'rs': 'Rust',
      'py': 'Python',
      'go': 'Go',
    };

    // 异步读取文件内容
    try {
      const file = await fileService.openFile(path);
      setOpenFiles(prev => [...prev, file]);
      setActiveFilePath(path);
    } catch (err) {
      console.error('[App] 读取文件失败:', err);
      // 失败时显示占位符
      setOpenFiles(prev => [...prev, {
        path,
        name: fileName,
        content: `// 无法读取文件: ${fileName}`,
        modified: false,
        language: langMap[ext] || 'Plain Text',
      }]);
      setActiveFilePath(path);
    }
  }, [openFiles]);

  const handleFileClose = useCallback((path: string) => {
    setOpenFiles(prev => {
      const newFiles = prev.filter(f => f.path !== path);
      if (activeFilePath === path && newFiles.length > 0) {
        setActiveFilePath(newFiles[newFiles.length - 1].path);
      } else if (newFiles.length === 0) {
        setActiveFilePath(null);
      }
      return newFiles;
    });
  }, [activeFilePath]);

  const handleContentChange = useCallback((path: string, content: string) => {
    setOpenFiles(prev => prev.map(f =>
      f.path === path ? { ...f, content, modified: true } : f
    ));
  }, []);

  const handleFileSave = useCallback(async (path: string) => {
    const file = openFiles.find(f => f.path === path);
    if (file && activeProjectPath) {
      try {
        await fileService.writeFile(path, file.content);
        setOpenFiles(prev => prev.map(f =>
          f.path === path ? { ...f, modified: false } : f
        ));
      } catch (err) {
        console.error('保存文件失败:', err);
      }
    } else {
      setOpenFiles(prev => prev.map(f =>
        f.path === path ? { ...f, modified: false } : f
      ));
    }
  }, [openFiles, activeProjectPath]);

  // 打开文件对话框
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
        const file = await fileService.openFile(selectedPath);
        setOpenFiles(prev => {
          if (prev.some(f => f.path === selectedPath)) {
            return prev;
          }
          return [...prev, file];
        });
        setActiveFilePath(selectedPath);
      }
    } catch (err) {
      console.error('打开文件失败:', err);
    }
  }, []);

  // 打开文件夹对话框
  // 通过路径打开工作区（追加项目，不替换）
  const openFolderByPath = useCallback(async (selectedPath: string) => {
    try {
      await fileService.initWorkspaceConfig(selectedPath);
      const info = await fileService.getWorkspaceInfo(selectedPath);

      // 添加到项目列表（如不存在）
      if (!projects.find(p => p.id === selectedPath)) {
        const project = { id: selectedPath, name: info.name, sortOrder: projects.length, addedAt: new Date().toISOString() };
        await dbAddProject(project);
        setProjects(prev => [...prev, { id: project.id, name: project.name, sortOrder: project.sortOrder }]);
      }

      // 设置为活跃项目
      setActiveProjectPath(selectedPath);
      setChatWorkspacePath(selectedPath);
      saveLastFolder(selectedPath);
      saveLastActiveProject(selectedPath);

      // 清理编辑器状态
      setOpenFiles([]);
      setActiveFilePath(null);
      setGitStatus(emptyGitStatus);

      // 后端已在启动时连接，此处只更新工作区路径
      if (backendPortRef.current) {
        setChatBackendPort(backendPortRef.current);
        setChatWorkspacePath(selectedPath);
      }

      setActiveActivity('explorer');

      // 自动发现 skills（项目级 + 第三方）
      const allDiscoveredSkills = [
        ...await settingsService.scanSkillsDir(selectedPath),
        ...await settingsService.scanThirdPartySkills(selectedPath),
      ];
      if (allDiscoveredSkills.length > 0) {
        setSettings(prev => {
          const existingPaths = new Set(prev.skills.map(s => s.path));
          const newSkills = allDiscoveredSkills.filter(s => !existingPaths.has(s.path));
          if (newSkills.length > 0) {
            return { ...prev, skills: [...prev.skills, ...newSkills] };
          }
          return prev;
        });
      }

      // 自动发现 agents
      const discoveredAgents = await settingsService.scanAgentsDir(selectedPath);
      if (discoveredAgents.length > 0) {
        setSettings(prev => {
          const existingPaths = new Set(prev.agents.map(a => a.path));
          const newAgents = discoveredAgents.filter(a => !existingPaths.has(a.path));
          if (newAgents.length > 0) {
            return { ...prev, agents: [...prev.agents, ...newAgents] };
          }
          return prev;
        });
      }

      // 恢复该文件夹的最后会话
      const lastSessionId = await loadLastSessionId(selectedPath);
      if (lastSessionId) {
        setCurrentSessionId(lastSessionId);
      }

      return true;
    } catch (err) {
      console.error('[App] 打开文件夹失败:', err);
      return false;
    }
  }, [projects]);

  // 打开文件夹对话框
  const handleOpenFolder = useCallback(async () => {
    const selectedPath = await fileService.openFolderDialog();
    if (selectedPath) {
      await openFolderByPath(selectedPath);
    }
  }, [openFolderByPath]);

  // 刷新项目文件树（递增 refreshKey，ExplorerPanel 自行重扫）
  const refreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const refreshFileTree = useCallback(async (_projectPath?: string) => {
    if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current);
    refreshTimerRef.current = setTimeout(() => {
      setProjectRefreshKey(prev => prev + 1);
    }, 300);
  }, []);

  // 新建文件（在指定项目根目录）
  const handleNewFile = useCallback(async (projectPath?: string) => {
    const basePath = projectPath || activeProjectPath;
    if (!basePath) return;
    const name = 'untitled';
    let path = `${basePath}/${name}`;
    let counter = 1;
    while (await fileService.pathExists(path)) {
      path = `${basePath}/${name}-${counter}`;
      counter++;
    }
    await fileService.createFile(path);
    refreshFileTree(basePath);
    handleFileSelect(path);
  }, [activeProjectPath, refreshFileTree, handleFileSelect]);

  // 新建文件夹（在指定项目根目录）
  const handleNewFolder = useCallback(async (projectPath?: string) => {
    const basePath = projectPath || activeProjectPath;
    if (!basePath) return;
    const name = 'new-folder';
    let path = `${basePath}/${name}`;
    let counter = 1;
    while (await fileService.pathExists(path)) {
      path = `${basePath}/${name}-${counter}`;
      counter++;
    }
    await fileService.createDirectory(path);
    refreshFileTree(basePath);
  }, [activeProjectPath, refreshFileTree]);

  // 重命名文件/文件夹
  const handleRename = useCallback(async (oldPath: string, newPath: string) => {
    await fileService.renameItem(oldPath, newPath);
    // 更新已打开的文件路径
    setOpenFiles(prev => prev.map(f =>
      f.path === oldPath ? { ...f, path: newPath, name: newPath.split(/[/\\]/).pop() || f.name } : f
    ));
    if (activeFilePath === oldPath) {
      setActiveFilePath(newPath);
    }
    await refreshFileTree();
  }, [refreshFileTree, activeFilePath]);

  // 删除文件/文件夹
  const handleDelete = useCallback(async (path: string, type: 'file' | 'folder') => {
    if (type === 'folder') {
      await fileService.deleteDirectory(path);
    } else {
      await fileService.deleteFile(path);
    }
    // 关闭已打开的该文件
    setOpenFiles(prev => {
      const remaining = prev.filter(f => !f.path.startsWith(path));
      if (activeFilePath?.startsWith(path) && remaining.length > 0) {
        setActiveFilePath(remaining[remaining.length - 1].path);
      } else if (remaining.length === 0) {
        setActiveFilePath(null);
      }
      return remaining;
    });
    await refreshFileTree();
  }, [refreshFileTree, activeFilePath]);

  // 复制文件/文件夹
  const handleCopy = useCallback(async (sourcePath: string, destPath: string) => {
    await fileService.copyItem(sourcePath, destPath);
    await refreshFileTree();
  }, [refreshFileTree]);

  // 移动文件/文件夹
  const handleMove = useCallback(async (sourcePath: string, destPath: string) => {
    await fileService.moveItem(sourcePath, destPath);
    // 更新已打开的文件路径
    setOpenFiles(prev => prev.map(f =>
      f.path === sourcePath ? { ...f, path: destPath, name: destPath.split(/[/\\]/).pop() || f.name } : f
    ));
    if (activeFilePath === sourcePath) {
      setActiveFilePath(destPath);
    }
    await refreshFileTree();
  }, [refreshFileTree, activeFilePath]);

  // 保存当前文件
  const handleSaveCurrentFile = useCallback(() => {
    if (activeFilePath) {
      handleFileSave(activeFilePath);
    }
  }, [activeFilePath, handleFileSave]);

  // Toast 提示
  // 终端面板状态
  const [terminalVisible, setTerminalVisible] = useState(false);

  const [toast, setToast] = useState<string | null>(null);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const showToast = useCallback((msg: string) => {
    if (toastTimer.current) clearTimeout(toastTimer.current);
    setToast(msg);
    toastTimer.current = setTimeout(() => setToast(null), 5000);
  }, []);

  // 会话操作
  const handleNewSession = useCallback((projectId?: string, _title?: string): string => {
    // 如果当前是临时会话且无消息，提示
    const currentSession = sessions.find(s => s.id === currentSessionId);
    if (currentSessionId && !currentSessionId.startsWith('temp-') && !currentSession) {
      showToast('已是最新对话');
      return '';
    }

    // 记住项目上下文
    if (projectId) {
      setPendingSessionProject(projectId);
    }

    // 只设临时 ID，不加入列表，不持久化。等第一条消息发送时再持久化并显示
    const tempId = `temp-${Date.now()}`;
    setCurrentSessionId(tempId);
    return tempId;
  }, [currentSessionId, sessions, activeProjectPath, pendingSessionProject]);

  const handleDeleteSession = useCallback((id: string) => {
    const remaining = sessions.filter(s => s.id !== id);
    setSessions(remaining);
    deleteConversation(id);
    if (currentSessionId === id) {
      setCurrentSessionId(remaining.length > 0 ? remaining[0].id : undefined);
    }
  }, [currentSessionId, sessions]);

  // 更新会话标题（首次发送消息时触发：持久化会话 + 用首条消息做标题）
  const handleUpdateSessionTitle = useCallback((sessionId: string, title: string) => {
    const wsPath = pendingSessionProject || activeProjectPath || UNLINKED_PROJECT;

    setSessions(prev => {
      const exists = prev.find(s => s.id === sessionId);

      if (!exists) {
        // 会话不在列表中，首次持久化
        saveConversation({ title, timestamp: '刚刚', status: 'active', workspacePath: wsPath }).then(dbId => {
          const realId = dbId.toString();
          // 将以 temp ID 保存的消息重新关联到真实 ID
          reassignMessages(sessionId, dbId);
          setSessions(p => [{ id: realId, title, timestamp: '刚刚', messageCount: 0, workspacePath: wsPath }, ...p]);
          setCurrentSessionId(realId);
        });
        return prev;
      }

      // 已持久化的会话：更新标题
      if (!sessionId.startsWith('temp-')) {
        if (exists.title === '新会话') {
          updateConversation(sessionId, { title });
        }
        return prev.map(s =>
          s.id === sessionId && s.title === '新会话' ? { ...s, title } : s
        );
      }

      // 临时会话：首次持久化，替换临时 ID 为真实 ID
      saveConversation({ title, timestamp: exists.timestamp, status: 'active', workspacePath: exists.workspacePath || wsPath }).then(dbId => {
        const realId = dbId.toString();
        // 将以 temp ID 保存的消息重新关联到真实 ID
        reassignMessages(sessionId, dbId);
        setSessions(p => p.map(s => s.id === sessionId ? { ...s, id: realId, title } : s));
        setCurrentSessionId(realId);
      });
      return prev.map(s =>
        s.id === sessionId ? { ...s, title } : s
      );
    });
    setPendingSessionProject(null);
  }, [activeProjectPath, pendingSessionProject]);

  // 添加项目（打开文件夹对话框）
  const handleAddProject = useCallback(async () => {
    const selectedPath = await fileService.openFolderDialog();
    if (selectedPath) {
      await openFolderByPath(selectedPath);
    }
  }, [openFolderByPath]);

  // 新建空项目
  const handleCreateProject = useCallback(async () => {
    try {
      const homeDir = await import('@tauri-apps/api/path').then(m => m.homeDir());
      const sep = homeDir.includes('\\') ? '\\' : '/';
      const baseDir = `${homeDir}Documents${sep}SolonCode`;

      // 确保基础目录存在
      await fileService.createDirectory(baseDir);

      // 生成不重复的项目名
      let name = '未命名项目';
      let projectPath = `${baseDir}${sep}${name}`;
      let counter = 1;
      while (await fileService.pathExists(projectPath)) {
        name = `未命名项目-${counter}`;
        projectPath = `${baseDir}${sep}${name}`;
        counter++;
      }

      await fileService.createDirectory(projectPath);
      await fileService.initWorkspaceConfig(projectPath);

      // 添加到数据库
      const project = { id: projectPath, name, sortOrder: projects.length, addedAt: new Date().toISOString() };
      await dbAddProject(project);
      setProjects(prev => [...prev, { id: project.id, name: project.name, sortOrder: project.sortOrder }]);

      // 设为活跃项目
      setActiveProjectPath(projectPath);
      setChatWorkspacePath(projectPath);
      saveLastActiveProject(projectPath);
      setActiveActivity('explorer');
    } catch (err) {
      console.error('[App] 创建项目失败:', err);
    }
  }, [projects]);

  // 切换活跃项目
  const handleSetActiveProject = useCallback(async (path: string) => {
    setActiveProjectPath(path);
    setChatWorkspacePath(path);
    saveLastActiveProject(path);
    setOpenFiles([]);
    setActiveFilePath(null);

    await fileService.initWorkspaceConfig(path);
  }, []);

  // 移除项目
  const handleRemoveProject = useCallback(async (projectId: string) => {
    await dbRemoveProject(projectId);
    setProjects(prev => prev.filter(p => p.id !== projectId));
    // 如果移除的是活跃项目，清除编辑器状态
    if (activeProjectPath === projectId) {
      setActiveProjectPath(null);
      setChatWorkspacePath(null);
      setOpenFiles([]);
      setActiveFilePath(null);
      setGitStatus(emptyGitStatus);
    }
    // 该项目的会话移至未关联
    setSessions(prev => prev.map(s => s.workspacePath === projectId ? { ...s, workspacePath: UNLINKED_PROJECT } : s));
    const convs = await getAllConversations();
    for (const c of convs) {
      if (c.workspacePath === projectId) {
        await updateConversation(c.id!, { workspacePath: UNLINKED_PROJECT });
      }
    }
  }, [activeProjectPath]);

  // 渲染侧边栏内容
  const renderSidebarContent = () => {
    if (sidebarCollapsed) return null;

    switch (activeActivity) {
      case 'explorer':
        return (
          <ExplorerPanel
            projects={projects}
            activeProjectPath={activeProjectPath}
            refreshKey={projectRefreshKey}
            onFileSelect={handleFileSelect}
            onOpenFolder={handleOpenFolder}
            onCreateProject={handleCreateProject}
            onRemoveProject={handleRemoveProject}
            onSetActiveProject={handleSetActiveProject}
            onRefreshProject={refreshFileTree}
            onNewFile={handleNewFile}
            onNewFolder={handleNewFolder}
            onRename={handleRename}
            onDelete={handleDelete}
            onCopy={handleCopy}
            onMove={handleMove}
          />
        );
      case 'git':
        return (
          <GitPanel
            status={gitStatus}
            cwd={activeProjectPath || undefined}
            onCommit={async (msg) => {
              if (activeProjectPath) { await gitService.commit(activeProjectPath, msg); refreshGitStatus(); }
            }}
            onStage={async (path) => {
              if (activeProjectPath) { await gitService.add(activeProjectPath, [path]); refreshGitStatus(); }
            }}
            onUnstage={async (path) => {
              if (activeProjectPath) { await gitService.reset(activeProjectPath, [path]); refreshGitStatus(); }
            }}
            onPush={async () => {
              if (activeProjectPath) { await gitService.push(activeProjectPath); refreshGitStatus(); }
            }}
            onPull={async () => {
              if (activeProjectPath) { await gitService.pull(activeProjectPath); refreshGitStatus(); }
            }}
            onDiscard={async (path) => {
              if (activeProjectPath) { await gitService.discard(activeProjectPath, [path]); refreshGitStatus(); }
            }}
            onFileClick={(relPath) => {
              if (activeProjectPath) {
                const fullPath = activeProjectPath.replace(/\\/g, '/') + '/' + relPath;
                handleFileSelect(fullPath);
              }
            }}
          />
        );
      case 'extensions':
        return (
          <ExtensionsPanel
            extensions={mockExtensions}
            onInstall={async (id) => console.log('安装:', id)}
            onUninstall={async (id) => console.log('卸载:', id)}
            onToggle={(id) => console.log('切换:', id)}
          />
        );
      case 'sessions':
        return (
          <SessionsPanel
            projects={projects}
            sessions={sessions}
            currentSessionId={currentSessionId}
            currentProjectId={activeProjectPath}
            onSelectSession={setCurrentSessionId}
            onNewSession={handleNewSession}
            onDeleteSession={handleDeleteSession}
            onAddProject={handleAddProject}
            onRemoveProject={handleRemoveProject}
          />
        );
      case 'skills':
        return (
          <SkillsPanel
            skills={settings.skills}
            onSkillsChange={(skills) => setSettings(prev => ({ ...prev, skills }))}
            onFileSelect={(path) => {
              setPanelState(prev => ({ ...prev, editorVisible: true }));
              handleFileSelect(path);
            }}
          />
        );
      case 'agents':
        return (
          <AgentsPanel
            agents={settings.agents}
            onAgentsChange={(agents) => setSettings(prev => ({ ...prev, agents }))}
            activeAgent={activeAgent}
            onAgentChange={setActiveAgent}
            onFileSelect={(path) => {
              setPanelState(prev => ({ ...prev, editorVisible: true }));
              handleFileSelect(path);
            }}
          />
        );
      default:
        return null;
    }
  };

  // 渲染面板
  const renderPanel = (panel: PanelPosition) => {
    if (panel === 'editor') {
      if (!panelState.editorVisible) return null;
      return (
        <div key="editor" className={`panel-wrapper editor-wrapper${panelState.chatVisible ? '' : ' expand'}`} style={{
          width: panelState.chatVisible ? panelState.editorWidth : undefined,
        }}>
          <EditorPanel
            files={openFiles}
            activeFilePath={activeFilePath}
            onFileSelect={setActiveFilePath}
            onFileClose={handleFileClose}
            onContentChange={handleContentChange}
            onFileSave={handleFileSave}
            theme={settings.theme}
            diffLines={diffLines}
          />
          {panelState.chatVisible && (
          <div
            className="resize-handle vertical"
            onMouseDown={(e) => startResize('editor', e)}
          />
          )}
        </div>
      );
    }

    if (panel === 'chat') {
      if (!panelState.chatVisible) return null;
      // 仅对话面板可见时，居中显示并左右各留 15%
      const onlyChat = !panelState.editorVisible;
      return (
        <div key="chat" className="panel-wrapper chat-wrapper" style={{
          width: onlyChat ? '70%' : panelState.chatWidth,
          flex: onlyChat ? 'none' : '1 1 auto',
          margin: onlyChat ? '0 15%' : undefined,
        }}>
          <ChatView
            currentConversation={currentConversation}
            plugins={plugins}
            workspacePath={activeProjectPath || undefined}
            projectName={workspaceName || undefined}
            theme={currentTheme}
            onUpdateSessionTitle={handleUpdateSessionTitle}
            onNewSession={(title) => handleNewSession(undefined, title)}
            providers={settings.providers}
            onActiveProviderChange={(providerId: string) => {
              setSettings(prev => {
                const updated = { ...prev, activeProviderId: providerId };
                settingsService.save(updated);
                return updated;
              });
            }}
            activeFileName={activeFile?.name}
            activeFilePath={activeFilePath || undefined}
          />
        </div>
      );
    }

    return null;
  };

  return (
    <div className="window-frame">
      <div className="drag-edge drag-top" onMouseDown={startWindowDrag} />
      <div className="drag-edge drag-bottom" onMouseDown={startWindowDrag} />
      <div className="drag-edge drag-left" onMouseDown={startWindowDrag} />
      <div className="drag-edge drag-right" onMouseDown={startWindowDrag} />
    <div className="app-container" ref={containerRef}>
      {/* 顶部标题栏/菜单栏 */}
      <TitleBar
        workspacePath={activeProjectPath || undefined}
        workspaceName={workspaceName}
        onNewFile={() => handleNewFile()}
        onOpenFile={handleOpenFile}
        onOpenFolder={handleOpenFolder}
        onNewProject={handleCreateProject}
        onSave={handleSaveCurrentFile}
        onSaveAll={() => openFiles.forEach(f => handleFileSave(f.path))}
        editorVisible={panelState.editorVisible}
        chatVisible={panelState.chatVisible}
        onToggleEditor={() => togglePanel('editor')}
        onToggleChat={() => togglePanel('chat')}
        onToggleTerminal={() => setTerminalVisible(v => !v)}
        onSwapPanels={swapPanels}
      />

      {/* 主内容区 */}
      <div className="main-area">
        {/* 左侧：活动栏 + 侧边栏 */}
        <ActivityBar
          activeActivity={activeActivity}
          theme={currentTheme}
          onToggleTheme={toggleTheme}
          onActivityChange={(activity) => {
            if (activity === 'settings') {
              setSettingsVisible(true);
              return;
            }
            if (activeActivity === activity) {
              setSidebarCollapsed(!sidebarCollapsed);
            } else {
              setSidebarCollapsed(false);
              setActiveActivity(activity);
            }
          }}
        />

        {/* 侧边栏面板 */}
        <div className={`sidebar-container${sidebarCollapsed ? ' collapsed' : ''}`}
             style={!sidebarCollapsed ? { width: sidebarWidth } : undefined}>
          {!sidebarCollapsed && (
            <SidePanel title="" width={sidebarWidth} minWidth={200} maxWidth={600}>
              {renderSidebarContent()}
            </SidePanel>
          )}
        </div>

        {/* 右侧区域：上面编辑器+对话框，下面终端 */}
        <div className="right-area">
          <div className="panels-container">
            {panelState.panelOrder.map(panel => renderPanel(panel))}
          </div>
          <TerminalPanel visible={terminalVisible} cwd={activeProjectPath || undefined} />
        </div>
      </div>

      {/* 底部状态栏 */}
      <StatusBar
        backendStatus={backendStatus}
        model={settings.model}
        branch={gitStatus.branch}
        ahead={gitStatus.ahead}
        behind={gitStatus.behind}
        warningCount={0}
        errorCount={0}
        cursorLine={activeFile ? activeFile.content.split('\n').length : undefined}
        cursorColumn={1}
        encoding="UTF-8"
        language={activeFile?.language}
        hasUnsavedChanges={openFiles.some(f => f.modified)}
      />

      {/* Toast 提示 */}
      {toast && (
        <div className="toast-message">{toast}</div>
      )}

      {/* 设置弹窗 */}
      <SettingsPanel
        visible={settingsVisible}
        settings={settings}
        onSettingsChange={handleSettingsChange}
        onClose={() => setSettingsVisible(false)}
        backendPort={backendPort}
        workspacePath={activeProjectPath}
      />
    </div>
    </div>
  );
}

export default App;

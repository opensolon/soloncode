import { useState, useCallback, useRef, useEffect, useMemo } from 'react';
import { ActivityBar, type ActivityType } from './components/layout/ActivityBar';
import { TitleBar } from './components/layout/TitleBar';
import { SidePanel } from './components/layout/SidePanel';
import { StatusBar } from './components/layout/StatusBar';
import { ExplorerPanel } from './components/sidebar/ExplorerPanel';
import { GitPanel } from './components/sidebar/GitPanel';
import { ExtensionsPanel } from './components/sidebar/ExtensionsPanel';
import { SessionsPanel, type Session } from './components/sidebar/SessionsPanel';
import { SkillsPanel } from './components/sidebar/SkillsPanel';
import { AgentsPanel } from './components/sidebar/AgentsPanel';
import { getAllConversations, saveConversation, deleteConversation, updateConversation, saveLastFolder, loadLastFolder, saveLastSessionId, loadLastSessionId } from './db';
import { SettingsPanel, type Settings } from './components/sidebar/SettingsPanel';
import { EditorPanel } from './components/editor/EditorPanel';
import { ChatView } from './components/ChatView';
import { TerminalPanel } from './components/terminal/TerminalPanel';
import { fileService, type FileInfo } from './services/fileService';
import { gitService, type GitStatus, type DiffLine } from './services/gitService';
import { settingsService } from './services/settingsService';
import { backendService } from './services/backendService';
import { setBackendPort as setChatBackendPort, setWorkspacePath as setChatWorkspacePath, sendModelConfig } from './components/ChatView';
import { useFileWatcher } from './hooks/useFileWatcher';
import type { Conversation, Plugin } from './types';
import './App.css';

// 文件树节点接口
interface FileTreeNode {
  name: string;
  type: 'folder' | 'file';
  path: string;
  children?: FileTreeNode[];
  gitStatus?: 'modified' | 'added' | 'deleted' | 'untracked';
}

// 将 FileInfo 转换为 FileTreeNode
function convertToFileTree(files: FileInfo[]): FileTreeNode[] {
  return files.map(f => ({
    name: f.name,
    type: f.isDir ? 'folder' as const : 'file' as const,
    path: f.path,
    children: f.children ? convertToFileTree(f.children) : undefined,
  }));
}

// 将 git status 合并到文件树（git paths 是相对路径，file tree 是绝对路径）
function mergeGitStatus(files: FileTreeNode[], gitFiles: { path: string; status: 'modified' | 'added' | 'deleted' | 'untracked' }[], workspacePath: string): FileTreeNode[] {
  // 统一用 / 分隔的相对路径做 key
  const normalize = (p: string) => p.replace(/\\/g, '/');
  const wsPrefix = normalize(workspacePath).replace(/\/$/, '') + '/';
  const map = new Map(gitFiles.map(f => [normalize(f.path), f.status]));
  return files.map(f => {
    const relPath = normalize(f.path).replace(wsPrefix, '');
    return {
      ...f,
      gitStatus: map.get(relPath) || map.get(normalize(f.path)),
      children: f.children ? mergeGitStatus(f.children, gitFiles, workspacePath) : undefined,
    };
  });
}

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

  // 工作区状态
  const [workspacePath, setWorkspacePath] = useState<string | null>(null);
  const [workspaceName, setWorkspaceName] = useState<string>('');
  const [workspaceFiles, setWorkspaceFiles] = useState<FileTreeNode[]>([]);

  // Git 状态
  const [gitStatus, setGitStatus] = useState<GitStatus>(emptyGitStatus);

  // 文件 Diff 行变更缓存
  const [diffLines, setDiffLines] = useState<DiffLine[]>([]);

  // 后端端口（固定值，不因心跳失败清除）
  const backendPortRef = useRef<number>(4808);
  const [backendPort, setBackendPortState] = useState<number | null>(null);

  // 后端连接状态（独立管理，心跳可恢复）
  const [backendConnected, setBackendConnected] = useState(false);

  // 同步后端端口到 ChatView
  useEffect(() => {
    setChatBackendPort(backendPort);
  }, [backendPort]);

  // 心跳：每30s检测后端存活，失败只改状态不清端口，可自动恢复
  // 检测成功时如果 port 未设置，自动补上（处理后端先于桌面版启动的场景）
  useEffect(() => {
    const port = backendPortRef.current;
    let alive = false;
    const check = async () => {
      try {
        const resp = await fetch(`http://localhost:${port}/chat/models`);
        alive = resp.ok;
      } catch {
        alive = false;
      }
      setBackendConnected(alive);
      if (alive) {
        setBackendPortState(prev => prev ?? port);
        setChatBackendPort(port);
      }
    };
    check();
    const timer = setInterval(check, 30000);
    return () => clearInterval(timer);
  }, []);

  // 刷新 Git 状态
  const refreshGitStatus = useCallback(async () => {
    if (workspacePath) {
      const status = await gitService.status(workspacePath);
      setGitStatus(status);
    } else {
      setGitStatus(emptyGitStatus);
    }
  }, [workspacePath]);

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

  // 会话或工作区变化时，保存最后会话 ID
  useEffect(() => {
    if (workspacePath && currentSessionId) {
      saveLastSessionId(workspacePath, currentSessionId);
    }
  }, [workspacePath, currentSessionId]);

  // 从 IndexedDB 加载会话列表
  useEffect(() => {
    getAllConversations().then(convs => {
      const loaded: Session[] = convs.map(c => ({
        id: c.id!.toString(),
        title: c.title,
        timestamp: c.timestamp,
        messageCount: 0,
        isPermanent: c.isPermanent,
      }));
      setSessions(loaded);
    });

    // 启动时恢复上次打开的文件夹
    loadLastFolder().then(async (lastFolder) => {
      if (!lastFolder) return;
      try {
        console.log('[App] 恢复上次工作区:', lastFolder);
        await fileService.initWorkspaceConfig(lastFolder);
        const info = await fileService.getWorkspaceInfo(lastFolder);
        setWorkspacePath(lastFolder);
        setChatWorkspacePath(lastFolder);
        setWorkspaceName(info.name);

        backendService.start(lastFolder).then(async (port) => {
          if (port) {
            backendPortRef.current = port;
            setBackendPortState(port);
            setBackendConnected(true);
            setChatBackendPort(port);

            // 从 CLI 配置读取 baseUrl 和密钥，获取所有可用模型
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
            setBackendConnected(false);
          }
        }).catch(() => { setBackendPortState(null); setBackendConnected(false); });

        const files = await fileService.listDirectoryTree(lastFolder, 10);
        setWorkspaceFiles(convertToFileTree(files));
        setActiveActivity('explorer');

        // 自动发现 skills（项目级 + 第三方）
        const allDiscoveredSkills = [
          ...await settingsService.scanSkillsDir(lastFolder),
          ...await settingsService.scanThirdPartySkills(lastFolder),
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
        const discoveredAgents = await settingsService.scanAgentsDir(lastFolder);
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
        const lastSessionId = await loadLastSessionId(lastFolder);
        if (lastSessionId) {
          setCurrentSessionId(lastSessionId);
          setActiveActivity('sessions');
        }
      } catch (err) {
        console.warn('[App] 恢复工作区失败:', err);
      }
    });
  }, []);

  // 当前活动文件（用于状态栏）
  const activeFile = openFiles.find(f => f.path === activeFilePath);

  // 拖拽调整大小
  const [isResizing, setIsResizing] = useState<string | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  // 文件监听 - 工作区文件变化时自动刷新文件树
  useFileWatcher({
    workspacePath,
    onChange: async (_changedPaths) => {
      if (workspacePath) {
        const files = await fileService.listDirectoryTree(workspacePath, 10);
        setWorkspaceFiles(convertToFileTree(files));
      }
    },
    enabled: !!workspacePath,
  });

  // 配置文件监听 - .soloncode/ 目录变化时自动重载设置
  useFileWatcher({
    workspacePath: workspacePath ? `${workspacePath}/.soloncode` : null,
    onChange: async (_changedPaths) => {
      if (workspacePath) {
        const configUpdate = await settingsService.loadConfigFile(workspacePath);
        if (configUpdate) {
          setSettings(prev => ({ ...prev, ...configUpdate }));
          showToast('配置已重新加载');
        }
      }
    },
    enabled: !!workspacePath,
  });

  // 获取当前活跃文件的 git diff
  useEffect(() => {
    if (!workspacePath || !activeFilePath) {
      setDiffLines([]);
      return;
    }
    const relPath = activeFilePath.replace(workspacePath.replace(/\\/g, '/').replace(/\/$/, '') + '/', '');
    gitService.diffFile(workspacePath, relPath).then(setDiffLines).catch(() => setDiffLines([]));
  }, [workspacePath, activeFilePath, gitStatus]);

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
    if (file && workspacePath) {
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
  }, [openFiles, workspacePath]);

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
  // 通过路径打开工作区（复用逻辑）
  const openFolderByPath = useCallback(async (selectedPath: string) => {
    try {
      // 1. 清理旧工作区（不停止后端进程，由 Rust 侧复用）
      if (workspacePath) {
        setChatBackendPort(null);
        setChatWorkspacePath(null);
        setBackendPortState(null);
        setBackendConnected(false);
        setOpenFiles([]);
        setActiveFilePath(null);
        setGitStatus(emptyGitStatus);
      }

      await fileService.initWorkspaceConfig(selectedPath);
      const info = await fileService.getWorkspaceInfo(selectedPath);

      setWorkspacePath(selectedPath);
      setChatWorkspacePath(selectedPath);
      setWorkspaceName(info.name);

      // 保存最后打开的文件夹
      saveLastFolder(selectedPath);

      // 启动后端
      backendService.start(selectedPath).then(async (port) => {
        if (port) {
          backendPortRef.current = port;
          setBackendPortState(port);
          setBackendConnected(true);
          setChatBackendPort(port);

          // 从 CLI 配置读取 baseUrl 和密钥，获取所有可用模型
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
          setBackendConnected(false);
        }
      }).catch(() => { setBackendPortState(null); setBackendConnected(false); });

      // 加载目录树
      const files = await fileService.listDirectoryTree(selectedPath, 10);
      setWorkspaceFiles(convertToFileTree(files));
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
  }, [workspacePath]);

  // 打开文件夹对话框
  const handleOpenFolder = useCallback(async () => {
    const selectedPath = await fileService.openFolderDialog();
    if (selectedPath) {
      await openFolderByPath(selectedPath);
    }
  }, [openFolderByPath]);

  // 刷新文件树（带防抖，避免短时间内多次全量扫描）
  const refreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const refreshFileTree = useCallback(async () => {
    if (!workspacePath) return;
    if (refreshTimerRef.current) {
      clearTimeout(refreshTimerRef.current);
    }
    refreshTimerRef.current = setTimeout(async () => {
      const files = await fileService.listDirectoryTree(workspacePath, 10);
      setWorkspaceFiles(convertToFileTree(files));
    }, 300);
  }, [workspacePath]);

  // 新建文件（在工作区根目录）
  const handleNewFile = useCallback(async () => {
    if (!workspacePath) return;
    const name = 'untitled';
    let path = `${workspacePath}/${name}`;
    let counter = 1;
    while (await fileService.pathExists(path)) {
      path = `${workspacePath}/${name}-${counter}`;
      counter++;
    }
    await fileService.createFile(path);
    await refreshFileTree();
    handleFileSelect(path);
  }, [workspacePath, refreshFileTree, handleFileSelect]);

  // 新建文件夹（在工作区根目录）
  const handleNewFolder = useCallback(async () => {
    if (!workspacePath) return;
    const name = 'new-folder';
    let path = `${workspacePath}/${name}`;
    let counter = 1;
    while (await fileService.pathExists(path)) {
      path = `${workspacePath}/${name}-${counter}`;
      counter++;
    }
    await fileService.createDirectory(path);
    await refreshFileTree();
  }, [workspacePath, refreshFileTree]);

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
  const handleNewSession = useCallback((title?: string): string => {
    // 点击"+"无标题时，当前已是空会话则提示
    if (!title && currentSessionId && !sessions.find(s => s.id === currentSessionId)) {
      showToast('已是最新对话');
      return '';
    }

    const newSession: Session = {
      id: Date.now().toString(),
      title: title || '新会话',
      timestamp: '刚刚',
      messageCount: 0,
    };

    // 有标题（发送消息触发）才加入列表显示；点击"+"只设ID不显示
    if (title) {
      setSessions(prev => [newSession, ...prev]);
      saveConversation({ id: newSession.id, title: newSession.title, timestamp: newSession.timestamp, status: 'active' });
    }
    setCurrentSessionId(newSession.id);
    return newSession.id;
  }, [currentSessionId, sessions]);

  const handleDeleteSession = useCallback((id: string) => {
    const remaining = sessions.filter(s => s.id !== id);
    setSessions(remaining);
    deleteConversation(id);
    if (currentSessionId === id) {
      setCurrentSessionId(remaining.length > 0 ? remaining[0].id : undefined);
    }
  }, [currentSessionId, sessions]);

  // 更新会话标题（首次发送消息时自动命名，同时将未保存的会话加入列表）
  const handleUpdateSessionTitle = useCallback((sessionId: string, title: string) => {
    setSessions(prev => {
      const exists = prev.find(s => s.id === sessionId);
      if (!exists) {
        // 会话不在列表中，加入并持久化
        saveConversation({ id: sessionId, title, timestamp: '刚刚', status: 'active' });
        return [{ id: sessionId, title, timestamp: '刚刚', messageCount: 0 }, ...prev];
      }
      if (exists.title === '新会话') {
        updateConversation(sessionId, { title });
      }
      return prev.map(s =>
        s.id === sessionId && s.title === '新会话' ? { ...s, title } : s
      );
    });
  }, []);

  // 渲染侧边栏内容
  const renderSidebarContent = () => {
    if (sidebarCollapsed) return null;

    switch (activeActivity) {
      case 'explorer':
        return (
          <ExplorerPanel
            files={mergeGitStatus(workspaceFiles, gitStatus.files, workspacePath || '')}
            workspaceName={workspaceName}
            hasWorkspace={!!workspacePath}
            workspacePath={workspacePath || undefined}
            onFileSelect={handleFileSelect}
            onOpenFolder={handleOpenFolder}
            onRefresh={refreshFileTree}
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
            cwd={workspacePath || undefined}
            onCommit={async (msg) => {
              if (workspacePath) { await gitService.commit(workspacePath, msg); refreshGitStatus(); }
            }}
            onStage={async (path) => {
              if (workspacePath) { await gitService.add(workspacePath, [path]); refreshGitStatus(); }
            }}
            onUnstage={async (path) => {
              if (workspacePath) { await gitService.reset(workspacePath, [path]); refreshGitStatus(); }
            }}
            onPush={async () => {
              if (workspacePath) { await gitService.push(workspacePath); refreshGitStatus(); }
            }}
            onPull={async () => {
              if (workspacePath) { await gitService.pull(workspacePath); refreshGitStatus(); }
            }}
            onDiscard={async (path) => {
              if (workspacePath) { await gitService.discard(workspacePath, [path]); refreshGitStatus(); }
            }}
            onFileClick={(relPath) => {
              // Git 返回相对路径，拼接为完整路径后打开
              if (workspacePath) {
                const fullPath = workspacePath.replace(/\\/g, '/') + '/' + relPath;
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
            sessions={sessions}
            currentSessionId={currentSessionId}
            onSelectSession={setCurrentSessionId}
            onNewSession={handleNewSession}
            onDeleteSession={handleDeleteSession}
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
            workspacePath={workspacePath || undefined}
            onUpdateSessionTitle={handleUpdateSessionTitle}
            onNewSession={handleNewSession}
            providers={settings.providers}
            activeProviderId={settings.activeProviderId}
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
    <div className="app-container" ref={containerRef}>
      {/* 顶部标题栏/菜单栏 */}
      <TitleBar
        workspacePath={workspacePath || undefined}
        workspaceName={workspaceName}
        onNewFile={handleNewFile}
        onOpenFile={handleOpenFile}
        onOpenFolder={handleOpenFolder}
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
          <TerminalPanel visible={terminalVisible} cwd={workspacePath || undefined} />
        </div>
      </div>

      {/* 底部状态栏 */}
      <StatusBar
        connected={backendConnected}
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
        workspacePath={workspacePath}
      />
    </div>
  );
}

export default App;

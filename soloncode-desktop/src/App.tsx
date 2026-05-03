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
import { SettingsPanel, type Settings } from './components/sidebar/SettingsPanel';
import { EditorPanel } from './components/editor/EditorPanel';
import { ChatView } from './components/ChatView';
import { TerminalPanel } from './components/terminal/TerminalPanel';
import { fileService } from './services/fileService';
import { gitService } from './services/gitService';
import { settingsService } from './services/settingsService';
import { setBackendPort as setChatBackendPort, setWorkspacePath as setChatWorkspacePath, sendModelConfig } from './components/ChatView';
import { useFileWatcher } from './hooks/useFileWatcher';
import { startWindowDrag, startWindowResize } from './hooks/useWindowDrag';
import { useBackend } from './hooks/useBackend';
import { useGit } from './hooks/useGit';
import { useFileManager } from './hooks/useFileManager';
import { useSessions } from './hooks/useSessions';
import { useWorkspace } from './hooks/useWorkspace';
import type { Conversation, Plugin, Theme } from './types';
import './App.css';

// 模拟扩展
const mockExtensions = [
  { id: '1', name: 'Markdown 渲染器', description: '增强 Markdown 渲染', version: '1.0.0', installed: true, enabled: true, author: 'SolonCode' },
  { id: '2', name: '代码格式化', description: '自动格式化代码', version: '2.1.0', installed: true, enabled: true, author: 'SolonCode' },
];

const plugins: Plugin[] = [
  { id: 'none', name: '插件暂不支持', icon: 'cube', description: '插件暂不支持', enabled: true, version: '1.0.0' }
];

const defaultSettings: Settings = {
  theme: 'dark', fontSize: 14, language: 'zh-CN',
  tabSize: 2, autoSave: true, formatOnSave: true,
  shell: 'bash', terminalFontSize: 14,
  providers: [], activeProviderId: '', maxSteps: 30,
  mcpServers: [],
  skills: [],
  agents: [],
};

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

  useEffect(() => { settingsService.load().then(s => setSettings(s)); }, []);

  const handleSettingsChange = useCallback((newSettings: Settings) => {
    const prevActive = settings.providers.find(p => p.id === settings.activeProviderId);
    const nextActive = newSettings.providers.find(p => p.id === newSettings.activeProviderId);
    setSettings(newSettings);
    settingsService.save(newSettings);
    if (nextActive && (
      !prevActive ||
      prevActive.apiUrl !== nextActive.apiUrl ||
      prevActive.apiKey !== nextActive.apiKey ||
      prevActive.model !== nextActive.model ||
      prevActive.type !== nextActive.type ||
      settings.activeProviderId !== newSettings.activeProviderId
    )) {
      sendModelConfig({ apiUrl: nextActive.apiUrl, apiKey: nextActive.apiKey, model: nextActive.model, type: nextActive.type });
    }
  }, [settings]);

  // ====== Hooks ======
  const { backendPort, backendPortRef, backendStatus, startBackend } = useBackend();
  const {
    openFiles, activeFilePath, activeFile, setActiveFilePath,
    handleFileSelect, handleFileClose, handleContentChange,
    handleFileSave, handleSaveCurrentFile, clearEditorState, setOpenFiles,
  } = useFileManager(null);

  const {
    sessions, currentSessionId, setCurrentSessionId, currentConversation,
    handleNewSession, handleDeleteSession, handleUpdateSessionTitle,
    restoreLastSession,
  } = useSessions(null);

  const {
    activeProjectPath, projectRefreshKey, projects, workspaceName,
    setActiveProjectPath, refreshFileTree, openFolderByPath,
    handleSetActiveProject, handleRemoveProject, handleCreateProject,
  } = useWorkspace({
    setOpenFiles, setActiveFilePath,
    setActiveActivity,
    setSettings,
    backendPortRef,
    setCurrentSessionId,
    restoreLastSession,
  });

  const { gitStatus, diffLines, refreshGitStatus, setGitStatus } = useGit(activeProjectPath, activeFilePath);

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

  // 文件监听
  useFileWatcher({
    workspacePath: activeProjectPath,
    onChange: async () => { refreshFileTree(); },
    enabled: !!activeProjectPath,
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
    startBackend((updater) => setSettings(updater));
  }, []);

  // 拖拽调整大小
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

  // Toast
  const [terminalVisible, setTerminalVisible] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const showToast = useCallback((msg: string) => {
    if (toastTimer.current) clearTimeout(toastTimer.current);
    setToast(msg);
    toastTimer.current = setTimeout(() => setToast(null), 5000);
  }, []);

  const handleAddProject = useCallback(async () => {
    const selectedPath = await fileService.openFolderDialog();
    if (selectedPath) await openFolderByPath(selectedPath);
  }, [openFolderByPath]);

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

  // 渲染侧边栏内容
  const renderSidebarContent = () => {
    if (sidebarCollapsed) return null;
    switch (activeActivity) {
      case 'explorer':
        return (
          <ExplorerPanel
            projects={projects} activeProjectPath={activeProjectPath} refreshKey={projectRefreshKey}
            onFileSelect={handleFileSelect} onOpenFolder={handleOpenFolder} onCreateProject={handleCreateProject}
            onRemoveProject={handleRemoveProject} onSetActiveProject={handleSetActiveProject}
            onRefreshProject={refreshFileTree} onNewFile={handleNewFile} onNewFolder={handleNewFolder}
            onRename={handleRename} onDelete={handleDelete} onCopy={handleCopy} onMove={handleMove}
          />
        );
      case 'git':
        return (
          <GitPanel
            status={gitStatus} cwd={activeProjectPath || undefined}
            onCommit={async (msg) => { if (activeProjectPath) { await gitService.commit(activeProjectPath, msg); refreshGitStatus(); } }}
            onStage={async (path) => { if (activeProjectPath) { await gitService.add(activeProjectPath, [path]); refreshGitStatus(); } }}
            onUnstage={async (path) => { if (activeProjectPath) { await gitService.reset(activeProjectPath, [path]); refreshGitStatus(); } }}
            onPush={async () => { if (activeProjectPath) { await gitService.push(activeProjectPath); refreshGitStatus(); } }}
            onPull={async () => { if (activeProjectPath) { await gitService.pull(activeProjectPath); refreshGitStatus(); } }}
            onDiscard={async (path) => { if (activeProjectPath) { await gitService.discard(activeProjectPath, [path]); refreshGitStatus(); } }}
            onFileClick={(relPath) => { if (activeProjectPath) handleFileSelect(activeProjectPath.replace(/\\/g, '/') + '/' + relPath); }}
          />
        );
      case 'extensions':
        return <ExtensionsPanel extensions={mockExtensions} onInstall={async (id) => console.log('安装:', id)} onUninstall={async (id) => console.log('卸载:', id)} onToggle={(id) => console.log('切换:', id)} />;
      case 'sessions':
        return (
          <SessionsPanel
            projects={projects} sessions={sessions} currentSessionId={currentSessionId} currentProjectId={activeProjectPath}
            onSelectSession={setCurrentSessionId} onNewSession={handleNewSession} onDeleteSession={handleDeleteSession}
            onAddProject={handleAddProject} onRemoveProject={handleRemoveProject}
          />
        );
      case 'skills':
        return <SkillsPanel skills={settings.skills} onSkillsChange={(skills) => setSettings(prev => ({ ...prev, skills }))} onFileSelect={(path) => { setPanelState(prev => ({ ...prev, editorVisible: true })); handleFileSelect(path); }} />;
      case 'agents':
        return <AgentsPanel agents={settings.agents} onAgentsChange={(agents) => setSettings(prev => ({ ...prev, agents }))} activeAgent={activeAgent} onAgentChange={setActiveAgent} onFileSelect={(path) => { setPanelState(prev => ({ ...prev, editorVisible: true })); handleFileSelect(path); }} />;
      default:
        return null;
    }
  };

  // 渲染面板
  const renderPanel = (panel: PanelPosition) => {
    if (panel === 'editor') {
      if (!panelState.editorVisible) return null;
      return (
        <div key="editor" className={`panel-wrapper editor-wrapper${panelState.chatVisible ? '' : ' expand'}`} style={{ width: panelState.chatVisible ? panelState.editorWidth : undefined }}>
          <EditorPanel files={openFiles} activeFilePath={activeFilePath} onFileSelect={setActiveFilePath} onFileClose={handleFileClose} onContentChange={handleContentChange} onFileSave={handleFileSave} theme={settings.theme} diffLines={diffLines} />
          {panelState.chatVisible && <div className="resize-handle vertical" onMouseDown={(e) => startResize('editor', e)} />}
        </div>
      );
    }
    if (panel === 'chat') {
      if (!panelState.chatVisible) return null;
      const onlyChat = !panelState.editorVisible;
      return (
        <div key="chat" className="panel-wrapper chat-wrapper" style={{ width: onlyChat ? '70%' : panelState.chatWidth, flex: onlyChat ? 'none' : '1 1 auto', margin: onlyChat ? '0 15%' : undefined }}>
          <ChatView
            currentConversation={currentConversation} plugins={plugins} workspacePath={activeProjectPath || undefined} projectName={workspaceName || undefined}
            theme={currentTheme} backendPort={backendPort} onUpdateSessionTitle={handleUpdateSessionTitle} onNewSession={(title) => handleNewSession(undefined, title)}
            providers={settings.providers} onActiveProviderChange={(providerId: string) => { setSettings(prev => { const updated = { ...prev, activeProviderId: providerId }; settingsService.save(updated); return updated; }); }}
            activeFileName={activeFile?.name} activeFilePath={activeFilePath || undefined}
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
        onToggleEditor={() => togglePanel('editor')} onToggleChat={() => togglePanel('chat')}
        onToggleTerminal={() => setTerminalVisible(v => !v)} onSwapPanels={swapPanels}
      />
      <div className="main-area">
        <ActivityBar
          activeActivity={activeActivity} theme={currentTheme} onToggleTheme={toggleTheme}
          onActivityChange={(activity) => {
            if (activity === 'settings') { setSettingsVisible(true); return; }
            if (activeActivity === activity) setSidebarCollapsed(!sidebarCollapsed);
            else { setSidebarCollapsed(false); setActiveActivity(activity); }
          }}
        />
        <div className={`sidebar-container${sidebarCollapsed ? ' collapsed' : ''}`} style={!sidebarCollapsed ? { width: sidebarWidth } : undefined}>
          {!sidebarCollapsed && <SidePanel title="" width={sidebarWidth} minWidth={200} maxWidth={600}>{renderSidebarContent()}</SidePanel>}
        </div>
        <div className="right-area">
          <div className="panels-container">{panelState.panelOrder.map(panel => renderPanel(panel))}</div>
          <TerminalPanel visible={terminalVisible} cwd={activeProjectPath || undefined} />
        </div>
      </div>
      <StatusBar
        backendStatus={backendStatus} model={settings.model} branch={gitStatus.branch}
        ahead={gitStatus.ahead} behind={gitStatus.behind} warningCount={0} errorCount={0}
        cursorLine={activeFile ? activeFile.content.split('\n').length : undefined} cursorColumn={1}
        encoding="UTF-8" language={activeFile?.language} hasUnsavedChanges={openFiles.some(f => f.modified)}
      />
      {toast && <div className="toast-message">{toast}</div>}
      <SettingsPanel visible={settingsVisible} settings={settings} onSettingsChange={handleSettingsChange} onClose={() => setSettingsVisible(false)} backendPort={backendPort} workspacePath={activeProjectPath} />
    </div>
    </div>
  );
}

export default App;

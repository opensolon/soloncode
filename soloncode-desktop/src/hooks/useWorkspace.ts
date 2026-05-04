import { useState, useCallback, useEffect, useMemo, useRef } from 'react';
import {
  getAllProjects, addProject as dbAddProject, removeProject as dbRemoveProject,
  saveLastFolder, saveLastActiveProject, loadLastActiveProject,
  loadLastSessionId,
} from '../db';
import { fileService } from '../services/fileService';
import { settingsService } from '../services/settingsService';
import { setWorkspacePath as setChatWorkspacePath } from '../components/ChatView';
import type { Project } from '../components/sidebar/SessionsPanel';

interface UseWorkspaceDeps {
  setOpenFiles: (files: any[]) => void;
  setActiveFilePath: (path: string | null) => void;
  setActiveActivity: (activity: string) => void;
  setSettings: (updater: any) => void;
  backendPortRef: React.MutableRefObject<number>;
  setCurrentSessionId: (id: string | undefined) => void;
  restoreLastSession: (projectPath: string) => void;
}

export function useWorkspace(deps: UseWorkspaceDeps) {
  const {
    setOpenFiles, setActiveFilePath, setActiveActivity,
    setSettings, backendPortRef,
    setCurrentSessionId, restoreLastSession,
  } = deps;

  const [activeProjectPath, setActiveProjectPath] = useState<string | null>(null);
  const [projectRefreshKey, setProjectRefreshKey] = useState(0);
  const [projects, setProjects] = useState<Project[]>([]);

  const workspaceName = useMemo(() => {
    const p = projects.find(p => p.id === activeProjectPath);
    return p?.name || '';
  }, [projects, activeProjectPath]);

  // 加载项目列表
  useEffect(() => {
    (async () => {
      const dbProjects = await getAllProjects();
      setProjects(dbProjects.map(p => ({
        id: p.id, name: p.name, sortOrder: p.sortOrder,
      })));
    })();
  }, []);

  // 恢复上次活跃项目
  useEffect(() => {
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
        restoreLastSession(lastActive);
      } catch (err) {
        console.warn('[App] 恢复活跃项目失败:', err);
      }
    });
  }, []);

  // 刷新文件树
  const refreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const refreshFileTreeLocal = useCallback(async (_projectPath?: string) => {
    if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current);
    refreshTimerRef.current = setTimeout(() => {
      setProjectRefreshKey(prev => prev + 1);
    }, 300);
  }, []);

  const openFolderByPath = useCallback(async (selectedPath: string) => {
    try {
      await fileService.initWorkspaceConfig(selectedPath);
      const info = await fileService.getWorkspaceInfo(selectedPath);

      if (!projects.find(p => p.id === selectedPath)) {
        const project = { id: selectedPath, name: info.name, sortOrder: projects.length, addedAt: new Date().toISOString() };
        await dbAddProject(project);
        setProjects(prev => [...prev, { id: project.id, name: project.name, sortOrder: project.sortOrder }]);
      }

      setActiveProjectPath(selectedPath);
      setChatWorkspacePath(selectedPath);
      saveLastFolder(selectedPath);
      saveLastActiveProject(selectedPath);

      setOpenFiles([]);
      setActiveFilePath(null);

      if (backendPortRef.current) {
        setChatWorkspacePath(selectedPath);
      }

      setActiveActivity('explorer');

      // 自动发现 skills
      const allDiscoveredSkills = [
        ...await settingsService.scanSkillsDir(selectedPath),
        ...await settingsService.scanThirdPartySkills(selectedPath),
      ];
      if (allDiscoveredSkills.length > 0) {
        setSettings((prev: any) => {
          const existingPaths = new Set(prev.skills.map((s: any) => s.path));
          const newSkills = allDiscoveredSkills.filter((s: any) => !existingPaths.has(s.path));
          if (newSkills.length > 0) {
            return { ...prev, skills: [...prev.skills, ...newSkills] };
          }
          return prev;
        });
      }

      // 自动发现 agents
      const discoveredAgents = await settingsService.scanAgentsDir(selectedPath);
      if (discoveredAgents.length > 0) {
        setSettings((prev: any) => {
          const existingPaths = new Set(prev.agents.map((a: any) => a.path));
          const newAgents = discoveredAgents.filter((a: any) => !existingPaths.has(a.path));
          if (newAgents.length > 0) {
            return { ...prev, agents: [...prev.agents, ...newAgents] };
          }
          return prev;
        });
      }

      const lastSessionId = await loadLastSessionId(selectedPath);
      if (lastSessionId) {
        setCurrentSessionId(lastSessionId);
      }

      return true;
    } catch (err) {
      console.error('[App] 打开文件夹失败:', err);
      return false;
    }
  }, [projects, backendPortRef, setOpenFiles, setActiveFilePath, setActiveActivity, setSettings, setCurrentSessionId]);

  const handleSetActiveProject = useCallback(async (path: string) => {
    setActiveProjectPath(path);
    setChatWorkspacePath(path);
    saveLastActiveProject(path);
    setOpenFiles([]);
    setActiveFilePath(null);
    await fileService.initWorkspaceConfig(path);
  }, [setOpenFiles, setActiveFilePath]);

  const handleRemoveProject = useCallback(async (projectId: string) => {
    await dbRemoveProject(projectId);
    setProjects(prev => prev.filter(p => p.id !== projectId));
    if (activeProjectPath === projectId) {
      setActiveProjectPath(null);
      setChatWorkspacePath(null);
      setOpenFiles([]);
      setActiveFilePath(null);
    }
  }, [activeProjectPath, setOpenFiles, setActiveFilePath]);

  const handleCreateProject = useCallback(async () => {
    try {
      const homeDir = await import('@tauri-apps/api/path').then(m => m.homeDir());
      const sep = homeDir.includes('\\') ? '\\' : '/';
      const baseDir = `${homeDir}Documents${sep}SolonCode`;

      await fileService.createDirectory(baseDir);

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

      const project = { id: projectPath, name, sortOrder: projects.length, addedAt: new Date().toISOString() };
      await dbAddProject(project);
      setProjects(prev => [...prev, { id: project.id, name: project.name, sortOrder: project.sortOrder }]);

      setActiveProjectPath(projectPath);
      setChatWorkspacePath(projectPath);
      saveLastActiveProject(projectPath);
      setActiveActivity('explorer');
    } catch (err) {
      console.error('[App] 创建项目失败:', err);
    }
  }, [projects, setActiveActivity]);

  return {
    activeProjectPath,
    projectRefreshKey,
    projects,
    workspaceName,
    setActiveProjectPath,
    refreshFileTree: refreshFileTreeLocal,
    openFolderByPath,
    handleSetActiveProject,
    handleRemoveProject,
    handleCreateProject,
  };
}

import { useState, useCallback, useEffect, useMemo, useRef } from 'react';
import {
  getAllProjects, addProject as dbAddProject, removeProject as dbRemoveProject,
  renameProject as dbRenameProject,
  updateProjectOrder,
  saveLastFolder, saveLastActiveProject, loadLastActiveProject,
  loadLastSessionId,
} from '../db';
import { fileService } from '../services/fileService';
import { settingsService } from '../services/settingsService';
import { setWorkspacePath as setChatWorkspacePath } from '../components/ChatView';
import type { Project } from '../components/sidebar/SessionsPanel';

interface UseWorkspaceDeps {
  setOpenFiles: (files: any[]) => void;
  setActiveFilePath: React.Dispatch<React.SetStateAction<string | null>>;
  setActiveActivity: (activity: string) => void;
  setSettings: (updater: any) => void;
  backendPortRef: React.MutableRefObject<number>;
  setCurrentSessionId: (id: string | undefined) => void;
  restoreLastSession: (projectPath: string) => void;
  onProjectPathChanged: (oldPath: string, newPath: string) => void;
}

const PROJECT_NAME_PATTERN = /^[\p{L}\p{N} _().-]+$/u;

function validateProjectName(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) throw new Error('项目名称不能为空');
  if (Array.from(trimmed).length > 64) throw new Error('项目名称不能超过 64 个字符');
  if (trimmed === '.' || trimmed === '..' || trimmed.endsWith('.') || trimmed.endsWith(' ')) {
    throw new Error('项目名称格式无效');
  }
  if (!PROJECT_NAME_PATTERN.test(trimmed)) {
    throw new Error('项目名称只能包含文字、数字、空格、点、横线、下划线和括号');
  }
  const stem = trimmed.split('.')[0].toUpperCase();
  if (/^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$/.test(stem)) {
    throw new Error('项目名称是系统保留名称');
  }
  return trimmed;
}

function remapWorkspacePath(path: string, oldRoot: string, newRoot: string): string {
  const normalizedPath = path.replace(/\\/g, '/');
  const normalizedOld = oldRoot.replace(/\\/g, '/').replace(/\/+$/, '');
  const normalizedPathLower = normalizedPath.toLowerCase();
  const normalizedOldLower = normalizedOld.toLowerCase();
  if (normalizedPathLower !== normalizedOldLower && !normalizedPathLower.startsWith(`${normalizedOldLower}/`)) {
    return path;
  }
  const separator = newRoot.includes('\\') ? '\\' : '/';
  const suffix = normalizedPath.slice(normalizedOld.length).replace(/\//g, separator);
  return `${newRoot.replace(/[\\/]+$/, '')}${suffix}`;
}

export function useWorkspace(deps: UseWorkspaceDeps) {
  const {
    setOpenFiles, setActiveFilePath, setActiveActivity,
    setSettings, backendPortRef,
    setCurrentSessionId, restoreLastSession, onProjectPathChanged,
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
      const allDiscoveredSkills = await settingsService.scanSkillsDir(selectedPath);
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

  const handlePinProject = useCallback(async (projectId: string) => {
    const sortedProjects = [...projects].sort((a, b) => a.sortOrder - b.sortOrder);
    const target = sortedProjects.find(project => project.id === projectId);
    if (!target || sortedProjects[0]?.id === projectId) return;

    const nextProjects = [
      target,
      ...sortedProjects.filter(project => project.id !== projectId),
    ].map((project, sortOrder) => ({ ...project, sortOrder }));

    try {
      await updateProjectOrder(nextProjects.map(project => project.id));
      setProjects(nextProjects);
    } catch (err) {
      console.error('[App] 置顶项目失败:', err);
    }
  }, [projects]);

  const handleRenameProject = useCallback(async (projectId: string, name: string) => {
    const trimmedName = validateProjectName(name);
    const project = projects.find(item => item.id === projectId);
    if (!project) throw new Error('项目不存在');
    const currentDirectoryName = projectId.replace(/[\\/]+$/, '').split(/[\\/]/).pop() || '';
    if (project.name === trimmedName && currentDirectoryName === trimmedName) return;
    if (currentDirectoryName === trimmedName) {
      await dbRenameProject(projectId, projectId, trimmedName);
      setProjects(prev => prev.map(item =>
        item.id === projectId ? { ...item, name: trimmedName } : item
      ));
      return;
    }

    let newProjectPath: string | null = null;
    try {
      newProjectPath = await fileService.renameProjectDirectory(projectId, trimmedName);
      try {
        await dbRenameProject(projectId, newProjectPath, trimmedName);
      } catch (dbError) {
        await fileService.renameItem(newProjectPath, projectId).catch(rollbackError => {
          console.error('[App] 回滚项目目录失败:', rollbackError);
        });
        throw dbError;
      }

      setProjects(prev => prev.map(project =>
        project.id === projectId ? { ...project, id: newProjectPath!, name: trimmedName } : project
      ));
      setSettings((prev: any) => ({
        ...prev,
        skills: (prev.skills || []).map((skill: any) => ({
          ...skill,
          path: remapWorkspacePath(skill.path, projectId, newProjectPath!),
        })),
        agents: (prev.agents || []).map((agent: any) => ({
          ...agent,
          path: remapWorkspacePath(agent.path, projectId, newProjectPath!),
        })),
        mounts: (prev.mounts || []).map((mount: any) => ({
          ...mount,
          path: remapWorkspacePath(mount.path, projectId, newProjectPath!),
        })),
      }));
      setOpenFiles((prev: any[]) => prev.map(file => ({
        ...file,
        path: remapWorkspacePath(file.path, projectId, newProjectPath!),
      })));
      setActiveFilePath(current => current ? remapWorkspacePath(current, projectId, newProjectPath!) : current);
      onProjectPathChanged(projectId, newProjectPath);

      if (activeProjectPath === projectId) {
        setActiveProjectPath(newProjectPath);
        setChatWorkspacePath(newProjectPath);
      }
      refreshFileTreeLocal(newProjectPath);
    } catch (err) {
      console.error('[App] 重命名项目失败:', err);
      throw err;
    }
  }, [activeProjectPath, onProjectPathChanged, projects, refreshFileTreeLocal, setActiveFilePath, setOpenFiles]);

  const handleCreateProject = useCallback(async () => {
    try {
      const homeDir = await import('@tauri-apps/api/path').then(m => m.homeDir());
      const sep = homeDir.includes('\\') ? '\\' : '/';
      const normalizedHome = homeDir.endsWith('\\') || homeDir.endsWith('/')
        ? homeDir
        : `${homeDir}${sep}`;
      const baseDir = `${normalizedHome}Documents${sep}SolonCode`;

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
      saveLastFolder(projectPath);
      setOpenFiles([]);
      setActiveFilePath(null);
      setActiveActivity('explorer');
      refreshFileTreeLocal(projectPath);
    } catch (err) {
      console.error('[App] 创建项目失败:', err);
    }
  }, [projects, setOpenFiles, setActiveFilePath, setActiveActivity, refreshFileTreeLocal]);

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
    handlePinProject,
    handleRenameProject,
    handleCreateProject,
  };
}

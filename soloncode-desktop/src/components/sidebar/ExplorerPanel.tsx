/**
 * 多项目管理面板 - 支持多项目、懒加载、空项目
 * @author bai
 */
import { useState, useCallback, useEffect, useRef } from 'react';
import { Icon, getFileIconName } from '../common/Icon';
import { ContextMenu } from '../common/ContextMenu';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { DropdownMenu, type MenuItem } from '../common/DropdownMenu';
import { fileService } from '../../services/fileService';
import { revealItemInDir } from '@tauri-apps/plugin-opener';
import { copyTextToClipboard } from '../../utils/clipboard';
import './ExplorerPanel.css';

interface FileNode {
  name: string;
  type: 'file' | 'folder';
  children?: FileNode[];
  path: string;
  gitStatus?: 'modified' | 'added' | 'deleted' | 'untracked';
}

interface Project {
  id: string;
  name: string;
  sortOrder: number;
}

const INITIAL_TREE_LEVELS = 3;

interface ExplorerPanelProps {
  projects: Project[];
  activeProjectPath: string | null;
  refreshKey: number;
  onFileSelect: (path: string) => void;
  onOpenFolder: () => void;
  onCreateProject: () => void;
  onRemoveProject: (id: string) => Promise<void>;
  onRenameProject: (id: string, name: string) => Promise<void>;
  onSetActiveProject: (path: string) => void;
  onRefreshProject: (projectPath: string) => void;
  onNewFile: (projectPath: string) => void;
  onNewFolder: (projectPath: string) => Promise<void>;
  onRename: (oldPath: string, newPath: string) => Promise<void>;
  onDelete: (path: string, type: 'file' | 'folder') => Promise<void>;
  onCopy: (sourcePath: string, destPath: string) => Promise<void>;
  onMove: (sourcePath: string, destPath: string) => Promise<void>;
}

function getParentDir(filePath: string): string {
  const sep = filePath.includes('\\') ? '\\' : '/';
  const parts = filePath.split(sep);
  parts.pop();
  return parts.join(sep);
}

function getExt(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot >= 0 ? name.slice(dot) : '';
}

function getBaseName(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot >= 0 ? name.slice(0, dot) : name;
}

function getProjectNameError(value: string): string {
  const name = value.trim();
  if (!name) return '项目名称不能为空';
  if (Array.from(name).length > 64) return '项目名称不能超过 64 个字符';
  if (name === '.' || name === '..' || name.endsWith('.') || name.endsWith(' ')) return '项目名称格式无效';
  if (!/^[\p{L}\p{N} _().-]+$/u.test(name)) return '名称包含不支持的字符';
  const stem = name.split('.')[0].toUpperCase();
  if (/^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$/.test(stem)) return '该名称是系统保留名称';
  return '';
}

export function ExplorerPanel({
  projects,
  activeProjectPath,
  refreshKey,
  onFileSelect,
  onOpenFolder,
  onCreateProject,
  onRemoveProject,
  onRenameProject,
  onSetActiveProject,
  onRefreshProject,
  onNewFile,
  onNewFolder,
  onRename,
  onDelete,
  onCopy,
  onMove,
}: ExplorerPanelProps) {
  // 每个项目的文件树缓存
  const [projectTrees, setProjectTrees] = useState<Map<string, FileNode[]>>(new Map());
  // 展开的项目集合
  const [expandedProjects, setExpandedProjects] = useState<Set<string>>(new Set());
  // 正在加载的项目集合
  const [loadingProjects, setLoadingProjects] = useState<Set<string>>(new Set());
  // 文件夹展开状态
  const [expandedFolders, setExpandedFolders] = useState<Set<string>>(new Set());
  // 超出初始深度后，按需加载中的文件夹
  const [loadingFolders, setLoadingFolders] = useState<Set<string>>(new Set());

  // 右键菜单
  const [contextMenu, setContextMenu] = useState<{
    x: number;
    y: number;
    node: FileNode | null;
    projectPath: string | null;
  } | null>(null);

  // 剪贴板
  const [clipboard, setClipboard] = useState<{
    path: string;
    operation: 'copy' | 'cut';
    name: string;
    type: 'file' | 'folder';
  } | null>(null);

  // 内联重命名
  const [renamingPath, setRenamingPath] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [isNewFile, setIsNewFile] = useState(false);

  // 确认对话框
  const [confirmDialog, setConfirmDialog] = useState<{
    path: string;
    type: 'file' | 'folder';
  } | null>(null);

  // 项目头部右键菜单
  const [projectContextMenu, setProjectContextMenu] = useState<{
    x: number;
    y: number;
    projectPath: string;
  } | null>(null);
  const [renameProjectTarget, setRenameProjectTarget] = useState<Project | null>(null);
  const [renameProjectValue, setRenameProjectValue] = useState('');
  const [deleteProjectTarget, setDeleteProjectTarget] = useState<Project | null>(null);
  const [projectActionPending, setProjectActionPending] = useState(false);
  const [projectActionMessage, setProjectActionMessage] = useState<{ text: string; error?: boolean } | null>(null);

  const prevRefreshKey = useRef(refreshKey);

  // refreshKey 变化时重扫活跃项目
  useEffect(() => {
    if (refreshKey !== prevRefreshKey.current && activeProjectPath && expandedProjects.has(activeProjectPath)) {
      loadProjectTree(activeProjectPath);
    }
    prevRefreshKey.current = refreshKey;
  }, [refreshKey, activeProjectPath]);

  // 懒加载项目文件树
  const loadProjectTree = useCallback(async (projectPath: string) => {
    setLoadingProjects(prev => new Set(prev).add(projectPath));
    try {
      // 后端从 0 开始计算深度，减 1 后对应用户看到的三层文件。
      const files = await fileService.listDirectoryTree(projectPath, INITIAL_TREE_LEVELS - 1);
      const tree: FileNode[] = files.map(f => ({
        name: f.name,
        type: f.isDir ? 'folder' as const : 'file' as const,
        path: f.path,
        children: f.children ? convertChildren(f.children) : undefined,
      }));
      setProjectTrees(prev => new Map(prev).set(projectPath, tree));
    } catch (err) {
      console.error('[ExplorerPanel] 加载项目树失败:', err);
      setProjectTrees(prev => new Map(prev).set(projectPath, []));
    } finally {
      setLoadingProjects(prev => {
        const next = new Set(prev);
        next.delete(projectPath);
        return next;
      });
    }
  }, []);

  useEffect(() => {
    if (!activeProjectPath || !projects.some(project => project.id === activeProjectPath)) return;
    setExpandedProjects(prev => {
      if (prev.has(activeProjectPath)) return prev;
      const next = new Set(prev);
      next.add(activeProjectPath);
      return next;
    });
    if (!projectTrees.has(activeProjectPath) && !loadingProjects.has(activeProjectPath)) {
      loadProjectTree(activeProjectPath);
    }
  }, [activeProjectPath, projects, projectTrees, loadingProjects, loadProjectTree]);

  function convertChildren(children: { name: string; isDir: boolean; path: string; children?: any[] }[]): FileNode[] {
    return children.map(f => ({
      name: f.name,
      type: f.isDir ? 'folder' as const : 'file' as const,
      path: f.path,
      children: f.children ? convertChildren(f.children) : undefined,
    }));
  }

  // 切换项目展开/折叠
  const toggleProject = useCallback(async (projectPath: string) => {
    const next = new Set(expandedProjects);
    if (next.has(projectPath)) {
      next.delete(projectPath);
      setExpandedProjects(next);
      return;
    }
    next.add(projectPath);
    setExpandedProjects(next);

    if (!projectTrees.has(projectPath) && !loadingProjects.has(projectPath)) {
      await loadProjectTree(projectPath);
    }

    onSetActiveProject(projectPath);
  }, [expandedProjects, projectTrees, loadingProjects, loadProjectTree, onSetActiveProject]);

  // 刷新单个项目
  const handleRefreshProject = useCallback(async (projectPath: string, e?: React.MouseEvent) => {
    e?.stopPropagation();
    await loadProjectTree(projectPath);
    onRefreshProject(projectPath);
  }, [loadProjectTree, onRefreshProject]);

  // 关闭项目
  const handleRemoveProject = useCallback(async (projectPath: string, e?: React.MouseEvent) => {
    e?.stopPropagation();
    await onRemoveProject(projectPath);
    setProjectTrees(prev => {
      const next = new Map(prev);
      next.delete(projectPath);
      return next;
    });
    setExpandedProjects(prev => {
      const next = new Set(prev);
      next.delete(projectPath);
      return next;
    });
  }, [onRemoveProject]);

  // 在系统资源管理器中打开
  const handleOpenInExplorer = useCallback(async (projectPath: string, e?: React.MouseEvent) => {
    e?.stopPropagation();
    try {
      await revealItemInDir(projectPath);
    } catch (err) {
      console.error('[ExplorerPanel] 打开资源管理器失败:', err);
    }
  }, []);

  const replaceFolderChildren = useCallback((
    nodes: FileNode[],
    folderPath: string,
    children: FileNode[],
  ): FileNode[] => nodes.map(node => {
    if (node.path === folderPath) return { ...node, children };
    if (!node.children) return node;
    return { ...node, children: replaceFolderChildren(node.children, folderPath, children) };
  }), []);

  const toggleFolder = useCallback(async (node: FileNode, projectPath: string) => {
    const isExpanded = expandedFolders.has(node.path);
    setExpandedFolders(prev => {
      const next = new Set(prev);
      if (isExpanded) next.delete(node.path);
      else next.add(node.path);
      return next;
    });

    if (isExpanded || node.children !== undefined || loadingFolders.has(node.path)) return;

    setLoadingFolders(prev => new Set(prev).add(node.path));
    try {
      const files = await fileService.listDirectoryTree(node.path, 0);
      const children = convertChildren(files);
      setProjectTrees(prev => {
        const projectTree = prev.get(projectPath);
        if (!projectTree) return prev;
        const next = new Map(prev);
        next.set(projectPath, replaceFolderChildren(projectTree, node.path, children));
        return next;
      });
    } catch (err) {
      console.error('[ExplorerPanel] 加载文件夹失败:', err);
    } finally {
      setLoadingFolders(prev => {
        const next = new Set(prev);
        next.delete(node.path);
        return next;
      });
    }
  }, [expandedFolders, loadingFolders, replaceFolderChildren]);

  // ==================== 右键菜单 ====================

  const handleContextMenu = useCallback((e: React.MouseEvent, node: FileNode | null, projectPath: string) => {
    e.preventDefault();
    e.stopPropagation();
    setContextMenu({ x: e.clientX, y: e.clientY, node, projectPath });
  }, []);

  const handleProjectContextMenu = useCallback((e: React.MouseEvent, projectPath: string) => {
    e.preventDefault();
    e.stopPropagation();
    setProjectContextMenu({ x: e.clientX, y: e.clientY, projectPath });
  }, []);

  const buildMenuItems = useCallback((): MenuItem[] => {
    const node = contextMenu?.node;
    const pasteDisabled = !clipboard;

    if (!node) {
      return [
        { id: 'new-file', label: '新建文件' },
        { id: 'new-folder', label: '新建文件夹' },
        { id: 'divider-paste', label: '', divider: true },
        { id: 'paste', label: '粘贴', shortcut: 'Ctrl+V', disabled: pasteDisabled },
      ];
    }

    const items: MenuItem[] = [
      { id: 'rename', label: '重命名', shortcut: 'F2' },
      { id: 'delete', label: '删除', shortcut: 'Del' },
      { id: 'divider-1', label: '', divider: true },
      { id: 'copy', label: '复制', shortcut: 'Ctrl+C' },
      { id: 'cut', label: '剪切', shortcut: 'Ctrl+X' },
      { id: 'divider-2', label: '', divider: true },
      { id: 'paste', label: '粘贴', shortcut: 'Ctrl+V', disabled: pasteDisabled },
    ];

    if (node.type === 'folder') {
      return [
        { id: 'new-file', label: '新建文件' },
        { id: 'new-folder', label: '新建文件夹' },
        { id: 'divider-0', label: '', divider: true },
        ...items,
      ];
    }

    return items;
  }, [contextMenu, clipboard]);

  const performPaste = useCallback(async (
    clip: { path: string; operation: 'copy' | 'cut'; name: string; type: 'file' | 'folder' },
    targetDir: string,
    projectPath: string,
  ) => {
    if (targetDir.startsWith(clip.path)) return;

    let destPath = `${targetDir}/${clip.name}`;

    if (await fileService.pathExists(destPath)) {
      const ext = getExt(clip.name);
      const base = getBaseName(clip.name);
      let counter = 1;
      while (await fileService.pathExists(`${targetDir}/${base}-${counter}${ext}`)) {
        counter++;
      }
      destPath = `${targetDir}/${base}-${counter}${ext}`;
    }

    if (clip.operation === 'copy') {
      await onCopy?.(clip.path, destPath);
    } else {
      await onMove?.(clip.path, destPath);
      setClipboard(null);
    }
    await loadProjectTree(projectPath);
  }, [onCopy, onMove, loadProjectTree]);

  const handleContextAction = useCallback(async (itemId: string) => {
    const node = contextMenu?.node;
    const projectPath = contextMenu?.projectPath || '';
    setContextMenu(null);

    switch (itemId) {
      case 'rename':
        if (node) {
          setRenamingPath(node.path);
          setRenameValue(node.name);
        }
        break;

      case 'delete':
        if (node) {
          if (node.type === 'folder') {
            setConfirmDialog({ path: node.path, type: node.type });
          } else {
            await onDelete?.(node.path, node.type);
            await loadProjectTree(projectPath);
          }
        }
        break;

      case 'copy':
        if (node) setClipboard({ path: node.path, operation: 'copy', name: node.name, type: node.type });
        break;

      case 'cut':
        if (node) setClipboard({ path: node.path, operation: 'cut', name: node.name, type: node.type });
        break;

      case 'paste': {
        if (!clipboard || !projectPath) break;
        let targetDir: string;
        if (node) {
          targetDir = node.type === 'folder' ? node.path : getParentDir(node.path);
        } else {
          targetDir = projectPath;
        }
        if (targetDir) await performPaste(clipboard, targetDir, projectPath);
        break;
      }

      case 'new-file': {
        if (!projectPath) break;
        const parentDir = node && node.type === 'folder' ? node.path : projectPath;
        const name = 'untitled';
        let path = `${parentDir}/${name}`;
        let counter = 1;
        while (await fileService.pathExists(path)) {
          path = `${parentDir}/${name}-${counter}`;
          counter++;
        }
        await fileService.createFile(path);
        await loadProjectTree(projectPath);
        setRenamingPath(path);
        setRenameValue(name);
        setIsNewFile(true);
        break;
      }

      case 'new-folder': {
        if (!projectPath) break;
        const parentDir = node && node.type === 'folder' ? node.path : projectPath;
        const name = 'new-folder';
        let path = `${parentDir}/${name}`;
        let counter = 1;
        while (await fileService.pathExists(path)) {
          path = `${parentDir}/${name}-${counter}`;
          counter++;
        }
        await fileService.createDirectory(path);
        await loadProjectTree(projectPath);
        break;
      }

    }
  }, [contextMenu, clipboard, performPaste, onDelete, loadProjectTree]);

  const handleProjectContextAction = useCallback(async (itemId: string) => {
    const path = projectContextMenu?.projectPath;
    setProjectContextMenu(null);
    if (!path) return;

    switch (itemId) {
      case 'open-in-explorer':
        await handleOpenInExplorer(path);
        break;
      case 'rename': {
        const project = projects.find(item => item.id === path);
        if (project) {
          setRenameProjectTarget(project);
          setRenameProjectValue(project.name);
        }
        break;
      }
      case 'copy':
        try {
          await copyTextToClipboard(path);
          setProjectActionMessage({ text: '项目路径已复制' });
        } catch (error) {
          console.error('[ExplorerPanel] 复制项目路径失败:', error);
          setProjectActionMessage({ text: '复制项目路径失败', error: true });
        }
        break;
      case 'delete': {
        const project = projects.find(item => item.id === path);
        if (project) setDeleteProjectTarget(project);
        break;
      }
      case 'new-file':
        await onNewFile(path);
        break;
      case 'new-folder':
        await onNewFolder(path);
        break;
    }
  }, [projectContextMenu, handleOpenInExplorer, onNewFile, onNewFolder, projects]);

  const handleRenameProjectConfirm = useCallback(async () => {
    if (!renameProjectTarget || getProjectNameError(renameProjectValue) || projectActionPending) return;
    setProjectActionPending(true);
    try {
      await onRenameProject(renameProjectTarget.id, renameProjectValue.trim());
      setRenameProjectTarget(null);
      setRenameProjectValue('');
      setProjectActionMessage({ text: '项目已重命名' });
    } catch (error) {
      console.error('[ExplorerPanel] 重命名项目失败:', error);
      setProjectActionMessage({ text: error instanceof Error ? error.message : '重命名项目失败', error: true });
    } finally {
      setProjectActionPending(false);
    }
  }, [onRenameProject, projectActionPending, renameProjectTarget, renameProjectValue]);

  const handleNewProjectAction = useCallback((itemId: string) => {
    if (itemId === 'new-empty-project') {
      onCreateProject();
    } else if (itemId === 'use-existing-project') {
      onOpenFolder();
    }
  }, [onCreateProject, onOpenFolder]);

  const handleConfirmDelete = useCallback(async () => {
    if (confirmDialog) {
      await onDelete?.(confirmDialog.path, confirmDialog.type);
      setConfirmDialog(null);
      // 找到该项目并刷新树
      const projectPath = Array.from(expandedProjects).find(p => confirmDialog.path.startsWith(p));
      if (projectPath) await loadProjectTree(projectPath);
    }
  }, [confirmDialog, onDelete, expandedProjects, loadProjectTree]);

  const handleRenameConfirm = useCallback(async (node: FileNode) => {
    const trimmed = renameValue.trim();

    if (isNewFile) {
      if (!trimmed) {
        await fileService.deleteFile(node.path);
        const projectPath = Array.from(expandedProjects).find(p => node.path.startsWith(p));
        if (projectPath) await loadProjectTree(projectPath);
      } else if (trimmed !== node.name) {
        const parentDir = getParentDir(node.path);
        const sep = node.path.includes('\\') ? '\\' : '/';
        const newPath = `${parentDir}${sep}${trimmed}`;
        await onRename?.(node.path, newPath);
        onFileSelect(newPath);
      } else {
        onFileSelect(node.path);
      }
      setRenamingPath(null);
      setIsNewFile(false);
      return;
    }

    if (!trimmed || trimmed === node.name) {
      setRenamingPath(null);
      return;
    }
    const parentDir = getParentDir(node.path);
    const sep = node.path.includes('\\') ? '\\' : '/';
    const newPath = `${parentDir}${sep}${trimmed}`;
    await onRename?.(node.path, newPath);
    setRenamingPath(null);
  }, [renameValue, isNewFile, onRename, onFileSelect, expandedProjects, loadProjectTree]);

  // ==================== 渲染 ====================

  function renderFileNode(node: FileNode, projectPath: string, depth: number = 0) {
    // 项目根目录本身占一层，子目录和文件从下一层开始缩进。
    const indent = (depth + 1) * 16;
    const isExpanded = expandedFolders.has(node.path);
    const isLoading = loadingFolders.has(node.path);
    const isRenaming = renamingPath === node.path;
    const isCut = clipboard?.operation === 'cut' && clipboard.path === node.path;

    return (
      <div key={node.path}>
        <div
          className={`file-node ${node.type}${isExpanded ? ' expanded' : ''}${isCut ? ' cut-item' : ''}${node.gitStatus ? ` git-${node.gitStatus}` : ''}`}
          style={{ paddingLeft: `${indent + 8}px` }}
          onClick={() => {
            if (isRenaming) return;
            if (node.type === 'folder') {
              toggleFolder(node, projectPath);
            } else {
              onFileSelect(node.path);
            }
          }}
          onContextMenu={(e) => {
            const projectPath = Array.from(expandedProjects).find(p => node.path.startsWith(p)) || '';
            handleContextMenu(e, node, projectPath);
          }}
        >
          {node.type === 'folder' ? (
            <>
              <span className="chevron-icon">
                <Icon
                  name={isLoading ? 'loading' : isExpanded ? 'chevron-down' : 'chevron-right'}
                  size={12}
                  className={isLoading ? 'spinning' : ''}
                />
              </span>
              <Icon name={isExpanded ? 'folder-open' : 'folder'} size={16} className="file-icon" />
            </>
          ) : (
            <>
              <span className="chevron-placeholder" />
              <Icon name={getFileIconName(node.name)} size={16} className="file-icon" />
            </>
          )}
          {isRenaming ? (
            <input
              className="rename-input"
              value={renameValue}
              autoFocus
              onChange={(e) => setRenameValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.stopPropagation();
                  handleRenameConfirm(node);
                } else if (e.key === 'Escape') {
                  setRenamingPath(null);
                }
              }}
              onBlur={() => handleRenameConfirm(node)}
              onClick={(e) => e.stopPropagation()}
            />
          ) : (
            <span className="file-name">{node.name}</span>
          )}
          {node.gitStatus && (
            <span className={`git-status ${node.gitStatus}`}>
              <Icon name={node.gitStatus} size={12} />
            </span>
          )}
        </div>
        {node.type === 'folder' && isExpanded && node.children?.map(child => renderFileNode(child, projectPath, depth + 1))}
      </div>
    );
  }

  function renderProject(project: Project) {
    const isActive = activeProjectPath === project.id;
    const isExpanded = expandedProjects.has(project.id);
    const isLoading = loadingProjects.has(project.id);
    const tree = projectTrees.get(project.id);
    const isEmpty = isExpanded && tree && tree.length === 0;

    return (
      <div key={project.id} className="project-root">
        <div
          className={`project-root-header${isActive ? ' active' : ''}`}
          onClick={() => toggleProject(project.id)}
          onContextMenu={(e) => handleProjectContextMenu(e, project.id)}
        >
          <span className="chevron-icon">
            <Icon name={isExpanded ? 'chevron-down' : 'chevron-right'} size={12} />
          </span>
          <Icon name={isExpanded ? 'folder-open' : 'folder'} size={16} className="file-icon" />
          <span className="project-root-name" title={project.id}>{project.name}</span>
          <div className="project-root-actions">
            <button className="project-root-action" title="刷新" onClick={(e) => handleRefreshProject(project.id, e)}>
              <Icon name="refresh" size={12} />
            </button>
            <button className="project-root-action" title="在资源管理器中打开" onClick={(e) => handleOpenInExplorer(project.id, e)}>
              <Icon name="folder" size={12} />
            </button>
            <button className="project-root-action" title="关闭项目" onClick={(e) => handleRemoveProject(project.id, e)}>
              <Icon name="close" size={12} />
            </button>
          </div>
        </div>
        {isExpanded && (
          <div
            className="project-tree-content"
            onContextMenu={(e) => handleContextMenu(e, null, project.id)}
          >
            {isLoading ? (
              <div className="project-loading">
                <Icon name="loading" size={14} />
                <span>加载中...</span>
              </div>
            ) : isEmpty ? (
              <div className="project-empty-tree">空项目</div>
            ) : tree ? (
              tree.map(file => renderFileNode(file, project.id))
            ) : null}
          </div>
        )}
      </div>
    );
  }

  const hasProjects = projects.length > 0;

  return (
    <div className="explorer-panel">
      <div className="panel-header">
        <span className="panel-title">项目管理</span>
        <div className="panel-actions">
          <DropdownMenu
            align="right"
            items={[
              { id: 'new-empty-project', label: '新建空项目' },
              { id: 'use-existing-project', label: '使用现有项目' },
            ]}
            onItemClick={handleNewProjectAction}
            trigger={(
              <button className="panel-action" title="新建项目" aria-label="新建项目">
                <Icon name="add" size={16} />
              </button>
            )}
          />
        </div>
      </div>
      <div className="projects-list">
        {projectActionMessage && <div className={`resource-action-message${projectActionMessage.error ? ' error' : ''}`}>{projectActionMessage.text}</div>}
        {hasProjects ? (
          projects.map(project => renderProject(project))
        ) : null}
      </div>


      {/* 文件右键菜单 */}
      {contextMenu && (
        <ContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          items={buildMenuItems()}
          onItemClick={handleContextAction}
          onClose={() => setContextMenu(null)}
        />
      )}

      {/* 项目头部右键菜单 */}
      {projectContextMenu && (
        <ContextMenu
          x={projectContextMenu.x}
          y={projectContextMenu.y}
          items={[
            { id: 'new-file', label: '新建文件' },
            { id: 'new-folder', label: '新建文件夹' },
            { id: 'divider-1', label: '', divider: true },
            { id: 'rename', label: '重命名' },
            { id: 'copy', label: '复制路径' },
            { id: 'open-in-explorer', label: '在资源管理器中打开' },
            { id: 'delete', label: '删除', danger: true },
          ]}
          onItemClick={handleProjectContextAction}
          onClose={() => setProjectContextMenu(null)}
        />
      )}

      {/* 删除确认对话框 */}
      {confirmDialog && (
        <ConfirmDialog
          title="确认删除"
          message={`确定要删除此文件夹及其所有内容吗？此操作不可撤销。`}
          confirmLabel="删除"
          cancelLabel="取消"
          danger
          onConfirm={handleConfirmDelete}
          onCancel={() => setConfirmDialog(null)}
        />
      )}
      {renameProjectTarget && (
        <ConfirmDialog
          title="重命名项目"
          message="项目目录会在原父目录中同步重命名。"
          inputLabel="项目名称"
          inputValue={renameProjectValue}
          inputError={getProjectNameError(renameProjectValue)}
          confirmLabel={projectActionPending ? '处理中' : '重命名'}
          confirmDisabled={Boolean(getProjectNameError(renameProjectValue)) || projectActionPending}
          onInputChange={setRenameProjectValue}
          onConfirm={() => { void handleRenameProjectConfirm(); }}
          onCancel={() => { if (!projectActionPending) setRenameProjectTarget(null); }}
        />
      )}
      {deleteProjectTarget && (
        <ConfirmDialog
          title="删除项目"
          message={`仅将「${deleteProjectTarget.name}」从项目管理中移除，不会删除磁盘上的项目目录和文件。`}
          confirmLabel="删除"
          confirmDisabled={projectActionPending}
          danger
          onConfirm={() => {
            setProjectActionPending(true);
            void handleRemoveProject(deleteProjectTarget.id)
              .then(() => {
                setDeleteProjectTarget(null);
                setProjectActionMessage({ text: '项目已从列表删除' });
              })
              .catch(error => {
                console.error('[ExplorerPanel] 删除项目管理项失败:', error);
                setProjectActionMessage({ text: '删除项目失败', error: true });
              })
              .finally(() => setProjectActionPending(false));
          }}
          onCancel={() => { if (!projectActionPending) setDeleteProjectTarget(null); }}
        />
      )}
    </div>
  );
}

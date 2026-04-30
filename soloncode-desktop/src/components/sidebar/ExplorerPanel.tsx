/**
 * 多项目管理面板 - 支持多项目、懒加载、空项目
 * @author bai
 */
import { useState, useCallback, useEffect, useRef } from 'react';
import { Icon, getFileIconName } from '../common/Icon';
import { ContextMenu } from '../common/ContextMenu';
import { ConfirmDialog } from '../common/ConfirmDialog';
import type { MenuItem } from '../common/DropdownMenu';
import { fileService } from '../../services/fileService';
import { revealItemInDir } from '@tauri-apps/plugin-opener';
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

interface ExplorerPanelProps {
  projects: Project[];
  activeProjectPath: string | null;
  refreshKey: number;
  onFileSelect: (path: string) => void;
  onOpenFolder: () => void;
  onCreateProject: () => void;
  onRemoveProject: (id: string) => void;
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

export function ExplorerPanel({
  projects,
  activeProjectPath,
  refreshKey,
  onFileSelect,
  onOpenFolder,
  onCreateProject,
  onRemoveProject,
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
      const files = await fileService.listDirectoryTree(projectPath, 10);
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
  const handleRemoveProject = useCallback((projectPath: string, e?: React.MouseEvent) => {
    e?.stopPropagation();
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
    onRemoveProject(projectPath);
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

  const toggleFolder = (path: string) => {
    setExpandedFolders(prev => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  };

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
        { id: 'new-file-in', label: '新建文件' },
        { id: 'new-folder-in', label: '新建文件夹' },
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
        const name = 'untitled';
        let path = `${projectPath}/${name}`;
        let counter = 1;
        while (await fileService.pathExists(path)) {
          path = `${projectPath}/${name}-${counter}`;
          counter++;
        }
        await fileService.createFile(path);
        await loadProjectTree(projectPath);
        setRenamingPath(path);
        setRenameValue(name);
        setIsNewFile(true);
        break;
      }

      case 'new-folder':
        if (projectPath) await onNewFolder(projectPath);
        break;

      case 'new-file-in':
        if (node && node.type === 'folder') {
          const parentDir = node.path;
          const name = 'untitled';
          let path = `${parentDir}/${name}`;
          let counter = 1;
          while (await fileService.pathExists(path)) {
            path = `${parentDir}/${name}-${counter}`;
            counter++;
          }
          await fileService.createFile(path);
          await loadProjectTree(projectPath || '');
          setRenamingPath(path);
          setRenameValue(name);
          setIsNewFile(true);
        }
        break;

      case 'new-folder-in':
        if (node && node.type === 'folder') {
          const parentDir = node.path;
          const name = 'new-folder';
          let path = `${parentDir}/${name}`;
          let counter = 1;
          while (await fileService.pathExists(path)) {
            path = `${parentDir}/${name}-${counter}`;
            counter++;
          }
          await fileService.createDirectory(path);
          await loadProjectTree(projectPath || '');
        }
        break;
    }
  }, [contextMenu, clipboard, performPaste, onNewFolder, onDelete, loadProjectTree]);

  const handleProjectContextAction = useCallback(async (itemId: string) => {
    const path = projectContextMenu?.projectPath;
    setProjectContextMenu(null);
    if (!path) return;

    switch (itemId) {
      case 'open-in-explorer':
        await handleOpenInExplorer(path);
        break;
      case 'close':
        handleRemoveProject(path);
        break;
      case 'new-file':
        await onNewFile(path);
        break;
      case 'new-folder':
        await onNewFolder(path);
        break;
    }
  }, [projectContextMenu, handleOpenInExplorer, handleRemoveProject, onNewFile, onNewFolder]);

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

  function renderFileNode(node: FileNode, depth: number = 0) {
    const indent = depth * 16;
    const isExpanded = expandedFolders.has(node.path);
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
              toggleFolder(node.path);
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
                <Icon name={isExpanded ? 'chevron-down' : 'chevron-right'} size={12} />
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
        {node.type === 'folder' && isExpanded && node.children?.map(child => renderFileNode(child, depth + 1))}
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
          <Icon name="folder-root" size={16} className="file-icon" />
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
              tree.map(file => renderFileNode(file))
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
        {hasProjects && (
          <div className="panel-actions">
            <button className="panel-action" title="打开文件夹" onClick={onOpenFolder}>
              <Icon name="folder-add" size={16} />
            </button>
            <button className="panel-action" title="新建项目" onClick={onCreateProject}>
              <Icon name="add" size={16} />
            </button>
          </div>
        )}
      </div>

      {hasProjects ? (
        <div className="projects-list">
          {projects.map(project => renderProject(project))}
        </div>
      ) : (
        <div className="empty-workspace">
          <div className="empty-icon">
            <Icon name="folder" size={48} />
          </div>
          <p className="empty-text">尚未打开项目</p>
          <button className="open-folder-btn" onClick={onOpenFolder}>
            打开文件夹
          </button>
          <button className="create-project-btn" onClick={onCreateProject}>
            新建项目
          </button>
        </div>
      )}

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
            { id: 'open-in-explorer', label: '在资源管理器中打开' },
            { id: 'close', label: '关闭项目' },
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
    </div>
  );
}

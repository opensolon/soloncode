/**
 * 资源管理器面板
 * @author bai
 */
import { useState, useCallback } from 'react';
import { Icon, getFileIconName } from '../common/Icon';
import { ContextMenu } from '../common/ContextMenu';
import { ConfirmDialog } from '../common/ConfirmDialog';
import type { MenuItem } from '../common/DropdownMenu';
import { fileService } from '../../services/fileService';
import './ExplorerPanel.css';

interface FileNode {
  name: string;
  type: 'file' | 'folder';
  children?: FileNode[];
  path: string;
  gitStatus?: 'modified' | 'added' | 'deleted' | 'untracked';
}

interface ExplorerPanelProps {
  files: FileNode[];
  workspaceName?: string;
  hasWorkspace?: boolean;
  workspacePath?: string;
  onFileSelect: (path: string) => void;
  onOpenFolder?: () => void;
  onRefresh?: () => void;
  onNewFile?: () => void;
  onNewFolder?: () => Promise<void>;
  onRename?: (oldPath: string, newPath: string) => Promise<void>;
  onDelete?: (path: string, type: 'file' | 'folder') => Promise<void>;
  onCopy?: (sourcePath: string, destPath: string) => Promise<void>;
  onMove?: (sourcePath: string, destPath: string) => Promise<void>;
}

// 获取父目录路径
function getParentDir(filePath: string): string {
  const sep = filePath.includes('\\') ? '\\' : '/';
  const parts = filePath.split(sep);
  parts.pop();
  return parts.join(sep);
}

// 获取文件扩展名
function getExt(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot >= 0 ? name.slice(dot) : '';
}

// 获取不含扩展名的文件名
function getBaseName(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot >= 0 ? name.slice(0, dot) : name;
}

export function ExplorerPanel({
  files,
  workspaceName,
  hasWorkspace: hasWorkspaceProp,
  workspacePath,
  onFileSelect,
  onOpenFolder,
  onRefresh,
  onNewFile,
  onNewFolder,
  onRename,
  onDelete,
  onCopy,
  onMove,
}: ExplorerPanelProps) {
  const [expandedFolders, setExpandedFolders] = useState<Set<string>>(new Set());

  // 右键菜单状态
  const [contextMenu, setContextMenu] = useState<{
    x: number;
    y: number;
    node: FileNode | null;
  } | null>(null);

  // 剪贴板状态
  const [clipboard, setClipboard] = useState<{
    path: string;
    operation: 'copy' | 'cut';
    name: string;
    type: 'file' | 'folder';
  } | null>(null);

  // 内联重命名状态
  const [renamingPath, setRenamingPath] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [isNewFile, setIsNewFile] = useState(false); // 标记是否为新建文件的重命名

  // 确认对话框状态
  const [confirmDialog, setConfirmDialog] = useState<{
    path: string;
    type: 'file' | 'folder';
  } | null>(null);

  // 如果有 hasWorkspace prop 则使用，否则根据 files 判断
  const hasWorkspace = hasWorkspaceProp !== undefined ? hasWorkspaceProp : files.length > 0;

  const toggleFolder = (path: string) => {
    setExpandedFolders(prev => {
      const next = new Set(prev);
      if (next.has(path)) {
        next.delete(path);
      } else {
        next.add(path);
      }
      return next;
    });
  };

  // ==================== 右键菜单 ====================

  const handleContextMenu = useCallback((e: React.MouseEvent, node: FileNode | null) => {
    e.preventDefault();
    e.stopPropagation();
    setContextMenu({ x: e.clientX, y: e.clientY, node });
  }, []);

  const buildMenuItems = useCallback((): MenuItem[] => {
    const node = contextMenu?.node;
    const pasteDisabled = !clipboard;

    if (!node) {
      // 空白区域右键
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

    // 如果是文件夹，加上新建子项
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

  // 执行粘贴
  const performPaste = useCallback(async (
    clip: { path: string; operation: 'copy' | 'cut'; name: string; type: 'file' | 'folder' },
    targetDir: string,
  ) => {
    // 防止粘贴到自身内部
    if (targetDir.startsWith(clip.path)) return;

    let destPath = `${targetDir}/${clip.name}`;

    // 名称冲突时自动加后缀
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
      setClipboard(null); // 剪切粘贴后清空剪贴板
    }
  }, [onCopy, onMove]);

  // 菜单项点击处理
  const handleContextAction = useCallback(async (itemId: string) => {
    const node = contextMenu?.node;
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
          }
        }
        break;

      case 'copy':
        if (node) {
          setClipboard({ path: node.path, operation: 'copy', name: node.name, type: node.type });
        }
        break;

      case 'cut':
        if (node) {
          setClipboard({ path: node.path, operation: 'cut', name: node.name, type: node.type });
        }
        break;

      case 'paste': {
        if (!clipboard) break;
        // 确定粘贴目标目录
        let targetDir: string;
        if (node) {
          targetDir = node.type === 'folder' ? node.path : getParentDir(node.path);
        } else {
          targetDir = workspacePath || '';
        }
        if (targetDir) {
          await performPaste(clipboard, targetDir);
        }
        break;
      }

      case 'new-file': {
        if (!workspacePath) break;
        const name = 'untitled';
        let path = `${workspacePath}/${name}`;
        let counter = 1;
        while (await fileService.pathExists(path)) {
          path = `${workspacePath}/${name}-${counter}`;
          counter++;
        }
        await fileService.createFile(path);
        await onRefresh?.();
        setRenamingPath(path);
        setRenameValue(name);
        setIsNewFile(true);
        break;
      }

      case 'new-folder':
        onNewFolder?.();
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
          await onRefresh?.();
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
          onRefresh?.();
        }
        break;
    }
  }, [contextMenu, clipboard, workspacePath, performPaste, onNewFolder, onDelete, onRefresh, onFileSelect]);

  // 确认删除
  const handleConfirmDelete = useCallback(async () => {
    if (confirmDialog) {
      await onDelete?.(confirmDialog.path, confirmDialog.type);
      setConfirmDialog(null);
    }
  }, [confirmDialog, onDelete]);

  // 重命名确认
  const handleRenameConfirm = useCallback(async (node: FileNode) => {
    const trimmed = renameValue.trim();

    // 新建文件模式
    if (isNewFile) {
      if (!trimmed) {
        // 输入为空，删除临时文件
        await fileService.deleteFile(node.path);
        await onRefresh?.();
      } else if (trimmed !== node.name) {
        // 有输入且改了名，执行重命名
        const parentDir = getParentDir(node.path);
        const sep = node.path.includes('\\') ? '\\' : '/';
        const newPath = `${parentDir}${sep}${trimmed}`;
        await onRename?.(node.path, newPath);
        onFileSelect(newPath);
      } else {
        // 名字没变，直接打开
        onFileSelect(node.path);
      }
      setRenamingPath(null);
      setIsNewFile(false);
      return;
    }

    // 普通重命名模式
    if (!trimmed || trimmed === node.name) {
      setRenamingPath(null);
      return;
    }
    const parentDir = getParentDir(node.path);
    const sep = node.path.includes('\\') ? '\\' : '/';
    const newPath = `${parentDir}${sep}${trimmed}`;
    await onRename?.(node.path, newPath);
    setRenamingPath(null);
  }, [renameValue, isNewFile, onRename, onRefresh, onFileSelect]);

  // ==================== 渲染 ====================

  function renderFileNode(node: FileNode, depth: number = 0) {
    const indent = depth * 16;
    const isExpanded = expandedFolders.has(node.path);
    const isRenaming = renamingPath === node.path;
    // 剪切项半透明
    const isCut = clipboard?.operation === 'cut' && clipboard.path === node.path;

    return (
      <div key={node.path}>
        <div
          className={`file-node ${node.type}${isExpanded ? ' expanded' : ''}${isCut ? ' cut-item' : ''}`}
          style={{ paddingLeft: `${indent + 8}px` }}
          onClick={() => {
            if (isRenaming) return;
            if (node.type === 'folder') {
              toggleFolder(node.path);
            } else {
              onFileSelect(node.path);
            }
          }}
          onContextMenu={(e) => handleContextMenu(e, node)}
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

  return (
    <div className="explorer-panel">
      <div className="panel-header">
        <span className="panel-title">资源管理器</span>
        {hasWorkspace && (
          <div className="panel-actions">
            <button className="panel-action" title="新建文件" onClick={onNewFile}>
              <Icon name="add" size={16} />
            </button>
            <button className="panel-action" title="新建文件夹" onClick={onNewFolder}>
              <Icon name="folder-add" size={16} />
            </button>
            <button className="panel-action" title="刷新" onClick={onRefresh}>
              <Icon name="refresh" size={16} />
            </button>
          </div>
        )}
      </div>

      {hasWorkspace ? (
        <>
          <div className="workspace-header">
            <Icon name="folder-root" size={16} />
            <span className="workspace-name">{workspaceName || '工作区'}</span>
          </div>
          <div
            className="panel-content"
            onContextMenu={(e) => handleContextMenu(e, null)}
          >
            {files.map(file => renderFileNode(file))}
          </div>
        </>
      ) : (
        <div className="empty-workspace">
          <div className="empty-icon">
            <Icon name="folder" size={48} />
          </div>
          <p className="empty-text">尚未打开文件夹</p>
          <button className="open-folder-btn" onClick={onOpenFolder}>
            打开文件夹
          </button>
        </div>
      )}

      {/* 右键菜单 */}
      {contextMenu && (
        <ContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          items={buildMenuItems()}
          onItemClick={handleContextAction}
          onClose={() => setContextMenu(null)}
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

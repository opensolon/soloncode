/**
 * 标题栏组件 - 包含文件菜单
 * @author bai
 */
import { DropdownMenu, type MenuItem } from '../common/DropdownMenu';
import { Icon } from '../common/Icon';
import './TitleBar.css';

interface TitleBarProps {
  // 工作区
  workspacePath?: string;
  workspaceName?: string;
  // 文件操作回调
  onNewFile?: () => void;
  onNewFolder?: () => void;
  onOpenFile?: () => void;
  onOpenFolder?: () => void;
  onSave?: () => void;
  onSaveAs?: () => void;
  onSaveAll?: () => void;
  // 视图控制
  editorVisible?: boolean;
  chatVisible?: boolean;
  onToggleEditor?: () => void;
  onToggleChat?: () => void;
  onToggleTerminal?: () => void;
  onSwapPanels?: () => void;
}

export function TitleBar({
  workspacePath,
  workspaceName,
  onNewFile,
  onNewFolder,
  onOpenFile,
  onOpenFolder,
  onSave,
  onSaveAs,
  onSaveAll,
  editorVisible,
  chatVisible,
  onToggleEditor,
  onToggleChat,
  onToggleTerminal,
  onSwapPanels,
}: TitleBarProps) {
  // 文件菜单项
  const fileMenuItems: MenuItem[] = [
    {
      id: 'new-file',
      label: '新建文件',
      shortcut: 'Ctrl+N',
    },
    {
      id: 'new-folder',
      label: '新建文件夹',
    },
    { id: 'divider1', label: '', divider: true },
    {
      id: 'open-folder',
      label: '打开文件夹...',
      shortcut: 'Ctrl+K Ctrl+O',
    },
    { id: 'divider2', label: '', divider: true },
    {
      id: 'save',
      label: '保存',
      shortcut: 'Ctrl+S',
    },
    {
      id: 'save-as',
      label: '另存为...',
      shortcut: 'Ctrl+Shift+S',
    },
    {
      id: 'save-all',
      label: '全部保存',
    },
  ];

  // 编辑菜单项
  const editMenuItems: MenuItem[] = [
    { id: 'undo', label: '撤销', shortcut: 'Ctrl+Z' },
    { id: 'redo', label: '重做', shortcut: 'Ctrl+Y' },
    { id: 'divider1', label: '', divider: true },
    { id: 'cut', label: '剪切', shortcut: 'Ctrl+X' },
    { id: 'copy', label: '复制', shortcut: 'Ctrl+C' },
    { id: 'paste', label: '粘贴', shortcut: 'Ctrl+V' },
    { id: 'divider2', label: '', divider: true },
    { id: 'find', label: '查找', shortcut: 'Ctrl+F' },
    { id: 'replace', label: '替换', shortcut: 'Ctrl+H' },
  ];

  // 视图菜单项
  const viewMenuItems: MenuItem[] = [
    { id: 'toggle-sidebar', label: '切换侧边栏', shortcut: 'Ctrl+B' },
    { id: 'toggle-terminal', label: '切换终端', shortcut: 'Ctrl+`' },
    { id: 'divider1', label: '', divider: true },
    { id: 'toggle-editor', label: '切换编辑器', shortcut: 'Ctrl+E' },
    { id: 'toggle-chat', label: '切换对话', shortcut: 'Ctrl+Shift+C' }
  ];

  // 帮助菜单项
  const helpMenuItems: MenuItem[] = [
    { id: 'about', label: '关于' },
    { id: 'docs', label: '文档' },
    { id: 'divider1', label: '', divider: true },
    { id: 'check-update', label: '检查更新' },
  ];

  // 处理菜单点击
  const handleFileMenuClick = (itemId: string) => {
    switch (itemId) {
      case 'new-file':
        onNewFile?.();
        break;
      case 'new-folder':
        onNewFolder?.();
        break;
      case 'open-folder':
        onOpenFolder?.();
        break;
      case 'save':
        onSave?.();
        break;
      case 'save-as':
        onSaveAs?.();
        break;
      case 'save-all':
        onSaveAll?.();
        break;
    }
  };

  const handleViewMenuClick = (itemId: string) => {
    switch (itemId) {
      case 'toggle-editor':
        onToggleEditor?.();
        break;
      case 'toggle-chat':
        onToggleChat?.();
        break;
      case 'swap-panels':
        onSwapPanels?.();
        break;
    }
  };

  return (
    <div className="title-bar" data-tauri-drag-region>
      {/* 左侧菜单 */}
      <div className="title-bar-left">
        <div className="app-logo">
          <Icon name="bot" size={16} />
        </div>
        <DropdownMenu
          trigger={<span className="menu-trigger">文件</span>}
          items={fileMenuItems}
          onItemClick={handleFileMenuClick}
        />
        <DropdownMenu
          trigger={<span className="menu-trigger">编辑</span>}
          items={editMenuItems}
        />
        <DropdownMenu
          trigger={<span className="menu-trigger">视图</span>}
          items={viewMenuItems}
          onItemClick={handleViewMenuClick}
        />
        <DropdownMenu
          trigger={<span className="menu-trigger">帮助</span>}
          items={helpMenuItems}
        />
      </div>

      {/* 中间标题 */}
      <div className="title-bar-center">
        <span className="app-title">SolonCode</span>
        {workspaceName && (
          <span className="workspace-name"> - {workspaceName}</span>
        )}
      </div>

      {/* 右侧工具栏 */}
      <div className="title-bar-right">
        <button
          className={`titlebar-btn${editorVisible ? ' active' : ''}`}
          onClick={onToggleEditor}
          title="显示/隐藏编辑器"
        >
          <Icon name="code" size={14} />
          <span>编辑器</span>
        </button>
        <button
          className={`titlebar-btn${chatVisible ? ' active' : ''}`}
          onClick={onToggleChat}
          title="显示/隐藏对话"
        >
          <Icon name="chat" size={14} />
          <span>对话</span>
        </button>
        <button
          className="titlebar-btn"
          onClick={onToggleTerminal}
          title="新开终端"
        >
          <Icon name="terminal" size={14} />
          <span>终端</span>
        </button>
      </div>
    </div>
  );
}

export default TitleBar;

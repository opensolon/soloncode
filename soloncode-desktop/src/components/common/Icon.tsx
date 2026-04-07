import './Icon.css';

export type IconName =
  // 文件类型
  | 'file' | 'folder' | 'folder-open' | 'folder-root' | 'folder-add'
  // 功能图标
  | 'explorer' | 'search' | 'git' | 'extensions' | 'sessions' | 'settings'
  | 'chat' | 'terminal' | 'code'
  // 操作图标
  | 'add' | 'remove' | 'edit' | 'delete' | 'refresh' | 'save'
  | 'push' | 'pull' | 'commit' | 'stage' | 'unstage'
  | 'close' | 'collapse' | 'expand' | 'swap'
  | 'send' | 'attach' | 'theme' | 'user' | 'assistant' | 'bot'
  // 状态图标
  | 'modified' | 'added' | 'deleted' | 'untracked' | 'warning' | 'error' | 'success' | 'loading'
  // 其他
  | 'chevron-right' | 'chevron-down' | 'chevron-up' | 'more' | 'menu';

interface IconProps {
  name: IconName;
  size?: number;
  className?: string;
}

const iconPaths: Record<IconName, string> = {
  // 文件类型
  'file': 'M14 2H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zm4 14H6V4h7v5h5v7z',
  'folder': 'M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z',
  'folder-open': 'M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2v2H2',
  'folder-root': 'M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z M12 9v4M10 11h4',
  'folder-add': 'M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z M12 9v4M10 11h4',

  // 功能图标 - 使用简单的几何图形
  'explorer': 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
  'search': 'M11 11m-7 0a7 7 0 1 0 14 0a7 7 0 1 0 -14 0M21 21l-4.35-4.35',
  'git': 'M6 6m-2 0a2 2 0 1 0 4 0a2 2 0 1 0 -4 0M6 18m-2 0a2 2 0 1 0 4 0a2 2 0 1 0 -4 0M18 6m-2 0a2 2 0 1 0 4 0a2 2 0 1 0 -4 0M6 8v10M8 6h8M16 8v6',
  'extensions': 'M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z',
  'sessions': 'M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z',
  'settings': 'M12 12m-3 0a3 3 0 1 0 6 0a3 3 0 1 0 -6 0M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z',
  'chat': 'M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z',
  'terminal': 'M4 17l6-6-6-6M12 19h8',
  'code': 'M16 18l6-6-6-6M8 6l-6 6 6 6',

  // 操作图标
  'add': 'M12 5v14M5 12h14',
  'remove': 'M5 12h14',
  'edit': 'M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z',
  'delete': 'M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2',
  'refresh': 'M23 4v6h-6M1 20v-6h6M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15',
  'save': 'M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z M17 21v-8H7v8 M7 3v5h8',
  'push': 'M12 19V5M5 12l7-7 7 7',
  'pull': 'M12 5v14M5 12l7 7 7-7',
  'commit': 'M12 12m-4 0a4 4 0 1 0 8 0a4 4 0 1 0 -8 0M1.05 12H7M17.01 12h5.95',
  'stage': 'M12 5v14M5 12h14',
  'unstage': 'M5 12h14',
  'close': 'M18 6L6 18M6 6l12 12',
  'collapse': 'M4 14l6 0l0 6',
  'expand': 'M15 3l6 0l0 6',
  'swap': 'M7 16V4m0 0L3 8m4-4l4 4M17 8v12m0 0l4-4m-4 4l-4-4',
  'send': 'M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z',
  'attach': 'M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48',
  'theme': 'M12 12m-5 0a5 5 0 1 0 10 0a5 5 0 1 0 -10 0M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42',
  'user': 'M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2 M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z',
  'assistant': 'M12 1a4 4 0 0 0-4 4v2H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-2V5a4 4 0 0 0-4-4z',
  'bot': 'M12 1v2M12 1a4 4 0 0 0-4 4v2H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-2V5a4 4 0 0 0-4-4z',

  // 状态图标
  'modified': 'M12 12m-4 0a4 4 0 1 0 8 0a4 4 0 1 0 -8 0',
  'added': 'M12 8v8M8 12h8',
  'deleted': 'M8 12h8',
  'untracked': 'M12 12m-4 0a4 4 0 1 0 8 0a4 4 0 1 0 -8 0',
  'warning': 'M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z M12 9v4M12 17h.01',
  'error': 'M12 12m-10 0a10 10 0 1 0 20 0a10 10 0 1 0 -20 0 M15 9l-6 6M9 9l6 6',
  'success': 'M22 11.08V12a10 10 0 1 1-5.93-9.14 M22 4L12 14.01l-3-3',
  'loading': 'M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83',

  // 其他
  'chevron-right': 'M9 18l6-6-6-6',
  'chevron-down': 'M6 9l6 6 6-6',
  'chevron-up': 'M18 15l-6-6-6 6',
  'more': 'M12 12m-1 0a1 1 0 1 0 2 0a1 1 0 1 0 -2 0M19 12m-1 0a1 1 0 1 0 2 0a1 1 0 1 0 -2 0M5 12m-1 0a1 1 0 1 0 2 0a1 1 0 1 0 -2 0',
  'menu': 'M3 12h18M3 6h18M3 18h18',
};

export function Icon({ name, size = 16, className = '' }: IconProps) {
  const path = iconPaths[name];
  if (!path) return null;

  return (
    <svg
      className={`icon ${className}`}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d={path} />
    </svg>
  );
}

// 文件图标映射
export function getFileIconName(filename: string): IconName {
  const ext = filename.split('.').pop()?.toLowerCase() || '';
  // 所有文件都用统一的文件图标
  return 'file';
}

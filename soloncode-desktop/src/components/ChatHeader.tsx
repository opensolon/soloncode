import { Theme } from '../types';
import './ChatHeader.css';

interface ChatHeaderProps {
  title: string;
  status: string;
  theme: Theme;
  projectName?: string;
  onToggleTheme: () => void;
}

export function ChatHeader({ title, status, theme, projectName, onToggleTheme }: ChatHeaderProps) {
  return (
    <header className="chat-header">
      <div className="chat-title">
        <h2>{title}</h2>
        <div className="chat-title-meta">
          {projectName && <span className="chat-project-name">{projectName}</span>}
          <span className="chat-status">{status === 'active' ? '进行中' : '已完成'}</span>
        </div>
      </div>
      <button
        className="theme-toggle-btn"
        title={theme === 'dark' ? '切换到亮色模式' : '切换到暗色模式'}
        onClick={onToggleTheme}
      >
        {theme === 'dark' ? (
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
            <circle cx="10" cy="10" r="4" stroke="currentColor" strokeWidth="2" />
            <path d="M10 2V4M10 16V18M18 10H16M4 10H2M15.66 15.66L14.24 14.24M5.76 5.76L4.34 4.34M15.66 4.34L14.24 5.76M5.76 14.24L4.34 15.66" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          </svg>
        ) : (
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
            <path d="M10 18C13.866 18 17 14.866 17 11C17 7.13401 13.866 4 10 4C10 4 10 4 10 4C6.13401 4 3 7.13401 3 11C3 14.866 6.13401 18 10 18Z" stroke="currentColor" strokeWidth="2" />
            <path d="M10 4V2" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            <path d="M10 18V16" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            <path d="M15.66 6.34L14.24 4.92" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            <path d="M4.34 15.66L5.76 14.24" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            <path d="M18 11H16" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            <path d="M4 11H2" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            <path d="M15.66 15.66L14.24 14.24" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            <path d="M4.34 6.34L5.76 4.92" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          </svg>
        )}
      </button>
    </header>
  );
}

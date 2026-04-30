import { Icon } from '../common/Icon';
import type { Theme } from '../../types';
import './ActivityBar.css';

export type ActivityType = 'explorer' | 'git' | 'extensions' | 'sessions' | 'settings' | 'skills' | 'agents';

interface ActivityBarProps {
  activeActivity: ActivityType;
  theme: Theme;
  onActivityChange: (activity: ActivityType) => void;
  onToggleTheme: () => void;
}

interface ActivityItem {
  id: ActivityType;
  icon: 'explorer' | 'search' | 'git' | 'extensions' | 'sessions' | 'settings' | 'skills' | 'agents';
  title: string;
}

const activities: ActivityItem[] = [
  { id: 'sessions', icon: 'sessions', title: '对话' },
  { id: 'explorer', icon: 'explorer', title: '资源管理' },
  { id: 'git', icon: 'git', title: '源代码管理' },
  { id: 'skills', icon: 'skills', title: 'Skills' },
  { id: 'agents', icon: 'agents', title: 'Agents' },
  // { id: 'extensions', icon: 'extensions', title: '扩展' }, // 后期开发
];

export function ActivityBar({ activeActivity, theme, onActivityChange, onToggleTheme }: ActivityBarProps) {
  return (
    <div className="activity-bar">
      <div className="activity-bar-top">
        {activities.map((activity) => (
          <button
            key={activity.id}
            className={`activity-item${activeActivity === activity.id ? ' active' : ''}`}
            title={activity.title}
            onClick={() => onActivityChange(activeActivity === activity.id ? 'explorer' : activity.id)}
          >
            <Icon name={activity.icon} size={24} />
          </button>
        ))}
      </div>
      <div className="activity-bar-bottom">
        <button
          className="activity-item"
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
        <button
          className={`activity-item${activeActivity === 'settings' ? ' active' : ''}`}
          title="设置"
          onClick={() => onActivityChange(activeActivity === 'settings' ? 'explorer' : 'settings')}
        >
          <Icon name="settings" size={24} />
        </button>
      </div>
    </div>
  );
}

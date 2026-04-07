import { Icon } from '../common/Icon';
import './ActivityBar.css';

export type ActivityType = 'explorer' | 'search' | 'git' | 'extensions' | 'sessions' | 'settings';

interface ActivityBarProps {
  activeActivity: ActivityType;
  onActivityChange: (activity: ActivityType) => void;
}

interface ActivityItem {
  id: ActivityType;
  icon: 'explorer' | 'search' | 'git' | 'extensions' | 'sessions' | 'settings';
  title: string;
}

const activities: ActivityItem[] = [
  { id: 'explorer', icon: 'explorer', title: '资源管理器' },
  { id: 'search', icon: 'search', title: '搜索' },
  { id: 'git', icon: 'git', title: '源代码管理' },
  // { id: 'extensions', icon: 'extensions', title: '扩展' }, // 后期开发
  { id: 'sessions', icon: 'sessions', title: '会话' },
];

export function ActivityBar({ activeActivity, onActivityChange }: ActivityBarProps) {
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

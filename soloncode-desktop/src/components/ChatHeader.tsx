import './ChatHeader.css';

interface ChatHeaderProps {
  title: string;
  status: string;
  projectName?: string;
}

export function ChatHeader({ title, status, projectName }: ChatHeaderProps) {
  return (
    <header className="chat-header">
      <div className="chat-title">
        <h2>{title}</h2>
        <div className="chat-title-meta">
          {projectName && <span className="chat-project-name">{projectName}</span>}
          <span className="chat-status">{status === 'active' ? '进行中' : '已完成'}</span>
        </div>
      </div>
    </header>
  );
}

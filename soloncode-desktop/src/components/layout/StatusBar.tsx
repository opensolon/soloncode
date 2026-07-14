import { Icon } from '../common/Icon';
import { startWindowDrag } from '../../hooks/useWindowDrag';
import './StatusBar.css';

export type BackendStatus = 'connecting' | 'connected' | 'disconnected';

export interface StatusBarProps {
  model?: string;
  backendStatus?: BackendStatus;
  branch?: string;
  ahead?: number;
  behind?: number;
  warningCount?: number;
  errorCount?: number;
  cursorLine?: number;
  cursorColumn?: number;
  encoding?: string;
  language?: string;
  hasUnsavedChanges?: boolean;
  onReconnect?: () => void;
}

export function StatusBar({
  model,
  backendStatus = 'disconnected',
  branch,
  ahead = 0,
  behind = 0,
  warningCount = 0,
  errorCount = 0,
  cursorLine,
  cursorColumn,
  encoding = 'UTF-8',
  language,
  hasUnsavedChanges = false,
  onReconnect,
}: StatusBarProps) {
  const backendTitle = backendStatus === 'connected'
    ? '后端已连接'
    : backendStatus === 'connecting'
      ? '正在连接或启动后端...'
      : '后端未连接，点击重试';

  const backendText = backendStatus === 'connected'
    ? '已连接'
    : backendStatus === 'connecting'
      ? '连接中...'
      : '连接失败，点击重试';

  return (
    <div className="status-bar" onMouseDown={startWindowDrag}>
      <div className="status-left" data-no-drag>
        <span
          className={`status-item status-connection${backendStatus === 'disconnected' && onReconnect ? ' clickable' : ''}`}
          title={backendTitle}
          onClick={backendStatus === 'disconnected' && onReconnect ? onReconnect : undefined}
        >
          <span className={`status-connection-dot${backendStatus === 'connected' ? ' connected' : ''}${backendStatus === 'connecting' ? ' connecting' : ''}`} />
          <span>{backendText}</span>
        </span>

        {model && (
          <span className="status-item status-model" title="当前模型">
            <Icon name="bot" size={12} />
            <span>{model}</span>
          </span>
        )}

        {branch && (
          <span className="status-item status-branch" title={`分支: ${branch}${ahead ? ` (ahead ${ahead})` : ''}${behind ? ` (behind ${behind})` : ''}`}>
            <Icon name="git" size={12} />
            <span>{branch}</span>
            {ahead > 0 && <span className="status-badge ahead">↑{ahead}</span>}
            {behind > 0 && <span className="status-badge behind">↓{behind}</span>}
          </span>
        )}

        {hasUnsavedChanges && (
          <span className="status-item status-unsaved" title="有未保存的更改">
            <span className="status-dot unsaved" />
            <span>未保存</span>
          </span>
        )}
      </div>

      <div className="status-right" data-no-drag>
        {(warningCount > 0 || errorCount > 0) && (
          <span className="status-item status-problems" title={`警告: ${warningCount}, 错误: ${errorCount}`}>
            {errorCount > 0 && (
              <span className="status-error">
                <Icon name="error" size={12} />
                <span>{errorCount}</span>
              </span>
            )}
            {warningCount > 0 && (
              <span className="status-warning">
                <Icon name="warning" size={12} />
                <span>{warningCount}</span>
              </span>
            )}
          </span>
        )}

        {cursorLine !== undefined && cursorColumn !== undefined && (
          <span className="status-item status-position" title="光标位置">
            <span>行 {cursorLine}, 列 {cursorColumn}</span>
          </span>
        )}

        <span className="status-item status-encoding" title="文件编码">
          <span>{encoding}</span>
        </span>

        {language && (
          <span className="status-item status-language" title="语言类型">
            <span>{language}</span>
          </span>
        )}
      </div>
    </div>
  );
}

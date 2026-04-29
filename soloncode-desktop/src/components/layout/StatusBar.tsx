/**
 * 状态栏组件 - 底部信息展示
 * 显示模型、分支、警告/错误数、光标位置、编码、语言类型
 * @author bai
 */
import { Icon } from '../common/Icon';
import './StatusBar.css';

export interface StatusBarProps {
  /** 当前 AI 模型名称 */
  model?: string;
  /** 后端是否已连接 */
  connected?: boolean;
  /** Git 分支名 */
  branch?: string;
  /** ahead 数量 */
  ahead?: number;
  /** behind 数量 */
  behind?: number;
  /** 警告数量 */
  warningCount?: number;
  /** 错误数量 */
  errorCount?: number;
  /** 光标行号 */
  cursorLine?: number;
  /** 光标列号 */
  cursorColumn?: number;
  /** 文件编码 */
  encoding?: string;
  /** 文件语言类型 */
  language?: string;
  /** 是否有未保存文件 */
  hasUnsavedChanges?: boolean;
}

export function StatusBar({
  model,
  connected = false,
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
}: StatusBarProps) {
  return (
    <div className="status-bar">
      <div className="status-left">
        {/* 后端连接状态 */}
        <span className="status-item status-connection" title={connected ? '后端已连接' : '后端未连接'}>
          <span className={`status-connection-dot${connected ? ' connected' : ''}`} />
          <span>{connected ? '已连接' : '未连接'}</span>
        </span>

        {/* AI 模型 */}
        {model && (
          <span className="status-item status-model" title="当前模型">
            <Icon name="bot" size={12} />
            <span>{model}</span>
          </span>
        )}

        {/* Git 分支 */}
        {branch && (
          <span className="status-item status-branch" title={`分支: ${branch}${ahead ? ` (ahead ${ahead})` : ''}${behind ? ` (behind ${behind})` : ''}`}>
            <Icon name="git" size={12} />
            <span>{branch}</span>
            {ahead > 0 && <span className="status-badge ahead">↑{ahead}</span>}
            {behind > 0 && <span className="status-badge behind">↓{behind}</span>}
          </span>
        )}

        {/* 同步状态 */}
        {hasUnsavedChanges && (
          <span className="status-item status-unsaved" title="有未保存的更改">
            <span className="status-dot unsaved" />
            <span>未保存</span>
          </span>
        )}
      </div>

      <div className="status-right">
        {/* 问题数量 */}
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

        {/* 光标位置 */}
        {cursorLine !== undefined && cursorColumn !== undefined && (
          <span className="status-item status-position" title="光标位置">
            <span>行 {cursorLine}, 列 {cursorColumn}</span>
          </span>
        )}

        {/* 编码 */}
        <span className="status-item status-encoding" title="文件编码">
          <span>{encoding}</span>
        </span>

        {/* 语言 */}
        {language && (
          <span className="status-item status-language" title="语言类型">
            <span>{language}</span>
          </span>
        )}
      </div>
    </div>
  );
}

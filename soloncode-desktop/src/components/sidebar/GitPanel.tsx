import { useState, useEffect, useCallback } from 'react';
import { Icon } from '../common/Icon';
import type { GitStatus, GitLogEntry } from '../../services/gitService';
import { gitService } from '../../services/gitService';
import './GitPanel.css';

interface GitPanelProps {
  status: GitStatus;
  cwd?: string;
  onCommit: (message: string) => Promise<void>;
  onStage: (path: string) => Promise<void>;
  onUnstage: (path: string) => Promise<void>;
  onPush: () => Promise<void>;
  onPull: () => Promise<void>;
  onDiscard: (path: string) => Promise<void>;
  onFileClick: (path: string) => void;
}

type FeedbackType = 'success' | 'error' | 'info';

export function GitPanel({
  status,
  cwd,
  onCommit,
  onStage,
  onUnstage,
  onPush,
  onPull,
  onDiscard,
  onFileClick
}: GitPanelProps) {
  const [commitMessage, setCommitMessage] = useState('');
  const [isCommitting, setIsCommitting] = useState(false);
  const [isPushing, setIsPushing] = useState(false);
  const [isPulling, setIsPulling] = useState(false);
  const [showBranchDropdown, setShowBranchDropdown] = useState(false);
  const [branches, setBranches] = useState<string[]>([]);
  const [isCheckingOut, setIsCheckingOut] = useState(false);

  // 提交历史
  const [showHistory, setShowHistory] = useState(false);
  const [logEntries, setLogEntries] = useState<GitLogEntry[]>([]);
  const [isLoadingLog, setIsLoadingLog] = useState(false);

  // 操作反馈
  const [feedback, setFeedback] = useState<{ type: FeedbackType; message: string } | null>(null);

  const stagedFiles = status.files.filter(f => f.staged);
  const changedFiles = status.files.filter(f => !f.staged && f.status !== 'untracked');
  const untrackedFiles = status.files.filter(f => f.status === 'untracked');

  // 自动隐藏反馈
  useEffect(() => {
    if (feedback) {
      const timer = setTimeout(() => setFeedback(null), 3000);
      return () => clearTimeout(timer);
    }
  }, [feedback]);

  // 加载分支列表
  const loadBranches = useCallback(async () => {
    if (!cwd) return;
    const list = await gitService.branches(cwd);
    setBranches(list);
  }, [cwd]);

  // 加载提交历史
  const loadHistory = useCallback(async () => {
    if (!cwd) return;
    setIsLoadingLog(true);
    try {
      const entries = await gitService.log(cwd, 15);
      setLogEntries(entries);
    } finally {
      setIsLoadingLog(false);
    }
  }, [cwd]);

  // 切换历史面板时自动加载
  useEffect(() => {
    if (showHistory && cwd) {
      loadHistory();
    }
  }, [showHistory, cwd, loadHistory]);

  async function handleCommit() {
    if (!commitMessage.trim() || stagedFiles.length === 0) return;
    setIsCommitting(true);
    try {
      await onCommit(commitMessage);
      setCommitMessage('');
      setFeedback({ type: 'success', message: '提交成功' });
    } catch (err) {
      setFeedback({ type: 'error', message: `提交失败: ${err}` });
    } finally {
      setIsCommitting(false);
    }
  }

  async function handlePush() {
    setIsPushing(true);
    try {
      await onPush();
      setFeedback({ type: 'success', message: '推送成功' });
    } catch (err) {
      setFeedback({ type: 'error', message: `推送失败: ${err}` });
    } finally {
      setIsPushing(false);
    }
  }

  async function handlePull() {
    setIsPulling(true);
    try {
      await onPull();
      setFeedback({ type: 'success', message: '拉取成功' });
    } catch (err) {
      setFeedback({ type: 'error', message: `拉取失败: ${err}` });
    } finally {
      setIsPulling(false);
    }
  }

  async function handleCheckout(branch: string) {
    if (!cwd || branch === status.branch) {
      setShowBranchDropdown(false);
      return;
    }
    setIsCheckingOut(true);
    try {
      await gitService.checkout(cwd, branch);
      setFeedback({ type: 'success', message: `已切换到 ${branch}` });
    } catch (err) {
      setFeedback({ type: 'error', message: `切换失败: ${err}` });
    } finally {
      setIsCheckingOut(false);
      setShowBranchDropdown(false);
    }
  }

  async function handleDiscard(path: string) {
    try {
      await onDiscard(path);
      setFeedback({ type: 'success', message: `已丢弃 ${path}` });
    } catch (err) {
      setFeedback({ type: 'error', message: `丢弃失败: ${err}` });
    }
  }

  // 状态标签映射
  function getStatusLabel(s: string) {
    const map: Record<string, string> = { modified: 'M', added: 'A', deleted: 'D', untracked: 'U' };
    return map[s] || '?';
  }

  return (
    <div className="git-panel">
      {/* 头部 */}
      <div className="panel-header">
        <span className="panel-title">源代码管理</span>
        <div className="panel-actions">
          <button className="panel-action" title="推送" onClick={handlePush} disabled={isPushing}>
            <Icon name="push" size={16} />
          </button>
          <button className="panel-action" title="拉取" onClick={handlePull} disabled={isPulling}>
            <Icon name="pull" size={16} />
          </button>
          <button
            className="panel-action"
            title="提交历史"
            onClick={() => setShowHistory(!showHistory)}
          >
            <Icon name="chat" size={16} />
          </button>
        </div>
      </div>

      {/* 操作反馈 */}
      {feedback && (
        <div className={`git-feedback ${feedback.type}`}>
          <span>{feedback.message}</span>
        </div>
      )}

      {/* 分支信息 */}
      <div
        className="branch-info"
        onClick={() => { setShowBranchDropdown(!showBranchDropdown); loadBranches(); }}
      >
        <Icon name="git" size={14} />
        <span className="branch-name">{status.branch || '(无分支)'}</span>
        {status.ahead > 0 && <span className="branch-badge ahead">↑{status.ahead}</span>}
        {status.behind > 0 && <span className="branch-badge behind">↓{status.behind}</span>}
        <Icon name="chevron-down" size={12} className="branch-chevron" />
      </div>

      {/* 分支下拉 */}
      {showBranchDropdown && (
        <div className="branch-dropdown">
          {isCheckingOut && <div className="branch-loading">切换中...</div>}
          {branches.map(b => (
            <div
              key={b}
              className={`branch-option${b === status.branch ? ' current' : ''}`}
              onClick={() => handleCheckout(b)}
            >
              <Icon name="git" size={12} />
              <span>{b}</span>
              {b === status.branch && <span className="branch-current-tag">当前</span>}
            </div>
          ))}
          {branches.length === 0 && !isCheckingOut && (
            <div className="branch-empty">无分支</div>
          )}
        </div>
      )}

      {/* 提交区域 */}
      <div className="commit-area">
        <textarea
          className="commit-input"
          placeholder="提交信息..."
          value={commitMessage}
          onChange={(e) => setCommitMessage(e.target.value)}
          rows={3}
        />
        <button
          className="commit-button"
          onClick={handleCommit}
          disabled={isCommitting || !commitMessage.trim() || stagedFiles.length === 0}
        >
          {isCommitting ? '提交中...' : `提交 (${stagedFiles.length})`}
        </button>
      </div>

      {/* 文件列表 */}
      <div className="git-files">
        {stagedFiles.length > 0 && (
          <FileGroup
            title="已暂存的更改"
            count={stagedFiles.length}
            files={stagedFiles}
            onAction={onUnstage}
            onDiscard={handleDiscard}
            onFileClick={onFileClick}
            actionIcon="remove"
            actionTitle="取消暂存"
            getStatusLabel={getStatusLabel}
          />
        )}

        {changedFiles.length > 0 && (
          <FileGroup
            title="已更改"
            count={changedFiles.length}
            files={changedFiles}
            onAction={onStage}
            onDiscard={handleDiscard}
            onFileClick={onFileClick}
            actionIcon="add"
            actionTitle="暂存"
            getStatusLabel={getStatusLabel}
          />
        )}

        {untrackedFiles.length > 0 && (
          <FileGroup
            title="未跟踪"
            count={untrackedFiles.length}
            files={untrackedFiles}
            onAction={onStage}
            onDiscard={handleDiscard}
            onFileClick={onFileClick}
            actionIcon="add"
            actionTitle="暂存"
            getStatusLabel={getStatusLabel}
          />
        )}

        {status.files.length === 0 && (
          <div className="git-empty">
            <span>没有待提交的更改</span>
          </div>
        )}
      </div>

      {/* 提交历史 */}
      {showHistory && (
        <div className="git-history">
          <div className="history-header">
            <span>提交历史</span>
            <button className="panel-action" onClick={loadHistory} title="刷新">
              <Icon name="refresh" size={12} />
            </button>
          </div>
          <div className="history-list">
            {isLoadingLog && <div className="history-loading">加载中...</div>}
            {!isLoadingLog && logEntries.length === 0 && (
              <div className="history-empty">无提交记录</div>
            )}
            {logEntries.map(entry => (
              <div key={entry.hash} className="history-entry">
                <div className="history-message">{entry.message}</div>
                <div className="history-meta">
                  <span className="history-hash">{entry.short_hash}</span>
                  <span className="history-author">{entry.author}</span>
                  <span className="history-date">{entry.date.split(' ')[0]}</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

// ==================== 文件分组子组件 ====================

interface FileGroupProps {
  title: string;
  count: number;
  files: Array<{ path: string; status: string; staged: boolean }>;
  onAction: (path: string) => Promise<void>;
  onDiscard: (path: string) => Promise<void>;
  onFileClick: (path: string) => void;
  actionIcon: string;
  actionTitle: string;
  getStatusLabel: (s: string) => string;
}

function FileGroup({
  title, count, files, onAction, onDiscard, onFileClick, actionIcon, actionTitle, getStatusLabel
}: FileGroupProps) {
  return (
    <div className="file-group">
      <div className="group-header">
        <span>{title}</span>
        <span className="group-count">{count}</span>
      </div>
      {files.map(file => (
        <div key={file.path} className="git-file-item">
          <span className={`status-badge ${file.status}`}>{getStatusLabel(file.status)}</span>
          <span className="file-path" onClick={() => onFileClick(file.path)}>
            {file.path}
          </span>
          <div className="file-actions">
            <button className="file-action-btn discard-btn" onClick={() => onDiscard(file.path)} title="丢弃更改">
              <Icon name="delete" size={12} />
            </button>
            <button className="file-action-btn" onClick={() => onAction(file.path)} title={actionTitle}>
              <Icon name={actionIcon} size={12} />
            </button>
          </div>
        </div>
      ))}
    </div>
  );
}

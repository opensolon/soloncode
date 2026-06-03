import { useState, useEffect, useCallback, useRef } from 'react';
import { gitService, type GitStatus, type DiffLine } from '../services/gitService';

const emptyGitStatus: GitStatus = {
  branch: '',
  ahead: 0,
  behind: 0,
  files: [],
};

export function useGit(activeProjectPath: string | null, activeFilePath: string | null, gitPanelVisible: boolean) {
  const [gitStatus, setGitStatus] = useState<GitStatus>(emptyGitStatus);
  const [diffLines, setDiffLines] = useState<DiffLine[]>([]);
  const prevFilePathRef = useRef<string | null>(null);
  const prevStatusHashRef = useRef<string>('');

  const refreshGitStatus = useCallback(async () => {
    if (activeProjectPath) {
      const status = await gitService.status(activeProjectPath);
      setGitStatus(status);
    } else {
      setGitStatus(emptyGitStatus);
    }
  }, [activeProjectPath]);

  // 只在 Git 面板可见时轮询
  useEffect(() => {
    if (!gitPanelVisible) return;
    refreshGitStatus();
    const timer = setInterval(refreshGitStatus, 5000);
    return () => clearInterval(timer);
  }, [refreshGitStatus, gitPanelVisible]);

  // 获取当前活跃文件的 git diff — 仅在文件切换或相关文件状态变化时刷新
  useEffect(() => {
    if (!activeProjectPath || !activeFilePath) {
      setDiffLines([]);
      prevFilePathRef.current = null;
      return;
    }

    const relPath = activeFilePath.replace(activeProjectPath.replace(/\\/g, '/').replace(/\/$/, '') + '/', '');
    const statusHash = gitStatus.files
      .filter(f => f.path === relPath)
      .map(f => `${f.path}:${f.status}`)
      .join(',');

    if (activeFilePath === prevFilePathRef.current && statusHash === prevStatusHashRef.current) {
      return;
    }

    prevFilePathRef.current = activeFilePath;
    prevStatusHashRef.current = statusHash;

    gitService.diffFile(activeProjectPath, relPath).then(setDiffLines).catch(() => setDiffLines([]));
  }, [activeProjectPath, activeFilePath, gitStatus]);

  return {
    gitStatus,
    diffLines,
    refreshGitStatus,
    setGitStatus,
  };
}

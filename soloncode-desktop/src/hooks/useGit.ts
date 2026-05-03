import { useState, useEffect, useCallback } from 'react';
import { gitService, type GitStatus, type DiffLine } from '../services/gitService';

const emptyGitStatus: GitStatus = {
  branch: '',
  ahead: 0,
  behind: 0,
  files: [],
};

export function useGit(activeProjectPath: string | null, activeFilePath: string | null) {
  const [gitStatus, setGitStatus] = useState<GitStatus>(emptyGitStatus);
  const [diffLines, setDiffLines] = useState<DiffLine[]>([]);

  const refreshGitStatus = useCallback(async () => {
    if (activeProjectPath) {
      const status = await gitService.status(activeProjectPath);
      setGitStatus(status);
    } else {
      setGitStatus(emptyGitStatus);
    }
  }, [activeProjectPath]);

  // 工作区变化时加载 Git 状态 + 定时刷新
  useEffect(() => {
    refreshGitStatus();
    const timer = setInterval(refreshGitStatus, 5000);
    return () => clearInterval(timer);
  }, [refreshGitStatus]);

  // 获取当前活跃文件的 git diff
  useEffect(() => {
    if (!activeProjectPath || !activeFilePath) {
      setDiffLines([]);
      return;
    }
    const relPath = activeFilePath.replace(activeProjectPath.replace(/\\/g, '/').replace(/\/$/, '') + '/', '');
    gitService.diffFile(activeProjectPath, relPath).then(setDiffLines).catch(() => setDiffLines([]));
  }, [activeProjectPath, activeFilePath, gitStatus]);

  return {
    gitStatus,
    diffLines,
    refreshGitStatus,
    setGitStatus,
  };
}

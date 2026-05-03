import { useState, useEffect, useCallback } from 'react';
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

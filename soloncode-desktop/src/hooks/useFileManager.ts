import { useState, useCallback, useRef } from 'react';
import { fileService } from '../services/fileService';

interface OpenFile {
  path: string;
  name: string;
  content: string;
  modified: boolean;
  language: string;
  isImage?: boolean;
  imageBase64?: string;
  imageMimeType?: string;
}

export function useFileManager(activeProjectPath: string | null) {
  const [openFiles, setOpenFiles] = useState<OpenFile[]>([]);
  const [activeFilePath, setActiveFilePath] = useState<string | null>(null);

  const activeFile = openFiles.find(f => f.path === activeFilePath);

  const handleFileSelect = useCallback(async (path: string) => {
    const existingFile = openFiles.find(f => f.path === path);
    if (existingFile) {
      setActiveFilePath(path);
      return;
    }

    const fileName = path.split(/[/\\]/).pop() || '';
    const ext = fileName.split('.').pop() || '';
    const langMap: Record<string, string> = {
      'ts': 'TypeScript', 'tsx': 'TypeScript React',
      'js': 'JavaScript', 'jsx': 'JavaScript React',
      'json': 'JSON', 'css': 'CSS', 'html': 'HTML',
      'md': 'Markdown', 'java': 'Java', 'rs': 'Rust',
      'py': 'Python', 'go': 'Go',
    };

    try {
      const file = await fileService.openFile(path);
      setOpenFiles(prev => [...prev, file]);
      setActiveFilePath(path);
    } catch (err) {
      console.error('[App] 读取文件失败:', err);
      setOpenFiles(prev => [...prev, {
        path, name: fileName,
        content: `// 无法读取文件: ${fileName}`,
        modified: false, language: langMap[ext] || 'Plain Text',
      }]);
      setActiveFilePath(path);
    }
  }, [openFiles]);

  const handleFileClose = useCallback((path: string) => {
    setOpenFiles(prev => {
      const newFiles = prev.filter(f => f.path !== path);
      if (activeFilePath === path && newFiles.length > 0) {
        setActiveFilePath(newFiles[newFiles.length - 1].path);
      } else if (newFiles.length === 0) {
        setActiveFilePath(null);
      }
      return newFiles;
    });
  }, [activeFilePath]);

  const handleContentChange = useCallback((path: string, content: string) => {
    setOpenFiles(prev => prev.map(f =>
      f.path === path ? { ...f, content, modified: true } : f
    ));
  }, []);

  const handleFileSave = useCallback(async (path: string) => {
    const file = openFiles.find(f => f.path === path);
    if (file && activeProjectPath) {
      try {
        await fileService.writeFile(path, file.content);
        setOpenFiles(prev => prev.map(f =>
          f.path === path ? { ...f, modified: false } : f
        ));
      } catch (err) {
        console.error('保存文件失败:', err);
      }
    } else {
      setOpenFiles(prev => prev.map(f =>
        f.path === path ? { ...f, modified: false } : f
      ));
    }
  }, [openFiles, activeProjectPath]);

  const handleSaveCurrentFile = useCallback(() => {
    if (activeFilePath) {
      handleFileSave(activeFilePath);
    }
  }, [activeFilePath, handleFileSave]);

  const clearEditorState = useCallback(() => {
    setOpenFiles([]);
    setActiveFilePath(null);
  }, []);

  return {
    openFiles,
    activeFilePath,
    activeFile,
    setActiveFilePath,
    handleFileSelect,
    handleFileClose,
    handleContentChange,
    handleFileSave,
    handleSaveCurrentFile,
    clearEditorState,
    setOpenFiles,
  };
}

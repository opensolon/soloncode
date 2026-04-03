/**
 * 设置服务 - 持久化应用配置到 localStorage
 */

const STORAGE_KEY = 'soloncode-settings';

export interface AppSettings {
  // AI 配置
  apiUrl: string;
  apiKey: string;
  model: string;
  maxSteps: number;
  // 外观
  theme: 'dark' | 'light';
  fontSize: number;
  language: string;
  // 编辑器
  tabSize: number;
  autoSave: boolean;
  formatOnSave: boolean;
  // 终端
  shell: string;
  terminalFontSize: number;
}

export const defaultSettings: AppSettings = {
  apiUrl: 'https://open.bigmodel.cn/api/paas/v4/chat/completions',
  apiKey: '',
  model: 'glm-4.7',
  maxSteps: 30,
  theme: 'dark',
  fontSize: 14,
  language: 'zh-CN',
  tabSize: 2,
  autoSave: true,
  formatOnSave: true,
  shell: 'bash',
  terminalFontSize: 14,
};

export const settingsService = {
  /**
   * 加载设置（从 localStorage，合并默认值）
   */
  load(): AppSettings {
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      if (stored) {
        const parsed = JSON.parse(stored);
        return { ...defaultSettings, ...parsed };
      }
    } catch (err) {
      console.error('[settingsService] 加载设置失败:', err);
    }
    return { ...defaultSettings };
  },

  /**
   * 保存设置
   */
  save(settings: AppSettings): void {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
    } catch (err) {
      console.error('[settingsService] 保存设置失败:', err);
    }
  },

  /**
   * 清除设置
   */
  clear(): void {
    localStorage.removeItem(STORAGE_KEY);
  },
};

export default settingsService;

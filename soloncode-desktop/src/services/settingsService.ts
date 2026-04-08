/**
 * 设置服务 - 持久化到 IndexedDB 结构化表
 */
import { db, getSetting, setSetting } from '../db';

// ==================== 类型定义 ====================

export interface McpServerConfig {
  id?: number;
  name: string;
  command: string;
  args: string[];
  enabled: boolean;
}

export type ProviderType = 'zhipu' | 'openai' | 'deepseek' | 'claude' | 'custom';

export const PROVIDER_PRESETS: Record<Exclude<ProviderType, 'custom'>, {
  label: string;
  apiUrl: string;
  models: { value: string; label: string }[];
}> = {
  zhipu: {
    label: '智谱 AI',
    apiUrl: 'https://open.bigmodel.cn/api/paas/v4/chat/completions',
    models: [
      { value: 'glm-4.7', label: 'GLM-4.7' },
      { value: 'glm-4-plus', label: 'GLM-4-Plus' },
      { value: 'glm-4-flash', label: 'GLM-4-Flash' },
      { value: 'glm-4-long', label: 'GLM-4-Long' },
    ],
  },
  openai: {
    label: 'OpenAI',
    apiUrl: 'https://api.openai.com/v1/chat/completions',
    models: [
      { value: 'gpt-4o', label: 'GPT-4o' },
      { value: 'gpt-4o-mini', label: 'GPT-4o Mini' },
      { value: 'gpt-4-turbo', label: 'GPT-4 Turbo' },
      { value: 'o1', label: 'O1' },
      { value: 'o3-mini', label: 'O3 Mini' },
    ],
  },
  deepseek: {
    label: 'DeepSeek',
    apiUrl: 'https://api.deepseek.com/v1/chat/completions',
    models: [
      { value: 'deepseek-chat', label: 'DeepSeek Chat' },
      { value: 'deepseek-reasoner', label: 'DeepSeek Reasoner' },
    ],
  },
  claude: {
    label: 'Claude',
    apiUrl: 'https://api.anthropic.com/v1/messages',
    models: [
      { value: 'claude-sonnet-4-20250514', label: 'Claude Sonnet 4' },
      { value: 'claude-opus-4-20250514', label: 'Claude Opus 4' },
      { value: 'claude-haiku-4-20250514', label: 'Claude Haiku 4' },
    ],
  },
};

export interface ModelProvider {
  id: string;
  type: ProviderType;
  name: string;
  apiUrl: string;
  apiKey: string;
  model: string;
  enabled: boolean;
}

/** 常规设置（键值对，存在 globalSettings 表） */
export interface GeneralSettings {
  theme: 'dark' | 'light';
  fontSize: number;
  language: string;
  tabSize: number;
  autoSave: boolean;
  formatOnSave: boolean;
  shell: string;
  terminalFontSize: number;
  activeProviderId: string;
  maxSteps: number;
}

/** 合并后的完整设置（UI 使用） */
export interface AppSettings extends GeneralSettings {
  providers: ModelProvider[];
  mcpServers: McpServerConfig[];
}

let _providerIdCounter = 0;
export function createProvider(type: ProviderType): ModelProvider {
  const preset = type !== 'custom' ? PROVIDER_PRESETS[type] : null;
  _providerIdCounter++;
  return {
    id: `provider_${Date.now()}_${_providerIdCounter}`,
    type,
    name: preset?.label || '自定义',
    apiUrl: preset?.apiUrl || '',
    apiKey: '',
    model: preset?.models[0]?.value || '',
    enabled: true,
  };
}

// ==================== 默认值 ====================

const defaultGeneral: GeneralSettings = {
  theme: 'dark',
  fontSize: 14,
  language: 'zh-CN',
  tabSize: 2,
  autoSave: true,
  formatOnSave: true,
  shell: 'bash',
  terminalFontSize: 14,
  activeProviderId: '',
  maxSteps: 30,
};

// ==================== 服务层 ====================

export const settingsService = {
  /**
   * 加载完整设置（常规 + providers + mcpServers）
   */
  async load(): Promise<AppSettings> {
    // 1. 常规设置
    const general: GeneralSettings = { ...defaultGeneral };

    // 尝试从 globalSettings 表加载新格式
    const gRow = await db.globalSettings.get('general');
    if (gRow) {
      try {
        const parsed = JSON.parse(gRow.value);
        Object.assign(general, parsed);
      } catch { /* ignore */ }
    } else {
      // 迁移：旧格式（整个 appSettings 存为一行 JSON）
      const oldRow = await db.globalSettings.get('appSettings');
      if (oldRow) {
        try {
          const parsed = JSON.parse(oldRow.value);
          // 提取常规字段
          for (const key of Object.keys(defaultGeneral) as (keyof GeneralSettings)[]) {
            if (parsed[key] !== undefined) {
              (general as any)[key] = parsed[key];
            }
          }
          // 迁移 providers 到独立表
          if (Array.isArray(parsed.providers)) {
            await db.providers.clear();
            for (let i = 0; i < parsed.providers.length; i++) {
              await db.providers.put({ ...parsed.providers[i], sortOrder: i });
            }
          }
          // 迁移 mcpServers 到独立表
          if (Array.isArray(parsed.mcpServers)) {
            await db.mcpServers.clear();
            for (const s of parsed.mcpServers) {
              await db.mcpServers.add({ ...s });
            }
          }
          // 写入新格式
          await db.globalSettings.put({ key: 'general', value: JSON.stringify(general) });
          // 删除旧行
          await db.globalSettings.delete('appSettings');
        } catch { /* ignore */ }
      } else {
        // 最后降级：localStorage
        try {
          const stored = localStorage.getItem('soloncode-settings');
          if (stored) {
            const parsed = JSON.parse(stored);
            for (const key of Object.keys(defaultGeneral) as (keyof GeneralSettings)[]) {
              if (parsed[key] !== undefined) (general as any)[key] = parsed[key];
            }
            if (parsed.apiUrl || parsed.apiKey || parsed.model) {
              const p = createProvider('custom');
              p.name = '已迁移';
              p.apiUrl = parsed.apiUrl || '';
              p.apiKey = parsed.apiKey || '';
              p.model = parsed.model || '';
              await db.providers.put({ ...p, sortOrder: 0 });
              general.activeProviderId = p.id;
            }
            await db.globalSettings.put({ key: 'general', value: JSON.stringify(general) });
            localStorage.removeItem('soloncode-settings');
          }
        } catch { /* ignore */ }
      }
    }

    // 2. Providers
    const providerRows = await db.providers.orderBy('sortOrder').toArray();
    const providers: ModelProvider[] = providerRows.map(r => ({
      id: r.id,
      type: r.type as ProviderType,
      name: r.name,
      apiUrl: r.apiUrl,
      apiKey: r.apiKey,
      model: r.model,
      enabled: !!r.enabled,
    }));

    // 3. MCP Servers
    const mcpRows = await db.mcpServers.toArray();
    const mcpServers: McpServerConfig[] = mcpRows.map(r => ({
      id: r.id,
      name: r.name,
      command: r.command,
      args: JSON.parse(r.args || '[]'),
      enabled: !!r.enabled,
    }));

    return { ...general, providers, mcpServers };
  },

  /**
   * 保存完整设置
   */
  async save(settings: AppSettings): Promise<void> {
    const { providers, mcpServers, ...general } = settings;

    // 1. 常规设置
    await db.globalSettings.put({ key: 'general', value: JSON.stringify(general) });

    // 2. Providers：全量覆盖
    await db.providers.clear();
    for (let i = 0; i < providers.length; i++) {
      const p = providers[i];
      await db.providers.put({
        id: p.id,
        type: p.type,
        name: p.name,
        apiUrl: p.apiUrl,
        apiKey: p.apiKey,
        model: p.model,
        enabled: p.enabled ? 1 : 0,
        sortOrder: i,
      });
    }

    // 3. MCP Servers：全量覆盖
    await db.mcpServers.clear();
    for (const s of mcpServers) {
      await db.mcpServers.add({
        name: s.name,
        command: s.command,
        args: JSON.stringify(s.args),
        enabled: s.enabled ? 1 : 0,
        sortOrder: 0,
      });
    }
  },
};

export default settingsService;

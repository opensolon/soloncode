/**
 * 设置服务 - 持久化到 IndexedDB 结构化表
 */
import { db, getSetting, setSetting } from '../db';
import { fileService } from './fileService';

// ==================== 类型定义 ====================

export interface McpServerConfig {
  id?: number;
  name: string;
  command: string;
  args: string[];
  enabled: boolean;
}

export type SkillGroup = 'global' | 'project' | 'claude' | 'codex';

export interface SkillConfig {
  id?: number;
  name: string;
  description: string;
  path: string;
  enabled: boolean;
  source: 'manual' | 'discovered';
  group: SkillGroup;
}

export interface AgentConfig {
  id?: number;
  name: string;
  description: string;
  path: string;
  enabled: boolean;
  source: 'manual' | 'discovered';
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
  availableModels?: { id: string; ownedBy?: string }[];
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
  skills: SkillConfig[];
  agents: AgentConfig[];
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

/**
 * 简易 YAML 解析器 — 仅支持最多两层缩进的 key: value 结构
 * 返回 Record<string, Record<string, string>> 形式的嵌套对象
 */
export function parseSimpleYaml(text: string): Record<string, Record<string, string>> {
  const result: Record<string, Record<string, string>> = {};
  let currentSection = '';

  for (const raw of text.split('\n')) {
    const line = raw.replace(/\r$/, '');
    // 跳过空行和注释
    if (!line.trim() || line.trim().startsWith('#')) continue;

    const indent = line.length - line.trimStart().length;
    const colonIdx = line.indexOf(':');
    if (colonIdx === -1) continue;

    const key = line.slice(0, colonIdx).trim();
    const value = line.slice(colonIdx + 1).trim();

    if (indent === 0) {
      // 顶层 section
      currentSection = key;
      if (!result[currentSection]) {
        result[currentSection] = {};
      }
      // 如果同一行有值（如 `model: gpt-4`），存为 _value
      if (value) {
        result[currentSection]['_value'] = value;
      }
    } else if (currentSection) {
      // 子级 key-value
      result[currentSection][key] = value;
    }
  }
  return result;
}

export const settingsService = {
  /**
   * 从工作区配置文件加载设置
   * 读取 {workspacePath}/.soloncode/config.yml，解析后返回部分 GeneralSettings
   * 文件不存在时返回 null（不报错）；YAML 解析失败时返回 null 并 console.warn
   */
  async loadConfigFile(workspacePath: string): Promise<Partial<GeneralSettings> | null> {
    const configPath = `${workspacePath}/.soloncode/config.yml`;

    // 检查文件是否存在，不存在则静默返回 null
    try {
      const exists = await fileService.pathExists(configPath);
      if (!exists) return null;
    } catch {
      return null;
    }

    // 读取文件内容
    let content: string;
    try {
      content = await fileService.readFile(configPath);
    } catch {
      return null;
    }

    // 解析 YAML
    try {
      const parsed = parseSimpleYaml(content);
      const result: Partial<GeneralSettings> = {};

      // agent 段 → maxSteps
      if (parsed.agent) {
        if (parsed.agent.maxSteps) {
          const steps = parseInt(parsed.agent.maxSteps, 10);
          if (!isNaN(steps) && steps > 0) {
            result.maxSteps = steps;
          }
        }
      }

      // model 段的 provider/apiUrl/model 字段属于 ModelProvider 级别，
      // 不直接映射到 GeneralSettings。调用方（App.tsx）可通过
      // parseConfigFile() 获取原始解析结果来处理 provider 更新。

      return Object.keys(result).length > 0 ? result : null;
    } catch (e) {
      console.warn('[settingsService] 解析配置文件失败:', configPath, e);
      return null;
    }
  },

  /**
   * 从后端获取可用模型列表并生成为 ModelProvider[]
   * 获取后自动注入到 CLI 后端的动态模型配置器
   */
  async fetchModelsFromBackend(
    backendPort: number,
    apiUrl: string,
    apiKey: string,
    existingProviders: ModelProvider[],
    provider: string = 'openai',
  ): Promise<{ providers: ModelProvider[]; activeProviderId: string } | null> {
    try {
      const resp = await fetch(
        `http://localhost:${backendPort}/chat/models/fetch?apiUrl=${encodeURIComponent(apiUrl)}&apiKey=${encodeURIComponent(apiKey)}&provider=${encodeURIComponent(provider)}`,
      );
      if (!resp.ok) return null;

      const result = await resp.json();
      const modelList = result.data;
      if (!Array.isArray(modelList) || modelList.length === 0) return null;

      const existingIds = new Set(existingProviders.map(p => p.id));
      const existingModels = new Set(existingProviders.map(p => p.model));
      const newProviders: ModelProvider[] = [];

      for (const m of modelList) {
        const modelId = m.id as string;
        const providerId = `model_${modelId.replace(/[^a-zA-Z0-9_-]/g, '_')}`;

        if (existingIds.has(providerId)) continue;

        const provider: ModelProvider = {
          id: providerId,
          type: 'custom' as ProviderType,
          name: m.ownedBy || '远程',
          apiUrl,
          apiKey,
          model: modelId,
          enabled: true,
        };
        newProviders.push(provider);

        // 注入到 CLI 后端（仅注入尚未添加的模型）
        if (!existingModels.has(modelId)) {
          try {
            await fetch(`http://localhost:${backendPort}/chat/models/add`, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({
                name: modelId,
                apiUrl,
                apiKey,
                model: modelId,
                provider: 'openai',
                timeout: 'PT120S',
              }),
            });
          } catch (e) {
            console.warn('[settingsService] 注入模型到CLI失败:', modelId, e);
          }
        }
      }

      if (newProviders.length === 0) return null;

      const allProviders = [...existingProviders, ...newProviders];
      const activeProviderId = existingProviders.length === 0 && allProviders.length > 0
        ? allProviders[0].id
        : '';

      return { providers: allProviders, activeProviderId };
    } catch (err) {
      console.warn('[settingsService] 获取远程模型列表失败:', err);
      return null;
    }
  },

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
      availableModels: r.availableModels ? JSON.parse(r.availableModels) : undefined,
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

    // 4. Skills
    const skillRows = await db.skills.toArray();
    const skills: SkillConfig[] = skillRows.map(r => ({
      id: r.id,
      name: r.name,
      description: r.description,
      path: r.path,
      enabled: !!r.enabled,
      source: r.source as 'manual' | 'discovered',
    }));

    return { ...general, providers, mcpServers, skills, agents: [] };
  },

  /**
   * 保存完整设置
   */
  async save(settings: AppSettings): Promise<void> {
    const { providers, mcpServers, skills, agents, ...general } = settings;

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
        availableModels: p.availableModels ? JSON.stringify(p.availableModels) : '',
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

    // 4. Skills：全量覆盖
    await db.skills.clear();
    for (let i = 0; i < (skills || []).length; i++) {
      const s = (skills || [])[i];
      await db.skills.add({
        name: s.name,
        description: s.description,
        path: s.path,
        enabled: s.enabled ? 1 : 0,
        source: s.source,
        sortOrder: i,
      });
    }

    // 5. Agents：全量覆盖
    await db.agents.clear();
    for (let i = 0; i < (agents || []).length; i++) {
      const a = (agents || [])[i];
      await db.agents.add({
        name: a.name,
        description: a.description,
        path: a.path,
        enabled: a.enabled ? 1 : 0,
        source: a.source,
        sortOrder: i,
      });
    }
  },

  /**
   * 扫描工作区 .soloncode/skills/ 目录，自动发现 skills
   * 每个包含 SKILL.md 的子目录视为一个 skill
   */
  async scanSkillsDir(workspacePath: string): Promise<SkillConfig[]> {
    const skillsDir = `${workspacePath}/.soloncode/skills`;
    try {
      const exists = await fileService.pathExists(skillsDir);
      if (!exists) return [];
      const entries = await fileService.listDirectory(skillsDir);
      const skills: SkillConfig[] = [];
      for (const entry of entries) {
        if (entry.isDir) {
          const skillMdPath = `${entry.path}/SKILL.md`;
          const hasMd = await fileService.pathExists(skillMdPath);
          if (hasMd) {
            skills.push({
              name: entry.name,
              description: '',
              path: entry.path,
              enabled: true,
              source: 'discovered',
              group: 'project',
            });
          }
        }
      }
      return skills;
    } catch (err) {
      console.warn('[settingsService] 扫描 skills 目录失败:', err);
      return [];
    }
  },

  /**
   * 扫描工作区中的第三方 skill 目录（如 .claude/commands/, .codex/）
   * 每个 .md 文件视为一个 skill
   */
  async scanThirdPartySkills(workspacePath: string): Promise<SkillConfig[]> {
    const scanConfigs: Array<{ dir: string; group: SkillGroup; ext: string }> = [
      { dir: '.claude/commands', group: 'claude', ext: '.md' },
      { dir: '.codex', group: 'codex', ext: '.md' },
    ];

    const skills: SkillConfig[] = [];

    for (const { dir, group, ext } of scanConfigs) {
      try {
        const fullPath = `${workspacePath}/${dir}`;
        const exists = await fileService.pathExists(fullPath);
        if (!exists) continue;

        const entries = await fileService.listDirectory(fullPath);
        for (const entry of entries) {
          if (!entry.isDir && entry.name.endsWith(ext)) {
            const name = entry.name.slice(0, -ext.length);
            skills.push({
              name,
              description: '',
              path: entry.path,
              enabled: true,
              source: 'discovered',
              group,
            });
          }
        }
      } catch {
        // 目录不存在或无权限，跳过
      }
    }

    return skills;
  },

  /**
   * 扫描工作区 .soloncode/agents/ 目录，自动发现 agents
   * 每个包含 AGENT.md 的子目录视为一个 agent
   */
  async scanAgentsDir(workspacePath: string): Promise<AgentConfig[]> {
    const agentsDir = `${workspacePath}/.soloncode/agents`;
    try {
      const exists = await fileService.pathExists(agentsDir);
      if (!exists) return [];
      const entries = await fileService.listDirectory(agentsDir);
      const agents: AgentConfig[] = [];
      for (const entry of entries) {
        if (entry.isDir) {
          const agentMdPath = `${entry.path}/AGENT.md`;
          const hasMd = await fileService.pathExists(agentMdPath);
          if (hasMd) {
            agents.push({
              name: entry.name,
              description: '',
              path: entry.path,
              enabled: true,
              source: 'discovered',
            });
          }
        }
      }
      return agents;
    } catch (err) {
      console.warn('[settingsService] 扫描 agents 目录失败:', err);
      return [];
    }
  },
};

export default settingsService;

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
  scope?: 'user' | 'workspace';
  type?: 'stdio' | 'sse' | 'streamable';
  url?: string;
  env?: Record<string, string>;
  headers?: Record<string, string>;
  timeout?: string;
}

export interface MountConfig {
  alias: string;
  path: string;
  type: 'SKILLS' | 'AGENTS' | 'FILES';
  scope: 'user' | 'workspace';
  writeable: boolean;
  description?: string;
}

export interface OpenApiServerConfig {
  name: string;
  baseUrl: string;
  docUrl: string;
  scope: 'user' | 'workspace';
  headers?: Record<string, string>;
  enabled: boolean;
}

export interface LspServerConfig {
  name: string;
  command: string;
  extensions: string[];
  scope: 'user' | 'workspace';
  env?: Record<string, string>;
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

export type ProviderType = '' | 'openai' | 'openai-responses' | 'anthropic' | 'ollama';

export const PROVIDER_PRESETS: Record<ProviderType, {
  label: string;
  apiUrl: string;
  models: { value: string; label: string }[];
}> = {
  '': {
    label: '自动检测（须填写完整地址）',
    apiUrl: '',
    models: [],
  },
  openai: {
    label: 'OpenAI BaseUrl / 兼容接口',
    apiUrl: 'https://api.openai.com/v1',
    models: [],
  },
  'openai-responses': {
    label: 'OpenAI Responses BaseUrl / 兼容接口',
    apiUrl: 'https://api.openai.com/v1/responses',
    models: [],
  },
  anthropic: {
    label: 'Anthropic BaseUrl / 兼容接口',
    apiUrl: 'https://api.anthropic.com',
    models: [],
  },
  ollama: {
    label: 'Ollama BaseUrl / 兼容接口',
    apiUrl: 'http://localhost:11434',
    models: [],
  },
};

export function normalizeProviderType(type?: string | null): ProviderType {
  if (type === 'openai' || type === 'openai-responses' || type === 'anthropic' || type === 'ollama' || type === '') {
    return type;
  }
  if (type === 'claude') {
    return 'anthropic';
  }
  return '';
}

export interface ModelProvider {
  id: string;
  type: ProviderType;
  name: string;
  apiUrl: string;
  apiKey: string;
  model: string;
  enabled: boolean;
  scope?: 'user' | 'workspace';
  timeout?: string;
  contextLength?: number;
  defaultOptions?: string;
  availableModels?: { id: string; ownedBy?: string; contextLength?: number }[];
}

/** 常规设置（键值对，存在 globalSettings 表） */
export interface GeneralSettings {
  theme: 'dark' | 'light';
  editorTheme: string;
  fontSize: number;
  language: string;
  autoCheckUpdates: boolean;
  lastUpdateCheckAt: string;
  tabSize: number;
  autoSave: boolean;
  formatOnSave: boolean;
  shell: string;
  terminalFontSize: number;
  activeProviderId: string;
  maxSteps: number;
  cliPort: number;
  sessionWindowSize: number;
  compressionMaxMessages: number;
  compressionMaxTokens: number;
  sandboxEnabled: boolean;
  sandboxAllowUserHome: boolean;
  sandboxSystemRestrict: boolean;
  memoryEnabled: boolean;
  memoryIsolation: boolean;
  modelRetries: number;
  mcpRetries: number;
  apiRetries: number;
  cliPrintSimplified: boolean;
  webAuthUser: string;
  webAuthPass: string;
  bashAsyncEnabled: boolean;
  subagentEnabled: boolean;
  mcpEnabled: boolean;
  openApiEnabled: boolean;
  lspEnabled: boolean;
  loopDefaultMaxTokens: number;
  loopDefaultMaxDuration: number;
  loopStagnationThreshold: number;
  loopMaxConsecutiveErrors: number;
  loopPauseAutoAbandonHours: number;
  loopBudgetWarningPercent: number;
  loopBudgetCriticalPercent: number;
  loopValidatorEnabled: boolean;
  disallowedTools: string[];
  mounts: MountConfig[];
  openApiServers: OpenApiServerConfig[];
  lspServers: LspServerConfig[];
  skillPrompt: string;
  agentPrompt: string;
  gitPrompt: string;
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
  const preset = PROVIDER_PRESETS[type];
  _providerIdCounter++;
  return {
    id: `provider_${Date.now()}_${_providerIdCounter}`,
    type,
    name: preset.label,
    apiUrl: preset.apiUrl,
    apiKey: '',
    model: preset.models[0]?.value || '',
    enabled: true,
    scope: 'user',
    timeout: 'PT120S',
    contextLength: 128000,
    defaultOptions: '',
  };
}

// ==================== 默认值 ====================

type ChatModelConfig = {
  apiUrl: string;
  apiKey?: string;
  provider?: string;
  model?: string;
};

function normalizeBaseUrl(value?: string) {
  return (value || '').trim().replace(/\/+$/, '');
}

function configuredProviderId(apiUrl: string, providerType: ProviderType) {
  const raw = `${providerType || 'auto'}_${normalizeBaseUrl(apiUrl)}`;
  const key = raw.replace(/[^a-zA-Z0-9_-]/g, '_').replace(/_+/g, '_').replace(/^_+|_+$/g, '');
  return `provider_${(key || 'remote').slice(0, 96)}`;
}

function providerNameFromConfig(apiUrl: string, providerType: ProviderType, model?: string) {
  try {
    return new URL(apiUrl).host || model || PROVIDER_PRESETS[providerType].label;
  } catch {
    return model || PROVIDER_PRESETS[providerType].label;
  }
}

function mergeAvailableModels(
  current: ModelProvider['availableModels'],
  next: ModelProvider['availableModels'],
  selectedModel?: string,
) {
  const models = new Map<string, { id: string; ownedBy?: string; contextLength?: number }>();
  for (const item of current || []) {
    if (item?.id) models.set(item.id, item);
  }
  for (const item of next || []) {
    if (item?.id) models.set(item.id, item);
  }
  if (selectedModel && !models.has(selectedModel)) {
    models.set(selectedModel, { id: selectedModel });
  }
  return Array.from(models.values());
}

function sameConfiguredProvider(provider: ModelProvider, config: ChatModelConfig, providerType: ProviderType) {
  return normalizeBaseUrl(provider.apiUrl) === normalizeBaseUrl(config.apiUrl)
    && normalizeProviderType(provider.type) === providerType;
}

function upsertConfiguredProvider(
  existingProviders: ModelProvider[],
  config: ChatModelConfig,
  availableModels?: ModelProvider['availableModels'],
): { providers: ModelProvider[]; providerId: string; changed: boolean } {
  const apiUrl = normalizeBaseUrl(config.apiUrl);
  const providerType = normalizeProviderType(config.provider);
  const selectedModel = (config.model || '').trim();
  if (!apiUrl) {
    return { providers: existingProviders, providerId: '', changed: false };
  }

  const nextProviders = [...existingProviders];
  const index = nextProviders.findIndex(p => sameConfiguredProvider(p, { ...config, apiUrl }, providerType));
  const baseId = configuredProviderId(apiUrl, providerType);

  if (index < 0) {
    let id = baseId;
    let suffix = 1;
    while (nextProviders.some(p => p.id === id)) {
      suffix += 1;
      id = `${baseId}_${suffix}`;
    }
    const provider: ModelProvider = {
      id,
      type: providerType,
      name: providerNameFromConfig(apiUrl, providerType, selectedModel),
      apiUrl,
      apiKey: config.apiKey || '',
      model: selectedModel || availableModels?.[0]?.id || '',
      enabled: true,
      scope: 'user',
      timeout: 'PT120S',
      contextLength: availableModels?.find(m => m.id === selectedModel)?.contextLength || 128000,
      defaultOptions: '',
      availableModels: mergeAvailableModels(undefined, availableModels, selectedModel),
    };
    nextProviders.push(provider);
    return { providers: nextProviders, providerId: provider.id, changed: true };
  }

  const current = nextProviders[index];
  const mergedModels = mergeAvailableModels(current.availableModels, availableModels, selectedModel || current.model);
  const selected = mergedModels.find(m => m.id === (selectedModel || current.model));
  const updated: ModelProvider = {
    ...current,
    type: providerType,
    name: current.name || providerNameFromConfig(apiUrl, providerType, selectedModel),
    apiUrl,
    apiKey: config.apiKey !== undefined ? config.apiKey : current.apiKey,
    model: selectedModel || current.model || mergedModels[0]?.id || '',
    enabled: current.enabled !== false,
    scope: current.scope || 'user',
    timeout: current.timeout || 'PT120S',
    contextLength: selected?.contextLength || current.contextLength || 128000,
    availableModels: mergedModels.length > 0 ? mergedModels : undefined,
  };

  const changed = JSON.stringify(current) !== JSON.stringify(updated);
  if (changed) nextProviders[index] = updated;
  return { providers: nextProviders, providerId: updated.id, changed };
}

export const DEFAULT_PROMPTS: Record<'skillPrompt' | 'agentPrompt' | 'gitPrompt', string> = {
  skillPrompt: `请帮我创建一个名为「{name}」的 Skill。
{description}

请直接输出完整的 SKILL.md 文件内容（纯 Markdown 格式，不要用代码块包裹）。

格式参考：

---
name: {name}
description: {description}
---

# {name}

## 功能描述
[详细描述这个 Skill 的功能和用途]

## 使用场景
[列出适用的使用场景]

## 规则与约束
[列出规则和约束条件]

## 示例
[提供使用示例]`,
  agentPrompt: `请根据以下需求创建一个 Agent，并自动生成简短、清晰且能概括职责的名称。
{description}

请直接输出完整的 AGENT.md 文件内容（纯 Markdown 格式，不要用代码块包裹）。
名称只能包含文字、数字、短横线和下划线，并写入 YAML frontmatter 的 name 字段。

格式参考：

---
name: <自动生成的名称>
description: <简短描述 Agent 的职责>
---

# <自动生成的名称> Agent

## 角色定义
[描述这个 Agent 的角色和能力]

## 工具权限
[列出需要的工具和权限]

## 行为准则
[列出行为准则和约束]

## 工作流程
[描述典型工作流程]`,
  gitPrompt: [
    '你是一个 Git 提交信息生成助手。请根据以下 diff 内容生成一条高质量的 commit message。',
    '',
    '## Commit 格式规范',
    '',
    '<type>(<scope>): <subject>',
    '',
    '<body>',
    '',
    '<footer>',
    '',
    '### 各部分说明',
    '- **type（必填）**：提交类型，必须是以下之一：',
    '  - feat: 新功能 | fix: Bug修复 | docs: 文档变更 | style: 代码格式（不影响逻辑）',
    '  - refactor: 重构 | perf: 性能优化 | test: 测试相关 | chore: 构建/工具 | ci: CI/CD',
    '- **scope（可选）**：影响范围，如 api、ui、db、config、auth 等模块名',
    '- **subject（必填）**：简短描述，不超过 50 字，不加句号',
    '- **body（可选）**：详细说明做了什么、为什么做',
    '- **footer（可选）**：关联 issue 或标注破坏性变更（BREAKING CHANGE）',
    '',
    '## 输出要求',
    '1. 语言：与 diff 注释语言保持一致（中文注释用中文，英文注释用英文）',
    '2. 语气：祈使语气、现在时（"add" 而非 "added"）',
    '3. 不要添加签名、emoji、Co-Authored-By 等额外信息',
    '4. 主题行不超过 72 字符',
    '',
    'diff 内容：',
    '{diff}',
  ].join('\n'),
};

const defaultGeneral: GeneralSettings = {
  theme: 'dark',
  editorTheme: 'auto',
  fontSize: 14,
  language: 'zh-CN',
  autoCheckUpdates: false,
  lastUpdateCheckAt: '',
  tabSize: 2,
  autoSave: true,
  formatOnSave: true,
  shell: 'bash',
  terminalFontSize: 14,
  activeProviderId: '',
  maxSteps: 30,
  cliPort: 4808,
  sessionWindowSize: 8,
  compressionMaxMessages: 40,
  compressionMaxTokens: 64000,
  sandboxEnabled: true,
  sandboxAllowUserHome: false,
  sandboxSystemRestrict: true,
  memoryEnabled: true,
  memoryIsolation: true,
  modelRetries: 3,
  mcpRetries: 3,
  apiRetries: 3,
  cliPrintSimplified: true,
  webAuthUser: '',
  webAuthPass: '',
  bashAsyncEnabled: false,
  subagentEnabled: true,
  mcpEnabled: true,
  openApiEnabled: true,
  lspEnabled: true,
  loopDefaultMaxTokens: 0,
  loopDefaultMaxDuration: 0,
  loopStagnationThreshold: 3,
  loopMaxConsecutiveErrors: 3,
  loopPauseAutoAbandonHours: 24,
  loopBudgetWarningPercent: 70,
  loopBudgetCriticalPercent: 85,
  loopValidatorEnabled: false,
  disallowedTools: [],
  mounts: [],
  openApiServers: [],
  lspServers: [],
  skillPrompt: DEFAULT_PROMPTS.skillPrompt,
  agentPrompt: DEFAULT_PROMPTS.agentPrompt,
  gitPrompt: DEFAULT_PROMPTS.gitPrompt,
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

function parseJsonArray(value?: string): string[] {
  try {
    const parsed = JSON.parse(value || '[]');
    return Array.isArray(parsed) ? parsed.map(String) : [];
  } catch {
    return [];
  }
}

function parseJsonRecord(value?: string): Record<string, string> | undefined {
  try {
    const parsed = JSON.parse(value || '{}');
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return undefined;
    return Object.fromEntries(Object.entries(parsed).map(([key, val]) => [key, String(val)]));
  } catch {
    return undefined;
  }
}

function normalizeMcpType(type?: string): 'stdio' | 'sse' | 'streamable' {
  return type === 'sse' || type === 'streamable' ? type : 'stdio';
}

type BackendResult<T> = { code?: number; data?: T; message?: string };
type RuntimeSettingsSection = 'general' | 'loop' | 'permission' | 'mounts' | 'mcp' | 'openapi' | 'lsp';

const CORE_RUNTIME_SETTINGS_SECTIONS: RuntimeSettingsSection[] = ['general', 'mounts', 'mcp', 'openapi'];
const FULL_RUNTIME_SETTINGS_SECTIONS: RuntimeSettingsSection[] = ['general', 'loop', 'permission', 'mounts', 'mcp', 'openapi', 'lsp'];

function backendBaseUrl(backendPort: number): string {
  return `http://localhost:${backendPort}`;
}

async function backendGet<T>(backendPort: number, path: string): Promise<T | null> {
  const resp = await fetch(`${backendBaseUrl(backendPort)}${path}`, { cache: 'no-store' });
  if (!resp.ok) return null;
  const result = await resp.json() as BackendResult<T>;
  return result.code === 200 ? (result.data ?? null) : null;
}

async function backendPost(backendPort: number, path: string, body: Record<string, unknown>): Promise<boolean> {
  const resp = await fetch(`${backendBaseUrl(backendPort)}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!resp.ok) return false;
  const result = await resp.json().catch(() => ({ code: 200 })) as BackendResult<unknown>;
  return result.code === undefined || result.code === 200;
}

function normalizeScope(scope?: string): 'user' | 'workspace' {
  return scope === 'workspace' ? 'workspace' : 'user';
}

function compactRecord(record?: Record<string, string>): Record<string, string> | undefined {
  if (!record) return undefined;
  const entries = Object.entries(record).filter(([key, value]) => key.trim() && String(value).trim());
  return entries.length > 0 ? Object.fromEntries(entries) : undefined;
}

function mapGeneralToBackend(settings: AppSettings) {
  return {
    sessionWindowSize: settings.sessionWindowSize,
    summaryWindowSize: settings.compressionMaxMessages,
    summaryWindowToken: settings.compressionMaxTokens,
    sandboxMode: settings.sandboxEnabled,
    sandboxAllowUserHome: settings.sandboxAllowUserHome,
    sandboxSystemRestrict: settings.sandboxSystemRestrict,
    apiRetries: settings.apiRetries,
    mcpRetries: settings.mcpRetries,
    modelRetries: settings.modelRetries,
    memoryEnabled: settings.memoryEnabled,
    memoryIsolation: settings.memoryIsolation,
    mcpEnabled: settings.mcpEnabled,
    openApiEnabled: settings.openApiEnabled,
    bashAsyncEnabled: settings.bashAsyncEnabled,
    subagentEnabled: settings.subagentEnabled,
    lspEnabled: settings.lspEnabled,
    cliPrintSimplified: settings.cliPrintSimplified,
    webAuthUser: settings.webAuthUser || null,
    webAuthPass: settings.webAuthPass || null,
  };
}

function mapLoopToBackend(settings: AppSettings) {
  return {
    defaultMaxTokens: settings.loopDefaultMaxTokens || 0,
    defaultMaxDurationMinutes: settings.loopDefaultMaxDuration || 0,
    stagnationThreshold: settings.loopStagnationThreshold,
    maxConsecutiveErrors: settings.loopMaxConsecutiveErrors,
    pauseAutoAbandonHours: settings.loopPauseAutoAbandonHours,
    budgetWarningPercent: settings.loopBudgetWarningPercent,
    budgetCriticalPercent: settings.loopBudgetCriticalPercent,
    validatorEnabled: settings.loopValidatorEnabled,
  };
}

function mapMcpToBackend(server: McpServerConfig): Record<string, unknown> {
  const type = server.type || 'stdio';
  const body: Record<string, unknown> = {
    name: server.name,
    type,
    enabled: server.enabled,
    scope: server.scope || 'user',
  };
  if (type === 'stdio') {
    body.command = server.command;
    body.args = server.args || [];
    const env = compactRecord(server.env);
    if (env) body.env = env;
  } else {
    body.url = server.url || '';
    const headers = compactRecord(server.headers);
    if (headers) body.headers = headers;
    if (server.timeout) body.timeout = server.timeout;
  }
  return body;
}

function mapOpenApiToBackend(server: OpenApiServerConfig): Record<string, unknown> {
  const body: Record<string, unknown> = {
    name: server.name,
    apiBaseUrl: server.baseUrl,
    docUrl: server.docUrl,
    enabled: server.enabled,
    scope: server.scope || 'user',
  };
  const headers = compactRecord(server.headers);
  if (headers) body.headers = headers;
  return body;
}

function mapLspToBackend(server: LspServerConfig): Record<string, unknown> {
  const body: Record<string, unknown> = {
    name: server.name,
    command: server.command,
    extensions: server.extensions || [],
    enabled: server.enabled,
    scope: server.scope || 'user',
  };
  const env = compactRecord(server.env);
  if (env) body.env = env;
  return body;
}

async function syncNamedServers<T extends { name: string; enabled?: boolean }>(
  backendPort: number,
  basePath: string,
  remoteServers: any[],
  localServers: T[],
  mapBody: (server: T) => Record<string, unknown>,
): Promise<void> {
  const remoteNames = new Set(remoteServers.map(server => server.name).filter(Boolean));
  const localNames = new Set(localServers.map(server => server.name).filter(Boolean));

  for (const server of localServers) {
    if (!server.name) continue;
    const exists = remoteNames.has(server.name);
    const body = mapBody(server);
    await backendPost(backendPort, `${basePath}/${exists ? 'update' : 'add'}`, body);
    if (exists && server.enabled !== undefined) {
      await backendPost(backendPort, `${basePath}/toggle`, { name: server.name, enabled: server.enabled });
    }
  }

  for (const remote of remoteServers) {
    if (remote.name && !localNames.has(remote.name)) {
      await backendPost(backendPort, `${basePath}/remove`, { name: remote.name });
    }
  }
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

  async loadRuntimeSettings(backendPort: number, sections: RuntimeSettingsSection[] = CORE_RUNTIME_SETTINGS_SECTIONS): Promise<Partial<AppSettings> | null> {
    try {
      const enabledSections = new Set(sections);
      const [general, loop, permission, mounts, mcpServers, openApiServers, lspServers] = await Promise.all([
        enabledSections.has('general') ? backendGet<Record<string, any>>(backendPort, '/web/settings/general') : Promise.resolve(null),
        enabledSections.has('loop') ? backendGet<Record<string, any>>(backendPort, '/web/settings/loop') : Promise.resolve(null),
        enabledSections.has('permission') ? backendGet<Record<string, any>>(backendPort, '/web/settings/permission') : Promise.resolve(null),
        enabledSections.has('mounts') ? backendGet<any[]>(backendPort, '/web/settings/mounts') : Promise.resolve(null),
        enabledSections.has('mcp') ? backendGet<any[]>(backendPort, '/web/settings/mcp/servers') : Promise.resolve(null),
        enabledSections.has('openapi') ? backendGet<any[]>(backendPort, '/web/settings/openapi/servers') : Promise.resolve(null),
        enabledSections.has('lsp') ? backendGet<any[]>(backendPort, '/web/settings/lsp/servers') : Promise.resolve(null),
      ]);

      const result: Partial<AppSettings> = {};
      if (general) {
        Object.assign(result, {
          sessionWindowSize: Number(general.sessionWindowSize) || defaultGeneral.sessionWindowSize,
          compressionMaxMessages: Number(general.summaryWindowSize) || defaultGeneral.compressionMaxMessages,
          compressionMaxTokens: Number(general.summaryWindowToken) || defaultGeneral.compressionMaxTokens,
          sandboxEnabled: !!general.sandboxMode,
          sandboxAllowUserHome: general.sandboxAllowUserHome !== false,
          sandboxSystemRestrict: !!general.sandboxSystemRestrict,
          apiRetries: Number(general.apiRetries) || defaultGeneral.apiRetries,
          mcpRetries: Number(general.mcpRetries) || defaultGeneral.mcpRetries,
          modelRetries: Number(general.modelRetries) || defaultGeneral.modelRetries,
          memoryEnabled: general.memoryEnabled !== false,
          memoryIsolation: general.memoryIsolation !== false,
          mcpEnabled: general.mcpEnabled !== false,
          openApiEnabled: general.openApiEnabled !== false,
          bashAsyncEnabled: !!general.bashAsyncEnabled,
          subagentEnabled: general.subagentEnabled !== false,
          lspEnabled: !!general.lspEnabled,
          cliPrintSimplified: general.cliPrintSimplified !== false,
          webAuthUser: general.webAuthUser || '',
          webAuthPass: general.webAuthPass || '',
        });
      }
      if (loop) {
        Object.assign(result, {
          loopDefaultMaxTokens: Number(loop.defaultMaxTokens) || 0,
          loopDefaultMaxDuration: Number(loop.defaultMaxDurationMinutes) || 0,
          loopStagnationThreshold: Number(loop.stagnationThreshold) || defaultGeneral.loopStagnationThreshold,
          loopMaxConsecutiveErrors: Number(loop.maxConsecutiveErrors) || defaultGeneral.loopMaxConsecutiveErrors,
          loopPauseAutoAbandonHours: Number(loop.pauseAutoAbandonHours) || defaultGeneral.loopPauseAutoAbandonHours,
          loopBudgetWarningPercent: Number(loop.budgetWarningPercent) || defaultGeneral.loopBudgetWarningPercent,
          loopBudgetCriticalPercent: Number(loop.budgetCriticalPercent) || defaultGeneral.loopBudgetCriticalPercent,
          loopValidatorEnabled: loop.validatorEnabled !== false,
        });
      }
      if (Array.isArray(permission?.disallowedTools)) {
        result.disallowedTools = permission.disallowedTools.map(String);
      }
      if (Array.isArray(mounts)) {
        result.mounts = mounts.map(item => ({
          alias: item.alias || '',
          path: item.path || '',
          type: item.type || 'SKILLS',
          scope: normalizeScope(item.scope),
          writeable: !!item.writeable,
          description: item.description || '',
        }));
      }
      if (Array.isArray(mcpServers)) {
        result.mcpServers = mcpServers.map(item => ({
          name: item.name || '',
          command: Array.isArray(item.command) ? item.command.join(' ') : (item.command || ''),
          args: Array.isArray(item.args) ? item.args.map(String) : [],
          enabled: item.enabled !== false,
          scope: normalizeScope(item.scope),
          type: normalizeMcpType(item.type),
          url: item.url || '',
          env: item.env || {},
          headers: item.headers || {},
          timeout: item.timeout || '',
        }));
      }
      if (Array.isArray(openApiServers)) {
        result.openApiServers = openApiServers.map(item => ({
          name: item.name || '',
          baseUrl: item.apiBaseUrl || '',
          docUrl: item.docUrl || '',
          scope: normalizeScope(item.scope),
          headers: item.headers || {},
          enabled: item.enabled !== false,
        }));
      }
      if (Array.isArray(lspServers)) {
        result.lspServers = lspServers.map(item => ({
          name: item.name || '',
          command: Array.isArray(item.command) ? item.command.join(' ') : (item.command || ''),
          extensions: Array.isArray(item.extensions) ? item.extensions.map(String) : [],
          scope: normalizeScope(item.scope),
          env: item.env || {},
          enabled: item.enabled !== false,
        }));
      }

      return Object.keys(result).length > 0 ? result : null;
    } catch (err) {
      console.warn('[settingsService] 同步读取后端设置失败:', err);
      return null;
    }
  },

  runtimeSettingsSections: {
    core: CORE_RUNTIME_SETTINGS_SECTIONS,
    full: FULL_RUNTIME_SETTINGS_SECTIONS,
  },

  ensureConfiguredProvider(
    existingProviders: ModelProvider[],
    config: ChatModelConfig,
  ): { providers: ModelProvider[]; providerId: string; changed: boolean } {
    return upsertConfiguredProvider(existingProviders, config);
  },

  async syncRuntimeSettings(backendPort: number, settings: AppSettings): Promise<void> {
    try {
      await Promise.all([
        backendPost(backendPort, '/web/settings/general/save', mapGeneralToBackend(settings)),
        backendPost(backendPort, '/web/settings/loop/save', mapLoopToBackend(settings)),
        backendPost(backendPort, '/web/settings/permission/save', { disallowedTools: settings.disallowedTools || [] }),
      ]);

      const [remoteMounts, remoteMcpServers, remoteOpenApiServers, remoteLspServers] = await Promise.all([
        backendGet<any[]>(backendPort, '/web/settings/mounts'),
        backendGet<any[]>(backendPort, '/web/settings/mcp/servers'),
        backendGet<any[]>(backendPort, '/web/settings/openapi/servers'),
        backendGet<any[]>(backendPort, '/web/settings/lsp/servers'),
      ]);

      const remoteMountMap = new Map((remoteMounts || []).map(item => [item.alias, item]));
      for (const mount of settings.mounts || []) {
        if (!mount.alias || !mount.path) continue;
        const exists = remoteMountMap.has(mount.alias);
        await backendPost(backendPort, exists ? '/web/settings/mounts/update' : '/web/settings/mounts/add', {
          alias: mount.alias,
          path: mount.path,
          type: mount.type || 'SKILLS',
          writeable: !!mount.writeable,
          description: mount.description || '',
          scope: mount.scope || 'user',
        });
      }
      for (const remote of remoteMounts || []) {
        if (remote.system === true) continue;
        if (!(settings.mounts || []).some(mount => mount.alias === remote.alias)) {
          await backendPost(backendPort, '/web/settings/mounts/remove', { alias: remote.alias });
        }
      }

      await syncNamedServers(backendPort, '/web/settings/mcp/servers', remoteMcpServers || [], settings.mcpServers || [], mapMcpToBackend);
      await syncNamedServers(backendPort, '/web/settings/openapi/servers', remoteOpenApiServers || [], settings.openApiServers || [], mapOpenApiToBackend);
      await syncNamedServers(backendPort, '/web/settings/lsp/servers', remoteLspServers || [], settings.lspServers || [], mapLspToBackend);
    } catch (err) {
      console.warn('[settingsService] 同步保存后端设置失败:', err);
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
    provider: string = '',
    model: string = '',
  ): Promise<{ providers: ModelProvider[]; activeProviderId: string } | null> {
    try {
      const resp = await fetch(
        `http://localhost:${backendPort}/desktop/chat/models/fetch?apiUrl=${encodeURIComponent(apiUrl)}&apiKey=${encodeURIComponent(apiKey)}&provider=${encodeURIComponent(provider)}&model=${encodeURIComponent(model)}`,
      );
      if (!resp.ok) return null;

      const result = await resp.json();
      const modelList = result.data;
      if (!Array.isArray(modelList) || modelList.length === 0) return null;

      const existingIds = new Set(existingProviders.map(p => p.id));
      const existingModels = new Set(existingProviders.map(p => p.model));
      const newProviders: ModelProvider[] = [];
      const availableModels = modelList.map(m => ({
        id: String(m.id),
        ownedBy: m.ownedBy || m.owned_by,
        contextLength: Number(m.contextLength || m.context_length) || undefined,
      }));
      const merged = upsertConfiguredProvider(
        existingProviders,
        { apiUrl, apiKey, provider, model },
        availableModels,
      );

      for (const m of modelList) {
        const modelId = m.id as string;
        const providerId = `model_${modelId.replace(/[^a-zA-Z0-9_-]/g, '_')}`;
        const contextLength = Number(m.contextLength || m.context_length) || undefined;

        if (existingIds.has(providerId)) continue;

        const modelProvider: ModelProvider = {
          id: providerId,
          type: normalizeProviderType(provider),
          name: m.ownedBy || '远程',
          apiUrl,
          apiKey,
          model: modelId,
          enabled: true,
          scope: 'user',
          timeout: 'PT120S',
          contextLength,
          defaultOptions: '',
        };
        newProviders.push(modelProvider);

        // 注入到 CLI 后端（仅注入尚未添加的模型）
        if (!existingModels.has(modelId)) {
          try {
            await fetch(`http://localhost:${backendPort}/desktop/chat/models/add`, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({
                name: modelId,
                apiUrl,
                apiKey,
                model: modelId,
                provider: normalizeProviderType(provider),
                standard: normalizeProviderType(provider),
                scope: modelProvider.scope,
                timeout: 'PT120S',
                contextLength: modelProvider.contextLength,
              }),
            });
          } catch (e) {
            console.warn('[settingsService] 注入模型到CLI失败:', modelId, e);
          }
        }
      }

      if (!merged.changed && newProviders.length === 0) return null;

      const allProviders = [...merged.providers, ...newProviders.filter(p => !merged.providers.some(existing => existing.id === p.id))];
      const activeProviderId = existingProviders.length === 0 && merged.providerId
        ? merged.providerId
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
              const p = createProvider('');
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
      type: normalizeProviderType(r.type),
      name: r.name,
      apiUrl: r.apiUrl,
      apiKey: r.apiKey,
      model: r.model,
      enabled: !!r.enabled,
      scope: ((r as any).scope === 'workspace' ? 'workspace' : 'user'),
      timeout: (r as any).timeout || 'PT120S',
      contextLength: Number((r as any).contextLength) || 128000,
      defaultOptions: (r as any).defaultOptions || '',
      availableModels: r.availableModels ? JSON.parse(r.availableModels) : undefined,
    }));

    // 3. MCP Servers
    const mcpRows = await db.mcpServers.toArray();
    const mcpServers: McpServerConfig[] = mcpRows.map(r => ({
      id: r.id,
      name: r.name,
      command: r.command,
      args: parseJsonArray(r.args),
      scope: r.scope === 'workspace' ? 'workspace' : 'user',
      type: normalizeMcpType(r.type),
      url: r.url || '',
      env: parseJsonRecord(r.env),
      headers: parseJsonRecord(r.headers),
      timeout: r.timeout || '',
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
      group: 'global',
    }));

    // 5. Agents
    const agentRows = await db.agents.toArray();
    const agents: AgentConfig[] = agentRows.map(r => ({
      id: r.id,
      name: r.name,
      description: r.description,
      path: r.path,
      enabled: !!r.enabled,
      source: r.source as 'manual' | 'discovered',
    }));

    return { ...general, providers, mcpServers, skills, agents };
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
        type: normalizeProviderType(p.type),
        name: p.name,
        apiUrl: p.apiUrl,
        apiKey: p.apiKey,
        model: p.model,
        enabled: p.enabled ? 1 : 0,
        sortOrder: i,
        scope: p.scope || 'user',
        timeout: p.timeout || 'PT120S',
        contextLength: p.contextLength || 128000,
        defaultOptions: p.defaultOptions || '',
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
        scope: s.scope || 'user',
        type: s.type || 'stdio',
        url: s.url || '',
        env: s.env ? JSON.stringify(s.env) : '',
        headers: s.headers ? JSON.stringify(s.headers) : '',
        timeout: s.timeout || '',
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

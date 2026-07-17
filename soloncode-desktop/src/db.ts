import Dexie, { type Table } from 'dexie';

// ==================== 消息 & 会话 ====================

export interface DbMessage {
  id?: number;
  conversationId: string | number;
  role: string;
  timestamp: string;
  contents: string;
  workspacePath?: string;
}

export interface DbConversation {
  id?: number;
  title: string;
  timestamp: string;
  status: string;
  isPermanent?: boolean;
  icon?: string;
  workspacePath?: string; // 关联的项目路径
}

// ==================== 项目 ====================

export interface DbProject {
  id: string;        // workspace path (natural key)
  name: string;      // 文件夹名称
  sortOrder: number;
  addedAt: string;   // ISO timestamp
}

// ==================== 自动化 ====================

export type AutomationReasoningEffort = 'low' | 'medium' | 'high' | 'max';

export interface DbAutomation {
  id?: number;
  title: string;
  prompt: string;
  projectId: string;
  projectName: string;
  modelId: string;
  modelName: string;
  reasoningEffort: AutomationReasoningEffort;
  scheduleEnabled: boolean;
  cron: string;
  lastScheduledAt?: string;
  createdAt: string;
  updatedAt: string;
  lastRunAt?: string;
  runCount: number;
}

export type AutomationRunStatus = 'running' | 'completed' | 'error';
export type AutomationRunTrigger = 'manual' | 'scheduled';

export interface DbAutomationRun {
  id?: number;
  automationId: number;
  sessionId?: string;
  status: AutomationRunStatus;
  trigger?: AutomationRunTrigger;
  projectId: string;
  projectName: string;
  modelId: string;
  modelName: string;
  reasoningEffort: AutomationReasoningEffort;
  startedAt: string;
  completedAt?: string;
  error?: string;
}

/** 未关联项目的特殊标记 */
export const UNLINKED_PROJECT = '__unlinked__';

// ==================== 设置相关表 ====================

/** 全局设置键值对（常规配置） */
export interface DbGlobalSetting {
  key: string;
  value: string; // JSON 序列化存储
}

/** 模型供应商 */
export interface DbProvider {
  id: string;
  type: string;        // ProviderType: '' | openai | openai-responses | anthropic | ollama
  name: string;
  apiUrl: string;
  apiKey: string;
  model: string;
  contextLength?: number;
  scope?: string;
  timeout?: string;
  defaultOptions?: string;
  enabled: number;     // SQLite 风格: 0 | 1
  sortOrder: number;   // 排序
  availableModels: string; // JSON 序列化 {id:string, ownedBy?:string}[]
}

/** MCP 服务器 */
export interface DbMcpServer {
  id?: number;
  name: string;
  command: string;
  args: string;        // JSON 序列化 string[]
  scope?: string;
  type?: string;
  url?: string;
  env?: string;        // JSON 序列化 Record<string, string>
  headers?: string;    // JSON 序列化 Record<string, string>
  timeout?: string;
  enabled: number;     // 0 | 1
  sortOrder: number;
}

/** Skill 配置 */
export interface DbSkill {
  id?: number;
  name: string;
  description: string;
  path: string;
  enabled: number;     // 0 | 1
  source: string;      // 'manual' | 'discovered'
  sortOrder: number;
}

/** Agent 配置 */
export interface DbAgent {
  id?: number;
  name: string;
  description: string;
  path: string;
  enabled: number;     // 0 | 1
  source: string;      // 'manual' | 'discovered'
  sortOrder: number;
}

// ==================== 数据库定义 ====================

class SolonCodeDatabase extends Dexie {
  messages!: Table<DbMessage>;
  conversations!: Table<DbConversation>;
  globalSettings!: Table<DbGlobalSetting>;
  providers!: Table<DbProvider>;
  mcpServers!: Table<DbMcpServer>;
  skills!: Table<DbSkill>;
  agents!: Table<DbAgent>;
  projects!: Table<DbProject>;
  automations!: Table<DbAutomation>;
  automationRuns!: Table<DbAutomationRun>;

  constructor() {
    super('SolonCodeDB');
    this.version(3).stores({
      messages: '++id, conversationId, timestamp',
      conversations: '++id, title, timestamp, status',
      globalSettings: 'key',
      providers: 'id, type, enabled, sortOrder',
      mcpServers: '++id, name, enabled, sortOrder',
    });
    // v4: 新增 skills 表
    this.version(4).stores({
      skills: '++id, name, enabled, source, sortOrder',
    });
    // v5: 新增 agents 表
    this.version(5).stores({
      agents: '++id, name, enabled, source, sortOrder',
    });
    // v6: 新增 projects 表，conversations 加 workspacePath 索引
    this.version(6).stores({
      projects: 'id, name, sortOrder',
      conversations: '++id, title, timestamp, status, workspacePath',
    });
    // v7: messages 加 workspacePath 索引
    this.version(7).stores({
      messages: '++id, conversationId, timestamp, workspacePath',
    });
    // v8: 新增自动化任务表
    this.version(8).stores({
      automations: '++id, projectId, createdAt, lastRunAt',
    });
    // v9: 新增自动化运行记录表
    this.version(9).stores({
      automationRuns: '++id, automationId, sessionId, status, startedAt',
    });
    // v10: 自动化支持 Cron 定时开关
    this.version(10).stores({
      automations: '++id, projectId, scheduleEnabled, createdAt, lastRunAt',
    }).upgrade(transaction => transaction.table<DbAutomation>('automations').toCollection().modify(automation => {
      if (typeof automation.scheduleEnabled !== 'boolean') automation.scheduleEnabled = false;
      if (!automation.cron) automation.cron = '0 9 * * *';
    }));
  }
}

export const db = new SolonCodeDatabase();

// ==================== 迁移：将现有会话关联到项目 ====================

export async function migrateConversationsToProjects(): Promise<void> {
  const setting = await db.globalSettings.get('projectsMigrated');
  if (setting) return;
  await db.conversations
    .filter(c => !c.workspacePath)
    .modify({ workspacePath: UNLINKED_PROJECT });
  await db.globalSettings.put({ key: 'projectsMigrated', value: 'true' });
}

// ==================== 消息 ====================

export async function saveMessage(message: Omit<DbMessage, 'id'>): Promise<number> {
  return await db.messages.add(message);
}

export async function updateMessage(id: number, changes: Partial<Omit<DbMessage, 'id'>>): Promise<void> {
  await db.messages.update(id, changes);
}

export async function reassignMessages(oldConvId: string | number, newConvId: string | number): Promise<void> {
  const numId = typeof oldConvId === 'string' ? parseInt(oldConvId, 10) : oldConvId;
  const ids = isNaN(numId) ? [oldConvId] : [oldConvId, numId];
  await db.messages
    .where('conversationId')
    .anyOf(ids)
    .modify({ conversationId: newConvId });
}

export async function getMessagesByConversation(
  conversationId: string | number,
  limit?: number,
  offset?: number,
): Promise<DbMessage[]> {
  // 同时匹配 string 和 number 类型的 conversationId
  const numId = typeof conversationId === 'string' ? parseInt(conversationId, 10) : conversationId;
  const ids = isNaN(numId) ? [conversationId] : [conversationId, numId];
  let collection = db.messages
    .where('conversationId')
    .anyOf(ids);

  if (offset) {
    collection = collection.offset(offset);
  }
  if (limit) {
    collection = collection.limit(limit);
  }

  return await collection.toArray();
}

/** 获取会话的消息总数 */
export async function getMessageCount(conversationId: string | number): Promise<number> {
  const numId = typeof conversationId === 'string' ? parseInt(conversationId, 10) : conversationId;
  const ids = isNaN(numId) ? [conversationId] : [conversationId, numId];
  return await db.messages
    .where('conversationId')
    .anyOf(ids)
    .count();
}

export async function saveConversation(conversation: DbConversation): Promise<number> {
  if (conversation.id) {
    await db.conversations.update(conversation.id, conversation);
    return conversation.id;
  }
  const newId = await db.conversations.add(conversation);
  return newId;
}

export async function getAllConversations(workspacePath?: string): Promise<DbConversation[]> {
  if (workspacePath) {
    return await db.conversations.where('workspacePath').equals(workspacePath).reverse().sortBy('timestamp');
  }
  return await db.conversations.toArray();
}

export async function deleteConversation(id: string | number): Promise<void> {
  const numId = typeof id === 'string' ? parseInt(id, 10) : id;
  if (isNaN(numId)) return;
  await db.messages.where('conversationId').anyOf([id, numId]).delete();
  await db.conversations.delete(numId);
}

export async function updateConversation(id: string | number, updates: Partial<DbConversation>): Promise<void> {
  const numId = typeof id === 'string' ? parseInt(id, 10) : id;
  if (isNaN(numId)) return;
  await db.conversations.update(numId, updates);
}

// ==================== 项目 CRUD ====================

export async function getAllProjects(): Promise<DbProject[]> {
  return await db.projects.orderBy('sortOrder').toArray();
}

export async function addProject(project: DbProject): Promise<void> {
  await db.projects.put(project);
}

export async function removeProject(id: string): Promise<void> {
  await db.projects.delete(id);
}

function remapProjectOwnedPath(path: string | undefined, oldProjectId: string, newProjectId: string): string | undefined {
  if (!path) return path;
  const normalizedPath = path.replace(/\\/g, '/').replace(/\/+$/, '');
  const normalizedOld = oldProjectId.replace(/\\/g, '/').replace(/\/+$/, '');
  const normalizedPathLower = normalizedPath.toLowerCase();
  const normalizedOldLower = normalizedOld.toLowerCase();
  if (normalizedPathLower !== normalizedOldLower && !normalizedPathLower.startsWith(`${normalizedOldLower}/`)) {
    return path;
  }

  const suffix = normalizedPath.slice(normalizedOld.length);
  const separator = newProjectId.includes('\\') ? '\\' : '/';
  return `${newProjectId.replace(/[\\/]+$/, '')}${suffix.replace(/\//g, separator)}`;
}

function storedSettingEquals(row: DbGlobalSetting | undefined, value: string): boolean {
  if (!row) return false;
  try {
    return JSON.parse(row.value) === value;
  } catch {
    return row.value === value;
  }
}

export async function renameProject(id: string, newId: string, name: string): Promise<void> {
  if (id === newId) {
    await db.projects.update(id, { name });
    return;
  }

  await db.transaction(
    'rw',
    [db.projects, db.conversations, db.messages, db.automations, db.skills, db.agents, db.globalSettings],
    async () => {
      const project = await db.projects.get(id);
      if (!project) throw new Error('项目不存在');
      if (await db.projects.get(newId)) throw new Error('目标项目已存在');

      await db.projects.add({ ...project, id: newId, name });
      await db.conversations.where('workspacePath').equals(id).modify({ workspacePath: newId });
      await db.messages.where('workspacePath').equals(id).modify({ workspacePath: newId });
      await db.automations.where('projectId').equals(id).modify({ projectId: newId, projectName: name });

      await db.skills.toCollection().modify(skill => {
        const nextPath = remapProjectOwnedPath(skill.path, id, newId);
        if (nextPath !== skill.path) skill.path = nextPath || skill.path;
      });
      await db.agents.toCollection().modify(agent => {
        const nextPath = remapProjectOwnedPath(agent.path, id, newId);
        if (nextPath !== agent.path) agent.path = nextPath || agent.path;
      });

      for (const key of ['lastFolder', 'lastActiveProject']) {
        const row = await db.globalSettings.get(key);
        if (storedSettingEquals(row, id)) {
          await db.globalSettings.put({ key, value: JSON.stringify(newId) });
        }
      }

      const oldLastSessionKey = `lastSession:${id}`;
      const lastSession = await db.globalSettings.get(oldLastSessionKey);
      if (lastSession) {
        await db.globalSettings.put({ ...lastSession, key: `lastSession:${newId}` });
        await db.globalSettings.delete(oldLastSessionKey);
      }

      const general = await db.globalSettings.get('general');
      if (general) {
        try {
          const parsed = JSON.parse(general.value);
          let changed = false;
          if (Array.isArray(parsed?.mounts)) {
            parsed.mounts = parsed.mounts.map((mount: { path?: string }) => {
              const nextPath = remapProjectOwnedPath(mount.path, id, newId);
              if (nextPath !== mount.path) changed = true;
              return nextPath === mount.path ? mount : { ...mount, path: nextPath };
            });
          }
          if (changed) {
            await db.globalSettings.put({ key: general.key, value: JSON.stringify(parsed) });
          }
        } catch {
          // 非法旧配置保持原样，不阻断项目路径迁移。
        }
      }

      await db.projects.delete(id);
    },
  );
}

export async function updateProjectOrder(projectIds: string[]): Promise<void> {
  await db.transaction('rw', db.projects, async () => {
    await Promise.all(
      projectIds.map((id, sortOrder) => db.projects.update(id, { sortOrder })),
    );
  });
}

// ==================== 自动化 CRUD ====================

export async function getAllAutomations(): Promise<DbAutomation[]> {
  return await db.automations.orderBy('createdAt').reverse().toArray();
}

export async function addAutomation(automation: Omit<DbAutomation, 'id'>): Promise<number> {
  return await db.automations.add(automation);
}

export async function updateAutomation(id: number, updates: Partial<DbAutomation>): Promise<void> {
  await db.automations.update(id, updates);
}

export async function getAutomation(id: number): Promise<DbAutomation | undefined> {
  return await db.automations.get(id);
}

export async function deleteAutomation(id: number): Promise<void> {
  await db.transaction('rw', [db.automations, db.automationRuns], async () => {
    await db.automationRuns.where('automationId').equals(id).delete();
    await db.automations.delete(id);
  });
}

export async function getAutomationRuns(automationId: number): Promise<DbAutomationRun[]> {
  if (!Number.isInteger(automationId) || automationId <= 0) return [];
  const runs = await db.automationRuns
    .where('automationId')
    .equals(automationId)
    .toArray();
  return runs.sort((left, right) => right.startedAt.localeCompare(left.startedAt));
}

export async function addAutomationRun(run: Omit<DbAutomationRun, 'id'>): Promise<number> {
  return await db.automationRuns.add(run);
}

export async function updateAutomationRun(id: number, updates: Partial<DbAutomationRun>): Promise<void> {
  if (!Number.isInteger(id) || id <= 0) return;
  await db.automationRuns.update(id, updates);
}

// ==================== 全局设置（键值对） ====================

async function getSetting<T>(key: string, defaultValue: T): Promise<T> {
  const row = await db.globalSettings.get(key);
  if (!row) return defaultValue;
  try {
    return JSON.parse(row.value) as T;
  } catch {
    return defaultValue;
  }
}

async function setSetting<T>(key: string, value: T): Promise<void> {
  await db.globalSettings.put({ key, value: JSON.stringify(value) });
}

/** 保存最后打开的工作区文件夹 */
export async function saveLastFolder(folderPath: string): Promise<void> {
  await setSetting('lastFolder', folderPath);
}

/** 读取最后打开的工作区文件夹 */
export async function loadLastFolder(): Promise<string | null> {
  return await getSetting<string | null>('lastFolder', null);
}

/** 保存最后活跃项目路径 */
export async function saveLastActiveProject(path: string): Promise<void> {
  await setSetting('lastActiveProject', path);
}

/** 读取最后活跃项目路径 */
export async function loadLastActiveProject(): Promise<string | null> {
  return await getSetting<string | null>('lastActiveProject', null);
}

/** 保存工作区对应的最后会话 ID */
export async function saveLastSessionId(folderPath: string, sessionId: string): Promise<void> {
  await setSetting(`lastSession:${folderPath}`, sessionId);
}

/** 读取工作区对应的最后会话 ID */
export async function loadLastSessionId(folderPath: string): Promise<string | null> {
  return await getSetting<string | null>(`lastSession:${folderPath}`, null);
}

export { getSetting, setSetting };

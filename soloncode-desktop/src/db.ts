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
  type: string;        // ProviderType: zhipu | openai | deepseek | claude | custom
  name: string;
  apiUrl: string;
  apiKey: string;
  model: string;
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

export async function reassignMessages(oldConvId: string | number, newConvId: string | number): Promise<void> {
  await db.messages
    .where('conversationId')
    .equals(oldConvId)
    .modify({ conversationId: newConvId });
}

export async function getMessagesByConversation(
  conversationId: string | number,
  limit?: number,
  offset?: number,
): Promise<DbMessage[]> {
  let collection = db.messages
    .where('conversationId')
    .equals(conversationId);

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
  return await db.messages
    .where('conversationId')
    .equals(conversationId)
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

export async function getAllConversations(): Promise<DbConversation[]> {
  return await db.conversations.toArray();
}

export async function deleteConversation(id: string | number): Promise<void> {
  const numId = typeof id === 'string' ? parseInt(id, 10) : id;
  if (isNaN(numId)) return;
  await db.messages.where('conversationId').equals(numId).delete();
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

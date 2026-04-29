import Dexie, { type Table } from 'dexie';

// ==================== 消息 & 会话 ====================

export interface DbMessage {
  id?: number;
  conversationId: string | number;
  role: string;
  timestamp: string;
  contents: string;
}

export interface DbConversation {
  id?: number;
  title: string;
  timestamp: string;
  status: string;
  isPermanent?: boolean;
  icon?: string;
}

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
  }
}

export const db = new SolonCodeDatabase();

// ==================== 消息 ====================

export async function saveMessage(message: Omit<DbMessage, 'id'>): Promise<number> {
  return await db.messages.add(message);
}

export async function getMessagesByConversation(conversationId: string | number): Promise<DbMessage[]> {
  return await db.messages
    .where('conversationId')
    .equals(conversationId)
    .toArray();
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
  await db.messages.where('conversationId').equals(id).delete();
  await db.conversations.where('id').equals(id).delete();
}

export async function updateConversation(id: string | number, updates: Partial<DbConversation>): Promise<void> {
  await db.conversations.where('id').equals(id).modify(updates);
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

/** 保存工作区对应的最后会话 ID */
export async function saveLastSessionId(folderPath: string, sessionId: string): Promise<void> {
  await setSetting(`lastSession:${folderPath}`, sessionId);
}

/** 读取工作区对应的最后会话 ID */
export async function loadLastSessionId(folderPath: string): Promise<string | null> {
  return await getSetting<string | null>(`lastSession:${folderPath}`, null);
}

export { getSetting, setSetting };

import Dexie, { Table } from 'dexie';

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

/** 全局设置（单行记录，key-value 模式） */
export interface DbGlobalSetting {
  key: string;
  value: string; // JSON 序列化存储
}

/** 工作区状态 */
export interface WorkspaceState {
  lastFolder: string | null;
  lastSessionId: string | null;
}

class SolonCodeDatabase extends Dexie {
  messages!: Table<DbMessage>;
  conversations!: Table<DbConversation>;
  globalSettings!: Table<DbGlobalSetting>;

  constructor() {
    super('SolonCodeDB');
    this.version(2).stores({
      messages: '++id, conversationId, timestamp',
      conversations: '++id, title, timestamp, status',
      globalSettings: 'key',
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

// ==================== 全局设置 ====================

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

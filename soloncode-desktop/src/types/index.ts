export type MessageType = 'user' | 'assistant' | 'reason' | 'action' | 'error';

export interface Message {
  id: number;
  role: MessageType;
  content: string;
  timestamp: string;
  toolName?: string;
  args?: any;
  reasonContent?: string;
  actionContent?: string;
}

export interface Conversation {
  id: number;
  title: string;
  timestamp: string;
  status: string;
  isPermanent?: boolean;
  icon?: string;
}

export interface Plugin {
  id: string;
  name: string;
  icon: string;
  description: string;
  enabled: boolean;
  version: string;
}

export type Theme = 'dark' | 'light';

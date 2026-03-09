<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import axios from 'axios';
import { type Message, type Conversation, type Theme, type Plugin } from '../types';
import ChatHeader from './ChatHeader.vue';
import ChatMessages from './ChatMessages.vue';
import ChatInput from './ChatInput.vue';

interface Props {
  currentConversation: Conversation;
  plugins: Plugin[];
}

interface Emits {
  (e: 'toggle-theme'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const currentTheme = ref<Theme>('dark');
const messages = ref<Message[]>([]);
const isLoading = ref(false);
const chatMessagesRef = ref<InstanceType<typeof ChatMessages>>();
const API_BASE_URL = '/cli';

function toggleTheme() {
  currentTheme.value = currentTheme.value === 'dark' ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', currentTheme.value);
  localStorage.setItem('soloncode-theme', currentTheme.value);
  emit('toggle-theme');
}

function loadTheme() {
  const savedTheme = localStorage.getItem('soloncode-theme') as Theme | null;
  if (savedTheme) {
    currentTheme.value = savedTheme;
  } else {
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    currentTheme.value = prefersDark ? 'dark' : 'light';
  }
  document.documentElement.setAttribute('data-theme', currentTheme.value);
}

async function sendMessage(messageText: string) {
  const userMessage: Message = {
    id: Date.now(),
    role: 'user',
    content: messageText,
    timestamp: new Date().toLocaleTimeString()
  };

  messages.value.push(userMessage);
  isLoading.value = true;

  await chatMessagesRef.value?.scrollToBottom();

  try {
    const sessionId = props.currentConversation.id.toString();
    const url = `${API_BASE_URL}?input=${encodeURIComponent(messageText)}&m=stream`;

    const response = await fetch(url, {
      method: 'GET',
      headers: {
        'X-Session-Id': sessionId
      }
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const reader = response.body?.getReader();
    const decoder = new TextDecoder();
    let assistantMessage: Message | null = null;
    let currentMessageId: number | null = null;
    let accumulatedReason = '';
    let accumulatedAction = '';
    let currentAction = { content: '', toolName: '', args: null as any };

    if (reader) {
      const assistantMsgId = Date.now() + 1;

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        const chunk = decoder.decode(value, { stream: true });
        const lines = chunk.split('\n');

        for (const line of lines) {
          if (line.trim() === '[DONE]') {
            isLoading.value = false;
            await chatMessagesRef.value?.scrollToBottom();
            return;
          }

          if (line.trim()) {
            try {
              let jsonStr = line.trim();
              if (jsonStr.startsWith('data:')) {
                jsonStr = jsonStr.substring(5).trim();
              }

              if (jsonStr === '[DONE]') {
                isLoading.value = false;
                await chatMessagesRef.value?.scrollToBottom();
                return;
              }

              const data = JSON.parse(jsonStr);
              const type = data.type;
              const text = data.text || '';

              if (text) {
                if (type === 'reason') {
                  accumulatedReason += text;
                  if (currentMessageId === null) {
                    currentMessageId = Date.now() + Math.floor(Math.random() * 1000);
                    assistantMessage = {
                      id: currentMessageId,
                      role: 'assistant',
                      content: '',
                      timestamp: new Date().toLocaleTimeString(),
                      reasonContent: text
                    };
                    messages.value.push(assistantMessage);
                  } else {
                    const existingMsg = messages.value.find(m => m.id === currentMessageId);
                    if (existingMsg) {
                      existingMsg.reasonContent = accumulatedReason;
                      await chatMessagesRef.value?.scrollToBottom();
                    }
                  }
                  await chatMessagesRef.value?.scrollToBottom();
                } else if (type === 'action') {
                  accumulatedAction += text;
                  if (data.toolName) currentAction.toolName = data.toolName;
                  if (data.args) currentAction.args = data.args;
                  if (currentMessageId === null) {
                    currentMessageId = Date.now() + Math.floor(Math.random() * 1000);
                    assistantMessage = {
                      id: currentMessageId,
                      role: 'assistant',
                      content: '',
                      timestamp: new Date().toLocaleTimeString(),
                      actionContent: accumulatedAction,
                      toolName: data.toolName,
                      args: data.args
                    };
                    messages.value.push(assistantMessage);
                  } else {
                    const existingMsg = messages.value.find(m => m.id === currentMessageId);
                    if (existingMsg) {
                      existingMsg.actionContent = accumulatedAction;
                      existingMsg.toolName = currentAction.toolName;
                      existingMsg.args = currentAction.args;
                      await chatMessagesRef.value?.scrollToBottom();
                    }
                  }
                  await chatMessagesRef.value?.scrollToBottom();
                } else if (type === 'agent') {
                  let role = 'assistant';

                  if (currentMessageId !== null) {
                    const existingMsg = messages.value.find(m => m.id === currentMessageId);
                    if (existingMsg && existingMsg.role === role) {
                      existingMsg.content += text;
                      existingMsg.toolName = currentAction.toolName || undefined;
                      existingMsg.args = currentAction.args;
                  if (accumulatedAction) {
                    existingMsg.actionContent = accumulatedAction;
                  }
                  if (accumulatedReason) {
                    existingMsg.reasonContent = accumulatedReason;
                  }
                    } else {
                      currentMessageId = Date.now() + Math.floor(Math.random() * 1000);
                    assistantMessage = {
                      id: currentMessageId,
                      role: role as any,
                      content: text,
                      timestamp: new Date().toLocaleTimeString(),
                      reasonContent: accumulatedReason || undefined,
                      actionContent: accumulatedAction || undefined,
                      toolName: currentAction.toolName || undefined,
                      args: currentAction.args
                    };
                    messages.value.push(assistantMessage);
                    accumulatedReason = '';
                    accumulatedAction = '';
                    currentAction = { content: '', toolName: '', args: null };
                  }
                  } else {
                    currentMessageId = Date.now() + Math.floor(Math.random() * 1000);
                    assistantMessage = {
                      id: currentMessageId,
                      role: role as any,
                      content: text,
                      timestamp: new Date().toLocaleTimeString(),
                      reasonContent: accumulatedReason || undefined,
                      actionContent: accumulatedAction || undefined,
                      toolName: currentAction.toolName || undefined,
                      args: currentAction.args
                    };
                    messages.value.push(assistantMessage);
                    accumulatedReason = '';
                    accumulatedAction = '';
                    currentAction = { content: '', toolName: '', args: null };
                  }
                  await chatMessagesRef.value?.scrollToBottom();
                } else if (type === 'error') {
                  currentMessageId = Date.now() + Math.floor(Math.random() * 1000);
                  assistantMessage = {
                    id: currentMessageId,
                    role: 'error',
                    content: text,
                    timestamp: new Date().toLocaleTimeString()
                  };
                  messages.value.push(assistantMessage);
                  accumulatedReason = '';
                  accumulatedAction = '';
                  await chatMessagesRef.value?.scrollToBottom();
                }
              }
            } catch (e) {
              console.warn('Failed to parse chunk:', line, e);
            }
          }
        }
      }
    }
  } catch (error) {
    console.error('Failed to send message:', error);
    const errorMessage: Message = {
      id: Date.now() + 1,
      role: 'error',
      content: `请求失败: ${error instanceof Error ? error.message : '未知错误'}`,
      timestamp: new Date().toLocaleTimeString()
    };
    messages.value.push(errorMessage);
  } finally {
    isLoading.value = false;
    await chatMessagesRef.value?.scrollToBottom();
  }
}

function loadSolonClawMessages() {
  messages.value = [
    {
      id: 1,
      role: 'assistant',
      content: '🦊 SolonClaw 已启动\n\n这是一个强大的代码分析和管理工具。我可以帮助你：\n\n• 分析项目结构和依赖关系\n• 检测代码质量问题\n• 生成代码文档\n• 执行代码重构建议\n• 管理项目配置\n\n请告诉我你需要什么帮助？',
      timestamp: new Date().toLocaleTimeString()
    }
  ];
}

function loadConversationMessages(convId: number) {
  messages.value = [
    {
      id: 1,
      role: 'assistant',
      content: '你好！我是 SolonCode 助手。有什么我可以帮助你的吗？',
      timestamp: new Date().toLocaleTimeString()
    }
  ];
}

watch(() => props.currentConversation.id, (newId) => {
  if (newId === 0 && props.currentConversation.isPermanent) {
    loadSolonClawMessages();
  } else {
    loadConversationMessages(newId);
  }
}, { immediate: true });

onMounted(() => {
  loadTheme();
});
</script>

<template>
  <main class="main-content">
    <ChatHeader
      :title="props.currentConversation.title"
      :status="props.currentConversation.status"
      :theme="currentTheme"
      @toggle-theme="toggleTheme"
    />
    <ChatMessages
      ref="chatMessagesRef"
      :messages="messages"
      :is-loading="isLoading"
    />
    <ChatInput @send="sendMessage" />
  </main>
</template>

<style scoped>
.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
</style>

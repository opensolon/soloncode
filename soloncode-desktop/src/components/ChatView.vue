<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
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

  setTimeout(async () => {
    const assistantMessage: Message = {
      id: Date.now() + 1,
      role: 'assistant',
      content: generateResponse(userMessage.content),
      timestamp: new Date().toLocaleTimeString()
    };

    messages.value.push(assistantMessage);
    isLoading.value = false;
    await chatMessagesRef.value?.scrollToBottom();
  }, 1000);
}

function generateResponse(userInput: string): string {
  const responses = [
    '我理解你的问题。让我来帮助你解决这个任务。',
    '这是一个很好的问题！让我详细解释一下。',
    '根据你的描述，我建议采用以下方案...',
    '我明白了。这里有几个可行的解决方案供你参考。',
    '你的想法很有创意！我们可以进一步探讨这个方向。'
  ];
  return responses[Math.floor(Math.random() * responses.length)];
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

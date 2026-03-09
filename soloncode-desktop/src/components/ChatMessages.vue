<script setup lang="ts">
import { ref, nextTick } from 'vue';
import { type Message } from '../types';

interface Props {
  messages: Message[];
  isLoading: boolean;
}

const props = defineProps<Props>();
const chatContainer = ref<HTMLElement>();

async function scrollToBottom() {
  await nextTick();
  if (chatContainer.value) {
    chatContainer.value.scrollTop = chatContainer.value.scrollHeight;
  }
}

defineExpose({
  scrollToBottom
});
</script>

<template>
  <div class="chat-messages" ref="chatContainer">
    <div
      v-for="message in props.messages"
      :key="message.id"
      class="message"
      :class="message.role"
    >
      <div class="message-content">
        <div class="message-avatar">
          {{ message.role === 'user' ? '👤' : '🤖' }}
        </div>
        <div class="message-body">
          <div class="message-text">{{ message.content }}</div>
          <div class="message-time">{{ message.timestamp }}</div>
        </div>
      </div>
    </div>

    <!-- 加载指示器 -->
    <div v-if="props.isLoading" class="message assistant loading">
      <div class="message-content">
        <div class="message-avatar">🤖</div>
        <div class="message-body">
          <div class="loading-indicator">
            <span></span>
            <span></span>
            <span></span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.message {
  display: flex;
  animation: fadeIn 0.3s ease-in;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.message.user {
  justify-content: flex-end;
}

.message-content {
  display: flex;
  gap: 12px;
  max-width: 80%;
}

.message.user .message-content {
  flex-direction: row-reverse;
}

.message-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background-color: var(--cb-vscode-input-background);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  flex-shrink: 0;
  transition: background-color 0.3s;
}

.message-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.message.user .message-body {
  align-items: flex-end;
}

.message-text {
  padding: 12px 16px;
  border-radius: 12px;
  background-color: var(--cb-vscode-input-background);
  color: var(--cb-text-primary);
  line-height: 1.6;
  word-wrap: break-word;
  transition: background-color 0.3s, color 0.3s;
}

.message.user .message-text {
  background-color: var(--cb-accent);
  color: white;
}

.message-time {
  font-size: 11px;
  color: var(--cb-text-secondary);
  padding: 0 4px;
}

.message.loading {
  opacity: 0.7;
}

.loading-indicator {
  display: flex;
  gap: 4px;
  padding: 12px 16px;
}

.loading-indicator span {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: var(--cb-text-secondary);
  animation: pulse 1.4s ease-in-out infinite;
}

.loading-indicator span:nth-child(1) {
  animation-delay: 0s;
}

.loading-indicator span:nth-child(2) {
  animation-delay: 0.2s;
}

.loading-indicator span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes pulse {
  0%, 80%, 100% {
    opacity: 0.3;
  }
  40% {
    opacity: 1;
  }
}
</style>

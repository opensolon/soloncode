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

function getAvatar(role: string): string {
  const avatars: Record<string, string> = {
    'user': '👤',
    'assistant': '🤖',
    'reason': '💭',
    'action': '⚡',
    'error': '❌'
  };
  return avatars[role] || '🤖';
}

function getRoleLabel(role: string): string {
  const labels: Record<string, string> = {
    'user': '用户',
    'assistant': '助手',
    'reason': '思考',
    'action': '执行',
    'error': '错误'
  };
  return labels[role] || role;
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
        <div v-if="message.role !== 'reason'" class="message-avatar">
          {{ getAvatar(message.role) }}
        </div>
        <div class="message-body">
          <div class="message-header">
            <span class="message-role">{{ getRoleLabel(message.role) }}</span>
            <span v-if="message.toolName" class="message-tool">🔧 {{ message.toolName }}</span>
          </div>
          <div class="message-text">
            <div v-if="message.reasonContent" class="message-reason">
              {{ message.reasonContent }}
            </div>
            <div v-if="message.actionContent" class="message-action">
              {{ message.actionContent }}
            </div>
            <div v-if="message.content" class="content-text">{{ message.content }}</div>
            <div v-if="message.args" class="message-args">
              <pre>{{ JSON.stringify(message.args, null, 2) }}</pre>
            </div>
          </div>
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

.message.reason {
  border-left: 3px solid #6c757d;
  padding-left: 8px;
}

.message.action {
  border-left: 3px solid #28a745;
  padding-left: 8px;
}

.message.error {
  border-left: 3px solid #dc3545;
  padding-left: 8px;
}

.message-header {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 4px;
}

.message-role {
  font-size: 11px;
  font-weight: 600;
  color: var(--cb-text-secondary);
  text-transform: uppercase;
  padding: 2px 6px;
  border-radius: 4px;
  background-color: var(--cb-vscode-input-background);
}

.message-tool {
  font-size: 11px;
  color: #28a745;
  font-weight: 500;
}

.message-args {
  margin-top: 8px;
  padding: 8px;
  background-color: var(--cb-vscode-sideBar-background);
  border-radius: 6px;
  font-size: 12px;
  overflow-x: auto;
}

.message-args pre {
  margin: 0;
  white-space: pre-wrap;
  word-wrap: break-word;
  color: var(--cb-text-secondary);
}

.message-text {
  padding: 0;
  border-radius: 0;
  background-color: transparent;
  color: var(--cb-text-primary);
  line-height: 1.6;
  word-wrap: break-word;
  transition: background-color 0.3s, color 0.3s;
}

.message.user .message-text {
  background-color: var(--cb-accent);
  color: white;
  padding: 12px 16px;
  border-radius: 12px;
}

.message.assistant .message-text {
  background-color: var(--cb-vscode-input-background);
  padding: 12px 16px;
  border-radius: 12px;
}

.content-text {
  padding: 4px 0;
}

.message.user .content-text {
  padding: 0;
}

.message-reason,
.message-action {
  padding: 4px 0;
  color: var(--cb-text-secondary);
  line-height: 1.5;
  white-space: pre-wrap;
}

.message-args {
  margin-top: 8px;
  padding: 8px;
  background-color: var(--cb-vscode-sideBar-background);
  border-radius: 6px;
  font-size: 12px;
  overflow-x: auto;
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

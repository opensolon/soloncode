<script setup lang="ts">
import { computed } from 'vue';
import { type Conversation } from '../types';

interface Props {
  conversations: Conversation[];
  currentId: number;
}

interface Emits {
  (e: 'select', conv: Conversation): void;
}

const props = defineProps<Props>();
defineEmits<Emits>();

// 分离固定会话和普通会话
const permanentConversations = computed(() =>
  props.conversations.filter(conv => conv.isPermanent)
);

const regularConversations = computed(() =>
  props.conversations.filter(conv => !conv.isPermanent)
);
</script>

<template>
  <div class="conversation-list">
    <!-- 固定会话按钮 -->
    <div
      v-for="conv in permanentConversations"
      :key="conv.id"
      class="conversation-item permanent"
      :class="{ active: conv.id === props.currentId }"
      @click="$emit('select', conv)"
    >
      <div class="conversation-title">
        <span v-if="conv.icon" class="conversation-icon">{{ conv.icon }}</span>
        {{ conv.title }}
      </div>
    </div>

    <!-- 普通会话 -->
    <div v-if="regularConversations.length > 0" class="conversations-section">
      <div class="section-header">
        <span class="section-title">会话</span>
        <span class="section-count">{{ regularConversations.length }}</span>
      </div>
      <div
        v-for="conv in regularConversations"
        :key="conv.id"
        class="conversation-item"
        :class="{ active: conv.id === props.currentId }"
        @click="$emit('select', conv)"
      >
        <div class="conversation-title">
          <span v-if="conv.icon" class="conversation-icon">{{ conv.icon }}</span>
          {{ conv.title }}
        </div>
        <div class="conversation-time">{{ conv.timestamp }}</div>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-if="props.conversations.length === 0" class="empty-conversations">
      <span>暂无会话</span>
    </div>
  </div>
</template>

<style scoped>
.conversation-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.conversations-section {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 16px;
}

.section-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--cb-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.section-count {
  font-size: 12px;
  color: var(--cb-text-secondary);
  background-color: var(--cb-vscode-input-background);
  padding: 2px 8px;
  border-radius: 10px;
}

.conversation-item {
  padding: 12px 16px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.conversation-item:hover {
  background-color: var(--cb-vscode-list-hoverBackground);
}

.conversation-item.active {
  background-color: var(--cb-vscode-list-activeSelectionBackground);
}

/* 固定会话按钮样式 */
.conversation-item.permanent {
  background-color: var(--cb-vscode-input-background);
  border: 1px solid var(--cb-vscode-widget-border);
  border-radius: 6px;
  padding: 10px 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 0;
  transition: all 0.2s;
  cursor: pointer;
  width: 100%;
  box-sizing: border-box;
}

.conversation-item.permanent:hover {
  background-color: var(--cb-vscode-list-hoverBackground);
  border-color: var(--cb-accent);
}

.conversation-item.permanent.active {
  border-color: var(--cb-accent);
  border-left-width: 3px;
}

.conversation-item.permanent .conversation-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 500;
  color: var(--cb-text-primary);
  margin-bottom: 0;
}

.conversation-item.permanent .conversation-icon {
  font-size: 16px;
  flex-shrink: 0;
}

.conversation-title {
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 4px;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--cb-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-icon {
  font-size: 16px;
  flex-shrink: 0;
}

.conversation-time {
  font-size: 12px;
  color: var(--cb-text-secondary);
}

.empty-conversations {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px 16px;
  color: var(--cb-text-secondary);
  font-size: 13px;
  text-align: center;
}

.empty-conversations span {
  opacity: 0.7;
}
</style>

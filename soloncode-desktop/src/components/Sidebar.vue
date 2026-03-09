<script setup lang="ts">
import { ref } from 'vue';
import { type Conversation, type Plugin } from '../types';
import PluginPanel from './PluginPanel.vue';
import ConversationList from './ConversationList.vue';

interface Props {
  conversations: Conversation[];
  currentConversation: Conversation;
  plugins: Plugin[];
}

interface Emits {
  (e: 'new-conversation'): void;
  (e: 'toggle-plugin-panel'): void;
  (e: 'toggle-plugin', pluginId: string): void;
  (e: 'select-conversation', conv: Conversation): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const showPluginPanel = ref(false);

function handleTogglePluginPanel() {
  showPluginPanel.value = !showPluginPanel.value;
  emit('toggle-plugin-panel');
}
</script>

<template>
  <aside class="sidebar">
    <!-- 顶部操作区 -->
    <div class="sidebar-top">
      <button class="action-btn" @click="$emit('new-conversation')">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
          <path d="M8 1V15M1 8H15" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        </svg>
        <span>新建任务</span>
      </button>
      <button
        class="plugin-btn"
        :class="{ active: showPluginPanel }"
        @click="handleTogglePluginPanel"
      >
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
          <path d="M8 2L10 6H6L8 2Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          <path d="M2 8L6 10L6 6L2 8Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          <path d="M14 8L10 6L10 10L14 8Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          <path d="M8 14L6 10H10L8 14Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        <span>插件管理</span>
      </button>
    </div>

    <!-- 插件面板 -->
    <PluginPanel
      :plugins="plugins"
      :show="showPluginPanel"
      @toggle="emit('toggle-plugin', $event)"
    />

    <!-- 会话列表 -->
    <ConversationList
      :conversations="props.conversations"
      :current-id="props.currentConversation.id"
      @select="emit('select-conversation', $event)"
    />
  </aside>
</template>

<style scoped>
.sidebar {
  width: 280px;
  background-color: var(--cb-vscode-sideBar-background);
  border-right: 1px solid var(--cb-vscode-panel-border);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  transition: background-color 0.3s, border-color 0.3s;
}

/* 顶部操作区 */
.sidebar-top {
  padding: 12px;
  border-bottom: 1px solid var(--cb-vscode-panel-border);
  display: flex;
  gap: 8px;
}

.action-btn,
.plugin-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  flex: 1;
  padding: 10px 12px;
  background-color: var(--cb-vscode-input-background);
  color: var(--cb-text-primary);
  border: 1px solid var(--cb-vscode-widget-border);
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.action-btn:hover,
.plugin-btn:hover {
  background-color: var(--cb-vscode-list-hoverBackground);
  border-color: var(--cb-accent);
}

.plugin-btn.active {
  background-color: var(--cb-accent);
  color: white;
  border-color: var(--cb-accent-light);
}

.action-btn svg,
.plugin-btn svg {
  flex-shrink: 0;
}
</style>

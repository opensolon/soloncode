<script setup lang="ts">
import { type Theme } from '../types';

interface Props {
  title: string;
  status: string;
  theme: Theme;
}

interface Emits {
  (e: 'toggle-theme'): void;
}

const props = defineProps<Props>();
defineEmits<Emits>();
</script>

<template>
  <header class="chat-header">
    <div class="chat-title">
      <h2>{{ title }}</h2>
      <span class="chat-status">{{ status === 'active' ? '进行中' : '已完成' }}</span>
    </div>
    <button
      class="theme-toggle-btn"
      :title="theme === 'dark' ? '切换到亮色模式' : '切换到暗色模式'"
      @click="$emit('toggle-theme')"
    >
      <svg v-if="theme === 'dark'" width="20" height="20" viewBox="0 0 20 20" fill="none">
        <circle cx="10" cy="10" r="4" stroke="currentColor" stroke-width="2"/>
        <path d="M10 2V4M10 16V18M18 10H16M4 10H2M15.66 15.66L14.24 14.24M5.76 5.76L4.34 4.34M15.66 4.34L14.24 5.76M5.76 14.24L4.34 15.66" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
      </svg>
      <svg v-else width="20" height="20" viewBox="0 0 20 20" fill="none">
        <path d="M10 18C13.866 18 17 14.866 17 11C17 7.13401 13.866 4 10 4C10 4 10 4 10 4C6.13401 4 3 7.13401 3 11C3 14.866 6.13401 18 10 18Z" stroke="currentColor" stroke-width="2"/>
        <path d="M10 4V2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        <path d="M10 18V16" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        <path d="M15.66 6.34L14.24 4.92" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        <path d="M4.34 15.66L5.76 14.24" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        <path d="M18 11H16" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        <path d="M4 11H2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        <path d="M15.66 15.66L14.24 14.24" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        <path d="M4.34 6.34L5.76 4.92" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
      </svg>
    </button>
  </header>
</template>

<style scoped>
.chat-header {
  padding: 16px 24px;
  border-bottom: 1px solid var(--cb-vscode-panel-border);
  background-color: var(--cb-vscode-editor-background);
  display: flex;
  justify-content: space-between;
  align-items: center;
  transition: background-color 0.3s, border-color 0.3s;
}

.chat-title h2 {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 4px;
  color: var(--cb-text-primary);
}

.chat-status {
  font-size: 12px;
  color: var(--cb-text-secondary);
  background-color: var(--cb-vscode-input-background);
  padding: 2px 8px;
  border-radius: 4px;
}

.theme-toggle-btn {
  width: 36px;
  height: 36px;
  background-color: var(--cb-vscode-input-background);
  color: var(--cb-text-primary);
  border: 1px solid var(--cb-vscode-widget-border);
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
  flex-shrink: 0;
}

.theme-toggle-btn:hover {
  background-color: var(--cb-vscode-list-hoverBackground);
  border-color: var(--cb-accent);
}

.theme-toggle-btn svg {
  color: var(--cb-text-primary);
  transition: transform 0.3s;
}

.theme-toggle-btn:hover svg {
  transform: rotate(180deg);
}
</style>

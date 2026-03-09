<script setup lang="ts">
import { type Plugin } from '../types';

interface Props {
  plugins: Plugin[];
  show: boolean;
}

interface Emits {
  (e: 'toggle', pluginId: string): void;
}

const props = defineProps<Props>();
defineEmits<Emits>();
</script>

<template>
  <div v-if="show" class="plugin-panel">
    <div class="plugin-list">
      <div
        v-for="plugin in plugins"
        :key="plugin.id"
        class="plugin-item"
      >
        <div class="plugin-info">
          <span class="plugin-icon">{{ plugin.icon }}</span>
          <div class="plugin-details">
            <div class="plugin-name">{{ plugin.name }}</div>
            <div class="plugin-description">{{ plugin.description }}</div>
          </div>
        </div>
        <div class="plugin-actions">
          <span class="plugin-version">v{{ plugin.version }}</span>
          <button
            class="plugin-toggle"
            :class="{ enabled: plugin.enabled }"
            @click="$emit('toggle', plugin.id)"
          >
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <path d="M3 7H11M3 7V5M3 7V9M11 7H13M11 7V5M11 7V9" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.plugin-panel {
  padding: 8px;
  border-bottom: 1px solid var(--cb-vscode-panel-border);
  background-color: var(--cb-vscode-input-background);
  max-height: 300px;
  overflow-y: auto;
}

.plugin-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.plugin-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  background-color: var(--cb-vscode-sideBar-background);
  border-radius: 6px;
  transition: background-color 0.2s;
}

.plugin-item:hover {
  background-color: var(--cb-vscode-list-hoverBackground);
}

.plugin-info {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
}

.plugin-icon {
  font-size: 18px;
  flex-shrink: 0;
}

.plugin-details {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.plugin-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--cb-text-primary);
}

.plugin-description {
  font-size: 11px;
  color: var(--cb-text-secondary);
}

.plugin-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.plugin-version {
  font-size: 11px;
  color: var(--cb-text-secondary);
  font-family: monospace;
}

.plugin-toggle {
  width: 28px;
  height: 20px;
  background-color: var(--cb-vscode-panel-border);
  border: none;
  border-radius: 10px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  padding: 2px;
  transition: background-color 0.2s;
}

.plugin-toggle.enabled {
  background-color: var(--cb-success);
  justify-content: flex-end;
}

.plugin-toggle svg {
  color: white;
  transition: transform 0.2s;
}
</style>

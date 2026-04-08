import { useState, useEffect, useRef } from 'react';
import { Icon, type IconName } from '../common/Icon';
import {
  type McpServerConfig,
  type ModelProvider,
  type ProviderType,
  PROVIDER_PRESETS,
  createProvider,
} from '../../services/settingsService';
import './SettingsPanel.css';

export interface Settings {
  // 常规
  theme: 'dark' | 'light';
  fontSize: number;
  language: string;
  tabSize: number;
  autoSave: boolean;
  formatOnSave: boolean;
  shell: string;
  terminalFontSize: number;

  // 模型供应商
  providers: ModelProvider[];
  activeProviderId: string;
  maxSteps: number;

  // MCP 服务器
  mcpServers: McpServerConfig[];
}

type SettingsMenuKey = 'general' | 'model' | 'mcp';

interface SettingsPanelProps {
  visible: boolean;
  settings: Settings;
  onSettingsChange: (settings: Settings) => void;
  onClose: () => void;
}

const menuItems: { key: SettingsMenuKey; icon: IconName; label: string }[] = [
  { key: 'general', icon: 'settings', label: '常规' },
  { key: 'model', icon: 'bot', label: '模型' },
  { key: 'mcp', icon: 'extensions', label: 'MCP 服务器' },
];

export function SettingsPanel({ visible, settings, onSettingsChange, onClose }: SettingsPanelProps) {
  const [activeMenu, setActiveMenu] = useState<SettingsMenuKey>('general');
  const [localSettings, setLocalSettings] = useState(settings);
  const overlayRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (visible) {
      setLocalSettings(settings);
      setActiveMenu('general');
    }
  }, [visible, settings]);

  useEffect(() => {
    if (!visible) return;
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [visible, onClose]);

  if (!visible) return null;

  function updateSetting<K extends keyof Settings>(key: K, value: Settings[K]) {
    setLocalSettings(prev => ({ ...prev, [key]: value }));
  }

  function handleSave() {
    onSettingsChange(localSettings);
    onClose();
  }

  // ---- MCP ----
  function handleAddMcpServer() {
    setLocalSettings(prev => ({
      ...prev,
      mcpServers: [...prev.mcpServers, { name: '', command: '', args: [], enabled: true }],
    }));
  }
  function handleRemoveMcpServer(index: number) {
    setLocalSettings(prev => ({
      ...prev,
      mcpServers: prev.mcpServers.filter((_, i) => i !== index),
    }));
  }
  function handleUpdateMcpServer(index: number, updates: Partial<McpServerConfig>) {
    setLocalSettings(prev => ({
      ...prev,
      mcpServers: prev.mcpServers.map((s, i) => i === index ? { ...s, ...updates } : s),
    }));
  }

  // ---- Provider ----
  function handleAddProvider(type: ProviderType) {
    const p = createProvider(type);
    setLocalSettings(prev => ({
      ...prev,
      providers: [...prev.providers, p],
      activeProviderId: prev.activeProviderId || p.id,
    }));
  }
  function handleRemoveProvider(id: string) {
    setLocalSettings(prev => {
      const next = prev.providers.filter(p => p.id !== id);
      return {
        ...prev,
        providers: next,
        activeProviderId: prev.activeProviderId === id ? (next[0]?.id || '') : prev.activeProviderId,
      };
    });
  }
  function handleUpdateProvider(id: string, updates: Partial<ModelProvider>) {
    setLocalSettings(prev => ({
      ...prev,
      providers: prev.providers.map(p => p.id === id ? { ...p, ...updates } : p),
    }));
  }

  return (
    <div className="settings-modal-overlay" ref={overlayRef} onClick={(e) => {
      if (e.target === overlayRef.current) onClose();
    }}>
      <div className="settings-modal">
        <div className="settings-modal-header">
          <span className="settings-modal-title">设置</span>
          <button className="settings-modal-close" onClick={onClose}>
            <Icon name="close" size={16} />
          </button>
        </div>

        <div className="settings-modal-content">
          <div className="settings-menu">
            {menuItems.map(item => (
              <div
                key={item.key}
                className={`settings-menu-item${activeMenu === item.key ? ' active' : ''}`}
                onClick={() => setActiveMenu(item.key)}
              >
                <Icon name={item.icon} size={16} />
                <span>{item.label}</span>
              </div>
            ))}
          </div>

          <div className="settings-detail">
            {activeMenu === 'general' && (
              <GeneralSettings settings={localSettings} updateSetting={updateSetting} />
            )}
            {activeMenu === 'model' && (
              <ModelSettings
                settings={localSettings}
                updateSetting={updateSetting}
                providers={localSettings.providers}
                activeProviderId={localSettings.activeProviderId}
                onAddProvider={handleAddProvider}
                onRemoveProvider={handleRemoveProvider}
                onUpdateProvider={handleUpdateProvider}
                onSetActive={(id) => updateSetting('activeProviderId', id)}
              />
            )}
            {activeMenu === 'mcp' && (
              <McpSettings
                servers={localSettings.mcpServers}
                onAdd={handleAddMcpServer}
                onRemove={handleRemoveMcpServer}
                onUpdate={handleUpdateMcpServer}
              />
            )}
          </div>
        </div>

        <div className="settings-modal-footer">
          <button className="settings-btn cancel" onClick={onClose}>取消</button>
          <button className="settings-btn save" onClick={handleSave}>保存</button>
        </div>
      </div>
    </div>
  );
}

/* ==================== 常规设置 ==================== */
function GeneralSettings({ settings, updateSetting }: {
  settings: Settings;
  updateSetting: <K extends keyof Settings>(key: K, value: Settings[K]) => void;
}) {
  return (
    <div className="settings-section-content">
      <div className="settings-section-title">外观</div>
      <SettingRow label="主题">
        <select className="setting-select" value={settings.theme}
          onChange={e => updateSetting('theme', e.target.value as any)}>
          <option value="dark">暗色</option>
          <option value="light">亮色</option>
        </select>
      </SettingRow>
      <SettingRow label="字体大小">
        <input type="number" className="setting-input number" value={settings.fontSize}
          onChange={e => updateSetting('fontSize', parseInt(e.target.value) || 14)}
          min={10} max={24} />
      </SettingRow>
      <SettingRow label="语言">
        <select className="setting-select" value={settings.language}
          onChange={e => updateSetting('language', e.target.value)}>
          <option value="zh-CN">中文</option>
          <option value="en-US">English</option>
        </select>
      </SettingRow>

      <div className="settings-section-title">编辑器</div>
      <SettingRow label="Tab 大小">
        <input type="number" className="setting-input number" value={settings.tabSize}
          onChange={e => updateSetting('tabSize', parseInt(e.target.value) || 2)} min={1} max={8} />
      </SettingRow>
      <SettingRow label="自动保存">
        <input type="checkbox" checked={settings.autoSave}
          onChange={e => updateSetting('autoSave', e.target.checked)} />
      </SettingRow>
      <SettingRow label="保存时格式化">
        <input type="checkbox" checked={settings.formatOnSave}
          onChange={e => updateSetting('formatOnSave', e.target.checked)} />
      </SettingRow>

      <div className="settings-section-title">终端</div>
      <SettingRow label="Shell">
        <select className="setting-select" value={settings.shell}
          onChange={e => updateSetting('shell', e.target.value)}>
          <option value="bash">Bash</option>
          <option value="zsh">Zsh</option>
          <option value="powershell">PowerShell</option>
          <option value="cmd">CMD</option>
        </select>
      </SettingRow>
      <SettingRow label="终端字体大小">
        <input type="number" className="setting-input number" value={settings.terminalFontSize}
          onChange={e => updateSetting('terminalFontSize', parseInt(e.target.value) || 14)}
          min={10} max={24} />
      </SettingRow>
    </div>
  );
}

/* ==================== 模型设置（多供应商） ==================== */
function ModelSettings({ settings, updateSetting, providers, activeProviderId, onAddProvider, onRemoveProvider, onUpdateProvider, onSetActive }: {
  settings: Settings;
  updateSetting: <K extends keyof Settings>(key: K, value: Settings[K]) => void;
  providers: ModelProvider[];
  activeProviderId: string;
  onAddProvider: (type: ProviderType) => void;
  onRemoveProvider: (id: string) => void;
  onUpdateProvider: (id: string, updates: Partial<ModelProvider>) => void;
  onSetActive: (id: string) => void;
}) {
  const [showAddMenu, setShowAddMenu] = useState(false);
  const activeProvider = providers.find(p => p.id === activeProviderId);

  return (
    <div className="settings-section-content">
      {/* 供应商列表 */}
      <div className="settings-section-title" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span>模型供应商</span>
        <div style={{ position: 'relative' }}>
          <button className="mcp-add-btn" onClick={() => setShowAddMenu(v => !v)}>+ 添加供应商</button>
          {showAddMenu && (
            <div className="provider-add-dropdown">
              {(Object.keys(PROVIDER_PRESETS) as ProviderType[]).map(type => (
                <div key={type} className="provider-add-item" onClick={() => {
                  onAddProvider(type);
                  setShowAddMenu(false);
                }}>
                  {PROVIDER_PRESETS[type].label}
                </div>
              ))}
              <div className="provider-add-item" onClick={() => {
                onAddProvider('custom');
                setShowAddMenu(false);
              }}>
                自定义
              </div>
            </div>
          )}
        </div>
      </div>

      {providers.length === 0 && (
        <div className="mcp-empty">暂无供应商，点击"添加供应商"配置 AI 模型</div>
      )}

      {/* 供应商卡片列表 */}
      {providers.map(p => (
        <div
          key={p.id}
          className={`provider-card${p.id === activeProviderId ? ' active' : ''}${!p.enabled ? ' disabled' : ''}`}
          onClick={() => onSetActive(p.id)}
        >
          <div className="provider-card-header">
            <div className="provider-card-info">
              <span className="provider-badge">{PROVIDER_PRESETS[p.type as keyof typeof PROVIDER_PRESETS]?.label || '自定义'}</span>
              <span className="provider-card-name">{p.name}</span>
            </div>
            <div className="provider-card-actions">
              <label className="checkbox-label" onClick={e => e.stopPropagation()}>
                <input type="checkbox" checked={p.enabled}
                  onChange={e => onUpdateProvider(p.id, { enabled: e.target.checked })} />
              </label>
              <button className="mcp-remove-btn" onClick={(e) => { e.stopPropagation(); onRemoveProvider(p.id); }}>
                <Icon name="close" size={12} />
              </button>
            </div>
          </div>
          <div className="provider-card-detail">
            <span>{p.model || '未选择模型'}</span>
            <span className="provider-card-sep">|</span>
            <span>{p.apiUrl ? new URL(p.apiUrl).host : '未配置'}</span>
          </div>
        </div>
      ))}

      {/* 当前选中供应商的编辑表单 */}
      {activeProvider && (
        <>
          <div className="settings-section-title">编辑：{activeProvider.name}</div>
          <SettingRow label="名称">
            <input type="text" className="setting-input" value={activeProvider.name}
              onChange={e => onUpdateProvider(activeProvider.id, { name: e.target.value })} />
          </SettingRow>
          <SettingRow label="类型">
            <select className="setting-select" value={activeProvider.type}
              onChange={e => {
                const t = e.target.value as ProviderType;
                const preset = PROVIDER_PRESETS[t as keyof typeof PROVIDER_PRESETS];
                const updates: Partial<ModelProvider> = { type: t, name: preset?.label || '自定义' };
                if (preset && !activeProvider.apiUrl) updates.apiUrl = preset.apiUrl;
                if (preset && !activeProvider.model) updates.model = preset.models[0]?.value || '';
                onUpdateProvider(activeProvider.id, updates);
              }}>
              {Object.entries(PROVIDER_PRESETS).map(([key, val]) => (
                <option key={key} value={key}>{val.label}</option>
              ))}
              <option value="custom">自定义</option>
            </select>
          </SettingRow>
          <SettingRow label="API 地址">
            <input type="text" className="setting-input" value={activeProvider.apiUrl}
              onChange={e => onUpdateProvider(activeProvider.id, { apiUrl: e.target.value })}
              placeholder="https://api.example.com/v1/chat/completions" />
          </SettingRow>
          <SettingRow label="API Key">
            <input type="password" className="setting-input" value={activeProvider.apiKey}
              onChange={e => onUpdateProvider(activeProvider.id, { apiKey: e.target.value })}
              placeholder="sk-..." />
          </SettingRow>
          <SettingRow label="模型">
            <ProviderModelSelect provider={activeProvider} onChange={m => onUpdateProvider(activeProvider.id, { model: m })} />
          </SettingRow>
        </>
      )}

      <div className="settings-section-title">Agent</div>
      <SettingRow label="最大步数">
        <input type="number" className="setting-input number" value={settings.maxSteps}
          onChange={e => updateSetting('maxSteps', parseInt(e.target.value) || 30)} min={1} max={100} />
      </SettingRow>
    </div>
  );
}

/** 模型选择：根据供应商类型显示预设模型或手动输入 */
function ProviderModelSelect({ provider, onChange }: { provider: ModelProvider; onChange: (model: string) => void }) {
  const preset = PROVIDER_PRESETS[provider.type as keyof typeof PROVIDER_PRESETS];
  const isPresetModel = preset?.models.some(m => m.value === provider.model);

  if (preset && (isPresetModel || !provider.model)) {
    return (
      <select className="setting-select" value={provider.model}
        onChange={e => onChange(e.target.value)}>
        {preset.models.map(m => <option key={m.value} value={m.value}>{m.label}</option>)}
        <option value="__custom__">自定义模型...</option>
      </select>
    );
  }

  return (
    <input type="text" className="setting-input" value={provider.model}
      onChange={e => onChange(e.target.value)} placeholder="模型名称" />
  );
}

/* ==================== MCP 服务器设置 ==================== */
function McpSettings({ servers, onAdd, onRemove, onUpdate }: {
  servers: McpServerConfig[];
  onAdd: () => void;
  onRemove: (index: number) => void;
  onUpdate: (index: number, updates: Partial<McpServerConfig>) => void;
}) {
  return (
    <div className="settings-section-content">
      <div className="settings-section-title" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span>MCP 服务器</span>
        <button className="mcp-add-btn" onClick={onAdd}>+ 添加</button>
      </div>

      {servers.length === 0 && (
        <div className="mcp-empty">暂无 MCP 服务器配置，点击上方"添加"按钮新增</div>
      )}

      {servers.map((server, index) => (
        <div key={index} className="mcp-server-card">
          <div className="mcp-server-header">
            <label className="checkbox-label">
              <input type="checkbox" checked={server.enabled}
                onChange={e => onUpdate(index, { enabled: e.target.checked })} />
              <span>启用</span>
            </label>
            <button className="mcp-remove-btn" onClick={() => onRemove(index)}>
              <Icon name="close" size={12} />
            </button>
          </div>
          <div className="mcp-server-fields">
            <div className="mcp-field">
              <label>名称</label>
              <input type="text" className="setting-input" value={server.name}
                onChange={e => onUpdate(index, { name: e.target.value })} placeholder="my-server" />
            </div>
            <div className="mcp-field">
              <label>命令</label>
              <input type="text" className="setting-input" value={server.command}
                onChange={e => onUpdate(index, { command: e.target.value })} placeholder="npx" />
            </div>
            <div className="mcp-field">
              <label>参数（空格分隔）</label>
              <input type="text" className="setting-input"
                value={server.args.join(' ')}
                onChange={e => onUpdate(index, { args: e.target.value.split(' ').filter(Boolean) })}
                placeholder="-y @modelcontextprotocol/server-memory" />
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}

/* ==================== 通用行组件 ==================== */
function SettingRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="setting-item">
      <label className="setting-label">{label}</label>
      <div className="setting-control">{children}</div>
    </div>
  );
}

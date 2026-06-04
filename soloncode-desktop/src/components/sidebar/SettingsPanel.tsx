import { useState, useEffect, useCallback } from 'react';
import { Icon, type IconName } from '../common/Icon';
import {
  type McpServerConfig,
  type SkillConfig,
  type AgentConfig,
  type ModelProvider,
  type ProviderType,
  type GeneralSettings,
  PROVIDER_PRESETS,
  DEFAULT_PROMPTS,
  createProvider,
} from '../../services/settingsService';
import { fileService } from '../../services/fileService';
import './SettingsPanel.css';
import './ChannelPanel.css';

export interface Settings extends GeneralSettings {
  providers: ModelProvider[];
  mcpServers: McpServerConfig[];
  skills: SkillConfig[];
  agents: AgentConfig[];
}

type SettingsMenuKey = 'general' | 'model' | 'channels' | 'mcp' | 'skills' | 'prompts' | 'logs';

interface SettingsPanelProps {
  visible: boolean;
  settings: Settings;
  onSettingsChange: (settings: Settings) => void;
  onClose: () => void;
  backendPort?: number | null;
  workspacePath?: string | null;
  sessionId?: string;
}

const menuItems: { key: SettingsMenuKey; icon: IconName; label: string }[] = [
  { key: 'general', icon: 'settings', label: '常规' },
  { key: 'model', icon: 'bot', label: '模型' },
  { key: 'channels', icon: 'channels', label: '渠道绑定' },
  { key: 'mcp', icon: 'extensions', label: 'MCP 服务器' },
  { key: 'skills', icon: 'skills', label: 'Skills' },
  { key: 'prompts', icon: 'edit', label: 'AI 提示词' },
  ...(import.meta.env.DEV ? [{ key: 'logs' as SettingsMenuKey, icon: 'terminal' as IconName, label: '日志' }] : []),
];

export function SettingsPanel({ visible, settings, onSettingsChange, onClose, backendPort, workspacePath, sessionId }: SettingsPanelProps) {
  const [activeMenu, setActiveMenu] = useState<SettingsMenuKey>('general');
  const [localSettings, setLocalSettings] = useState(settings);

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
    <div className="settings-modal-overlay" onClick={onClose}>
      <div className="settings-modal" onClick={(e) => e.stopPropagation()}>
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
                backendPort={backendPort}
              />
            )}
            {activeMenu === 'channels' && (
              <ChannelSettings backendPort={backendPort} sessionId={sessionId} />
            )}
            {activeMenu === 'mcp' && (
              <McpSettings
                servers={localSettings.mcpServers}
                onAdd={handleAddMcpServer}
                onRemove={handleRemoveMcpServer}
                onUpdate={handleUpdateMcpServer}
              />
            )}
            {activeMenu === 'skills' && (
              <SkillsSettings backendPort={backendPort} />
            )}
            {activeMenu === 'prompts' && (
              <PromptsSettings
                skillPrompt={localSettings.skillPrompt}
                agentPrompt={localSettings.agentPrompt}
                gitPrompt={localSettings.gitPrompt}
                onPromptChange={(key, value) => setLocalSettings(prev => ({ ...prev, [key]: value }))}
              />
            )}
            {activeMenu === 'logs' && (
              <LogsSettings workspacePath={workspacePath} />
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
      <SettingRow label="编辑器主题">
        <select className="setting-select" value={settings.editorTheme}
          onChange={e => updateSetting('editorTheme', e.target.value)}>
          <option value="vs-dark">VS Dark</option>
          <option value="light">VS Light</option>
          <option value="hc-black">High Contrast Dark</option>
          <option value="hc-light">High Contrast Light</option>
        </select>
      </SettingRow>
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

      <div className="settings-section-title">后端服务</div>
      <SettingRow label="CLI 端口">
        <input type="number" className="setting-input number" value={settings.cliPort}
          onChange={e => updateSetting('cliPort', parseInt(e.target.value) || 4808)}
          min={1024} max={65535} />
      </SettingRow>
    </div>
  );
}

/* ==================== 模型设置（多供应商） ==================== */
function ModelSettings({ settings, updateSetting, providers, activeProviderId, onAddProvider, onRemoveProvider, onUpdateProvider, onSetActive, backendPort }: {
  settings: Settings;
  updateSetting: <K extends keyof Settings>(key: K, value: Settings[K]) => void;
  providers: ModelProvider[];
  activeProviderId: string;
  onAddProvider: (type: ProviderType) => void;
  onRemoveProvider: (id: string) => void;
  onUpdateProvider: (id: string, updates: Partial<ModelProvider>) => void;
  onSetActive: (id: string) => void;
  backendPort?: number | null;
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
            <span>{p.apiUrl ? (() => { try { return new URL(p.apiUrl).host; } catch { return p.apiUrl; } })() : '未配置'}</span>
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
                const updates: Partial<ModelProvider> = { type: t };
                if (preset) {
                  updates.name = preset.label;
                  updates.apiUrl = preset.apiUrl;
                  updates.model = preset.models[0]?.value || '';
                } else {
                  updates.name = '自定义';
                  updates.apiUrl = '';
                  updates.model = '';
                }
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
            <ApiKeyInput value={activeProvider.apiKey} onChange={v => onUpdateProvider(activeProvider.id, { apiKey: v })} />
          </SettingRow>
          <SettingRow label="模型">
            <ProviderModelSelect provider={activeProvider} onChange={m => onUpdateProvider(activeProvider.id, { model: m })} onModelsLoaded={models => onUpdateProvider(activeProvider.id, { availableModels: models })} backendPort={backendPort} />
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

const FALLBACK_PORT = 4808;

/** 模型选择：手动点击测试按钮获取，结果持久化到 provider */
function ProviderModelSelect({ provider, onChange, onModelsLoaded, backendPort }: {
  provider: ModelProvider;
  onChange: (model: string) => void;
  onModelsLoaded: (models: { id: string; ownedBy?: string }[]) => void;
  backendPort?: number | null;
}) {
  const models = provider.availableModels || [];
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleFetch = useCallback(async () => {
    if (!provider.apiUrl || !provider.apiKey) {
      setError('请填写完整的 API 地址和密钥');
      return;
    }

    const port = backendPort || FALLBACK_PORT;
    setLoading(true);
    setError('');

    try {
      const url = `http://localhost:${port}/chat/models/fetch?apiUrl=${encodeURIComponent(provider.apiUrl)}&apiKey=${encodeURIComponent(provider.apiKey)}&provider=${encodeURIComponent(provider.type)}`;
      const resp = await fetch(url);
      if (!resp.ok) {
        setError('API地址或密钥不正确');
        return;
      }

      const result = await resp.json();
      const modelList = result.data;
      if (!Array.isArray(modelList) || modelList.length === 0) {
        setError('未获取到可用模型');
        return;
      }

      onModelsLoaded(modelList);
      if (!provider.model && modelList.length > 0) {
        onChange(modelList[0].id);
      }
      setError('');
    } catch (err) {
      console.error('[SettingsPanel] fetchModels error:', err);
      setError('后端服务未就绪或API地址不正确');
    } finally {
      setLoading(false);
    }
  }, [backendPort, provider.apiUrl, provider.apiKey, provider.type, provider.model, onChange, onModelsLoaded]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '4px', width: '100%' }}>
      <div style={{ display: 'flex', gap: '6px' }}>
        <button className="mcp-add-btn" style={{ whiteSpace: 'nowrap', flexShrink: 0 }}
          onClick={handleFetch} disabled={loading}>
          {loading ? '加载中...' : '获取模型'}
        </button>
        {models.length > 0 ? (
          <select className="setting-select" value={provider.model}
            onChange={e => onChange(e.target.value)} style={{ flex: 1, minWidth: 0 }}>
            <option value="">选择模型...</option>
            {models.map(m => (
              <option key={m.id} value={m.id}>{m.id}</option>
            ))}
          </select>
        ) : (
          <input type="text" className="setting-input" value={provider.model}
            onChange={e => onChange(e.target.value)} placeholder="模型名称"
            disabled={loading} style={{ flex: 1, minWidth: 0 }} />
        )}
      </div>
      {error && <span style={{ fontSize: '11px', color: '#f87171' }}>{error}</span>}
      {models.length > 0 && <span style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>已加载 {models.length} 个模型</span>}
    </div>
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

/* ==================== Skills 设置（挂载池 + 市场） ==================== */
function SkillsSettings({ backendPort }: { backendPort?: number | null }) {
  const [mounts, setMounts] = useState<Array<{ alias: string; path: string; system: boolean }>>([]);
  const [poolSkills, setPoolSkills] = useState<Record<string, Array<{ name: string; description: string }>>>({});
  const [loading, setLoading] = useState(false);
  const [showAddPool, setShowAddPool] = useState(false);
  const [newAlias, setNewAlias] = useState("");
  const [newPath, setNewPath] = useState("");
  const [addError, setAddError] = useState("");
  const [markets, setMarkets] = useState<Array<{ name: string; description: string }>>([]);
  const [selectedMarket, setSelectedMarket] = useState("");
  const [marketItems, setMarketItems] = useState<Array<any>>([]);
  const [searchQuery, setSearchQuery] = useState("");
  const [marketLoading, setMarketLoading] = useState(false);
  const [installingSlug, setInstallingSlug] = useState<string | null>(null);
  const [collapsedPools, setCollapsedPools] = useState<Set<string>>(new Set());

  const baseUrl = backendPort ? `http://localhost:${backendPort}` : "";

  const fetchJson = useCallback(async (path: string, params?: Record<string, string>) => {
    if (!baseUrl) return null;
    const url = new URL(baseUrl + path);
    if (params) Object.entries(params).forEach(([k, v]) => { if (v) url.searchParams.set(k, v); });
    const resp = await fetch(url.toString());
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const json = await resp.json();
    if (json.code !== undefined && json.code !== 200) throw new Error(json.description || json.msg || "Error");
    return json.data ?? json;
  }, [baseUrl]);

  const postJson = useCallback(async (path: string, body: Record<string, string>) => {
    if (!baseUrl) return null;
    const resp = await fetch(baseUrl + path, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(body).toString(),
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const json = await resp.json();
    if (json.code !== undefined && json.code !== 200) throw new Error(json.description || json.msg || "Error");
    return json.data ?? json;
  }, [baseUrl]);

  const loadMounts = useCallback(async () => {
    if (!baseUrl) return;
    setLoading(true);
    try {
      const list = await fetchJson("/web/settings/mounts") || [];
      setMounts(list);
      const skillsMap: Record<string, Array<{ name: string; description: string }>> = {};
      await Promise.all(list.map(async (m: any) => {
        try { skillsMap[m.alias] = await fetchJson("/web/settings/mounts/skills", { alias: m.alias }) || []; }
        catch { skillsMap[m.alias] = []; }
      }));
      setPoolSkills(skillsMap);
    } catch (err) { console.warn("[SkillsSettings] load mounts failed:", err); }
    finally { setLoading(false); }
  }, [baseUrl, fetchJson]);

  const loadMarkets = useCallback(async () => {
    if (!baseUrl) return;
    try {
      const list = await fetchJson("/web/settings/skills/markets") || [];
      setMarkets(list);
      if (list.length > 0 && !selectedMarket) setSelectedMarket(list[0].name);
    } catch (err) { console.warn("[SkillsSettings] load markets failed:", err); }
  }, [baseUrl, fetchJson, selectedMarket]);

  const browseMarket = useCallback(async (query?: string) => {
    if (!baseUrl || !selectedMarket) return;
    setMarketLoading(true);
    try {
      const params: Record<string, string> = { action: query ? "search" : "trending", marketName: selectedMarket };
      if (query) params.q = query;
      params.limit = "20";
      const items = await fetchJson("/web/settings/skills/proxy", params) || [];
      setMarketItems(items);
    } catch { setMarketItems([]); }
    finally { setMarketLoading(false); }
  }, [baseUrl, selectedMarket, fetchJson]);

  useEffect(() => { loadMounts(); }, [loadMounts]);
  useEffect(() => { loadMarkets(); }, [loadMarkets]);
  useEffect(() => { if (selectedMarket) browseMarket(searchQuery || undefined); }, [selectedMarket]);

  const handleAddPool = async () => {
    if (!backendPort || !newAlias.trim() || !newPath.trim()) return;
    setAddError("");
    try {
      const alias = newAlias.trim().startsWith("@") ? newAlias.trim() : "@" + newAlias.trim();
      await postJson("/web/settings/mounts/add", { alias, path: newPath.trim() });
      setShowAddPool(false); setNewAlias(""); setNewPath("");
      await loadMounts();
    } catch (err) { setAddError(String(err)); }
  };

  const handleRemovePool = async (alias: string) => {
    if (!backendPort) return;
    try { await postJson("/web/settings/mounts/remove", { alias }); await loadMounts(); }
    catch (err) { console.warn("remove pool failed:", err); }
  };

  const handleRemoveSkill = async (alias: string, skillName: string) => {
    if (!backendPort) return;
    try { await postJson("/web/settings/mounts/skills/remove", { alias, skillName }); await loadMounts(); }
    catch (err) { console.warn("remove skill failed:", err); }
  };

  const handleInstall = async (slug: string, mountAlias: string) => {
    if (!backendPort) return;
    setInstallingSlug(slug);
    try { await postJson("/web/settings/skills/install", { slug, marketName: selectedMarket, mountAlias }); await loadMounts(); }
    catch (err) { console.warn("install failed:", err); }
    finally { setInstallingSlug(null); }
  };

  const togglePool = (alias: string) => {
    setCollapsedPools(prev => { const next = new Set(prev); if (next.has(alias)) next.delete(alias); else next.add(alias); return next; });
  };

  return (
    <div className="settings-section-content skills-settings-layout">
      <div className="skills-col skills-col-pools">
        <div className="skills-col-header">
          <span className="skills-col-title">挂载池</span>
          <button className="mcp-add-btn" onClick={loadMounts}>刷新</button>
        </div>
        <div className="skills-col-body">
          {!backendPort && <div className="mcp-empty">等待后端连接...</div>}
          {backendPort && loading && <div className="mcp-empty">加载中...</div>}
          {backendPort && !loading && mounts.length === 0 && <div className="mcp-empty">暂无挂载池</div>}
          {backendPort && !loading && mounts.map(mount => {
            const collapsed = collapsedPools.has(mount.alias);
            const skills = poolSkills[mount.alias] || [];
            return (
              <div key={mount.alias} className="skill-pool-card">
                <div className="skill-pool-header" onClick={() => togglePool(mount.alias)}>
                  <span className="skill-pool-arrow">{collapsed ? "▶" : "▼"}</span>
                  <span className="skill-pool-alias">{mount.alias}</span>
                  <span className="skill-pool-count">{skills.length}</span>
                  {mount.system && <span className="skill-pool-badge">系统</span>}
                  {!mount.system && <button className="mcp-remove-btn" onClick={(e) => { e.stopPropagation(); handleRemovePool(mount.alias); }}><Icon name="close" size={12} /></button>}
                </div>
                {!collapsed && skills.length > 0 && (
                  <div className="skill-pool-skills">
                    {skills.map(skill => (
                      <div key={skill.name} className="skill-pool-skill-item">
                        <span>{skill.name}</span>
                        <button className="mcp-remove-btn" onClick={() => handleRemoveSkill(mount.alias, skill.name)}><Icon name="close" size={12} /></button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            );
          })}
          {backendPort && !loading && (
            showAddPool ? (
              <div className="skill-pool-card skill-add-form">
                <div className="mcp-field">
                  <label>别名</label>
                  <input type="text" className="setting-input" placeholder="@my-skills" value={newAlias} onChange={e => setNewAlias(e.target.value)} />
                </div>
                <div className="mcp-field">
                  <label>路径</label>
                  <input type="text" className="setting-input" placeholder="~/my-skills" value={newPath} onChange={e => setNewPath(e.target.value)} />
                </div>
                {addError && <div style={{ color: "#ef5350", fontSize: 11, padding: "4px 0" }}>{addError}</div>}
                <div className="skill-add-actions">
                  <button className="settings-btn cancel" onClick={() => { setShowAddPool(false); setNewAlias(""); setNewPath(""); setAddError(""); }}>取消</button>
                  <button className="settings-btn save" onClick={handleAddPool} disabled={!newAlias.trim() || !newPath.trim()}>添加</button>
                </div>
              </div>
            ) : (
              <button className="mcp-add-btn skill-add-pool-btn" onClick={() => setShowAddPool(true)}>+ 添加挂载池</button>
            )
          )}
        </div>
      </div>

      <div className="skills-col skills-col-market">
        <div className="skills-col-header">
          <span className="skills-col-title">市场</span>
          {markets.length > 1 && (
            <select className="setting-select skill-market-select" value={selectedMarket} onChange={e => setSelectedMarket(e.target.value)}>
              {markets.map(m => <option key={m.name} value={m.name}>{m.name}</option>)}
            </select>
          )}
        </div>
        <div className="skills-col-body">
          <input type="text" className="setting-input skill-market-search" placeholder="搜索 skill..." value={searchQuery} onChange={e => { setSearchQuery(e.target.value); setTimeout(() => browseMarket(e.target.value || undefined), 400); }} />
          {marketLoading && <div className="mcp-empty">加载中...</div>}
          {!marketLoading && marketItems.length === 0 && <div className="mcp-empty">{searchQuery ? "无搜索结果" : "暂无推荐"}</div>}
          {!marketLoading && marketItems.map((item: any) => (
            <div key={item.slug} className="skill-market-card">
              <div className="skill-market-card-header">
                <span className="skill-market-card-name">{item.displayName || item.name}</span>
                {item.ownerHandle && <span className="skill-market-card-author">@{item.ownerHandle}</span>}
              </div>
              <div className="skill-market-card-desc">{item.summary || item.description}</div>
              <div className="skill-market-card-footer">
                <div className="skill-market-card-meta">
                  {item.installs > 0 && <span>⬇ {item.installs}</span>}
                  {item.stars > 0 && <span>⭐ {item.stars}</span>}
                </div>
                {mounts.length <= 1 ? (
                  <button className="settings-btn save skill-install-btn" disabled={installingSlug === item.slug} onClick={() => handleInstall(item.slug, mounts[0]?.alias || "")}>{installingSlug === item.slug ? "安装中..." : "安装"}</button>
                ) : (
                  <select className="setting-select skill-install-select" value="" onChange={e => { if (e.target.value) handleInstall(item.slug, e.target.value); }}>
                    <option value="">安装到...</option>
                    {mounts.map(m => <option key={m.alias} value={m.alias}>{m.alias}</option>)}
           </select>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}



/* ==================== AI 提示词设置 ==================== */
type PromptKey = 'skillPrompt' | 'agentPrompt' | 'gitPrompt';

function PromptsSettings({ skillPrompt, agentPrompt, gitPrompt, onPromptChange }: {
  skillPrompt: string;
  agentPrompt: string;
  gitPrompt: string;
  onPromptChange: (key: PromptKey, value: string) => void;
}) {
  const values: Record<PromptKey, string> = { skillPrompt, agentPrompt, gitPrompt };
  const items: { key: PromptKey; label: string; placeholder: string }[] = [
    { key: 'skillPrompt', label: 'Skill 生成提示词', placeholder: '请帮我创建一个名为「{name}」的 Skill...' },
    { key: 'agentPrompt', label: 'Agent 生成提示词', placeholder: '请帮我创建一个名为「{name}」的 Agent...' },
    { key: 'gitPrompt', label: 'Git Commit 生成提示词', placeholder: '请根据以下 git diff 内容，生成一条简洁的 git commit message...' },
  ];

  return (
    <div className="settings-section-content">
      <div className="settings-section-title">AI 生成提示词</div>
      <div className="prompt-hint">
        支持 {'{name}'}、{'{description}'}、{'{diff}'} 占位符，创建时自动替换
      </div>
      {items.map(item => (
        <div key={item.key} className="mcp-server-card">
          <div className="mcp-server-header">
            <span style={{ fontSize: 12, fontWeight: 500, color: 'var(--cb-text-primary)' }}>{item.label}</span>
            <button className="mcp-remove-btn" title="重置为默认" onClick={() => onPromptChange(item.key, DEFAULT_PROMPTS[item.key])}
              style={{ color: 'var(--cb-text-secondary)', fontSize: 11 }}>
              重置
            </button>
          </div>
          <div className="mcp-server-fields">
            <textarea className="setting-input prompt-textarea" value={values[item.key]}
              onChange={e => onPromptChange(item.key, e.target.value)}
              placeholder={item.placeholder} rows={5} />
          </div>
        </div>
      ))}
    </div>
  );
}

/* ==================== 日志查看 ==================== */
function LogsSettings({ workspacePath }: { workspacePath?: string | null }) {
  const [activeLog, setActiveLog] = useState<'desktop' | 'cli'>('desktop');
  const [desktopLog, setDesktopLog] = useState('');
  const [cliLog, setCliLog] = useState('');
  const [loading, setLoading] = useState(false);

  const refreshLogs = useCallback(async () => {
    setLoading(true);
    try {
      const [dLog, cLog] = await Promise.all([
        fileService.readDesktopLog(),
        workspacePath ? fileService.readCliLog(workspacePath) : Promise.resolve(''),
      ]);
      setDesktopLog(dLog || '暂无日志');
      setCliLog(cLog || '暂无日志');
    } catch {
      setDesktopLog('读取失败');
      setCliLog('读取失败');
    }
    setLoading(false);
  }, [workspacePath]);

  useEffect(() => { refreshLogs(); }, [refreshLogs]);

  const content = activeLog === 'desktop' ? desktopLog : cliLog;

  return (
    <div className="settings-section-content">
      <div className="settings-section-title">
        日志
        <button className="settings-btn cancel" style={{ marginLeft: 'auto', padding: '2px 10px', fontSize: 12 }} onClick={refreshLogs} disabled={loading}>
          {loading ? '刷新中...' : '刷新'}
        </button>
      </div>
      <div style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
        <button className={`settings-btn ${activeLog === 'desktop' ? 'save' : 'cancel'}`} style={{ padding: '2px 12px', fontSize: 12 }} onClick={() => setActiveLog('desktop')}>桌面端日志</button>
        <button className={`settings-btn ${activeLog === 'cli' ? 'save' : 'cancel'}`} style={{ padding: '2px 12px', fontSize: 12 }} onClick={() => setActiveLog('cli')}>CLI 日志</button>
      </div>
      <pre style={{
        background: 'var(--bg-secondary, #1e1e1e)',
        color: 'var(--text-primary, #ccc)',
        padding: 12,
        borderRadius: 6,
        fontSize: 12,
        maxHeight: 400,
        overflow: 'auto',
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-all',
        margin: 0,
        fontFamily: 'monospace',
      }}>
        {content}
      </pre>
    </div>
  );
}

/* ==================== API Key 输入（带密码显隐切换） ==================== */
function ApiKeyInput({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const [visible, setVisible] = useState(false);

  return (
    <div style={{ position: 'relative', display: 'flex', alignItems: 'center', width: '100%' }}>
      <input
        type={visible ? 'text' : 'password'}
        className="setting-input"
        value={value}
        onChange={e => onChange(e.target.value)}
        placeholder="sk-..."
        style={{ paddingRight: '32px' }}
      />
      <button
        type="button"
        onClick={() => setVisible(v => !v)}
        style={{
          position: 'absolute', right: '4px', top: '50%', transform: 'translateY(-50%)',
          background: 'none', border: 'none', cursor: 'pointer', padding: '4px',
          color: 'var(--text-secondary)', display: 'flex', alignItems: 'center',
        }}
        title={visible ? '隐藏密钥' : '显示密钥'}
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          {visible ? (
            <>
              <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94" />
              <path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19" />
              <path d="M14.12 14.12a3 3 0 1 1-4.24-4.24" />
              <line x1="1" y1="1" x2="23" y2="23" />
            </>
          ) : (
            <>
              <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
              <circle cx="12" cy="12" r="3" />
            </>
          )}
        </svg>
      </button>
    </div>
  );
}

/* ==================== 渠道绑定设置 ==================== */
function ChannelSettings({ backendPort, sessionId }: { backendPort?: number | null; sessionId?: string }) {
  return (
    <div className="settings-section-content">
      <div className="settings-section-title">渠道绑定</div>
      <div style={{ padding: '12px 20px', display: 'flex', flexDirection: 'column', gap: 12 }}>
        <WeChatCard backendPort={backendPort} sessionId={sessionId} />
        <FeishuCard backendPort={backendPort} sessionId={sessionId} />
        <DingTalkCard backendPort={backendPort} sessionId={sessionId} />
      </div>
    </div>
  );
}

function WeChatCard({ backendPort, sessionId }: { backendPort?: number | null; sessionId?: string }) {
  const [qrCode, setQrCode] = useState<string | null>(null);
  const [showQR, setShowQR] = useState(false);
  const [status, setStatus] = useState('');
  const [bound, setBound] = useState(false);
  const [loading, setLoading] = useState(false);

  const fetchQR = useCallback(async () => {
    if (!backendPort) return;
    setLoading(true);
    setStatus('scanning');
    try {
      const sid = sessionId || 'default';
      const resp = await fetch(`http://localhost:${backendPort}/chat/wechat/qrcode?sessionId=${encodeURIComponent(sid)}`);
      const data = await resp.json();
      if (data.data?.qrcode_img_content) {
        setQrCode(data.data.qrcode_img_content);
        setShowQR(true);
        const poll = setInterval(async () => {
          try {
            const r = await fetch(`http://localhost:${backendPort}/chat/wechat/qrcode/status?qrcode=${encodeURIComponent(data.data.qrcode_img_content)}&sessionId=${encodeURIComponent(sid)}`);
            const d = await r.json();
            if (d.data?.status === 'confirmed') {
              clearInterval(poll);
              setBound(true);
              setStatus('bound');
              setShowQR(false);
            } else if (d.data?.status === 'error' || d.data?.status === 'expired') {
              clearInterval(poll);
              setStatus('expired');
              setShowQR(false);
            }
          } catch {
            clearInterval(poll);
            setStatus('error');
            setShowQR(false);
          }
        }, 2000);
        setTimeout(() => { clearInterval(poll); setStatus('timeout'); setShowQR(false); }, 60000);
      } else {
        setStatus('error');
      }
    } catch {
      setStatus('error');
    } finally {
      setLoading(false);
    }
  }, [backendPort, sessionId]);

  const unbind = useCallback(async () => {
    if (!backendPort) return;
    try {
      await fetch(`http://localhost:${backendPort}/chat/wechat/unbind?sessionId=${encodeURIComponent(sessionId || 'default')}`, { method: 'POST' });
      setBound(false);
      setStatus('');
    } catch { /* ignore */ }
  }, [backendPort, sessionId]);

  return (
    <div className="channel-card">
      <div className="channel-card-header">
        <div className="channel-card-icon wechat-icon">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M8.691 2.188C3.891 2.188 0 5.476 0 9.53c0 2.212 1.17 4.203 3.002 5.55a.59.59 0 0 1 .213.665l-.39 1.48c-.019.07-.048.141-.048.213 0 .163.13.295.29.295a.326.326 0 0 0 .167-.054l1.903-1.114a.864.864 0 0 1 .717-.098 10.16 10.16 0 0 0 2.837.403c.276 0 .543-.027.811-.05-.857-2.578.157-4.972 1.932-6.446 1.703-1.415 3.882-1.98 5.853-1.838-.576-3.583-4.196-6.348-8.596-6.348zM5.785 5.991c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 0 1-1.162 1.178A1.17 1.17 0 0 1 4.623 7.17c0-.651.52-1.18 1.162-1.18zm5.813 0c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 0 1-1.162 1.178 1.17 1.17 0 0 1-1.162-1.178c0-.651.52-1.18 1.162-1.18zm5.34 2.867c-1.797-.052-3.746.512-5.28 1.786-1.72 1.428-2.687 3.72-1.78 6.22.942 2.453 3.666 4.229 6.884 4.229.826 0 1.622-.12 2.361-.336a.722.722 0 0 1 .598.082l1.584.926a.272.272 0 0 0 .14.047c.134 0 .24-.111.24-.247 0-.06-.023-.12-.038-.177l-.327-1.233a.582.582 0 0 1-.023-.156.49.49 0 0 1 .201-.398C23.024 18.48 24 16.82 24 14.98c0-3.21-2.931-5.837-7.062-6.122zM14.033 13.4c.535 0 .969.44.969.982a.976.976 0 0 1-.969.983.976.976 0 0 1-.969-.983c0-.542.434-.982.97-.982zm4.844 0c.535 0 .969.44.969.982a.976.976 0 0 1-.969.983.976.976 0 0 1-.969-.983c0-.542.434-.982.97-.982z"/></svg>
        </div>
        <div className="channel-card-info">
          <span className="channel-card-name">微信</span>
          <span className="channel-card-desc">{bound ? '已绑定' : '扫码绑定，在微信中与 AI 对话'}</span>
        </div>
        <div className="channel-card-action">
          {bound ? (
            <button className="channel-btn unbind" onClick={unbind}>解绑</button>
          ) : (
            <button className="channel-btn bind" onClick={fetchQR} disabled={loading}>
              {loading ? '获取中...' : '获取二维码'}
            </button>
          )}
        </div>
      </div>
      {status === 'error' && <p className="channel-error">获取二维码失败</p>}
      {status === 'timeout' && <p className="channel-error">二维码已过期，请重新获取</p>}
      {showQR && qrCode && (
        <div className="qrcode-overlay" onClick={() => setShowQR(false)}>
          <div className="qrcode-modal" onClick={e => e.stopPropagation()}>
            <img src={qrCode} alt="微信二维码" className="qrcode-modal-img" />
            <p className="qrcode-modal-hint">请使用微信扫码关注</p>
            <button className="qrcode-modal-close" onClick={() => setShowQR(false)}>
              <Icon name="close" size={16} />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function FeishuCard({ backendPort, sessionId }: { backendPort?: number | null; sessionId?: string }) {
  const [appId, setAppId] = useState('');
  const [appSecret, setAppSecret] = useState('');
  const [bound, setBound] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState(false);

  const bind = useCallback(async () => {
    if (!backendPort || !appId || !appSecret) return;
    setLoading(true);
    setError('');
    try {
      const resp = await fetch(`http://localhost:${backendPort}/chat/feishu/bind`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: `sessionId=${encodeURIComponent(sessionId || 'default')}&appId=${encodeURIComponent(appId)}&appSecret=${encodeURIComponent(appSecret)}`,
      });
      const data = await resp.json();
      if (data.code === 200) setBound(true);
      else setError(data.description || '绑定失败');
    } catch { setError('连接失败'); } finally { setLoading(false); }
  }, [backendPort, sessionId, appId, appSecret]);

  const unbind = useCallback(async () => {
    if (!backendPort || !sessionId) return;
    try {
      await fetch(`http://localhost:${backendPort}/chat/feishu/unbind?sessionId=${encodeURIComponent(sessionId || 'default')}`, { method: 'POST' });
      setBound(false);
      setAppId('');
      setAppSecret('');
    } catch { /* ignore */ }
  }, [backendPort, sessionId]);

  return (
    <div className="channel-card">
      <div className="channel-card-header">
        <div className="channel-card-icon feishu-icon">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M3.5 7.5C5.5 4 9 2 12 2c2 0 4.5.5 6.5 3 1.5 2 2 4 2 6 0 2.5-1 5-3 6.5-2 1.5-4.5 2-7 2s-5-.5-7-2C1.5 16 .5 13.5.5 11c0-1.5.5-3 1.5-4L7 4l-2 5h7l-5 7 2-5H3.5z"/></svg>
        </div>
        <div className="channel-card-info">
          <span className="channel-card-name">飞书</span>
          <span className="channel-card-desc">{bound ? '已绑定' : '输入机器人凭据绑定'}</span>
        </div>
        <div className="channel-card-action">
          {bound ? (
            <button className="channel-btn unbind" onClick={unbind}>解绑</button>
          ) : (
            <button className="channel-btn bind" onClick={() => setExpanded(!expanded)}>
              {expanded ? '收起' : '绑定'}
            </button>
          )}
        </div>
      </div>
      {expanded && !bound && (
        <div className="channel-card-form">
          <input className="setting-input channel-form-input" placeholder="App ID" value={appId} onChange={e => setAppId(e.target.value)} />
          <input className="setting-input channel-form-input" placeholder="App Secret" type="password" value={appSecret} onChange={e => setAppSecret(e.target.value)} />
          <button className="channel-btn bind" onClick={bind} disabled={loading || !appId || !appSecret} style={{ alignSelf: 'flex-end' }}>
            {loading ? '绑定中...' : '确认绑定'}
          </button>
          {error && <p className="channel-error">{error}</p>}
        </div>
      )}
    </div>
  );
}

function DingTalkCard({ backendPort, sessionId }: { backendPort?: number | null; sessionId?: string }) {
  const [appKey, setAppKey] = useState('');
  const [appSecret, setAppSecret] = useState('');
  const [bound, setBound] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState(false);

  const bind = useCallback(async () => {
    if (!backendPort || !appKey || !appSecret) return;
    setLoading(true);
    setError('');
    try {
      const resp = await fetch(`http://localhost:${backendPort}/chat/dingtalk/bind`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: `sessionId=${encodeURIComponent(sessionId || 'default')}&appKey=${encodeURIComponent(appKey)}&appSecret=${encodeURIComponent(appSecret)}`,
      });
      const data = await resp.json();
      if (data.code === 200) setBound(true);
      else setError(data.description || '绑定失败');
    } catch { setError('连接失败'); } finally { setLoading(false); }
  }, [backendPort, sessionId, appKey, appSecret]);

  const unbind = useCallback(async () => {
    if (!backendPort || !sessionId) return;
    try {
      await fetch(`http://localhost:${backendPort}/chat/dingtalk/unbind?sessionId=${encodeURIComponent(sessionId || 'default')}`, { method: 'POST' });
      setBound(false);
      setAppKey('');
      setAppSecret('');
    } catch { /* ignore */ }
  }, [backendPort, sessionId]);

  return (
    <div className="channel-card">
      <div className="channel-card-header">
        <div className="channel-card-icon dingtalk-icon">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 15h-2v-2h2v2zm0-4h-2V7h2v6zm4 4h-2v-2h2v2zm0-4h-2V7h2v6z"/></svg>
        </div>
        <div className="channel-card-info">
          <span className="channel-card-name">钉钉</span>
          <span className="channel-card-desc">{bound ? '已绑定' : '输入机器人凭据绑定'}</span>
        </div>
        <div className="channel-card-action">
          {bound ? (
            <button className="channel-btn unbind" onClick={unbind}>解绑</button>
          ) : (
            <button className="channel-btn bind" onClick={() => setExpanded(!expanded)}>
              {expanded ? '收起' : '绑定'}
            </button>
          )}
        </div>
      </div>
      {expanded && !bound && (
        <div className="channel-card-form">
          <input className="setting-input channel-form-input" placeholder="AppKey" value={appKey} onChange={e => setAppKey(e.target.value)} />
          <input className="setting-input channel-form-input" placeholder="App Secret" type="password" value={appSecret} onChange={e => setAppSecret(e.target.value)} />
          <button className="channel-btn bind" onClick={bind} disabled={loading || !appKey || !appSecret} style={{ alignSelf: 'flex-end' }}>
            {loading ? '绑定中...' : '确认绑定'}
          </button>
          {error && <p className="channel-error">{error}</p>}
        </div>
      )}
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

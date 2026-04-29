import { useState, useEffect, useRef, useCallback } from 'react';
import { Icon, type IconName } from '../common/Icon';
import {
  type McpServerConfig,
  type SkillConfig,
  type ModelProvider,
  type ProviderType,
  PROVIDER_PRESETS,
  createProvider,
} from '../../services/settingsService';
import { fileService } from '../../services/fileService';
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

  // Skills
  skills: SkillConfig[];
}

type SettingsMenuKey = 'general' | 'model' | 'mcp' | 'skills' | 'logs';

interface SettingsPanelProps {
  visible: boolean;
  settings: Settings;
  onSettingsChange: (settings: Settings) => void;
  onClose: () => void;
  backendPort?: number | null;
  workspacePath?: string | null;
}

const menuItems: { key: SettingsMenuKey; icon: IconName; label: string }[] = [
  { key: 'general', icon: 'settings', label: '常规' },
  { key: 'model', icon: 'bot', label: '模型' },
  { key: 'mcp', icon: 'extensions', label: 'MCP 服务器' },
  { key: 'skills', icon: 'skills', label: 'Skills' },
  ...(import.meta.env.DEV ? [{ key: 'logs' as SettingsMenuKey, icon: 'terminal' as IconName, label: '日志' }] : []),
];

export function SettingsPanel({ visible, settings, onSettingsChange, onClose, backendPort, workspacePath }: SettingsPanelProps) {
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

  // ---- Skills ----
  function handleAddSkill() {
    setLocalSettings(prev => ({
      ...prev,
      skills: [...prev.skills, { name: '', description: '', path: '', enabled: true, source: 'manual' as const, group: 'project' as const }],
    }));
  }
  function handleRemoveSkill(index: number) {
    setLocalSettings(prev => ({
      ...prev,
      skills: prev.skills.filter((_, i) => i !== index),
    }));
  }
  function handleUpdateSkill(index: number, updates: Partial<SkillConfig>) {
    setLocalSettings(prev => ({
      ...prev,
      skills: prev.skills.map((s, i) => i === index ? { ...s, ...updates } : s),
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
                backendPort={backendPort}
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
            {activeMenu === 'skills' && (
              <SkillsSettings
                skills={localSettings.skills}
                onAdd={handleAddSkill}
                onRemove={handleRemoveSkill}
                onUpdate={handleUpdateSkill}
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

/* ==================== Skills 设置 ==================== */
function SkillsSettings({ skills, onAdd, onRemove, onUpdate }: {
  skills: SkillConfig[];
  onAdd: () => void;
  onRemove: (index: number) => void;
  onUpdate: (index: number, updates: Partial<SkillConfig>) => void;
}) {
  return (
    <div className="settings-section-content">
      <div className="settings-section-title" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span>Skills</span>
        <button className="mcp-add-btn" onClick={onAdd}>+ 添加</button>
      </div>

      {skills.length === 0 && (
        <div className="mcp-empty">暂无 Skill 配置，点击上方"添加"按钮新增</div>
      )}

      {skills.map((skill, index) => (
        <div key={index} className="mcp-server-card">
          <div className="mcp-server-header">
            <label className="checkbox-label">
              <input type="checkbox" checked={skill.enabled}
                onChange={e => onUpdate(index, { enabled: e.target.checked })} />
              <span>启用</span>
            </label>
            <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
              {skill.source === 'discovered' && (
                <span style={{ fontSize: '11px', color: 'var(--text-secondary)', opacity: 0.7 }}>自动发现</span>
              )}
              <button className="mcp-remove-btn" onClick={() => onRemove(index)}>
                <Icon name="close" size={12} />
              </button>
            </div>
          </div>
          <div className="mcp-server-fields">
            <div className="mcp-field">
              <label>名称</label>
              <input type="text" className="setting-input" value={skill.name}
                onChange={e => onUpdate(index, { name: e.target.value })} placeholder="my-skill" />
            </div>
            <div className="mcp-field">
              <label>描述</label>
              <input type="text" className="setting-input" value={skill.description}
                onChange={e => onUpdate(index, { description: e.target.value })} placeholder="Skill 描述" />
            </div>
            <div className="mcp-field">
              <label>路径</label>
              <input type="text" className="setting-input" value={skill.path}
                onChange={e => onUpdate(index, { path: e.target.value })} placeholder=".soloncode/skills/my-skill" />
            </div>
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

/* ==================== 通用行组件 ==================== */
function SettingRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="setting-item">
      <label className="setting-label">{label}</label>
      <div className="setting-control">{children}</div>
    </div>
  );
}

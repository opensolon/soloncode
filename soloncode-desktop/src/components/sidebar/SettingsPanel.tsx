import { useState, useEffect, useRef } from 'react';
import { Icon, type IconName } from '../common/Icon';
import './SettingsPanel.css';

export interface Settings {
  // AI 配置
  apiUrl: string;
  apiKey: string;
  model: string;
  maxSteps: number;

  // 外观配置
  theme: 'dark' | 'light';
  fontSize: number;
  language: string;

  // 编辑器配置
  tabSize: number;
  autoSave: boolean;
  formatOnSave: boolean;

  // 终端配置
  shell: string;
  terminalFontSize: number;
}

interface SettingsPanelProps {
  visible: boolean;
  settings: Settings;
  onSettingsChange: (settings: Settings) => void;
  onClose: () => void;
}

export function SettingsPanel({ visible, settings, onSettingsChange, onClose }: SettingsPanelProps) {
  const [searchQuery, setSearchQuery] = useState('');
  const [localSettings, setLocalSettings] = useState(settings);
  const overlayRef = useRef<HTMLDivElement>(null);

  // 打开时同步最新设置
  useEffect(() => {
    if (visible) {
      setLocalSettings(settings);
      setSearchQuery('');
    }
  }, [visible, settings]);

  // ESC 关闭
  useEffect(() => {
    if (!visible) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
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

  // 搜索过滤
  const sections: { key: string; icon: IconName; label: string; items: any[] }[] = [
    {
      key: 'ai',
      icon: 'bot',
      label: 'AI 配置',
      items: [
        { id: 'apiUrl', label: 'API 地址', type: 'text' as const },
        { id: 'apiKey', label: 'API Key', type: 'password' as const },
        { id: 'model', label: '模型', type: 'select' as const, options: [
          { value: 'glm-4.7', label: 'GLM-4.7' },
          { value: 'gpt-4', label: 'GPT-4' },
          { value: 'gpt-4o', label: 'GPT-4o' },
          { value: 'claude-3-opus', label: 'Claude 3 Opus' },
          { value: 'claude-3-sonnet', label: 'Claude 3 Sonnet' },
          { value: 'deepseek-chat', label: 'DeepSeek Chat' },
        ]},
        { id: 'maxSteps', label: '最大步数', type: 'number' as const, min: 1, max: 100 },
      ]
    },
    {
      key: 'appearance',
      icon: 'theme',
      label: '外观',
      items: [
        { id: 'theme', label: '主题', type: 'select' as const, options: [
          { value: 'dark', label: '暗色' },
          { value: 'light', label: '亮色' },
        ]},
        { id: 'fontSize', label: '字体大小', type: 'number' as const, min: 10, max: 24 },
        { id: 'language', label: '语言', type: 'select' as const, options: [
          { value: 'zh-CN', label: '中文' },
          { value: 'en-US', label: 'English' },
        ]},
      ]
    },
    {
      key: 'editor',
      icon: 'code',
      label: '编辑器',
      items: [
        { id: 'tabSize', label: 'Tab 大小', type: 'number' as const, min: 1, max: 8 },
        { id: 'autoSave', label: '自动保存', type: 'checkbox' as const },
        { id: 'formatOnSave', label: '保存时格式化', type: 'checkbox' as const },
      ]
    },
    {
      key: 'terminal',
      icon: 'terminal',
      label: '终端',
      items: [
        { id: 'shell', label: 'Shell', type: 'select' as const, options: [
          { value: 'bash', label: 'Bash' },
          { value: 'zsh', label: 'Zsh' },
          { value: 'powershell', label: 'PowerShell' },
          { value: 'cmd', label: 'CMD' },
        ]},
        { id: 'terminalFontSize', label: '终端字体大小', type: 'number' as const, min: 10, max: 24 },
      ]
    },
  ];

  const query = searchQuery.toLowerCase();
  const filteredSections = query
    ? sections.map(s => ({
        ...s,
        items: s.items.filter(item => item.label.toLowerCase().includes(query) || s.label.toLowerCase().includes(query)),
      })).filter(s => s.items.length > 0)
    : sections;

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

        <div className="settings-modal-search">
          <div className="search-wrapper">
            <Icon name="search" size={14} className="search-prefix" />
            <input
              type="text"
              className="search-input"
              placeholder="搜索设置..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
        </div>

        <div className="settings-modal-body">
          {filteredSections.map(section => (
            <div className="settings-section" key={section.key}>
              <div className="section-header">
                <Icon name={section.icon} size={16} />
                <span>{section.label}</span>
              </div>
              {section.items.map(item => (
                <div className={`setting-item${item.type === 'checkbox' ? ' checkbox' : ''}`} key={item.id}>
                  {item.type === 'checkbox' ? (
                    <label className="checkbox-label">
                      <input
                        type="checkbox"
                        checked={localSettings[item.id as keyof Settings] as boolean}
                        onChange={(e) => updateSetting(item.id as keyof Settings, e.target.checked)}
                      />
                      <span>{item.label}</span>
                    </label>
                  ) : (
                    <>
                      <label className="setting-label">{item.label}</label>
                      {item.type === 'select' ? (
                        <select
                          className="setting-select"
                          value={localSettings[item.id as keyof Settings] as string}
                          onChange={(e) => updateSetting(item.id as keyof Settings, e.target.value)}
                        >
                          {item.options?.map((opt: { value: string; label: string }) => (
                            <option key={opt.value} value={opt.value}>{opt.label}</option>
                          ))}
                        </select>
                      ) : (
                        <input
                          type={item.type}
                          className={`setting-input${item.type === 'number' ? ' number' : ''}`}
                          value={localSettings[item.id as keyof Settings] as string | number}
                          onChange={(e) => {
                            const val = item.type === 'number'
                              ? (parseInt(e.target.value) || 0)
                              : e.target.value;
                            updateSetting(item.id as keyof Settings, val as any);
                          }}
                          min={item.min}
                          max={item.max}
                          placeholder={item.type === 'text' ? '请输入...' : undefined}
                        />
                      )}
                    </>
                  )}
                </div>
              ))}
            </div>
          ))}
        </div>

        <div className="settings-modal-footer">
          <button className="settings-btn cancel" onClick={onClose}>取消</button>
          <button className="settings-btn save" onClick={handleSave}>保存</button>
        </div>
      </div>
    </div>
  );
}

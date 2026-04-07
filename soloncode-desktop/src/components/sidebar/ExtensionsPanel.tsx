import { useState } from 'react';
import { Icon } from '../common/Icon';
import './ExtensionsPanel.css';

interface Extension {
  id: string;
  name: string;
  description: string;
  version: string;
  installed: boolean;
  enabled: boolean;
  author: string;
}

interface ExtensionsPanelProps {
  extensions: Extension[];
  onInstall: (id: string) => Promise<void>;
  onUninstall: (id: string) => Promise<void>;
  onToggle: (id: string) => void;
}

export function ExtensionsPanel({ extensions, onInstall, onUninstall, onToggle }: ExtensionsPanelProps) {
  const [searchQuery, setSearchQuery] = useState('');

  const installedExtensions = extensions.filter(e => e.installed);
  const availableExtensions = extensions.filter(e => !e.installed);

  const filteredInstalled = installedExtensions.filter(e =>
    e.name.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const filteredAvailable = availableExtensions.filter(e =>
    e.name.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <div className="extensions-panel">
      <div className="panel-header">
        <span className="panel-title">扩展</span>
      </div>

      <div className="search-container">
        <div className="search-wrapper">
          <Icon name="search" size={14} className="search-prefix" />
          <input
            type="text"
            className="search-input"
            placeholder="搜索扩展..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
        </div>
      </div>

      <div className="extensions-list">
        {filteredInstalled.length > 0 && (
          <div className="extension-group">
            <div className="group-header">
              <span>已安装</span>
              <span className="group-count">{filteredInstalled.length}</span>
            </div>
            {filteredInstalled.map(ext => (
              <div key={ext.id} className="extension-item">
                <div className="extension-icon">
                  <Icon name="extensions" size={24} />
                </div>
                <div className="extension-info">
                  <div className="extension-name">{ext.name}</div>
                  <div className="extension-desc">{ext.description}</div>
                  <div className="extension-meta">
                    <span className="extension-author">{ext.author}</span>
                    <span className="extension-version">v{ext.version}</span>
                  </div>
                </div>
                <div className="extension-actions">
                  <button
                    className={`toggle-btn ${ext.enabled ? 'enabled' : ''}`}
                    onClick={() => onToggle(ext.id)}
                    title={ext.enabled ? '禁用' : '启用'}
                  >
                    <Icon name={ext.enabled ? 'success' : 'remove'} size={14} />
                  </button>
                  <button
                    className="uninstall-btn"
                    onClick={() => onUninstall(ext.id)}
                    title="卸载"
                  >
                    <Icon name="delete" size={14} />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}

        {filteredAvailable.length > 0 && (
          <div className="extension-group">
            <div className="group-header">
              <span>可用</span>
              <span className="group-count">{filteredAvailable.length}</span>
            </div>
            {filteredAvailable.map(ext => (
              <div key={ext.id} className="extension-item">
                <div className="extension-icon">
                  <Icon name="extensions" size={24} />
                </div>
                <div className="extension-info">
                  <div className="extension-name">{ext.name}</div>
                  <div className="extension-desc">{ext.description}</div>
                  <div className="extension-meta">
                    <span className="extension-author">{ext.author}</span>
                    <span className="extension-version">v{ext.version}</span>
                  </div>
                </div>
                <div className="extension-actions">
                  <button
                    className="install-btn"
                    onClick={() => onInstall(ext.id)}
                  >
                    安装
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}

        {!searchQuery && filteredInstalled.length === 0 && filteredAvailable.length === 0 && (
          <div className="empty-state">
            <Icon name="extensions" size={32} className="empty-icon" />
            <span className="empty-text">暂无扩展</span>
          </div>
        )}

        {searchQuery && filteredInstalled.length === 0 && filteredAvailable.length === 0 && (
          <div className="empty-state">
            <span className="empty-text">未找到匹配的扩展</span>
          </div>
        )}
      </div>
    </div>
  );
}

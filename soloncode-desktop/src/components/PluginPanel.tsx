import type { Plugin } from '../types';
import './PluginPanel.css';

interface PluginPanelProps {
  plugins: Plugin[];
  show: boolean;
  onToggle: (pluginId: string) => void;
}

export function PluginPanel({ plugins, show, onToggle }: PluginPanelProps) {
  if (!show) return null;

  return (
    <div className="plugin-panel">
      <div className="plugin-list">
        {plugins.map((plugin) => (
          <div key={plugin.id} className="plugin-item">
            <div className="plugin-info">
              <span className="plugin-icon">{plugin.icon}</span>
              <div className="plugin-details">
                <div className="plugin-name">{plugin.name}</div>
                <div className="plugin-description">{plugin.description}</div>
              </div>
            </div>
            <div className="plugin-actions">
              <span className="plugin-version">v{plugin.version}</span>
              <button
                className={`plugin-toggle${plugin.enabled ? ' enabled' : ''}`}
                onClick={() => onToggle(plugin.id)}
              >
                <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                  <path d="M3 7H11M3 7V5M3 7V9M11 7H13M11 7V5M11 7V9" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

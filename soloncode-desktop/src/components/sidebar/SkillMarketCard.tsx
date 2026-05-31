import { useState } from 'react';
import { Icon } from '../common/Icon';
import type { MarketItem, MountPool } from '../../services/skillService';
import './SkillMarketCard.css';

interface SkillMarketCardProps {
  item: MarketItem;
  mounts: MountPool[];
  installing: boolean;
  onInstall: (slug: string, mountAlias: string) => void;
}

export function SkillMarketCard({ item, mounts, installing, onInstall }: SkillMarketCardProps) {
  const [showPoolSelect, setShowPoolSelect] = useState(false);

  const handleInstall = (alias: string) => {
    setShowPoolSelect(false);
    onInstall(item.slug, alias);
  };

  return (
    <div className="market-card">
      <div className="market-card-header">
        <span className="market-card-name">{item.displayName || item.name}</span>
        {item.ownerHandle && <span className="market-card-author">@{item.ownerHandle}</span>}
      </div>
      <div className="market-card-desc">{item.summary || item.description}</div>
      <div className="market-card-footer">
        <div className="market-card-meta">
          {item.installs > 0 && <span title="安装量"><Icon name="download" size={12} /> {item.installs}</span>}
          {item.stars > 0 && <span title="星标"><Icon name="star" size={12} /> {item.stars}</span>}
        </div>
        <div className="market-card-actions">
          {!showPoolSelect ? (
            <button
              className="market-install-btn"
              disabled={installing}
              onClick={() => mounts.length === 1 ? handleInstall(mounts[0].alias) : setShowPoolSelect(true)}
            >
              {installing ? '安装中...' : '安装'}
            </button>
          ) : (
            <div className="pool-select-dropdown">
              {mounts.map(m => (
                <button key={m.alias} className="pool-select-item" onClick={() => handleInstall(m.alias)}>
                  {m.alias}
                </button>
              ))}
              <button className="pool-select-item cancel" onClick={() => setShowPoolSelect(false)}>取消</button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

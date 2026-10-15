import { useEffect, useMemo, useState } from 'react';
import { Icon } from '../common/Icon';
import { memoryService, type MemoryEntry } from '../../services/memoryService';
import './MemoryPanel.css';

interface MemoryPanelProps {
  backendPort?: number | null;
  refreshKey?: number;
  selectedKey?: string | null;
  onSelect: (key: string) => void;
  onCreate: () => void;
}

export function MemoryPanel({ backendPort, refreshKey = 0, selectedKey, onSelect, onCreate }: MemoryPanelProps) {
  const [entries, setEntries] = useState<MemoryEntry[]>([]);
  const [filter, setFilter] = useState('');
  const [busy, setBusy] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);
  const [message, setMessage] = useState<{ text: string; error?: boolean } | null>(null);

  useEffect(() => {
    let cancelled = false;
    setBusy(true);
    setMessage(null);
    if (!backendPort) {
      setEntries([]);
      setBusy(false);
      return;
    }
    memoryService.list(backendPort)
      .then(items => { if (!cancelled) setEntries(items); })
      .catch(() => { if (!cancelled) setMessage({ text: '长期记忆加载失败，请检查后端服务', error: true }); })
      .finally(() => { if (!cancelled) setBusy(false); });
    return () => { cancelled = true; };
  }, [backendPort, refreshKey, reloadKey]);

  const visibleEntries = useMemo(() => {
    const query = filter.trim().toLocaleLowerCase();
    if (!query) return entries;
    return entries.filter(entry => entry.key.toLocaleLowerCase().includes(query)
      || entry.content.toLocaleLowerCase().includes(query));
  }, [entries, filter]);

  return (
    <div className="memory-panel">
      <div className="panel-header">
        <span className="panel-title">长期记忆</span>
        <div className="panel-actions">
          <button className="panel-action" title="刷新" onClick={() => setReloadKey(key => key + 1)} disabled={busy || !backendPort}><Icon name="refresh" size={14} /></button>
          <button className="panel-action" title="新建记忆" onClick={onCreate}><Icon name="add" size={14} /></button>
        </div>
      </div>
      <div className="memory-search"><Icon name="search" size={13} /><input value={filter} onChange={event => setFilter(event.target.value)} placeholder="搜索记忆" /></div>
      {message && <div className={`memory-message${message.error ? ' error' : ''}`}>{message.text}</div>}
      <div className="memory-list">
        {!backendPort ? <div className="memory-empty">等待后端连接...</div> : busy && entries.length === 0 ? <div className="memory-empty">加载中...</div> : visibleEntries.length === 0 ? <div className="memory-empty">{filter.trim() ? '没有匹配的记忆' : '暂无记忆'}</div> : visibleEntries.map(entry => (
          <button key={entry.key} type="button" className={`memory-item${selectedKey === entry.key ? ' active' : ''}`} aria-pressed={selectedKey === entry.key} onClick={() => onSelect(entry.key)}>
            <span className="memory-item-title"><span className="memory-star">★{entry.importance}</span>{entry.key}</span>
            <span className="memory-item-preview">{entry.content}</span>
            {entry.time && <span className="memory-item-time">{entry.time}</span>}
          </button>
        ))}
      </div>
    </div>
  );
}

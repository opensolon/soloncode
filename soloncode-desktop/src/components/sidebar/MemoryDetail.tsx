import { useEffect, useRef, useState } from 'react';
import { Icon } from '../common/Icon';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { memoryService, validateMemoryEntry, type MemoryEntry } from '../../services/memoryService';
import './MemoryDetail.css';

interface MemoryDetailProps {
  backendPort?: number | null;
  entryKey: string | null;
  onSaved: (entry: MemoryEntry) => void;
  onDeleted: (key: string) => void;
  onClose: () => void;
}

const EMPTY_ENTRY: MemoryEntry = { key: '', content: '', importance: 5 };

export function MemoryDetail({ backendPort, entryKey, onSaved, onDeleted, onClose }: MemoryDetailProps) {
  const [draft, setDraft] = useState<MemoryEntry | null>(() => entryKey === null ? { ...EMPTY_ENTRY } : null);
  const [initial, setInitial] = useState<MemoryEntry | null>(() => entryKey === null ? { ...EMPTY_ENTRY } : null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);
  const mountedRef = useRef(false);
  const creating = entryKey === null;

  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  useEffect(() => {
    let cancelled = false;
    if (entryKey === null) return;
    setError('');
    setConfirmDelete(false);
    setDraft(null);
    setInitial(null);
    if (!backendPort) {
      setLoading(false);
      return;
    }
    setLoading(true);
    memoryService.get(backendPort, entryKey)
      .then(entry => {
        if (cancelled) return;
        setDraft(entry);
        setInitial(entry);
      })
      .catch(() => { if (!cancelled) setError('记忆详情加载失败，请重试'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [backendPort, entryKey, reloadKey]);

  const dirty = Boolean(draft && initial && (draft.key !== initial.key
    || draft.content !== initial.content || draft.importance !== initial.importance));
  const validationError = draft ? validateMemoryEntry(draft) : null;

  async function save() {
    if (!draft || validationError || !dirty || busy || !backendPort) return;
    const entry = { ...draft, key: draft.key.trim(), content: draft.content.trim() };
    setBusy(true);
    setError('');
    try {
      await memoryService.save(backendPort, entry);
      if (mountedRef.current) {
        setDraft(entry);
        setInitial(entry);
      }
      onSaved(entry);
    } catch (cause) {
      if (mountedRef.current) setError(cause instanceof Error ? cause.message : '保存失败，编辑内容已保留');
    } finally {
      if (mountedRef.current) setBusy(false);
    }
  }

  async function remove() {
    if (!entryKey || busy || !backendPort) return;
    setBusy(true);
    setError('');
    try {
      await memoryService.remove(backendPort, entryKey);
      onDeleted(entryKey);
    } catch {
      if (mountedRef.current) setError('删除失败，原数据已保留');
    } finally {
      if (mountedRef.current) {
        setBusy(false);
        setConfirmDelete(false);
      }
    }
  }

  return (
    <div className="memory-detail">
      <div className="memory-detail-header">
        <div><Icon name="memory" size={16} /><span>{creating ? '新建长期记忆' : '长期记忆详情'}</span></div>
        <button type="button" title="关闭记忆详情" disabled={busy} onClick={onClose}><Icon name="close" size={14} /></button>
      </div>
      {loading || !draft ? (
        <div className="memory-detail-empty">
          <span>{loading ? '加载记忆详情...' : error || '等待后端连接...'}</span>
          {error && <button type="button" onClick={() => setReloadKey(key => key + 1)}>重试</button>}
        </div>
      ) : (
        <>
          <div className="memory-detail-scroll">
            <label className="memory-detail-field" htmlFor="memory-detail-key">
              <span>记忆标识（Key）</span>
              <input id="memory-detail-key" value={draft.key} readOnly={!creating} disabled={busy} maxLength={128}
                placeholder="例如 project-build" onChange={event => setDraft(current => current ? { ...current, key: event.target.value } : current)} />
              <small>{creating ? '使用简短、易于识别的名称保存记忆。' : '记忆标识用于定位已有记录，不能直接修改。'}</small>
            </label>
            <label className="memory-detail-field" htmlFor="memory-detail-importance">
              <span>重要度</span>
              <input id="memory-detail-importance" type="number" min={1} max={10} value={draft.importance} disabled={busy}
                onChange={event => setDraft(current => current ? { ...current, importance: Number(event.target.value) } : current)} />
              <small>1–10，数值越高越重要。</small>
            </label>
            <label className="memory-detail-field memory-detail-content-field" htmlFor="memory-detail-content">
              <span>记忆内容</span>
              <textarea id="memory-detail-content" value={draft.content} disabled={busy} maxLength={100_000}
                placeholder="需要长期保留的信息、约定和经验" onChange={event => setDraft(current => current ? { ...current, content: event.target.value } : current)} />
            </label>
            {draft.time && <p className="memory-detail-time">更新时间：{draft.time}</p>}
          </div>
          <div className="memory-detail-actions">
            <span className="memory-detail-error" role="status">{error || (!backendPort ? '等待后端连接...' : dirty ? validationError : '')}</span>
            {!creating && <button type="button" className="memory-detail-delete" disabled={busy || !backendPort} onClick={() => setConfirmDelete(true)}>删除记忆</button>}
            <button type="button" disabled={busy} onClick={onClose}>{creating ? '取消' : '关闭'}</button>
            <button type="button" className="memory-detail-save" disabled={busy || !dirty || Boolean(validationError) || !backendPort} onClick={() => void save()}>
              <Icon name={busy ? 'loading' : 'save'} size={13} />{busy ? '处理中...' : creating ? '创建记忆' : '保存更改'}
            </button>
          </div>
        </>
      )}
      {confirmDelete && <ConfirmDialog title="删除长期记忆" message={`确定删除记忆“${entryKey}”吗？此操作无法撤销。`}
        danger confirmLabel={busy ? '删除中...' : '删除'} confirmDisabled={busy}
        onConfirm={() => void remove()} onCancel={() => { if (!busy) setConfirmDelete(false); }} />}
    </div>
  );
}

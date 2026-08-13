import { useCallback, useEffect, useMemo, useState } from 'react';
import { Icon } from '../common/Icon';
import { permissionService } from '../../services/permissionService';
import type { DbPermissionAudit, DbPermissionPolicy, PermissionAuditAction } from '../../db';
import './AuditLogPanel.css';

const ACTION_LABELS: Record<PermissionAuditAction, string> = {
  approve_once: '允许本次',
  approve_always: '总是允许',
  reject: '拒绝',
  auto_approve: '自动放行',
};

export function AuditLogPanel() {
  const [tab, setTab] = useState<'audit' | 'policy'>('audit');
  const [audits, setAudits] = useState<DbPermissionAudit[]>([]);
  const [policies, setPolicies] = useState<DbPermissionPolicy[]>([]);
  const [busy, setBusy] = useState(false);
  const [filter, setFilter] = useState('');

  const refresh = useCallback(async () => {
    setBusy(true);
    try {
      const [a, p] = await Promise.all([
        permissionService.listAudits(200),
        permissionService.listPolicies(),
      ]);
      setAudits(a);
      setPolicies(p);
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);

  const visibleAudits = useMemo(() => {
    const q = filter.trim().toLowerCase();
    if (!q) return audits;
    return audits.filter(a =>
      a.toolName.toLowerCase().includes(q) ||
      a.action.toLowerCase().includes(q) ||
      a.workspacePath.toLowerCase().includes(q) ||
      (a.commandPreview || '').toLowerCase().includes(q)
    );
  }, [audits, filter]);

  const visiblePolicies = useMemo(() => {
    const q = filter.trim().toLowerCase();
    if (!q) return policies;
    return policies.filter(p =>
      p.toolName.toLowerCase().includes(q) ||
      p.workspacePath.toLowerCase().includes(q)
    );
  }, [policies, filter]);

  async function removePolicy(id: string) {
    await permissionService.removePolicy(id);
    await refresh();
  }

  return (
    <div className="audit-log-panel">
      <div className="panel-header">
        <span className="panel-title">审计日志</span>
        <div className="panel-actions">
          <button className="panel-action" title="刷新" onClick={() => void refresh()} disabled={busy}>
            <Icon name="refresh" size={14} />
          </button>
        </div>
      </div>
      <div className="audit-tabs">
        <button className={`audit-tab${tab === 'audit' ? ' active' : ''}`} onClick={() => setTab('audit')}>
          操作记录
        </button>
        <button className={`audit-tab${tab === 'policy' ? ' active' : ''}`} onClick={() => setTab('policy')}>
          权限策略 ({policies.length})
        </button>
      </div>
      <div className="audit-search">
        <Icon name="search" size={13} />
        <input value={filter} onChange={e => setFilter(e.target.value)} placeholder="搜索..." />
      </div>
      <div className="audit-list">
        {busy && (tab === 'audit' ? audits.length === 0 : policies.length === 0) ? (
          <div className="audit-empty">加载中...</div>
        ) : tab === 'audit' ? (
          visibleAudits.length === 0 ? (
            <div className="audit-empty">暂无审计记录</div>
          ) : visibleAudits.map(audit => (
            <div key={audit.id} className="audit-item">
              <div className="audit-item-header">
                <span className={`audit-badge audit-badge-${audit.action}`}>{ACTION_LABELS[audit.action]}</span>
                <span className="audit-tool">{audit.toolName}</span>
                <span className="audit-time">{formatTime(audit.createdAt)}</span>
              </div>
              {audit.commandPreview && (
                <div className="audit-command">{audit.commandPreview}</div>
              )}
              <div className="audit-workspace" title={audit.workspacePath}>{audit.workspacePath}</div>
            </div>
          ))
        ) : visiblePolicies.length === 0 ? (
          <div className="audit-empty">暂无权限策略</div>
        ) : visiblePolicies.map(policy => (
          <div key={policy.id} className="audit-policy-item">
            <div className="audit-policy-info">
              <span className="audit-policy-tool">{policy.toolName}</span>
              <span className="audit-policy-path" title={policy.workspacePath}>{policy.workspacePath}</span>
              <span className="audit-policy-time">创建于 {formatTime(policy.createdAt)}</span>
            </div>
            <button className="audit-policy-remove" title="移除策略" onClick={() => void removePolicy(policy.id)}>
              <Icon name="close" size={14} />
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}

function formatTime(iso: string): string {
  try {
    const d = new Date(iso);
    const now = new Date();
    const diff = now.getTime() - d.getTime();
    if (diff < 60_000) return '刚刚';
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}分钟前`;
    if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}小时前`;
    if (diff < 604_800_000) return `${Math.floor(diff / 86_400_000)}天前`;
    return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  } catch {
    return iso;
  }
}

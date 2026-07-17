import { useState, useEffect, useCallback } from "react";
import { Icon } from "../common/Icon";
import { ConfirmDialog } from "../common/ConfirmDialog";
import { ContextMenu } from "../common/ContextMenu";
import { skillService } from "../../services/skillService";
import type { MountPool, PoolSkill } from "../../services/skillService";
import { getManagedResourceNameError, managedResourceService } from "../../services/managedResourceService";
import "../sidebar/ExplorerPanel.css";
import "../sidebar/SessionsPanel.css";
import "./SkillsPanel.css";

interface SkillsPanelProps {
  backendPort: number | null;
  onFileSelect: (path: string) => void;
  onCreateWithAI?: () => void;
  refreshKey?: number;
}

export function SkillsPanel({ backendPort, onFileSelect, onCreateWithAI, refreshKey = 0 }: SkillsPanelProps) {
  const [mounts, setMounts] = useState<MountPool[]>([]);
  const [poolSkills, setPoolSkills] = useState<Record<string, PoolSkill[]>>({});
  const [loading, setLoading] = useState(false);
  const [collapsedPools, setCollapsedPools] = useState<Set<string>>(new Set());
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; mount: MountPool; skill: PoolSkill } | null>(null);
  const [renameTarget, setRenameTarget] = useState<{ mount: MountPool; skill: PoolSkill; value: string } | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<{ mount: MountPool; skill: PoolSkill } | null>(null);
  const [actionMessage, setActionMessage] = useState<{ text: string; error?: boolean } | null>(null);
  const [actionPending, setActionPending] = useState(false);

  const loadMounts = useCallback(async () => {
    if (!backendPort) return;
    setLoading(true);
    try {
      const list = await skillService.getMounts(backendPort);
      const skillMounts = list.filter(mount => mount.type === "SKILLS");
      setMounts(skillMounts);

      // Tauri 在独立进程中写入 Skill，文件监听可能尚未完成刷新。
      // 主动让 CLI 重新扫描挂载池，确保管理列表和运行时立即可用。
      for (const mount of skillMounts) {
        try { await skillService.refreshMount(backendPort, mount.alias); }
        catch (err) { console.warn(`[SkillsPanel] refresh mount ${mount.alias} failed:`, err); }
      }

      const skillsMap: Record<string, PoolSkill[]> = {};
      await Promise.all(skillMounts.map(async (m) => {
        try { skillsMap[m.alias] = await skillService.getPoolSkills(backendPort, m.alias); }
        catch { skillsMap[m.alias] = []; }
      }));
      setPoolSkills(skillsMap);
    } catch (err) { console.warn("[SkillsPanel] load mounts failed:", err); }
    finally { setLoading(false); }
  }, [backendPort]);

  useEffect(() => { loadMounts(); }, [loadMounts, refreshKey]);

  const togglePool = (alias: string) => {
    setCollapsedPools(prev => {
      const next = new Set(prev);
      if (next.has(alias)) next.delete(alias); else next.add(alias);
      return next;
    });
  };

  const totalSkills = Object.values(poolSkills).reduce((sum, arr) => sum + arr.length, 0);
  const visibleMounts = mounts.filter(mount => (poolSkills[mount.alias]?.length || 0) > 0);
  const renameError = renameTarget ? getManagedResourceNameError(renameTarget.value) : '';

  const handleContextAction = useCallback((action: string) => {
    const target = contextMenu;
    setContextMenu(null);
    if (!target || target.mount.system || !target.skill.path) return;
    setActionMessage(null);
    if (action === 'rename') {
      setRenameTarget({ ...target, value: target.skill.name });
    } else if (action === 'copy') {
      setActionPending(true);
      void managedResourceService.copy(target.skill.path, 'skill')
        .then(result => {
          setActionMessage({ text: `已复制为 ${result.name}` });
          return loadMounts();
        })
        .catch(error => {
          console.error('[SkillsPanel] 复制 Skill 失败:', error);
          setActionMessage({ text: '复制 Skill 失败', error: true });
        })
        .finally(() => setActionPending(false));
    } else if (action === 'delete') {
      setDeleteTarget(target);
    }
  }, [contextMenu, loadMounts]);

  const confirmRename = useCallback(async () => {
    if (!renameTarget?.skill.path || getManagedResourceNameError(renameTarget.value) || actionPending) return;
    setActionPending(true);
    try {
      const result = await managedResourceService.rename(renameTarget.skill.path, 'skill', renameTarget.value);
      setRenameTarget(null);
      setActionMessage({ text: `已重命名为 ${result.name}` });
      await loadMounts();
    } catch (error) {
      console.error('[SkillsPanel] 重命名 Skill 失败:', error);
      setActionMessage({ text: '重命名 Skill 失败', error: true });
    } finally {
      setActionPending(false);
    }
  }, [actionPending, loadMounts, renameTarget]);

  const confirmDelete = useCallback(async () => {
    if (!deleteTarget?.skill.path || actionPending) return;
    setActionPending(true);
    try {
      await managedResourceService.delete(deleteTarget.skill.path, 'skill');
      setDeleteTarget(null);
      setActionMessage({ text: `已删除 ${deleteTarget.skill.name}` });
      await loadMounts();
    } catch (error) {
      console.error('[SkillsPanel] 删除 Skill 失败:', error);
      setActionMessage({ text: '删除 Skill 失败', error: true });
    } finally {
      setActionPending(false);
    }
  }, [actionPending, deleteTarget, loadMounts]);

  return (
    <div className="skills-panel">
      <div className="panel-header">
        <span className="panel-title">Skills</span>
        <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
          <span className="group-count">{totalSkills}</span>
          <button className="new-session-btn" onClick={onCreateWithAI} title="根据提示词创建 Skill"><Icon name="add" size={14} /></button>
          <button className="new-session-btn" onClick={loadMounts} title="刷新"><Icon name="refresh" size={14} /></button>
        </div>
      </div>

      <div className="panel-content skills-list">
        {actionMessage && <div className={`resource-action-message${actionMessage.error ? ' error' : ''}`}>{actionMessage.text}</div>}
        {!backendPort && <div className="empty-state"><div className="empty-text">等待后端连接...</div></div>}
        {backendPort && loading && <div className="empty-state"><div className="empty-text">加载中...</div></div>}
        {backendPort && !loading && visibleMounts.length === 0 && <div className="empty-state"><div className="empty-text">暂无 Skills</div></div>}
        {backendPort && !loading && visibleMounts.map(mount => {
          const collapsed = collapsedPools.has(mount.alias);
          const skills = poolSkills[mount.alias] || [];
          return (
            <div key={mount.alias} className="pool-section">
              <div className="pool-header" onClick={() => togglePool(mount.alias)}>
                <Icon name={collapsed ? "chevron-right" : "chevron-down"} size={12} />
                <span className="pool-alias">{mount.alias}</span>
                <span className="group-count">{skills.length}</span>
                {mount.system && <span className="pool-system-badge">系统</span>}
              </div>
              {!collapsed && skills.map(skill => (
                <div
                  key={skill.path || skill.name}
                  className="file-node file"
                  style={{ paddingLeft: "20px", cursor: skill.path ? "pointer" : "default" }}
                  onClick={() => {
                    if (skill.path) {
                      const mdPath = skill.path.replace(/[\\/]$/, '') + '/SKILL.md';
                      onFileSelect(mdPath);
                    }
                  }}
                  onContextMenu={event => {
                    event.preventDefault();
                    event.stopPropagation();
                    setContextMenu({ x: event.clientX, y: event.clientY, mount, skill });
                  }}
                >
                  <span className="chevron-placeholder" />
                  <Icon name="skills" size={16} className="file-icon" />
                  <span className="file-name">{skill.name}</span>
                </div>
              ))}
            </div>
          );
        })}
      </div>
      {contextMenu && (
        <ContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          items={[
            ...(contextMenu.mount.system ? [{ id: 'readonly', label: '系统 Skill（只读）', disabled: true }] : []),
            { id: 'rename', label: '重命名', disabled: contextMenu.mount.system || !contextMenu.skill.path || actionPending },
            { id: 'copy', label: '复制', disabled: contextMenu.mount.system || !contextMenu.skill.path || actionPending },
            { id: 'delete', label: '删除', danger: true, disabled: contextMenu.mount.system || !contextMenu.skill.path || actionPending },
          ]}
          onItemClick={handleContextAction}
          onClose={() => setContextMenu(null)}
        />
      )}
      {renameTarget && (
        <ConfirmDialog
          title="重命名 Skill"
          message="名称会同步写入 SKILL.md。"
          inputLabel="Skill 名称"
          inputValue={renameTarget.value}
          inputError={renameError}
          confirmLabel={actionPending ? '处理中' : '重命名'}
          confirmDisabled={Boolean(renameError) || actionPending}
          onInputChange={value => setRenameTarget(current => current ? { ...current, value } : null)}
          onConfirm={() => { void confirmRename(); }}
          onCancel={() => { if (!actionPending) setRenameTarget(null); }}
        />
      )}
      {deleteTarget && (
        <ConfirmDialog
          title="删除 Skill"
          message={`将删除「${deleteTarget.skill.name}」及其目录中的全部文件，此操作无法撤销。`}
          confirmLabel={actionPending ? '处理中' : '删除'}
          confirmDisabled={actionPending}
          danger
          onConfirm={() => { void confirmDelete(); }}
          onCancel={() => { if (!actionPending) setDeleteTarget(null); }}
        />
      )}
    </div>
  );
}

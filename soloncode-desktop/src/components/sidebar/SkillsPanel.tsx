import { useState, useEffect, useCallback } from "react";
import { Icon } from "../common/Icon";
import { skillService } from "../../services/skillService";
import type { MountPool, PoolSkill } from "../../services/skillService";
import "../sidebar/ExplorerPanel.css";
import "../sidebar/SessionsPanel.css";
import "./SkillsPanel.css";

interface SkillsPanelProps {
  backendPort: number | null;
  onFileSelect: (path: string) => void;
  onCreateWithAI?: (name: string, description: string) => void;
}

export function SkillsPanel({ backendPort, onFileSelect, onCreateWithAI }: SkillsPanelProps) {
  const [mounts, setMounts] = useState<MountPool[]>([]);
  const [poolSkills, setPoolSkills] = useState<Record<string, PoolSkill[]>>({});
  const [loading, setLoading] = useState(false);
  const [collapsedPools, setCollapsedPools] = useState<Set<string>>(new Set());

  const loadMounts = useCallback(async () => {
    if (!backendPort) return;
    setLoading(true);
    try {
      const list = await skillService.getMounts(backendPort);
      setMounts(list);
      const skillsMap: Record<string, PoolSkill[]> = {};
      await Promise.all(list.map(async (m) => {
        try { skillsMap[m.alias] = await skillService.getPoolSkills(backendPort, m.alias); }
        catch { skillsMap[m.alias] = []; }
      }));
      setPoolSkills(skillsMap);
    } catch (err) { console.warn("[SkillsPanel] load mounts failed:", err); }
    finally { setLoading(false); }
  }, [backendPort]);

  useEffect(() => { loadMounts(); }, [loadMounts]);

  const togglePool = (alias: string) => {
    setCollapsedPools(prev => {
      const next = new Set(prev);
      if (next.has(alias)) next.delete(alias); else next.add(alias);
      return next;
    });
  };

  const totalSkills = Object.values(poolSkills).reduce((sum, arr) => sum + arr.length, 0);

  return (
    <div className="skills-panel">
      <div className="panel-header">
        <span className="panel-title">Skills</span>
        <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
          <span className="group-count">{totalSkills}</span>
          <button className="new-session-btn" onClick={loadMounts} title="刷新"><Icon name="refresh" size={14} /></button>
        </div>
      </div>

      <div className="panel-content skills-list">
        {!backendPort && <div className="empty-state"><div className="empty-text">等待后端连接...</div></div>}
        {backendPort && loading && <div className="empty-state"><div className="empty-text">加载中...</div></div>}
        {backendPort && !loading && mounts.length === 0 && <div className="empty-state"><div className="empty-text">暂无挂载池</div></div>}
        {backendPort && !loading && mounts.map(mount => {
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
                  key={skill.name}
                  className="file-node file"
                  style={{ paddingLeft: "20px", cursor: skill.path ? "pointer" : "default" }}
                  onClick={() => {
                    if (skill.path) {
                      const mdPath = skill.path.replace(/\/$/, '') + '/skill.md';
                      onFileSelect(mdPath);
                    }
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
    </div>
  );
}

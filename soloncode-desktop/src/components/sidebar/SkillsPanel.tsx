import { useState, useEffect, useCallback, useRef } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { Icon, getFileIconName } from '../common/Icon';
import type { SkillConfig, SkillGroup } from '../../services/settingsService';
import '../sidebar/ExplorerPanel.css';
import './SkillsPanel.css';

interface SkillsPanelProps {
  skills: SkillConfig[];
  onSkillsChange: (skills: SkillConfig[]) => void;
  onFileSelect: (path: string) => void;
  onCreateWithAI?: (name: string, description: string) => void;
}

const GROUP_META: Record<SkillGroup, { label: string; icon: string }> = {
  global: { label: '全局', icon: 'folder-root' },
  project: { label: '项目', icon: 'folder' },
  claude: { label: 'Claude', icon: 'bot' },
  codex: { label: 'Codex', icon: 'code' },
};

const GROUP_ORDER: SkillGroup[] = ['global', 'project', 'claude', 'codex'];

export function SkillsPanel({ skills, onSkillsChange, onFileSelect, onCreateWithAI }: SkillsPanelProps) {
  const [loading, setLoading] = useState(false);
  const [expandedFolders, setExpandedFolders] = useState<Set<string>>(new Set());
  const [collapsedGroups, setCollapsedGroups] = useState<Set<SkillGroup>>(new Set());
  const [showCreate, setShowCreate] = useState(false);
  const [newName, setNewName] = useState('');
  const [newDesc, setNewDesc] = useState('');
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState('');
  const onSkillsChangeRef = useRef(onSkillsChange);
  onSkillsChangeRef.current = onSkillsChange;

  const loadFromBackend = useCallback(async () => {
    setLoading(true);
    try {
      const backendSkills = await invoke<Array<{
        name: string;
        description: string;
        path: string;
        enabled: boolean;
      }>>('list_skills');

      const mapped: SkillConfig[] = backendSkills.map(s => ({
        name: s.name,
        description: s.description,
        path: s.path,
        enabled: s.enabled,
        source: 'discovered' as const,
        group: 'global' as const,
      }));
      onSkillsChangeRef.current(mapped);
    } catch (err) {
      console.warn('[SkillsPanel] 加载后端 skills 失败:', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadFromBackend();
  }, [loadFromBackend]);

  const handleCreate = async () => {
    if (!newName.trim()) return;
    setCreating(true);
    setCreateError('');
    try {
      await invoke('create_skill', { name: newName.trim(), description: newDesc.trim() });
      setShowCreate(false);
      setNewName('');
      setNewDesc('');
      await loadFromBackend();
    } catch (err) {
      setCreateError(String(err));
    } finally {
      setCreating(false);
    }
  };

  const handleToggle = async (skill: SkillConfig, index: number) => {
    try {
      await invoke('toggle_skill', { skillPath: skill.path, enabled: !skill.enabled });
      const updated = skills.map((s, i) =>
        i === index ? { ...s, enabled: !s.enabled } : s
      );
      onSkillsChange(updated);
    } catch (err) {
      console.warn('[SkillsPanel] 切换 skill 失败:', err);
    }
  };

  const toggleFolder = (path: string) => {
    setExpandedFolders(prev => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  };

  const toggleGroup = (group: SkillGroup) => {
    setCollapsedGroups(prev => {
      const next = new Set(prev);
      if (next.has(group)) next.delete(group);
      else next.add(group);
      return next;
    });
  };

  // 按分组归类，保留在 skills 数组中的原始 index
  const grouped = GROUP_ORDER
    .map(group => ({
      group,
      items: skills
        .map((s, i) => ({ skill: s, index: i }))
        .filter(({ skill }) => skill.group === group),
    }))
    .filter(g => g.items.length > 0);

  const enabledCount = skills.filter(s => s.enabled).length;

  function renderSkillNode(skill: SkillConfig, index: number) {
    const isExpanded = expandedFolders.has(skill.path);
    const isFolder = skill.group === 'global' || skill.group === 'project';
    const skillMdPath = skill.group === 'global' || skill.group === 'project'
      ? `${skill.path}/SKILL.md`
      : skill.path;

    return (
      <div key={skill.path}>
        <div
          className={`file-node${isFolder ? ' folder' : ' file'}${isExpanded ? ' expanded' : ''}${skill.enabled ? '' : ' disabled-item'}`}
          onClick={() => {
            if (isFolder) toggleFolder(skill.path);
            else onFileSelect(skill.path);
          }}
        >
          {isFolder ? (
            <span className="chevron-icon">
              <Icon name={isExpanded ? 'chevron-down' : 'chevron-right'} size={12} />
            </span>
          ) : (
            <span className="chevron-placeholder" />
          )}
          <Icon
            name={isFolder ? (isExpanded ? 'folder-open' : 'folder') : getFileIconName(skillMdPath.split('/').pop() || '')}
            size={16} className="file-icon"
          />
          <span className="file-name">{skill.name}</span>
          <input
            type="checkbox"
            className="tree-checkbox"
            checked={skill.enabled}
            onClick={(e) => e.stopPropagation()}
            onChange={() => handleToggle(skill, index)}
          />
        </div>
        {isFolder && isExpanded && (
          <div
            className="file-node file"
            style={{ paddingLeft: '24px' }}
            onClick={() => onFileSelect(skillMdPath)}
          >
            <span className="chevron-placeholder" />
            <Icon name={getFileIconName('SKILL.md')} size={16} className="file-icon" />
            <span className="file-name">SKILL.md</span>
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="skills-panel">
      <div className="panel-header">
        <span className="panel-title">Skills</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span className="group-count">{enabledCount}/{skills.length}</span>
          <button className="new-session-btn" onClick={() => setShowCreate(!showCreate)} title="新建 Skill">
            <Icon name="add" size={14} />
          </button>
          <button className="new-session-btn" onClick={loadFromBackend} title="刷新">
            <Icon name="refresh" size={14} />
          </button>
        </div>
      </div>

      {showCreate && (
        <div className="create-form">
          <input className="create-form-input" placeholder="Skill 名称" value={newName} onChange={e => setNewName(e.target.value)} autoFocus onKeyDown={e => { if (e.key === 'Enter') handleCreate(); }} />
          <input className="create-form-input" placeholder="描述（可选）" value={newDesc} onChange={e => setNewDesc(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') handleCreate(); }} />
          <div className="create-form-actions">
            <button className="create-form-btn cancel" onClick={() => { setShowCreate(false); setNewName(''); setNewDesc(''); setCreateError(''); }}>取消</button>
            {onCreateWithAI && <button className="create-form-btn ai-gen" onClick={() => { if (!newName.trim()) return; onCreateWithAI(newName.trim(), newDesc.trim()); setShowCreate(false); setNewName(''); setNewDesc(''); setCreateError(''); }} disabled={!newName.trim()} title="AI 生成"><Icon name="bot" size={12} /> AI 生成</button>}
            <button className="create-form-btn confirm" onClick={handleCreate} disabled={creating || !newName.trim()}>{creating ? '创建中...' : '创建'}</button>
          </div>
          {createError && <p className="create-form-error">{createError}</p>}
        </div>
      )}

      <div className="panel-content skills-list">
        {loading && (
          <div className="empty-state">
            <div className="empty-text">加载中...</div>
          </div>
        )}

        {!loading && skills.length === 0 && (
          <div className="empty-state">
            <div className="empty-icon"><Icon name="skills" size={40} /></div>
            <div className="empty-text">暂无 Skill</div>
          </div>
        )}

        {!loading && grouped.map(({ group, items }) => {
          const meta = GROUP_META[group];
          const collapsed = collapsedGroups.has(group);
          return (
            <div key={group}>
              <div
                className="skill-group-header"
                onClick={() => toggleGroup(group)}
              >
                <Icon name={collapsed ? 'chevron-right' : 'chevron-down'} size={12} />
                <Icon name={meta.icon as any} size={14} />
                <span className="skill-group-label">{meta.label}</span>
                <span className="group-count">{items.length}</span>
              </div>
              {!collapsed && items.map(({ skill, index }) => renderSkillNode(skill, index))}
            </div>
          );
        })}
      </div>
    </div>
  );
}

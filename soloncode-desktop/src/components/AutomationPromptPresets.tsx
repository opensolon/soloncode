import { useState } from 'react';
import { AUTOMATION_PROMPT_PRESETS, buildAutomationPresetRequest } from '../utils/automationPresets';
import './AutomationPromptPresets.css';

interface AutomationPromptPresetsProps {
  disabled?: boolean;
  onSelect: (request: string) => void;
}

export function AutomationPromptPresets({ disabled, onSelect }: AutomationPromptPresetsProps) {
  const [selectedId, setSelectedId] = useState('');
  return (
    <section className="automation-prompt-presets" aria-label="任务预设">
      <div className="automation-prompt-presets-heading">
        <strong>从预设开始</strong><span>选择后可编辑，发送后创建任务</span>
      </div>
      <div className="automation-prompt-presets-grid">
        {AUTOMATION_PROMPT_PRESETS.map(preset => <button
          type="button"
          key={preset.id}
          className="automation-prompt-preset"
          aria-label={`使用预设 ${preset.title}`}
          aria-pressed={selectedId === preset.id}
          disabled={disabled}
          onClick={() => { setSelectedId(preset.id); onSelect(buildAutomationPresetRequest(preset)); }}
        >
          <strong>{preset.title}</strong>
          <span>{preset.description}</span>
          <small>{preset.scheduleEnabled ? preset.id === 'code-review' ? '每天 9:00' : '每小时' : '手动运行'}</small>
        </button>)}
      </div>
    </section>
  );
}

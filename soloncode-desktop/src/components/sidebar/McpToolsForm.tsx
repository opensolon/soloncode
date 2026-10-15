import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { FormEvent } from 'react';
import { Icon } from '../common/Icon';
import type { McpTool } from '../../types/mcp';
import { fetchMcpTools, saveMcpToolPermissions } from '../../services/mcpService';
import './McpToolsForm.css';

interface McpToolsFormProps {
  serverName: string;
  backendPort: number;
  onClose: () => void;
  onSaved: () => void;
}

export function McpToolsForm({ serverName, backendPort, onClose, onSaved }: McpToolsFormProps) {
  const [tools, setTools] = useState<McpTool[] | null>(null);
  const [disabled, setDisabled] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState<'fetch' | 'save' | null>(null);
  const [error, setError] = useState('');
  const requestRef = useRef<AbortController | null>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const fetchButtonRef = useRef<HTMLButtonElement>(null);
  useEffect(() => () => requestRef.current?.abort(), []);
  useEffect(() => {
    const previousFocus = document.activeElement;
    fetchButtonRef.current?.focus();
    function handleKey(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        onClose();
      } else if (event.key === 'Tab') {
        const controls = Array.from(formRef.current?.querySelectorAll<HTMLElement>('button:enabled, input:enabled') ?? []);
        const first = controls[0];
        const last = controls[controls.length - 1];
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last?.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first?.focus();
        }
      }
    }
    document.addEventListener('keydown', handleKey, true);
    return () => {
      document.removeEventListener('keydown', handleKey, true);
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus();
    };
  }, [onClose]);

  async function loadTools() {
    const request = new AbortController();
    requestRef.current = request;
    setBusy('fetch');
    setError('');
    try {
      const result = await fetchMcpTools(backendPort, serverName, request.signal);
      if (request.signal.aborted) return;
      // 再次获取时保留已编辑工具的选择，新工具使用服务端权限。
      setDisabled(current => {
        const next = new Set(result.disallowedTools);
        for (const tool of tools ?? []) {
          if (current.has(tool.name)) next.add(tool.name);
          else next.delete(tool.name);
        }
        return next;
      });
      setTools(result.tools);
    } catch (cause) {
      if (!request.signal.aborted) setError(`获取工具失败：${cause instanceof Error ? cause.message : '请检查服务连接'}`);
    } finally {
      if (!request.signal.aborted) setBusy(null);
    }
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (tools === null || busy) return;
    const request = new AbortController();
    requestRef.current = request;
    setBusy('save');
    setError('');
    try {
      await saveMcpToolPermissions(backendPort, serverName, disabled, request.signal);
      if (!request.signal.aborted) onSaved();
    } catch (cause) {
      if (!request.signal.aborted) setError(`保存权限失败：${cause instanceof Error ? cause.message : '请稍后重试'}`);
    } finally {
      if (!request.signal.aborted) setBusy(null);
    }
  }

  function toggle(name: string, enabled: boolean) {
    setDisabled(current => {
      const next = new Set(current);
      if (enabled) next.delete(name); else next.add(name);
      return next;
    });
  }

  const enabledCount = tools?.filter(tool => !disabled.has(tool.name)).length ?? 0;
  return createPortal(
    <div className="mcp-tools-overlay" onClick={event => { event.stopPropagation(); if (event.target === event.currentTarget) onClose(); }}>
    <form ref={formRef} className="mcp-tools-form" role="dialog" aria-modal="true" onSubmit={event => void save(event)} aria-labelledby="mcp-tools-heading">
      <div className="mcp-tools-heading">
        <div><h3 id="mcp-tools-heading">MCP 工具管理</h3><div className="mcp-tools-server">{serverName}</div></div>
        <button type="button" className="mcp-tools-close" aria-label="关闭工具管理" onClick={onClose}><Icon name="close" size={16} /></button>
      </div>
      <p className="mcp-tools-hint">使用已保存的服务配置获取工具。新增或修改服务后，请先保存设置。</p>
      <div className="mcp-tools-toolbar">
        <span aria-live="polite">{tools === null ? '尚未获取工具' : `共 ${tools.length} 个工具，已启用 ${enabledCount} 个`}</span>
        <button ref={fetchButtonRef} type="button" className="mcp-add-btn" disabled={!!busy} onClick={() => void loadTools()}>
          {busy === 'fetch' ? '获取中…' : tools === null ? '获取工具' : '重新获取工具'}
        </button>
      </div>
      {error && <div className="settings-action-message error" role="alert">{error}</div>}
      <fieldset className="mcp-tools-fields" disabled={!!busy} aria-busy={!!busy}>
        <legend className="mcp-tools-legend">工具权限</legend>
        <div className="mcp-tools-table-wrap">
          <table className="mcp-tools-table">
            <thead><tr><th scope="col">启用</th><th scope="col">工具名称</th><th scope="col">说明</th></tr></thead>
            <tbody>
              {tools?.map(tool => <tr key={tool.name}>
                <td><input type="checkbox" aria-label={`启用 ${tool.name}`} checked={!disabled.has(tool.name)} onChange={event => toggle(tool.name, event.target.checked)} /></td>
                <th scope="row">{tool.name}</th><td>{tool.description || '暂无说明'}</td>
              </tr>)}
              {!tools?.length && <tr><td colSpan={3} className="mcp-tools-empty">
                {busy === 'fetch' ? '正在连接服务并获取工具…' : tools === null ? '点击“获取工具”加载此服务的工具列表' : '服务未提供工具，可重新获取'}
              </td></tr>}
            </tbody>
          </table>
        </div>
      </fieldset>
      <div className="mcp-tools-footer">
        <span>保存权限后立即生效</span>
        <div className="settings-inline-actions">
          <button type="button" className="settings-btn cancel" onClick={onClose}>取消</button>
          <button type="submit" className="settings-btn save" disabled={tools === null || !!busy}>{busy === 'save' ? '保存中…' : '保存权限'}</button>
        </div>
      </div>
    </form>
    </div>,
    document.body,
  );
}

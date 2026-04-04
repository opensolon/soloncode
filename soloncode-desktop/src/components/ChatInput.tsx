import { useState, FormEvent, KeyboardEvent, useRef, useEffect, useCallback } from 'react';
import { Icon } from './common/Icon';
import './ChatInput.css';

// 可用的智能体列表
const AVAILABLE_AGENTS = [
  { id: 'default', name: '助手', icon: 'bot', description: '通用编程助手' },
  { id: 'explorer', name: '探索', icon: 'search', description: '探索代码库' },
  { id: 'architect', name: '架构师', icon: 'code', description: '设计实现方案' },
  { id: 'bash', name: '终端', icon: 'terminal', description: '执行命令' },
];

// 上下文引用项
interface ContextRef {
  id: string;
  type: 'file' | 'folder' | 'code' | 'symbol';
  name: string;
  path?: string;
}

interface ChatInputProps {
  onSend: (message: string, options: SendOptions) => void;
  isLoading?: boolean;
  onStop?: () => void;
  availableFiles?: ContextRef[];
}

export interface SendOptions {
  model: string;
  agent: string;
  contexts: ContextRef[];
}

export function ChatInput({ onSend, isLoading, onStop, availableFiles = [] }: ChatInputProps) {
  const [userInput, setUserInput] = useState('');
  const [selectedModel, setSelectedModel] = useState('');
  const [selectedAgent, setSelectedAgent] = useState('default');
  const [contexts, setContexts] = useState<ContextRef[]>([]);

  // 监听侧边栏双击设置上下文事件
  useEffect(() => {
    const handleSetContext = (e: CustomEvent<string>) => {
      const detail = e.detail;
      // 解析 @folder path 或 @file path
      const match = detail.match(/^@(folder|file)\s+(.+)$/);
      if (match) {
        const [, type, path] = match;
        const name = path.split(/[/\\]/).pop() || path;
        const newContext: ContextRef = {
          id: `sidebar-${Date.now()}`,
          type: type as 'file' | 'folder',
          name,
          path,
        };
        setContexts(prev => {
          if (prev.some(c => c.path === path)) return prev;
          return [...prev, newContext];
        });
      }
    };

    window.addEventListener('set-chat-context', handleSetContext as EventListener);
    return () => window.removeEventListener('set-chat-context', handleSetContext as EventListener);
  }, []);

  // 自动完成状态
  const [showAutocomplete, setShowAutocomplete] = useState(false);
  const [autocompleteType, setAutocompleteType] = useState<'context' | 'agent' | null>(null);
  const [autocompleteQuery, setAutocompleteQuery] = useState('');
  const [autocompletePosition, setAutocompletePosition] = useState({ start: 0, end: 0 });
  const [selectedIndex, setSelectedIndex] = useState(0);

  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const autocompleteRef = useRef<HTMLDivElement>(null);

  // 获取过滤后的自动完成选项
  const getFilteredOptions = useCallback(() => {
    if (autocompleteType === 'agent') {
      return AVAILABLE_AGENTS.filter(a =>
        a.name.toLowerCase().includes(autocompleteQuery.toLowerCase())
      );
    }
    if (autocompleteType === 'context') {
      const defaultContexts: ContextRef[] = [
        { id: 'current-file', type: 'file', name: '当前文件' },
        { id: 'current-folder', type: 'folder', name: '当前目录' },
        { id: 'selection', type: 'code', name: '选中代码' },
        { id: 'project', type: 'folder', name: '整个项目' },
      ];
      const allContexts = [...defaultContexts, ...availableFiles];
      return allContexts.filter(c =>
        c.name.toLowerCase().includes(autocompleteQuery.toLowerCase())
      );
    }
    return [];
  }, [autocompleteType, autocompleteQuery, availableFiles]);

  // 处理输入变化
  function handleInput(event: React.ChangeEvent<HTMLTextAreaElement>) {
    const value = event.target.value;
    const cursorPos = event.target.selectionStart || 0;
    const beforeCursor = value.substring(0, cursorPos);

    const lastAtIndex = beforeCursor.lastIndexOf('@');
    const lastHashIndex = beforeCursor.lastIndexOf('#');

    let triggerType: 'agent' | 'context' | null = null;
    let triggerIndex = -1;

    if (lastAtIndex > lastHashIndex && lastAtIndex !== -1) {
      const afterAt = beforeCursor.substring(lastAtIndex + 1);
      if (!afterAt.includes(' ') && !afterAt.includes('\n')) {
        triggerType = 'agent';
        triggerIndex = lastAtIndex;
      }
    } else if (lastHashIndex !== -1) {
      const afterHash = beforeCursor.substring(lastHashIndex + 1);
      if (!afterHash.includes(' ') && !afterHash.includes('\n')) {
        triggerType = 'context';
        triggerIndex = lastHashIndex;
      }
    }

    if (triggerType && triggerIndex !== -1) {
      setAutocompleteType(triggerType);
      setAutocompleteQuery(beforeCursor.substring(triggerIndex + 1));
      setAutocompletePosition({ start: triggerIndex, end: cursorPos });
      setShowAutocomplete(true);
      setSelectedIndex(0);
    } else {
      setShowAutocomplete(false);
      setAutocompleteType(null);
    }

    setUserInput(value);
  }

  // 选择自动完成项
  function selectAutocompleteItem(item: { id: string; name: string }) {
    const beforeTrigger = userInput.substring(0, autocompletePosition.start);
    const afterCursor = userInput.substring(autocompletePosition.end);

    const trigger = autocompleteType === 'agent' ? '@' : '#';
    const newValue = beforeTrigger + `${trigger}${item.name} ` + afterCursor;

    setUserInput(newValue);
    setShowAutocomplete(false);
    setAutocompleteType(null);

    if (autocompleteType === 'context') {
      const contextRef: ContextRef = {
        id: item.id,
        type: 'file',
        name: item.name,
      };
      setContexts(prev => {
        if (prev.find(c => c.id === item.id)) return prev;
        return [...prev, contextRef];
      });
    }

    if (autocompleteType === 'agent') {
      const agent = AVAILABLE_AGENTS.find(a => a.id === item.id || a.name === item.name);
      if (agent) {
        setSelectedAgent(agent.id);
      }
    }

    setTimeout(() => {
      if (textareaRef.current) {
        const newPos = beforeTrigger.length + trigger.length + item.name.length + 1;
        textareaRef.current.focus();
        textareaRef.current.setSelectionRange(newPos, newPos);
      }
    }, 0);
  }

  // 键盘导航
  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (showAutocomplete) {
      const options = getFilteredOptions();
      if (options.length > 0) {
        if (event.key === 'ArrowDown') {
          event.preventDefault();
          setSelectedIndex(prev => (prev + 1) % options.length);
          return;
        }
        if (event.key === 'ArrowUp') {
          event.preventDefault();
          setSelectedIndex(prev => (prev - 1 + options.length) % options.length);
          return;
        }
        if (event.key === 'Tab' || (event.key === 'Enter' && !event.shiftKey)) {
          event.preventDefault();
          const selected = options[selectedIndex];
          if (selected) {
            selectAutocompleteItem(selected);
          }
          return;
        }
        if (event.key === 'Escape') {
          setShowAutocomplete(false);
          return;
        }
      }
    }

    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      sendMessage();
    }
  }

  function sendMessage() {
    if (!userInput.trim()) return;
    onSend(userInput, {
      model: selectedModel,
      agent: selectedAgent,
      contexts: [...contexts],
    });
    setUserInput('');
    setContexts([]);
    setShowAutocomplete(false);
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    sendMessage();
  }

  // 移除上下文引用
  function removeContext(id: string) {
    setContexts(prev => prev.filter(c => c.id !== id));
    const context = contexts.find(c => c.id === id);
    if (context) {
      setUserInput(prev => prev.replace(new RegExp(`#${context.name}\\s*`, 'g'), ''));
    }
  }

  // 点击外部关闭自动完成
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (autocompleteRef.current && !autocompleteRef.current.contains(event.target as Node)) {
        setShowAutocomplete(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const filteredOptions = getFilteredOptions();
  const selectedAgentInfo = AVAILABLE_AGENTS.find(a => a.id === selectedAgent);

  return (
    <div className="chat-input-wrapper">
      {/* 上下文标签 */}
      {contexts.length > 0 && (
        <div className="context-tags">
          {contexts.map(context => (
            <span key={context.id} className="context-tag">
              <Icon name="file" size={12} />
              <span>{context.name}</span>
              <button
                className="context-tag-remove"
                onClick={() => removeContext(context.id)}
              >
                <Icon name="close" size={10} />
              </button>
            </span>
          ))}
        </div>
      )}

      {/* 输入区域 */}
      <div className="input-area">
        <form onSubmit={handleSubmit} className="input-container">
          {/* 工具栏 */}
          <div className="input-toolbar">
            {/* 模型选择 */}
            {/* <div className="toolbar-group">
              <select
                className="model-select"
                value={selectedModel}
                onChange={(e) => setSelectedModel(e.target.value)}
              >
                {AVAILABLE_MODELS.map(model => (
                  <option key={model.id} value={model.id}>{model.name}</option>
                ))}
              </select>
            </div> */}

            {/* 智能体选择 */}
            {/* <div className="toolbar-group">
              <button
                type="button"
                className="agent-btn"
                onClick={() => {
                  if (textareaRef.current) {
                    const pos = textareaRef.current.selectionStart;
                    setUserInput(prev => prev.slice(0, pos) + '@' + prev.slice(pos));
                    textareaRef.current.focus();
                    setTimeout(() => {
                      setAutocompleteType('agent');
                      setAutocompleteQuery('');
                      setAutocompletePosition({ start: pos, end: pos + 1 });
                      setShowAutocomplete(true);
                    }, 0);
                  }
                }}
              >
                <Icon name={selectedAgentInfo?.icon as any || 'bot'} size={14} />
                <span>{selectedAgentInfo?.name}</span>
              </button>
            </div> */}

          </div>

          {/* 输入行 */}
          <div className="input-row">
            <textarea
              ref={textareaRef}
              value={userInput}
              onChange={handleInput}
              className="message-input"
              placeholder="输入消息..."
              rows={1}
              onKeyDown={handleKeyDown}
            />
            {isLoading && onStop ? (
              <button
                type="button"
                className="stop-button"
                onClick={onStop}
                title="停止生成"
              >
                <Icon name="close" size={14} />
              </button>
            ) : null}
            <button
              type="submit"
              className="send-button"
              disabled={!userInput.trim()}
            >
              <Icon name="send" size={16} />
            </button>
          </div>

          {/* 底部操作栏 */}
          <div className="input-bottom-bar">
            <button
              type="button"
              className="toolbar-btn"
              title="引用上下文 (#)"
              onClick={() => {
                if (textareaRef.current) {
                  const pos = textareaRef.current.selectionStart;
                  setUserInput(prev => prev.slice(0, pos) + '#' + prev.slice(pos));
                  textareaRef.current.focus();
                  setTimeout(() => {
                    setAutocompleteType('context');
                    setAutocompleteQuery('');
                    setAutocompletePosition({ start: pos, end: pos + 1 });
                    setShowAutocomplete(true);
                  }, 0);
                }
              }}
            >
              #
            </button>
          </div>
        </form>

        {/* 自动完成下拉框 - 简洁风格 */}
        {showAutocomplete && filteredOptions.length > 0 && (
          <div className="autocomplete-dropdown" ref={autocompleteRef}>
            <div className="autocomplete-list">
              {filteredOptions.map((option, index) => (
                <div
                  key={option.id}
                  className={`autocomplete-item${index === selectedIndex ? ' selected' : ''}`}
                  onClick={() => selectAutocompleteItem(option)}
                >
                  <Icon name={
                    autocompleteType === 'agent'
                      ? (option as any).icon || 'bot'
                      : (option as any).type === 'folder' ? 'folder' : 'file'
                  } size={14} />
                  <span className="item-name">{option.name}</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* 底部提示 */}
        <div className="input-footer">
          <span className="input-hint">
            Enter 发送，Shift + Enter 换行，# 引用上下文，@ 选择智能体
          </span>
        </div>
      </div>
    </div>
  );
}

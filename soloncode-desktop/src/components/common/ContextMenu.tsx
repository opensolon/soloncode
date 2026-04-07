/**
 * 右键上下文菜单组件
 */
import { useEffect, useRef, useState } from 'react';
import type { MenuItem } from './DropdownMenu';
import './ContextMenu.css';

interface ContextMenuProps {
  x: number;
  y: number;
  items: MenuItem[];
  onItemClick: (itemId: string) => void;
  onClose: () => void;
}

export function ContextMenu({ x, y, items, onItemClick, onClose }: ContextMenuProps) {
  const menuRef = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState({ x, y });

  // 边界检测 + 自动关闭
  useEffect(() => {
    // 延迟一帧以获取菜单尺寸
    const raf = requestAnimationFrame(() => {
      if (menuRef.current) {
        const rect = menuRef.current.getBoundingClientRect();
        const newX = Math.min(x, window.innerWidth - rect.width - 8);
        const newY = Math.min(y, window.innerHeight - rect.height - 8);
        if (newX !== x || newY !== y) {
          setPosition({ x: Math.max(0, newX), y: Math.max(0, newY) });
        }
      }
    });
    return () => cancelAnimationFrame(raf);
  }, [x, y]);

  useEffect(() => {
    const handleClick = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        onClose();
      }
    };
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    const handleScroll = () => onClose();

    // 延迟注册防止右键事件立即触发关闭
    const timer = setTimeout(() => {
      document.addEventListener('mousedown', handleClick);
      document.addEventListener('keydown', handleKeyDown);
      document.addEventListener('wheel', handleScroll, true);
    }, 0);

    return () => {
      clearTimeout(timer);
      document.removeEventListener('mousedown', handleClick);
      document.removeEventListener('keydown', handleKeyDown);
      document.removeEventListener('wheel', handleScroll, true);
    };
  }, [onClose]);

  return (
    <div
      ref={menuRef}
      className="context-menu"
      style={{ left: position.x, top: position.y }}
    >
      {items.map(item => {
        if (item.divider) {
          return <div key={item.id} className="dropdown-divider" />;
        }
        return (
          <div
            key={item.id}
            className={`dropdown-item${item.disabled ? ' disabled' : ''}`}
            onClick={(e) => {
              e.stopPropagation();
              if (!item.disabled) {
                onClose();
                onItemClick(item.id);
              }
            }}
          >
            <span className="dropdown-item-label">{item.label}</span>
            {item.shortcut && (
              <span className="dropdown-item-shortcut">{item.shortcut}</span>
            )}
          </div>
        );
      })}
    </div>
  );
}

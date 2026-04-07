/**
 * 下拉菜单组件
 * @author bai
 */
import { useState, useRef, useEffect, useCallback } from 'react';
import './DropdownMenu.css';

export interface MenuItem {
  id: string;
  label: string;
  shortcut?: string;
  disabled?: boolean;
  divider?: boolean;
  children?: MenuItem[];
}

interface DropdownMenuProps {
  trigger: React.ReactNode;
  items: MenuItem[];
  onItemClick?: (itemId: string) => void;
  align?: 'left' | 'right';
}

export function DropdownMenu({ trigger, items, onItemClick, align = 'left' }: DropdownMenuProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [menuPosition, setMenuPosition] = useState({ top: 0, left: 0 });
  const menuRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  // 更新菜单位置
  const updateMenuPosition = useCallback(() => {
    if (triggerRef.current) {
      const rect = triggerRef.current.getBoundingClientRect();
      setMenuPosition({
        top: rect.bottom + 4,
        left: align === 'right' ? rect.right : rect.left,
      });
    }
  }, [align]);

  // 打开菜单时更新位置
  useEffect(() => {
    if (isOpen) {
      updateMenuPosition();
    }
  }, [isOpen, updateMenuPosition]);

  // 点击外部关闭菜单
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (
        containerRef.current &&
        !containerRef.current.contains(event.target as Node)
      ) {
        setIsOpen(false);
      }
    };

    if (isOpen) {
      // 延迟添加事件监听，避免立即触发
      const timer = setTimeout(() => {
        document.addEventListener('mousedown', handleClickOutside);
      }, 100);

      return () => {
        clearTimeout(timer);
        document.removeEventListener('mousedown', handleClickOutside);
      };
    }
  }, [isOpen]);

  // 键盘事件处理
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setIsOpen(false);
      }
    };

    if (isOpen) {
      document.addEventListener('keydown', handleKeyDown);
    }

    return () => {
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [isOpen]);

  const handleTriggerClick = useCallback(() => {
    setIsOpen(prev => !prev);
  }, []);

  const handleItemClick = useCallback((item: MenuItem) => {
    if (item.disabled) return;
    if (item.children && item.children.length > 0) return;

    setIsOpen(false);
    onItemClick?.(item.id);
  }, [onItemClick]);

  const renderMenuItem = (item: MenuItem, depth: number = 0) => {
    if (item.divider) {
      return <div key={item.id} className="dropdown-divider" />;
    }

    const hasChildren = item.children && item.children.length > 0;

    return (
      <div
        key={item.id}
        className={`dropdown-item${item.disabled ? ' disabled' : ''}${hasChildren ? ' has-children' : ''}`}
        onClick={(e) => {
          e.stopPropagation();
          handleItemClick(item);
        }}
        onMouseEnter={() => {
          if (!hasChildren) return;
          // 预加载子菜单位置
        }}
      >
        <span className="dropdown-item-label">{item.label}</span>
        {item.shortcut && <span className="dropdown-item-shortcut">{item.shortcut}</span>}
        {hasChildren && <span className="dropdown-item-arrow">▶</span>}
        {hasChildren && (
          <div className="dropdown-submenu">
            {item.children!.map(child => renderMenuItem(child, depth + 1))}
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="dropdown-container" ref={containerRef}>
      <div
        ref={triggerRef}
        className="dropdown-trigger"
        onClick={handleTriggerClick}
      >
        {trigger}
      </div>
      {isOpen && (
        <div
          ref={menuRef}
          className={`dropdown-menu ${align === 'right' ? 'align-right' : ''}`}
          style={{
            top: menuPosition.top,
            left: align === 'right' ? 'auto' : menuPosition.left,
            right: align === 'right' ? `calc(100vw - ${menuPosition.left}px)` : 'auto',
          }}
        >
          {items.map(item => renderMenuItem(item))}
        </div>
      )}
    </div>
  );
}

export default DropdownMenu;

import type { ReactNode } from 'react';
import './SidePanel.css';

interface SidePanelProps {
  title: string;
  children: ReactNode;
  width?: number;
  minWidth?: number;
  maxWidth?: number;
  collapsed?: boolean;
  onToggle?: () => void;
}

export function SidePanel({
  title,
  children,
  width = 240,
  minWidth = 180,
  maxWidth = 400,
  collapsed = false,
  onToggle
}: SidePanelProps) {
  if (collapsed) {
    return null;
  }

  return (
    <div
      className="side-panel"
      style={{
        width: `${width}px`,
        minWidth: `${minWidth}px`,
        maxWidth: `${maxWidth}px`
      }}
    >
      {children}
    </div>
  );
}

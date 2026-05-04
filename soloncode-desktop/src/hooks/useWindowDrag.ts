import { getCurrentWindow } from '@tauri-apps/api/window';

let isDragging = false;

/**
 * 在标题栏拖拽移动窗口
 */
export function startWindowDrag(e: React.MouseEvent) {
  if (e.button !== 0) return;
  const target = e.target as HTMLElement;
  if (target.closest('button, a, input, select, textarea, [data-no-drag]')) return;

  if (isDragging) return;
  isDragging = true;

  getCurrentWindow().startDragging()
    .catch(() => {})
    .finally(() => { isDragging = false; });
}

/**
 * 在窗口边缘拖拽调整大小
 */
export function startWindowResize(e: React.MouseEvent, direction: string) {
  if (e.button !== 0) return;
  e.preventDefault();
  e.stopPropagation();
  getCurrentWindow().startResizeDragging(direction)
    .catch(() => {});
}

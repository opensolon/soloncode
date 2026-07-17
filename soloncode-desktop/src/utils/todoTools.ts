export function isTodoToolName(toolName?: string) {
  const normalized = (toolName || '').toLowerCase().replace(/[_\s-]/g, '');
  return normalized === 'todoread' || normalized === 'todowrite';
}

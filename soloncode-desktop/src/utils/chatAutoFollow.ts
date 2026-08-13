export interface ChatAutoFollowInput {
  running: boolean;
  atBottom: boolean;
}

export interface ChatAutoFollowState {
  enabled: boolean;
  behavior: 'auto' | false;
}

export function resolveChatAutoFollow({ running, atBottom }: ChatAutoFollowInput): ChatAutoFollowState {
  const enabled = running || atBottom;
  return {
    enabled,
    behavior: enabled ? 'auto' : false,
  };
}

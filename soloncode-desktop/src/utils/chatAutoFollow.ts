export interface ChatAutoFollowInput {
  atBottom: boolean;
}

export interface ChatAutoFollowState {
  enabled: boolean;
  behavior: 'auto' | false;
}

// 跟随策略：只在用户停留于底部时跟随新内容；
// 运行/思考期间不再强制把滚动条拉回最低，用户可自由上翻查看历史。
export function resolveChatAutoFollow({ atBottom }: ChatAutoFollowInput): ChatAutoFollowState {
  const enabled = atBottom;
  return {
    enabled,
    behavior: enabled ? 'auto' : false,
  };
}

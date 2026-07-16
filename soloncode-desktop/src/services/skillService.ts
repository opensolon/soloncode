/**
 * Skill 服务层 - 封装后端挂载池和市场 HTTP API
 */

export interface MountPool {
  alias: string;
  type: 'SKILLS' | 'AGENTS' | 'FILES';
  path: string;
  system: boolean;
}

export interface PoolSkill {
  name: string;
  description: string;
  poolAlias: string;
  path?: string;
}

interface BackendPoolSkill {
  name: string;
  description: string;
  realPath?: string;
}

export interface MarketInfo {
  name: string;
  description: string;
}

export interface MarketItem {
  slug: string;
  name: string;
  displayName: string;
  summary: string;
  description: string;
  ownerHandle: string;
  installs: number;
  stars: number;
}

async function get<T>(port: number, path: string, params?: Record<string, string>): Promise<T> {
  const url = new URL(`http://localhost:${port}${path}`);
  if (params) {
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== '') url.searchParams.set(k, v);
    });
  }
  const resp = await fetch(url.toString());
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  const json = await resp.json();
  if (json.code !== undefined && json.code !== 200) {
    throw new Error(json.description || json.msg || `Error code ${json.code}`);
  }
  return json.data ?? json;
}

async function post<T>(port: number, path: string, body: Record<string, string>): Promise<T> {
  const resp = await fetch(`http://localhost:${port}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(body).toString(),
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  const json = await resp.json();
  if (json.code !== undefined && json.code !== 200) {
    throw new Error(json.description || json.msg || `Error code ${json.code}`);
  }
  return json.data ?? json;
}

export const skillService = {
  async getMounts(port: number): Promise<MountPool[]> {
    return get<MountPool[]>(port, '/web/settings/mounts');
  },

  async addMount(port: number, alias: string, path: string): Promise<void> {
    await post(port, '/web/settings/mounts/add', { alias, path });
  },

  async removeMount(port: number, alias: string): Promise<void> {
    await post(port, '/web/settings/mounts/remove', { alias });
  },

  async refreshMount(port: number, alias: string): Promise<void> {
    await post(port, '/desktop/settings/mounts/refresh', { alias });
  },

  async getPoolSkills(port: number, alias: string): Promise<PoolSkill[]> {
    const skills = await get<BackendPoolSkill[]>(port, '/web/settings/mounts/content', {
      alias,
      type: 'SKILLS',
    });
    return skills.map(skill => ({
      name: skill.name,
      description: skill.description,
      poolAlias: alias,
      path: skill.realPath,
    }));
  },

  async removePoolSkill(port: number, alias: string, skillName: string): Promise<void> {
    await post(port, '/web/settings/mounts/skills/remove', { alias, skillName });
  },

  async getMarkets(port: number): Promise<MarketInfo[]> {
    return get<MarketInfo[]>(port, '/web/settings/skills/markets');
  },

  async marketBrowse(port: number, marketName: string, action: string, query?: string, limit?: number): Promise<MarketItem[]> {
    const params: Record<string, string> = { action, marketName };
    if (query) params.q = query;
    if (limit) params.limit = String(limit);
    return get<MarketItem[]>(port, '/web/settings/skills/proxy', params);
  },

  async installSkill(port: number, slug: string, marketName: string, mountAlias?: string): Promise<void> {
    const body: Record<string, string> = { slug, marketName };
    if (mountAlias) body.mountAlias = mountAlias;
    await post(port, '/web/settings/skills/install', body);
  },
};

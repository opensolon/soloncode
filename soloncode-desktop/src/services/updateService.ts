import { invoke } from '@tauri-apps/api/core';

export interface UpdateInfo {
  currentDesktopVersion: string;
  currentBackendVersion: string | null;
  latestDesktopVersion: string | null;
  latestBackendVersion: string | null;
  latestDesktopReleaseTag: string | null;
  desktopDownloadUrl: string | null;
  backendUpdateUrl: string;
  desktopUpdateAvailable: boolean;
  backendUpdateAvailable: boolean;
}

function normalizeBackendPort(backendPort?: number | null): number | null {
  return typeof backendPort === 'number' && backendPort > 0 ? backendPort : null;
}

export function formatDesktopVersion(version: string): string {
  const normalized = version.trim();
  if (!normalized) return '';
  return /^v/i.test(normalized) ? normalized : `v${normalized}`;
}

export const updateService = {
  async checkForUpdates(backendPort?: number | null): Promise<UpdateInfo> {
    return await invoke<UpdateInfo>('check_updates', {
      backendPort: normalizeBackendPort(backendPort),
    });
  },

  async installUpdates(backendPort?: number | null): Promise<string> {
    return await invoke<string>('install_updates', {
      backendPort: normalizeBackendPort(backendPort),
    });
  },
};

export default updateService;

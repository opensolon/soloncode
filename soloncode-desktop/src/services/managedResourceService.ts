import { invoke } from '@tauri-apps/api/core';

export type ManagedResourceKind = 'skill' | 'agent';

export interface ManagedResourceResult {
  name: string;
  path: string;
}

export function getManagedResourceNameError(value: string): string {
  const name = value.trim();
  if (!name) return '名称不能为空';
  if (Array.from(name).length > 64) return '名称不能超过 64 个字符';
  if (!/^[\p{L}\p{N}_-]+$/u.test(name)) return '只能包含文字、数字、短横线和下划线';
  const upper = name.toUpperCase();
  if (/^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$/.test(upper)) return '该名称是系统保留名称';
  return '';
}

export const managedResourceService = {
  rename(path: string, kind: ManagedResourceKind, newName: string): Promise<ManagedResourceResult> {
    return invoke<ManagedResourceResult>('rename_managed_resource', {
      resourcePath: path,
      kind,
      newName: newName.trim(),
    });
  },

  copy(path: string, kind: ManagedResourceKind): Promise<ManagedResourceResult> {
    return invoke<ManagedResourceResult>('copy_managed_resource', { resourcePath: path, kind });
  },

  delete(path: string, kind: ManagedResourceKind): Promise<void> {
    return invoke<void>('delete_managed_resource', { resourcePath: path, kind });
  },
};

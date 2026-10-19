import assert from 'node:assert/strict';
import test from 'node:test';
import { formatDesktopVersion } from '../src/services/updateService.ts';

test('desktop version is displayed with exactly one v prefix', () => {
  assert.equal(formatDesktopVersion('2026.10.19'), 'v2026.10.19');
  assert.equal(formatDesktopVersion('26.8.11'), 'v26.8.11');
  assert.equal(formatDesktopVersion('v26.8.11'), 'v26.8.11');
});

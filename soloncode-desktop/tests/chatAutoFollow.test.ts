import assert from 'node:assert/strict';
import test from 'node:test';
import { resolveChatAutoFollow } from '../src/utils/chatAutoFollow.ts';

test('keeps following the bottom while a running response grows in place', () => {
  assert.deepEqual(
    resolveChatAutoFollow({ running: true, atBottom: false }),
    { enabled: true, behavior: 'auto' },
  );
});

test('allows leaving the bottom after the response stops running', () => {
  assert.deepEqual(
    resolveChatAutoFollow({ running: false, atBottom: false }),
    { enabled: false, behavior: false },
  );
});

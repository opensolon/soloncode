import assert from 'node:assert/strict';
import test from 'node:test';
import { resolveChatAutoFollow } from '../src/utils/chatAutoFollow.ts';

test('keeps following the bottom while running if the user stays at the bottom', () => {
  assert.deepEqual(
    resolveChatAutoFollow({ atBottom: true }),
    { enabled: true, behavior: 'auto' },
  );
});

test('stops pulling the scrollbar to the bottom while running once the user scrolls away', () => {
  assert.deepEqual(
    resolveChatAutoFollow({ atBottom: false }),
    { enabled: false, behavior: false },
  );
});

test('does not follow after the response stops running and the user is away from the bottom', () => {
  assert.deepEqual(
    resolveChatAutoFollow({ atBottom: false }),
    { enabled: false, behavior: false },
  );
});

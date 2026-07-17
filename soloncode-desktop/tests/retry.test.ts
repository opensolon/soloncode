import assert from 'node:assert/strict';
import test from 'node:test';
import { withRetry } from '../src/utils/retry.ts';

test('retries a transient failure and returns the successful result', async () => {
  let attempts = 0;
  const result = await withRetry(async () => {
    attempts++;
    if (attempts < 3) throw new Error('temporary');
    return 'ok';
  }, [0, 0]);
  assert.equal(result, 'ok');
  assert.equal(attempts, 3);
});

test('stops retrying after the configured attempts', async () => {
  let attempts = 0;
  await assert.rejects(() => withRetry(async () => {
    attempts++;
    throw new Error('persistent');
  }, [0, 0]), /persistent/);
  assert.equal(attempts, 3);
});

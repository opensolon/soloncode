import assert from 'node:assert/strict';
import test from 'node:test';
import {
  cronMatchesDate,
  getCronMinuteKey,
  getLatestCronRun,
} from '../src/utils/cron.ts';

test('cronMatchesDate matches a normal scheduled minute', () => {
  assert.equal(cronMatchesDate('0 10 * * *', new Date(2026, 6, 16, 10, 0, 30)), true);
  assert.equal(cronMatchesDate('0 10 * * *', new Date(2026, 6, 16, 10, 1, 0)), false);
});

test('getLatestCronRun recovers a minute missed by a delayed timer', () => {
  const result = getLatestCronRun(
    '0 10 * * *',
    new Date(2026, 6, 16, 9, 59, 40),
    new Date(2026, 6, 16, 10, 2, 10),
  );

  assert.ok(result);
  assert.equal(result.getHours(), 10);
  assert.equal(result.getMinutes(), 0);
});

test('getLatestCronRun collapses several missed recurring minutes to the latest run', () => {
  const result = getLatestCronRun(
    '* * * * *',
    new Date(2026, 6, 16, 10, 0, 10),
    new Date(2026, 6, 16, 10, 4, 45),
  );

  assert.ok(result);
  assert.equal(getCronMinuteKey(result), getCronMinuteKey(new Date(2026, 6, 16, 10, 4, 0)));
});

test('getLatestCronRun does not repeat the minute containing the previous check', () => {
  const result = getLatestCronRun(
    '* * * * *',
    new Date(2026, 6, 16, 10, 0, 10),
    new Date(2026, 6, 16, 10, 0, 50),
  );

  assert.equal(result, null);
});

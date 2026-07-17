import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildAutomationPlanningPrompt,
  parseGeneratedAutomationPlan,
} from '../src/utils/automationPlan.ts';

test('planning prompt asks the model to understand and enrich the work', () => {
  const prompt = buildAutomationPlanningPrompt('每分钟说一次你好');
  assert.match(prompt, /理解用户真正要重复执行的工作/);
  assert.match(prompt, /"prompt":"请说你好，返回你好"/);
  assert.match(prompt, /用户需求：\n每分钟说一次你好/);
});

test('accepts a valid generated automation plan', () => {
  assert.deepEqual(parseGeneratedAutomationPlan(JSON.stringify({
    title: '每分钟说你好',
    prompt: '请说你好，返回你好',
    scheduleEnabled: true,
    cron: '* * * * *',
  })), {
    title: '每分钟说你好',
    prompt: '请说你好，返回你好',
    scheduleEnabled: true,
    cron: '* * * * *',
  });
});

test('accepts JSON fenced by the model', () => {
  const plan = parseGeneratedAutomationPlan('```json\n{"title":"日报","prompt":"请汇总今日工作并返回摘要","scheduleEnabled":true,"cron":"0 18 * * 1-5"}\n```');
  assert.equal(plan.prompt, '请汇总今日工作并返回摘要');
  assert.equal(plan.cron, '0 18 * * 1-5');
});

test('rejects malformed or unsafe generated plans', () => {
  assert.throws(() => parseGeneratedAutomationPlan('不是 JSON'), /有效的自动化 JSON/);
  assert.throws(() => parseGeneratedAutomationPlan('{"title":"任务","prompt":"执行","scheduleEnabled":true,"cron":"bad"}'), /Cron 无效/);
});

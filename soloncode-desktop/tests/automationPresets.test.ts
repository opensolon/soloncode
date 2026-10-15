import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { AUTOMATION_PROMPT_PRESETS, buildAutomationPresetRequest } from '../src/utils/automationPresets.ts';
import { getCronValidationError } from '../src/utils/cron.ts';

test('offers the same four task presets as Web with valid desktop schedules', () => {
  const web = JSON.parse(fs.readFileSync(new URL('../../soloncode-cli/src/main/resources/static/i18n/zh-CN.json', import.meta.url), 'utf8'));
  const keys = ['autoFix', 'codeReview', 'depSecurity', 'testCoverage'];
  assert.deepEqual(AUTOMATION_PROMPT_PRESETS.map(item => item.id), ['auto-fix', 'code-review', 'dep-security', 'test-coverage']);
  AUTOMATION_PROMPT_PRESETS.forEach((preset, index) => {
    assert.equal(preset.title, web[`loop.tpl.${keys[index]}.name`]);
    assert.equal(getCronValidationError(preset.cron), null);
    assert.match(preset.prompt, /主动.*(读取|检查)/);
    assert.match(preset.prompt, /不重复询问/);
    assert.match(preset.prompt, /验证/);
  });
});

test('keeps review and dependency inspection read-only and repair tasks bounded', () => {
  const byId = Object.fromEntries(AUTOMATION_PROMPT_PRESETS.map(preset => [preset.id, preset]));
  assert.match(byId['code-review'].prompt, /只读/);
  assert.match(byId['code-review'].prompt, /昨天.*今天/);
  assert.match(byId['dep-security'].prompt, /只读/);
  assert.match(byId['dep-security'].prompt, /无法核实/);
  assert.match(byId['auto-fix'].prompt, /不修改.*(断言|测试).*掩盖/);
  assert.match(byId['test-coverage'].prompt, /不.*(降低|删除).*覆盖率/);
  assert.equal(byId['auto-fix'].scheduleEnabled, false);
  assert.equal(byId['test-coverage'].scheduleEnabled, false);
  assert.equal(byId['code-review'].cron, '0 9 * * *');
  assert.equal(byId['dep-security'].cron, '0 * * * *');
});

test('prefills an editable creation request that preserves the complete task and scheduling intent', () => {
  for (const preset of AUTOMATION_PROMPT_PRESETS) {
    const request = buildAutomationPresetRequest(preset);
    assert.ok(request.includes(preset.prompt));
    assert.ok(request.includes(preset.title));
    assert.match(request, /创建.*不立即执行/);
    if (preset.scheduleEnabled) assert.ok(request.includes(preset.cron));
    else assert.match(request, /不启用定时/);
  }
});

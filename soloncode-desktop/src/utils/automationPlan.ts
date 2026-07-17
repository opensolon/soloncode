import { getCronValidationError } from './cron.ts';

export interface GeneratedAutomationPlan {
  title: string;
  prompt: string;
  scheduleEnabled: boolean;
  cron: string;
}

function stripJsonFence(content: string): string {
  const trimmed = content.trim();
  const fenced = trimmed.match(/^```(?:json)?\s*\r?\n([\s\S]*?)\r?\n```$/i);
  return (fenced?.[1] || trimmed).trim();
}

export function parseGeneratedAutomationPlan(content: string): GeneratedAutomationPlan {
  let parsed: unknown;
  try {
    parsed = JSON.parse(stripJsonFence(content));
  } catch {
    throw new Error('模型未返回有效的自动化 JSON');
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('模型返回的自动化配置格式无效');
  }

  const value = parsed as Record<string, unknown>;
  const title = typeof value.title === 'string' ? value.title.trim() : '';
  const prompt = typeof value.prompt === 'string' ? value.prompt.trim() : '';
  const scheduleEnabled = value.scheduleEnabled;
  const cron = typeof value.cron === 'string' ? value.cron.trim().replace(/\s+/g, ' ') : '';

  if (!title || Array.from(title).length > 100) throw new Error('模型生成的任务名称无效');
  if (!prompt || prompt.length > 10000) throw new Error('模型生成的执行提示词无效');
  if (typeof scheduleEnabled !== 'boolean') throw new Error('模型未正确判断是否启用定时');
  const cronError = getCronValidationError(cron);
  if (cronError) throw new Error(`模型生成的 Cron 无效：${cronError}`);

  return { title, prompt, scheduleEnabled, cron };
}

export function buildAutomationPlanningPrompt(userRequest: string): string {
  return [
    '你是自动化任务规划器。请理解用户真正要重复执行的工作，并把简略需求补充成可独立、稳定执行的提示词。',
    '用户输入同时可能包含执行频率和工作内容。频率只写入 Cron，不要保留在执行提示词中。',
    '执行提示词必须明确动作、目标和期望返回结果；不要只是机械截取或改写原句。',
    '如果用户没有表达定时要求，将 scheduleEnabled 设为 false，cron 使用默认值 "0 9 * * *"。',
    'Cron 必须是 5 段格式：分钟 小时 日 月 星期。',
    '只输出一个 JSON 对象，不要输出 Markdown、解释或额外文字，结构必须严格如下：',
    '{"title":"简洁的任务名称","prompt":"补充完整的实际执行提示词","scheduleEnabled":true,"cron":"* * * * *"}',
    '示例：用户输入“每分钟说一次你好”时，应输出：',
    '{"title":"每分钟说你好","prompt":"请说你好，返回你好","scheduleEnabled":true,"cron":"* * * * *"}',
    '',
    '用户需求：',
    userRequest.trim(),
  ].join('\n');
}

interface ParsedCronField {
  values: Set<number>;
  wildcard: boolean;
}

interface ParsedCronExpression {
  minute: ParsedCronField;
  hour: ParsedCronField;
  dayOfMonth: ParsedCronField;
  month: ParsedCronField;
  dayOfWeek: ParsedCronField;
}

const MAX_CRON_LENGTH = 100;

function parseInteger(value: string, min: number, max: number): number {
  if (!/^\d+$/.test(value)) throw new Error('Cron 只能包含整数、范围、列表和步长');
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) {
    throw new Error(`Cron 数值必须在 ${min}-${max} 之间`);
  }
  return parsed;
}

function parseField(source: string, min: number, max: number, normalize?: (value: number) => number): ParsedCronField {
  const values = new Set<number>();
  const tokens = source.split(',');
  if (tokens.some(token => !token)) throw new Error('Cron 列表项不能为空');

  for (const token of tokens) {
    const stepParts = token.split('/');
    if (stepParts.length > 2) throw new Error('Cron 步长格式不正确');
    const base = stepParts[0];
    const step = stepParts[1] === undefined ? 1 : parseInteger(stepParts[1], 1, max - min + 1);
    let start: number;
    let end: number;

    if (base === '*') {
      start = min;
      end = max;
    } else if (base.includes('-')) {
      const range = base.split('-');
      if (range.length !== 2) throw new Error('Cron 范围格式不正确');
      start = parseInteger(range[0], min, max);
      end = parseInteger(range[1], min, max);
      if (start > end) throw new Error('Cron 范围起始值不能大于结束值');
    } else {
      start = parseInteger(base, min, max);
      end = stepParts[1] === undefined ? start : max;
    }

    for (let value = start; value <= end; value += step) {
      values.add(normalize ? normalize(value) : value);
    }
  }

  const expectedSize = normalize ? max - min : max - min + 1;
  return { values, wildcard: values.size === expectedSize };
}

export function parseCronExpression(expression: string): ParsedCronExpression {
  const normalized = expression.trim().replace(/\s+/g, ' ');
  if (!normalized) throw new Error('请输入 Cron 表达式');
  if (normalized.length > MAX_CRON_LENGTH) throw new Error(`Cron 表达式不能超过 ${MAX_CRON_LENGTH} 个字符`);
  const fields = normalized.split(' ');
  if (fields.length !== 5) throw new Error('Cron 必须包含 5 段：分钟 小时 日 月 星期');

  return {
    minute: parseField(fields[0], 0, 59),
    hour: parseField(fields[1], 0, 23),
    dayOfMonth: parseField(fields[2], 1, 31),
    month: parseField(fields[3], 1, 12),
    dayOfWeek: parseField(fields[4], 0, 7, value => value === 7 ? 0 : value),
  };
}

export function getCronValidationError(expression: string): string | null {
  try {
    parseCronExpression(expression);
    return null;
  } catch (error) {
    return error instanceof Error ? error.message : 'Cron 表达式无效';
  }
}

function parsedCronMatchesDate(parsed: ParsedCronExpression, date: Date): boolean {
  if (!parsed.minute.values.has(date.getMinutes())) return false;
  if (!parsed.hour.values.has(date.getHours())) return false;
  if (!parsed.month.values.has(date.getMonth() + 1)) return false;

  const dayOfMonthMatches = parsed.dayOfMonth.values.has(date.getDate());
  const dayOfWeekMatches = parsed.dayOfWeek.values.has(date.getDay());
  if (!parsed.dayOfMonth.wildcard && !parsed.dayOfWeek.wildcard) {
    return dayOfMonthMatches || dayOfWeekMatches;
  }
  return dayOfMonthMatches && dayOfWeekMatches;
}

export function cronMatchesDate(expression: string, date: Date): boolean {
  return parsedCronMatchesDate(parseCronExpression(expression), date);
}

export function getCronMinuteKey(date: Date): string {
  return new Date(Math.floor(date.getTime() / 60_000) * 60_000).toISOString();
}

/**
 * 返回检查间隔内最近一次应触发的时间。
 *
 * 渲染进程在窗口最小化、系统休眠时可能错过 setInterval 的精确分钟，
 * 因此调度器恢复后需要补偿检查，而不是只匹配恢复时的当前分钟。
 */
export function getLatestCronRun(
  expression: string,
  afterExclusive: Date,
  throughInclusive = new Date(),
  maxLookbackMinutes = 7 * 24 * 60,
): Date | null {
  const afterTime = afterExclusive.getTime();
  const throughTime = throughInclusive.getTime();
  if (!Number.isFinite(afterTime) || !Number.isFinite(throughTime) || throughTime <= afterTime) return null;

  const parsed = parseCronExpression(expression);
  const latestMinute = Math.floor(throughTime / 60_000) * 60_000;
  const firstMinuteAfter = Math.floor(afterTime / 60_000) * 60_000 + 60_000;
  const lookback = Math.max(1, Math.floor(maxLookbackMinutes));
  const earliestMinute = Math.max(firstMinuteAfter, latestMinute - (lookback - 1) * 60_000);

  for (let time = latestMinute; time >= earliestMinute; time -= 60_000) {
    const candidate = new Date(time);
    if (parsedCronMatchesDate(parsed, candidate)) return candidate;
  }
  return null;
}

export function getNextCronRun(expression: string, after = new Date()): Date | null {
  const parsed = parseCronExpression(expression);
  const candidate = new Date(Math.floor(after.getTime() / 60_000) * 60_000 + 60_000);
  const maxChecks = 366 * 24 * 60;
  for (let index = 0; index < maxChecks; index++) {
    if (parsedCronMatchesDate(parsed, candidate)) return candidate;
    candidate.setMinutes(candidate.getMinutes() + 1);
  }
  return null;
}

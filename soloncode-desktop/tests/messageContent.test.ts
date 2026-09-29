import assert from 'node:assert/strict';
import test from 'node:test';
import { buildUserMessageContents, isSafeImageDataUrl, mergeStreamingMessage, sumReportedTokens } from '../src/utils/messageContent.ts';
import type { Message } from '../src/types/index.ts';

const PNG_DATA_URL = 'data:image/png;base64,iVBORw0KGgo=';

test('keeps image attachments alongside user text', () => {
  const contents = buildUserMessageContents('请分析这张图', [
    { name: 'chart.png', type: 'image', content: PNG_DATA_URL },
  ]);

  assert.deepEqual(contents, [
    { type: 'IMAGE', text: PNG_DATA_URL, name: 'chart.png' },
    { type: 'TEXT', text: '请分析这张图' },
  ]);
});

test('does not turn arbitrary data URLs into rendered image content', () => {
  assert.equal(isSafeImageDataUrl('data:text/html;base64,PHNjcmlwdD4='), false);
  assert.equal(isSafeImageDataUrl('https://example.com/image.png'), false);

  const contents = buildUserMessageContents('hello', [
    { name: 'unsafe.html', type: 'image', content: 'data:text/html;base64,PHNjcmlwdD4=' },
  ]);
  assert.deepEqual(contents, [{ type: 'TEXT', text: 'hello' }]);
});

test('keeps file metadata without persisting uploaded file contents', () => {
  const contents = buildUserMessageContents('请分析附件', [
    { name: 'report.pdf', type: 'file', content: 'large-base64-payload', size: 2048 },
  ]);

  assert.deepEqual(contents, [
    { type: 'FILE', text: 'report.pdf', name: 'report.pdf', size: 2048 },
    { type: 'TEXT', text: '请分析附件' },
  ]);
  assert.equal(JSON.stringify(contents).includes('large-base64-payload'), false);
});

test('late streaming snapshot cannot erase final response metadata', () => {
  const finalMessage: Message = {
    id: 7,
    role: 'ASSISTANT',
    timestamp: '19:13:21',
    contents: [{ type: 'TEXT', text: '完成' }],
    metadata: { modelName: 'claude-opus-4-6', totalTokens: 321, elapsedMs: 4500 },
  };
  const staleSnapshot: Message = {
    id: 7,
    role: 'ASSISTANT',
    timestamp: '19:13:22',
    contents: [{ type: 'TEXT', text: '完成' }],
  };

  assert.deepEqual(mergeStreamingMessage(finalMessage, staleSnapshot), {
    ...staleSnapshot,
    timestamp: '19:13:21',
    metadata: finalMessage.metadata,
  });
});

test('reported zero token usage is not replaced with a text-length estimate', () => {
  const messages: Message[] = [{
    id: 8,
    role: 'ASSISTANT',
    timestamp: '19:13:23',
    contents: [{ type: 'TEXT', text: '这段回复有内容，但模型明确报告本轮 token 为零' }],
    metadata: { totalTokens: 0 },
  }];
  assert.equal(sumReportedTokens(messages), 0);
});

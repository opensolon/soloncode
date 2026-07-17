import assert from 'node:assert/strict';
import test from 'node:test';
import { isTodoToolName } from '../src/utils/todoTools.ts';

test('recognizes TodoRead and TodoWrite tool-name variants', () => {
  for (const name of ['TodoRead', 'todo_read', 'todo-read', 'TODO WRITE', 'todowrite']) {
    assert.equal(isTodoToolName(name), true, name);
  }
});

test('does not hide unrelated tools', () => {
  for (const name of [undefined, '', 'Read', 'Write', 'TodoList']) {
    assert.equal(isTodoToolName(name), false, String(name));
  }
});

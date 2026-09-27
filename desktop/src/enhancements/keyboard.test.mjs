import test from 'node:test';
import assert from 'node:assert/strict';
const { mapKey } = await import('./keyboard.mjs');

test('printables type as-is; shift passes through the uppercase char', () => {
  assert.deepEqual(mapKey({ key: 'a' }), { kind: 'text', text: 'a' });
  assert.deepEqual(mapKey({ key: 'A', shiftKey: true }), { kind: 'text', text: 'A' });
  assert.deepEqual(mapKey({ key: 'ش' }), { kind: 'text', text: 'ش' });
  assert.deepEqual(mapKey({ key: ' ' }), { kind: 'text', text: ' ' });
});

test('modifier-held printables are dropped, never sent bare', () => {
  for (const ev of [
    { key: 'a', ctrlKey: true },
    { key: 'c', metaKey: true },
    { key: 'v', altKey: true },
    { key: 'ش', ctrlKey: true },
  ]) {
    assert.deepEqual(mapKey(ev), { kind: 'drop', reason: 'modifier' }, JSON.stringify(ev));
  }
});

test('known navigation keys map to phone names', () => {
  const pairs = [
    ['Enter', 'ENTER'], ['Backspace', 'BACKSPACE'], ['Escape', 'BACK'],
    ['Tab', 'TAB'], ['ArrowUp', 'DPAD_UP'], ['ArrowLeft', 'DPAD_LEFT'],
    ['Home', 'MOVE_HOME'], ['End', 'MOVE_END'], ['PageDown', 'PAGE_DOWN'],
  ];
  for (const [k, named] of pairs) {
    assert.deepEqual(mapKey({ key: k }), { kind: 'key', key: named }, k);
  }
});

test('shift+tab is dropped: bare TAB moves focus the wrong way', () => {
  assert.deepEqual(mapKey({ key: 'Tab', shiftKey: true }), { kind: 'drop', reason: 'modifier' });
});

test('unknown keys are dropped instead of being sent to the phone', () => {
  for (const k of ['F5', 'Insert', 'CapsLock', 'AudioVolumeUp', 'Unidentified', '']) {
    assert.deepEqual(mapKey({ key: k }), { kind: 'drop', reason: 'unknown' }, k);
  }
});

import test from 'node:test';
import assert from 'node:assert/strict';
const logic = await import('./logic.mjs').catch(() => ({}));
test('privacy blocks apps and removes both title and body before retention', () => {
  assert.equal(typeof logic.filterNotification, 'function');
  const n = {app:'chat.app', title:'Secret', body:'Code 123', id:4};
  assert.equal(logic.filterNotification(n, {blockedApps:['chat.app']}), null);
  assert.equal(logic.filterNotification(n, {notifications:false}), null);
  assert.deepEqual(logic.filterNotification(n, {hidePreview:true}), {...n,title:'',body:''});
  assert.deepEqual(logic.filterNotification(n, {}), n);
});
test('transfer queue keeps exact order and is strictly sequential', () => {
  const q = logic.createTransferQueue();
  q.enqueue('a'); q.enqueue('b'); q.enqueue('c');
  assert.equal(q.active, null);
  assert.deepEqual(q.queuedIds(), ['a','b','c']);
  q.start();
  assert.equal(q.active, 'a');
  assert.equal(q.start(), false); // no double-start
  q.start();
  assert.equal(q.active, 'a');
  assert.equal(q.start(), false); // no double-start while 'a' is active
  assert.equal(q.active, 'a');
  q.done('a'); q.start();
  assert.equal(q.active, 'b');
  q.cancel('c'); // cancel a queued item
  assert.deepEqual(q.queuedIds(), []);
  q.fail(q.active, 'read error');
  q.start();
  assert.equal(q.active, null);
  assert.deepEqual(q.queuedIds(), []);
});
test('cancelling queued items works; active needs backend abort', () => {
  const q = logic.createTransferQueue();
  q.enqueue('a'); q.enqueue('b');
  q.start();
  assert.equal(q.cancel('b'), true, 'queued item can be cancelled');
  assert.equal(q.cancel('a'), false, 'active item is not silently cancelled');
  assert.deepEqual(q.queuedIds(), []);
});
test('health distinguishes unreported, no frames, stalled, idle and unavailable', () => {
  assert.equal(typeof logic.mirrorHealth, 'function');
  const h = logic.mirrorHealth;
  assert.equal(h({connected:false}, null, 10000), 'disconnected');
  assert.equal(h({connected:true}, null, 10000), 'unknown');
  assert.equal(h({connected:true,mirror:true}, null, 10000), 'waiting');
  assert.equal(h({connected:true,mirror:true}, 1000, 10000), 'stalled');
  assert.equal(h({connected:true,mirror:true}, 9500, 10000), 'live');
  assert.equal(h({connected:true,mirror:false}, 9500, 10000), 'idle');
  assert.equal(h({connected:true}, null, 10000, false), 'unavailable');
});

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
test('per-app mute blocks forever; snooze lifts at its deadline', () => {
  assert.equal(typeof logic.isAppSuppressed, 'function');
  const is = logic.isAppSuppressed;
  // never muted, never snoozed
  assert.equal(is('chat.app', [], {}, 1000), false);
  // hard mute wins regardless of a stale snooze
  assert.equal(is('chat.app', ['chat.app'], {}, 1000), true);
  assert.equal(is('chat.app', ['chat.app'], {other:9999}, 1000), true);
  // snooze active before its deadline, released after
  assert.equal(is('chat.app', [], {['chat.app']: 5000}, 4000), true);
  assert.equal(is('chat.app', [], {['chat.app']: 5000}, 5000), false);
  assert.equal(is('chat.app', [], {['chat.app']: 5000}, 6000), false);
  // exact-name match only, no substring bleed
  assert.equal(is('chat', ['chat.app'], {}, 1000), false);
  assert.equal(is('chat.app.extra', ['chat.app'], {}, 1000), false);
  // garbage persisted shapes must not throw
  assert.equal(is('chat.app', null, null, 1000), false);
  assert.equal(is('chat.app', undefined, {['chat.app']: 'soon'}, 1000), false);
});

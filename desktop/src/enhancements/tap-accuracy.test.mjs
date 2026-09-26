import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
// Side taps were inaccurate: norm() measured the <img> element box, but with
// object-fit:contain the painted bitmap is letterboxed INSIDE that box, so a
// wide canvas maps side clicks into the black bars. Tap math must use the
// painted (content-box) rect of the image, not the element box.
test('tap mapping uses painted letterbox rect, not img element box', () => {
  const app = readFileSync(new URL('../App.tsx', import.meta.url), 'utf8');
  const norm = app.slice(app.indexOf('const norm = (ev: React.PointerEvent)'), app.indexOf('const onTap ='));
  assert.ok(norm.includes('naturalWidth'), 'norm must account for intrinsic image size');
  assert.ok(!norm.includes('?? (ev.currentTarget'), 'element box fallback still used for mapping');
});

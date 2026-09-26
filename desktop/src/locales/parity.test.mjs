import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

test('Persian supplies every English locale key used by the desktop', () => {
  const en = JSON.parse(readFileSync(new URL('./en.json', import.meta.url), 'utf8'));
  const fa = JSON.parse(readFileSync(new URL('./fa.json', import.meta.url), 'utf8'));
  for (const [section, entries] of Object.entries(en)) {
    for (const key of Object.keys(entries)) {
      assert.equal(typeof fa[section]?.[key], 'string', `Missing Persian translation: ${section}.${key}`);
      assert.ok(fa[section][key].trim(), `Empty Persian translation: ${section}.${key}`);
    }
  }
});

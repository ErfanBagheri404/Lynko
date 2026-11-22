import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';

const en = JSON.parse(readFileSync(new URL('./en.json', import.meta.url), 'utf8'));
const fa = JSON.parse(readFileSync(new URL('./fa.json', import.meta.url), 'utf8'));
const zh = JSON.parse(readFileSync(new URL('./zh.json', import.meta.url), 'utf8'));

for (const [lang, bundle] of [['Persian', fa], ['Chinese', zh]]) {
  test(`${lang} supplies every English locale key used by the desktop`, () => {
    for (const [section, entries] of Object.entries(en)) {
      for (const key of Object.keys(entries)) {
        assert.equal(typeof bundle[section]?.[key], 'string', `Missing ${lang} translation: ${section}.${key}`);
        assert.ok(bundle[section][key].trim(), `Empty ${lang} translation: ${section}.${key}`);
      }
    }
  });
}

// The phone ships its own bundle from android/app/src/main/assets/locales; keep
// it in lockstep with the phone's own en.json so a key added to one is never
// missing in the other.
test('phone bundle: Persian and Chinese supply every English phone key', () => {
  const phoneEn = JSON.parse(
    readFileSync(new URL('../../../android/app/src/main/assets/locales/en.json', import.meta.url), 'utf8'),
  );
  for (const lang of ['fa', 'zh']) {
    const bundle = JSON.parse(
      readFileSync(new URL(`../../../android/app/src/main/assets/locales/${lang}.json`, import.meta.url), 'utf8'),
    );
    for (const [section, entries] of Object.entries(phoneEn)) {
      for (const key of Object.keys(entries)) {
        assert.equal(typeof bundle[section]?.[key], 'string', `Missing phone ${lang} translation: ${section}.${key}`);
        assert.ok(bundle[section][key].trim(), `Empty phone ${lang} translation: ${section}.${key}`);
      }
    }
  }
});

// The phone UI reads the JSON bundle for most text, but a few strings live in
// Android res (settings labels, privacy policy). Each locale must translate
// every string the default values/ folder declares.
test('every Android res locale translates all default strings', () => {
  const resDir = new URL('../../../android/app/src/main/res/', import.meta.url);
  const read = (dir) => {
    const names = new Set();
    for (const file of readdirSync(new URL(dir, resDir))) {
      if (!file.endsWith('.xml')) continue;
      const xml = readFileSync(new URL(`${dir}${file}`, resDir), 'utf8');
      for (const m of xml.matchAll(/<string name="([^"]+)"/g)) names.add(m[1]);
    }
    return names;
  };
  const defaults = read('values/');
  for (const dir of ['values-fa/', 'values-b+zh+Hans/']) {
    const translated = read(dir);
    for (const name of defaults) {
      assert.ok(translated.has(name), `Missing ${dir} string: ${name}`);
    }
  }
});

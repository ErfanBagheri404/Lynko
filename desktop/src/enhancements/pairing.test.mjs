import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
test('USB PIN dialog waits for a live phone-service check',()=>{
 const app=readFileSync(new URL('../App.tsx',import.meta.url),'utf8');
 const pair=app.slice(app.indexOf('  const pair = async'),app.indexOf('  const submitPin ='));
 assert.ok(pair.includes('check_pairing_ready'), 'Pair opens PIN without probing phone service');
 assert.ok(pair.indexOf('check_pairing_ready') < pair.indexOf('setPairingDevice(d)'));
});

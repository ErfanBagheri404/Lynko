import { readFileSync } from 'node:fs';
const src = readFileSync('src/App.tsx', 'utf8');
// Verify ripple is completely removed: no setRipples, no ripples state, no tap-ripple references
const issues = [];
if (src.includes('setRipples')) issues.push('setRipples still referenced');
if (src.includes('const [ripples')) issues.push('ripples state still declared');
if (src.includes('ripples.map')) issues.push('ripples.map still in JSX');
if (src.includes('tap-ripple')) issues.push('tap-ripple still referenced');
if (issues.length) {
  console.error('FAIL: ripple not fully removed:', issues);
  process.exit(1);
}
console.log('PASS: tap ripple fully removed');

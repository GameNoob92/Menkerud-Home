// Runs every *.test.js in this folder with the current node, in name order, and stops at the first failure.
'use strict';
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const files = fs.readdirSync(__dirname).filter(f => f.endsWith('.test.js')).sort();
for (const f of files) {
  console.log('\n# ' + f);
  const r = spawnSync(process.execPath, [path.join(__dirname, f)], { stdio: 'inherit' });
  if (r.status !== 0) { console.error('\nFAILED: ' + f); process.exit(r.status || 1); }
}
console.log('\nALL TESTS PASSED (' + files.length + ' files)');

// Extracts the inline <script> from index.html (and ring.html) and syntax-checks it with node --check.
const fs = require('fs'), path = require('path'), { execFileSync } = require('child_process'), os = require('os');
let failed = false;
for (const f of ['index.html', 'ring.html']) {
  const html = fs.readFileSync(path.join(__dirname, '..', f), 'utf8');
  const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
  const tmp = path.join(os.tmpdir(), f.replace('.html', '.inline.js'));
  fs.writeFileSync(tmp, scripts[scripts.length - 1]);
  try { execFileSync(process.execPath, ['--check', tmp], { stdio: 'inherit' }); console.log('ok -', f); }
  catch (e) { failed = true; }
}
for (const f of ['config.js']) {
  try { execFileSync(process.execPath, ['--check', path.join(__dirname, '..', f)], { stdio: 'inherit' }); console.log('ok -', f); }
  catch (e) { failed = true; }
}
process.exit(failed ? 1 : 0);
